package com.controleco.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.controleco.app.capture.CapturePermission

/**
 * 打开 App 时的权限提醒：只列缺失项的短标签（尽量少的文字），一键进设置-权限逐个开启。
 */
@Composable
fun PermissionReminderDialog(
    missing: List<CapturePermission>,
    onOpenPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动记账尚未就绪") },
        text = {
            Column {
                Text(
                    "缺少 ${missing.size} 项：${missing.joinToString("、") { it.label }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "开启后才能自动记下微信/支付宝付款",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (CapturePermission.SCREEN_READER in missing) {
                    Text(
                        "微信 8.0.52+ 还需要系统「随选朗读」同时开启，否则读不到微信页面；" +
                            "权限页里有开启步骤。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenPermissions) { Text("去开启") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}
