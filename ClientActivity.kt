// 客户端代码应该类似这样
try {
    // 处理接收到的图片...
    
    // 直接发送确认消息，不再延迟
    Log.d("ClientActivity", "Sending IMAGE_RECEIVED confirmation immediately")
    socket.getOutputStream().write("IMAGE_RECEIVED".toByteArray())
    socket.getOutputStream().flush()
    Log.d("ClientActivity", "IMAGE_RECEIVED confirmation sent successfully")
    
} catch (e: Exception) {
    Log.e("ClientActivity", "Error sending confirmation: ${e.message}")
    e.printStackTrace()
} 