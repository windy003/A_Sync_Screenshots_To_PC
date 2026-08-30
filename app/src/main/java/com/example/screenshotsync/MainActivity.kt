package com.example.screenshotsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.screenshotsync.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** Android 13+ 通知权限（不阻塞流程） */
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 忽略结果 */ }

    /** 选择手机本地目录（SAF）。返回目录的 tree Uri。 */
    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        restoreFields()
        renderPairs()
        applyLoginState()

        binding.btnAddPair.setOnClickListener { pickFolderLauncher.launch(null) }
        binding.btnLogin.setOnClickListener { onLoginClicked() }
        binding.btnLogout.setOnClickListener { onLogoutClicked() }
        binding.btnSync.setOnClickListener { onStartClicked() }
        binding.btnPause.setOnClickListener { onPauseToggle() }
        binding.btnPause30.setOnClickListener { SyncService.pause30(this) }
        binding.btnStop.setOnClickListener { SyncService.stop(this) }
        binding.btnClearLog.setOnClickListener { SyncState.clearLogs() }

        observeState()

        // 打开即自动开启后台同步（需已登录）
        if (prefs.autoSync && prefs.loggedIn && prefs.host.isNotBlank() && prefs.pairs.isNotEmpty()) {
            onStartClicked()
        }
    }

    private fun restoreFields() {
        binding.etHost.setText(prefs.host)
        binding.etUser.setText(prefs.username)
        binding.etPassword.setText(prefs.password)
        binding.etDomain.setText(prefs.domain)
        binding.etBackupUser1.setText(prefs.backupUsername1)
        binding.etBackupPassword1.setText(prefs.backupPassword1)
        binding.etBackupUser2.setText(prefs.backupUsername2)
        binding.etBackupPassword2.setText(prefs.backupPassword2)
        binding.cbAutoSync.isChecked = prefs.autoSync
    }

    private fun saveServerFields() {
        prefs.host = binding.etHost.text.toString().trim()
        prefs.username = binding.etUser.text.toString().trim()
        prefs.password = binding.etPassword.text.toString()
        prefs.domain = binding.etDomain.text.toString().trim()
        prefs.backupUsername1 = binding.etBackupUser1.text.toString().trim()
        prefs.backupPassword1 = binding.etBackupPassword1.text.toString()
        prefs.backupUsername2 = binding.etBackupUser2.text.toString().trim()
        prefs.backupPassword2 = binding.etBackupPassword2.text.toString()
        prefs.autoSync = binding.cbAutoSync.isChecked
    }

    // ---- 同步对管理 ----

    private fun onFolderPicked(uri: Uri) {
        // 持久化目录访问权限（含写权限，用于上传后删除），重启后服务仍可访问
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            SyncState.log("无法获取目录持久访问权限：${e.message}")
        }
        val name = LocalFolderRepository.displayName(uri)
        promptServerDir(uri.toString(), name, existing = null)
    }

    /** 弹窗输入/编辑该同步对对应的服务器目录 */
    private fun promptServerDir(localUri: String, localName: String, existing: SyncPair?) {
        val input = EditText(this).apply {
            hint = "服务器目录，如 共享名/子目录"
            setText(existing?.serverDir ?: "")
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("本地：$localName")
            .setMessage("填写要同步到的服务器目录（相对共享根）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val dir = input.text.toString().trim().trim('/')
                if (dir.isBlank()) {
                    SyncState.log("服务器目录不能为空，未保存该同步对。")
                    return@setPositiveButton
                }
                val list = prefs.pairs.toMutableList()
                if (existing != null) {
                    val idx = list.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) list[idx] = existing.copy(serverDir = dir)
                } else {
                    list.add(SyncPair(UUID.randomUUID().toString(), localUri, localName, dir))
                }
                prefs.pairs = list
                renderPairs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePair(pair: SyncPair) {
        AlertDialog.Builder(this)
            .setTitle("删除同步对")
            .setMessage("确定删除「${pair.localName} → ${pair.serverDir}」吗？\n（仅移除同步配置，不会动已上传的文件）")
            .setPositiveButton("删除") { _, _ ->
                prefs.pairs = prefs.pairs.filterNot { it.id == pair.id }
                // 尝试释放该目录的持久访问权限
                try {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(pair.localUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                renderPairs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderPairs() {
        val container = binding.pairsContainer
        container.removeAllViews()
        val pairs = prefs.pairs
        if (pairs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "（暂无同步对，点上方按钮添加）"
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (pair in pairs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val label = TextView(this).apply {
                text = "${pair.localName}\n  →  ${pair.serverDir}"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { promptServerDir(pair.localUri, pair.localName, pair) }
            }
            val del = Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = "删除"
                setOnClickListener { deletePair(pair) }
            }
            row.addView(label)
            row.addView(del)
            container.addView(row)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- 登录 / 退出登录（切换服务器）----

    /** 一组待尝试的登录凭据：用户名/密码 + 用于日志展示的标签 */
    private data class Credential(val label: String, val username: String, val password: String)

    /** 按顺序收集要尝试的凭据：主账号 → 备用账号1 → 备用账号2（用户名为空的槽位跳过）。 */
    private fun candidateCredentials(): List<Credential> {
        val list = mutableListOf<Credential>()
        val user = binding.etUser.text.toString().trim()
        if (user.isNotBlank()) {
            list.add(Credential("主账号", user, binding.etPassword.text.toString()))
        }
        val backup1 = binding.etBackupUser1.text.toString().trim()
        if (backup1.isNotBlank()) {
            list.add(Credential("备用账号1", backup1, binding.etBackupPassword1.text.toString()))
        }
        val backup2 = binding.etBackupUser2.text.toString().trim()
        if (backup2.isNotBlank()) {
            list.add(Credential("备用账号2", backup2, binding.etBackupPassword2.text.toString()))
        }
        return list
    }

    /**
     * 点击登录：保存当前服务器字段，依次尝试主账号和最多两个备用账号，
     * 第一个连接成功的凭据即停止尝试，并成为之后同步实际使用的账号。
     */
    private fun onLoginClicked() {
        saveServerFields()
        if (prefs.host.isBlank()) {
            SyncState.log("请先填写服务器 IP。")
            return
        }
        val candidates = candidateCredentials()
        if (candidates.isEmpty()) {
            SyncState.log("请至少填写一个账号的用户名。")
            return
        }

        binding.btnLogin.isEnabled = false
        binding.tvLoginStatus.text = "登录中…"
        SyncState.log("正在登录 ${prefs.host} …")

        lifecycleScope.launch {
            var success: Credential? = null
            for (cred in candidates) {
                SyncState.log("尝试${cred.label}（${cred.username}）…")
                val err = withContext(Dispatchers.IO) { verifyLogin(cred.username, cred.password) }
                if (err == null) {
                    success = cred
                    break
                } else {
                    SyncState.log("✗ ${cred.label} 登录失败：$err")
                }
            }
            if (success != null) {
                // 成功的凭据设为当前生效账号，后台同步服务读取的也是这一套。
                // 同时把主账号输入框的文字也换成它，避免之后 saveServerFields()
                // （例如点"开启后台同步"时）又用输入框里旧的主账号文字把它覆盖回去。
                binding.etUser.setText(success.username)
                binding.etPassword.setText(success.password)
                prefs.username = success.username
                prefs.password = success.password
                prefs.loggedIn = true
                SyncState.log("✓ 已登录 ${prefs.host}（使用${success.label}：${success.username}）")
            } else {
                prefs.loggedIn = false
                SyncState.log("✗ 所有账号均登录失败")
            }
            applyLoginState()
        }
    }

    /**
     * 验证登录：若已配置同步对，直接连其真实共享目录（最可靠）；否则连服务器根验证凭据。
     * @return 成功返回 null，失败返回错误信息。
     */
    private fun verifyLogin(username: String, password: String): String? {
        val pairs = prefs.pairs
        return if (pairs.isNotEmpty()) {
            SmbUploader(
                host = prefs.host,
                sharePath = pairs.first().serverDir,
                username = username,
                password = password,
                domain = prefs.domain
            ).connectError()
        } else {
            SmbUploader.loginError(prefs.host, username, password, prefs.domain)
        }
    }

    /** 点击退出登录：停止后台同步，解锁服务器输入框以便切换到另一台服务器（保留已填内容）。 */
    private fun onLogoutClicked() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后将停止后台同步，并解锁服务器设置，方便切换到另一台服务器。\n（已填的 IP / 用户名 / 密码会保留，可直接修改）")
            .setPositiveButton("退出登录") { _, _ ->
                if (SyncState.running.value) SyncService.stop(this)
                prefs.loggedIn = false
                SyncState.log("已退出登录，可修改服务器设置后重新登录。")
                applyLoginState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 根据登录状态刷新输入框可编辑性、登录/退出按钮与状态文字。 */
    private fun applyLoginState() {
        val loggedIn = prefs.loggedIn
        binding.etHost.isEnabled = !loggedIn
        binding.etUser.isEnabled = !loggedIn
        binding.etPassword.isEnabled = !loggedIn
        binding.etDomain.isEnabled = !loggedIn
        binding.etBackupUser1.isEnabled = !loggedIn
        binding.etBackupPassword1.isEnabled = !loggedIn
        binding.etBackupUser2.isEnabled = !loggedIn
        binding.etBackupPassword2.isEnabled = !loggedIn

        binding.btnLogin.isEnabled = !loggedIn
        binding.btnLogin.text = if (loggedIn) "已登录" else "登录"
        binding.btnLogout.isEnabled = loggedIn

        binding.tvLoginStatus.text = if (loggedIn) {
            val u = prefs.username
            "已登录：${prefs.host}" + if (u.isNotBlank()) "（$u）" else ""
        } else {
            "未登录（填好服务器信息后点登录）"
        }
        updateControls()
    }

    // ---- 启动 / 暂停 / 状态 ----

    private fun onStartClicked() {
        saveServerFields()

        if (!prefs.loggedIn) {
            SyncState.log("请先登录服务器，再开启后台同步。")
            return
        }
        if (prefs.host.isBlank() || prefs.username.isBlank()) {
            SyncState.log("请先填写服务器 IP 和用户名。")
            return
        }
        if (prefs.pairs.isEmpty()) {
            SyncState.log("请先添加至少一个同步对。")
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
                        // 时间倒序：最新的日志显示在最上面
                        binding.tvLog.text = lines.asReversed().joinToString("\n")
                        binding.tvLog.post {
                            (binding.tvLog.parent.parent as? android.widget.ScrollView)
                                ?.fullScroll(android.view.View.FOCUS_UP)
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

        binding.btnSync.isEnabled = prefs.loggedIn && !running
        binding.btnSync.text = when {
            !prefs.loggedIn -> "请先登录"
            !running -> "开启后台同步"
            paused && until > 0L -> "已暂停（${formatTime(until)} 自动继续）"
            paused -> "已暂停"
            else -> "后台同步运行中"
        }

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
