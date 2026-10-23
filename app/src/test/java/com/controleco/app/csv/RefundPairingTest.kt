package com.controleco.app.csv

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import com.controleco.app.data.model.BillSource
import com.controleco.app.data.model.ImportedBillRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退款配对规则单测：导入账单时把「退款收入」配回「被退款的原支出」。
 *
 * 配不上的退款会进「待确认账单」让用户选，所以这里的原则是**宁可漏配也不要错配**。
 * 时间统一用「相对基准的小时数」表示。
 */
class RefundPairingTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val base = 1_760_000_000_000L

    private fun expense(
        amountCents: Long,
        offsetMillis: Long,
        merchant: String,
        mark: RefundMark = RefundMark.NONE,
        refundedCents: Long? = null,
        externalId: String? = null,
    ) = ImportedBillRow(
        type = TxType.EXPENSE,
        amountCents = amountCents,
        timestamp = base + offsetMillis,
        counterparty = merchant,
        goods = "",
        note = "",
        payMethod = "",
        externalId = externalId,
        guessedCategoryName = "",
        refundMark = mark,
        refundedCents = refundedCents,
    )

    private fun refund(
        amountCents: Long,
        offsetMillis: Long,
        merchant: String,
        externalId: String? = null,
    ) = ImportedBillRow(
        type = TxType.INCOME,
        amountCents = amountCents,
        timestamp = base + offsetMillis,
        counterparty = merchant,
        goods = "",
        note = "",
        payMethod = "",
        externalId = externalId,
        guessedCategoryName = "",
        refundMark = RefundMark.REFUND_INCOME,
    )

    @Test
    fun `微信全额退款配到同商户时间最近的那一笔`() {
        val rows = listOf(
            expense(5_000, 10 * day, "姜胖胖烤肉", RefundMark.REFUNDED_EXPENSE),
            expense(5_000, 20 * day, "姜胖胖烤肉", RefundMark.REFUNDED_EXPENSE),
            refund(5_000, 20 * day + 2 * hour, "姜胖胖烤肉"),
        )

        val pairs = RefundPairing.pair(rows, BillSource.WECHAT)

        assertEquals(listOf(1), pairs.map { it.expenseIndex })
        assertEquals(2, pairs.single().refundIndex)
    }

    @Test
    fun `微信部分退款按状态里的退款额配对_原支出不标已退`() {
        val rows = listOf(
            expense(3_179, 5 * day, "饿了么", refundedCents = 854),
            refund(854, 5 * day + 2 * hour, "饿了么"),
        )

        val pairs = RefundPairing.pair(rows, BillSource.WECHAT)

        assertEquals(1, pairs.size)
        assertEquals(0, pairs.single().expenseIndex)
    }

    @Test
    fun `商户写法不同也能按金额配对_平台简称`() {
        val rows = listOf(
            expense(4_260, 2 * day, "拼多多", RefundMark.REFUNDED_EXPENSE),
            refund(4_260, 2 * day + 1 * hour, "拼多多平台商户"),
        )

        assertEquals(1, RefundPairing.pair(rows, BillSource.WECHAT).size)
    }

    @Test
    fun `退款必须晚于支出_之前的不配`() {
        val rows = listOf(
            expense(5_000, 20 * day, "某商户", RefundMark.REFUNDED_EXPENSE),
            refund(5_000, 10 * day, "某商户"),
        )

        assertTrue(RefundPairing.pair(rows, BillSource.WECHAT).isEmpty())
    }

    @Test
    fun `超出90天窗口不配`() {
        val rows = listOf(
            expense(5_000, 0, "某商户", RefundMark.REFUNDED_EXPENSE),
            refund(5_000, 91 * day, "某商户"),
        )

        assertTrue(RefundPairing.pair(rows, BillSource.WECHAT).isEmpty())
    }

    @Test
    fun `没有已退款标记的支出不是候选_不靠金额硬配`() {
        val rows = listOf(
            expense(5_000, 10 * day, "某商户"),                 // 普通支出，账单没说退款
            refund(5_000, 10 * day + 1 * hour, "某商户"),
        )

        assertTrue(RefundPairing.pair(rows, BillSource.WECHAT).isEmpty())
    }

    @Test
    fun `候选同商户且时间距离完全相同视为有歧义_不配`() {
        val rows = listOf(
            expense(5_000, 10 * day, "商户A", RefundMark.REFUNDED_EXPENSE),
            expense(5_000, 10 * day, "商户B", RefundMark.REFUNDED_EXPENSE),
            refund(5_000, 11 * day, "/"),
        )

        assertNull(RefundPairing.pair(rows, BillSource.WECHAT).firstOrNull())
    }

    @Test
    fun `支付宝按交易单号前缀精确配对_金额不同也认`() {
        val rows = listOf(
            expense(10_000, 3 * day, "Steam", RefundMark.REFUNDED_EXPENSE, externalId = "2026100322001448451421675858"),
            refund(3_480, 4 * day, "Steam", externalId = "2026100322001448451421675858_2026100319074102000920019200572"),
            // 同额干扰项：不应被选中
            expense(3_480, 3 * day, "Steam", RefundMark.REFUNDED_EXPENSE, externalId = "other"),
        )

        val pairs = RefundPairing.pair(rows, BillSource.ALIPAY)

        assertEquals(1, pairs.size)
        assertEquals(0, pairs.single().expenseIndex)
    }

    @Test
    fun `一笔支出只被一笔退款指名`() {
        val rows = listOf(
            expense(5_000, 10 * day, "某商户", RefundMark.REFUNDED_EXPENSE),
            refund(5_000, 10 * day + 1 * hour, "某商户"),
            refund(5_000, 10 * day + 2 * hour, "某商户"),
        )

        assertEquals(1, RefundPairing.pair(rows, BillSource.WECHAT).size)
    }

    @Test
    fun `商户相关性_简称与全称算同一家_斜杠与空不算`() {
        assertTrue(RefundPairing.merchantsRelated("拼多多平台商户", "拼多多"))
        assertTrue(RefundPairing.merchantsRelated("姜胖胖（厦门店）", "姜胖胖(厦门店)"))
        assertFalse(RefundPairing.merchantsRelated("滴滴顺风车", "滴滴出行"))
        assertFalse(RefundPairing.merchantsRelated("/", "/"))
        assertFalse(RefundPairing.merchantsRelated("", "某商户"))
    }

    @Test
    fun `真实微信账单_成对退款都能配上_只留转账退款无原支出`() {
        val file = java.io.File("../docs/book/微信支付账单流水文件(20260704-20261004)_20261004234212..xlsx")
        if (!file.exists()) return

        val bill = BillParsers.parse(file.readBytes()) ?: error("解析失败")
        val refundRows = bill.rows.count { it.refundMark == RefundMark.REFUND_INCOME }

        assertEquals(17, refundRows)
        assertEquals(16, bill.refundPairs.size)
        // 剩下那笔是「转账-退款 500」，账单里没有对应的转账支出
        val pairedRefundIndexes = bill.refundPairs.map { it.refundIndex }.toSet()
        val unpaired = bill.rows.indices.filter {
            bill.rows[it].refundMark == RefundMark.REFUND_INCOME && it !in pairedRefundIndexes
        }
        assertEquals(1, unpaired.size)
        assertEquals(50_000L, bill.rows[unpaired.single()].amountCents)
    }

    @Test
    fun `真实支付宝账单_退款全部按单号配回原支出`() {
        val file = java.io.File("../docs/book/支付宝交易明细(20260704-20261004)..csv")
        if (!file.exists()) return

        val bill = BillParsers.parse(file.readBytes()) ?: error("解析失败")
        val refundRows = bill.rows.count { it.refundMark == RefundMark.REFUND_INCOME }

        assertEquals(7, refundRows)
        assertEquals(refundRows, bill.refundPairs.size)
    }
}
