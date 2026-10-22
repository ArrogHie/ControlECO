package com.controleco.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controleco.app.capture.CapturePermission
import com.controleco.app.ui.eco.EcoDialog
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 冷启动权限提醒：ECO 风格的居中 modal（不是系统 AlertDialog）。
 *
 * 内容按原型 permissionPrompt：amber 警告图标 + 标题 + 说明 + 缺失权限清单（逐项状态）
 * + 「稍后」/「去开启」。清单直接消费 [missing]，顺序与 [CapturePermission] 声明一致，
 * 权限名与图标/说明一一对应；回调保持 `onOpenPermissions` / `onDismiss` 不变。
 */
@Composable
fun PermissionReminderDialog(
    missing: List<CapturePermission>,
    onOpenPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    EcoDialog(onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 520.dp)) {
            EcoIconTile(
                icon = EcoIcons.Warning,
                tint = c.amber,
                size = 43.dp,
                radius = 14.dp,
                iconSize = 21.dp,
                background = c.amberSoft,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "还差几步才能自动记账",
                style = EcoType.ModalTitle,
                color = c.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "开启下面 ${missing.size} 项权限后，微信/支付宝的付款才会自动记入账本。",
                style = EcoType.ModalBody,
                color = c.muted,
            )
            Spacer(Modifier.height(11.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                missing.forEach { permission ->
                    MissingPermissionRow(permission)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(
                    text = "稍后",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                )
                EcoPrimaryButton(
                    text = "去开启",
                    onClick = onOpenPermissions,
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                )
            }
        }
    }
}

/** 一行缺失权限：图标 + 名称/说明 + 状态标签（未开启） */
@Composable
private fun MissingPermissionRow(permission: CapturePermission) {
    val c = MaterialTheme.eco
    val meta = permissionMeta(permission)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface2)
            .border(1.dp, c.rule, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EcoIconTile(
            icon = meta.icon,
            tint = c.amber,
            size = 28.dp,
            radius = 9.dp,
            iconSize = 14.dp,
            background = c.amberSoft,
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                permission.label,
                style = EcoType.RowTitle,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                meta.hint,
                style = EcoType.PageNote,
                color = c.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        EcoPill(text = "未开启", tone = EcoPillTone.Warn)
    }
}

private data class PermissionMeta(val icon: ImageVector, val hint: String)

private fun permissionMeta(permission: CapturePermission): PermissionMeta = when (permission) {
    CapturePermission.LISTENER ->
        PermissionMeta(EcoIcons.Bell, "读取微信/支付宝的付款通知")
    CapturePermission.A11Y ->
        PermissionMeta(EcoIcons.A11y, "读取微信/支付宝页面上的金额")
    CapturePermission.NOTIFY ->
        PermissionMeta(EcoIcons.Bell, "接收入账与待确认提醒")
    CapturePermission.SMS ->
        PermissionMeta(EcoIcons.Sms, "读取银行动账短信")
    CapturePermission.OVERLAY ->
        PermissionMeta(EcoIcons.Overlay, "在支付结果页弹出确认卡")
}
