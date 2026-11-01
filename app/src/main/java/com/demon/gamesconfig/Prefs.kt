package com.demon.gamesconfig

import android.content.Context

/** ذخیره‌ی کلید/دستگاه/تنظیمات منوی شناور */
object Prefs {
    private const val FILE = "dg_prefs"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceId(c: Context): String {
        val s = sp(c)
        var d = s.getString("device_id", null)
        if (d.isNullOrEmpty()) {
            d = java.util.UUID.randomUUID().toString()
            s.edit().putString("device_id", d).apply()
        }
        return d
    }

    fun key(c: Context): String = sp(c).getString("key", "") ?: ""
    fun saveKey(c: Context, k: String) { sp(c).edit().putString("key", k).apply() }
    fun clear(c: Context) { sp(c).edit().remove("key").apply() }

    fun aimOn(c: Context): Boolean = sp(c).getBoolean("aim_on", false)
    fun setAimOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("aim_on", v).apply() }

    fun aimColor(c: Context): Int = sp(c).getInt("aim_color", -0x10000) // قرمز
    fun setAimColor(c: Context, v: Int) { sp(c).edit().putInt("aim_color", v).apply() }
}
