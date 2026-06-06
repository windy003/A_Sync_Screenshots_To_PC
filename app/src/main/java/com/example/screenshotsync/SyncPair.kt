package com.example.screenshotsync

/**
 * 一个同步对：把手机上的某个本地目录同步到服务器上的某个目录。
 *
 * @param id        唯一标识（UUID）
 * @param localUri  手机本地目录的 SAF tree Uri（字符串形式），已持久化访问权限
 * @param localName 本地目录的可读名称，用于界面展示，如 "Pictures/Screenshots"
 * @param serverDir 服务器上的目标目录，相对共享根，如 "共享名/子目录"
 */
data class SyncPair(
    val id: String,
    val localUri: String,
    val localName: String,
    val serverDir: String
)
