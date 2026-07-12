package com.example.newdrawingapp

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PasswordActivity : AppCompatActivity() {
    companion object {
        private const val CORRECT_PASSWORD = "yaomagic2025"
        private const val PREFS_NAME = "AppPrefs"
        private const val KEY_IS_VERIFIED = "isVerified"
        private const val LICENSE_PREFS = "app_settings"
        private const val LICENSE_VERIFIED_KEY = "is_verified"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 保持屏幕常亮，防止设备进入休眠模式
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 只有在需要序列号验证时才检查序列号状态
        if (AppConfig.REQUIRE_SERIAL_VERIFICATION && !isLicenseVerified()) {
            startActivity(
                Intent(this, SerialVerifyActivity::class.java).apply {
                    putExtra(SerialVerifyActivity.EXTRA_RETURN_TO_PASSWORD, true)
                }
            )
            finish()
            return
        }

        // 检查是否已经验证过
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IS_VERIFIED, false)) {
            startMainActivity()
            return
        }

        setContentView(R.layout.activity_password)

        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val submitButton = findViewById<Button>(R.id.submitButton)

        submitButton.setOnClickListener {
            if (passwordInput.text.toString() == CORRECT_PASSWORD) {
                // 保存验证状态
                prefs.edit().putBoolean(KEY_IS_VERIFIED, true).apply()
                startMainActivity()
            } else {
                Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun isLicenseVerified(): Boolean {
        return getSharedPreferences(LICENSE_PREFS, MODE_PRIVATE)
            .getBoolean(LICENSE_VERIFIED_KEY, false)
    }
} 