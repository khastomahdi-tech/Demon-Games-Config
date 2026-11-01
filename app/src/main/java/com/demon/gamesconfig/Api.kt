package com.demon.gamesconfig

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** کلاینت API پنل /dg روی demonvpn.shop */
object Api {

    private const val BASE = "https://demonvpn.shop/dg/api"

    class Result(
        val ok: Boolean,
        val error: String?,
        val code: String?,
        val expiresAt: Long
    )

    private fun post(path: String, body: JSONObject): Result {
        return try {
            val c = URL("$BASE/$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 12000
            c.readTimeout = 12000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            try {
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            } catch (_: Exception) {}
            val status = c.responseCode
            val text = try {
                (if (status in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            if (text.isEmpty()) return Result(false, "پاسخ سرور خالی بود", "network", 0)
            val j = JSONObject(text)
            if (j.optBoolean("ok", false)) {
                Result(true, null, null, j.optLong("expires_at", 0L))
            } else {
                Result(false, j.optString("error", "خطای نامشخص"), j.optString("code", ""), 0)
            }
        } catch (e: Exception) {
            Result(false, "اتصال به سرور برقرار نشد — اینترنت را چک کن", "network", 0)
        }
    }

    fun activate(key: String, deviceId: String, model: String, androidVer: String): Result =
        post("activate", JSONObject()
            .put("key", key)
            .put("device_id", deviceId)
            .put("model", model)
            .put("android_ver", androidVer))

    fun check(key: String, deviceId: String): Result =
        post("check", JSONObject()
            .put("key", key)
            .put("device_id", deviceId))
}
