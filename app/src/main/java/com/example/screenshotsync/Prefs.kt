package com.example.screenshotsync

import android.content.Context

/**
 * 简单的本地配置存储。
 *
 * 注意：密码以明文存储在 SharedPreferences 中（仅本机可读）。对于个人局域网工具
 * 可以接受；如果对安全性有更高要求，可改用 EncryptedSharedPreferences。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("screenshot_sync", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOST, v).apply()

    var sharePath: String
        get() = sp.getString(KEY_SHARE, "") ?: ""
        set(v) = sp.edit().putString(KEY_SHARE, v).apply()

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

    /** 已成功上传过的文件标识集合，避免重复上传 */
    var syncedKeys: MutableSet<String>
        get() = HashSet(sp.getStringSet(KEY_SYNCED, emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet(KEY_SYNCED, v).apply()

    fun markSynced(key: String) {
        val set = syncedKeys
        set.add(key)
        syncedKeys = set
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_SHARE = "share"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_AUTO = "auto"
        private const val KEY_SERVICE = "service_enabled"
        private const val KEY_PAUSED = "paused"
        private const val KEY_RESUME_AT = "resume_at"
        private const val KEY_SYNCED = "synced"
    }
}
