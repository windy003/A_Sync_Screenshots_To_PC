package com.example.screenshotsync

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快速设置磁贴：一键暂停 30 分钟 / 继续。
 *
 * - 后台同步未开启时：点击开启后台同步。
 * - 正在监视时：点击暂停 30 分钟（到点由 SyncService 的闹钟自动继续）。
 * - 已暂停时：点击立即继续。
 *
 * 磁贴与 SyncService 同进程，直接订阅进程内的实时状态 [SyncState]，
 * 状态一变化磁贴立即刷新，无需关闭再展开下拉面板。
 */
class PauseTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        // 面板展开期间持续订阅实时状态，任何变化都即时反映到磁贴
        val s = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope = s
        s.launch {
            combine(
                SyncState.running,
                SyncState.paused,
                SyncState.pausedUntil
            ) { running, paused, until -> Triple(running, paused, until) }
                .collect { (running, paused, until) -> render(running, paused, until) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // 以实时状态判断当前该执行的动作；服务会同步更新 SyncState，
        // 订阅流随即触发 render()，磁贴立即反映新状态。
        val running = SyncState.running.value
        val paused = SyncState.paused.value
        when {
            !running -> SyncService.start(this)
            paused -> SyncService.resume(this)
            else -> SyncService.pause30(this)
        }
    }

    private fun render(running: Boolean, paused: Boolean, resumeAt: Long) {
        val tile = qsTile ?: return
        // 高亮表示“正在暂停中”：仅当后台运行且处于暂停状态时点亮磁贴
        tile.state = if (running && paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_30)
        tile.label = when {
            !running -> "截图同步"
            paused && resumeAt > 0L -> "已暂停 · ${formatTime(resumeAt)}继续"
            paused -> "已暂停"
            else -> "暂停30分钟"
        }
        tile.updateTile()
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
