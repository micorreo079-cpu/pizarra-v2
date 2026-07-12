package com.example.newdrawingapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // QUICKBOOT_POWERON está registrado en el manifest: sin aceptarlo aquí,
        // en dispositivos con quick boot la app no arrancaba al encender.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "检测到开机完成，准备启动ClientActivity")
            try {
                // 启动ClientActivity
                val launchIntent = Intent(context, ClientActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
                Log.d("BootReceiver", "ClientActivity已启动")
            } catch (e: Exception) {
                Log.e("BootReceiver", "启动ClientActivity失败: ${e.message}", e)
            }
        }
    }
}

