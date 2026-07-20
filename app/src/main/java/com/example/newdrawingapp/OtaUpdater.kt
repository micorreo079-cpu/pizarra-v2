package com.example.newdrawingapp

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Auto-actualización OTA. Servidor: https://yaomagic.es/ota/
 *
 * checkForUpdate hace un check-in (app, versión, dueño, licencia, device_id) y
 * devuelve si hay versión nueva — SIN instalar. La UI decide (diálogo Install /
 * Later) y, si el usuario acepta, llama a downloadAndInstall.
 */
object OtaUpdater {

    private const val TAG = "OtaUpdater"
    private const val SERVER = "https://yaomagic.es"

    // Android < 7.1 (la DPT-RP1 es 5.1) NO confía en el certificado raíz de
    // Let's Encrypt → todo HTTPS a yaomagic.es falla con SSLHandshakeException.
    // Solución: incluir ISRG Root X1 en la app y usar esta factory en el OTA.
    // Funciona igual en Android moderno, así que se aplica siempre.
    @Volatile private var otaSocketFactory: SSLSocketFactory? = null

    private fun socketFactory(context: Context): SSLSocketFactory {
        otaSocketFactory?.let { return it }
        val cf = CertificateFactory.getInstance("X.509")
        val ca = context.resources.openRawResource(R.raw.isrg_root_x1)
            .use { cf.generateCertificate(it) }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("isrg-root-x1", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(ks) }
        val ssl = SSLContext.getInstance("TLSv1.2").apply { init(null, tmf.trustManagers, null) }
        return ssl.socketFactory.also { otaSocketFactory = it }
    }

    /** Info de actualización devuelta por el check-in. */
    data class UpdateInfo(
        val available: Boolean,
        val latestVersion: String,
        val downloadUrl: String
    )

    /**
     * Check-in al servidor. Devuelve UpdateInfo si el servidor respondió (con
     * available true/false), o null si no hubo respuesta (sin internet, caído).
     * NO descarga ni instala nada.
     */
    suspend fun checkForUpdate(context: Context, owner: String, license: String): UpdateInfo? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("app", context.packageName)
                    put(
                        "version",
                        context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName
                    )
                    put("owner", owner)
                    put("device_id", deviceId(context))
                    put("license", license)
                }

                val conn = URL("$SERVER/ota/api/checkin").openConnection() as HttpsURLConnection
                conn.sslSocketFactory = socketFactory(context)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                Log.d(TAG, "OTA check-in ok: $resp")
                UpdateInfo(
                    available = resp.optBoolean("update_available"),
                    latestVersion = resp.optString("latest_version"),
                    downloadUrl = resp.optString("download_url")
                )
            } catch (e: Exception) {
                // sin internet o servidor caído: silencioso, se reintenta la próxima vez
                Log.w(TAG, "OTA check-in falló: ${e.message}")
                null
            }
        }

    /** Descarga el APK y lanza el instalador. Llamar tras el "Install" del diálogo. */
    suspend fun downloadAndInstall(context: Context, downloadUrl: String) =
        withContext(Dispatchers.IO) {
            try {
                val apk = download(context, downloadUrl)
                install(context, apk)
            } catch (e: Exception) {
                Log.e(TAG, "OTA download/install falló: ${e.message}")
            }
        }

    /** UUID único por dispositivo, generado una vez y guardado en SharedPreferences. */
    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("ota", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString("device_id", it).apply() }
    }

    private fun download(context: Context, path: String): File {
        // Android < 7 (la DPT-RP1 es 5.1): el instalador del sistema no puede
        // leer content:// ni la caché privada de la app → descargar a la caché
        // EXTERNA (legible por el instalador con file://). En Android 7+ se usa
        // la caché privada + FileProvider como siempre.
        val dir = if (android.os.Build.VERSION.SDK_INT < 24) {
            context.externalCacheDir ?: context.cacheDir
        } else {
            context.cacheDir
        }
        val file = File(dir, "update.apk")
        val conn = URL("$SERVER$path").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = socketFactory(context)
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.inputStream.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun install(context: Context, apk: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                // Android 7+: content:// vía FileProvider (obligatorio desde N).
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", apk
                )
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                // Android < 7: el instalador solo entiende file:// clásico.
                apk.setReadable(true, false)
                setDataAndType(
                    android.net.Uri.fromFile(apk),
                    "application/vnd.android.package-archive"
                )
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
