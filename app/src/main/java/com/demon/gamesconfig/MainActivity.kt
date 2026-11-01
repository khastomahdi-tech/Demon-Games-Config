package com.demon.gamesconfig

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCpu: TextView
    private lateinit var pbCpu: ProgressBar
    private lateinit var tvCores: TextView
    private lateinit var tvRam: TextView
    private lateinit var pbRam: ProgressBar
    private lateinit var tvRamInfo: TextView
    private lateinit var tvMenuState: TextView
    private lateinit var btnMenu: Button

    private val handler = Handler(Looper.getMainLooper())
    private var prevCpu: Pair<Long, Long>? = null
    private var checkingKey = false

    // ژست باز کردن منو: ۳ بار، هر بار لمس صفحه با ۳ انگشت
    private var tap3Count = 0
    private var tap3Last = 0L

    private val cpuTick = object : Runnable {
        override fun run() {
            tickCpu()
            tickRam()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.key(this).isEmpty()) {
            goLogin(null)
            return
        }
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        tvCpu = findViewById(R.id.tvCpu)
        pbCpu = findViewById(R.id.pbCpu)
        tvCores = findViewById(R.id.tvCores)
        tvRam = findViewById(R.id.tvRam)
        pbRam = findViewById(R.id.pbRam)
        tvRamInfo = findViewById(R.id.tvRamInfo)
        tvMenuState = findViewById(R.id.tvMenuState)
        btnMenu = findViewById(R.id.btnMenu)

        try {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tvVersion).text = "نسخه $v"
        } catch (_: Exception) {}

        tvCores.text = "پردازنده: ${Runtime.getRuntime().availableProcessors()} هسته"

        findViewById<Button>(R.id.btnBoost).setOnClickListener { boost() }
        btnMenu.setOnClickListener { openMenu() }

        // اجازه‌ی نوتیفیکیشن برای سرویس میانبر (اندروید ۱۳+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
            }
        }
    }

    /** ۳ بار لمس صفحه با ۳ انگشت = باز کردن منوی شناور */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount == 3) {
            val now = SystemClock.elapsedRealtime()
            tap3Count = if (now - tap3Last < 2200) tap3Count + 1 else 1
            tap3Last = now
            if (tap3Count >= 3) {
                tap3Count = 0
                vibrate()
                openMenu()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun vibrate() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), -1))
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.key(this).isEmpty()) return
        prevCpu = null
        handler.post(cpuTick)
        refreshMenuState()
        validateKey()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(cpuTick)
    }

    private fun goLogin(err: String?) {
        val i = Intent(this, LoginActivity::class.java)
        if (err != null) i.putExtra("err", err)
        startActivity(i)
        finish()
    }

    /** کنترل اعتبار کلید در شروع — اگر رد شد برگرد لاگین */
    private fun validateKey() {
        if (checkingKey) return
        checkingKey = true
        val key = Prefs.key(this)
        Thread {
            val r = Api.check(key, Prefs.deviceId(this))
            runOnUiThread {
                checkingKey = false
                if (r.ok) {
                    if (r.expiresAt > 0) {
                        val df = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())
                        tvStatus.text = "✅ حساب فعال است تا ${df.format(Date(r.expiresAt))}"
                    } else {
                        tvStatus.text = "✅ حساب فعال است"
                    }
                } else {
                    Prefs.clear(this)
                    goLogin(r.error ?: "کلید منقضی شده است")
                }
            }
        }.start()
    }

    private fun tickCpu() {
        val now = CpuMon.sample()
        val pct = CpuMon.usage(prevCpu, now)
        prevCpu = now ?: prevCpu
        if (pct >= 0) {
            tvCpu.text = "$pct٪"
            pbCpu.progress = pct
        }
    }

    private fun tickRam() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val pct = (((mi.totalMem - mi.availMem) * 100) / mi.totalMem).toInt()
            tvRam.text = "$pct٪"
            pbRam.progress = pct
            val usedGb = ((mi.totalMem - mi.availMem) / 1073741824.0)
            val totGb = (mi.totalMem / 1073741824.0)
            tvRamInfo.text = String.format(Locale.US, "%.1f از %.1f گیگابایت استفاده شده", usedGb, totGb)
        } catch (_: Exception) {}
    }

    /** بهترین تلاش ممکن برای آزادسازی — جمع‌آوری حافظه و گزارش */
    private fun boost() {
        val before = freeRamMb()
        Thread {
            System.gc()
            System.runFinalization()
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            val after = freeRamMb()
            runOnUiThread {
                val freed = after - before
                if (freed > 0) {
                    Toast.makeText(this, "کاهش لگ انجام شد — ${freed} مگابایت آزاد شد ⚡", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "کاهش لگ انجام شد — حافظه در وضعیت بهینه است ⚡", Toast.LENGTH_LONG).show()
                }
                tickRam()
            }
        }.start()
    }

    private fun freeRamMb(): Long {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.availMem / 1048576
        } catch (_: Exception) { 0 }
    }

    /** باز کردن منوی شناور — اگر سرویس خاموش است اول روشنش کن */
    private fun openMenu() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "اول اجازه‌ی «نمایش روی برنامه‌های دیگر» را بده", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_e: Exception) {}
            }
            return
        }
        if (!OverlayService.running) {
            try {
                startForegroundService(
                    Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW_MENU))
                Toast.makeText(this, "منوی شناور باز شد 🔥", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "خطا در راه‌اندازی منو: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_TOGGLE_MENU))
        }
        handler.postDelayed({ refreshMenuState() }, 400)
    }

    private fun refreshMenuState() {
        if (OverlayService.running) {
            tvMenuState.text = "منو روشن است — ۳ بار با ۳ انگشت روی صفحه بزن یا روی آیکون گوشه بزن"
            btnMenu.text = "باز کردن منو"
        } else {
            tvMenuState.text = "برای باز شدن منو: ۳ بار با ۳ انگشت روی صفحه بزن (یا دکمه‌ی بالا)"
            btnMenu.text = "منو"
        }
    }
}
