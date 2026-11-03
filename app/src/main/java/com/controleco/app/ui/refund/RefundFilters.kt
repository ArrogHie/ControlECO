package com.controleco.app.ui.refund

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.transactions.AmountMode
import com.controleco.app.ui.transactions.amountMatches
import com.controleco.app.ui.transactions.amountQueryOf
import com.controleco.app.ui.transactions.merchantMatches
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 退款候选跨会计周期；日期两端包含全天，留空表示不限。 */
internal fun filterRefundCandidates(
    rows: List<TransactionRow>,
    capturedAmount: Long? = null,
    merchant: String = "",
    minAmount: String = "",
    maxAmount: String = "",
    startDate: LocalDate? = null,
    endDate: LocalDate? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TransactionRow> {
    val exact = capturedAmount?.takeIf { it > 0 }
    val amount = amountQueryOf(AmountMode.RANGE, "", minAmount, maxAmount)
    val reversed = startDate != null && endDate != null && startDate > endDate
    val first = if (reversed) endDate else startDate
    val last = if (reversed) startDate else endDate
    return rows.filter { row ->
        val date = Instant.ofEpochMilli(row.timestamp).atZone(zone).toLocalDate()
        row.type == TxType.EXPENSE && row.refundMark != RefundMark.REFUNDED_EXPENSE &&
            (if (exact != null) row.amountCents == exact else
                merchantMatches(row.counterparty, merchant) && amountMatches(row.amountCents, amount)) &&
            (first == null || date >= first) && (last == null || date <= last)
    }
}
