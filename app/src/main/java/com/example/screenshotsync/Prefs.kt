package com.example.screenshotsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 简单的本地配置存储。
 *
 * 注意：密码以明文存储在 SharedPreferences 中（仅本机可读）。对于个人局域网工具
 * 可以接受；如果对安全性有更高要求，可改用 EncryptedSharedPreferences。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("screenshot_sync", Context.MODE_PRIVATE)

    // ---- 全局服务器配置（所有同步对共用一台服务器）----

    var host: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOST, v).apply()

    var username: String
        get() = sp.getString(KEY_USER, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER, v).apply()

    var password: String
        get() = sp.getString(KEY_PASS, "") ?: ""
        set(v) = sp.edit().putString(KEY_PASS, v).apply()

    var domain: String
        get() = sp.getString(KEY_DOMAIN, "") ?: ""
        set(v) = sp.edit().putString(KEY_DOMAIN, v).apply()

    var autoSync: Boolean
        get() = sp.getBoolean(KEY_AUTO, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO, v).apply()

    /**
     * 是否已「登录」到当前服务器。
     * 登录 = 已用当前凭据成功连接过服务器；登录后锁定服务器输入框，退出登录后才能切换服务器。
     */
    var loggedIn: Boolean
        get() = sp.getBoolean(KEY_LOGGED_IN, false)
        set(v) = sp.edit().putBoolean(KEY_LOGGED_IN, v).apply()

    /** 后台监视服务是否处于「已开启」状态（用于开机自启与重新打开 App 时恢复） */
    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_SERVICE, false)
        set(v) = sp.edit().putBoolean(KEY_SERVICE, v).apply()

    /** 是否处于暂停状态 */
    var paused: Boolean
        get() = sp.getBoolean(KEY_PAUSED, false)
        set(v) = sp.edit().putBoolean(KEY_PAUSED, v).apply()

    /** 定时暂停的自动恢复时间点（毫秒时间戳），0 表示无定时暂停 */
    var resumeAt: Long
        get() = sp.getLong(KEY_RESUME_AT, 0L)
        set(v) = sp.edit().putLong(KEY_RESUME_AT, v).apply()

    // ---- 同步对列表 ----

    /**
     * 用户配置的同步对：每个同步对 = 一个手机本地目录（SAF tree Uri）+ 一个服务器目录。
     * 以 JSON 字符串持久化。
     */
    var pairs: List<SyncPair>
        get() {
            val raw = sp.getString(KEY_PAIRS, "") ?: ""
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SyncPair(
                        id = o.optString("id"),
                        localUri = o.optString("localUri"),
                        localName = o.optString("localName"),
                        serverDir = o.optString("serverDir")
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("localUri", p.localUri)
                    put("localName", p.localName)
                    put("serverDir", p.serverDir)
                })
            }
            sp.edit().putString(KEY_PAIRS, arr.toString()).apply()
        }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_AUTO = "auto"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_SERVICE = "service_enabled"
        private const val KEY_PAUSED = "paused"
        private const val KEY_RESUME_AT = "resume_at"
        private const val KEY_PAIRS = "pairs"
    }
}
