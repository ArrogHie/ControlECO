package com.controleco.app.util

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 内置图片资源 → 系统相册（`Pictures/易控钱包`）。
 *
 * 写入的是**原始资源字节**，不重新编码：收款码是点阵图，二次 JPEG 压缩会糊掉码点，
 * 扫码成功率下降；顺带保住原图的 PNG/JPEG 格式。
 *
 * - Android 10（Q）及以上：`MediaStore` + `RELATIVE_PATH`，不需要任何存储权限；
 * - Android 8 / 9：公共 `Pictures` 目录 + 媒体扫描，需要 `WRITE_EXTERNAL_STORAGE`
 *   （清单里该权限限定 `maxSdkVersion=28`；调用方先用 [needsLegacyPermission] /
 *   [hasLegacyPermission] 判断，再申请运行时权限）。
 */
object AlbumSaver {
    /** 相册里的子目录名，同时也是系统相册里显示的相册名 */
    const val ALBUM_NAME = "易控钱包"

    /** MediaStore 相对路径：`Pictures/易控钱包`（MediaStore 里存的值带结尾斜杠） */
    val albumRelativePath: String get() = "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"

    /** Android 8 / 9 才需要写外部存储的运行时权限；10 起走 MediaStore，不需要 */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** 当前是否已拿到写外部存储的权限（10+ 恒为 true） */
    fun hasLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    /** 扩展名 → MIME；不认识的返回 null */
    fun mimeOf(extension: String): String? = when (extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
    }

    /**
     * 同名递增：`名字.png` → `名字-2.png` → `名字-3.png`……
     * 重复保存不覆盖旧图，也不在相册里堆一串同名文件。
     */
    fun nextName(baseName: String, extension: String, existing: Set<String>): String {
        val first = "$baseName.$extension"
        if (first !in existing) return first
        var index = 2
        while ("$baseName-$index.$extension" in existing) index++
        return "$baseName-$index.$extension"
    }

    /** 本地目录里的同名文件递增（Android 8/9 走这条路径） */
    fun uniqueFile(dir: File, baseName: String, extension: String): File {
        var file = File(dir, "$baseName.$extension")
        var index = 2
        while (file.exists()) {
            file = File(dir, "$baseName-$index.$extension")
            index++
        }
        return file
    }

    /**
     * 把 [resId] 的原始字节写入相册。
     *
     * @return 相册里的文件名（含扩展名）
     * @throws IllegalStateException 创建条目、读写文件或资源解码失败
     */
    fun saveRawResource(context: Context, resId: Int, baseName: String, extension: String): String {
        val mime = mimeOf(extension) ?: error("不支持的图片格式：$extension")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, resId, baseName, extension, mime)
        } else {
            saveToPublicPictures(context, resId, baseName, extension, mime)
        }
    }

    // ---------- Android 10+：MediaStore ----------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        resId: Int,
        baseName: String,
        extension: String,
        mime: String,
    ): String {
        val resolver = context.contentResolver
        val name = nextName(baseName, extension, existingMediaStoreNames(context))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "$albumRelativePath/")
            // IS_PENDING：写完之前对其它 App 不可见，避免相册里出现半张图
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在相册中创建图片条目")
        try {
            context.resources.openRawResource(resId).use { input ->
                (resolver.openOutputStream(uri) ?: error("无法写入相册")).use { output ->
                    input.copyTo(output)
                }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (t: Throwable) {
            // 失败时删掉刚插入的占位条目，别在相册里留空记录
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return name
    }

    /** 相册目录下已有的文件名：用来给重复保存挑一个不冲突的名字 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun existingMediaStoreNames(context: Context): Set<String> {
        val names = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf("$albumRelativePath/%"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
            }
        }.onFailure { AppLog.w("Album", "查询相册同名文件失败", it) }
        return names
    }

    // ---------- Android 8/9：公共 Pictures 目录 ----------

    @Suppress("DEPRECATION")
    private fun saveToPublicPictures(
        context: Context,
        resId: Int,
        baseName: String,
        extension: String,
        mime: String,
    ): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_NAME,
        )
        if (!dir.isDirectory && !dir.mkdirs()) error("无法创建相册目录")
        val file = uniqueFile(dir, baseName, extension)
        context.resources.openRawResource(resId).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        // 部分 ROM 不主动扫描直接写入的文件，这里通知媒体库，相册里才能立刻看到
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return file.name
    }
}
