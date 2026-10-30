package com.controleco.app.capture

import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首次捕获固定 180s：通知/页面/商家/银行的先后顺序、用户确认/忽略、第三四次捕获不能续期。
 * 采用真实项目的来源包名，不依赖订单号或商户、支付渠道必填。
 */
class LogicalTransactionsSequenceTest {
    private val t0 = 1_770_000_000_000L
    private val amount = 1920L
    private val wechat = NotificationRules.WECHAT
    private val meituan = NotificationRules.MEITUAN_TAKEOUT
    private val bankSms = "SMS:招商银行"
    private val bankPush = "cmb.pb"

    private fun key(pkg: String, identity: String): String =
        "$pkg|EXPENSE|$amount|${t0 / 60_000}@$identity"

    private fun pending(pkg: String, identity: String, status: PendingStatus = PendingStatus.PENDING,
                        createdAt: Long = t0, timestamp: Long = t0): PendingTransactionEntity =
        PendingTransactionEntity(
            id = 42, type = TxType.EXPENSE, amountCents = amount, timestamp = timestamp,
            sourcePackage = pkg, ruleName = "支付成功", dedupKey = key(pkg, identity),
            createdAt = createdAt, status = status, note = "支付成功",
            notificationInstanceId = identity.takeIf { it.startsWith("n:") }.orEmpty(),
            bankInstanceId = identity.takeIf { it.startsWith("b:") }.orEmpty(),
            flowId = identity.takeIf { it.startsWith("p:") }.orEmpty(),
        )

    private fun capture(pkg: String, identity: String, elapsedMs: Long,
                        amountCents: Long = amount, isRefund: Boolean = false): CaptureObservation =
        CaptureObservation(
            CrossAppLinker.Evidence(
                sourcePackage = pkg, amountCents = amountCents, type = TxType.EXPENSE,
                isRefund = isRefund, time = t0 + elapsedMs,
                // 商家与支付页面可能一个字段也读不到，因此不要求订单号/付款渠道。
                counterparty = "", instrument = "", note = "", ruleName = "",
            ),
            identity = identity, dedupKey = key(pkg, identity),
        )

    @Before fun init() = LogicalTransactions.clearAll()
    @After fun clean() = LogicalTransactions.clearAll()

    @Test fun `通知先到未处理_连续四次从通知到页面商家银行都归同笔`() {
        val original = pending(wechat, "n:wx1")
        val page = capture(wechat, "p:wxpage", 20_000)
        val checkout = capture(meituan, "p:order1", 40_000)
        val sms = capture(bankSms, "b:sms1", 55_000)
        assertEquals(original, LogicalTransactions.findActive(listOf(original), page, t0 + 20_000))
        LogicalTransactions.observe(original, page)
        assertEquals(original, LogicalTransactions.findActive(listOf(original), checkout, t0 + 40_000))
        LogicalTransactions.observe(original, checkout)
        assertEquals(original, LogicalTransactions.findActive(listOf(original), sms, t0 + 55_000))
        LogicalTransactions.observe(original, sms)
        assertTrue(LogicalTransactions.sameLogical(key(bankSms, "b:sms1"), original.dedupKey))
        assertTrue(LogicalTransactions.sameLogical(key(meituan, "p:order1"), original.dedupKey))
    }

    @Test fun `商家先到仍未处理_第二第三第四次可经已关联来源匹配`() {
        val original = pending(meituan, "p:order1")
        val payNotification = capture(wechat, "n:wx1", 18_000)
        assertNotNull(LogicalTransactions.findActive(listOf(original), payNotification, t0 + 18_000))
        LogicalTransactions.observe(original, payNotification)
        val sms = capture(bankSms, "b:sms1", 43_000)
        assertNotNull(LogicalTransactions.findActive(listOf(original), sms, t0 + 43_000))
        LogicalTransactions.observe(original, sms)
        val payPage = capture(wechat, "p:wxpage", 58_000)
        assertNotNull(LogicalTransactions.findActive(listOf(original), payPage, t0 + 58_000))
    }

    @Test fun `确认后跨应用重复捕获仍能命中且不因后续捕获续期`() {
        val original = pending(wechat, "n:wx1", timestamp = t0 + 80_000)
        LogicalTransactions.markResolved(original, CaptureResolution.HANDLED, now = t0 + 160_000)
        val meituanAt170 = capture(meituan, "p:order1", 170_000)
        assertNotNull(LogicalTransactions.findResolved(meituanAt170, t0 + 170_000))
        // 第三次在首次后的179s，仍应命中。
        assertNotNull(LogicalTransactions.findResolved(capture(wechat, "p:wxpage", 179_000), t0 + 179_000))
        // 第四次首次后的181s，不允许因为第二/第三次而变成新的+180s窗口。
        assertNull(LogicalTransactions.findResolved(capture(bankSms, "b:sms1", 181_000), t0 + 181_000))
    }

    @Test fun `忽略后跨应用出现迟到的两到三次捕获同样不弹新卡`() {
        val original = pending(meituan, "p:order1")
        LogicalTransactions.observe(original, capture(wechat, "n:wx1", 20_000))
        LogicalTransactions.markResolved(original, CaptureResolution.REJECTED, now = t0 + 30_000)
        assertEquals(CaptureResolution.REJECTED,
            LogicalTransactions.findResolved(capture(bankSms, "b:sms1", 40_000), t0 + 40_000)?.resolution)
        assertNotNull(LogicalTransactions.findResolved(capture(wechat, "p:wxpage", 170_000), t0 + 170_000))
        assertNull(LogicalTransactions.findResolved(capture(bankPush, "b:push1", 185_000), t0 + 185_000))
    }

    @Test fun `确认是在五分钟以后_不能从点击确认时间重开三分钟`() {
        val original = pending(wechat, "n:wx1")
        LogicalTransactions.markResolved(original, CaptureResolution.HANDLED, now = t0 + 300_000)
        assertNull(LogicalTransactions.findResolved(capture(meituan, "p:order1", 301_000), t0 + 301_000))
    }

    @Test fun `银行短信先到_处理后银行推送仍识别为同笔`() {
        val original = pending(bankSms, "b:sms1")
        LogicalTransactions.markResolved(original, CaptureResolution.HANDLED, now = t0 + 10_000)
        assertNotNull(LogicalTransactions.findResolved(capture(bankPush, "b:push1", 30_000), t0 + 30_000))
        assertNotNull(LogicalTransactions.findResolved(capture(wechat, "p:wxpage", 50_000), t0 + 50_000))
    }

    @Test fun `融合与确认交错_迟到的别名仍追加到终态`() {
        val original = pending(meituan, "p:order1")
        LogicalTransactions.markResolved(original, CaptureResolution.HANDLED, now = t0 + 30_000)
        // 第二个捕获已被识别属于原交易，但数据库合并协程恰好晚于用户确认。
        LogicalTransactions.observe(original, capture(wechat, "n:wx1", 35_000))
        // 第三个银行捕获必须能通过刚才追加的微信来源继续命中终态。
        assertNotNull(LogicalTransactions.findResolved(capture(bankSms, "b:sms1", 48_000), t0 + 48_000))
    }

    @Test fun `同一来源同一通道不同实例不误合并`() {
        val original = pending(wechat, "n:wx1")
        assertNull(LogicalTransactions.findActive(listOf(original), capture(wechat, "n:wx2", 30_000), t0 + 30_000))
        LogicalTransactions.markResolved(original, CaptureResolution.HANDLED, now = t0 + 40_000)
        assertNull(LogicalTransactions.findResolved(capture(wechat, "n:wx2", 50_000), t0 + 50_000))
    }

    @Test fun `不同金额_退款标记_不同支付应用不能仅靠同额短窗合并`() {
        val original = pending(wechat, "n:wx1")
        assertNull(LogicalTransactions.findActive(listOf(original), capture(meituan, "p:x", 20_000, 1930), t0 + 20_000))
        assertNull(LogicalTransactions.findActive(listOf(original), capture(meituan, "p:x", 20_000, isRefund = true), t0 + 20_000))
        assertNull(LogicalTransactions.findActive(listOf(original), capture(NotificationRules.ALIPAY, "n:ali1", 20_000), t0 + 20_000))
    }

    @Test fun `银行事件登记后续通道不能刷新首次捕获窗口`() {
        val claims = BankEventClaims()
        val bankKey = BankEventDedup.keyOf("招商银行", amount, TxType.EXPENSE, false, "2532")!!
        claims.claim(42, bankKey, BankEventDedup.Channel.SMS, t0, now = t0)
        assertEquals(42L, claims.find(bankKey, BankEventDedup.Channel.PUSH, t0 + 170_000, now = t0 + 170_000))
        claims.claim(42, bankKey, BankEventDedup.Channel.PUSH, t0 + 170_000, now = t0 + 170_000)
        assertNull(claims.find(bankKey, BankEventDedup.Channel.PAGE, t0 + 181_000, now = t0 + 181_000))
    }
}
