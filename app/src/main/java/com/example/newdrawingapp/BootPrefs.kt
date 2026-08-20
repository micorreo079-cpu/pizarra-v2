package com.example.newdrawingapp

/**
 * Arranque de la pizarra al encenderla.
 *
 * `ClientActivity` guarda aquí el modo que se está usando (V1 o V2) y el
 * `BootReceiver` lo lee para arrancar en ESE mismo modo la próxima vez que se
 * encienda el aparato. Sin nada guardado (primer encendido) se usa V1, que es
 * como se ha comportado siempre.
 */
object BootPrefs {
    const val PREFS = "BootPrefs"
    const val KEY_LAST_MODE_V2 = "last_mode_v2"
}
