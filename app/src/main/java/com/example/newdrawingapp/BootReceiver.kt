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
            // Candado de versión: en Android > 6 la app no arranca sola.
            if (!DeviceGate.isSupported()) {
                Log.d("BootReceiver", "Android no soportado (>6): no se autoarranca")
                return
            }
            // Sin activar todavía: al encender se abre la pantalla de
            // activación (no tiene sentido entrar a dibujar).
            if (!Activation.isActivated(context)) {
                Log.d("BootReceiver", "开机完成: sin activar → pantalla de activación")
                try {
                    context.startActivity(
                        Intent(context, SplashActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                } catch (e: Exception) {
                    Log.e("BootReceiver", "启动失败: ${e.message}", e)
                }
                return
            }

            // Arranca en el ÚLTIMO modo usado (lo guarda ClientActivity). Si
            // todavía no se ha usado ninguno, se abre el MENÚ para elegir.
            val prefs = context
                .getSharedPreferences(BootPrefs.PREFS, Context.MODE_PRIVATE)
            val hasLastMode = prefs.contains(BootPrefs.KEY_LAST_MODE_V2)
            val lastModeV2 = prefs.getBoolean(BootPrefs.KEY_LAST_MODE_V2, false)
            Log.d(
                "BootReceiver",
                "开机完成: " + if (!hasLastMode) "sin modo previo → menú"
                else "arrancando en ${if (lastModeV2) "V2" else "V1"}"
            )
            try {
                val launchIntent = if (!hasLastMode) {
                    // Primer encendido tras instalar: menú de selección.
                    Intent(context, SplashActivity::class.java)
                } else {
                    Intent(context, ClientActivity::class.java).apply {
                        if (lastModeV2) putExtra("SERVER_MODE", true)
                    }
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
                Log.d("BootReceiver", "arranque lanzado (hasLast=$hasLastMode, V2=$lastModeV2)")
            } catch (e: Exception) {
                Log.e("BootReceiver", "启动ClientActivity失败: ${e.message}", e)
            }
        }
    }
}

