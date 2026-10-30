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
    fun `页面没有支付方式_短时同额可关联但超出窗口不关联`() {
        // 2026-10-19 规则调整：页面读不到支付方式时，同一笔仍会同时被商户页与支付应用捕获；
        // 180 秒内完全相同金额即视为同一笔（真实样本：美团 ¥19.20 + 微信 ¥19.20）。
        assertTrue(CrossAppLinker.isSamePurchase(page(""), other(NotificationRules.ALIPAY)))
        // 窗口外不凭金额关联（时间越远，"同额"越可能是另一笔）
        assertFalse(
            CrossAppLinker.isSamePurchase(
                page(""), other(NotificationRules.ALIPAY, time = t0 + CrossAppLinker.SAME_AMOUNT_WINDOW_MS + 1),
            ),
        )
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

    // ---------- 跨链路短时同额（2026-10-19：美团微信支付被记两笔的真实样本） ----------

    @Test
    fun `真实样本_美团页面没有支付方式时与微信支付通知短时同额判重`() {
        // capturedAt=10-19 17:40 同一分钟：
        //   美团外卖结果页 ¥19.20（页面无"支付方式"节点）
        //   微信支付成功页 ¥19.20 / 微信通知"已支付¥19.20"
        // 旧实现要求页面写出渠道才合并 → 记成两笔。新规则：两条链路短时同额即同一笔。
        val meituan = page(instrument = "", amount = 1920, counterparty = "美团外卖")
        val wechatNtf = other(
            NotificationRules.WECHAT, amount = 1920, time = t0 + 3_000,
            ruleName = "微信·支出/消费", note = "微信支付: 已支付¥19.20",
        )
        assertEquals(CrossAppLinker.LinkReason.SAME_AMOUNT, CrossAppLinker.linkReason(meituan, wechatNtf))
        assertTrue(CrossAppLinker.isSamePurchase(meituan, wechatNtf))
        // 反方向（微信支付成功页后到、美团页已存在）同样成立
        val wechatPage = other(
            NotificationRules.WECHAT, amount = 1920, time = t0 + 5_000,
            ruleName = "微信·支付成功页", counterparty = "美团",
        )
        assertEquals(CrossAppLinker.LinkReason.SAME_AMOUNT, CrossAppLinker.linkReason(meituan, wechatPage))
    }

    @Test
    fun `短时同额窗口边界_恰好一百八十秒仍判重_超过不判`() {
        val meituan = page(instrument = "", amount = 1920)
        val at60 = other(NotificationRules.WECHAT, amount = 1920, time = t0 + CrossAppLinker.SAME_AMOUNT_WINDOW_MS)
        val at61 = other(NotificationRules.WECHAT, amount = 1920, time = t0 + CrossAppLinker.SAME_AMOUNT_WINDOW_MS + 1)
        assertTrue(CrossAppLinker.isSamePurchase(meituan, at60))
        assertFalse(CrossAppLinker.isSamePurchase(meituan, at61))
    }

    @Test
    fun `短时同额窗口内金额不同_不判重`() {
        val meituan = page(instrument = "", amount = 1920)
        assertFalse(CrossAppLinker.isSamePurchase(meituan, other(NotificationRules.WECHAT, amount = 1921)))
    }

    @Test
    fun `两个支付应用之间不按同额判重_只允许商户页与支付侧`() {
        // 微信通知与支付宝通知同额（同一分钟内两笔不同渠道的付款）绝不能合并：
        // 同额档要求"一侧必须是电商/外卖页面"。
        val wechatNotice = other(NotificationRules.WECHAT, amount = 1920, ruleName = "微信·支出/消费")
        val alipayNotice = other(NotificationRules.ALIPAY, amount = 1920, ruleName = "支付宝·支出/消费")
        assertFalse(CrossAppLinker.isSamePurchase(wechatNotice, alipayNotice))
        // 两个外卖页面之间也不合并（各自都是不同订单）
        val meituan = page(instrument = "", amount = 1920, source = NotificationRules.MEITUAN_TAKEOUT)
        val eleme = page(instrument = "", amount = 1920, source = "me.ele", counterparty = "饿了么")
        assertFalse(CrossAppLinker.isSamePurchase(meituan, eleme))
    }

    @Test
    fun `页面写支付宝_微信通知同额不合并`() {
        val meituan = page(instrument = "支付宝", amount = 1920)
        val wechatNotice = other(NotificationRules.WECHAT, amount = 1920, time = t0 + 3_000)
        assertFalse(CrossAppLinker.isSamePurchase(meituan, wechatNotice))
        // 与支付宝通知仍然合并
        assertTrue(CrossAppLinker.isSamePurchase(meituan, other(NotificationRules.ALIPAY, amount = 1920)))
    }

    @Test
    fun `商户名明显冲突时短时同额也不合并`() {
        val meituan = page(instrument = "", amount = 1920, counterparty = "肯德基")
        val wechat = other(
            NotificationRules.WECHAT, amount = 1920,
            counterparty = "麦当劳", ruleName = "微信·支出/消费",
        )
        assertFalse(CrossAppLinker.isSamePurchase(meituan, wechat))
        // 包含关系（"美团" ⊂ "美团外卖"）可以合并
        val meituanPage = page(instrument = "", amount = 1920, counterparty = "美团")
        val wechatMeituan = other(
            NotificationRules.WECHAT, amount = 1920,
            counterparty = "美团外卖", ruleName = "微信·支出/消费",
        )
        assertTrue(CrossAppLinker.isSamePurchase(meituanPage, wechatMeituan))
    }

    @Test
    fun `银行侧不适用短时同额_仍需页面写出银行线索`() {
        // 银行短信只有金额与卡尾号：页面没写银行名/尾号时不按同额合并
        val meituan = page(instrument = "", amount = 1920)
        val bank = other(
            source = "SMS:招商银行", amount = 1920, time = t0 + 3_000,
            instrument = "招商银行(1234)", ruleName = "招商银行短信·支出",
        )
        assertFalse(CrossAppLinker.isSamePurchase(meituan, bank))
        // 页面写出对应卡（招行 1234）→ 明确线索合并
        assertTrue(CrossAppLinker.isSamePurchase(page("招商银行(1234)", amount = 1920), bank))
        // 页面写了另一张卡 → 同额也不合并
        assertFalse(
            CrossAppLinker.isSamePurchase(page("招商银行(5678)", amount = 1920), bank),
        )
    }

    @Test
    fun `充值转账等同额也不参与判重`() {
        val meituan = page(instrument = "", amount = 1920, ruleName = "美团·充值成功页")
        assertFalse(
            CrossAppLinker.isSamePurchase(
                meituan, other(NotificationRules.WECHAT, amount = 1920, ruleName = "微信·支出/消费"),
            ),
        )
    }
}
