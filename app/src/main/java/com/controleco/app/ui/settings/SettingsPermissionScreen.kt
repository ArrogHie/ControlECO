package com.controleco.app.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.keepalive.Brand
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.SettingsDivider
import com.controleco.app.ui.common.SettingsNote
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SettingsPane
import com.controleco.app.ui.common.SettingsSection
import com.controleco.app.ui.common.SettingsStatusRow
import com.controleco.app.ui.common.SettingsSwitchRow
import com.controleco.app.ui.common.SettingsTutorial
import com.controleco.app.ui.common.a11yPathFor
import com.controleco.app.ui.common.overlayPathFor
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 设置 → 权限：核心通道总览 + 五段（通知监听 / 无障碍 / 随选朗读 / 悬浮窗 / 短信）。
 *
 * 每段结构一致：状态行（绿 pill 或灰 pill + 「去开启」）+ 开关 + 可折叠教程。
 * 从系统设置页返回时通过 ON_RESUME 重新探测授权状态；所有跳转与权限申请都保持原样。
 */
@Composable
fun SettingsPermissionScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
) {
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage(micro = "PERMISSIONS", title = "权限", onBack = onBack) {
        PermissionSummary(vm, resumeTick)
        NotificationSection(vm, resumeTick, onOpenNotificationAccess)
        A11ySection(vm, resumeTick, onOpenAccessibilitySettings)
        ScreenReaderSection(vm, resumeTick, onOpenAccessibilitySettings)
        OverlaySection(vm, resumeTick, onOpenOverlayPermission)
        BankSection(vm, resumeTick)
        SettingsNote("权限随时可以撤销：撤销后对应通道立即停止采集，已入账的账单不受影响。")
    }
}

// ---------- 总览 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionSummary(vm: LedgerViewModel, resumeTick: Int) {
    val c = MaterialTheme.eco
    val listener = remember(resumeTick) { vm.isListenerGranted() }
    val a11y = remember(resumeTick) { vm.isA11yEnabled() }
    val screenReader = remember(resumeTick) { vm.isScreenReaderEnabled() }
    val overlay = remember(resumeTick) { vm.isOverlayGranted() }
    val coreMissing = listOf(listener, a11y, screenReader, overlay).count { !it }
    val title = when {
        coreMissing == 0 -> "核心通道已就绪"
        coreMissing <= 2 -> "还需开启 $coreMissing 项核心权限"
        else -> "自动记账尚未就绪"
    }
    val subtitle = if (!screenReader) {
        "微信 8.0.52+ 页面读取还需要「随选朗读」"
    } else {
        "通知与页面通道均已连接，捕获后自动入账"
    }

    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = 8.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EcoIconTile(
                    icon = EcoIcons.Shield,
                    tint = c.jade,
                    size = 39.dp,
                    radius = 13.dp,
                    iconSize = 19.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = EcoType.RowTitle.copy(
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.W800,
                        ),
                        color = c.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = EcoType.RowSub.copy(fontSize = 8.5.sp, lineHeight = 13.sp),
                        color = c.muted,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ChannelPill("通知监听", listener, grantedText = "已连接")
                ChannelPill("无障碍", a11y, grantedText = "已连接")
                ChannelPill("随选朗读", screenReader, grantedText = "已开启")
                ChannelPill("悬浮窗", overlay, grantedText = "已授权")
            }
        }
    }
}

@Composable
private fun ChannelPill(label: String, granted: Boolean, grantedText: String) {
    EcoPill(
        text = "$label ${if (granted) grantedText else "未开启"}",
        tone = if (granted) EcoPillTone.Good else EcoPillTone.Neutral,
        dot = granted,
    )
}

// ---------- 通知监听 ----------

@Composable
private fun NotificationSection(vm: LedgerViewModel, resumeTick: Int, onOpenNotificationAccess: () -> Unit) {
    val granted = remember(resumeTick) { vm.isListenerGranted() }
    val captureEnabled by vm.captureEnabled.collectAsStateWithLifecycle()
    val bankPush by vm.bankPushEnabled.collectAsStateWithLifecycle()
    val amountReply by vm.amountReplyEnabled.collectAsStateWithLifecycle()
    val refundLinkEnabled by vm.refundLinkEnabled.collectAsStateWithLifecycle()
    val reeauthNeeded = remember(resumeTick) { vm.isReauthNeeded() }

    SettingsSection(number = "01", title = "通知监听", aside = "NOTIFICATION ACCESS")
    SettingsPane {
        SettingsStatusRow(
            title = "通知监听",
            subtitle = if (granted) "系统授权已确认" else "需要在系统设置中开启",
            granted = granted,
            grantedText = "已连接",
            missingText = "未开启",
            actionText = "去开启",
            onAction = onOpenNotificationAccess,
        )
        if (reeauthNeeded) {
            AmberBand(
                text = "应用刚更新过：通知监听可能已被系统静默关闭，若收不到动账请重新开关一次授权。",
                actionText = "去检查",
                onAction = onOpenNotificationAccess,
            )
        }
        SettingsDivider()
        SettingsSwitchRow(
            title = "自动记录收款 / 动账通知",
            subtitle = "从通知里识别金额、商户与方向",
            checked = captureEnabled,
            onCheckedChange = { vm.setCaptureEnabled(it) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "银行 App 动账推送",
            subtitle = "银行 App 的动账通知，靠【银行名】签名识别",
            checked = bankPush,
            onCheckedChange = { vm.setBankPushEnabled(it) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "金额待补录",
            subtitle = "方向可辨、金额缺失的通知入待确认，可下拉通知栏补录",
            checked = amountReply,
            onCheckedChange = { vm.setAmountReplyEnabled(it) },
        )
        SettingsDivider()
        CaptureNotifySetting(vm, resumeTick)
        SettingsDivider()
        SettingsSwitchRow(
            title = "退款到账关联提醒",
            subtitle = "退款通知不带金额，提醒你选择原支出",
            checked = refundLinkEnabled,
            onCheckedChange = { vm.setRefundLinkEnabled(it) },
        )
        SettingsDivider()
        SettingsTutorial(
            "通知监听开启教程",
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
        EcoToast.show(
            if (granted) "通知权限已授予" else "未授予通知权限，将收不到入账提醒",
            if (granted) EcoPillTone.Good else EcoPillTone.Warn,
        )
    }

    SettingsSwitchRow(
        title = "入账提醒",
        subtitle = "捕获后发「已记入一笔」通知，可直接撤销",
        checked = enabled,
        onCheckedChange = { vm.setCaptureNotifyEnabled(it) },
        trailing = {
            InlineAction(
                text = if (notifGranted) "横幅" else "权限",
                onClick = {
                    if (notifGranted) {
                        openCaptureChannelSettings(context)
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        },
    )
}

// ---------- 无障碍 ----------

@Composable
private fun A11ySection(vm: LedgerViewModel, resumeTick: Int, onOpenAccessibilitySettings: () -> Unit) {
    val enabled = remember(resumeTick) { vm.isA11yEnabled() }
    val captureOn by vm.a11yCaptureEnabled.collectAsStateWithLifecycle()

    SettingsSection(number = "02", title = "无障碍（页面采集）", aside = "ACCESSIBILITY")
    SettingsPane {
        SettingsStatusRow(
            title = "无障碍页面采集",
            subtitle = if (enabled) "系统授权已确认" else "需要在系统设置中开启",
            granted = enabled,
            grantedText = "已连接",
            missingText = "未开启",
            actionText = "去开启",
            onAction = onOpenAccessibilitySettings,
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "自动记录支付页面",
            subtitle = "开启后页面识别出的已支付交易会自动入账/进入待确认；关闭只保留捕获日志，不入账",
            checked = captureOn,
            onCheckedChange = { vm.setA11yCaptureEnabled(it) },
        )
        SettingsDivider()
        SettingsTutorial(
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
 * 随选朗读（或 TalkBack）与本应用的无障碍服务同时开启时，页面通道才能读到微信文本。
 */
@Composable
private fun ScreenReaderSection(vm: LedgerViewModel, resumeTick: Int, onOpenAccessibilitySettings: () -> Unit) {
    val enabled = remember(resumeTick) { vm.isScreenReaderEnabled() }

    SettingsSection(number = "03", title = "微信页面读取（随选朗读）", aside = "SCREEN READER")
    SettingsPane {
        SettingsStatusRow(
            title = "随选朗读",
            subtitle = if (enabled) "系统授权已确认" else "需要在系统设置中开启",
            granted = enabled,
            grantedText = "已开启",
            missingText = "未开启",
            actionText = "去开启",
            onAction = onOpenAccessibilitySettings,
        )
        SettingsDivider()
        PaneNote(
            "微信 8.0.52+ 只向系统读屏服务暴露页面节点。只开「无障碍页面采集」时，" +
                "微信页面可能读到空树；请同时开启随选朗读（或 TalkBack）。",
        )
        SettingsDivider()
        SettingsTutorial(
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

// ---------- 悬浮窗 ----------

/**
 * 悬浮窗入账：捕获到动账时在支付页上方弹出底部确认卡片（预填金额/商户/分类/账户）。
 * 未授权悬浮窗权限时自动回落横幅通知。
 */
@Composable
private fun OverlaySection(vm: LedgerViewModel, resumeTick: Int, onOpenOverlayPermission: () -> Unit) {
    val granted = remember(resumeTick) { vm.isOverlayGranted() }
    val overlayOn by vm.overlayConfirmEnabled.collectAsStateWithLifecycle()

    SettingsSection(number = "04", title = "悬浮窗入账", aside = "OVERLAY")
    SettingsPane {
        SettingsStatusRow(
            title = "悬浮窗权限",
            subtitle = if (granted) "系统授权已确认" else "需要在系统设置中开启",
            granted = granted,
            grantedText = "已授权",
            missingText = "未授权",
            actionText = "去授权",
            onAction = onOpenOverlayPermission,
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "悬浮窗入账",
            subtitle = "捕获后在支付页上方弹出确认卡，改完关掉即同步",
            checked = overlayOn,
            onCheckedChange = { vm.setOverlayConfirmEnabled(it) },
        )
        OverlayDecisionHint(vm)
        SettingsDivider()
        SettingsTutorial(
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
 * 最近一次弹卡判定（排查用）：卡片没弹时直接显示原因（开关未开/未授权/前台不符/系统拦截）。
 */
@Composable
private fun OverlayDecisionHint(vm: LedgerViewModel) {
    val decision by vm.overlayDecision.collectAsStateWithLifecycle()
    val text = decision ?: return
    SettingsDivider()
    PaneNote(
        if (text == "已弹出") "最近一次捕获：悬浮卡已正常弹出" else "最近一次捕获未弹卡：$text（排查用）",
        tone = if (text == "已弹出") MaterialTheme.eco.jade else MaterialTheme.eco.amber,
    )
}

// ---------- 短信 ----------

/**
 * 银行动账短信：只收新到短信（RECEIVE_SMS），不读历史、非默认短信应用；
 * 非银行号码与无银行签名的短信在采集层丢弃（内容不入库）。
 */
@Composable
private fun BankSection(vm: LedgerViewModel, resumeTick: Int) {
    val context = LocalContext.current
    val enabled by vm.smsCaptureEnabled.collectAsStateWithLifecycle()
    val granted = remember(resumeTick) { vm.isSmsPermissionGranted() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        EcoToast.show(
            if (ok) "短信权限已授予，银行动账短信将进入待确认" else "未授予短信权限，银行动账短信无法记录",
            if (ok) EcoPillTone.Good else EcoPillTone.Warn,
        )
    }

    SettingsSection(number = "05", title = "银行动账短信", aside = "SMS")
    SettingsPane {
        SettingsStatusRow(
            title = "收短信权限",
            subtitle = if (granted) "系统授权已确认" else "需要在系统弹窗中允许",
            granted = granted,
            grantedText = "已授权",
            missingText = "未授权",
            actionText = "去授权",
            onAction = { permissionLauncher.launch(android.Manifest.permission.RECEIVE_SMS) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "记录银行动账短信",
            subtitle = "开启时如未授权会先申请短信权限",
            checked = enabled,
            onCheckedChange = { on ->
                vm.setSmsCaptureEnabled(on)
                if (on && !granted) permissionLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
            },
        )
        SettingsDivider()
        SettingsTutorial(
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

// ---------- 共用小件 ----------

/** 权限页的行内动作词：与 pill 同排，10/800 蓝色 */
@Composable
private fun InlineAction(text: String, onClick: () -> Unit) {
    val c = MaterialTheme.eco
    Text(
        text,
        style = EcoType.Pill.copy(fontWeight = FontWeight.W800),
        color = c.blueDeep,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}

/** 卡片内的说明段落（9.5 / 1.6 muted） */
@Composable
private fun PaneNote(text: String, tone: androidx.compose.ui.graphics.Color = MaterialTheme.eco.muted) {
    Text(
        text,
        style = EcoType.RowSub,
        color = tone,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** 琥珀提示带：应用更新后通知监听被系统静默关闭时提示复查 */
@Composable
private fun AmberBand(text: String, actionText: String?, onAction: (() -> Unit)?) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.amberSoft)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            EcoIcons.Warning,
            contentDescription = null,
            tint = c.amber,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(13.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            style = EcoType.RowSub,
            color = c.amber,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                actionText,
                style = EcoType.Pill.copy(fontWeight = FontWeight.W800),
                color = c.amber,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** 跳转「自动记账提醒」渠道的通知设置（横幅/悬浮通知、重要性都在这里开） */
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
        }.onFailure { EcoToast.show("未找到通知设置页", EcoPillTone.Warn) }
    }
}
