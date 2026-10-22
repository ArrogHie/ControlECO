package com.controleco.app.capture

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨通道归并测试：同一笔交易被「支付渠道（微信/支付宝/云闪付）+ 银行动账」双通道捕获时应判为同一笔。
 * 这是账目翻倍的头号来源（微信支付 ¥50 + 工行卡扣款短信 ¥50）。
 * 反向同样重要：**不该并的绝不能并**（两笔真实交易并成一笔 = 丢记录）。
 *
 * 口径（2026-10-22 用户规则）：
 * - 只认「微信/支付宝/云闪付 ⇄ 银行短信/推送」这一种配对，且**仅支出**；
 * - 两个支付渠道之间、两家银行之间不归并。
 */
class CrossChannelDedupTest {

    private val t0 = 1_700_000_000_000L

    private fun sig(
        amountCents: Long,
        type: TxType = TxType.EXPENSE,
        isRefund: Boolean = false,
        source: String = NotificationRules.WECHAT,
        time: Long = t0,
    ) = CrossChannelDedup.Signature(amountCents, type, isRefund, source, time)

    // ---------- 通道类别 ----------

    @Test
    fun `pay channels are pay class`() {
        assertFalse(CrossChannelDedup.isBankChannel(NotificationRules.WECHAT))
        assertFalse(CrossChannelDedup.isBankChannel(NotificationRules.ALIPAY))
    }

    @Test
    fun `bank channels are bank class`() {
        assertTrue(CrossChannelDedup.isBankChannel("SMS:工商银行"))
        assertTrue(CrossChannelDedup.isBankChannel(NotificationRules.UNIONPAY))
        // 银行 App 推送的包名（银行短名单里）
        assertTrue(CrossChannelDedup.isBankChannel("com.icbc"))
        assertTrue(CrossChannelDedup.isBankChannel("com.chinamworld.main"))
    }

    @Test
    fun `云闪付在跨应用链路算银行侧_在动账去重算支付渠道侧`() {
        // 两套口径刻意不同：跨应用链路里云闪付提供银行卡线索，
        // 而银行动账去重里它扣的是绑定卡，必须与微信/支付宝同侧，否则配对永远配不上
        assertTrue(CrossChannelDedup.isBankChannel(NotificationRules.UNIONPAY))
        assertTrue(CrossChannelDedup.isPayChannelSide(NotificationRules.UNIONPAY))
        assertFalse(CrossChannelDedup.isBankLedgerSide(NotificationRules.UNIONPAY))
        assertTrue(CrossChannelDedup.isPayChannelSide(NotificationRules.WECHAT))
        assertTrue(CrossChannelDedup.isPayChannelSide(NotificationRules.ALIPAY))
        assertTrue(CrossChannelDedup.isBankLedgerSide("SMS:招商银行"))
        assertTrue(CrossChannelDedup.isBankLedgerSide("cmb.pb"))
        assertFalse(CrossChannelDedup.isBankLedgerSide("com.jingdong.app.mall"))
    }

    @Test
    fun `unknown channels are not classified`() {
        // 早前草稿把"不在支付白名单里"一律当银行侧，电商/外卖收银台会被误判、与支付通知错误归并
        assertEquals(CrossChannelDedup.ChannelClass.UNKNOWN, CrossChannelDedup.channelClass("com.jingdong.app.mall"))
        assertEquals(CrossChannelDedup.ChannelClass.UNKNOWN, CrossChannelDedup.channelClass("com.example.unknown"))
    }

    @Test
    fun `refund mark maps to refund flag in signature`() {
        // 待确认条目用 refundMark 存退款标记，折算口径只在一处（signatureOf）
        val refund = CrossChannelDedup.signatureOf(
            5000, TxType.INCOME, RefundMark.REFUND_INCOME, NotificationRules.ALIPAY, t0,
        )
        val plain = CrossChannelDedup.signatureOf(
            5000, TxType.INCOME, RefundMark.NONE, NotificationRules.ALIPAY, t0,
        )
        assertTrue(refund.isRefund)
        assertFalse(plain.isRefund)
    }

    // ---------- 判为同一笔 ----------

    @Test
    fun `wechat payment and bank sms of same amount merge`() {
        assertTrue(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.WECHAT, time = t0),
                sig(5000, source = "SMS:工商银行", time = t0 + 30_000),
            ),
        )
    }

    @Test
    fun `alipay page and bank app push merge`() {
        assertTrue(
            CrossChannelDedup.isSameTransaction(
                sig(8888, source = NotificationRules.ALIPAY, time = t0),
                sig(8888, source = "com.icbc", time = t0 + 120_000),
            ),
        )
    }

    @Test
    fun `云闪付与银行动账同额同向归并`() {
        // 云闪付扣的是绑定银行卡：卡侧短信/推送与它描述的是一笔（2026-10-22 用户规则）
        assertTrue(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.UNIONPAY, time = t0),
                sig(5000, source = "SMS:招商银行", time = t0 + 10_000),
            ),
        )
    }

    // ---------- 不判为同一笔 ----------

    @Test
    fun `云闪付与微信支付宝同属支付渠道侧_不互相归并`() {
        // 用户规则：只做「支付渠道 ⇄ 银行」的去重，支付渠道之间同额就是两笔真实交易
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.WECHAT, time = t0),
                sig(5000, source = NotificationRules.UNIONPAY, time = t0 + 10_000),
            ),
        )
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.ALIPAY, time = t0),
                sig(5000, source = NotificationRules.UNIONPAY, time = t0 + 10_000),
            ),
        )
    }

    @Test
    fun `收入方向不归并`() {
        // 微信/支付宝收到的钱进零钱/余额，不会同时产生银行入账；同额两条收入更可能是两笔独立的钱
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, type = TxType.INCOME, source = NotificationRules.WECHAT, time = t0),
                sig(5000, type = TxType.INCOME, source = "SMS:工商银行", time = t0 + 30_000),
            ),
        )
    }

    @Test
    fun `电商外卖页面不是支付渠道侧_不与银行动账归并`() {
        // 电商页 ⇄ 银行由 CrossAppLinker 负责，不在这条链路里
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = "com.jingdong.app.mall", time = t0),
                sig(5000, source = "SMS:工商银行", time = t0),
            ),
        )
    }

    @Test
    fun `two pay apps with same amount are not merged`() {
        // 微信与支付宝各付一笔同额是两笔真实交易，不成对
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.WECHAT, time = t0),
                sig(5000, source = NotificationRules.ALIPAY, time = t0),
            ),
        )
    }

    @Test
    fun `two bank channels with same amount are not merged`() {
        // 两家银行各一笔同额动账（或同一家的两条短信）在不读卡号的前提下无法与"同一笔的重复线索"区分，
        // 用户规则也只要求「支付渠道 ⇄ 银行」这一种配对，保守起见不并（宁可两条待确认，不冒丢记录的风险）
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = "SMS:工商银行", time = t0),
                sig(5000, source = "SMS:建设银行", time = t0),
            ),
        )
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = "SMS:工商银行", time = t0),
                sig(5000, source = "com.icbc", time = t0),
            ),
        )
    }

    @Test
    fun `unknown source never merges`() {
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.WECHAT, time = t0),
                sig(5000, source = "com.example.unknown", time = t0),
            ),
        )
    }

    @Test
    fun `different amount is not merged`() {
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.WECHAT, time = t0),
                sig(5001, source = "SMS:工商银行", time = t0),
            ),
        )
    }

    @Test
    fun `different direction is not merged`() {
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, type = TxType.EXPENSE, source = NotificationRules.WECHAT, time = t0),
                sig(5000, type = TxType.INCOME, source = "SMS:工商银行", time = t0),
            ),
        )
    }

    @Test
    fun `refund flag difference is not merged`() {
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, type = TxType.INCOME, isRefund = false, source = NotificationRules.WECHAT, time = t0),
                sig(5000, type = TxType.INCOME, isRefund = true, source = "SMS:工商银行", time = t0),
            ),
        )
    }

    @Test
    fun `outside the window is not merged`() {
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.WECHAT, time = t0),
                sig(5000, source = "SMS:工商银行", time = t0 + CrossChannelDedup.WINDOW_MS + 1),
            ),
        )
    }

    @Test
    fun `inside the window boundary is merged`() {
        assertTrue(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.WECHAT, time = t0),
                sig(5000, source = "SMS:工商银行", time = t0 + CrossChannelDedup.WINDOW_MS),
            ),
        )
    }

    @Test
    fun `bank evidence requires bank name or card tail`() {
        // 金额与时间吻合还不够：支付侧条目必须出现银行名/卡尾号才能与银行侧合并
        assertFalse(CrossChannelDedup.hasBankEvidence("", "工商银行", "1234"))
        assertFalse(CrossChannelDedup.hasBankEvidence("微信支付 · 已支付¥50.00", "工商银行", "1234"))
        assertTrue(CrossChannelDedup.hasBankEvidence("支付方式 工商银行储蓄卡", "工商银行", null))
        assertTrue(CrossChannelDedup.hasBankEvidence("尾号1234", "工商银行", "1234"))
    }

    // ---------- 银行侧点名支付渠道（真实招行样本的反向线索） ----------

    /** 真实样本（2026-10-21）招商银行动账通知正文 */
    private val cmbNotice =
        "招商银行 您账户2532于10月21日16:54在【财付通-微信支付-微信转账】发生快捷支付扣款，人民币200.00"

    @Test
    fun `银行侧点名微信支付即为可核对线索`() {
        // 支付侧（微信支付成功页）没有任何银行卡信息时，银行侧的"财付通-微信支付"就是唯一线索
        assertTrue(CrossChannelDedup.bankTextMentionsPayChannel(cmbNotice, NotificationRules.WECHAT))
        assertTrue(CrossChannelDedup.bankTextMentionsPayChannel("【财付通】快捷支付扣款", NotificationRules.WECHAT))
    }

    @Test
    fun `银行侧不点名渠道时线索不成立`() {
        assertFalse(CrossChannelDedup.bankTextMentionsPayChannel(cmbNotice, NotificationRules.ALIPAY))
        assertFalse(CrossChannelDedup.bankTextMentionsPayChannel(cmbNotice, NotificationRules.UNIONPAY))
        assertFalse(CrossChannelDedup.bankTextMentionsPayChannel("您尾号1234的账户消费50.00元", NotificationRules.WECHAT))
        assertFalse(CrossChannelDedup.bankTextMentionsPayChannel("", NotificationRules.WECHAT))
        // 电商页面不是支付渠道侧，即便银行文本里出现"微信"也不该拿它当配对依据
        assertFalse(CrossChannelDedup.bankTextMentionsPayChannel(cmbNotice, "com.jingdong.app.mall"))
    }

    @Test
    fun `支付宝与云闪付的银行侧线索`() {
        assertTrue(CrossChannelDedup.bankTextMentionsPayChannel("在【支付宝-商户】发生快捷支付扣款", NotificationRules.ALIPAY))
        assertTrue(CrossChannelDedup.bankTextMentionsPayChannel("银联云闪付消费50.00元", NotificationRules.UNIONPAY))
        assertTrue(CrossChannelDedup.bankTextMentionsPayChannel("您通过银联通道消费50.00元", NotificationRules.UNIONPAY))
    }

    @Test
    fun `支付侧与银行侧任一有线索即可配对`() {
        // 支付侧写了银行名 → 成立
        assertTrue(
            CrossChannelDedup.payBankLinked(
                paymentText = "支付方式 招商银行储蓄卡(2532)",
                bankText = "招商银行 您账户2532于10月21日16:54消费200.00元",
                bankName = "招商银行",
                cardTail = null,
                paySource = NotificationRules.WECHAT,
            ),
        )
        // 支付侧没有线索，但银行侧点名了微信支付 → 同样成立（真实事故那一笔）
        assertTrue(
            CrossChannelDedup.payBankLinked(
                paymentText = "微信·支付成功页",
                bankText = cmbNotice,
                bankName = "招商银行",
                cardTail = null,
                paySource = NotificationRules.WECHAT,
            ),
        )
        // 两侧都没有线索 → 证据不足，不配对
        assertFalse(
            CrossChannelDedup.payBankLinked(
                paymentText = "微信·支付成功页",
                bankText = "招商银行 您账户2532于10月21日16:54消费200.00元",
                bankName = "招商银行",
                cardTail = null,
                paySource = NotificationRules.WECHAT,
            ),
        )
    }

    // ---------- 真实样本端到端（银行解析 → 签名 → 同笔判定） ----------

    @Test
    fun `真实样本_微信200转账与招行快捷支付扣款是同一笔`() {
        // 用户实测（2026-10-21）：这笔曾记成两条账单
        val bank = BankLedgerRules.parse(
            "招商银行 您账户2532于10月21日16:54在【财付通-微信支付-微信转账】发生快捷支付扣款，人民币200.00",
            "cmb.pb", "通知", t0, "招商银行",
        )!!
        // 支付侧是微信支付成功页：没有任何银行卡信息（卡信息只出现在后续的付款方式页）
        val wechatPageText = "支付成功 待常时福确认收款 ￥200.00 完成"
        val bankSig = CrossChannelDedup.signatureOf(
            amountCents = bank.amountCents,
            type = bank.type,
            refundMark = RefundMark.NONE,
            source = bank.sourceKey,
            time = t0,
        )
        val paySig = CrossChannelDedup.Signature(
            amountCents = 20_000,
            type = TxType.EXPENSE,
            isRefund = false,
            source = NotificationRules.WECHAT,
            time = t0 + 3_000,
        )
        assertEquals(TxType.EXPENSE, bank.type)
        assertEquals(20_000L, bank.amountCents)
        assertTrue(CrossChannelDedup.isSameTransaction(bankSig, paySig))
        // 线索来自银行正文："财付通-微信支付"
        assertTrue(CrossChannelDedup.payBankLinked(
            paymentText = wechatPageText,
            bankText = bank.note,
            bankName = bank.bankName,
            cardTail = bank.cardTail,
            paySource = NotificationRules.WECHAT,
        ))
    }
}
