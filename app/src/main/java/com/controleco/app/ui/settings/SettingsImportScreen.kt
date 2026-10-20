package com.controleco.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.SettingsDivider
import com.controleco.app.ui.common.SettingsNote
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SettingsPane
import com.controleco.app.ui.common.SettingsRow
import com.controleco.app.ui.common.SettingsSection
import com.controleco.app.ui.common.SettingsSwitchRow
import com.controleco.app.ui.theme.eco

/**
 * 账单导入（个人页「账本」→ 账单导入）：导入入口 + 微信/支付宝导出账单的路径教程
 * + 退款记录显示开关。所有回调与开关写回逻辑保持不变。
 */
@Composable
fun SettingsImportScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsPage(micro = "IMPORT", title = "账单导入", onBack = onBack) {
        SettingsSection(title = "导入", aside = "IMPORT")
        SettingsPane {
            SettingsRow(
                title = "选择账单文件",
                subtitle = "微信 xlsx / 支付宝 CSV",
                icon = EcoIcons.File,
                iconTint = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = onImport,
            )
        }

        SettingsSection(title = "导出账单", aside = "HOW TO")
        SettingsPane {
            SettingsRow(
                title = "微信",
                subtitle = "我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单，用途选「用于个人对账」，xlsx 会发送到邮箱。",
                subtitleMaxLines = 3,
                icon = EcoIcons.Phone,
                iconTint = MaterialTheme.eco.jade,
                minHeight = 66.dp,
            )
            SettingsDivider()
            SettingsRow(
                title = "支付宝",
                subtitle = "我的 → 账单 → 右上角「…」 → 开具交易流水证明，用途选「用于个人对账」，CSV 会发送到邮箱。",
                subtitleMaxLines = 3,
                icon = EcoIcons.App,
                iconTint = MaterialTheme.eco.blue,
                minHeight = 66.dp,
            )
        }

        SettingsSection(title = "显示", aside = "DISPLAY")
        SettingsPane {
            RefundDisplaySetting(vm)
        }

    }
}

@Composable
private fun RefundDisplaySetting(vm: LedgerViewModel) {
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = "显示退款记录",
        checked = showRefunds,
        onCheckedChange = { vm.setShowRefunds(it) },
    )
}
