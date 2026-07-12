package com.example.newdrawingapp.network

/**
 * 与 RealBoard 手机端 [com.example.newdrawingapp.BtRfcommHeartbeat] 保持一致。
 */
object BtRfcommHeartbeat {
    const val SERVER_PING_MAGIC = -91001
    const val SERVER_PONG_MAGIC = -91002

    const val LINE_PING = "BT_PING"
    const val LINE_PONG = "BT_PONG"

    const val INTERVAL_MS = 3_000L
    const val DEAD_MS = 9_000L

    fun isServerHeartbeatMagic(size: Int): Boolean =
        size == SERVER_PING_MAGIC || size == SERVER_PONG_MAGIC
}
