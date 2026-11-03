package com.controleco.app.ui.refund

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RefundFiltersTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun row(id: Long, amount: Long = 1000, merchant: String = "商户", date: String = "2026-11-03", hour: Int = 12) =
        TransactionRow(
            id = id, type = TxType.EXPENSE, amountCents = amount,
            timestamp = LocalDate.parse(date).atTime(hour, 59, 59).atZone(zone).toInstant().toEpochMilli(),
            note = "", counterparty = merchant, source = TxSource.MANUAL,
            categoryId = 1, categoryName = "其他", accountId = 1, accountName = "现金", refundMark = RefundMark.NONE,
        )

    @Test fun `known amount only allows equal unrefunded expenses`() {
        val rows = listOf(row(1), row(2, amount = 1001), row(3).copy(type = TxType.INCOME),
            row(4).copy(refundMark = RefundMark.REFUNDED_EXPENSE))
        assertEquals(listOf(1L), filterRefundCandidates(rows, capturedAmount = 1000, zone = zone).map { it.id })
    }

    @Test fun `unknown amount includes old expenses with no 50 row cutoff`() {
        val rows = (1L..75L).map { row(it, date = "2020-01-01") }
        assertEquals(rows, filterRefundCandidates(rows, capturedAmount = 0, zone = zone))
    }

    @Test fun `merchant amount and date combine with inclusive boundaries`() {
        val rows = listOf(
            row(1, amount = 1000, merchant = "Luckin Coffee", hour = 0),
            row(2, amount = 2000, merchant = "Luckin Coffee", hour = 23),
            row(3, amount = 2001, merchant = "Luckin Coffee"),
            row(4, merchant = "其他"), row(5, merchant = "Luckin Coffee", date = "2026-11-04"),
        )
        val date = LocalDate.parse("2026-11-03")
        assertEquals(listOf(1L, 2L), filterRefundCandidates(rows, merchant = " LUCKINcoffee ",
            minAmount = "10", maxAmount = "20", startDate = date, endDate = date, zone = zone).map { it.id })
    }

    @Test fun `reversed bounds and open date end are accepted`() {
        val rows = listOf(row(1, date = "2026-11-01"), row(2), row(3, date = "2026-11-05"))
        val start = LocalDate.parse("2026-11-03")
        val end = LocalDate.parse("2026-11-01")
        assertEquals(listOf(1L, 2L), filterRefundCandidates(rows, minAmount = "20", maxAmount = "10",
            startDate = start, endDate = end, zone = zone).map { it.id })
        assertEquals(listOf(2L, 3L), filterRefundCandidates(rows, startDate = start, zone = zone).map { it.id })
    }

    @Test fun `known amount cannot be widened by other filters but still observes dates`() {
        val rows = listOf(row(1), row(2, amount = 2000), row(3, date = "2026-11-04"))
        assertEquals(listOf(1L), filterRefundCandidates(rows, capturedAmount = 1000, merchant = "does not match",
            minAmount = "20", endDate = LocalDate.parse("2026-11-03"), zone = zone).map { it.id })
    }
}
