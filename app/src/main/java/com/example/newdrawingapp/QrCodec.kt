package com.example.newdrawingapp

import android.util.Base64

/**
 * Ofuscación del contenido del QR de conexión.
 *
 * OBJETIVO: que un espectador que escanee el QR con un lector normal NO vea la
 * IP/MAC con sentido, sino texto Base64 opaco. La app (pizarra genera, móvil
 * lee) sí lo entiende. NO es cifrado fuerte: la clave viaja en el binario; es
 * ofuscación suficiente para el uso escénico.
 *
 * DEBE ser byte-a-byte idéntico al códec Dart del móvil (lib/services/qr_codec.dart).
 */
object QrCodec {
    private val KEY = "YaoMagic-RealBoard-v2".toByteArray(Charsets.UTF_8)

    /** Texto plano ("ip:puerto|BT:mac") → Base64 ofuscado para el QR. */
    fun encode(plain: String): String {
        val data = plain.toByteArray(Charsets.UTF_8)
        for (i in data.indices) {
            data[i] = (data[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /**
     * Base64 ofuscado → texto plano. Devuelve null si no es un QR nuestro
     * (Base64 inválido, o el resultado no parece "ip:puerto"): así el móvil
     * puede distinguirlo de una IP escrita a mano o de un QR antiguo sin cifrar.
     */
    fun decode(text: String): String? {
        return try {
            val data = Base64.decode(text.trim(), Base64.NO_WRAP)
            for (i in data.indices) {
                data[i] = (data[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
            }
            val s = String(data, Charsets.UTF_8)
            if (s.contains('.') && s.contains(':')) s else null
        } catch (e: Exception) {
            null
        }
    }
}
