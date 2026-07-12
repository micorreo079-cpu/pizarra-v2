package com.example.newdrawingapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.newdrawingapp.network.LicenseApi
import com.example.newdrawingapp.network.LicenseApi.LicenseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SerialVerifyActivity : AppCompatActivity() {
    private lateinit var etSerialNumber: EditText
    private lateinit var btnVerify: Button
    private lateinit var tvError: TextView

    companion object {
        const val EXTRA_RETURN_TO_PASSWORD = "extra_return_to_password"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 保持屏幕常亮，防止设备进入休眠模式
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        setContentView(R.layout.activity_serial_verify)

        etSerialNumber = findViewById(R.id.etSerialNumber)
        btnVerify = findViewById(R.id.btnVerify)
        tvError = findViewById(R.id.tvError)

        btnVerify.setOnClickListener {
            verifySerialNumber()
        }
    }

    private fun verifySerialNumber() {
        val serialNumber = etSerialNumber.text.toString().trim()
        if (serialNumber.isEmpty()) {
            showError("Please enter the serial number")
            return
        }

        btnVerify.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            val status = checkSerialNumberWithServer(serialNumber)

            withContext(Dispatchers.Main) {
                when (status) {
                    LicenseStatus.FIRST_ACTIVATION -> {
                        getSharedPreferences("app_settings", MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_verified", true)
                            .putString("license_key", serialNumber)
                            .apply()

                        // 验证成功后跳转到密码页面
                        val targetIntent = Intent(this@SerialVerifyActivity, PasswordActivity::class.java)
                        startActivity(targetIntent)
                        finish()
                    }
                    LicenseStatus.ALREADY_ACTIVATED -> {
                        showError("Serial number already activated on another device. Please use a new serial number.")
                        btnVerify.isEnabled = true
                    }
                    LicenseStatus.SERVER_ERROR -> {
                        showError("Invalid serial number")
                        btnVerify.isEnabled = true
                    }
                    LicenseStatus.NETWORK_ERROR -> {
                        showError("Verification failed, please check your network connection")
                        btnVerify.isEnabled = true
                    }
                }
            }
        }
    }

    private suspend fun checkSerialNumberWithServer(serialNumber: String): LicenseStatus {
        return LicenseApi.verifyLicenseStatus(serialNumber)
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }
}

