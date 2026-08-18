package com.example.newdrawingapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * 启动页面
 * 
 * 根据 AppConfig.REQUIRE_SERIAL_VERIFICATION 决定跳转到哪个页面
 */
class SplashActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 全屏模式
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        // 根据配置决定跳转到哪个页面
        val requireSerial = AppConfig.REQUIRE_SERIAL_VERIFICATION
        android.util.Log.d("SplashActivity", "序列号验证开关: REQUIRE_SERIAL_VERIFICATION = $requireSerial")
        
        val targetActivity = if (requireSerial) {
            // 需要序列号验证
            android.util.Log.d("SplashActivity", "跳转到: SerialVerifyActivity")
            SerialVerifyActivity::class.java
        } else if (!Activation.isActivated(this)) {
            // Sin activar: pantalla de código/clave (sustituye a la contraseña
            // compartida, que valía en todas las pizarras).
            android.util.Log.d("SplashActivity", "跳转到: ActivationActivity")
            ActivationActivity::class.java
        } else {
            android.util.Log.d("SplashActivity", "跳转到: MainActivity")
            MainActivity::class.java
        }
        
        val intent = Intent(this, targetActivity)
        startActivity(intent)
        finish()
    }
}
