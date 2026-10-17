package com.controleco.app.capture

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨通道归并测试：同一笔交易被「支付渠道 + 银行动账」双通道捕获时应判为同一笔。
 * 这是账目翻倍的头号来源（微信支付 ¥50 + 工行卡扣款短信 ¥50）。
 * 反向同样重要：**不该并的绝不能并**（两笔真实交易并成一笔 = 丢记录）。
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
    fun `unionpay notice and wechat payment merge across classes`() {
        // 云闪付属银行侧：它与支付类通知之间允许归并
        assertTrue(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = NotificationRules.WECHAT, time = t0),
                sig(5000, source = NotificationRules.UNIONPAY, time = t0 + 10_000),
            ),
        )
    }

    // ---------- 不判为同一笔 ----------

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
        // 云闪付通知与银行短信大概率是同一笔，但在不读卡号的前提下无法与"两卡同额"区分，
        // 保守起见不并（宁可两条待确认，不冒丢记录的风险）
        assertFalse(
            CrossChannelDedup.isSameTransaction(
                sig(5000, source = "SMS:工商银行", time = t0),
                sig(5000, source = "SMS:建设银行", time = t0),
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
}
