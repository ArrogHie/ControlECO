package com.controleco.app.ui.pending

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.theme.accBook
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.DropdownSelector
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val pending by vm.pendingList.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    var confirming by remember { mutableStateOf<PendingTransactionEntity?>(null) }
    var amountEditing by remember { mutableStateOf<PendingTransactionEntity?>(null) }
    var confirmAllAsk by remember { mutableStateOf(false) }
    val amountMissing = pending.count { it.amountCents <= 0 }
    val confirmableCount = pending.count { it.status != PendingStatus.ASK && it.amountCents > 0 }
    val askCount = pending.count { it.status == PendingStatus.ASK }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待确认账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (confirmableCount > 0) {
                        TextButton(onClick = { confirmAllAsk = true }) { Text("全部确认") }
                    }
                },
            )
        },
    ) { padding ->
        if (pending.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无待确认账单\n开启通知监听后，收款/动账通知会出现在这里",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                if (amountMissing > 0) {
                    item {
                        Text(
                            "有 $amountMissing 条金额待补录：点「补金额」输入，" +
                                "或从通知栏通知直接回复（「全部确认」会跳过它们）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.accBook.warning,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (askCount > 0) {
                    item {
                        Text(
                            "有 $askCount 条微信红包待确认：请逐条确认是否记账，不参与「全部确认」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.accBook.warning,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(pending, key = { it.id }) { p ->
                    PendingRow(
                        item = p,
                        onConfirm = { confirming = p },
                        onFillAmount = { amountEditing = p },
                        onDiscard = { vm.discardPending(p.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirming?.let { p ->
        ConfirmPendingDialog(
            item = p,
            accounts = accounts,
            categories = categories,
            defaultCategoryId = vm.guessCategoryId(p.type, p.counterparty.ifBlank { p.note }),
            defaultAccountId = vm.guessAccountId(p.sourcePackage, p.ruleName, p.paymentInstrument),
            onConfirm = { categoryId, accountId, counterparty ->
                vm.confirmPending(p, categoryId, accountId, counterparty)
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }

    amountEditing?.let { p ->
        PendingAmountDialog(
            item = p,
            onSave = { cents ->
                vm.setPendingAmount(p.id, cents) { message ->
                    if (message != null) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
                amountEditing = null
            },
            onDismiss = { amountEditing = null },
        )
    }

    if (confirmAllAsk) {
        AlertDialog(
            onDismissRequest = { confirmAllAsk = false },
            title = { Text("全部确认") },
            text = {
                val confirmable = pending.count { it.status != PendingStatus.ASK && it.amountCents > 0 }
                Text(
                    "将把 $confirmable 条按默认分类（其他）与推测账户入账，之后可在流水中修改。" +
                        if (amountMissing > 0) "另有 $amountMissing 条金额待补录，不会被确认。" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirmAllPending()
                    confirmAllAsk = false
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirmAllAsk = false }) { Text("取消") } },
        )
    }
}

/**
 * 金额待补录弹窗：0 元占位条（方向可辨但金额缺失）补金额后重算去重键，
 * 与通知栏「输入金额」是同一套逻辑（CaptureCenter.applyPendingAmount）。
 */
@Composable
private fun PendingAmountDialog(
    item: PendingTransactionEntity,
    onSave: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(item.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val kind = if (item.type == TxType.EXPENSE) "支出" else "收入"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补录$kind 金额") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    sourceLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.note.isNotBlank()) {
                    Text(
                        item.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("金额（元）") },
                    prefix = { Text(Money.symbol) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    "两条路都行：下拉通知栏点「输入金额」直接回复，或在这里补。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseCents(text)
                if (cents == null || cents <= 0) {
                    error = "请输入有效金额"
                    return@TextButton
                }
                onSave(cents)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 来源行：来源标签与规则名可能重复（"微信" + "微信·支出/消费"、银行推送的
 * "工商银行通知" + "工商银行通知·支出"）——规则名已含前缀时只显示规则名。
 */
private fun sourceLine(item: PendingTransactionEntity): String {
    val label = NotificationRules.sourceLabelOrRule(item.sourcePackage, item.ruleName)
    return if (item.ruleName.startsWith(label)) item.ruleName else "$label · ${item.ruleName}"
}

@Composable
private fun PendingRow(
    item: PendingTransactionEntity,
    onConfirm: () -> Unit,
    onFillAmount: () -> Unit,
    onDiscard: () -> Unit,
) {
    val amountMissing = item.amountCents <= 0
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = if (amountMissing) onFillAmount else onConfirm)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val amountText = if (amountMissing) {
                "金额待补"
            } else {
                (if (item.type == TxType.EXPENSE) "-" else "+") + Money.formatYuan(item.amountCents)
            }
            Text(
                amountText,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    amountMissing -> MaterialTheme.accBook.warning
                    item.type == TxType.INCOME -> MaterialTheme.accBook.income
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                sourceLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.counterparty.isNotBlank()) {
                Text(
                    "收款方 ${item.counterparty}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.note.isNotBlank()) {
                Text(
                    item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                formatTimestamp(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = if (amountMissing) onFillAmount else onConfirm) {
            Text(if (amountMissing) "补金额" else "确认")
        }
        TextButton(onClick = onDiscard) { Text("丢弃") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmPendingDialog(
    item: PendingTransactionEntity,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    defaultCategoryId: Long?,
    defaultAccountId: Long?,
    onConfirm: (Long, Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var categoryId by remember(item.id) { mutableStateOf(defaultCategoryId) }
    var accountId by remember(item.id) { mutableStateOf(defaultAccountId) }
    var counterparty by remember(item.id) { mutableStateOf(item.counterparty) }
    var error by remember { mutableStateOf<String?>(null) }
    val catsOfType = categories.filter { it.type == item.type }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((if (item.type == TxType.EXPENSE) "支出 " else "收入 ") + Money.formatYuan(item.amountCents)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${sourceLine(item)} · ${formatTimestamp(item.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.note.isNotBlank()) {
                    Text(
                        item.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("分类", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = counterparty,
                    onValueChange = { counterparty = it },
                    label = { Text("收款方/商户") },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    catsOfType.forEach { c ->
                        FilterChip(
                            selected = categoryId == c.id,
                            onClick = { categoryId = c.id },
                            label = { Text(c.name) },
                        )
                    }
                }
                DropdownSelector(
                    label = "账户",
                    options = accounts.map { it.id to it.name },
                    selectedId = accountId,
                    onSelect = { accountId = it },
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (categoryId == null) {
                    error = "请选择分类"
                    return@TextButton
                }
                if (accountId == null) {
                    error = "请选择账户"
                    return@TextButton
                }
                onConfirm(categoryId!!, accountId!!, counterparty.trim())
            }) { Text("入账") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
