package com.example.newdrawingapp.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import com.example.newdrawingapp.ClientActivity
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class DrawingSocketManager(private val context: Context) {
    companion object {
        private const val TAG = "DrawingSocketManager"
        private const val DEFAULT_PORT = 8888
        private const val DISCONNECT_MESSAGE = "CLIENT_DISCONNECT"
        private const val IMAGE_RECEIVED_MESSAGE = "IMAGE_RECEIVED_CLIENT"
        // Rango de puertos y descubrimiento para el MODO V2 (pizarra = servidor).
        private const val PORT_RANGE_START = 8888
        private const val PORT_RANGE_END = 8988
        private const val DISCOVERY_PORT = 8889
        private const val BROADCAST_MESSAGE = "DRAWING_APP_DISCOVERY"
        private const val SERVER_IDENTIFIER = "DRAWING_APP_SERVER"
        // Timeout de lectura del socket: hace la lectura interrumpible (los
        // lectores viejos no quedan bloqueados para siempre) y da el tick del
        // watchdog de entrada. NO es fatal por sí solo (ver startReceiving).
        private const val SO_TIMEOUT_MS = 5000
        // Si el emisor manda keepalives (frames tamaño 0 cada 2s) y pasan más
        // de estos ms sin recibir NADA, la conexión se da por muerta.
        private const val RX_DEAD_MS = 8000L
    }

    // ── Modo V2: la pizarra actúa como SERVIDOR de red ──────────────────────
    @Volatile private var serverSocket: ServerSocket? = null
    private var serverLoopJob: Job? = null
    private var discoveryResponderJob: Job? = null
    private var heartbeatJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    @Volatile private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private var receiveJob: Job? = null
    private var autoConnectJob: Job? = null
    private var isConnecting = false
    private var lastConnectedAddress: Pair<String, Int>? = null

    // 输出队列：所有需要发往服务端的消息（ACK、笔划数据）统一经此队列发出，避免并发写 socket
    private val outputQueue = ConcurrentLinkedQueue<String>()
    private var outputSendJob: Job? = null

    // 笔划点临时缓冲（仅 UI 线程访问，无需同步）
    private val strokePendingPoints = StringBuilder()
    private var strokePendingCount = 0
    private val STROKE_BATCH_SIZE = 12  // 每 12 个点打包一次发送
    
    sealed class ConnectionState {
        object Connected : ConnectionState()
        object Disconnected : ConnectionState()
        object Searching : ConnectionState()
        object Connecting : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    fun startAutoConnect() {
        autoConnectJob?.cancel()
        autoConnectJob = scope.launch {
            while (isActive) {
                try {
                    if (!isConnecting && _connectionState.value !is ConnectionState.Connected) {
                        _connectionState.value = ConnectionState.Searching
                        val serverInfo = NetworkUtils.searchServer()

                        if (serverInfo != null) {
                            // Conectar SIEMPRE que no estemos conectados, aunque
                            // sea la misma dirección de antes: la comparación con
                            // lastConnectedAddress dejaba de reconectar tras una
                            // caída si el servidor seguía en la misma IP:puerto.
                            _connectionState.value = ConnectionState.Connecting
                            connect(serverInfo.first, serverInfo.second)
                            if (_connectionState.value !is ConnectionState.Connected) {
                                delay(2000)
                            }
                        } else {
                            delay(1000)
                        }
                    } else {
                        // 如果已连接或正在连接，等待更长时间
                        delay(3000)
                    }
                } catch (e: Exception) {
                    // La cancelación debe propagarse: el catch genérico la
                    // tragaba y el job viejo pisaba el estado del job nuevo.
                    if (e is CancellationException) throw e
                    Log.e("DrawingSocketManager", "Auto connect error: ${e.message}")
                    _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
                    delay(3000)
                }
            }
        }
    }

    // Detiene el modo cliente (búsqueda automática + conexión actual).
    fun stopClientMode() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        disconnect()
    }

    private fun connect(address: String, port: Int) {
        if (isConnecting) return
        isConnecting = true

        try {
            // Retirar el socket viejo del campo ANTES de cerrarlo: el lector
            // viejo que despierte ya no pasa la comparación de identidad y no
            // puede tumbar la conexión nueva.
            receiveJob?.cancel()
            val old = socket
            socket = null
            try { old?.close() } catch (_: Exception) {}

            socket = Socket(address, port).apply {
                keepAlive = true
                soTimeout = SO_TIMEOUT_MS
                tcpNoDelay = true  // 禁用 Nagle 算法
                sendBufferSize = 65536  // 增加发送缓冲区大小
                receiveBufferSize = 65536  // 增加接收缓冲区大小
            }
            _connectionState.value = ConnectionState.Connected
            lastConnectedAddress = Pair(address, port)
            startReceiving()
            startOutputSender()
            startHeartbeat()
        } catch (e: Exception) {
            handleError(e)
        } finally {
            isConnecting = false
        }
    }
    
    private fun startReceiving() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val currentSocket = socket ?: return@launch
            try {
                if (currentSocket.isClosed) {
                    throw IOException("Socket is closed")
                }

                // UN stream por conexión (ver NetworkUtils.openImageStream):
                // recrearlo por frame perdía bytes pre-leídos y desincronizaba.
                val dis = NetworkUtils.openImageStream(currentSocket)

                // Watchdog de entrada: el emisor (nuevo) manda un keepalive
                // (frame tamaño 0) cada 2s. Si los mandaba y dejan de llegar
                // > RX_DEAD_MS, la conexión está muerta (half-open silencioso).
                // Con emisores antiguos (sin keepalive) no se aplica.
                var lastRxMs = System.currentTimeMillis()
                var peerSendsKeepalive = false

                while (isActive && !currentSocket.isClosed && currentSocket === socket) {
                    val frame = try {
                        NetworkUtils.receiveImage(dis)
                    } catch (e: SocketTimeoutException) {
                        // Sin datos durante el soTimeout, en frontera de frame.
                        val silentMs = System.currentTimeMillis() - lastRxMs
                        if (peerSendsKeepalive && silentMs > RX_DEAD_MS) {
                            throw IOException("Peer silent ${silentMs}ms (keepalive lost)")
                        }
                        continue
                    }

                    lastRxMs = System.currentTimeMillis()

                    when (frame) {
                        is NetworkUtils.RxFrame.KeepAlive -> {
                            peerSendsKeepalive = true
                            continue
                        }
                        is NetworkUtils.RxFrame.License -> {
                            // El móvil nos manda el nombre de la licencia: guardarlo.
                            saveReceivedLicense(frame.name)
                            continue
                        }
                        is NetworkUtils.RxFrame.Image -> {
                            val imageData = frame.bytes
                            // Errores de UI/decodificación NO deben tumbar la conexión.
                            try {
                                withContext(Dispatchers.Main) {
                                    val clientActivity = context as? ClientActivity
                                    try {
                                        clientActivity?.updateImage(imageData)
                                        clientActivity?.forceBackgroundDetection()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "updateImage error: ${e.message}", e)
                                    }
                                    // 图片显示完成后，通过输出队列通知服务端（避免与笔划数据并发写 socket）
                                    outputQueue.offer("IMAGE_RECEIVED")
                                    Log.d(TAG, "Queued IMAGE_RECEIVED confirmation after display")
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.e(TAG, "Error displaying image: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Guarda de identidad: si ESTE socket ya fue sustituido por una
                // reconexión/adopción, su muerte tardía no debe tocar la
                // conexión nueva (antes le enviaba CLIENT_DISCONNECT y la cerraba).
                if (currentSocket === socket) {
                    handleError(e)
                } else {
                    Log.d(TAG, "Stale receive loop ended quietly: ${e.message}")
                }
            }
        }
    }

    private fun handleError(error: Exception) {
        Log.e("DrawingSocketManager", "Error: ${error.message}")
        if (error !is CancellationException) {
            _connectionState.value = ConnectionState.Error(error.message ?: "Unknown error")
            disconnect()
        }
    }

    // Guarda el nombre de licencia que envía el móvil (SharedPreferences).
    // Clave "received_license_name" en el fichero "app_settings".
    private fun saveReceivedLicense(name: String) {
        try {
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("received_license_name", name)
                .apply()
            Log.d(TAG, "Nombre de licencia guardado: $name")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando licencia: ${e.message}")
        }
    }

    fun disconnect() {
        outputSendJob?.cancel()
        outputSendJob = null
        outputQueue.clear()
        strokePendingPoints.clear()
        strokePendingCount = 0
        receiveJob?.cancel()

        // Campo y estado se limpian SÍNCRONAMENTE. Antes se hacía en una
        // corrutina con delay(100) que releía el campo mutable: cerraba el
        // socket NUEVO de una reconexión inmediata y machacaba su estado.
        val s = socket
        socket = null
        isConnecting = false
        _connectionState.value = ConnectionState.Disconnected

        if (s != null) {
            // Hilo plano (no corrutina): sobrevive a scope.cancel() en
            // release()/onDestroy, así el CLIENT_DISCONNECT sí llega a enviarse
            // al cerrar la app. Solo toca el socket capturado, nunca el campo.
            thread(name = "socket-close", isDaemon = true) {
                try {
                    if (!s.isClosed) {
                        try { NetworkUtils.sendData(s, DISCONNECT_MESSAGE) } catch (_: Exception) {}
                        try { Thread.sleep(100) } catch (_: InterruptedException) {}
                    }
                } finally {
                    try { s.close() } catch (_: Exception) {}
                }
            }
        }
    }
    
    fun release() {
        // 在应用退出时调用 disconnect
        disconnect()
        autoConnectJob?.cancel()
        stopServer()
        scope.cancel()
    }
    
    fun clearDrawing() {
        Log.d(TAG, "clearDrawing() 被调用")
        scope.launch {
            try {
                // 直接通知 Activity 清除画面，包括新的手写功能
                withContext(Dispatchers.Main) {
                    val clientActivity = context as? ClientActivity
                    if (clientActivity != null) {
                        Log.d(TAG, "正在清除ClientActivity的屏幕内容")
                        clientActivity.clearScreen()
                        
                        // 确保清除所有相关状态
                        clientActivity.clearAllWritingContent()
                        
                        Log.d(TAG, "屏幕清除完成，包括手写功能")
                    } else {
                        Log.w(TAG, "ClientActivity为null，无法清除屏幕")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in clear drawing: ${e.message}", e)
            }
        }
    }
    
    suspend fun connectToIp(connectionInfo: String) {
        try {
            _connectionState.value = ConnectionState.Connecting
            withContext(Dispatchers.IO) {
                try {
                    // 解析IP和端口
                    val parts = connectionInfo.split(":")
                    val ip = parts[0]
                    val port = if (parts.size > 1) {
                        try {
                            parts[1].toInt()
                        } catch (e: NumberFormatException) {
                            DEFAULT_PORT
                        }
                    } else DEFAULT_PORT

                    receiveJob?.cancel()
                    val old = socket
                    socket = null
                    try { old?.close() } catch (_: Exception) {}
                    socket = Socket().apply {
                        keepAlive = true
                        soTimeout = SO_TIMEOUT_MS
                        tcpNoDelay = true
                        sendBufferSize = 65536
                        receiveBufferSize = 65536
                        // 设置连接超时为5秒
                        connect(InetSocketAddress(ip, port), 5000)
                    }
                    _connectionState.value = ConnectionState.Connected
                    lastConnectedAddress = Pair(ip, port)
                    startReceiving()
                    startOutputSender()
                    startHeartbeat()
                } catch (e: SocketTimeoutException) {
                    _connectionState.value = ConnectionState.Error("连接超时，请检查IP地址和网络")
                    throw e
                } catch (e: UnknownHostException) {
                    _connectionState.value = ConnectionState.Error("无法解析IP地址，请检查输入是否正确")
                    throw e
                } catch (e: ConnectException) {
                    _connectionState.value = ConnectionState.Error("无法连接到服务器，请确保服务器已启动")
                    throw e
                } catch (e: Exception) {
                    _connectionState.value = ConnectionState.Error("连接失败: ${e.message}")
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接错误: ${e.message}")
            disconnect()
        }
    }
    
    suspend fun sendImageReceivedConfirmation() {
        socket?.let { socket ->
            try {
                NetworkUtils.sendData(socket, IMAGE_RECEIVED_MESSAGE)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending image confirmation: ${e.message}")
                throw e
            }
        }
    }

    suspend fun sendDisconnectMessage() {
        socket?.let { socket ->
            try {
                if (!socket.isClosed) {
                    NetworkUtils.sendData(socket, DISCONNECT_MESSAGE)
                    // 等待一小段时间确保消息发送
                    delay(100)
                } else {
                    Log.d(TAG, "Socket already closed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending disconnect message: ${e.message}")
            }
        }
    }
    
    // 获取上次连接的地址
    fun getLastConnectedAddress(): Pair<String, Int>? {
        return lastConnectedAddress
    }

    // ── 笔划实时传输 ──────────────────────────────────────────────

    /**
     * 落笔：发送笔划起点、颜色和粗细（bitmap 坐标系）。
     * 在 UI 线程调用，不阻塞。
     */
    fun enqueueStrokeStart(bx: Int, by: Int, color: Int, strokeWidth: Float) {
        if (_connectionState.value !is ConnectionState.Connected) return
        flushPendingStrokePoints()
        outputQueue.offer("STROKE_START:$bx,$by,$color,${strokeWidth.toInt()}")
    }

    /**
     * 移动：将坐标点放入临时缓冲，满 STROKE_BATCH_SIZE 后自动打包入队。
     * 在 UI 线程调用，不阻塞。
     */
    fun enqueueStrokePoint(bx: Int, by: Int) {
        if (_connectionState.value !is ConnectionState.Connected) return
        if (strokePendingCount > 0) strokePendingPoints.append('|')
        strokePendingPoints.append(bx).append(',').append(by)
        strokePendingCount++
        if (strokePendingCount >= STROKE_BATCH_SIZE) {
            flushPendingStrokePoints()
        }
    }

    /**
     * 抬笔：冲刷剩余缓冲点，并发送 STROKE_END。
     * 在 UI 线程调用，不阻塞。
     */
    fun enqueueStrokeEnd() {
        if (_connectionState.value !is ConnectionState.Connected) return
        flushPendingStrokePoints()
        outputQueue.offer("STROKE_END")
    }

    private fun flushPendingStrokePoints() {
        if (strokePendingCount == 0) return
        outputQueue.offer("STROKE:$strokePendingPoints")
        strokePendingPoints.clear()
        strokePendingCount = 0
    }

    /**
     * 后台输出协程：每 16 ms 排空队列，统一写入 socket。
     * 所有向服务端的写操作（ACK + 笔划）均经此通道，保证串行不交叉。
     */
    private fun startOutputSender() {
        outputSendJob?.cancel()
        outputSendJob = scope.launch {
            while (isActive) {
                delay(16)
                if (outputQueue.isEmpty()) continue
                val currentSocket = socket ?: continue
                if (currentSocket.isClosed) continue
                try {
                    val sb = StringBuilder()
                    while (outputQueue.isNotEmpty()) {
                        val msg = outputQueue.poll() ?: break
                        sb.append(msg).append('\n')
                    }
                    if (sb.isNotEmpty()) {
                        val out = currentSocket.getOutputStream()
                        out.write(sb.toString().toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Output sender error (non-critical): ${e.message}")
                    // 笔划/ACK 发送失败不中断连接，继续下一轮
                }
            }
        }
    }
    
    // La IP objetivo debe estar en la subred (/24) de algún interfaz local con
    // IPv4. Si no, la conexión saldría por la ruta por defecto (datos móviles
    // del hotspot) y un proxy del operador puede "aceptarla" → conectado falso
    // a una IP de una red anterior, sin llegar nunca al descubrimiento.
    private fun isOnLocalSubnet(ip: String): Boolean {
        return try {
            val target = ip.split(".")
            if (target.size != 4) return false
            java.net.NetworkInterface.getNetworkInterfaces().toList().any { ni ->
                ni.isUp && ni.inetAddresses.toList().any { addr ->
                    addr is java.net.Inet4Address && !addr.isLoopbackAddress &&
                        addr.hostAddress?.split(".")?.take(3) == target.take(3)
                }
            }
        } catch (e: Exception) {
            true // ante la duda, no bloquear el intento
        }
    }

    // 快速连接指定IP和端口
    suspend fun tryQuickConnect(ip: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            if (!isOnLocalSubnet(ip)) {
                Log.d(TAG, "快速连接 descartada: $ip no está en la red actual")
                _connectionState.value = ConnectionState.Disconnected
                return@withContext false
            }
            try {
                _connectionState.value = ConnectionState.Connecting
                Log.d(TAG, "尝试快速连接: $ip:$port")

                receiveJob?.cancel()
                val old = socket
                socket = null
                try { old?.close() } catch (_: Exception) {}
                socket = Socket().apply {
                    keepAlive = true
                    soTimeout = SO_TIMEOUT_MS
                    tcpNoDelay = true
                    sendBufferSize = 65536
                    receiveBufferSize = 65536
                    // 设置较短的连接超时时间用于快速连接
                    connect(InetSocketAddress(ip, port), 2000)
                }

                _connectionState.value = ConnectionState.Connected
                lastConnectedAddress = Pair(ip, port)
                startReceiving()
                startOutputSender()
                startHeartbeat()
                Log.d(TAG, "快速连接成功: $ip:$port")
                true
            } catch (e: Exception) {
                Log.d(TAG, "快速连接失败: $ip:$port, 错误: ${e.message}")
                _connectionState.value = ConnectionState.Disconnected
                false
            }
        }
    }

    // ── MODO V2: la pizarra hace de SERVIDOR de red ─────────────────────────
    // En vez de conectarse a un servidor, ABRE un socket, responde al
    // descubrimiento y ACEPTA la conexión entrante del emisor. Una vez
    // aceptada, reutiliza exactamente la misma recepción/envío que el cliente.
    fun startAsServer() {
        // Excluir el modo cliente V1: nunca deben convivir. Con el bucle de
        // autoconexión vivo, la pizarra podía descubrir su PROPIO responder y
        // conectarse a sí misma, ocupando el sitio del móvil.
        autoConnectJob?.cancel()
        autoConnectJob = null

        // Idempotente: un segundo arranque (onCreate + onNewIntent, doble toque
        // en el botón Modo V2) dejaba un accept() zombi reteniendo el puerto
        // 8888 para siempre y el servidor nuevo en bucle "Port 8888 busy".
        if (serverLoopJob?.isActive == true && serverSocket?.isClosed == false) {
            Log.d(TAG, "V2 server already running, ignoring duplicate start")
            acquireWakeLock()
            return
        }

        // Cerrar el ServerSocket ANTES de cancelar el job: cancel() no
        // interrumpe el accept() bloqueante, el close() sí lo despierta.
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverLoopJob?.cancel()

        acquireWakeLock() // (C) que no se suspenda el CPU/red mientras escucha
        startHeartbeat() // (B) latido para que el móvil detecte caídas rápido
        serverLoopJob = scope.launch {
            // (D) Bucle externo: reabre el ServerSocket si falla → nunca se muere.
            while (isActive) {
                val ss = createServerSocketFixed()
                if (ss == null) {
                    _connectionState.value = ConnectionState.Error("Port $DEFAULT_PORT busy")
                    delay(2000)
                    continue
                }
                serverSocket = ss
                val port = ss.localPort // siempre DEFAULT_PORT (8888)
                Log.d(TAG, "V2 server listening on port $port")
                startDiscoveryResponder(port)
                if (socket == null) _connectionState.value = ConnectionState.Searching

                // (A) Bucle de accept SIEMPRE activo: cada conexión nueva
                // SUSTITUYE a la anterior → recuperación instantánea al reconectar,
                // aunque la conexión vieja esté zombi (no espera timeouts).
                try {
                    while (isActive) {
                        val accepted = ss.accept()
                        if (!isActive) {
                            try { accepted.close() } catch (_: Exception) {}
                            break
                        }
                        adoptClient(accepted, port)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "V2 accept loop error: ${e.message}, reopening server")
                } finally {
                    // Cerrar SIEMPRE este ServerSocket (también si el job se
                    // cancela dentro de accept) para no retener el puerto, y
                    // limpiar el campo solo si nadie lo ha sustituido ya.
                    try { ss.close() } catch (_: Exception) {}
                    if (serverSocket === ss) serverSocket = null
                }
                if (isActive) delay(1000)
            }
        }
    }

    // Adopta la conexión entrante, sustituyendo cualquier conexión anterior.
    private fun adoptClient(accepted: Socket, port: Int) {
        try {
            accepted.apply {
                keepAlive = true
                soTimeout = SO_TIMEOUT_MS
                tcpNoDelay = true
                sendBufferSize = 65536
                receiveBufferSize = 65536
            }
        } catch (_: Exception) {}

        // Cerrar/limpiar la conexión anterior (puede estar zombi).
        receiveJob?.cancel()
        outputSendJob?.cancel()
        outputQueue.clear()
        strokePendingPoints.clear()
        strokePendingCount = 0
        val old = socket
        if (old != null && old !== accepted) {
            try { old.close() } catch (_: Exception) {}
        }

        socket = accepted
        lastConnectedAddress = Pair(accepted.inetAddress?.hostAddress ?: "", port)
        _connectionState.value = ConnectionState.Connected
        Log.d(TAG, "V2 client adopted: ${accepted.inetAddress?.hostAddress}")

        startReceiving()
        startOutputSender()
    }

    // Latido saliente: cada 2s mete "PING" en la cola de salida (canal de texto
    // pizarra→móvil). El móvil, con su watchdog, detecta la caída si dejan de
    // llegar. Se usa en AMBOS modos (cliente V1 y servidor V2): en V1 permite
    // al móvil detectar en segundos una pizarra que desapareció sin FIN.
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2000)
                if (_connectionState.value is ConnectionState.Connected) {
                    // offer tras poll de resto: la cola se vacía cada 16 ms por
                    // el outputSender; no se acumulan PINGs si hay conexión.
                    outputQueue.offer("PING")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "RealBoard::V2ServerWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    // Puerto FIJO (8888) para que conectar sea siempre igual. reuseAddress
    // permite reabrir el mismo puerto al instante tras cerrar (sin TIME_WAIT).
    private fun createServerSocketFixed(): ServerSocket? {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(DEFAULT_PORT))
            }
        } catch (e: Exception) {
            Log.e(TAG, "bind $DEFAULT_PORT failed: ${e.message}")
            null
        }
    }

    // Responde al descubrimiento del emisor (DRAWING_APP_DISCOVERY → DRAWING_APP_SERVER:port).
    // (D) Se auto-repara: si el bind o el socket UDP fallan, reintenta.
    private fun startDiscoveryResponder(port: Int) {
        discoveryResponderJob?.cancel()
        discoveryResponderJob = scope.launch {
            while (isActive) {
                var ds: DatagramSocket? = null
                try {
                    ds = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(DISCOVERY_PORT))
                    }
                    val buf = ByteArray(1024)
                    val packet = DatagramPacket(buf, buf.size)
                    while (isActive) {
                        ds.receive(packet)
                        val msg = String(packet.data, 0, packet.length).trim()
                        if (msg == BROADCAST_MESSAGE) {
                            val resp = "$SERVER_IDENTIFIER:$port".toByteArray()
                            ds.send(DatagramPacket(resp, resp.size, packet.address, packet.port))
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Discovery responder error: ${e.message}, retrying")
                } finally {
                    try { ds?.close() } catch (_: Exception) {}
                }
                if (isActive) delay(1000)
            }
        }
    }

    private fun stopServer() {
        // Cerrar el ServerSocket ANTES de cancelar el job para despertar el
        // accept() bloqueante (cancel() solo no lo interrumpe).
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverLoopJob?.cancel()
        serverLoopJob = null
        discoveryResponderJob?.cancel()
        discoveryResponderJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        releaseWakeLock()
    }

    // Salir del Modo V2 sin destruir el manager (p.ej. volver al modo cliente V1).
    fun stopServerMode() {
        stopServer()
    }
} 