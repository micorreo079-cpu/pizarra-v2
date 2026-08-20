package com.example.newdrawingapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.newdrawingapp.network.LicenseApi
import com.example.newdrawingapp.network.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "ConnectionPrefs"
        private const val KEY_LAST_IP = "last_ip"
        // 添加ClientActivity的SharedPreferences键，用于读取上次成功连接的地址
        private const val CLIENT_PREFS_NAME = "ClientConnectionPrefs"
        private const val KEY_LAST_CONNECTED_IP = "last_connected_ip"
        private const val KEY_LAST_CONNECTED_PORT = "last_connected_port"
        private const val APP_SETTINGS_NAME = "app_settings"
        private const val KEY_IS_VERIFIED = "is_verified"
        private const val KEY_LICENSE = "license_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Candado de versión: en Android > 6 la app no funciona.
        if (DeviceGate.enforce(this)) return

        // 保持屏幕常亮，防止设备进入休眠模式
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 只有在需要序列号验证时才检查序列号状态
        if (AppConfig.REQUIRE_SERIAL_VERIFICATION && !isVerified()) {
            startActivity(Intent(this, SerialVerifyActivity::class.java))
            finish()
            return
        }

        // 只有在需要序列号验证时才检查设备状态
        if (AppConfig.REQUIRE_SERIAL_VERIFICATION && NetworkUtils.isWifiConnected(this)) {
            checkDeviceStatus()
        }

        setContentView(R.layout.activity_main)

        // Comprobación OTA: si hay actualización, avisa aquí (en el menú) con
        // un diálogo Install / Later. Comprueba el servidor como mucho 1 vez/mes.
        maybeCheckOta()

        // Sin WiFi NO se cierra la app: el Modo V2 puede conectar por Bluetooth
        // (sin red) y el usuario puede conectar el WiFi más tarde. Solo se avisa.
        if (!NetworkUtils.isWifiConnected(this)) {
            Toast.makeText(
                this,
                "Sin WiFi: solo disponible Bluetooth (Modo V2). Conecta WiFi para el resto.",
                Toast.LENGTH_LONG
            ).show()
        }

        val ipInput = findViewById<EditText>(R.id.ipInput)
        val btnAutoConnect = findViewById<Button>(R.id.btnAutoConnect)
        val btnManualConnect = findViewById<Button>(R.id.btnManualConnect)

        // Botón "Check updates": comprobación manual inmediata (ignora el gate
        // mensual). Sin update → "You have the latest version".
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            manualCheckUpdate()
        }

        // Modo V2: lanza la pizarra en modo SERVIDOR de red (escucha y acepta la
        // conexión del emisor). Mismas funciones, solo cambia la conexión.
        findViewById<Button>(R.id.btnModoV2).setOnClickListener {
            val intent = Intent(this, ClientActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("SERVER_MODE", true)
            }
            startActivity(intent)
        }

        // 读取上次保存的IP地址
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastIp = prefs.getString(KEY_LAST_IP, "")
        if (!lastIp.isNullOrEmpty()) {
            ipInput.setText(lastIp)
        }

        btnAutoConnect.setOnClickListener {
            // 优先使用上次成功连接的地址
            val lastConnectedAddress = getLastConnectedAddress()
            if (lastConnectedAddress != null) {
                val connectionInfo = "${lastConnectedAddress.first}:${lastConnectedAddress.second}"
                Toast.makeText(this, "尝试连接上次地址: $connectionInfo", Toast.LENGTH_SHORT).show()
                startClientActivity(connectionInfo)
            } else {
                // 如果没有上次连接记录，则使用自动搜索
                Toast.makeText(this, "开始自动搜索连接", Toast.LENGTH_SHORT).show()
                startClientActivity(null)
            }
        }

        btnManualConnect.setOnClickListener {
            val input = ipInput.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(this, "Please enter IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 解析IP和端口
            val (ip, port) = parseIpAndPort(input)
            if (ip == null) {
                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 保存输入的IP地址
            prefs.edit().putString(KEY_LAST_IP, input).apply()
            
            // 将IP和端口组合成字符串传递给ClientActivity
            val connectionInfo = if (port != null) "$ip:$port" else ip
            startClientActivity(connectionInfo)
        }
    }

    private fun startClientActivity(connectionInfo: String?) {
        val intent = Intent(this, ClientActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!connectionInfo.isNullOrEmpty()) {
                putExtra("MANUAL_IP", connectionInfo)
            }
        }
        startActivity(intent)
    }

    // ── OTA ──────────────────────────────────────────────────────────────────
    // ANTI-BUCLE:
    // - Solo cuenta como update una versión ESTRICTAMENTE más nueva que la
    //   instalada (comparación de strings; nuestras versiones son fechas
    //   "1.yyyy.MM.dd.HHmm", así que el orden alfabético = orden cronológico).
    //   Si el panel tiene un APK más viejo que el instalado, NO se avisa ni se
    //   descarga nada (antes: "distinto" = update → bucle de reintentos).
    // - "Later" silencia el recordatorio 24 h (antes re-preguntaba en cada
    //   entrada al menú).
    // - La descarga solo ocurre al pulsar Install, nunca sola.
    private fun isNewerVersion(latest: String, installed: String): Boolean {
        if (latest.isEmpty()) return false
        return latest > installed
    }

    // Comprueba el servidor como mucho 1 vez/mes. Un update conocido pero no
    // instalado se recuerda (respetando el silencio de 24 h de "Later").
    private fun maybeCheckOta() {
        try {
            val ota = getSharedPreferences("ota", MODE_PRIVATE)
            val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            val now = System.currentTimeMillis()

            // 1) ¿Update pendiente sin instalar? Recordar, salvo silencio activo.
            val pendingVer = ota.getString("pending_version", null)
            val pendingUrl = ota.getString("pending_url", null)
            if (!pendingVer.isNullOrEmpty() && !pendingUrl.isNullOrEmpty()) {
                if (isNewerVersion(pendingVer, installed)) {
                    val snoozeUntil = ota.getLong("snooze_until_ms", 0L)
                    if (now >= snoozeUntil) {
                        showUpdateDialog(pendingVer, pendingUrl)
                    }
                    return
                } else {
                    // Ya instalado (o el pendiente no es más nuevo): limpiar.
                    ota.edit().remove("pending_version").remove("pending_url").apply()
                }
            }

            // 2) Comprobar en el servidor, como mucho una vez al mes.
            val lastCheck = ota.getLong("last_check_ms", 0L)
            if (now - lastCheck < 30L * 24 * 60 * 60 * 1000) return

            val license = getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
                .getString("received_license_name", "") ?: ""

            lifecycleScope.launch {
                val info = OtaUpdater.checkForUpdate(this@MainActivity, license, license)
                if (info != null) {
                    // El servidor respondió: cuenta como comprobación mensual.
                    ota.edit().putLong("last_check_ms", now).apply()
                    if (info.available && info.downloadUrl.isNotEmpty() &&
                        isNewerVersion(info.latestVersion, installed)
                    ) {
                        ota.edit()
                            .putString("pending_version", info.latestVersion)
                            .putString("pending_url", info.downloadUrl)
                            .apply()
                        showUpdateDialog(info.latestVersion, info.downloadUrl)
                    } else {
                        // Sin update real: limpiar cualquier pendiente antiguo.
                        ota.edit().remove("pending_version").remove("pending_url").apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "maybeCheckOta: ${e.message}")
        }
    }

    // Comprobación MANUAL (botón Check updates): siempre llama al servidor.
    private fun manualCheckUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        val license = getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
            .getString("received_license_name", "") ?: ""
        lifecycleScope.launch {
            val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            val info = OtaUpdater.checkForUpdate(this@MainActivity, license, license)
            val ota = getSharedPreferences("ota", MODE_PRIVATE)
            if (info == null) {
                Toast.makeText(
                    this@MainActivity,
                    "Could not check for updates. Try again later.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // El servidor respondió: cuenta también como comprobación mensual.
            ota.edit().putLong("last_check_ms", System.currentTimeMillis()).apply()
            if (info.available && info.downloadUrl.isNotEmpty() &&
                isNewerVersion(info.latestVersion, installed)
            ) {
                ota.edit()
                    .putString("pending_version", info.latestVersion)
                    .putString("pending_url", info.downloadUrl)
                    .apply()
                showUpdateDialog(info.latestVersion, info.downloadUrl)
            } else {
                ota.edit().remove("pending_version").remove("pending_url").apply()
                Toast.makeText(
                    this@MainActivity,
                    "You have the latest version.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showUpdateDialog(version: String, url: String) {
        // SOLO mostrar el aviso si el menú está en primer plano. Nunca debe
        // salir sobre el canvas (ClientActivity): si el check-in vuelve mientras
        // el usuario ya entró a dibujar, no se muestra — el update queda
        // pendiente y se recordará al volver al menú.
        if (isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        try {
            AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("Version $version is available. Do you want to install it now?")
                .setPositiveButton("Install") { _, _ ->
                    Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        OtaUpdater.downloadAndInstall(this@MainActivity, url)
                    }
                }
                .setNegativeButton("Later") { d, _ ->
                    // Silenciar el recordatorio 24 h (anti-pesadez).
                    getSharedPreferences("ota", MODE_PRIVATE).edit()
                        .putLong("snooze_until_ms",
                            System.currentTimeMillis() + 24L * 60 * 60 * 1000)
                        .apply()
                    d.dismiss()
                }
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Log.e("MainActivity", "showUpdateDialog: ${e.message}")
        }
    }

    private fun parseIpAndPort(input: String): Pair<String?, Int?> {
        // 检查是否包含端口号
        val parts = input.split(":")
        if (parts.size > 2) return Pair(null, null)
        
        val ip = parts[0]
        val port = if (parts.size == 2) {
            try {
                val portNum = parts[1].toInt()
                if (portNum in 1..65535) portNum else null
            } catch (e: NumberFormatException) {
                null
            }
        } else null
        
        // 验证IP地址
        if (!isValidIp(ip)) return Pair(null, null)
        
        return Pair(ip, port)
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            try {
                val num = part.toInt()
                num in 0..255
            } catch (e: NumberFormatException) {
                false
            }
        }
    }
    
    // 获取上次成功连接的IP和端口
    private fun getLastConnectedAddress(): Pair<String, Int>? {
        val clientPrefs = getSharedPreferences(CLIENT_PREFS_NAME, MODE_PRIVATE)
        val ip = clientPrefs.getString(KEY_LAST_CONNECTED_IP, null)
        val port = clientPrefs.getInt(KEY_LAST_CONNECTED_PORT, -1)
        
        return if (ip != null && port != -1) {
            Pair(ip, port)
        } else {
            null
        }
    }

    private fun isVerified(): Boolean {
        return getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_IS_VERIFIED, false)
    }

    private fun checkDeviceStatus() {
        val savedLicense = getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
            .getString(KEY_LICENSE, "") ?: ""

        if (savedLicense.isEmpty()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = LicenseApi.verifyLicenseStatus(savedLicense)
                when (status) {
                    LicenseApi.LicenseStatus.NETWORK_ERROR -> {
                        // 网络错误时不清除验证状态，直接返回
                        Log.d("MainActivity", "Network error during device status check, keeping verification status")
                        return@launch
                    }
                    LicenseApi.LicenseStatus.FIRST_ACTIVATION,
                    LicenseApi.LicenseStatus.ALREADY_ACTIVATED -> {
                        // 验证成功，无需操作
                        Log.d("MainActivity", "Device status check successful")
                    }
                    LicenseApi.LicenseStatus.SERVER_ERROR -> {
                        // 服务器明确返回错误，清除验证状态
                        withContext(Dispatchers.Main) {
                            getSharedPreferences(APP_SETTINGS_NAME, MODE_PRIVATE)
                                .edit()
                                .clear()
                                .apply()

                            Toast.makeText(
                                this@MainActivity,
                                "Device has been disabled",
                                Toast.LENGTH_LONG
                            ).show()

                            // 只有在需要序列号验证时才跳转到序列号页面
                            if (AppConfig.REQUIRE_SERIAL_VERIFICATION) {
                                startActivity(Intent(this@MainActivity, SerialVerifyActivity::class.java))
                                finish()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Device status check failed", e)
                // 异常时也不清除验证状态，避免网络问题导致误清除
            }
        }
    }
} 