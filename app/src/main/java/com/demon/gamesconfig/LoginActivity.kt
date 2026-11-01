package com.demon.gamesconfig

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class LoginActivity : Activity() {

    private lateinit var edKey: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // اگر کلید معتبر ذخیره داریم مستقیم برو صفحه‌ی اصلی
        if (Prefs.key(this).isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_login)
        edKey = findViewById(R.id.edKey)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)

        btnLogin.setOnClickListener { login() }
    }

    private fun login() {
        if (busy) return
        val key = edKey.text.toString().trim().uppercase()
        if (key.isEmpty()) {
            tvError.text = "کلید را وارد کن"
            return
        }
        busy = true
        btnLogin.isEnabled = false
        tvError.text = "در حال بررسی کلید..."
        val model = android.os.Build.MANUFACTURER.trim() + " " + android.os.Build.MODEL.trim()
        val av = android.os.Build.VERSION.RELEASE ?: ""
        Thread {
            val r = Api.activate(key, Prefs.deviceId(this), model, av)
            runOnUiThread {
                busy = false
                btnLogin.isEnabled = true
                if (r.ok) {
                    Prefs.saveKey(this, key)
                    Toast.makeText(this, "خوش آمدی 🔥", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    tvError.text = r.error ?: "کلید نامعتبر است"
                }
            }
        }.start()
    }
}
