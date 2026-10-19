package com.controleco.app.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.keepalive.Brand
import com.controleco.app.ui.common.PaneCard
import com.controleco.app.ui.common.PermissionTutorial
import com.controleco.app.ui.common.RowDivider
import com.controleco.app.ui.common.SectionTitle
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SwitchRow
import com.controleco.app.ui.common.a11yPathFor
import com.controleco.app.ui.common.overlayPathFor
import com.controleco.app.ui.theme.accBook

/**
 * 设置 → 权限：通知监听 / 无障碍 / 随选朗读 / 悬浮窗 / 短信的状态、开关与开启教程都在这一页。
 * 行上只留标题与开关；授权状态用右侧短标签表达（未授权时附「去开启」）。
 */
@Composable
fun SettingsPermissionScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
) {
    // 从系统设置页返回时重新探测授权状态
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage(title = "权限", onBack = onBack) {
        NotificationSection(vm, resumeTick, onOpenNotificationAccess)
        A11ySection(vm, resumeTick, onOpenAccessibilitySettings)
        ScreenReaderSection(vm, resumeTick, onOpenAccessibilitySettings)
        OverlaySection(vm, resumeTick, onOpenOverlayPermission)
        BankSection(vm, resumeTick)
    }
}

/** 权限状态行：标题 + 状态短标签（已授权=绿，未开启=灰）+ 未授权时的直达按钮 */
@Composable
private fun PermissionStatusRow(
    title: String,
    granted: Boolean,
    grantedText: String,
    ungrantedText: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (granted) grantedText else ungrantedText,
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) MaterialTheme.accBook.income else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun NotificationSection(vm: LedgerViewModel, resumeTick: Int, onOpenNotificationAccess: () -> Unit) {
    val granted = remember(resumeTick) { vm.isListenerGranted() }
    val captureEnabled by vm.captureEnabled.collectAsStateWithLifecycle()
    val refundLinkEnabled by vm.refundLinkEnabled.collectAsStateWithLifecycle()
    val reeauthNeeded = remember(resumeTick) { vm.isReauthNeeded() }

    SectionTitle("通知监听")
    PaneCard {
        PermissionStatusRow(
            title = "通知监听",
            granted = granted,
            grantedText = "已授权",
            ungrantedText = "未开启",
            actionText = "去开启",
            onAction = onOpenNotificationAccess,
        )
        if (reeauthNeeded) {
            RowDivider()
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "应用刚更新过：通知监听可能已被系统静默关闭，若收不到动账请重新开关一次授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.accBook.warning,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenNotificationAccess) { Text("去检查") }
            }
        }
        RowDivider()
        SwitchRow(
            title = "自动记录收款/动账通知",
            checked = captureEnabled,
            onCheckedChange = { vm.setCaptureEnabled(it) },
        )
        RowDivider()
        BankPushSetting(vm)
        RowDivider()
        AmountReplySetting(vm)
        RowDivider()
        CaptureNotifySetting(vm, resumeTick)
        RowDivider()
        SwitchRow(
            title = "退款到账关联提醒",
            checked = refundLinkEnabled,
            onCheckedChange = { vm.setRefundLinkEnabled(it) },
        )
        RowDivider()
        PermissionTutorial(
            "通知监听教程",
            listOf(
                "安卓 13+ 侧载应用需先解锁：应用信息 → 右上角「⋯」→ 允许受限制的设置" +
                    "（否则通知使用权列表里找不到或开不了 我ECO）。",
                "系统设置中搜索「通知使用权 / 通知访问」→ 找到 我ECO → 打开开关。",
                "回到本页状态应显示「已连接」；付款后可在捕获日志看到通知快照。",
                "若长时间无记录：重启手机 → 打开应用再看状态（部分机型需重新开关一次）。",
            ),
        )
    }
}

@Composable
private fun CaptureNotifySetting(vm: LedgerViewModel, resumeTick: Int) {
    val enabled by vm.captureNotifyEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifGranted = remember(resumeTick) { vm.canPostNotifications() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) "通知权限已授予" else "未授予通知权限，将收不到入账提醒",
            Toast.LENGTH_LONG,
        ).show()
    }

    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "入账提醒",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (!notifGranted) {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text("权限") }
        } else {
            TextButton(onClick = { openCaptureChannelSettings(context) }) { Text("横幅") }
        }
        Switch(checked = enabled, onCheckedChange = { vm.setCaptureNotifyEnabled(it) })
    }
}

@Composable
private fun A11ySection(vm: LedgerViewModel, resumeTick: Int, onOpenAccessibilitySettings: () -> Unit) {
    val enabled = remember(resumeTick) { vm.isA11yEnabled() }
    val captureOn by vm.a11yCaptureEnabled.collectAsStateWithLifecycle()

    SectionTitle("无障碍（页面采集）")
    PaneCard {
        PermissionStatusRow(
            title = "无障碍页面采集",
            granted = enabled,
            grantedText = "已授权",
            ungrantedText = "未开启",
            actionText = "去开启",
            onAction = onOpenAccessibilitySettings,
        )
        RowDivider()
        SwitchRow(
            title = "自动记录支付页面",
            subtitle = "开启后页面识别出的已支付交易会自动入账/进入待确认；关闭只保留捕获日志，不入账",
            checked = captureOn,
            onCheckedChange = { vm.setA11yCaptureEnabled(it) },
        )
        RowDivider()
        PermissionTutorial(
            "无障碍开启教程",
            listOf(
                "安卓 13+ 侧载应用需先解锁：应用信息 → 右上角「⋯」→ 允许受限制的设置" +
                    "（否则无障碍列表里找不到或开不了 我ECO）。",
                a11yPathFor(Brand.current()),
                "打开「我ECO 支付页面采集」开关；系统弹 10 秒倒计时确认属正常（小米十秒盾）。",
                "回到本页状态应显示「已连接」；付款后在捕获日志的「页面」通道能看到页面快照。",
            ),
        )
    }
}

/**
 * 微信 8.0.52+ 的页面节点只对系统读屏服务开放。
 * 随选朗读（或 TalkBack）与本应用的无障碍服务同时开启时，页面通道才能读到微信文本；
 * 这里给出状态与开启步骤，未开启时冷启动权限提醒也会列出该项。
 */
@Composable
private fun ScreenReaderSection(vm: LedgerViewModel, resumeTick: Int, onOpenAccessibilitySettings: () -> Unit) {
    val enabled = remember(resumeTick) { vm.isScreenReaderEnabled() }

    SectionTitle("微信页面读取（随选朗读）")
    PaneCard {
        PermissionStatusRow(
            title = "随选朗读",
            granted = enabled,
            grantedText = "已开启",
            ungrantedText = "未开启",
            actionText = "去开启",
            onAction = onOpenAccessibilitySettings,
        )
        RowDivider()
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "微信 8.0.52+ 只向系统读屏服务暴露页面节点。只开「无障碍页面采集」时，" +
                    "微信页面可能读到空树；请同时开启随选朗读（或 TalkBack）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowDivider()
        PermissionTutorial(
            "随选朗读开启教程",
            listOf(
                "系统设置 → 无障碍 → 随选朗读（Select to Speak），打开开关；找不到时在设置里搜索「随选朗读」。",
                "「随选朗读」与上方「我ECO 支付页面采集」需要同时保持开启；两者都在无障碍的已下载服务/应用里。",
                "回到本页状态应显示「已开启」；若微信页面仍为空树，关闭再重新打开两个服务，并在微信页面停留 1–2 秒。",
                "没有随选朗读时可改用 TalkBack；它也会朗读屏幕内容，按需选择。",
                "不使用微信页面通道时，可在上方关闭「自动记录支付页面」；通知与转账仍可正常记账。",
            ),
        )
    }
}

@Composable
private fun BankPushSetting(vm: LedgerViewModel) {
    val enabled by vm.bankPushEnabled.collectAsStateWithLifecycle()
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("银行 App 动账推送", style = MaterialTheme.typography.bodyLarge)
            Text(
                "银行 App 的动账通知（与动账短信同源，靠【银行名】签名识别）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = { vm.setBankPushEnabled(it) })
    }
}

/** 金额待补录：方向可辨但金额缺失的通知不再丢弃，入队 0 元条目 + 通知栏直接输入金额回填 */
@Composable
private fun AmountReplySetting(vm: LedgerViewModel) {
    val enabled by vm.amountReplyEnabled.collectAsStateWithLifecycle()
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("金额待补录", style = MaterialTheme.typography.bodyLarge)
            Text(
                "金额缺失的交易进待确认队列，下拉通知栏点「输入金额」直接补录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = { vm.setAmountReplyEnabled(it) })
    }
}

/**
 * 最近一次弹卡判定（排查用）：卡片没弹时直接显示原因（开关未开/未授权/前台不符/系统拦截），
 * 不用抓日志就能定位；"已弹出"则显示正常状态。
 */
@Composable
private fun OverlayDecisionHint(vm: LedgerViewModel) {
    val decision by vm.overlayDecision.collectAsStateWithLifecycle()
    val text = decision ?: return
    RowDivider()
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            if (text == "已弹出") "最近一次捕获：悬浮卡已正常弹出" else "最近一次捕获未弹卡：$text（排查用）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 悬浮窗入账：捕获到动账时在支付页上方弹出底部确认卡片（预填金额/商户/分类/账户），
 * 关闭卡片或长时间不操作视为入账，点「忽略」才不入账。未授权悬浮窗权限时自动回落横幅通知。
 */
@Composable
private fun OverlaySection(vm: LedgerViewModel, resumeTick: Int, onOpenOverlayPermission: () -> Unit) {
    val granted = remember(resumeTick) { vm.isOverlayGranted() }
    val overlayOn by vm.overlayConfirmEnabled.collectAsStateWithLifecycle()

    SectionTitle("悬浮窗入账")
    PaneCard {
        PermissionStatusRow(
            title = "悬浮窗权限",
            granted = granted,
            grantedText = "已授权",
            ungrantedText = "未授权",
            actionText = "去授权",
            onAction = onOpenOverlayPermission,
        )
        RowDivider()
        SwitchRow(
            title = "悬浮窗入账",
            checked = overlayOn,
            onCheckedChange = { vm.setOverlayConfirmEnabled(it) },
        )
        OverlayDecisionHint(vm)
        RowDivider()
        PermissionTutorial(
            "悬浮窗授权教程",
            listOf(
                "推荐：点上方「去授权」，直达「显示在其他应用上层 / 悬浮窗」开关，打开即可。" +
                    "未授权不影响记账：捕获到的账会先记好，只是少了确认卡。",
                overlayPathFor(Brand.current()),
                "卡片打开时这笔账已经记好：改金额/分类/账户后关掉卡片即同步修改；" +
                    "点「忽略」删除这笔；关掉卡片（或长时间不操作）＝保留这笔。",
                "若开关为灰色不可开：安卓 13+ 需先做「允许受限制的设置」（见通知监听教程第 1 步）。",
                "小米/澎湃额外需要「后台弹出界面」类权限：设置 → 应用管理 → 我ECO → 权限 → 后台弹出界面，允许。",
                "付款后若 Logcat 有 confirm card shown 但看不到卡片：是支付保护中心/支付保险箱拦截，" +
                    "见保活设置；可在开发者功能里用「悬浮确认卡（立即）」测试。",
            ),
        )
    }
}

/**
 * 银行动账短信：只收新到短信（RECEIVE_SMS），不读历史、非默认短信应用；
 * 非银行号码与无银行签名的短信在采集层丢弃（内容不入库）。开关默认关，开启时申请权限。
 */
@Composable
private fun BankSection(vm: LedgerViewModel, resumeTick: Int) {
    val context = LocalContext.current
    val enabled by vm.smsCaptureEnabled.collectAsStateWithLifecycle()
    val granted = remember(resumeTick) { vm.isSmsPermissionGranted() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        Toast.makeText(
            context,
            if (ok) "短信权限已授予，银行动账短信将进入待确认" else "未授予短信权限，银行动账短信无法记录",
            Toast.LENGTH_LONG,
        ).show()
    }

    SectionTitle("银行动账短信")
    PaneCard {
        PermissionStatusRow(
            title = "收短信权限",
            granted = granted,
            grantedText = "已授权",
            ungrantedText = "未授权",
            actionText = "去授权",
            onAction = { permissionLauncher.launch(android.Manifest.permission.RECEIVE_SMS) },
        )
        RowDivider()
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "记录银行动账短信",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    vm.setSmsCaptureEnabled(on)
                    if (on && !granted) permissionLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
                },
            )
        }
        RowDivider()
        PermissionTutorial(
            "为什么需要短信权限",
            listOf(
                "银行卡直接刷卡/被扣款时可能没有任何 App 通知，动账短信是唯一的线索。",
                "只接收新到的短信，不读取历史短信、不需要成为默认短信应用。",
                "只有银行号码（95588 等）或带【银行名】签名的短信会被解析；" +
                    "其他短信在采集层直接丢弃，内容不会入库。",
                "关闭本开关或撤销权限后，短信通道立即失效。",
            ),
        )
    }
}

/** 跳转"自动记账提醒"渠道的通知设置（横幅/悬浮通知、重要性都在这里开） */
private fun openCaptureChannelSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.controleco.app.capture.CaptureCenter.CHANNEL_CAPTURE),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }.onFailure { Toast.makeText(context, "未找到通知设置页", Toast.LENGTH_SHORT).show() }
    }
}
