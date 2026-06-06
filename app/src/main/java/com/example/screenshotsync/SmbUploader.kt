package com.example.screenshotsync

import android.content.ContentResolver
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream
import java.util.Properties

/**
 * 负责连接 Win11 SMB 共享并上传文件。
 *
 * @param host       服务器 IP，如 192.168.1.50
 * @param sharePath  共享名 + 子目录，如 "MyShare/Screenshots"
 */
class SmbUploader(
    host: String,
    sharePath: String,
    private val username: String,
    private val password: String,
    domain: String
) {
    private val baseUrl: String = buildBaseUrl(host, sharePath)
    private val domain: String = domain.trim()

    private val ctx: CIFSContext by lazy { buildContext(username, password, domain) }

    /** 校验连接与凭据：目标目录可访问则返回 null，否则返回错误信息 */
    fun connectError(): String? {
        return try {
            val dir = SmbFile(baseUrl, ctx)
            if (!dir.exists()) "目标目录不存在：$baseUrl" else null
        } catch (e: Exception) {
            "连接失败：${e.message}"
        }
    }

    /**
     * 上传单个文件。
     * @return UploadResult 表示跳过 / 已上传 / 失败
     */
    fun upload(file: LocalFile, resolver: ContentResolver): UploadResult {
        return try {
            val dir = SmbFile(baseUrl, ctx)
            // 用 (parent, child) 构造，子文件名自动做 URL 编码
            val remote = SmbFile(dir, file.name)
            if (remote.exists() && remote.length() == file.size) {
                return UploadResult.Skipped
            }
            resolver.openInputStream(file.uri)?.use { input ->
                SmbFileOutputStream(remote).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
                }
            } ?: return UploadResult.Failed("无法读取本地文件")
            UploadResult.Uploaded
        } catch (e: Exception) {
            UploadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /** 构造一个带超时配置、绑定指定凭据的 jcifs 上下文 */
        private fun buildContext(username: String, password: String, domain: String): CIFSContext {
            val props = Properties().apply {
                put("jcifs.smb.client.minVersion", "SMB202")
                put("jcifs.smb.client.maxVersion", "SMB311")
                put("jcifs.smb.client.responseTimeout", "30000")
                put("jcifs.smb.client.soTimeout", "35000")
                put("jcifs.smb.client.connTimeout", "10000")
            }
            val base = BaseContext(PropertyConfiguration(props))
            val auth = NtlmPasswordAuthenticator(domain.trim(), username, password)
            return base.withCredentials(auth)
        }

        /**
         * 登录验证：用给定凭据连接服务器根并列出共享，验证主机可达且账号密码正确。
         * @return 成功返回 null，失败返回错误信息。
         */
        fun loginError(host: String, username: String, password: String, domain: String): String? {
            val cleanedHost = host.trim().removePrefix("smb://").trim('/')
            if (cleanedHost.isBlank()) return "请填写服务器 IP"
            return try {
                val ctx = buildContext(username, password, domain)
                val root = SmbFile("smb://$cleanedHost/", ctx)
                // 列出共享会触发认证：凭据错误时抛 SmbAuthException，主机不可达时抛连接异常
                root.list()
                null
            } catch (e: Exception) {
                "登录失败：${e.message}"
            }
        }

        private fun buildBaseUrl(host: String, sharePath: String): String {
            val cleanedHost = host.trim().removePrefix("smb://").trim('/')
            val segments = sharePath.trim()
                .removePrefix("smb://")
                .trim('/')
                .split('/')
                .filter { it.isNotEmpty() }
                .joinToString("/") { it.replace(" ", "%20") }
            return "smb://$cleanedHost/$segments/"
        }
    }
}

sealed class UploadResult {
    object Uploaded : UploadResult()
    object Skipped : UploadResult()
    data class Failed(val reason: String) : UploadResult()
}
