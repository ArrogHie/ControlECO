package com.controleco.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CapturedNotificationEntity
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 捕获日志（设计稿 5.18）：通知 / 页面 / 短信三个通道的原始快照。
 *
 * 结构与原型一致：连接状态 → 通道筛选 → 条数/提示 → 行列表（通道徽标 · 应用 · 时间 →
 * 标题 → 正文两行截断 → 规则 + 状态胶囊）；点行内联展开原始快照，复制原文。
 * debug 构建仍可在 Logcat 用 tag=ControlECO 过滤全量明细（[Ntf]/[Page]/[Sms]）。
 */
@Composable
fun CaptureLogScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val rows by vm.captureRows.collectAsStateWithLifecycle(initialValue = emptyList())
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val a11yConnected by vm.a11yConnected.collectAsStateWithLifecycle()
    val filter by vm.captureFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var clearAsk by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    val granted = vm.isListenerGranted()
    val c = MaterialTheme.eco

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        EcoTopBar(
            micro = "CAPTURE LOG",
            title = "捕获日志",
            onBack = onBack,
            action = {
                if (rows.isNotEmpty()) {
                    EcoTextAction("清空", onClick = { clearAsk = true })
                }
            },
        )

        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(
                    color = when {
                        !granted -> c.clay
                        connected -> c.jade
                        else -> c.amber
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    captureStatusText(granted, connected, a11yConnected),
                    style = EcoType.RowSub.copy(fontSize = 9.5.sp),
                    color = c.muted,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                if (!granted) {
                    Spacer(Modifier.width(6.dp))
                    EcoTextAction(
                        text = "去开启",
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                            )
                        },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CAPTURE_FILTERS.forEach { option ->
                    FilterPill(
                        text = option.label,
                        selected = filter == option.channel,
                        onClick = { vm.setCaptureFilter(option.channel) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 ${rows.size} 条（仅保留最近记录）",
                    style = EcoType.PageNote,
                    color = c.quiet,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text("点条目看原文", style = EcoType.PageNote, color = c.quiet, maxLines = 1)
            }
        }

        if (rows.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                    EcoEmpty(
                        title = "暂无捕获记录",
                        body = "收到通知或打开支付页面后会出现；可按通道筛选查看",
                        icon = EcoIcons.Bell,
                    )
                }
            }
            EcoPageNote(
                "仅保留最近记录；点条目展开并复制原文。若长时间无记录，重启手机后重试。",
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp),
            ) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    LogRowCard(
                        row = row,
                        expanded = expandedId == row.id,
                        shape = paneShape(index, rows.size),
                        isLast = index == rows.lastIndex,
                        onToggle = {
                            expandedId = if (expandedId == row.id) null else row.id
                        },
                        onCopy = {
                            copyToClipboard(context, copyPayload(row))
                            EcoToast.show("已复制原文")
                        },
                    )
                }
                item(key = "note") {
                    EcoPageNote(
                        "仅保留最近记录；点条目可展开并复制原文。debug 构建下可在 Logcat 过滤 ControlECO 查看全部明细" +
                            "（[Ntf] 通知 / [Page] 页面 / [Sms] 短信）。若长时间无记录，重启手机后重试。",
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp),
                    )
                }
            }
        }
    }

    if (clearAsk) {
        EcoConfirm(
            title = "清空捕获日志",
            body = "仅清除调试日志，不影响已确认的交易与待确认队列。",
            confirmText = "清空",
            danger = true,
            onConfirm = {
                vm.clearCaptureLog()
                clearAsk = false
            },
            onDismiss = { clearAsk = false },
        )
    }
}

/** 一行捕获记录：通道徽标 / 应用 / 时间 → 标题 → 正文（两行截断）→ 规则 + 状态；点开展开原文 */
@Composable
private fun LogRowCard(
    row: CapturedNotificationEntity,
    expanded: Boolean,
    shape: Shape,
    isLast: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    val c = MaterialTheme.eco
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .ecoPressable(scaleTo = 0.995f, onClick = onToggle)
                .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelBadge(row.source)
                Spacer(Modifier.width(7.dp))
                Text(
                    appLabel(row.packageName),
                    style = EcoType.RowTitle.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    logTime(row.receivedAt),
                    style = EcoType.TraceTime.copy(fontSize = 7.5.sp),
                    color = c.quiet,
                    maxLines = 1,
                )
            }
            if (row.title.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    row.title,
                    style = EcoType.RowTitle.copy(fontSize = 11.sp),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val body = row.text.ifBlank { row.bigText }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    body,
                    style = EcoType.RowSub.copy(fontSize = 8.5.sp, lineHeight = 12.5.sp),
                    color = c.muted,
                    maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.matchedRule.ifBlank { "未命中规则" },
                    style = EcoType.PageNote.copy(fontSize = 7.5.sp),
                    color = c.quiet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                EcoPill(
                    text = captureStatusLabel(row),
                    tone = captureStatusTone(row),
                )
            }
        }

        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface2)
                        .border(1.dp, c.rule, RoundedCornerShape(8.dp))
                        .padding(9.dp),
                ) {
                    Text(
                        snapshotText(row),
                        style = EcoType.PageNote.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            lineHeight = 13.sp,
                        ),
                        color = c.ink2,
                        modifier = Modifier
                            .heightIn(max = 150.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EcoTextAction(text = "复制原文", onClick = onCopy)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "接收 ${formatTimestamp(row.receivedAt)}",
                        style = EcoType.PageNote.copy(fontSize = 8.sp),
                        color = c.quiet,
                    )
                }
            }
        }

        if (!isLast) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(c.rule),
            )
        }
    }
}

/** 通道徽标：通知（青蓝）/ 页面（鸢尾）/ 短信（玉绿） */
@Composable
private fun ChannelBadge(channel: CaptureChannel) {
    val c = MaterialTheme.eco
    val (label, tint) = when (channel) {
        CaptureChannel.NOTIFICATION -> "通知" to c.blue
        CaptureChannel.A11Y -> "页面" to c.violet
        CaptureChannel.SMS -> "短信" to c.jade
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = EcoType.LogChannel, color = tint, maxLines = 1)
    }
}

/** 通道筛选胶囊：选中用主色实底，深色主题下文字用纸色保证对比度 */
@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier
            .height(30.dp)
            .clip(shape)
            .background(if (selected) c.blueDeep else c.surface)
            .border(1.dp, if (selected) Color.Transparent else c.rule, shape)
            .ecoPressable(scaleTo = 0.96f, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = EcoType.Chip,
            color = if (!selected) {
                c.muted
            } else if (c.isDark) {
                c.paper
            } else {
                Color.White
            },
            maxLines = 1,
        )
    }
}

/** 状态点：6dp 实心 + 2.6s 外环扩散（对应原型 livePulse） */
@Composable
private fun PulseDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "capture-pulse")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Restart),
        label = "capture-pulse-scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.34f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Restart),
        label = "capture-pulse-alpha",
    )
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(6.dp)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                    alpha = ringAlpha
                }
                .clip(CircleShape)
                .background(color),
        )
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
    }
}

private data class CaptureFilterOption(val label: String, val channel: CaptureChannel?)

private val CAPTURE_FILTERS = listOf(
    CaptureFilterOption("全部", null),
    CaptureFilterOption("通知", CaptureChannel.NOTIFICATION),
    CaptureFilterOption("页面", CaptureChannel.A11Y),
    CaptureFilterOption("短信", CaptureChannel.SMS),
)

/** 首尾行取圆角，中间行保持直角 → 视觉上仍是一张连续的卡 */
private fun paneShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(19.dp)
    index == 0 -> RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp)
    index == count - 1 -> RoundedCornerShape(bottomStart = 19.dp, bottomEnd = 19.dp)
    else -> androidx.compose.ui.graphics.RectangleShape
}

private fun captureStatusText(granted: Boolean, connected: Boolean, a11yConnected: Boolean): String {
    val notification = when {
        granted && connected -> "已连接"
        granted -> "已授权（连接状态未知）"
        else -> "未授权"
    }
    return "通知监听：$notification · 无障碍：${if (a11yConnected) "已连接" else "未连接"}"
}

private fun captureStatusLabel(row: CapturedNotificationEntity): String = when {
    row.queued -> "待确认"
    row.matchedRule.isNotBlank() -> "已识别"
    else -> "未匹配"
}

private fun captureStatusTone(row: CapturedNotificationEntity): EcoPillTone = when {
    row.queued -> EcoPillTone.Warn
    row.matchedRule.isNotBlank() -> EcoPillTone.Good
    else -> EcoPillTone.Neutral
}

private fun channelLabel(source: CaptureChannel): String = when (source) {
    CaptureChannel.A11Y -> "页面"
    CaptureChannel.SMS -> "短信"
    CaptureChannel.NOTIFICATION -> "通知"
}

private fun appLabel(packageName: String): String = when (packageName) {
    NotificationRules.WECHAT -> "微信"
    NotificationRules.ALIPAY -> "支付宝"
    NotificationRules.UNIONPAY -> "云闪付"
    else -> NotificationRules.checkoutPackages[packageName] ?: packageName
}

private val logClockFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val logDateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 原型口径：今天 / 昨天显示时刻，更早显示 月-日 时:分 */
private fun logTime(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now(zone)
    return when (at.toLocalDate()) {
        today -> "今天 " + logClockFormatter.format(at)
        today.minusDays(1) -> "昨天 " + logClockFormatter.format(at)
        else -> logDateFormatter.format(at)
    }
}

/** 复制格式（与旧版一致，便于贴进开发会话） */
private fun copyPayload(row: CapturedNotificationEntity): String =
    "channel=${channelLabel(row.source)}\n" +
        "pkg=${row.packageName}\ntitle=${row.title}\ntext=${row.text}\nbigText=${row.bigText}"

/** 展开后展示的完整快照（含元信息，排版给眼睛看） */
private fun snapshotText(row: CapturedNotificationEntity): String = buildString {
    appendLine("通道：${channelLabel(row.source)} · ${appLabel(row.packageName)}（${row.packageName}）")
    appendLine("时间：${formatTimestamp(row.receivedAt)}")
    appendLine("规则：${row.matchedRule.ifBlank { "未匹配" }}")
    appendLine("状态：${if (row.queued) "已加入待确认" else "未入队"}")
    if (row.title.isNotBlank()) {
        appendLine()
        appendLine(row.title)
    }
    if (row.text.isNotBlank()) {
        appendLine()
        append(row.text)
    }
    if (row.bigText.isNotBlank() && row.bigText != row.text) {
        appendLine()
        append(row.bigText)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("我ECO 捕获通知", text))
}
