package com.example.newdrawingapp

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/**
 * Candado de versión de Android.
 *
 * La app está pensada SOLO para la pizarra RealBoard (Sony DPT-RP1, Android 5.1).
 * En cualquier Android superior a 6.0 (API 23) se niega a funcionar.
 *
 * Además, cuando eso pasa, avisa al servidor: primero pide conexión a internet y
 * NO enseña el mensaje de bloqueo hasta que el aviso ha llegado a yaomagic.es
 * (el servidor registra la IP y la hora; la app manda modelo, fabricante,
 * versión de Android y ANDROID_ID). Así te enteras de quién intenta instalar la
 * app en un aparato que no es la pizarra.
 *
 * Ojo: es una comprobación en Kotlin; un experto podría parchearla. Para el uso
 * normal (que no arranque en un móvil cualquiera y te avise si lo intentan) vale.
 */
object DeviceGate {

    // Máximo Android permitido: 6.0 (API 23). Todo lo que sea MÁS que esto se bloquea.
    private const val MAX_SDK = Build.VERSION_CODES.M

    fun isSupported(): Boolean = Build.VERSION.SDK_INT <= MAX_SDK

    /**
     * Si el dispositivo no está soportado, arranca el flujo de aviso y devuelve
     * true. El llamante debe hacer `if (DeviceGate.enforce(this)) return` al
     * principio de onCreate, antes de cualquier otra cosa.
     */
    fun enforce(activity: Activity): Boolean {
        if (isSupported()) return false

        // 1) Pantalla "conéctate a internet".
        activity.setContentView(
            textView(
                activity,
                "To work, this app needs an internet connection the first time."
            )
        )

        // 2) En segundo plano: reintenta enviar el aviso hasta que el servidor
        //    lo reciba. Mientras no haya internet, la pantalla de arriba se queda.
        Thread {
            var sent = false
            while (!sent) {
                sent = OtaUpdater.postUnsupportedAlertBlocking(activity)
                if (!sent) {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                }
            }
            // 3) Aviso enviado: ahora sí, el mensaje de bloqueo.
            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    activity.setContentView(
                        textView(
                            activity,
                            "This app only runs on the RealBoard.\n\n" +
                                "Esta app solo funciona en la RealBoard."
                        )
                    )
                }
            }
        }.apply { isDaemon = true }.start()

        return true
    }

    private fun textView(activity: Activity, msg: String): TextView =
        TextView(activity).apply {
            text = msg
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.BLACK)
        }
}
