package com.example.screenshotsync

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内共享的同步状态，供前台服务写入、界面读取。
 * 服务与界面在同一进程，使用 StateFlow 即可实时联动，无需广播。
 */
object SyncState {

    /** 后台服务是否在运行 */
    val running = MutableStateFlow(false)

    /** 是否处于暂停状态 */
    val paused = MutableStateFlow(false)

    /** 定时暂停的自动恢复时间点（毫秒时间戳），0 表示不是定时暂停 */
    val pausedUntil = MutableStateFlow(0L)

    /** 当前状态文字，例如「监视中」「上传中…」 */
    val status = MutableStateFlow("未启动")

    /** 已成功上传（并删除本地）的累计数量 */
    val uploadedCount = MutableStateFlow(0)

    /** 滚动日志（最多保留 MAX 行） */
    val logs = MutableStateFlow<List<String>>(emptyList())

    private const val MAX = 300

    fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $msg"
        val next = (logs.value + line)
        logs.value = if (next.size > MAX) next.takeLast(MAX) else next
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}
