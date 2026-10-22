package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知规则引擎测试。样本来自真实通知（用户实测样本见 docs/开发日志.md）
 * 与已知格式；新样本请加入捕获日志后对照扩规则。
 */
class NotificationRulesTest {

    private val postTime = 1_700_000_000_000L

    private fun parse(pkg: String, title: String, text: String, bigText: String = "") =
        NotificationRules.parse(pkg, title, text, bigText, postTime)

    // ---------- 收款/入账 ----------

    @Test
    fun `wechat income`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "微信支付收款0.01元（朋友到账）")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1L, r.amountCents)
        assertEquals("com.tencent.mm|INCOME|1|${postTime / 60_000}", r.dedupKey)
    }

    @Test
    fun `红包领取类通知不自动入账`() {
        // 红包改由领取页悬浮卡确认；通知侧不能把“红包到账/你领取了”当普通收入。
        assertNull(parse(NotificationRules.WECHAT, "微信", "你领取了对方的红包"))
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "红包到账5.20元"))
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "微信红包收款5.20元"))
        assertNull(NotificationRules.parseDirectionOnly(NotificationRules.WECHAT, "微信", "你领取了对方的红包"))
        // 普通转账/收款通知不受影响
        assertEquals(
            TxType.INCOME,
            parse(NotificationRules.WECHAT, "微信支付", "微信支付收款0.01元（朋友到账）")?.type,
        )
    }

    @Test
    fun `wechat income with colon`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "收款入账：10.00元\n存入零钱")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1000L, r.amountCents)
    }

    @Test
    fun `wechat income with currency in bigText`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "已存入零钱", bigText = "收款金额¥0.01")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1L, r.amountCents)
    }

    @Test
    fun `wechat income integer amount`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "微信支付收款100元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(10000L, r.amountCents)
    }

    @Test
    fun `alipay income`() {
        val r = parse(NotificationRules.ALIPAY, "支付宝通知", "支付宝成功收款1.00元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(100L, r.amountCents)
        assertEquals("支付宝", r.sourceLabel)
    }

    @Test
    fun `alipay receive code payment is income`() {
        // 真实样本（2026-10-18）：收钱码收款通知只有"向你付款"，没有"收款"，
        // 旧规则只命中泛化的"付款"，把 2.00 元收款记成了支出
        val r = parse(NotificationRules.ALIPAY, "收钱码", "收钱码 倩蕾通过扫码向你付款2.00元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(200L, r.amountCents)
        assertEquals("支付宝·收款/入账", r.ruleName)
        assertEquals("com.eg.android.AlipayGphone|INCOME|200|${postTime / 60_000}", r.dedupKey)
    }

    @Test
    fun `receive code without amount yields income direction`() {
        val hint = NotificationRules.parseDirectionOnly(
            NotificationRules.ALIPAY,
            "收钱码",
            "倩蕾通过扫码向你付款",
        )!!
        assertEquals(TxType.INCOME, hint.type)
    }

    @Test
    fun `transfer to someone else remains expense`() {
        // "向张三付款"是支出；只有"向你付款/付款给你"才是入账
        val r = parse(NotificationRules.ALIPAY, "转账提醒", "你向张三付款10.00元")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1000L, r.amountCents)
    }

    @Test
    fun `unionpay income`() {
        val r = parse(
            NotificationRules.UNIONPAY,
            "云闪付",
            "您的尾号1234的账户于10月04日17:33成功入账100.00元，可用余额200.00元",
        )!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(10000L, r.amountCents)
    }

    // ---------- 支出（真实样本：2026-10 实测） ----------

    @Test
    fun `wechat expense with currency prefix`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "已支付¥12.80", bigText = " ；")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1280L, r.amountCents)
        assertEquals(false, r.isRefund)
        assertEquals("com.tencent.mm|EXPENSE|1280|${postTime / 60_000}", r.dedupKey)
    }

    @Test
    fun `alipay expense with promo after keyword`() {
        // 真实样本（2026-10）：关键词后的"领1元生活缴费红包"是营销文案，
        // 金额在关键词前（0.01元），反向正则必须优先于正向
        val r = parse(NotificationRules.ALIPAY, "交易提醒", "你有一笔0.01元的支出，领1元生活缴费红包。")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1L, r.amountCents)
    }

    @Test
    fun `alipay expense with amount before keyword`() {
        val r = parse(NotificationRules.ALIPAY, "交易提醒", "你有一笔14.90元的支出，点此查看详情。")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1490L, r.amountCents)
        assertEquals(false, r.isRefund)
    }

    @Test
    fun `alipay expense with long modifier before keyword`() {
        // 真实样本（2026-10-11）：免密扣款通知，金额与"扣款"之间的修饰语超过 3 个字；
        // 关键词后的"领取6个支付宝积分"不能被当成金额
        val r = parse(
            NotificationRules.ALIPAY,
            "交易提醒",
            "你在浙江嗨便利网络科技有限公司有一笔2.98元的免密/自动扣款支付，点击领取6个支付宝积分。",
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(298L, r.amountCents)
    }

    @Test
    fun `unionpay expense`() {
        val r = parse(NotificationRules.UNIONPAY, "云闪付", "您尾号1234的账户消费50.00元")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(5000L, r.amountCents)
    }

    @Test
    fun `wechat auto deduction with grouped count prefix`() {
        // 真实样本（2026-10-22 用户实测，漏记）：
        // text=[11条]微信支付: 已扣费¥6.50 —— "扣费"此前不在支出方向词里，整条被判「方向不明」丢掉
        val r = parse(NotificationRules.WECHAT, "微信支付", "[11条]微信支付: 已扣费¥6.50")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(650L, r.amountCents)
        assertEquals(false, r.isRefund)
        assertEquals("com.tencent.mm|EXPENSE|650|${postTime / 60_000}", r.dedupKey)
    }

    @Test
    fun `grouped count prefix is optional and the number varies`() {
        // "[xx条]" 不是固定词：可能不存在、位数可变、全角括号、可带"消息"
        for (text in listOf(
            "[1条]微信支付: 已扣费¥6.50",
            "[11条]微信支付: 已扣费¥6.50",
            "[128条]微信支付: 已扣费¥6.50",
            "［11条］微信支付: 已扣费¥6.50",
            "[11条消息] 微信支付: 已扣费¥6.50",
            "微信支付: 已扣费¥6.50",
        )) {
            val r = parse(NotificationRules.WECHAT, "微信支付", text)
            assertTrue("未解析: $text", r != null)
            assertEquals("方向: $text", TxType.EXPENSE, r!!.type)
            assertEquals("金额: $text", 650L, r.amountCents)
        }
        assertEquals("微信支付: 已扣费¥6.50", NotificationRules.normalize("[11条]微信支付: 已扣费¥6.50"))
    }

    @Test
    fun `deduction amount before keyword parses too`() {
        // 同一族写法的另两种形态：金额在"扣费"前 / 关键词后带"元"
        val before = parse(NotificationRules.WECHAT, "微信支付", "6.50元的自动扣费")!!
        assertEquals(TxType.EXPENSE, before.type)
        assertEquals(650L, before.amountCents)
        val after = parse(NotificationRules.WECHAT, "微信支付", "已扣费6.50元")!!
        assertEquals(TxType.EXPENSE, after.type)
        assertEquals(650L, after.amountCents)
    }

    // ---------- 退款（真实样本：2026-10 实测） ----------

    @Test
    fun `alipay refund income with amount before keyword`() {
        val r = parse(NotificationRules.ALIPAY, "退款提醒", "你收到一笔14.90元退款，点此查看账单详情！")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1490L, r.amountCents)
        assertTrue(r.isRefund)
        assertTrue(r.ruleName.contains("退款"))
        // 退款与同额收款去重键不同
        assertEquals("com.eg.android.AlipayGphone|INCOME|R|1490|${postTime / 60_000}", r.dedupKey)
    }

    @Test
    fun `wechat grouped refund without amount is skipped`() {
        // "[2条]"折叠汇总通知没有金额，属预期不匹配（明细行捕获后若含金额仍可解析）
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "[2条]微信支付: 退款到账通知"))
    }

    @Test
    fun `refund wins over income keyword`() {
        // "退款到账"同时命中"到账"，必须按退款处理而不是普通收款
        val r = parse(NotificationRules.WECHAT, "微信支付", "退款到账通知：12.80元")!!
        assertEquals(TxType.INCOME, r.type)
        assertTrue(r.isRefund)
        assertEquals(1280L, r.amountCents)
    }

    // ---------- 边界 ----------

    @Test
    fun `uninterested package returns null`() {
        assertNull(parse("com.android.systemui", "无关", "收款10.00元"))
    }

    @Test
    fun `ambiguous direction returns null`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "收款10.00元同时有支出20.00元"))
    }

    @Test
    fun `no amount returns null`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "微信支付服务通知"))
    }

    @Test
    fun `blank body returns null`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "  "))
    }
}
