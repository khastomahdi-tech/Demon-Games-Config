package com.demon.gamesconfig

import java.io.File

/** خواندن درصد مصرف CPU از /proc/stat (دلتای total نسبت به idle بین دو نمونه) */
object CpuMon {

    /** (total, idle) لحظه‌ی فعلی — یا null اگر خواندن ممکن نشد */
    fun sample(): Pair<Long, Long>? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            val p = line.trim().split(Regex("\\s+"))
            if (p.isEmpty() || p[0] != "cpu") return null
            var total = 0L
            var idle = 0L
            for (i in 1 until p.size) {
                val v = p[i].toLongOrNull() ?: 0L
                total += v
                if (i == 4 || i == 5) idle += v // idle + iowait
            }
            if (total <= 0) null else total to idle
        } catch (_: Exception) {
            null
        }
    }

    /** درصد بین دو نمونه — -1 نامعتبر */
    fun usage(prev: Pair<Long, Long>?, now: Pair<Long, Long>?): Int {
        if (prev == null || now == null) return -1
        val dt = now.first - prev.first
        val di = now.second - prev.second
        if (dt <= 0) return -1
        return (((dt - di) * 100) / dt).toInt().coerceIn(0, 100)
    }
}
