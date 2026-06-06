package com.example.screenshotsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.screenshotsync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** 申请「所有文件访问」后返回，重新检查并启动 */
    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hasStorageAccess()) {
                requestNotificationThenStart()
            } else {
                SyncState.log("未授予「所有文件访问」权限，无法在后台自动删除本地截图。")
            }
        }

    /** Android 13+ 通知权限（不阻塞流程） */
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 忽略结果 */ }

    /** Android 9 及以下的读写存储权限 */
    private val legacyStorageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                requestNotificationThenStart()
            } else {
                SyncState.log("未授予存储权限，无法读取/删除截图。")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        restoreFields()

        binding.btnSync.setOnClickListener { onStartClicked() }
        binding.btnPause.setOnClickListener { onPauseToggle() }
        binding.btnPause30.setOnClickListener { SyncService.pause30(this) }
        binding.btnStop.setOnClickListener { SyncService.stop(this) }
        binding.btnClearLog.setOnClickListener { SyncState.clearLogs() }

        observeState()

        // 打开即自动开启后台同步
        if (prefs.autoSync && prefs.host.isNotBlank() && prefs.sharePath.isNotBlank()) {
            onStartClicked()
        }
    }

    private fun restoreFields() {
        binding.etHost.setText(prefs.host)
        binding.etSharePath.setText(prefs.sharePath)
        binding.etUser.setText(prefs.username)
        binding.etPassword.setText(prefs.password)
        binding.etDomain.setText(prefs.domain)
        binding.cbAutoSync.isChecked = prefs.autoSync
    }

    private fun saveFields() {
        prefs.host = binding.etHost.text.toString().trim()
        prefs.sharePath = binding.etSharePath.text.toString().trim()
        prefs.username = binding.etUser.text.toString().trim()
        prefs.password = binding.etPassword.text.toString()
        prefs.domain = binding.etDomain.text.toString().trim()
        prefs.autoSync = binding.cbAutoSync.isChecked
    }

    // ---- 启动 / 暂停 / 状态 ----

    private fun onStartClicked() {
        saveFields()

        if (prefs.host.isBlank() || prefs.sharePath.isBlank() || prefs.username.isBlank()) {
            SyncState.log("请先填写服务器 IP、共享路径和用户名。")
            return
        }

        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        requestNotificationThenStart()
    }

    private fun requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 重新启动时清掉暂停标志
        prefs.paused = false
        SyncState.log("开启后台同步…")
        SyncService.start(this)
    }

    private fun onPauseToggle() {
        if (SyncState.paused.value) {
            SyncService.resume(this)
        } else {
            SyncService.pause(this)
        }
    }

    // ---- 权限 ----

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            read && write
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SyncState.log("需要「所有文件访问」权限才能在后台自动删除截图，请在弹出的设置页开启。")
            val intent = try {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            } catch (e: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            allFilesLauncher.launch(intent)
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    // ---- 观察服务状态 ----

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SyncState.status.collect { updateControls() }
                }
                launch {
                    SyncState.running.collect { updateControls() }
                }
                launch {
                    SyncState.paused.collect { updateControls() }
                }
                launch {
                    SyncState.pausedUntil.collect { updateControls() }
                }
                launch {
                    SyncState.logs.collect { lines ->
                        binding.tvLog.text = lines.joinToString("\n")
                        binding.tvLog.post {
                            (binding.tvLog.parent.parent as? android.widget.ScrollView)
                                ?.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }

    private fun updateControls() {
        val running = SyncState.running.value
        val paused = SyncState.paused.value
        val until = SyncState.pausedUntil.value

        val pauseSuffix = when {
            !running || !paused -> ""
            until > 0L -> "（已暂停，${formatTime(until)} 自动继续）"
            else -> "（已暂停）"
        }
        binding.tvStatus.text = "状态：${SyncState.status.value}$pauseSuffix"

        binding.btnSync.isEnabled = !running
        binding.btnSync.text = if (running) "后台同步运行中" else "开启后台同步"

        binding.btnPause.isEnabled = running
        binding.btnPause.text = if (paused) "继续" else "暂停"

        // 已经暂停时再按「暂停30分」无意义，仅在运行且未暂停时可用
        binding.btnPause30.isEnabled = running && !paused

        binding.btnStop.isEnabled = running
    }

    private fun formatTime(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
}
