package com.example.newdrawingapp.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * 客户端蓝牙：RFCOMM Client；按二进制帧收图后主线程回调，再在 **Outbound** 写 **`IMAGE_RECEIVED\n`**，
 * 与 RealBoard 服务端 BluetoothServerManager 对齐。
 *
 * ## RFCOMM 入站二进制帧（与 TCP `NetworkUtils.receiveImage` 相同编码）
 *
 * 1. **长度**：`DataInputStream.readInt()`，Java **大端**。
 *    合法 `1 … NetworkUtils.MAX_BINARY_IMAGE_FRAME_BYTES`。
 * 2. **载荷**：紧接 **`N` 字节**，必须 **`readFully`** 读完再读下一帧；**禁止**在同一入站流上叠加
 *    `BufferedReader`/`InputStreamReader` —— 预读会破坏 `readInt()` 对齐（曾出现异常伪长度）。
 * 3. **ACK**：仅经 **出站** 写常量 `BluetoothManager.ACK_IMAGE_RECEIVED_BYTES`；须在 `readFully` 之后、下一轮 `readInt` 之前。
 */
class BluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothManager"

        /** Pause between retries when disconnected (after [receiveLoop] ends or failed connect sweep). */
        private const val RECONNECT_POLL_MS = 1_200L

        /** 与服务端共用的 SPP UUID */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** 与 RealBoard BluetoothServerManager 单帧上限一致 */
        internal const val MAX_RF_IMAGE_PAYLOAD_BYTES = NetworkUtils.MAX_BINARY_IMAGE_FRAME_BYTES

        /** `IMAGE_RECEIVED` + LF，服务端 `BufferedReader.readLine()` 对齐 */
        internal val ACK_IMAGE_RECEIVED_BYTES = "IMAGE_RECEIVED\n".toByteArray(Charsets.UTF_8)

        private val LINE_BT_PING_BYTES =
            "${BtRfcommHeartbeat.LINE_PING}\n".toByteArray(Charsets.UTF_8)
        private val LINE_BT_PONG_BYTES =
            "${BtRfcommHeartbeat.LINE_PONG}\n".toByteArray(Charsets.UTF_8)

        private val globalBtConnectMutex = Mutex()
    }

    sealed class BtState {
        object Disconnected : BtState()
        object Searching : BtState()
        object Connecting : BtState()
        data class Connected(val deviceName: String) : BtState()
    }

    private val _state = MutableStateFlow<BtState>(BtState.Disconnected)
    val state: StateFlow<BtState> = _state.asStateFlow()

    private val ioJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + ioJob)
    @Volatile private var btSocket: BluetoothSocket? = null
    private var searchJob: Job? = null
    // ── Modo V2: la pizarra también es SERVIDOR Bluetooth (RFCOMM/SPP) ──────
    // El móvil se conecta directamente con la MAC que va en el QR: funciona
    // sin red WiFi compartida. El protocolo es simétrico, así que la sesión
    // aceptada reutiliza receiveLoop/heartbeat tal cual.
    @Volatile private var btServerSocket: android.bluetooth.BluetoothServerSocket? = null
    private var serverJob: Job? = null
    private val outboundLock = Any()
    @Volatile private var lastPeerActivityMs = 0L
    @Volatile private var sessionSocket: BluetoothSocket? = null
    private val imageProcessMutex = Mutex()

    @Volatile private var preferredDeviceAddress: String? = null

    var onRfcommConnected: ((android.bluetooth.BluetoothDevice) -> Unit)? = null

    /**
     * 主线程调用；完成 UI/解码/刷屏。读循环在后台收包，不阻塞心跳。
     */
    var onImageReceived: ((ByteArray) -> Unit)? = null

    fun startSearch() {
        searchJob?.cancel()
        // Cierra la sesión previa: el lector viejo bloqueado en readInt no
        // muere con cancel() y dejaría una conexión zombi en Connected.
        closeSilently()
        searchJob = scope.launch {
            while (isActive) {
                if (_state.value !is BtState.Connected) {
                    tryConnectPaired()
                }
                delay(RECONNECT_POLL_MS)
            }
        }
    }

    /** Arranca el barrido solo si no está ya corriendo (no corta una sesión sana). */
    fun ensureSearching() {
        if (searchJob?.isActive == true) return
        startSearch()
    }

    /** MAC propia para el QR. En Android 5.1 (la Sony) devuelve la real. */
    fun getOwnMacAddress(): String? = try {
        val mac = BluetoothAdapter.getDefaultAdapter()?.address
        // Android 6+ devuelve una MAC falsa: no sirve para conectar.
        if (mac.isNullOrEmpty() || mac.startsWith("02:00:00")) null else mac
    } catch (e: Exception) {
        null
    }

    /** Modo V2: escuchar como servidor RFCOMM y adoptar conexiones entrantes.
     *  OJO: NO detiene el barrido cliente — en V2 conviven las dos vías BT:
     *  el barrido automático (dispositivos emparejados, como en V1) y este
     *  servidor (conexión directa por la MAC del QR, sin emparejar). Si ya hay
     *  sesión Connected, el barrido no hace nada; la adopción resuelve empates. */
    fun startAsServer() {
        if (serverJob?.isActive == true && btServerSocket != null) {
            Log.d(TAG, "BT V2 server already running")
            return
        }
        // Cerrar el server socket ANTES de cancelar el job (despierta accept()).
        try { btServerSocket?.close() } catch (_: Exception) {}
        btServerSocket = null
        serverJob?.cancel()

        serverJob = scope.launch {
            while (isActive) {
                val adapter = try {
                    BluetoothAdapter.getDefaultAdapter()
                } catch (e: Exception) { null }
                if (adapter == null) {
                    Log.w(TAG, "BT no soportado; servidor BT V2 desactivado")
                    return@launch
                }
                if (!adapter.isEnabled) {
                    delay(3000)
                    continue
                }
                val server = try {
                    adapter.listenUsingInsecureRfcommWithServiceRecord("RealBoardV2", SPP_UUID)
                } catch (e: Exception) {
                    Log.e(TAG, "BT listen failed: ${e.message}")
                    delay(3000)
                    continue
                }
                btServerSocket = server
                Log.d(TAG, "BT V2 server listening (SPP RealBoardV2)")
                try {
                    while (isActive) {
                        val accepted = server.accept() // bloqueante (hilo IO)
                        if (!isActive) {
                            try { accepted.close() } catch (_: Exception) {}
                            break
                        }
                        Log.d(TAG, "BT V2 client adopted: ${accepted.remoteDevice?.address}")
                        // Adopción: la conexión nueva sustituye a la anterior.
                        closeSilently()
                        btSocket = accepted
                        _state.value = BtState.Connected(
                            accepted.remoteDevice?.name ?: "Mobile"
                        )
                        launch { receiveLoop(accepted) }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "BT accept loop error: ${e.message}")
                } finally {
                    try { server.close() } catch (_: Exception) {}
                    if (btServerSocket === server) btServerSocket = null
                }
                if (isActive) delay(1000)
            }
        }
    }

    /** Salir del modo servidor BT (volver a cliente V1). */
    fun stopServerMode() {
        try { btServerSocket?.close() } catch (_: Exception) {}
        btServerSocket = null
        serverJob?.cancel()
        serverJob = null
        closeSilently()
    }

    fun setPreferredDeviceAddress(mac: String?) {
        preferredDeviceAddress = mac?.trim()?.takeIf { it.length >= 11 }
        Log.d(TAG, "preferred BT device: ${preferredDeviceAddress ?: "none"}")
    }

    fun isConnected(): Boolean =
        btSocket?.isConnected == true && _state.value is BtState.Connected

    fun release() {
        searchJob?.cancel()
        try { btServerSocket?.close() } catch (_: Exception) {}
        btServerSocket = null
        serverJob?.cancel()
        closeSilently()
        ioJob.cancel()
    }

    private suspend fun tryConnectPaired() {
        val sock = globalBtConnectMutex.withLock {
            tryConnectPairedLocked()
        }
        sock?.let { receiveLoop(it) }
    }

    private suspend fun tryConnectPairedLocked(): BluetoothSocket? {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: Exception) {
            Log.e(TAG, "获取 BluetoothAdapter 失败: ${e.message}")
            null
        }
        if (adapter == null) {
            Log.w(TAG, "设备不支持蓝牙或 BluetoothAdapter 为 null")
            return null
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "蓝牙未开启，跳过本次搜索")
            return null
        }

        if (_state.value is BtState.Connected && btSocket?.isConnected == true) {
            Log.d(TAG, "已有蓝牙连接，跳过重复连接尝试")
            return null
        }

        _state.value = BtState.Searching

        val paired = try {
            adapter.bondedDevices
        } catch (e: SecurityException) {
            Log.w(TAG, "缺少蓝牙权限: ${e.message}")
            _state.value = BtState.Disconnected
            return null
        }
        if (paired.isNullOrEmpty()) {
            Log.d(TAG, "已配对设备列表为空，跳过本次搜索")
            _state.value = BtState.Disconnected
            return null
        }
        val prefUpper = preferredDeviceAddress?.trim()?.uppercase()
        val ordered = prefUpper?.let { p ->
            paired.sortedWith(compareBy { if (it.address.equals(p, ignoreCase = true)) 0 else 1 })
        } ?: paired.toList()
        Log.d(TAG, "已配对设备数量: ${paired.size}，依次尝试 (${if (prefUpper != null) "优先 $prefUpper；" else ""}…）")

        for (device in ordered) {
            if (!coroutineContext.isActive) return null
            if (_state.value is BtState.Connected && btSocket?.isConnected == true) {
                Log.d(TAG, "连接过程中已建立连接，中止本轮")
                return null
            }
            var sock: BluetoothSocket? = null
            try {
                Log.d(TAG, "尝试连接: ${device.name} (${device.address})")
                _state.value = BtState.Connecting
                try {
                    adapter.cancelDiscovery()
                } catch (_: Exception) {
                }
                sock = createSocket(device)
                // connect() bloqueante sin timeout propio: el stack BT puede
                // tardar 10-25s por dispositivo apagado (y el barrido recorre
                // TODOS los emparejados con el mutex global retenido). Un
                // "verdugo" cierra el socket a los 8s para abortar el intento.
                val connecting = sock
                val killer = scope.launch {
                    delay(8000)
                    try { connecting?.close() } catch (_: Exception) {}
                }
                try {
                    withContext(Dispatchers.IO) { sock!!.connect() }
                } finally {
                    killer.cancel()
                }
                btSocket = sock
                _state.value = BtState.Connected(device.name ?: "Unknown")
                Log.d(TAG, "蓝牙已连接: ${device.name}")
                try {
                    onRfcommConnected?.invoke(device)
                } catch (_: Exception) {
                }
                return sock
            } catch (e: Exception) {
                Log.w(TAG, "连接 ${device.name} 失败: ${e.message}")
                try {
                    sock?.close()
                } catch (_: Exception) {
                }
            }
        }
        _state.value = BtState.Disconnected
        Log.d(TAG, "所有已配对设备均连接失败，等待下次重试")
        return null
    }

    private fun markPeerAlive() {
        lastPeerActivityMs = SystemClock.elapsedRealtime()
    }

    // Guarda el nombre de licencia que envía el móvil por BT (misma clave y
    // fichero que el path WiFi de DrawingSocketManager).
    private fun saveReceivedLicense(name: String) {
        try {
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("received_license_name", name)
                .apply()
            Log.d(TAG, "Nombre de licencia guardado (BT): $name")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando licencia (BT): ${e.message}")
        }
    }

    private fun writeOutboundBytes(sock: BluetoothSocket, bytes: ByteArray) {
        synchronized(outboundLock) {
            sock.getOutputStream().apply {
                write(bytes)
                flush()
            }
        }
    }

    private suspend fun clientHeartbeatLoop(sock: BluetoothSocket) {
        markPeerAlive()
        Log.d(
            TAG,
            "BT 心跳循环已启动 (interval=${BtRfcommHeartbeat.INTERVAL_MS}ms, dead=${BtRfcommHeartbeat.DEAD_MS}ms)"
        )
        while (coroutineContext.isActive && sock.isConnected && sessionSocket === sock) {
            delay(BtRfcommHeartbeat.INTERVAL_MS)
            if (!sock.isConnected || sessionSocket !== sock) break
            val silentMs = SystemClock.elapsedRealtime() - lastPeerActivityMs
            if (silentMs > BtRfcommHeartbeat.DEAD_MS) {
                Log.w(TAG, "BT 心跳超时 (${silentMs}ms)，断开并重搜")
                closeSilently()
                break
            }
            try {
                writeOutboundBytes(sock, LINE_BT_PING_BYTES)
                Log.d(TAG, "BT 心跳 → 已发送 ${BtRfcommHeartbeat.LINE_PING}")
            } catch (e: Exception) {
                Log.w(TAG, "BT 心跳 PING 发送失败: ${e.message}")
                closeSilently()
                break
            }
        }
    }

    private suspend fun receiveLoop(sock: BluetoothSocket) {
        sessionSocket = sock
        markPeerAlive()
        try {
            coroutineScope {
                val heartbeat = launch { clientHeartbeatLoop(sock) }
                val dis = DataInputStream(BufferedInputStream(sock.getInputStream(), 8192))
                while (coroutineContext.isActive && sock.isConnected && sessionSocket === sock) {
                    val size = try {
                        dis.readInt()
                    } catch (_: EOFException) {
                        Log.d(TAG, "BT 入站 EOF（对端关闭）")
                        break
                    }

                    when {
                        size == BtRfcommHeartbeat.SERVER_PING_MAGIC -> {
                            markPeerAlive()
                            Log.d(
                                TAG,
                                "BT 心跳 ← 收到 SERVER_PING (magic=$size), 回复 ${BtRfcommHeartbeat.LINE_PONG}"
                            )
                            try {
                                writeOutboundBytes(sock, LINE_BT_PONG_BYTES)
                            } catch (e: IOException) {
                                Log.e(TAG, "BT 心跳 PONG 发送失败: ${e.message}")
                                break
                            }
                        }
                        size == BtRfcommHeartbeat.SERVER_PONG_MAGIC -> {
                            markPeerAlive()
                            Log.d(TAG, "BT 心跳 ← 收到 SERVER_PONG (magic=$size)")
                        }
                        size == NetworkUtils.LICENSE_MAGIC -> {
                            // Frame de licencia por BT: int32 longitud + bytes UTF-8.
                            markPeerAlive()
                            val len = try {
                                dis.readInt()
                            } catch (_: EOFException) { break }
                            if (len in 1..NetworkUtils.LICENSE_MAX_BYTES) {
                                val nameBytes = ByteArray(len)
                                try {
                                    dis.readFully(nameBytes)
                                } catch (_: EOFException) { break }
                                val name = String(nameBytes, Charsets.UTF_8)
                                Log.d(TAG, "BT licencia recibida: $name")
                                saveReceivedLicense(name)
                            } else {
                                Log.e(TAG, "BT longitud de licencia inválida: $len")
                                break
                            }
                        }
                        size in 1..MAX_RF_IMAGE_PAYLOAD_BYTES -> {
                            markPeerAlive()
                            val data = try {
                                ByteArray(size)
                            } catch (e: OutOfMemoryError) {
                                Log.e(TAG, "BT sin memoria para frame de $size bytes, cerrando sesión")
                                break
                            }
                            try {
                                dis.readFully(data)
                            } catch (_: EOFException) {
                                Log.e(TAG, "BT 帧不完整（readFully EOF），期望 $size 字节")
                                break
                            }

                            // Procesar EN LÍNEA (sin `launch`): así el bucle NO lee
                            // el frame siguiente hasta mostrar y confirmar el
                            // actual → backpressure natural. Antes, en una ráfaga,
                            // se acumulaban varios byte arrays de imagen a la vez
                            // (riesgo de OOM en el e-ink). El emisor se frena solo
                            // al llenarse el buffer del socket.
                            if (sessionSocket === sock && sock.isConnected) {
                                withContext(Dispatchers.Main) {
                                    onImageReceived?.invoke(data)
                                }
                                if (sessionSocket === sock && sock.isConnected) {
                                    try {
                                        writeOutboundBytes(sock, ACK_IMAGE_RECEIVED_BYTES)
                                        markPeerAlive()
                                        Log.d(TAG, "BT IMAGE_RECEIVED 已发送")
                                    } catch (e: IOException) {
                                        Log.e(TAG, "发送 BT ACK 失败: ${e.message}")
                                    }
                                }
                            }
                        }
                        else -> {
                            Log.e(
                                TAG,
                                "无效帧长度: $size（图片须在 1..$MAX_RF_IMAGE_PAYLOAD_BYTES，或心跳 magic）"
                            )
                            break
                        }
                    }
                }
                // Al salir del bucle de lectura por `break`, cancelar el
                // heartbeat explícitamente: coroutineScope esperaría a que
                // muriera solo (3-12s), retrasando el cierre y la reconexión.
                heartbeat.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "BT 接收错误: ${e.message}")
        } finally {
            // Guardas de identidad: si esta sesión ya fue sustituida por una
            // adopción (modo servidor V2), no tocar el estado de la nueva.
            if (sessionSocket === sock) sessionSocket = null
            try { sock.close() } catch (_: Exception) {}
            if (btSocket === sock) {
                btSocket = null
                _state.value = BtState.Disconnected
            }
            Log.d(TAG, "蓝牙连接断开，将重新搜索")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createSocket(device: android.bluetooth.BluetoothDevice): BluetoothSocket {
        return try {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e1: Exception) {
            Log.w(TAG, "createInsecureRfcomm 失败，尝试反射 channel 1: ${e1.message}")
            try {
                device.javaClass
                    .getMethod("createRfcommSocket", Integer.TYPE)
                    .invoke(device, 1) as BluetoothSocket
            } catch (e2: Exception) {
                Log.w(TAG, "反射也失败，使用安全模式: ${e2.message}")
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            }
        }
    }

    private fun closeSilently() {
        try {
            btSocket?.close()
        } catch (_: Exception) {
        }
        btSocket = null
    }
}