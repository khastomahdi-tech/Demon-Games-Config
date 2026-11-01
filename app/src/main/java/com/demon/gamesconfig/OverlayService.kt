package com.demon.gamesconfig

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

/**
 * منوی شناور DEMON — نسخه‌ی ۱.۱:
 * · آیکون کوچک برنامه روی همه‌ی صفحات می‌ماند (حتی وقتی کاربر بیرون است) · لمس آیکون = باز/بستن منو
 * · منو با ۳ ضربه‌ی ۳ انگشتی روی صفحه هم باز می‌شود (از داخل برنامه با Intent به این سرویس)
 * · Aim Assist: نقطه با ۶ رنگ + حالا با فلش‌های بالا/پایین/چپ/راست هر جای صفحه جابه‌جا می‌شود
 *   (نگه داشتن فلش = جابه‌جایی پیوسته) · دکمه‌ی وسط = برگشت نقطه به مرکز
 * · Wall Hack (فعلاً نمایشی): روشن/خاموش → اعلان پایین صفحه‌ی کاربر
 * · موزیک تمرکز: پخش لوپ آرام پیش‌فرض برنامه
 * · «بستن منو» = بستن کامل (منو + آیکون + نقطه) · لمس آیکون = فقط جمع شدن منو
 */
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
        private const val NOTIF_ID = 2001
        private const val CH = "dg_overlay"
        const val ACTION_SHOW_MENU = "com.demon.gamesconfig.SHOW_MENU"
        const val ACTION_TOGGLE_MENU = "com.demon.gamesconfig.TOGGLE_MENU"

        // رنگ‌های نقطه‌ی Aim Assist: قرمز/سبز/آبی/سفید/زرد/صورتی
        val AIM_COLORS = arrayOf("#FF1744", "#00E676", "#2979FF", "#FFFFFF", "#FFEA00", "#E040FB")
        private const val MUT = -0x657048      // 0x9A8FB8
        private const val PURPLE = 0xFF7C3AED.toInt()
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var iconView: View? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var dotView: View? = null
    private var bannerView: View? = null
    private var player: MediaPlayer? = null

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
        if (Prefs.musicOn(this)) startMusic()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_MENU -> main.postDelayed({ showMenu() }, 250)
            ACTION_TOGGLE_MENU -> main.post { toggleMenu() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopMusic()
        removeViewSafe(menuView); menuView = null
        removeViewSafe(iconView); iconView = null
        removeViewSafe(dotView); dotView = null
        removeViewSafe(bannerView); bannerView = null
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

    // ------------------------------------------------ ابزارهای overlay

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

    private fun makeSwitch(): Switch {
        val sw = Switch(this)
        val states = arrayOf(
            intArrayOf(-android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_checked)
        )
        sw.trackTintList = ColorStateList(states, intArrayOf(0x33FFFFFF, PURPLE))
        sw.thumbTintList = ColorStateList(states, intArrayOf(0x99FFFFFF.toInt(), 0xFFFFFFFF.toInt()))
        return sw
    }

    private fun divider(): View {
        val v = View(this)
        v.background = roundRect(0x22FFFFFF.toInt(), dp(1))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        lp.topMargin = dp(8)
        lp.bottomMargin = dp(2)
        v.layoutParams = lp
        return v
    }

    // ------------------------------------------------ آیکون شناور

    private fun showIcon() {
        if (iconView != null) return
        val frame = FrameLayout(this)
        frame.background = roundRect(0xCC15101F.toInt(), dp(15), 0x66A855F7.toInt(), dp(1))
        frame.setPadding(dp(4), dp(4), dp(4), dp(4))
        val iv = ImageView(this)
        iv.setImageResource(R.mipmap.ic_launcher)
        frame.addView(iv, FrameLayout.LayoutParams(dp(40), dp(40)))

        val p = WindowManager.LayoutParams(
            dp(48), dp(48),
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
            if (menuView != null) closeMenu() else showMenu()
        }
    }

    private fun closeMenu() {
        val v = menuView ?: return
        menuView = null
        v.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(140)
            .withEndAction { removeViewSafe(v) }.start()
    }

    /** یک ردیف امکان: آیکون + عنوان + توضیح + سوییچ */
    private fun featureRow(emoji: String, title: String, sub: String): Pair<LinearLayout, Switch> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(8), dp(6), dp(8))

        val ic = TextView(this)
        ic.text = emoji
        ic.textSize = 15f
        ic.gravity = Gravity.CENTER
        ic.background = roundRect(0x22FFFFFF.toInt(), dp(11))
        row.addView(ic, LinearLayout.LayoutParams(dp(34), dp(34)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        col.addView(t)
        val s = TextView(this)
        s.text = sub
        s.setTextColor(MUT)
        s.textSize = 10f
        col.addView(s)
        val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clp.marginStart = dp(10)
        row.addView(col, clp)

        val sw = makeSwitch()
        row.addView(sw)
        return Pair(row, sw)
    }

    private fun showMenu() {
        if (menuView != null) return

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFF3130F26.toInt(), 0xFFF3221240.toInt()))
        bg.cornerRadius = dp(24f).toFloat()
        bg.setStroke(dp(1), 0x59A855F7.toInt())
        card.background = bg
        card.setPadding(dp(12), dp(12), dp(12), dp(14))

        // ── هدر: عنوان + دکمه‌ی ✕ (فقط جمع کردن منو، آیکون می‌ماند)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6), dp(2), dp(2), dp(6))

        val title = TextView(this)
        title.text = "🔥  DEMON MENU"
        title.setTextColor(Color.WHITE)
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val x = TextView(this)
        x.text = "✕"
        x.setTextColor(0xFFB9AED4.toInt())
        x.textSize = 14f
        x.typeface = Typeface.DEFAULT_BOLD
        x.gravity = Gravity.CENTER
        x.background = circle(0x26FFFFFF.toInt(), dp(1), 0x33FFFFFF.toInt())
        x.setOnClickListener { closeMenu() }
        header.addView(x, LinearLayout.LayoutParams(dp(30), dp(30)))
        card.addView(header)
        card.addView(divider())

        // ── Aim Assist
        val (rowAim, swAim) = featureRow("🎯", "Aim Assist", "نقطه‌ی نشانه‌گیری — قابل جابه‌جایی و تغییر رنگ")
        swAim.isChecked = Prefs.aimOn(this)
        card.addView(rowAim)

        // پنل Aim: فلش‌های جابه‌جایی + رنگ‌ها (وقتی روشن است)
        val aimPanel = LinearLayout(this)
        aimPanel.orientation = LinearLayout.VERTICAL
        aimPanel.setPadding(dp(6), dp(2), dp(6), dp(2))

        // دی‌پد: ▲ بالا / ◀ ▶ / ▼ پایین، وسط = برگشت به مرکز
        val STEP = dp(18)
        fun arrow(glyph: String, ax: Int, ay: Int): TextView {
            val t = TextView(this)
            t.text = glyph
            t.setTextColor(0xFFECE7F6.toInt())
            t.textSize = 15f
            t.typeface = Typeface.DEFAULT_BOLD
            t.gravity = Gravity.CENTER
            t.background = roundRect(0x26FFFFFF.toInt(), dp(12), 0x33FFFFFF.toInt(), dp(1))
            var rep: Runnable? = null
            t.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        moveDot(ax * STEP, ay * STEP)
                        val r = object : Runnable {
                            override fun run() {
                                moveDot(ax * STEP, ay * STEP)
                                main.postDelayed(this, 110)
                            }
                        }
                        rep = r
                        main.postDelayed(r, 330)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        rep?.let { main.removeCallbacks(it) }
                        rep = null
                        true
                    }
                    else -> false
                }
            }
            return t
        }

        val bsz = LinearLayout.LayoutParams(dp(44), dp(44))
        fun spacer(): View {
            val s = View(this)
            s.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            return s
        }
        fun drow(vararg vs: View): LinearLayout {
            val l = LinearLayout(this)
            l.orientation = LinearLayout.HORIZONTAL
            l.gravity = Gravity.CENTER
            for (v in vs) l.addView(v, bsz)
            return l
        }
        val gap = dp(4)
        val r1 = drow(spacer(), arrow("▲", 0, -1), spacer())
        // دکمه‌ی وسط: برگشت نقطه به مرکز (بدون تچ‌لیسنر تا کلیک کار کند)
        val mid = TextView(this)
        mid.text = "◉"
        mid.setTextColor(MUT)
        mid.textSize = 13f
        mid.typeface = Typeface.DEFAULT_BOLD
        mid.gravity = Gravity.CENTER
        mid.background = roundRect(0x26FFFFFF.toInt(), dp(12), 0x33FFFFFF.toInt(), dp(1))
        mid.setOnClickListener { resetDot() }
        val r2 = drow(arrow("◀", -1, 0), mid, arrow("▶", 1, 0))
        val r3 = drow(spacer(), arrow("▼", 0, 1), spacer())
        for (l in arrayOf(r1, r2, r3)) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            l.setPadding(0, gap, 0, gap)
            aimPanel.addView(l)
        }

        val hint = TextView(this)
        hint.text = "با فلش‌ها نقطه را هر جای صفحه ببری — نگه داشتن = حرکت پیوسته · ◉ = برگشت به مرکز"
        hint.setTextColor(MUT)
        hint.textSize = 9.5f
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, dp(2), 0, 0)
        aimPanel.addView(hint)

        // ردیف رنگ‌ها
        val colorRow = LinearLayout(this)
        colorRow.orientation = LinearLayout.HORIZONTAL
        colorRow.gravity = Gravity.CENTER
        val swatches = ArrayList<View>()
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
            val lp = LinearLayout.LayoutParams(dp(26), dp(26))
            lp.marginStart = dp(5)
            lp.marginEnd = dp(5)
            lp.topMargin = dp(8)
            v.layoutParams = lp
            v.setOnClickListener {
                Prefs.setAimColor(this, Color.parseColor(hex))
                applyDotColor()
                highlightSelected()
            }
            colorRow.addView(v)
            swatches.add(v)
        }
        aimPanel.addView(colorRow)
        highlightSelected()

        aimPanel.visibility = if (Prefs.aimOn(this)) View.VISIBLE else View.GONE
        card.addView(aimPanel)

        swAim.setOnCheckedChangeListener { _, checked ->
            Prefs.setAimOn(this, checked)
            aimPanel.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) showDot() else hideDot()
        }

        card.addView(divider())

        // ── Wall Hack (فعلاً نمایشی) — اعلان پایین صفحه
        val (rowWall, swWall) = featureRow("🧱", "Wall Hack", "نمایشی — با روشن/خاموش کردن اعلان می‌بینی")
        swWall.isChecked = Prefs.wallOn(this)
        swWall.setOnCheckedChangeListener { _, checked ->
            Prefs.setWallOn(this, checked)
            if (checked) showBanner("🧱  وال هک فعال شد — نسخه‌ی نمایشی", 0xFF00E676.toInt())
            else showBanner("وال هک خاموش شد", 0xFF9A8FB8.toInt())
        }
        card.addView(rowWall)

        card.addView(divider())

        // ── موزیک تمرکز
        val (rowMusic, swMusic) = featureRow("🎵", "موزیک تمرکز", "لوپ آرام پیش‌فرض برنامه — برای تمرکز بیشتر")
        swMusic.isChecked = Prefs.musicOn(this)
        swMusic.setOnCheckedChangeListener { _, checked ->
            Prefs.setMusicOn(this, checked)
            if (checked) startMusic() else stopMusic()
        }
        card.addView(rowMusic)

        // ── بستن کامل منو
        val close = TextView(this)
        close.text = "بستن منو"
        close.setTextColor(Color.WHITE)
        close.textSize = 14f
        close.typeface = Typeface.DEFAULT_BOLD
        close.gravity = Gravity.CENTER
        close.background = roundRect(0xFFEF4444.toInt(), dp(14))
        val closeLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        closeLp.topMargin = dp(12)
        close.layoutParams = closeLp
        close.setOnClickListener { stopSelf() }
        card.addView(close)

        val p = WindowManager.LayoutParams(
            dp(278), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            overlayFlags(true),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.CENTER
        menuView = card
        try {
            wm.addView(card, p)
            // انیمیشن ورود
            card.scaleX = 0.85f
            card.scaleY = 0.85f
            card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(190)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        } catch (e: Exception) {
            menuView = null
        }
    }

    // ------------------------------------------------ نقطه‌ی Aim Assist

    private fun dotParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            dp(26), dp(26),
            overlayType(),
            overlayFlags(false),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.CENTER
        p.x = Prefs.dotDx(this)
        p.y = Prefs.dotDy(this)
        return p
    }

    /** نقطه با هاله‌ی نور: بیرونی نیمه‌شفاف + درونی توپر */
    private fun dotContainer(color: Int): FrameLayout {
        val f = FrameLayout(this)
        val outer = View(this)
        outer.background = circle((color and 0x00FFFFFF) or 0x55000000.toInt(), 0, 0)
        f.addView(outer, FrameLayout.LayoutParams(dp(26), dp(26)))
        val inner = View(this)
        inner.background = circle(color, dp(1), 0x66000000.toInt())
        val ip = FrameLayout.LayoutParams(dp(10), dp(10))
        ip.gravity = Gravity.CENTER
        f.addView(inner, ip)
        return f
    }

    private fun showDot() {
        main.post {
            if (dotView == null) {
                val v = dotContainer(Prefs.aimColor(this))
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
            if (dotView == null) return@post
            removeViewSafe(dotView)
            dotView = null
            val v = dotContainer(Prefs.aimColor(this))
            dotView = v
            try { wm.addView(v, dotParams()) } catch (_: Exception) { dotView = null }
        }
    }

    /** جابه‌جایی نقطه با فلش‌ها — با کلامپ تا لبه‌ی صفحه */
    private fun moveDot(adx: Int, ady: Int) {
        if (dotView == null) return
        val dm = resources.displayMetrics
        val maxX = dm.widthPixels / 2 - dp(40)
        val maxY = dm.heightPixels / 2 - dp(40)
        val nx = (Prefs.dotDx(this) + adx).coerceIn(-maxX, maxX)
        val ny = (Prefs.dotDy(this) + ady).coerceIn(-maxY, maxY)
        Prefs.setDotDx(this, nx)
        Prefs.setDotDy(this, ny)
        main.post {
            val v = dotView ?: return@post
            try { wm.updateViewLayout(v, dotParams()) } catch (_: Exception) {}
        }
    }

    private fun resetDot() {
        Prefs.setDotDx(this, 0)
        Prefs.setDotDy(this, 0)
        main.post {
            val v = dotView ?: return@post
            try { wm.updateViewLayout(v, dotParams()) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------ اعلان پایین صفحه

    /** نوار اعلان کوچک پایین صفحه‌ی کاربر — چند ثانیه بعد خودش می‌رود */
    private fun showBanner(text: String, accent: Int) {
        main.post {
            removeViewSafe(bannerView)
            bannerView = null

            val pill = LinearLayout(this)
            pill.orientation = LinearLayout.HORIZONTAL
            pill.gravity = Gravity.CENTER_VERTICAL
            pill.background = roundRect(0xF00E3A24.toInt(), dp(22), accent, dp(1))
            pill.setPadding(dp(18), dp(10), dp(18), dp(10))
            val tv = TextView(this)
            tv.text = text
            tv.setTextColor(Color.WHITE)
            tv.textSize = 13f
            tv.typeface = Typeface.DEFAULT_BOLD
            pill.addView(tv)

            pill.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val w = min(pill.measuredWidth, resources.displayMetrics.widthPixels - dp(32))

            val p = WindowManager.LayoutParams(
                w, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            p.y = dp(64)

            bannerView = pill
            try {
                wm.addView(pill, p)
                pill.alpha = 0f
                pill.translationY = dp(24).toFloat()
                pill.animate().alpha(1f).translationY(0f).setDuration(220).start()
                main.postDelayed({
                    val b = bannerView
                    if (b != null && b === pill) {
                        b.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(200).start()
                        main.postDelayed({ removeViewSafe(bannerView); bannerView = null }, 230)
                    }
                }, 3400)
            } catch (_: Exception) {
                bannerView = null
            }
        }
    }

    // ------------------------------------------------ موزیک تمرکز

    private fun startMusic() {
        if (player != null) return
        try {
            val mp = MediaPlayer.create(this, R.raw.focus_loop)
            mp.isLooping = true
            mp.setVolume(0.9f, 0.9f)
            mp.start()
            player = mp
        } catch (_: Exception) {
            player = null
        }
    }

    private fun stopMusic() {
        player?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        player = null
    }
}
