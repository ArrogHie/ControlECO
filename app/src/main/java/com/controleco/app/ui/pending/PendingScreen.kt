package com.controleco.app.ui.pending

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategoryRules
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoConfirm
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 普通捕获、捕获退款与历史待处理退款合并展示；退款只从候选页处理。 */
@Composable
fun PendingScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onRefund: (pendingId: Long?, legacyRefundId: Long?) -> Unit,
) {
    val pending by vm.pendingList.collectAsStateWithLifecycle(initialValue = emptyList())
    val refunds by vm.pendingRefunds.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val merchantRules by vm.merchantRules.collectAsStateWithLifecycle()

    var confirming by remember { mutableStateOf<PendingTransactionEntity?>(null) }
    var amountEditing by remember { mutableStateOf<PendingTransactionEntity?>(null) }
    var confirmAllAsk by remember { mutableStateOf(false) }
    val confirmableCount = pending.count {
        it.status != PendingStatus.ASK && it.amountCents > 0 && it.refundMark != RefundMark.REFUND_INCOME
    }
    val context = LocalContext.current

    // 捕获条目与退款条目并进一个列表，按时间倒序；不分区、不加小标题
    val entries = remember(pending, refunds) {
        buildList {
            pending.forEach { add(PendingEntry.Capture(it)) }
            refunds.forEach { add(PendingEntry.Refund(it)) }
        }.sortedByDescending { it.sortAt }
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "PENDING",
            title = "待处理",
            onBack = onBack,
            action = {
                if (confirmableCount > 0) {
                    EcoTextAction(text = "全部确认", onClick = { confirmAllAsk = true })
                }
            },
        )

        if (entries.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EcoEmpty(
                    title = "暂无待处理账单",
                    icon = EcoIcons.Check,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 32.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is PendingEntry.Capture -> PendingRow(
                            item = entry.item,
                            onConfirm = {
                                if (entry.item.refundMark == RefundMark.REFUND_INCOME) onRefund(entry.item.id, null)
                                else confirming = entry.item
                            },
                            onFillAmount = { amountEditing = entry.item },
                            onDiscard = { vm.discardPending(entry.item.id) },
                        )
                        is PendingEntry.Refund -> RefundPendingRow(
                            row = entry.row,
                            onLink = { onRefund(null, entry.row.id) },
                        )
                    }
                }
            }
        }
    }

    confirming?.let { p ->
        ConfirmPendingSheet(
            item = p,
            accounts = accounts,
            categories = categories,
            defaultCategoryId = remember(p, categories, merchantRules) {
                vm.guessCategoryId(p.type, p.counterparty.ifBlank { p.note }, counterparty = p.counterparty, note = p.note)
            },
            defaultAccountId = remember(p, accounts, merchantRules) {
                vm.guessAccountId(p.sourcePackage, p.ruleName, p.paymentInstrument, p.type, p.counterparty, p.note)
            },
            onConfirm = { categoryId, accountId, counterparty ->
                vm.confirmPending(p, categoryId, accountId, counterparty)
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }

    amountEditing?.let { p ->
        PendingAmountSheet(
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
        EcoConfirm(
            title = "全部确认",
            body = "确认 $confirmableCount 笔账单？",
            confirmText = "确认",
            onConfirm = {
                vm.confirmAllPending()
                confirmAllAsk = false
            },
            onDismiss = { confirmAllAsk = false },
        )
    }
}

/** 待确认页的一行：捕获待确认，或已入账但还没指认原支出的退款 */
private sealed interface PendingEntry {
    val key: String
    val sortAt: Long

    data class Capture(val item: PendingTransactionEntity) : PendingEntry {
        override val key: String get() = "capture-${item.id}"
        override val sortAt: Long get() = item.createdAt
    }

    data class Refund(val row: TransactionRow) : PendingEntry {
        override val key: String get() = "refund-${row.id}"

        /** 按入库时间排：几个月前导入的退款也要浮到顶部，而不是按业务时间沉下去 */
        override val sortAt: Long get() = row.createdAt
    }
}

// ---------- 列表 ----------

@Composable
private fun PendingRow(
    item: PendingTransactionEntity,
    onConfirm: () -> Unit,
    onFillAmount: () -> Unit,
    onDiscard: () -> Unit,
) {
    val c = MaterialTheme.eco
    val amountMissing = item.amountCents <= 0
    val isAsk = item.status == PendingStatus.ASK
    val isRefund = item.refundMark == RefundMark.REFUND_INCOME

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = if (amountMissing && !isRefund) onFillAmount else onConfirm)
            .padding(top = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val amountText = when {
                    isRefund && amountMissing -> "退款"
                    amountMissing -> "金额待补"
                    item.type == TxType.INCOME -> "+" + Money.formatYuan(item.amountCents)
                    else -> "-" + Money.formatYuan(item.amountCents)
                }
                Text(
                    amountText,
                    style = if (amountMissing) {
                        EcoType.PendingAmount.copy(fontSize = 12.sp)
                    } else {
                        EcoType.PendingAmount
                    },
                    color = when {
                        amountMissing -> c.amber
                        item.type == TxType.INCOME -> c.jade
                        else -> c.ink
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    sourceLine(item),
                    style = EcoType.RowSub.copy(lineHeight = 12.sp),
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.counterparty.isNotBlank()) {
                    Text(
                        "收款方 ${item.counterparty}",
                        style = EcoType.RowSub.copy(lineHeight = 12.sp),
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.note.isNotBlank()) {
                    Text(
                        item.note,
                        style = EcoType.RowSub.copy(lineHeight = 12.sp),
                        color = c.quiet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.paymentInstrument.isNotBlank()) {
                    Text(
                        item.paymentInstrument,
                        style = EcoType.RowSub.copy(lineHeight = 12.sp),
                        color = c.quiet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    pendingTime(item.timestamp),
                    style = EcoType.PageNote.copy(fontSize = 8.sp, lineHeight = 10.sp),
                    color = c.quiet,
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                isRefund -> EcoPill(text = "退款", tone = EcoPillTone.Warn)
                amountMissing -> EcoPill(text = "待补", tone = EcoPillTone.Warn)
                isAsk -> EcoPill(text = "红包", tone = EcoPillTone.Bad)
                else -> EcoPill(text = "待确认", tone = EcoPillTone.Blue)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isRefund -> PendingTextAction("选择原账单", c.blue, onConfirm)
                amountMissing -> PendingTextAction("补金额", c.blue, onFillAmount)
                else -> PendingTextAction("确认", c.blue, onConfirm)
            }
            Spacer(Modifier.width(18.dp))
            PendingTextAction("丢弃", c.clay, onDiscard)
        }
    }
    EcoRowDivider(inset = 0.dp)
}

/**
 * 退款待处理行：已入账的退款收入，还没标记对应的原支出。
 * 仅用于兼容更新前已经入账的退款。
 */
@Composable
private fun RefundPendingRow(
    row: TransactionRow,
    onLink: () -> Unit,
) {
    val c = MaterialTheme.eco
    val merchant = row.counterparty.ifBlank { row.note }.ifBlank { "未命名退款" }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onLink)
            .padding(top = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "+" + Money.formatYuan(row.amountCents),
                    style = EcoType.PendingAmount,
                    color = c.jade,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "退款到账 ${refundSourceLabel(row.source)}",
                    style = EcoType.RowSub.copy(lineHeight = 12.sp),
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    merchant,
                    style = EcoType.RowSub.copy(lineHeight = 12.sp),
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pendingTime(row.timestamp),
                    style = EcoType.PageNote.copy(fontSize = 8.sp, lineHeight = 10.sp),
                    color = c.quiet,
                )
            }
            Spacer(Modifier.width(10.dp))
            EcoPill(text = "待处理", tone = EcoPillTone.Warn)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PendingTextAction("选择原账单", c.blue, onLink)
        }
    }
    EcoRowDivider(inset = 0.dp)
}

private fun refundSourceLabel(source: TxSource): String = when (source) {
    TxSource.WECHAT -> "微信支付"
    TxSource.ALIPAY -> "支付宝"
    TxSource.NOTIFICATION -> "通知捕获"
    TxSource.BANK -> "银行动账"
    TxSource.MANUAL -> "手动记录"
}

@Composable
private fun PendingTextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        style = EcoType.Btn.copy(fontSize = 10.sp),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

// ---------- 确认 sheet ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmPendingSheet(
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
    var categoryEdited by remember(item.id) { mutableStateOf(false) }
    var accountEdited by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(defaultCategoryId) { if (!categoryEdited) categoryId = defaultCategoryId }
    LaunchedEffect(defaultAccountId) { if (!accountEdited) accountId = defaultAccountId }
    var counterparty by remember(item.id) { mutableStateOf(item.counterparty) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    val catsOfType = categories.filter { it.type == item.type && !CategoryRules.isRefundIncome(it.type, it.name) }
    val c = MaterialTheme.eco
    val kind = if (item.type == TxType.EXPENSE) "支出" else "收入"

    EcoSheet(
        onDismiss = onDismiss,
        title = "确认$kind ${Money.formatYuan(item.amountCents)}",
        subtitle = "${sourceLine(item)} ${pendingTime(item.timestamp)}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (item.note.isNotBlank()) PendingQuote(item.note)

            EcoField(
                label = "收款方 / 商户",
                value = counterparty,
                onValueChange = { counterparty = it },
                placeholder = "例如：美团外卖",
            )

            Column {
                Text("分类", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(6.dp))
                if (catsOfType.isEmpty()) {
                    Text(
                        "暂无分类，请先在分类管理中添加",
                        style = EcoType.PageNote,
                        color = c.amber,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        catsOfType.forEach { cat ->
                            EcoChip(
                                text = cat.name,
                                selected = categoryId == cat.id,
                                onClick = {
                                    categoryEdited = true
                                    categoryId = cat.id
                                    if (error != null) error = null
                                },
                            )
                        }
                    }
                }
            }

            Column {
                Text("账户", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(6.dp))
                if (accounts.isEmpty()) {
                    Text("暂无账户，请先在账户管理中添加", style = EcoType.PageNote, color = c.amber)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        accounts.forEach { acc ->
                            EcoChip(
                                text = acc.name,
                                selected = accountId == acc.id,
                                onClick = {
                                    accountEdited = true
                                    accountId = acc.id
                                    if (error != null) error = null
                                },
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(it, style = EcoType.PageNote, color = c.clay)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(text = "取消", onClick = onDismiss, height = 42.dp)
                }
                Box(Modifier.weight(1f)) {
                    EcoPrimaryButton(
                        text = "入账",
                        height = 42.dp,
                        onClick = save@ {
                            val cat = categoryId
                            val acc = accountId
                            if (cat == null) {
                                error = "请选择分类"
                                return@save
                            }
                            if (acc == null) {
                                error = "请选择账户"
                                return@save
                            }
                            error = null
                            onConfirm(cat, acc, counterparty.trim())
                        },
                    )
                }
            }
        }
    }
}

// ---------- 补金额 sheet ----------

@Composable
private fun PendingAmountSheet(
    item: PendingTransactionEntity,
    onSave: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(item.id) { mutableStateOf("") }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    val c = MaterialTheme.eco
    val kind = if (item.type == TxType.EXPENSE) "支出" else "收入"

    EcoSheet(
        onDismiss = onDismiss,
        title = "补录$kind 金额",
        subtitle = "${sourceLine(item)} ${pendingTime(item.timestamp)}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (item.note.isNotBlank()) PendingQuote(item.note)

            EcoMoneyField(
                label = "金额（元）",
                value = text,
                onValueChange = {
                    text = it
                    if (error != null) error = null
                },
                placeholder = "0.00",
                isError = error != null,
            )

            Text(
                "两条路都行：下拉通知栏点「输入金额」直接回复，或在这里补。",
                style = EcoType.PageNote,
                color = c.quiet,
            )

            error?.let {
                Text(it, style = EcoType.PageNote, color = c.clay)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(text = "取消", onClick = onDismiss, height = 42.dp)
                }
                Box(Modifier.weight(1f)) {
                    EcoPrimaryButton(
                        text = "保存",
                        height = 42.dp,
                        onClick = save@ {
                            val cents = Money.parseCents(text)
                            if (cents == null || cents <= 0) {
                                error = "请输入有效金额"
                                return@save
                            }
                            onSave(cents)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingQuote(text: String) {
    val c = MaterialTheme.eco
    Text(
        "「$text」",
        style = EcoType.PageLead,
        color = c.muted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    )
}

// ---------- 工具 ----------

/**
 * 来源行：来源标签与规则名可能重复（"微信" + "微信·支出/消费"、银行推送的
 * "工商银行通知" + "工商银行通知·支出"）——规则名已含前缀时只显示规则名。
 */
private fun sourceLine(item: PendingTransactionEntity): String {
    val label = NotificationRules.sourceLabelOrRule(item.sourcePackage, item.ruleName)
    return if (item.ruleName.startsWith(label)) item.ruleName else "$label ${item.ruleName}"
}

private val pendingTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun pendingTime(millis: Long): String = pendingTimeFormatter.format(Instant.ofEpochMilli(millis))
