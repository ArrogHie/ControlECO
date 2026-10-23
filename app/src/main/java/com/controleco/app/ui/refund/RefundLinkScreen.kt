package com.controleco.app.ui.refund

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoMoneyField
import com.controleco.app.ui.eco.EcoPageLead
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSheet
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
 * 登记退款页：微信"退款到账通知"不带金额，无法自动入账——
 * 用户在此选择被退款的支出，生成退款收入（金额可改，支持部分退款）并把那笔支出标「已退款」。
 *
 * 两笔记录之间**不建立关联**：支出只是被标了「已退款」，退款收入只是一笔收入。
 */
@Composable
fun RefundLinkScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<TransactionRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var linking by remember { mutableStateOf<TransactionRow?>(null) }
    val c = MaterialTheme.eco

    LaunchedEffect(Unit) {
        rows = vm.refundCandidates()
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "REFUND LINK",
            title = "选择被退款的账单",
            onBack = onBack,
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 32.dp),
        ) {
            Spacer(Modifier.size(4.dp))
            EcoPageLead(
                "微信「退款到账」通知不带金额，无法自动入账。选择对应的支出记录后，会生成一条退款收入" +
                    "（可改金额以支持部分退款），并把原支出标记为「已退款」。",
            )
            Spacer(Modifier.size(10.dp))

            when {
                loading -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("加载中…", style = EcoType.PageLead, color = c.quiet)
                    }
                }
                rows.isEmpty() -> {
                    EcoPane {
                        EcoEmpty(
                            title = "暂无可选的支出",
                            body = "近 60 天没有可选的支出记录。退款收入默认等于原支出金额。",
                            icon = EcoIcons.Receipt,
                        )
                    }
                }
                else -> {
                    EcoPane {
                        Column {
                            rows.forEachIndexed { index, row ->
                                RefundRow(row = row, onClick = { linking = row })
                                if (index != rows.lastIndex) EcoRowDivider(inset = 14.dp)
                            }
                        }
                    }
                }
            }

            EcoPageNote("仅显示近 60 天、未退款的支出；退款收入默认等于原支出金额。")
        }
    }

    linking?.let { row ->
        LinkRefundSheet(
            row = row,
            onLink = { amountCents, onResult ->
                vm.registerRefund(row.id, amountCents) { error ->
                    onResult(error)
                    if (error == null) {
                        // 原支出已标记退款，从候选中移除
                        rows = rows.filter { it.id != row.id }
                        EcoToast.show("已生成退款收入")
                    }
                }
            },
            onDismiss = { linking = null },
        )
    }
}

@Composable
private fun RefundRow(
    row: TransactionRow,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    val merchant = row.counterparty.ifBlank { row.note }.ifBlank { "未命名支出" }
    val detail = listOf(row.categoryName, row.accountName, refundDate(row.timestamp))
        .filter { it.isNotBlank() }
        .joinToString(" ")

    Row(
        Modifier
            .fillMaxWidth()
            .ecoPressable(scaleTo = 0.99f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EcoCategoryTile(
            categoryName = row.categoryName,
            icon = row.categoryIcon,
            size = 38.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "-" + Money.formatYuan(row.amountCents),
                    style = EcoType.BillAmount,
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "$merchant",
                    style = EcoType.RowTitle,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.size(2.dp))
                Text(
                    detail,
                    style = EcoType.RowSub,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(EcoIcons.Chev, contentDescription = null, tint = c.quiet, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun LinkRefundSheet(
    row: TransactionRow,
    onLink: (amountCents: Long, onResult: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by remember(row.id) { mutableStateOf(Money.formatCents(row.amountCents)) }
    var error by remember(row.id) { mutableStateOf<String?>(null) }
    var submitting by remember(row.id) { mutableStateOf(false) }
    val c = MaterialTheme.eco
    val merchant = row.counterparty.ifBlank { row.note }.ifBlank { "未命名支出" }

    EcoSheet(
        onDismiss = { if (!submitting) onDismiss() },
        title = "登记退款",
        subtitle = "原支出 ${Money.formatYuan(row.amountCents)}，$merchant，${refundDate(row.timestamp)}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EcoMoneyField(
                label = "退款金额（元）",
                value = amountText,
                onValueChange = {
                    amountText = it
                    if (error != null) error = null
                },
                placeholder = "0.00",
                supporting = "默认等于原支出；可改成更小的金额以支持部分退款。",
                isError = error != null,
            )

            error?.let {
                Text(it, style = EcoType.PageNote, color = c.clay)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(
                        text = "取消",
                        onClick = onDismiss,
                        enabled = !submitting,
                        height = 42.dp,
                    )
                }
                Box(Modifier.weight(1f)) {
                    EcoPrimaryButton(
                        text = if (submitting) "处理中…" else "生成退款收入",
                        onClick = link@ {
                            val cents = Money.parseCents(amountText)
                            if (cents == null || cents <= 0) {
                                error = "请输入有效金额"
                                return@link
                            }
                            if (cents > row.amountCents) {
                                error = "退款金额不能超过原支出"
                                return@link
                            }
                            submitting = true
                            onLink(cents) { result ->
                                submitting = false
                                if (result == null) {
                                    onDismiss()
                                } else {
                                    error = result
                                }
                            }
                        },
                        enabled = !submitting,
                        height = 42.dp,
                    )
                }
            }
        }
    }
}

private val refundDateFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())

private fun refundDate(millis: Long): String = refundDateFormatter.format(Instant.ofEpochMilli(millis))
