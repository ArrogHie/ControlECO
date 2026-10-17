package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨应用支付关联（CR-004）：页面 + 支付通知/银行动账必须存在可核对线索，
 * 只凭"金额相同 + 时间接近"绝不合并。
 */
class CrossAppLinkerTest {

    private val t0 = 1_700_000_000_000L

    private fun page(
        instrument: String,
        source: String = NotificationRules.MEITUAN_TAKEOUT,
        amount: Long = 1800,
        type: TxType = TxType.EXPENSE,
        time: Long = t0,
        counterparty: String = "美团外卖",
        note: String = "",
        ruleName: String = "美团外卖·支付结果页",
        orderId: String = "",
    ) = CrossAppLinker.Evidence(
        sourcePackage = source,
        amountCents = amount,
        type = type,
        isRefund = false,
        time = time,
        counterparty = counterparty,
        instrument = instrument,
        note = note,
        ruleName = ruleName,
        orderId = orderId,
    )

    private fun other(
        source: String,
        amount: Long = 1800,
        type: TxType = TxType.EXPENSE,
        time: Long = t0 + 30_000,
        instrument: String = "",
        note: String = "",
        ruleName: String = "支付宝·支出/消费",
        counterparty: String = "",
        orderId: String = "",
    ) = CrossAppLinker.Evidence(
        sourcePackage = source,
        amountCents = amount,
        type = type,
        isRefund = false,
        time = time,
        counterparty = counterparty,
        instrument = instrument,
        note = note,
        ruleName = ruleName,
        orderId = orderId,
    )

    @Test
    fun `美团页面写着支付宝_与支付宝同额通知关联`() {
        assertTrue(
            CrossAppLinker.isSamePurchase(
                page("支付宝"),
                other(NotificationRules.ALIPAY),
            ),
        )
    }

    @Test
    fun `页面写着微信支付_与微信通知关联_与支付宝不关联`() {
        assertTrue(CrossAppLinker.isSamePurchase(page("微信支付"), other(NotificationRules.WECHAT)))
        assertFalse(CrossAppLinker.isSamePurchase(page("微信支付"), other(NotificationRules.ALIPAY)))
    }

    @Test
    fun `页面只写渠道名也能被结构化解析`() {
        val instrument = com.controleco.app.capture.page.PaymentInstrumentParser.parse("支付宝", "淘宝")!!
        assertEquals(com.controleco.app.capture.page.InstrumentKind.PAYMENT_CHANNEL, instrument.kind)
        assertTrue(CrossAppLinker.isSamePurchase(page("支付宝"), other(NotificationRules.ALIPAY)))
    }

    @Test
    fun `页面银行卡信息能对上银行短信`() {
        val bank = other(
            source = "SMS:招商银行",
            instrument = "招商银行(1234)",
            ruleName = "招商银行短信·支出",
        )
        assertTrue(CrossAppLinker.isSamePurchase(page("招商银行(1234)"), bank))
        // 卡尾号不一致 → 不是同一张卡
        assertFalse(CrossAppLinker.isSamePurchase(page("招商银行(5678)"), bank))
        // 银行名不一致 → 不关联
        assertFalse(CrossAppLinker.isSamePurchase(page("工商银行(1234)"), bank))
    }

    @Test
    fun `页面没有支付方式_不凭金额时间关联`() {
        assertFalse(CrossAppLinker.isSamePurchase(page(""), other(NotificationRules.ALIPAY)))
    }

    @Test
    fun `金额方向时间不一致都不关联`() {
        assertFalse(CrossAppLinker.isSamePurchase(page("支付宝"), other(NotificationRules.ALIPAY, amount = 1801)))
        assertFalse(
            CrossAppLinker.isSamePurchase(
                page("支付宝"), other(NotificationRules.ALIPAY, type = TxType.INCOME),
            ),
        )
        assertFalse(
            CrossAppLinker.isSamePurchase(
                page("支付宝"), other(NotificationRules.ALIPAY, time = t0 + CrossAppLinker.WINDOW_MS + 1),
            ),
        )
    }

    @Test
    fun `充值转账提现不参与跨应用关联`() {
        assertFalse(
            CrossAppLinker.isSamePurchase(
                page("支付宝", ruleName = "淘宝·充值成功页"),
                other(NotificationRules.ALIPAY),
            ),
        )
        assertFalse(
            CrossAppLinker.isSamePurchase(
                page("支付宝"),
                other(NotificationRules.ALIPAY, note = "转账成功 1800 元", ruleName = "支付宝·转账"),
            ),
        )
    }

    @Test
    fun `商户名明显冲突时不关联`() {
        assertFalse(
            CrossAppLinker.isSamePurchase(
                page("支付宝", counterparty = "肯德基"),
                other(NotificationRules.ALIPAY, counterparty = "麦当劳"),
            ),
        )
    }

    @Test
    fun `订单号相同是强证据`() {
        assertTrue(
            CrossAppLinker.isSamePurchase(
                page("", orderId = "ORDER-1"),
                other(NotificationRules.WECHAT, instrument = "", orderId = "ORDER-1", time = t0 + 10_000),
            ),
        )
    }

    @Test
    fun `未知来源不参与跨应用关联`() {
        assertFalse(CrossAppLinker.isSamePurchase(page("支付宝"), other("com.example.shop")))
    }
}
