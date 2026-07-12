package com.example.newdrawingapp

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

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

                val conn = URL("$SERVER/ota/api/checkin").openConnection() as HttpURLConnection
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
        val file = File(context.cacheDir, "update.apk")
        URL("$SERVER$path").openStream().use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
