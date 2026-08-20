package com.example.newdrawingapp

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

/**
 * Activación de la pizarra.
 *
 * La app enseña un código de 4 caracteres sacado del identificador del aparato;
 * el cliente lo dicta y recibe una clave de 4 que solo vale en ESA pizarra
 * (se genera en el panel de yaomagic.es). La comprobación es local: no hace
 * falta internet ni al activar ni después.
 *
 * El cálculo vive en la librería nativa `librbact.so` para que el secreto no
 * se lea descompilando el Kotlin.
 */
object Activation {

    private const val TAG = "Activation"
    private const val PREFS = "activation"
    private const val KEY_DONE = "activated"
    private const val KEY_CODE = "device_code"
    private const val KEY_SAVED_KEY = "activation_key"
    private const val KEY_FAILS = "fail_count"
    private const val KEY_LOCK_UNTIL = "lock_until_ms"

    // Huella SHA-256 del certificado oficial: si alguien modifica la app y la
    // firma con otra clave, la activación se niega a funcionar.
    private const val OFFICIAL_SIG_SHA256 =
        "EAD579CCD095078A3913822870BBDE206702A8466B3BA03A5131F8D0E5615459"

    private var libOk = false

    init {
        libOk = try {
            System.loadLibrary("rbact")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo cargar librbact: ${e.message}")
            false
        }
    }

    private external fun nativeDeviceCode(androidId: String): String
    private external fun nativeCheckKey(code: String, key: String): Boolean

    /** Código que se muestra al cliente (4 caracteres). */
    fun deviceCode(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Exception) {
            ""
        }
        // Si el sistema no da ANDROID_ID (raro), se usa uno propio persistente
        // para no dejar la pizarra sin poder activarse.
        val id = if (androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("fallback_id", null) ?: java.util.UUID.randomUUID().toString()
                .also { p.edit().putString("fallback_id", it).apply() }
        }
        return if (libOk) nativeDeviceCode(id) else fallbackCode(id)
    }

    /** Reserva por si la librería nativa no cargara: mismo formato, sin secreto. */
    private fun fallbackCode(id: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest("RBDEV|$id".toByteArray())
        val a = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return (0..3).map { a[(h[it].toInt() and 0xFF) % 32] }.joinToString("")
    }

    /** Normaliza lo tecleado: sin confusiones entre O/0 ni I/L/1. */
    fun normalize(s: String): String {
        val a = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return s.uppercase()
            .replace('O', '0').replace('I', '1').replace('L', '1')
            .filter { a.contains(it) }
    }

    // Amnistía DESACTIVADA a propósito: los clientes instalan esta versión
    // desde cero, así que TODOS pasan por la activación. Si estuviera activada,
    // bastaría con instalar el APK antiguo, meter la contraseña compartida y
    // actualizar para saltarse la clave.
    private const val GRANDFATHER_OLD_INSTALLS = false

    /**
     * ¿Está activada? No basta con un "sí" guardado: se vuelve a comprobar la
     * CLAVE en cada arranque contra el código de este aparato. Así, en una
     * pizarra con root, escribir a mano el ajuste de "activada" no sirve de
     * nada — hace falta una clave válida de verdad.
     */
    fun isActivated(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedKey = p.getString(KEY_SAVED_KEY, null)
        if (savedKey != null && libOk) {
            return nativeCheckKey(deviceCode(context), savedKey)
        }
        if (p.getBoolean(KEY_DONE, false)) return true
        if (GRANDFATHER_OLD_INSTALLS) {
            val hadPassword = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .getBoolean("isVerified", false)
            if (hadPassword) {
                p.edit().putBoolean(KEY_DONE, true).apply()
                Log.i(TAG, "Instalación anterior: se da por activada")
                return true
            }
        }
        return false
    }

    /** Milisegundos que faltan para poder volver a intentarlo (0 = ya puede). */
    fun lockRemainingMs(context: Context): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = p.getLong(KEY_LOCK_UNTIL, 0L)
        val left = until - System.currentTimeMillis()
        return if (left > 0) left else 0
    }

    /**
     * Comprueba la clave introducida. Al acertar deja la pizarra activada para
     * siempre; al fallar, va frenando los intentos (5 fallos → 1 min, 10 → 10
     * min, 20 → 1 h) para que no se pueda probar a lo bruto.
     */
    fun tryActivate(context: Context, typedKey: String): Boolean {
        if (lockRemainingMs(context) > 0) return false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = deviceCode(context)
        val key = normalize(typedKey)

        val ok = if (libOk) nativeCheckKey(code, key) else false
        if (ok) {
            p.edit()
                .putBoolean(KEY_DONE, true)
                .putString(KEY_CODE, code)
                .putString(KEY_SAVED_KEY, key) // se revalida en cada arranque
                .remove(KEY_FAILS)
                .remove(KEY_LOCK_UNTIL)
                .apply()
            return true
        }

        val fails = p.getInt(KEY_FAILS, 0) + 1
        val lockMs = when {
            fails >= 20 -> 60 * 60 * 1000L
            fails >= 10 -> 10 * 60 * 1000L
            fails >= 5 -> 60 * 1000L
            else -> 0L
        }
        p.edit()
            .putInt(KEY_FAILS, fails)
            .putLong(KEY_LOCK_UNTIL, if (lockMs > 0) System.currentTimeMillis() + lockMs else 0L)
            .apply()
        return false
    }

    /**
     * Firma del APK: debe ser la oficial. Evita que alguien descompile la app,
     * le quite la activación y la vuelva a firmar con su propia clave.
     * Ante cualquier duda devuelve true: nunca dejar tirado a un cliente
     * legítimo por un fallo leyendo la firma.
     */
    @Suppress("DEPRECATION")
    fun signatureOk(context: Context): Boolean {
        return try {
            val pi = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES
            )
            val sigs = pi.signatures ?: return true
            val md = MessageDigest.getInstance("SHA-256")
            sigs.any { s ->
                md.reset()
                md.digest(s.toByteArray()).joinToString("") { "%02X".format(it) } ==
                    OFFICIAL_SIG_SHA256
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar la firma: ${e.message}")
            true
        }
    }
}
