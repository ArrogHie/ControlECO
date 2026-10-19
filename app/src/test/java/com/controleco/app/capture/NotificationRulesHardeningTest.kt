package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知解析加固测试（2026-10-13 自测发现的问题逐条加固，
 * 见 docs/自动记账捕获机制.md）。
 * 每个用例都对应一类**真实会错账**的输入，改动规则时这些断言就是护栏。
 */
class NotificationRulesHardeningTest {

    private val postTime = 1_700_000_000_000L

    private fun parse(pkg: String, title: String, text: String, bigText: String = "") =
        NotificationRules.parse(pkg, title, text, bigText, postTime)

    // ---------- 千分位金额：修复"¥6,064.76 被抓成 ¥6.00"的静默错账 ----------

    @Test
    fun `thousand separator in currency amount`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "已支付¥6,064.76")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(606_476L, r.amountCents)
    }

    @Test
    fun `thousand separator before yuan keyword`() {
        val r = parse(NotificationRules.UNIONPAY, "云闪付", "您尾号1234的账户消费1,234.56元")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(123_456L, r.amountCents)
    }

    @Test
    fun `thousand separator in income`() {
        val r = parse(NotificationRules.ALIPAY, "支付宝通知", "支付宝成功收款1,000.00元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(100_000L, r.amountCents)
    }

    @Test
    fun `amount above sanity cap is rejected instead of recorded`() {
        // 订单号/手机号被当金额时宁可漏记也不记错账
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "已支付¥9,999,999,999.99"))
    }

    // ---------- 卡尾号：不应被当成金额 ----------

    @Test
    fun `card tail is not parsed as amount`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "微信支付收款尾号1234"))
    }

    @Test
    fun `card tail in parentheses is masked but bank name kept`() {
        val masked = NotificationRules.maskCardTails("【中国银行储蓄卡(8920)】已支付¥12.80")
        assertFalse(masked.contains("8920"))
        // 括号外的银行名保留下来供支付方式识别
        assertTrue(masked.contains("中国银行储蓄卡"))
    }

    @Test
    fun `tail word masking`() {
        assertEquals("尾号****", NotificationRules.maskCardTails("尾号7619"))
        assertEquals("储蓄卡(****)", NotificationRules.maskCardTails("储蓄卡(8920)"))
    }

    // ---------- 文本归一化 ----------

    @Test
    fun `full width digits are normalized`() {
        assertEquals("已支付¥12.80", NotificationRules.normalize("已支付￥１２.８０"))
    }

    @Test
    fun `full width digits can be parsed end to end`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "已支付￥１２.８０")!!
        assertEquals(1280L, r.amountCents)
    }

    @Test
    fun `bracketed count prefix is stripped`() {
        assertEquals("微信支付: 收款12.00元", NotificationRules.normalize("[3条]微信支付: 收款12.00元"))
    }

    @Test
    fun `bare count after app name is stripped`() {
        assertEquals("微信支付 已支付¥12.80", NotificationRules.normalize("微信支付 2条 已支付¥12.80"))
    }

    @Test
    fun `parsing works after count prefix normalization`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "[3条]微信支付: 收款12.00元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1200L, r.amountCents)
    }

    // ---------- 噪声过滤 ----------

    @Test
    fun `coupon marketing is filtered`() {
        // 含"1元消费券"会被金额正则抓成 ¥1.00 支出，必须靠噪声词拦掉
        assertNull(parse(NotificationRules.ALIPAY, "支付宝", "您有一笔1元消费券待领取，点击领取"))
    }

    @Test
    fun `credit limit notice is filtered`() {
        assertNull(parse(NotificationRules.UNIONPAY, "云闪付", "您尾号1234信用卡可用额度提升至50000元"))
    }

    @Test
    fun `verification code is filtered`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "验证码123456，请勿泄露"))
    }

    @Test
    fun `real payment with coupon tail is still recorded`() {
        // 有交易确认短语就放行：真实通知常带"使用优惠券省 3 元"的尾巴，不能连交易一起丢
        val r = parse(NotificationRules.WECHAT, "微信支付", "已支付¥12.80，使用优惠券省3元")!!
        assertEquals(1280L, r.amountCents)
        assertFalse(NotificationRules.isNoise("已支付¥12.80，使用优惠券省3元"))
    }

    // ---------- 去重键构造 ----------

    @Test
    fun `dedup key format is stable across channels`() {
        assertEquals(
            "com.tencent.mm|EXPENSE|1280|${postTime / 60_000}",
            NotificationRules.dedupKey(NotificationRules.WECHAT, TxType.EXPENSE, false, 1280, postTime),
        )
        assertEquals(
            "com.tencent.mm|INCOME|R|1280|${postTime / 60_000}",
            NotificationRules.dedupKey(NotificationRules.WECHAT, TxType.INCOME, true, 1280, postTime),
        )
    }

    @Test
    fun `dedup key can be rebuilt with a new amount`() {
        // 金额待补录回填：只替换金额段，来源/方向/退款段与分钟桶保持不变
        val key = NotificationRules.dedupKey(NotificationRules.WECHAT, TxType.EXPENSE, false, 0L, postTime)
        assertEquals(
            "com.tencent.mm|EXPENSE|1280|${postTime / 60_000}",
            dedupKeyWithAmount(key, 1280),
        )
        val refundKey = NotificationRules.dedupKey(NotificationRules.ALIPAY, TxType.INCOME, true, 0L, postTime)
        assertEquals(
            "com.eg.android.AlipayGphone|INCOME|R|990|${postTime / 60_000}",
            dedupKeyWithAmount(refundKey, 990),
        )
        // 残缺键不重算
        assertNull(dedupKeyWithAmount("com.tencent.mm|EXPENSE", 100))
    }

    // ---------- 电商/外卖收银台（比支付三包多一道确认短语硬闸门） ----------

    @Test
    fun `checkout payment success is captured`() {
        val r = parse("com.jingdong.app.mall", "京东", "您的订单支付成功，已支付¥59.00")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(5900L, r.amountCents)
        assertEquals("京东", r.sourceLabel)
    }

    @Test
    fun `checkout pending payment is not captured`() {
        // 未完成状态绝不能记账
        assertNull(parse("com.jingdong.app.mall", "京东", "您有一笔订单待支付¥59.00，请尽快完成付款"))
        assertNull(parse("com.taobao.taobao", "淘宝", "订单已提交，待付款 128.00元"))
    }

    @Test
    fun `checkout delivery notice is not captured`() {
        assertNull(parse("com.jingdong.app.mall", "京东", "您的包裹已发货，预计明天送达"))
    }

    @Test
    fun `checkout marketing is not captured`() {
        assertNull(parse("com.jingdong.app.mall", "京东", "限时优惠券已到账，点击领取 20元券"))
    }

    @Test
    fun `checkout packages are marked interested`() {
        assertTrue(NotificationRules.isInterested("com.jingdong.app.mall"))
        assertTrue(NotificationRules.isInterested(NotificationRules.WECHAT))
        assertFalse(NotificationRules.isInterested("com.example.unknown"))
    }

    // ---------- 方向可辨但金额缺失 → 通知栏补录 ----------

    @Test
    fun `direction only expense is detected`() {
        val hint = NotificationRules.parseDirectionOnly(NotificationRules.WECHAT, "微信支付", "已支付")!!
        assertEquals(TxType.EXPENSE, hint.type)
        assertEquals("微信", hint.sourceLabel)
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "已支付"))
    }

    @Test
    fun `direction only income is detected`() {
        val hint = NotificationRules.parseDirectionOnly(NotificationRules.UNIONPAY, "云闪付", "您的账户已到账")!!
        assertEquals(TxType.INCOME, hint.type)
    }

    @Test
    fun `generic service notice is not treated as a transaction`() {
        // 没有交易确认短语就不该打扰用户
        assertNull(NotificationRules.parseDirectionOnly(NotificationRules.WECHAT, "微信支付", "微信支付服务通知"))
        assertNull(NotificationRules.parseDirectionOnly(NotificationRules.ALIPAY, "支付宝", "您的账单已出，请及时还款"))
    }

    @Test
    fun `refund does not go to amount reply`() {
        // 退款走既有的"退款关联"流程（金额常由原账单承载）
        assertNull(NotificationRules.parseDirectionOnly(NotificationRules.WECHAT, "微信支付", "退款到账通知"))
    }

    @Test
    fun `grouped summary does not go to amount reply`() {
        assertNull(NotificationRules.parseDirectionOnly(NotificationRules.WECHAT, "微信支付", "[2条]微信支付: 已支付"))
    }

    @Test
    fun `source label falls back to rule name for unknown packages`() {
        // 真机验证发现：未收录短名单的银行 App 推送只能显示包名（对用户无意义）→ 退回规则名前缀
        assertEquals(
            "工商银行通知",
            NotificationRules.sourceLabelOrRule("com.some.unknown.bank", "工商银行通知·支出"),
        )
        // 已知来源仍用品牌名
        assertEquals("微信", NotificationRules.sourceLabelOrRule(NotificationRules.WECHAT, "微信·支出/消费"))
        assertEquals(
            "工商银行通知",
            NotificationRules.sourceLabelOrRule("com.icbc", "工商银行通知·支出"),
        )
        assertEquals(
            "工商银行短信",
            NotificationRules.sourceLabelOrRule("SMS:工商银行", "工商银行短信·支出"),
        )
        // 规则名为空时最差退回包名（不抛异常）
        assertEquals("com.unknown.pkg", NotificationRules.sourceLabelOrRule("com.unknown.pkg", ""))
    }

    @Test
    fun `grouped summary detection helpers`() {
        assertTrue(NotificationRules.looksLikeGroupedSummary("[2条]微信支付: 退款到账通知"))
        assertTrue(NotificationRules.looksLikeGroupedSummary("微信支付 共3条"))
        assertFalse(NotificationRules.looksLikeGroupedSummary("已支付¥12.80"))
        // 账务明细判定：有方向动词 + 可解析金额
        assertTrue(NotificationRules.looksLikeLedgerDetail("已支付¥12.80"))
        assertFalse(NotificationRules.looksLikeLedgerDetail("微信支付服务通知"))
    }
}
