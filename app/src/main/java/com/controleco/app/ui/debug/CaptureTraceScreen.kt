package com.controleco.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.capture.NotificationRules
import com.controleco.app.capture.TraceState
import com.controleco.app.capture.traceStateOf
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CaptureTraceEntity
import com.controleco.app.data.db.CaptureTraceRow
import com.controleco.app.data.db.TraceOutcome
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoPageLead
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
import com.controleco.app.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记账溯源（设计稿 5.17）：每次与入账有关的捕获，原始来源（通知原文 / 页面快照）与
 * 处置结果（自动入账 / 待确认 / 合并回填 / 重复 / 退款提醒）一览。
 *
 * 行结构：固定宽时间列 → 规则/通道 → 处置胶囊（+ 必要的状态胶囊）→ 展开箭头；
 * 展开后是原始快照（等宽、可滚动）+ 复制原文 + 查看对应账单（回调 onOpenTransaction）。
 */
@Composable
fun CaptureTraceScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
) {
    val rows by vm.traceRows.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabled by vm.traceEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var clearAsk by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val c = MaterialTheme.eco

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        EcoTopBar(
            micro = "TRACE",
            title = "记账溯源",
            onBack = onBack,
            action = {
                if (rows.isNotEmpty()) {
                    EcoTextAction("清空", onClick = { clearAsk = true })
                }
            },
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        ) {
            item(key = "lead") {
                Column {
                    EcoPageLead(
                        if (enabled) {
                            "记录中：每次入账相关捕获都留原始来源（最近 1000 条，可在开发者功能里关闭）。"
                        } else {
                            "未开启：仅显示历史记录。到 开发者功能 →「记录记账来源」开启后才有新记录。"
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共 ${rows.size} 条（最新在前）",
                            style = EcoType.PageNote,
                            color = c.quiet,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text("点条目展开原文", style = EcoType.PageNote, color = c.quiet, maxLines = 1)
                    }
                }
            }

            if (rows.isEmpty()) {
                item(key = "empty") {
                    EcoPane(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp)) {
                        EcoEmpty(
                            title = if (enabled) "暂无溯源记录" else "暂无记录",
                            body = if (enabled) {
                                "捕获到动账并走完入账流程后会出现"
                            } else {
                                "开启「记录记账来源」后再走一笔支付"
                            },
                            icon = EcoIcons.Link,
                        )
                    }
                }
            } else {
                itemsIndexed(rows, key = { _, row -> row.trace.id }) { index, row ->
                    TraceRowCard(
                        row = row,
                        expanded = expandedId == row.trace.id,
                        shape = tracePaneShape(index, rows.size),
                        isLast = index == rows.lastIndex,
                        onToggle = {
                            expandedId = if (expandedId == row.trace.id) null else row.trace.id
                        },
                        onCopy = {
                            copyTrace(context, row)
                            EcoToast.show("已复制溯源原文")
                        },
                        onOpenTransaction = onOpenTransaction,
                    )
                }
            }

            item(key = "note") {
                EcoPageNote(
                    "溯源只在本机留存，关闭开关后零写入。开启期间最多保留 1000 条。" +
                        "「已丢弃」= 该笔没有入账，或对应账单已被删除。",
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }

    if (clearAsk) {
        EcoConfirm(
            title = "清空溯源记录",
            body = "只清除溯源证据，不影响任何账单与待确认条目。",
            confirmText = "清空",
            danger = true,
            onConfirm = {
                vm.clearTraces()
                clearAsk = false
            },
            onDismiss = { clearAsk = false },
        )
    }
}

/** 一行溯源：时间（78 宽）→ 规则/通道 → 处置/状态胶囊 → 展开箭头；展开后是原始快照与动作 */
@Composable
private fun TraceRowCard(
    row: CaptureTraceRow,
    expanded: Boolean,
    shape: Shape,
    isLast: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
) {
    val c = MaterialTheme.eco
    val t = row.trace
    val linkedId = row.linkedTxId
    val state = traceStateOf(t.outcome, linkedId, row.pendingCount)
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "trace-chevron",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .ecoPressable(scaleTo = 0.995f, onClick = onToggle)
                .padding(start = 14.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                traceTime(t.capturedAt),
                style = EcoType.TraceTime,
                color = c.quiet,
                maxLines = 1,
                modifier = Modifier.width(78.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${traceAppLabel(t.packageName)} · ${t.title.ifBlank { outcomeLabel(t.outcome) }}",
                    style = EcoType.RowTitle.copy(fontSize = 10.5.sp),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${t.matchedRule.ifBlank { "未命中规则" }} · ${traceChannelLabel(t.channel)}",
                    style = EcoType.PageNote.copy(fontSize = 8.sp),
                    color = c.quiet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                EcoPill(text = outcomeLabel(t.outcome), tone = outcomeTone(t.outcome))
                extraState(state, t.outcome)?.let { extra ->
                    EcoPill(text = stateLabel(extra), tone = stateTone(extra))
                }
            }
            Spacer(Modifier.width(5.dp))
            Icon(
                EcoIcons.Chev,
                contentDescription = if (expanded) "收起" else "展开",
                tint = c.quiet,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = chevron },
            )
        }

        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                val details = buildList {
                    if (t.counterparty.isNotBlank()) add("商户 ${t.counterparty}")
                    if (t.note.isNotBlank()) add("备注 ${t.note}")
                    add("去重键 ${t.dedupKey}")
                }
                Text(
                    details.joinToString(" · "),
                    style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                    color = c.muted,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        linkedId != null -> "对应账单 #$linkedId（点下方「查看对应账单」）"
                        row.pendingCount > 0 -> "仍在待确认队列（确认或丢弃后此状态自动更新）"
                        t.outcome == TraceOutcome.REFUND_NOTICE -> "仅提醒，不产生账目（关联退款后生成独立收入记录）"
                        else -> "无对应账单（未入账或已删除）"
                    },
                    style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                    color = c.quiet,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "原始来源（${rawSourceLabel(t.channel)}）",
                        style = EcoType.FieldLabel,
                        color = c.muted,
                        modifier = Modifier.weight(1f),
                    )
                    t.type?.let { type ->
                        Text(
                            (if (type == TxType.EXPENSE) "支出 " else "收入 ") + Money.formatYuan(t.amountCents),
                            style = EcoType.BillAmount.copy(fontSize = 12.sp),
                            color = if (type == TxType.EXPENSE) c.ink else c.jade,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface2)
                        .border(1.dp, c.rule, RoundedCornerShape(8.dp))
                        .padding(9.dp),
                ) {
                    Text(
                        traceSnapshot(t, state),
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
                    if (linkedId != null) {
                        EcoTextAction(
                            text = "查看对应账单",
                            onClick = { onOpenTransaction(linkedId) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    EcoPill(text = stateLabel(state), tone = stateTone(state))
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

/** 首尾行取圆角，中间行保持直角 → 视觉上仍是一张连续的卡 */
private fun tracePaneShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(19.dp)
    index == 0 -> RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp)
    index == count - 1 -> RoundedCornerShape(bottomStart = 19.dp, bottomEnd = 19.dp)
    else -> androidx.compose.ui.graphics.RectangleShape
}

private val traceTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun traceTime(millis: Long): String =
    traceTimeFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun traceChannelLabel(channel: CaptureChannel): String = when (channel) {
    CaptureChannel.A11Y -> "页面"
    CaptureChannel.SMS -> "短信"
    CaptureChannel.NOTIFICATION -> "通知"
}

private fun rawSourceLabel(channel: CaptureChannel): String = when (channel) {
    CaptureChannel.A11Y -> "页面快照"
    CaptureChannel.SMS -> "短信原文"
    CaptureChannel.NOTIFICATION -> "通知原文"
}

private fun traceAppLabel(packageName: String): String = when (packageName) {
    NotificationRules.WECHAT -> "微信"
    NotificationRules.ALIPAY -> "支付宝"
    NotificationRules.UNIONPAY -> "云闪付"
    else -> NotificationRules.checkoutPackages[packageName] ?: packageName
}

private fun outcomeLabel(outcome: TraceOutcome): String = when (outcome) {
    TraceOutcome.AUTO_ENTRY -> "自动入账"
    TraceOutcome.PENDING -> "待确认入账"
    TraceOutcome.MERGED_PAGE -> "页面合并回填"
    TraceOutcome.MERGED_CROSS_APP -> "跨应用同一笔"
    TraceOutcome.MERGED_CROSS -> "跨通道归并"
    TraceOutcome.SKIPPED_DUPLICATE -> "重复捕获"
    TraceOutcome.REFUND_NOTICE -> "退款提醒"
}

private fun outcomeTone(outcome: TraceOutcome): EcoPillTone = when (outcome) {
    TraceOutcome.AUTO_ENTRY -> EcoPillTone.Good
    TraceOutcome.PENDING -> EcoPillTone.Warn
    TraceOutcome.MERGED_PAGE, TraceOutcome.MERGED_CROSS_APP, TraceOutcome.MERGED_CROSS ->
        EcoPillTone.Blue
    TraceOutcome.SKIPPED_DUPLICATE -> EcoPillTone.Neutral
    TraceOutcome.REFUND_NOTICE -> EcoPillTone.Blue
}

private fun stateLabel(state: TraceState): String = when (state) {
    TraceState.BOOKED -> "已入账"
    TraceState.PENDING -> "待确认"
    TraceState.DISCARDED -> "已丢弃"
    TraceState.NOTICE -> "仅提醒"
}

private fun stateTone(state: TraceState): EcoPillTone = when (state) {
    TraceState.BOOKED -> EcoPillTone.Good
    TraceState.PENDING -> EcoPillTone.Warn
    TraceState.DISCARDED, TraceState.NOTICE -> EcoPillTone.Neutral
}

/** 处置结果没覆盖到、但用户需要一眼看到的状态：已丢弃 / 仍在待确认 / 仅提醒 */
private fun extraState(state: TraceState, outcome: TraceOutcome): TraceState? = when {
    state == TraceState.DISCARDED -> state
    state == TraceState.PENDING && outcome != TraceOutcome.PENDING -> state
    state == TraceState.NOTICE && outcome != TraceOutcome.REFUND_NOTICE -> state
    else -> null
}

/** 展开后的完整快照（原始文本 + 解析元信息） */
private fun traceSnapshot(t: CaptureTraceEntity, state: TraceState): String = buildString {
    appendLine("通道：${traceChannelLabel(t.channel)} · ${traceAppLabel(t.packageName)}（${t.packageName}）")
    appendLine("时间：${formatTimestamp(t.capturedAt)}")
    appendLine("规则：${t.matchedRule.ifBlank { "未匹配" }}")
    appendLine("处置：${outcomeLabel(t.outcome)} / ${stateLabel(state)}")
    if (t.counterparty.isNotBlank()) appendLine("商户：${t.counterparty}")
    if (t.note.isNotBlank()) appendLine("备注：${t.note}")
    appendLine("去重键：${t.dedupKey}")
    if (t.title.isNotBlank()) {
        appendLine()
        appendLine(t.title)
    }
    if (t.text.isNotBlank()) {
        appendLine()
        append(t.text)
    }
    if (t.bigText.isNotBlank() && t.bigText != t.text) {
        appendLine()
        append(t.bigText)
    }
}

/** 复制为纯文本（供贴进开发会话排查）：字段与捕获日志的复制格式一致，另附处置与账目状态 */
private fun copyTrace(context: Context, row: CaptureTraceRow) {
    val t = row.trace
    val state = traceStateOf(t.outcome, row.linkedTxId, row.pendingCount)
    val text = buildString {
        appendLine("channel=${traceChannelLabel(t.channel)}")
        appendLine("pkg=${t.packageName}")
        appendLine("capturedAt=${formatTimestamp(t.capturedAt)}")
        appendLine("rule=${t.matchedRule}")
        appendLine("outcome=${outcomeLabel(t.outcome)} / ${stateLabel(state)}")
        t.type?.let { appendLine("${if (it == TxType.EXPENSE) "expense" else "income"}=${Money.formatYuan(t.amountCents)}") }
        if (t.counterparty.isNotBlank()) appendLine("counterparty=${t.counterparty}")
        if (t.note.isNotBlank()) appendLine("note=${t.note}")
        appendLine("dedupKey=${t.dedupKey}")
        row.linkedTxId?.let { appendLine("transactionId=$it") }
        if (t.title.isNotBlank()) appendLine("title=${t.title}")
        if (t.text.isNotBlank()) appendLine("text=${t.text}")
        if (t.bigText.isNotBlank()) appendLine("bigText=${t.bigText}")
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("我ECO 溯源记录", text))
}
