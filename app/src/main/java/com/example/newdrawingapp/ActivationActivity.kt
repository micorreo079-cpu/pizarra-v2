package com.example.newdrawingapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Pantalla de activación: enseña el código de la pizarra y pide la clave.
 *
 * Sustituye a la contraseña compartida: cada pizarra necesita su propia clave,
 * que el mago genera en el panel a partir del código que le dicte el cliente.
 */
class ActivationActivity : AppCompatActivity() {

    private lateinit var msg: TextView
    private val handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Candado de versión: en Android > 6 la app no funciona.
        if (DeviceGate.enforce(this)) return

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ya activada: directo a la app.
        if (Activation.isActivated(this)) {
            goToApp()
            return
        }

        setContentView(R.layout.activity_activation)

        val codeView = findViewById<TextView>(R.id.deviceCode)
        val input = findViewById<EditText>(R.id.keyInput)
        val button = findViewById<Button>(R.id.btnActivate)
        msg = findViewById(R.id.activationMsg)

        // App modificada y vuelta a firmar: no se activa.
        if (!Activation.signatureOk(this)) {
            codeView.text = "----"
            input.isEnabled = false
            button.isEnabled = false
            msg.text = "Invalid copy of the app."
            return
        }

        codeView.text = Activation.deviceCode(this)
        showLockIfNeeded()

        button.setOnClickListener {
            val left = Activation.lockRemainingMs(this)
            if (left > 0) {
                showLockIfNeeded()
                return@setOnClickListener
            }
            if (Activation.tryActivate(this, input.text.toString())) {
                goToApp()
            } else {
                input.setText("")
                val newLeft = Activation.lockRemainingMs(this)
                if (newLeft > 0) {
                    showLockIfNeeded()
                } else {
                    msg.text = "Wrong key."
                }
            }
        }
    }

    /** Mientras haya bloqueo por fallos, se avisa y se va refrescando. */
    private fun showLockIfNeeded() {
        val left = Activation.lockRemainingMs(this)
        if (left <= 0) {
            msg.text = ""
            return
        }
        val mins = (left / 60000).toInt()
        val secs = ((left % 60000) / 1000).toInt()
        msg.text = if (mins > 0) {
            "Too many attempts. Wait $mins min."
        } else {
            "Too many attempts. Wait $secs s."
        }
        handler.postDelayed({ showLockIfNeeded() }, 1000)
    }

    private fun goToApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
