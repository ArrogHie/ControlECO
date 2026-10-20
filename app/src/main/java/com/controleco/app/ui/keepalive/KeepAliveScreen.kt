package com.controleco.app.ui.keepalive

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.keepalive.Brand
import com.controleco.app.keepalive.DeviceBrands
import com.controleco.app.keepalive.KeepAliveAction
import com.controleco.app.keepalive.KeepAliveIntents
import com.controleco.app.keepalive.KeepAliveStep
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.eco.EcoHairline
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 防杀后台与自启动引导（原型 5.19 keepAlive）。
 *
 * 版式：品牌 hero（42dp 图标 + 品牌/机型/系统 + 运行 pill + 三行服务状态）
 * → clay 警告卡（安卓 13+ 侧载解锁）→ 01 电池优化白名单 → 02 品牌设置步骤
 * （编号圆 + 可选跳转）→ 03 更多说明 → 页尾 note。
 *
 * 自启动/省电策略等开关大多无法程序检测，因此保持「状态可查的尽量显示 +
 * 步骤引导 + 直达跳转」的既有策略；探测结果在 ON_RESUME 时重算，跳转仍走
 * [KeepAliveIntents]，所有回调与状态语义不变，只做视觉重排。
 */
@Composable
fun KeepAliveScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val brand = remember { Brand.current() }
    val context = LocalContext.current
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val a11yConnected by vm.a11yConnected.collectAsStateWithLifecycle()
    val lastCaptureAt by vm.lastCaptureAt.collectAsStateWithLifecycle()

    // 从系统设置页返回时刷新可检测状态
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(resumeTick) { vm.isListenerGranted() }
    val a11yEnabled = remember(resumeTick) { vm.isA11yEnabled() }
    val batteryIgnoring = remember(resumeTick) { KeepAliveIntents.isIgnoringBatteryOptimizations(context) }
    val model = remember(resumeTick) { Build.MODEL ?: "" }
    val androidVersion = remember(resumeTick) { Build.VERSION.RELEASE ?: "" }

    val c = MaterialTheme.eco
    val steps = remember(brand) { DeviceBrands.stepsFor(brand) }
    val serviceRunning = connected || a11yConnected

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(micro = "KEEP ALIVE", title = "防杀后台与自启动", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 6.dp, bottom = ecoBottomContentPadding(extra = 10.dp)),
        ) {
            // ---------- 设备 hero：品牌 + 三行服务状态 ----------
            EcoPane(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(19.dp),
                elevation = 10.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EcoIconTile(
                            icon = EcoIcons.Phone,
                            tint = c.blue,
                            size = 42.dp,
                            radius = 14.dp,
                            iconSize = 20.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                brand.displayName,
                                style = EcoType.RowTitle,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                deviceLine(model, androidVersion),
                                style = EcoType.RowSub.copy(fontSize = 8.5.sp, lineHeight = 12.sp),
                                color = c.quiet,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ServicePill(running = serviceRunning, grantedAny = granted || a11yEnabled)
                    }
                    EcoHairline()
                    EcoRow(
                        title = "通知监听",
                        minHeight = 44.dp,
                        trailing = {
                            StatusValue(
                                text = when {
                                    granted && connected -> "已连接"
                                    granted -> "已授权（连接未知）"
                                    else -> "未授权"
                                },
                                tone = if (granted && connected) c.jade else c.amber,
                                actionLabel = if (!granted) "去开启" else null,
                                onAction = onOpenNotificationAccess,
                            )
                        },
                    )
                    EcoHairline()
                    EcoRow(
                        title = "无障碍页面采集",
                        minHeight = 44.dp,
                        trailing = {
                            StatusValue(
                                text = when {
                                    a11yEnabled && a11yConnected -> "已连接"
                                    a11yEnabled -> "已开启（连接未知）"
                                    else -> "未开启"
                                },
                                tone = if (a11yEnabled && a11yConnected) c.jade else c.amber,
                                actionLabel = if (!a11yEnabled) "去开启" else null,
                                onAction = onOpenAccessibilitySettings,
                            )
                        },
                    )
                    EcoHairline()
                    EcoRow(
                        title = "最近捕获通知 / 页面",
                        minHeight = 44.dp,
                        trailing = {
                            StatusValue(
                                text = if (lastCaptureAt == 0L) "尚未收到" else formatTimestamp(lastCaptureAt),
                                tone = null,
                            )
                        },
                    )
                }
            }

            // ---------- 安卓 13+ 侧载解锁（clay 警告卡） ----------
            WarnCard(
                title = "安卓 13+ 侧载解锁（重要，需最先做）",
                body = "安卓 13 起侧载应用默认被禁止开启无障碍与通知使用权：先到 应用信息 → 右上角「⋯」→ " +
                    "「允许受限制的设置」，然后才能在系统设置里开启 我ECO 的通知监听与无障碍。" +
                    "若系统设置列表里找不到 我ECO，通常就是缺这一步。",
                actionLabel = "打开应用信息",
                onAction = { KeepAliveIntents.launchAppDetails(context) },
                modifier = Modifier.padding(top = 10.dp),
            )

            // ---------- 01 电池优化白名单 ----------
            EcoSectionHead(number = "01", title = "电池优化白名单", aside = "BATTERY")
            EcoPane(modifier = Modifier.fillMaxWidth()) {
                EcoRow(
                    title = if (batteryIgnoring) "已忽略电池优化" else "未忽略电池优化",
                    minHeight = 58.dp,
                    trailing = {
                        if (batteryIgnoring) {
                            EcoPill(text = "已加入", tone = EcoPillTone.Good, dot = true)
                        } else {
                            EcoTextAction(
                                text = "申请忽略",
                                onClick = { KeepAliveIntents.requestIgnoreBatteryOptimizations(context) },
                            )
                        }
                    },
                )
            }

            // ---------- 02 品牌设置步骤 ----------
            EcoSectionHead(
                number = "02",
                title = "${brand.displayName} 设置步骤",
                aside = brand.name,
            )
            EcoPane(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    steps.forEachIndexed { index, step ->
                        if (index > 0) EcoHairline()
                        StepRow(index = index + 1, step = step, brand = brand)
                    }
                }
            }


        }
    }
}

// ---------- 小件 ----------

/** 品牌 hero 的右侧运行状态 pill（good 带 live 点，未就绪时为中性）。 */
@Composable
private fun ServicePill(running: Boolean, grantedAny: Boolean) {
    when {
        running -> EcoPill(text = "服务在运行", tone = EcoPillTone.Good, dot = true)
        grantedAny -> EcoPill(text = "待连接", tone = EcoPillTone.Warn, dot = true)
        else -> EcoPill(text = "未就绪", tone = EcoPillTone.Neutral)
    }
}

/**
 * 服务行右侧取值：可选 5dp 状态点 + 文本（jade/amber/muted）+ 可选「去开启」文字动作。
 * [tone] 为 null 时用 muted（如「最近捕获」时间）。
 */
@Composable
private fun StatusValue(
    text: String,
    tone: Color?,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = MaterialTheme.eco
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (tone != null) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = EcoType.RowTrail,
            color = tone ?: c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null) {
            Spacer(Modifier.width(4.dp))
            EcoTextAction(text = actionLabel, onClick = onAction)
        }
    }
}

/** clay 警告卡：warning 图标 + 标题 + 正文 + 可选直达动作（原型 `.warn-card`）。 */
@Composable
private fun WarnCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.claySoft)
            .border(1.dp, c.clay.copy(alpha = 0.15f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            EcoIcons.Warning,
            contentDescription = null,
            tint = c.clay,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = EcoType.RowTitle.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = c.clay,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = EcoType.RowSub.copy(fontSize = 8.5.sp, lineHeight = 14.sp),
                color = c.clay.copy(alpha = 0.92f),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(2.dp))
                EcoTextAction(
                    text = actionLabel,
                    onClick = onAction,
                    color = c.clay,
                )
            }
        }
    }
}

/** 品牌步骤行：23dp 编号圆（1px 描边 + blue 数字）+ 标题 + 说明 + 可选跳转动作。 */
@Composable
private fun StepRow(index: Int, step: KeepAliveStep, brand: Brand) {
    val c = MaterialTheme.eco
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(23.dp)
                .clip(CircleShape)
                .background(c.surface2)
                .border(1.dp, c.ruleStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(index.toString(), style = EcoType.SectionNo, color = c.blue)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = EcoType.RowTitle, color = c.ink)
            Spacer(Modifier.height(3.dp))
            Text(step.detail, style = EcoType.RowSub, color = c.muted)
            if (step.actions.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (KeepAliveAction.AUTOSTART in step.actions) {
                        EcoTextAction(
                            text = "打开自启动设置",
                            onClick = { KeepAliveIntents.openAutostartWithFallback(context, brand) },
                        )
                    }
                    if (KeepAliveAction.APP_INFO in step.actions) {
                        EcoTextAction(
                            text = "应用信息",
                            onClick = { KeepAliveIntents.launchAppDetails(context) },
                        )
                    }
                }
            }
        }
    }
}

/** 机型行：`23127PN0CC · Android 15`（型号缺失时给占位，不显示空点号）。 */
private fun deviceLine(model: String, androidVersion: String): String {
    val head = model.ifBlank { "未知机型" }
    return if (androidVersion.isBlank()) head else "$head · Android $androidVersion"
}
