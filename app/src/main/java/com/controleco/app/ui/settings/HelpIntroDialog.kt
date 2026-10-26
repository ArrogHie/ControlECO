package com.controleco.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoDialog
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 首次使用引导：问用户要不要看帮助（`HelpOnboarding.shouldPrompt` 为 true 时由 `AccBookNav` 弹出）。
 *
 * 只弹一次；「暂不查看」也算问过，之后从「设置 → 关于 → 帮助」进入。
 * 版式与 [com.controleco.app.ui.common.PermissionReminderDialog] 同一套：图标瓦片 + 标题 + 说明 + 双按钮。
 */
@Composable
fun HelpIntroDialog(
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    EcoDialog(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            EcoIconTile(
                icon = EcoIcons.Book,
                tint = c.blue,
                size = 43.dp,
                radius = 14.dp,
                iconSize = 21.dp,
                background = c.blueSoft,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "要看使用方法吗",
                style = EcoType.ModalTitle,
                color = c.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "帮助页里说明了预算口径、自动记账的重复与遗漏处理，以及常见问题，看完就能上手。",
                style = EcoType.ModalBody,
                color = c.muted,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(
                    text = "暂不查看",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                )
                EcoPrimaryButton(
                    text = "查看帮助",
                    onClick = onOpenHelp,
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                )
            }
        }
    }
}
