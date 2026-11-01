package com.demon.gamesconfig

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * منوی شناور DEMON — دقیقاً مثل درخواست:
 * آیکون کوچک برنامه روی همه‌ی صفحات (حتی بیرون از اپ) · لمس = باز/بسته شدن منو ·
 * درگ = جابه‌جایی · سوییچ «Aim Assist» = نقطه‌ی وسط صفحه با رنگ قابل تغییر ·
 * «بستن منو» = بستن کامل (آیکون هم می‌رود) · لمس دوباره‌ی آیکون = بستن منو
 */
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
        private const val NOTIF_ID = 2001
        private const val CH = "dg_overlay"

        // رنگ‌های نقطه‌ی Aim Assist: قرمز/سبز/آبی/سفید/زرد/صورتی
        val AIM_COLORS = arrayOf("#FF1744", "#00E676", "#2979FF", "#FFFFFF", "#FFEA00", "#E040FB")
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var iconView: View? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var dotView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // حتماً اول startForeground — وگرنه اگر با startForegroundService استارت شده باشد
        // و Permission overlay نبود، سیستم با ForegroundServiceDidNotStartInTime کرش می‌کند
        startForegroundCompat()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showIcon()
        if (Prefs.aimOn(this)) showDot()
    }

    override fun onDestroy() {
        running = false
        removeViewSafe(menuView); menuView = null
        removeViewSafe(iconView); iconView = null
        removeViewSafe(dotView); dotView = null
        super.onDestroy()
    }

    private fun removeViewSafe(v: View?) {
        if (v == null) return
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    // ------------------------------------------------ سرویس پیش‌زمینه

    private fun startForegroundCompat() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH, "میانبر شناور DEMON", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
            val n: Notification = Notification.Builder(this, CH)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("DEMON GAMES CONFIG")
                .setContentText("میانبر شناور فعال است")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Exception) {
            try { startForeground(NOTIF_ID, Notification()) } catch (_e: Exception) {}
        }
    }

    // ------------------------------------------------ آیکون شناور

    private fun overlayFlags(touchable: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun showIcon() {
        if (iconView != null) return
        val frame = FrameLayout(this)
        frame.background = roundRect(0xB3000000.toInt(), dp(14))
        frame.setPadding(dp(4), dp(4), dp(4), dp(4))
        val iv = ImageView(this)
        iv.setImageResource(R.mipmap.ic_launcher)
        frame.addView(iv, FrameLayout.LayoutParams(dp(42), dp(42)))

        val p = WindowManager.LayoutParams(
            dp(50), dp(50),
            overlayType(),
            overlayFlags(true),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.BOTTOM or Gravity.END
        p.x = dp(12)
        p.y = dp(150)

        // درگ + لمس (تشخیص کلیک از درگ)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        frame.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = p.x; startY = p.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                    if (moved) {
                        p.x = startX - dx
                        p.y = startY - dy
                        try { wm.updateViewLayout(frame, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMenu()
                    true
                }
                else -> false
            }
        }

        iconParams = p
        iconView = frame
        try {
            wm.addView(frame, p)
        } catch (e: Exception) {
            iconView = null
            Toast.makeText(this, "نمایش میانبر ممکن نشد: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    // ------------------------------------------------ منو

    private fun toggleMenu() {
        main.post {
            if (menuView != null) {
                removeViewSafe(menuView)
                menuView = null
            } else {
                showMenu()
            }
        }
    }

    private fun showMenu() {
        if (menuView != null) return

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = roundRect(0xF2161022.toInt(), dp(18), 0xFF2C2044.toInt(), dp(1))
        val pad = dp(14)
        card.setPadding(pad, pad, pad, pad)

        // عنوان
        val title = TextView(this)
        title.text = "🔥  DEMON MENU"
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.typeface = Typeface.DEFAULT_BOLD
        card.addView(title)

        // سوییچ Aim Assist
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val rp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rp.topMargin = dp(12)
        row.layoutParams = rp

        val lbl = TextView(this)
        lbl.text = "Aim Assist"
        lbl.setTextColor(Color.WHITE)
        lbl.textSize = 14f
        lbl.typeface = Typeface.DEFAULT_BOLD
        row.addView(lbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(this)
        sw.isChecked = Prefs.aimOn(this)
        row.addView(sw)
        card.addView(row)

        // ردیف رنگ‌ها (فقط وقتی Aim روشن است)
        val colorRow = LinearLayout(this)
        colorRow.orientation = LinearLayout.HORIZONTAL
        colorRow.gravity = Gravity.CENTER
        val cp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cp.topMargin = dp(12)
        colorRow.layoutParams = cp

        val swatches = ArrayList<View>()
        // انتخاب فعلی با حلقه‌ی سفید مشخص شود
        fun highlightSelected() {
            val sel = Prefs.aimColor(this)
            for (i in swatches.indices) {
                val v = swatches[i]
                val c = Color.parseColor(AIM_COLORS[i])
                v.background = if (c == sel) circle(c, dp(3), Color.WHITE)
                else circle(c, dp(2), 0x66FFFFFF.toInt())
            }
        }
        for (hex in AIM_COLORS) {
            val v = View(this)
            v.background = circle(Color.parseColor(hex), dp(2), 0x66FFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(dp(28), dp(28))
            lp.marginStart = dp(5)
            lp.marginEnd = dp(5)
            v.layoutParams = lp
            v.setOnClickListener {
                Prefs.setAimColor(this, Color.parseColor(hex))
                applyDotColor()
                highlightSelected()
                Toast.makeText(this, "رنگ Aim Assist عوض شد", Toast.LENGTH_SHORT).show()
            }
            colorRow.addView(v)
            swatches.add(v)
        }
        card.addView(colorRow)
        colorRow.visibility = if (Prefs.aimOn(this)) View.VISIBLE else View.GONE

        highlightSelected()

        sw.setOnCheckedChangeListener { _, checked ->
            Prefs.setAimOn(this, checked)
            if (checked) {
                showDot()
                colorRow.visibility = View.VISIBLE
                highlightSelected()
            } else {
                hideDot()
                colorRow.visibility = View.GONE
            }
        }

        // دکمه‌ی بستن کامل منو
        val close = TextView(this)
        close.text = "بستن منو"
        close.setTextColor(Color.WHITE)
        close.textSize = 14f
        close.typeface = Typeface.DEFAULT_BOLD
        close.gravity = Gravity.CENTER
        close.background = roundRect(0xFFEF4444.toInt(), dp(12))
        val closeLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        closeLp.topMargin = dp(16)
        close.layoutParams = closeLp
        close.setOnClickListener { stopSelf() }
        card.addView(close)

        val p = WindowManager.LayoutParams(
            dp(252), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(true),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.CENTER
        menuView = card
        try {
            wm.addView(card, p)
        } catch (e: Exception) {
            menuView = null
        }
    }

    // ------------------------------------------------ نقطه‌ی Aim Assist

    private fun dotParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            dp(14), dp(14),
            overlayType(),
            overlayFlags(false),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.CENTER
        return p
    }

    private fun dotDrawable(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        d.setStroke(dp(1), 0x66000000.toInt())
        return d
    }

    private fun showDot() {
        main.post {
            if (dotView == null) {
                val v = View(this)
                v.background = dotDrawable(Prefs.aimColor(this))
                dotView = v
                try { wm.addView(v, dotParams()) } catch (_: Exception) { dotView = null }
            }
        }
    }

    private fun hideDot() {
        main.post {
            removeViewSafe(dotView)
            dotView = null
        }
    }

    private fun applyDotColor() {
        main.post {
            val v = dotView
            if (v != null) {
                v.background = dotDrawable(Prefs.aimColor(this))
                try { wm.updateViewLayout(v, dotParams()) } catch (_: Exception) {}
            }
        }
    }

    // ------------------------------------------------ اشکال

    private fun roundRect(fill: Int, radiusPx: Int, stroke: Int = 0, strokePx: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.cornerRadius = radiusPx.toFloat()
        if (stroke != 0) d.setStroke(strokePx, stroke)
        return d
    }

    private fun circle(fill: Int, strokePx: Int, stroke: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(fill)
        d.setStroke(strokePx, stroke)
        return d
    }
}
