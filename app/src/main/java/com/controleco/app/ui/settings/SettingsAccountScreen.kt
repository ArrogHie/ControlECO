package com.controleco.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.SettingsDivider
import com.controleco.app.ui.common.SettingsLead
import com.controleco.app.ui.common.SettingsNote
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SettingsPane
import com.controleco.app.ui.common.SettingsRow
import com.controleco.app.ui.common.SettingsSection
import com.controleco.app.ui.theme.eco

/**
 * 账户（个人页「账本」/ 设置体系 → 账户）：只放账户与分类两个入口。
 * 预算相关内容在个人页；余额明细在各管理页内，这里不重复。
 */
@Composable
fun SettingsAccountScreen(
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    SettingsPage(micro = "ACCOUNTS", title = "账户", onBack = onBack) {
        SettingsSection(number = "01", title = "账户与分类", aside = "LEDGER")
        SettingsPane {
            SettingsRow(
                title = "账户管理",
                icon = EcoIcons.Wallet,
                iconTint = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = onOpenAccounts,
            )
            SettingsDivider()
            SettingsRow(
                title = "分类管理",
                icon = EcoIcons.App,
                iconTint = MaterialTheme.eco.jade,
                showChevron = true,
                onClick = onOpenCategories,
            )
        }
    }
}
