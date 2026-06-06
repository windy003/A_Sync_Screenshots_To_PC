package com.example.screenshotsync

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 前台服务：常驻后台监视「截图」目录，出现新截图自动上传到 SMB 共享，
 * 上传成功后删除本地文件。支持暂停 / 继续 / 停止。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()

    @Volatile
    private var rescanRequested = false

    private lateinit var prefs: Prefs
    private var observer: ContentObserver? = null
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        registerObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_PAUSE_30 -> setPausedTimed(PAUSE_DURATION_MS)
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> {
                // 启动 / 恢复
                prefs.serviceEnabled = true
                SyncState.running.value = true
                restorePauseState()
            }
        }

        startInForeground()
        startPolling()

        if (!SyncState.paused.value) kickScan()
        return START_STICKY
    }

    /**
     * 定时轮询：SAF 目录无法可靠用 ContentObserver 监听，因此周期性扫描各同步对。
     * MediaStore 的 ContentObserver 仍保留，用于截图/照片等媒体变化时即时触发。
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (SyncState.running.value && !SyncState.paused.value) kickScan()
            }
        }
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        scope.cancel()
        super.onDestroy()
    }

    // ---- 暂停 / 继续 / 停止 ----

    private fun setPaused(paused: Boolean) {
        // 手动暂停 / 继续都会取消定时暂停
        cancelResumeAlarm()
        prefs.resumeAt = 0L
        SyncState.pausedUntil.value = 0L

        SyncState.paused.value = paused
        prefs.paused = paused
        SyncState.status.value = if (paused) "已暂停" else "监视中"
        SyncState.log(if (paused) "⏸ 已暂停" else "▶ 已继续")
        updateNotification()
        if (!paused) kickScan()
    }

    /** 暂停指定时长，到点后由 AlarmManager 触发 ACTION_RESUME 自动继续 */
    private fun setPausedTimed(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        prefs.resumeAt = until
        SyncState.pausedUntil.value = until
        scheduleResumeAlarm(until)

        SyncState.paused.value = true
        prefs.paused = true
        SyncState.status.value = "已暂停"
        val mins = (durationMs / 60_000L).toInt()
        SyncState.log("⏸ 暂停 $mins 分钟，将于 ${formatTime(until)} 自动继续")
        updateNotification()
    }

    /** 服务启动 / 恢复时，根据持久化状态还原暂停（含定时暂停）状态 */
    private fun restorePauseState() {
        val resumeAt = prefs.resumeAt
        if (prefs.paused && resumeAt > 0L) {
            if (System.currentTimeMillis() >= resumeAt) {
                // 定时已到期，直接继续
                setPaused(false)
            } else {
                // 仍在定时暂停期内，恢复暂停并重新挂上闹钟
                SyncState.pausedUntil.value = resumeAt
                SyncState.paused.value = true
                scheduleResumeAlarm(resumeAt)
                SyncState.status.value = "已暂停"
                SyncState.log("⏸ 定时暂停中，将于 ${formatTime(resumeAt)} 自动继续")
            }
        } else {
            SyncState.paused.value = prefs.paused
            SyncState.pausedUntil.value = 0L
            if (SyncState.status.value == "未启动") {
                SyncState.status.value = if (prefs.paused) "已暂停" else "监视中"
            }
        }
    }

    private fun stopEverything() {
        SyncState.log("■ 已停止后台同步")
        cancelResumeAlarm()
        SyncState.running.value = false
        SyncState.paused.value = false
        SyncState.pausedUntil.value = 0L
        SyncState.status.value = "未启动"
        prefs.serviceEnabled = false
        prefs.paused = false
        prefs.resumeAt = 0L
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        stopForegroundCompat()
        stopSelf()
    }

    // ---- 定时恢复闹钟 ----

    private fun resumeAlarmPi(): PendingIntent {
        val intent = Intent(this, SyncService::class.java).setAction(ACTION_RESUME)
        return PendingIntent.getService(this, RESUME_REQ, intent, piFlags())
    }

    private fun scheduleResumeAlarm(triggerAt: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = resumeAlarmPi()
        // 用 setAndAllowWhileIdle：可穿透 Doze，且无需精确闹钟权限（30 分钟无需秒级精确）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelResumeAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(resumeAlarmPi())
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    // ---- 监视 ----

    private fun registerObserver() {
        if (observer != null) return
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (SyncState.running.value && !SyncState.paused.value) kickScan()
            }
        }
        contentResolver.registerContentObserver(uri, true, obs)
        observer = obs
    }

    // ---- 扫描 + 上传 ----

    private fun kickScan() {
        scope.launch {
            if (scanMutex.isLocked) {
                // 已有扫描在进行，标记需要再扫一轮即可，避免堆积
                rescanRequested = true
                return@launch
            }
            scanMutex.withLock {
                do {
                    rescanRequested = false
                    runScan()
                } while (rescanRequested && !SyncState.paused.value && SyncState.running.value)
            }
        }
    }

    private suspend fun runScan() {
        if (SyncState.paused.value || !SyncState.running.value) return

        if (prefs.host.isBlank() || prefs.username.isBlank()) {
            SyncState.status.value = "未配置服务器"
            updateNotification()
            return
        }

        val pairs = prefs.pairs
        if (pairs.isEmpty()) {
            SyncState.status.value = "未配置同步对"
            updateNotification()
            return
        }

        // 新文件刚生成时可能尚未写完，稍等片刻再处理
        delay(800)
        if (SyncState.paused.value || !SyncState.running.value) return

        for (pair in pairs) {
            if (SyncState.paused.value || !SyncState.running.value) break
            if (pair.localUri.isBlank() || pair.serverDir.isBlank()) continue

            val uploader = SmbUploader(
                host = prefs.host,
                sharePath = pair.serverDir,
                username = prefs.username,
                password = prefs.password,
                domain = prefs.domain
            )

            val connErr = uploader.connectError()
            if (connErr != null) {
                SyncState.status.value = "连接失败"
                SyncState.log("✗ [${pair.localName}] $connErr")
                updateNotification()
                continue
            }

            val files = LocalFolderRepository.listFiles(contentResolver, Uri.parse(pair.localUri))
            for (file in files) {
                if (SyncState.paused.value || !SyncState.running.value) break

                SyncState.status.value = "上传中…"
                updateNotification()

                when (val result = uploader.upload(file, contentResolver)) {
                    is UploadResult.Uploaded -> {
                        // 移动模式：上传成功后删除本地文件
                        val deleted = LocalFolderRepository.delete(contentResolver, file.uri)
                        SyncState.uploadedCount.value = SyncState.uploadedCount.value + 1
                        SyncState.log("↑ ${pair.localName}/${file.name}" + if (deleted) "（已删本地）" else "（本地删除失败）")
                    }
                    is UploadResult.Skipped -> {
                        // 服务器已有同名同大小文件，视为已同步，删本地
                        val deleted = LocalFolderRepository.delete(contentResolver, file.uri)
                        if (deleted) SyncState.log("✓ ${pair.localName}/${file.name}（服务器已存在，删本地）")
                    }
                    is UploadResult.Failed -> {
                        SyncState.log("✗ ${pair.localName}/${file.name}：${result.reason}")
                    }
                }
            }
        }

        SyncState.status.value = if (SyncState.paused.value) "已暂停" else "监视中"
        updateNotification()
    }

    // ---- 前台通知 ----

    private fun startInForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val paused = SyncState.paused.value
        val count = SyncState.uploadedCount.value

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            piFlags()
        )

        val toggle = if (paused) {
            NotificationCompat.Action(0, "继续", servicePi(ACTION_RESUME))
        } else {
            NotificationCompat.Action(0, "暂停", servicePi(ACTION_PAUSE))
        }
        val pause30 = NotificationCompat.Action(0, "暂停30分", servicePi(ACTION_PAUSE_30))
        val stop = NotificationCompat.Action(0, "停止", servicePi(ACTION_STOP))

        val until = SyncState.pausedUntil.value
        val text = buildString {
            append(SyncState.status.value)
            if (paused && until > 0L) append("，${formatTime(until)} 自动继续")
            if (count > 0) append("，已同步 $count 张")
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "截图同步（已暂停）" else "截图同步")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(toggle)

        // 未暂停时额外提供「暂停30分」快捷操作
        if (!paused) builder.addAction(pause30)

        return builder
            .addAction(stop)
            .build()
    }

    private fun servicePi(action: String): PendingIntent {
        val intent = Intent(this, SyncService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, piFlags())
    }

    private fun piFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "截图后台同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台监视并上传截图" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "screenshot_sync"
        private const val NOTIF_ID = 1001
        private const val RESUME_REQ = 2001

        /** 定时暂停时长：30 分钟 */
        const val PAUSE_DURATION_MS = 30 * 60 * 1000L

        /** 轮询扫描间隔：60 秒 */
        private const val POLL_INTERVAL_MS = 60_000L

        const val ACTION_START = "com.example.screenshotsync.START"
        const val ACTION_PAUSE = "com.example.screenshotsync.PAUSE"
        const val ACTION_RESUME = "com.example.screenshotsync.RESUME"
        const val ACTION_PAUSE_30 = "com.example.screenshotsync.PAUSE_30"
        const val ACTION_STOP = "com.example.screenshotsync.STOP"

        fun start(context: Context) = sendAction(context, ACTION_START)
        fun pause(context: Context) = sendAction(context, ACTION_PAUSE)
        fun resume(context: Context) = sendAction(context, ACTION_RESUME)
        fun pause30(context: Context) = sendAction(context, ACTION_PAUSE_30)
        fun stop(context: Context) = sendAction(context, ACTION_STOP)

        private fun sendAction(context: Context, action: String) {
            val intent = Intent(context, SyncService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
