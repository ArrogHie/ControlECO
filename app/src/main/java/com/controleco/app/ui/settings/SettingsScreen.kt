package com.controleco.app.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.common.InfoRow
import com.controleco.app.ui.common.PaneCard
import com.controleco.app.ui.common.RowDivider
import com.controleco.app.ui.common.SectionTitle
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SwitchRow
import com.controleco.app.util.AppLog

/**
 * 设置（一级页）：以导航分发为主，各子页保持干净——
 * 自动记账（三个入账方式开关 + 权限）、其他（自动更新/检查更新/开发者功能/关于）。
 * 「账本」两项（账户、账单导入）不在本页：入口已移到个人页「本月预算」下方。
 * 例外：「自动记账」段落顶部放三个开关（入账提醒/悬浮窗入账/自动入账），其余行仍只做导航。
 */
@Composable
fun SettingsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenWatchedApps: () -> Unit,
    onOpenMerchantRules: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    LaunchedEffect(Unit) { AppLog.i("settings", "hub opened") }

    val context = LocalContext.current
    val captureNotify by vm.captureNotifyEnabled.collectAsStateWithLifecycle()
    val overlayConfirm by vm.overlayConfirmEnabled.collectAsStateWithLifecycle()
    val autoEntry by vm.autoEntryEnabled.collectAsStateWithLifecycle()
    val autoUpdate by vm.autoUpdateEnabled.collectAsStateWithLifecycle()

    SettingsPage(title = "设置", onBack = onBack) {
        SectionTitle("自动记账")
        PaneCard {
            SwitchRow(
                title = "入账提醒",
                subtitle = "捕获后先记账，再发通知提醒（可在通知里撤销）",
                checked = captureNotify,
                onCheckedChange = { vm.setCaptureNotifyEnabled(it) },
            )
            RowDivider()
            SwitchRow(
                title = "悬浮窗入账",
                subtitle = "捕获后先记账，并弹出确认卡；改完关掉卡片即同步",
                checked = overlayConfirm,
                onCheckedChange = { vm.setOverlayConfirmEnabled(it) },
            )
            RowDivider()
            SwitchRow(
                title = "自动入账",
                subtitle = "捕获后直接记账、不打扰；开启时会关闭悬浮窗入账",
                checked = autoEntry,
                onCheckedChange = { vm.setAutoEntryEnabled(it) },
            )
            RowDivider()
            InfoRow(title = "监听的 App", onClick = onOpenWatchedApps)
            RowDivider()
            InfoRow(title = "商户归类", onClick = onOpenMerchantRules)
            RowDivider()
            InfoRow(title = "权限", onClick = onOpenPermissions)
        }

        SectionTitle("其他")
        PaneCard {
            SwitchRow(
                title = "自动更新",
                checked = autoUpdate,
                onCheckedChange = { vm.setAutoUpdateEnabled(it) },
            )
            RowDivider()
            InfoRow(
                title = "检查更新",
                onClick = {
                    Toast.makeText(context, "正在检查更新…", Toast.LENGTH_SHORT).show()
                    vm.checkUpdateNow { message ->
                        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    }
                },
            )
            RowDivider()
            InfoRow(title = "开发者功能", onClick = onOpenDeveloper)
            RowDivider()
            InfoRow(title = "关于 我ECO", onClick = onOpenAbout)
        }
    }
}
