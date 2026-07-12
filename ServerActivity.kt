private fun sendCanvas() {
    if (clientSocket == null || !NetworkUtils.isSocketConnected(clientSocket)) {
        Toast.makeText(this, "Not connected to RealBoard", Toast.LENGTH_SHORT).show()
        return
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val bitmap = drawingView.getDrawingBitmap()
            bitmap?.let {
                val stream = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val imageBytes = stream.toByteArray()
                
                // 发送图片数据
                NetworkUtils.sendImage(clientSocket!!, imageBytes) { progress ->
                    // 可以在这里处理进度回调
                }

                // 等待客户端的确认消息
                val response = NetworkUtils.receiveData(clientSocket!!)
                Log.d("ServerActivity", "Received response: $response")
                
                withContext(Dispatchers.Main) {
                    when (response) {
                        "IMAGE_RECEIVED" -> {
                            Log.d("ServerActivity", "Showing success toast")
                            Toast.makeText(
                                this@ServerActivity,
                                "RealBoard has displayed the image",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        null -> {
                            Log.d("ServerActivity", "Showing null response toast")
                            Toast.makeText(
                                this@ServerActivity,
                                "No RealBoard confirmation",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        else -> {
                            Log.d("ServerActivity", "Showing unknown response toast")
                            Toast.makeText(
                                this@ServerActivity,
                                "Received unknown response: $response",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ServerActivity, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
                handleDisconnection()
            }
        }
    }
} 