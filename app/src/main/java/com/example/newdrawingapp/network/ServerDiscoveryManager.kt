package com.example.newdrawingapp.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import android.util.Log

class ServerDiscoveryManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var discoveryJob: Job? = null
    private val _serverAddress = MutableStateFlow<String?>(null)
    val serverAddress: StateFlow<String?> = _serverAddress.asStateFlow()

    fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1000
                }

                val broadcastMessage = "DRAWING_SERVER_DISCOVERY"
                val sendData = broadcastMessage.toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddress, DISCOVERY_PORT)

                val receiveData = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveData, receiveData.size)

                var retryCount = 0
                while (isActive && retryCount < 5) {
                    try {
                        Log.d("ServerDiscovery", "Sending discovery broadcast")
                        socket.send(sendPacket)

                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length).trim()
                        Log.d("ServerDiscovery", "Received response: $response")

                        if (response.startsWith("SERVER:")) {
                            val serverPort = response.substringAfter(":").toInt()
                            val serverAddress = receivePacket.address.hostAddress
                            val fullAddress = "$serverAddress:$serverPort"
                            Log.d("ServerDiscovery", "Found server at: $fullAddress")
                            _serverAddress.value = fullAddress
                            break
                        }
                    } catch (e: SocketTimeoutException) {
                        retryCount++
                        Log.d("ServerDiscovery", "Timeout, retry: $retryCount")
                        delay(500)
                    } catch (e: Exception) {
                        Log.e("ServerDiscovery", "Error: ${e.message}")
                        break
                    }
                }

                socket.close()
                if (retryCount >= 5) {
                    withContext(Dispatchers.Main) {
                        _serverAddress.value = null
                    }
                }
            } catch (e: Exception) {
                Log.e("ServerDiscovery", "Fatal error: ${e.message}")
                _serverAddress.value = null
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    companion object {
        const val DISCOVERY_PORT = 8081
    }
} 