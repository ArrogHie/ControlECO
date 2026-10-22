package com.controleco.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.util.AppLog
import java.io.File

/**
 * 分享入口（无需无障碍/通知权限的手动通道）：从微信/支付宝/邮箱等把账单文件
 * （.csv / .xlsx）分享到 易控钱包，落盘到应用缓存后直接跳到账单导入页并预解析。
 *
 * 设计：无界面（透明主题，读完即 finish）；只接受"能用账单解析器读懂"的文件——
 * 解析失败由导入页提示（与手动选文件同一条路径，不另造一套错误文案）。
 */
class ShareImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = extractSharedUri(intent)
        if (uri == null) {
            toast("没有收到可导入的文件")
            finish()
            return
        }
        try {
            val name = queryDisplayName(uri)
            val suffix = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (suffix.isNotEmpty() && suffix !in SUPPORTED_SUFFIXES) {
                toast("只支持微信/支付宝导出的 CSV / xlsx 账单文件")
                finish()
                return
            }
            val target = File(cacheDir, "shared_import_${System.currentTimeMillis()}.${suffix.ifEmpty { "csv" }}")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取分享的文件")
            AppLog.i("import", "shared file received: $name -> ${target.name}")
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(
                        CaptureCenter.EXTRA_ROUTE,
                        CaptureCenter.ROUTE_IMPORT_FILE_PREFIX + Uri.fromFile(target).toString(),
                    ),
            )
        } catch (e: Exception) {
            AppLog.e("import", "share import failed", e)
            toast("导入失败：${e.message ?: "无法读取文件"}")
        }
        finish()
    }

    /** ACTION_SEND（单文件）与 ACTION_VIEW（直接用本应用打开）都支持 */
    private fun extractSharedUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        val SUPPORTED_SUFFIXES = setOf("csv", "xlsx", "xls")
    }
}
