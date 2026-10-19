package com.controleco.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.controleco.app.ui.common.SettingsValueRow
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 设置 → 开发者功能：手动启动（链路测试）+ 日志与诊断（待确认、捕获日志、保活、调试开关）
 * + 自动记账溯源。所有"测试/排查"性质的东西集中在这一页，权限页与个人页保持干净。
 *
 * release 构建也保留本页；所有测试只在本机运行。
 */
@Composable
fun SettingsDeveloperScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenCaptureLog: () -> Unit,
    onOpenCaptureTrace: () -> Unit,
    onOpenKeepAlive: () -> Unit,
) {
    val lastCaptureAt by vm.lastCaptureAt.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val debugCaptureAll by vm.debugCaptureAll.collectAsStateWithLifecycle()
    val debugCaptureAllPages by vm.debugCaptureAllPages.collectAsStateWithLifecycle()
    val traceEnabled by vm.traceEnabled.collectAsStateWithLifecycle()
    val traceCount by vm.traceCount.collectAsStateWithLifecycle()

    fun toast(): (String) -> Unit = { message -> EcoToast.show(message, EcoPillTone.Blue) }

    SettingsPage(micro = "DEVELOPER", title = "开发者功能", onBack = onBack) {
        SettingsSection(number = "01", title = "手动启动", aside = "TEST")
        SettingsPane {
            DevActionRow(
                icon = EcoIcons.Bell,
                title = "动账通知（延迟 20 秒）",
                subtitle = "发一条测试捕获，检查通知监听链路",
                onStart = { vm.scheduleNotifyTest(toast()) },
            )
            SettingsDivider()
            // 悬浮确认卡排查：Toast 是系统级窗口不受支付保护层拦截——
            // Toast 可见而卡片不可见 = 卡片被支付保护层/系统拦截（见权限页教程）
            DevActionRow(
                icon = EcoIcons.Overlay,
                title = "悬浮确认卡（立即）",
                subtitle = "检查悬浮窗权限与系统拦截",
                onStart = { vm.showOverlayTest(0L, toast()) },
            )
            SettingsDivider()
            DevActionRow(
                icon = EcoIcons.Clock,
                title = "悬浮确认卡（延迟 20 秒）",
                subtitle = "先切到支付页，再等待卡片出现",
                onStart = { vm.showOverlayTest(20_000L, toast()) },
            )
        }

        SettingsSection(number = "02", title = "日志与诊断", aside = "DIAGNOSTICS")
        SettingsPane {
            SettingsRow(
                title = "待确认账单",
                subtitle = if (pendingCount == 0) "没有等待处理的条目" else "$pendingCount 笔等待处理",
                icon = EcoIcons.Receipt,
                iconTint = MaterialTheme.eco.amber,
                trail = "查看",
                trailColor = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = onOpenPending,
            )
            SettingsDivider()
            SettingsRow(
                title = "捕获日志",
                subtitle = "通知 / 页面 / 短信原始快照",
                icon = EcoIcons.File,
                iconTint = MaterialTheme.eco.violet,
                trail = "查看",
                trailColor = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = onOpenCaptureLog,
            )
            SettingsDivider()
            SettingsRow(
                title = "保活与自启动",
                subtitle = "服务状态 · 电池白名单 · 品牌步骤",
                icon = EcoIcons.Battery,
                iconTint = MaterialTheme.eco.jade,
                trail = "查看",
                trailColor = MaterialTheme.eco.blue,
                showChevron = true,
                onClick = onOpenKeepAlive,
            )
            SettingsDivider()
            SettingsValueRow(
                label = "最近捕获",
                value = if (lastCaptureAt == 0L) "尚未收到" else formatTimestamp(lastCaptureAt),
            )
            SettingsDivider()
            // release 也保留（开发者功能整体保留）：开关与捕获路径都不依赖 BuildConfig.DEBUG
            SettingsSwitchRow(
                title = "记录所有应用的通知（调试）",
                subtitle = "只影响采集范围，不改变解析规则",
                checked = debugCaptureAll,
                onCheckedChange = { vm.setDebugCaptureAll(it) },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "捕获所有页面（调试）",
                subtitle = "记录页面快照，便于补充解析器",
                checked = debugCaptureAllPages,
                onCheckedChange = { vm.setDebugCaptureAllPages(it) },
            )
        }

        SettingsSection(number = "03", title = "自动记账溯源", aside = "TRACE")
        SettingsPane {
            // 默认关：开启后每次与入账有关的捕获都留原始来源（通知原文/页面快照），
            // 供回查"这笔账从哪来"；关闭时零写入
            SettingsSwitchRow(
                title = "记录记账来源（溯源）",
                subtitle = "保存每笔自动入账 / 待确认的原始来源，关闭时零写入",
                checked = traceEnabled,
                onCheckedChange = { vm.setTraceEnabled(it) },
            )
            SettingsDivider()
            SettingsRow(
                title = "溯源记录",
                subtitle = if (traceEnabled) "记录中：最新在前，保留最近 1000 条" else "历史记录仍可查看（当前未在记录）",
                icon = EcoIcons.Spark,
                iconTint = MaterialTheme.eco.blue,
                trail = if (traceCount == 0) "暂无" else "$traceCount 条",
                showChevron = true,
                onClick = onOpenCaptureTrace,
            )
        }

        SettingsNote("release 构建也保留开发者功能；所有测试只在本机运行。")
    }
}

/** 手动启动行：图标 + 标题/说明 + 右侧「启动」动作（整行可点） */
@Composable
private fun DevActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onStart: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = MaterialTheme.eco.blue,
        onClick = onStart,
        trailing = {
            Spacer(Modifier.width(8.dp))
            Text(
                "启动",
                style = EcoType.RowTrail.copy(fontWeight = FontWeight.W700),
                color = MaterialTheme.eco.blue,
                maxLines = 1,
            )
        },
    )
}
