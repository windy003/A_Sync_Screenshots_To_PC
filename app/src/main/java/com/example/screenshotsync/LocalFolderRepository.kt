package com.example.screenshotsync

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** 一个待同步的本地文件（来自 SAF 目录枚举） */
data class LocalFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    /** 最后修改时间（毫秒，自纪元）。无法获取时为 0。 */
    val lastModified: Long
)

/**
 * 基于 SAF（Storage Access Framework）访问用户选定的本地目录：
 * 枚举目录下的文件、上传后删除文件。
 *
 * 通过 DocumentsContract 直接查询子文档，比 DocumentFile 更高效，
 * 一次查询即可拿到 文档ID/文件名/大小/MIME。
 */
object LocalFolderRepository {

    /** 列出目录下的所有文件（跳过子目录）。失败时返回空列表。 */
    fun listFiles(resolver: ContentResolver, treeUri: Uri): List<LocalFile> {
        val result = ArrayList<LocalFile>()
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return result
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val mime = c.getString(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = c.getString(1) ?: id.substringAfterLast('/')
                    val size = if (c.isNull(2)) 0L else c.getLong(2)
                    val lastModified = if (c.isNull(4)) 0L else c.getLong(4)
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    result.add(LocalFile(fileUri, name, size, lastModified))
                }
            }
        } catch (e: Exception) {
            // 目录不可访问（权限被撤销 / 已删除）时返回已收集到的部分
        }
        return result
    }

    /** 删除一个文件，成功返回 true */
    fun delete(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            false
        }
    }

    /** 从 tree Uri 推导一个可读名称，如 "Pictures/Screenshots" */
    fun displayName(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            // docId 形如 "primary:Pictures/Screenshots"，取冒号后的部分更易读
            docId.substringAfter(':', docId)
        } catch (e: Exception) {
            treeUri.toString()
        }
    }
}
