package com.example.screenshotsync

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

data class LocalImage(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateModified: Long,
    /** 绝对路径，用于在拥有「所有文件访问」权限时直接删除文件；可能为空 */
    val path: String?
) {
    /** 用于去重的唯一标识：文件名 + 大小 */
    val key: String get() = "$name|$size"
}

object ScreenshotRepository {

    /**
     * 通过 MediaStore 查询「截图」相册里的所有图片。
     * 用 BUCKET_DISPLAY_NAME = "Screenshots" 过滤，可同时覆盖
     * Pictures/Screenshots 和 DCIM/Screenshots 两种存放位置。
     */
    fun listScreenshots(resolver: ContentResolver): List<LocalImage> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATA
        )

        // 截图相册的 bucket 名称在国内外 ROM 上基本都是 "Screenshots"
        val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        val args = arrayOf("Screenshots")
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} ASC"

        val result = ArrayList<LocalImage>()
        resolver.query(collection, projection, selection, args, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result.add(
                    LocalImage(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "screenshot_$id.png",
                        size = c.getLong(sizeCol),
                        dateModified = c.getLong(dateCol),
                        path = if (dataCol >= 0) c.getString(dataCol) else null
                    )
                )
            }
        }
        return result
    }

    /**
     * 删除本地截图：优先按文件路径直接删除（需要「所有文件访问」/写入存储权限），
     * 再清理 MediaStore 记录。返回是否真正删除了文件。
     */
    fun deleteLocal(resolver: ContentResolver, image: LocalImage): Boolean {
        val path = image.path
        if (!path.isNullOrBlank()) {
            val f = File(path)
            val ok = if (f.exists()) f.delete() else true
            // 即便文件已不在，也尝试清掉可能残留的 MediaStore 记录
            try {
                resolver.delete(image.uri, null, null)
            } catch (_: Exception) {
            }
            return ok
        }
        // 没有路径时退回用 MediaStore 删除（同样需要相应权限）
        return try {
            resolver.delete(image.uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }
}
