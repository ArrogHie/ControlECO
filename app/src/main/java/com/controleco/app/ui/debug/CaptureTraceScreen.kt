package com.controleco.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.capture.TraceState
import com.controleco.app.capture.traceStateOf
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CaptureTraceRow
import com.controleco.app.data.db.TraceOutcome
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.theme.accBook
import com.controleco.app.util.Money

/**
 * 记账溯源（开发者功能）：每次与入账有关的捕获，原始来源（通知原文 / 页面快照）与
 * 处置结果（自动入账/待确认/合并回填/重复/退款提醒）一览。
 * 需在 开发者功能 里开启「记录记账来源」才有新记录；点击条目展开全文（可复制、可跳对应账单）。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记账溯源") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = { clearAsk = true }) { Text("清空") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                if (enabled) {
                    "记录中：每次入账相关捕获都留原始来源（最近 1000 条，可在开发者功能里关闭）"
                } else {
                    "未开启：仅显示历史记录。到 开发者功能 →「记录记账来源」开启后才有新记录"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.accBook.primaryStrong else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "共 ${rows.size} 条（最新在前）；点条目展开原始来源与解析结果，可复制、可跳对应账单。" +
                    "「已丢弃」= 该笔没有入账，或对应账单已被删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))

            if (rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (enabled) "暂无溯源记录\n捕获到动账并走完入账流程后会出现" else "暂无记录\n开启「记录记账来源」后再走一笔支付",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(rows, key = { it.trace.id }) { row ->
                        TraceRowItem(
                            row = row,
                            expanded = expandedId == row.trace.id,
                            onToggle = { expandedId = if (expandedId == row.trace.id) null else row.trace.id },
                            onCopy = { copyTrace(context, row) },
                            onOpenTransaction = onOpenTransaction,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (clearAsk) {
        AlertDialog(
            onDismissRequest = { clearAsk = false },
            title = { Text("清空溯源记录") },
            text = { Text("只清除溯源证据，不影响任何账单与待确认条目。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearTraces()
                    clearAsk = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clearAsk = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TraceRowItem(
    row: CaptureTraceRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
) {
    val t = row.trace
    val state = traceStateOf(t.outcome, row.linkedTxId, row.pendingCount)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${channelLabel(t.channel)} · ${t.packageName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTimestamp(t.capturedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val amountText = t.type?.let { type ->
                (if (type == TxType.EXPENSE) "支出 " else "收入 ") + Money.formatYuan(t.amountCents)
            }
            if (amountText != null) {
                Text(amountText, style = MaterialTheme.typography.bodyMedium)
            }
            TraceChip(outcomeLabel(t.outcome), MaterialTheme.colorScheme.onSurfaceVariant)
            TraceChip(stateLabel(state), stateColor(state))
            Spacer(Modifier.weight(1f))
            if (t.matchedRule.isNotBlank()) {
                Text(
                    t.matchedRule,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (expanded) {
            Column(Modifier.padding(top = 8.dp)) {
                val details = buildList {
                    if (t.counterparty.isNotBlank()) add("商户 ${t.counterparty}")
                    if (t.note.isNotBlank()) add("备注 ${t.note}")
                    add("去重键 ${t.dedupKey}")
                }
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        row.linkedTxId != null -> "对应账单 #${row.linkedTxId}（点下方「查看账单」）"
                        row.pendingCount > 0 -> "仍在待确认队列（确认或丢弃后此状态自动更新）"
                        t.outcome == TraceOutcome.REFUND_NOTICE -> "仅提醒，不产生账目（关联退款后生成独立收入记录）"
                        else -> "无对应账单（未入账或已删除）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    "原始来源（${rawSourceLabel(t.channel)}）：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (t.title.isNotBlank()) {
                    Text(t.title, style = MaterialTheme.typography.bodyMedium)
                }
                if (t.text.isNotBlank()) {
                    Text(
                        t.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (t.bigText.isNotBlank() && t.bigText != t.text) {
                    Text(
                        t.bigText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = onCopy) { Text("复制") }
                    if (row.linkedTxId != null) {
                        TextButton(onClick = { onOpenTransaction(row.linkedTxId) }) { Text("查看账单") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceChip(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun channelLabel(channel: CaptureChannel): String = when (channel) {
    CaptureChannel.A11Y -> "页面"
    CaptureChannel.SMS -> "短信"
    CaptureChannel.NOTIFICATION -> "通知"
}

private fun rawSourceLabel(channel: CaptureChannel): String = when (channel) {
    CaptureChannel.A11Y -> "页面快照"
    CaptureChannel.SMS -> "短信原文"
    CaptureChannel.NOTIFICATION -> "通知原文"
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

private fun stateLabel(state: TraceState): String = when (state) {
    TraceState.BOOKED -> "已入账"
    TraceState.PENDING -> "待确认"
    TraceState.DISCARDED -> "已丢弃"
    TraceState.NOTICE -> "仅提醒"
}

@Composable
private fun stateColor(state: TraceState): Color = when (state) {
    TraceState.BOOKED -> MaterialTheme.accBook.primaryStrong
    TraceState.PENDING -> MaterialTheme.accBook.warning
    TraceState.DISCARDED -> MaterialTheme.colorScheme.onSurfaceVariant
    TraceState.NOTICE -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 复制为纯文本（供贴进开发会话排查）：字段与捕获日志的复制格式一致，另附处置与账目状态 */
private fun copyTrace(context: Context, row: CaptureTraceRow) {
    val t = row.trace
    val state = traceStateOf(t.outcome, row.linkedTxId, row.pendingCount)
    val text = buildString {
        appendLine("channel=${channelLabel(t.channel)}")
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
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
