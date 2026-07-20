package com.example.newdrawingapp.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NetworkUtils {

    // Resultado de un frame recibido por el canal de imágenes.
    sealed class RxFrame {
        class Image(val bytes: ByteArray) : RxFrame()
        object KeepAlive : RxFrame()
        class License(val name: String) : RxFrame()
    }

    companion object {
        private const val TAG = "NetworkUtils"

        /**
         * 单帧二进制图片载荷上限（字节）；与服务端 TCP / RFCOMM `DataOutputStream.writeInt(N)` + N 字节一致。
         * 须与 RealBoard `BluetoothServerManager.MAX_RF_IMAGE_PAYLOAD_BYTES`（50 MiB）对齐。
         */
        // Tope de un frame de imagen COMPRIMIDO (PNG/JPEG). Un canvas 1650x2200
        // real nunca pasa de ~10 MB; 20 MB da margen y evita que un tamaño
        // basura por desincronización reserve un array gigante que reviente el
        // heap del e-ink (antes 50 MB). El emisor jamás manda imágenes tan grandes.
        const val MAX_BINARY_IMAGE_FRAME_BYTES = 20 * 1024 * 1024

        // Frame de LICENCIA (móvil → pizarra): cabecera int32 con este valor
        // mágico, seguida de int32 con la longitud y los bytes UTF-8 del nombre.
        // Valor distinto de tamaños de imagen (1..50MB), keepalive (0) y de los
        // magics de heartbeat BT (-91001/-91002).
        const val LICENSE_MAGIC = -70001
        const val LICENSE_MAX_BYTES = 4096

        private const val PORT_RANGE_START = 8888
        private const val PORT_RANGE_END = 8988
        // El servidor (móvil V1 y pizarra V2) SOLO escucha discovery UDP en 8889.
        private const val DISCOVERY_PORT = 8889
        private const val BROADCAST_MESSAGE = "DRAWING_APP_DISCOVERY"
        private const val SERVER_IDENTIFIER = "DRAWING_APP_SERVER"

        // 保存上次连接的地址和端口
        private var lastConnectedAddress: String? = null
        private var lastConnectedPort: Int? = null

        // 设置上次连接的地址和端口
        fun setLastConnectedAddress(address: String, port: Int) {
            lastConnectedAddress = address
            lastConnectedPort = port
        }

        // 获取设备IP地址 (兼容 Android 5.1)
        @Suppress("DEPRECATION")
        fun getLocalIpAddress(context: Context): String {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            return if (ipAddress != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            } else {
                "0.0.0.0"
            }
        }

        fun isWifiConnected(context: Context): Boolean {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            try {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    return true
                }
            } catch (ignored: NoSuchMethodError) {
                Log.w(TAG, "activeNetwork not supported, falling back to legacy check")
            } catch (ignored: Exception) {
                Log.w(TAG, "activeNetwork check failed: ${ignored.message}")
            }

            val networkInfo = connectivityManager.activeNetworkInfo
            if (networkInfo != null && networkInfo.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected) {
                return true
            }

            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return wifiManager.isWifiEnabled && wifiManager.connectionInfo.networkId != -1
        }

        // 搜索服务器
        // Broadcast DIRECTO al puerto de discovery 8889 (único donde escucha el
        // servidor). Antes se probaban puertos TCP y "comunes" (2s de timeout
        // perdidos por puerto) y se hacía un connect() de sonda que el servidor
        // Flutter adoptaba como cliente real (parpadeo conectado/desconectado).
        suspend fun searchServer(): Pair<String, Int>? = withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                Log.d(TAG, "Starting server search (UDP $DISCOVERY_PORT)...")
                socket = DatagramSocket().apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 2000
                }

                val message = BROADCAST_MESSAGE.toByteArray()
                socket.send(
                    DatagramPacket(
                        message,
                        message.size,
                        InetAddress.getByName("255.255.255.255"),
                        DISCOVERY_PORT
                    )
                )

                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)

                val response = String(responsePacket.data, 0, responsePacket.length)
                Log.d(TAG, "Received response: $response")

                val parts = response.split(":")
                if (parts.size == 2 && parts[0] == SERVER_IDENTIFIER) {
                    val serverPort = parts[1].trim().toInt()
                    val serverAddress = responsePacket.address.hostAddress
                        ?: return@withContext null
                    Log.d(TAG, "Found server at $serverAddress:$serverPort")
                    setLastConnectedAddress(serverAddress, serverPort)
                    return@withContext Pair(serverAddress, serverPort)
                }
                null
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "Server search timeout (no response)")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error searching server: ${e.message}")
                null
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }

        // Crea el stream de entrada de la conexión. DEBE crearse UNA sola vez
        // por conexión y reutilizarse en todas las llamadas a receiveImage: un
        // BufferedInputStream nuevo por frame puede pre-leer (hasta 8KB) el
        // principio del frame siguiente y perderlo al descartar el wrapper,
        // desincronizando el protocolo para siempre.
        fun openImageStream(socket: Socket): DataInputStream {
            return DataInputStream(BufferedInputStream(socket.getInputStream(), 8192))
        }

        // 接收图片：二进制帧为大端 length + length 字节；禁止另开 BufferedReader 读同一 inbound 以免错位。
        // Devuelve: bytes de imagen; ByteArray VACÍO si el frame es un keepalive
        // (tamaño 0, enviado por el emisor cada 2s). Errores:
        // - EOFException/IOException (incluye cierre remoto limpio): PROPAGA →
        //   el llamador debe desconectar/reconectar (antes EOF devolvía null y
        //   la conexión quedaba zombi en Connected para siempre).
        // - SocketTimeoutException en readInt (sin datos, frontera de frame):
        //   propaga tal cual; el llamador decide si es inactividad normal.
        // - Timeout o EOF a MITAD de frame, o tamaño inválido: IOException
        //   (stream desincronizado; solo se resincroniza reconectando).
        suspend fun receiveImage(dis: DataInputStream): RxFrame = withContext(Dispatchers.IO) {
            val size = dis.readInt()

            if (size == 0) {
                // Keepalive del emisor: la conexión está viva, no hay imagen.
                return@withContext RxFrame.KeepAlive
            }
            if (size == LICENSE_MAGIC) {
                // Frame de licencia: int32 longitud + bytes UTF-8 del nombre.
                val len = dis.readInt()
                if (len <= 0 || len > LICENSE_MAX_BYTES) {
                    throw IOException("Invalid license length: $len")
                }
                val nameBytes = ByteArray(len)
                dis.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                Log.d(TAG, "License received: $name")
                return@withContext RxFrame.License(name)
            }
            if (size < 0 || size > MAX_BINARY_IMAGE_FRAME_BYTES) {
                throw IOException("Invalid frame size: $size (stream desynchronized)")
            }

            Log.d(TAG, "Receiving image of size: $size bytes")
            val imageBytes = try {
                ByteArray(size)
            } catch (e: OutOfMemoryError) {
                // Sin memoria para el array: convertir en IOException para que el
                // llamador desconecte y reconecte, en vez de crashear la app.
                throw IOException("Out of memory for image frame ($size bytes)")
            }
            try {
                dis.readFully(imageBytes)
            } catch (e: SocketTimeoutException) {
                // Timeout a mitad de imagen: bytes parciales consumidos → desincronizado.
                throw IOException("Truncated image frame (timeout mid-frame)")
            }
            Log.d(TAG, "Image received completely: ${imageBytes.size} bytes")
            RxFrame.Image(imageBytes)
        }

        // 发送数据
        // Escritura directa al OutputStream: PrintWriter TRAGA las IOException
        // (solo activa un flag interno) y los fallos de envío pasaban en silencio.
        fun sendData(socket: Socket, data: String) {
            try {
                val out = socket.getOutputStream()
                out.write((data + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                Log.d(TAG, "Sent data: $data")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending data: ${e.message}")
                throw e
            }
        }

        // 接收文本数据
        fun receiveData(socket: Socket): String? {
            return try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val data = reader.readLine()
                Log.d(TAG, "Received data: $data")
                data
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving data: ${e.message}")
                null
            }
        }

    }
} 