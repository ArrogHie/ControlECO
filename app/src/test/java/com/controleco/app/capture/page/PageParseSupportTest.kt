package com.controleco.app.capture.page

import com.controleco.app.capture.page.PageParseSupport.AmountKind
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面解析公共原语的行为约束：金额节点判定、金额上下文分类、实付金额优先级链、
 * "标签→值"提取、商户兜底、交易时间/单号解析，以及页面形状闸门。
 */
class PageParseSupportTest {

    private fun snapshot(vararg texts: String): PageSnapshot = PageSnapshot(
        packageName = "com.controleco.app",
        nodes = texts.map { PageNode(text = it, parentIndex = -1) },
    )

    // ---------- 金额节点判定 ----------

    @Test
    fun `isAmountLikeNode 认可带符号与尾随元的纯金额`() {
        for (text in listOf("12.80", "¥12.80", "￥ 20.06", "1,234.56", "12元", "-¥0.01")) {
            assertTrue("应判定为金额节点：$text", PageParseSupport.isAmountLikeNode(text))
        }
    }

    @Test
    fun `isAmountLikeNode 拒绝非金额文本`() {
        for (text in listOf("12.8.9", "完成", "12.80元起")) {
            assertFalse("不应判定为金额节点：$text", PageParseSupport.isAmountLikeNode(text))
        }
    }

    // ---------- 金额上下文分类 ----------

    @Test
    fun `classifyAmount 识别实付与订单金额`() {
        assertEquals(AmountKind.PAID, PageParseSupport.classifyAmount("实付款 ¥19.38"))
        assertEquals(AmountKind.PAID, PageParseSupport.classifyAmount("实付 ¥19.38"))
        assertEquals(AmountKind.PAID, PageParseSupport.classifyAmount("支付金额 ¥19.38"))
        assertEquals(AmountKind.ORDER_TOTAL, PageParseSupport.classifyAmount("订单金额 ¥25.00"))
        assertEquals(AmountKind.ORDER_TOTAL, PageParseSupport.classifyAmount("原价 ¥25.00"))
    }

    @Test
    fun `classifyAmount 识别优惠_余额_运费_退款`() {
        assertEquals(AmountKind.DISCOUNT, PageParseSupport.classifyAmount("优惠 ¥5.00"))
        assertEquals(AmountKind.DISCOUNT, PageParseSupport.classifyAmount("立减 ¥1.00"))
        assertEquals(AmountKind.DISCOUNT, PageParseSupport.classifyAmount("红包 ¥2.00"))
        assertEquals(AmountKind.DISCOUNT, PageParseSupport.classifyAmount("已减 ¥0.01"))
        assertEquals(AmountKind.DISCOUNT, PageParseSupport.classifyAmount("-¥0.01"))
        assertEquals(AmountKind.BALANCE, PageParseSupport.classifyAmount("余额 ¥1,234.56"))
        assertEquals(AmountKind.BALANCE, PageParseSupport.classifyAmount("可用 ¥1,234.56"))
        assertEquals(AmountKind.FEE, PageParseSupport.classifyAmount("运费 ¥6.00"))
        assertEquals(AmountKind.FEE, PageParseSupport.classifyAmount("服务费 ¥1.00"))
        assertEquals(AmountKind.REFUND, PageParseSupport.classifyAmount("退款 ¥19.38"))
    }

    @Test
    fun `classifyAmount 负向上下文优先于金额类弱标签`() {
        // 同一行里"余额"先于普通金额语义被识别（账户余额不是这笔的实付）
        assertEquals(AmountKind.BALANCE, PageParseSupport.classifyAmount("账户余额¥1,234.56"))
        // 行内出现明确的实付词时，优惠词不再把整行降级为优惠
        assertEquals(AmountKind.PAID, PageParseSupport.classifyAmount("实付¥12.00 优惠¥5.00"))
        assertEquals(AmountKind.PAID, PageParseSupport.classifyAmount("已支付 优惠¥1.00"))
    }

    @Test
    fun `classifyAmount 没有上下文词的金额记为未知`() {
        assertEquals(AmountKind.UNKNOWN, PageParseSupport.classifyAmount("¥12.00"))
        assertEquals(AmountKind.UNKNOWN, PageParseSupport.classifyAmount("支付成功¥19.38"))
    }

    // ---------- 实付金额优先级链 ----------

    @Test
    fun `pickPaidAmount 锚点行内的金额优先于全页扫描`() {
        val s = snapshot("支付成功¥19.38", "订单金额 ¥25.00")
        assertEquals("19.38", PageParseSupport.pickPaidAmount(s, anchorIndex = 0))
        // 不给锚点时按优先级链兜底：没有实付上下文 → 落到订单金额
        assertEquals("25.00", PageParseSupport.pickPaidAmount(s))
    }

    @Test
    fun `pickPaidAmount 同页有原价与实付时取实付`() {
        val s = snapshot("订单金额 ¥25.00", "实付款 ¥19.38")
        assertEquals("19.38", PageParseSupport.pickPaidAmount(s))
    }

    @Test
    fun `pickPaidAmount 只有订单金额时取订单金额`() {
        assertEquals("25.00", PageParseSupport.pickPaidAmount(snapshot("订单金额 ¥25.00")))
    }

    @Test
    fun `pickPaidAmount 只有优惠的页面没有可记金额`() {
        assertNull(PageParseSupport.pickPaidAmount(snapshot("优惠 ¥5.00", "红包 ¥2.00")))
    }

    // ---------- 锚点行内的金额 ----------

    @Test
    fun `amountInAnchor 跳过优惠项取实付金额`() {
        assertEquals("19.38", PageParseSupport.amountInAnchor("实付¥19.38"))
        assertEquals("19.38", PageParseSupport.amountInAnchor("支付成功 ¥19.38"))
        // 前面的"减"字金额是优惠项，取后面那个
        assertEquals("19.38", PageParseSupport.amountInAnchor("立减￥1 实付￥19.38"))
    }

    @Test
    fun `amountInAnchor 只有负号优惠项时返回空`() {
        assertNull(PageParseSupport.amountInAnchor("-¥0.01"))
        assertNull(PageParseSupport.amountInAnchor("已减-¥0.01"))
        assertNull(PageParseSupport.amountInAnchor("支付成功"))
    }

    // ---------- 标签→值 ----------

    private val methodLabels = PageParseSupport.paymentMethodLabels

    @Test
    fun `valueAfterLabel 取冒号或空格分隔的同行值`() {
        assertEquals("零钱", PageParseSupport.valueAfterLabel(listOf("支付方式：零钱"), methodLabels))
        assertEquals("零钱", PageParseSupport.valueAfterLabel(listOf("支付方式 零钱"), methodLabels))
        assertEquals("招商银行(1234)", PageParseSupport.valueAfterLabel(listOf("支付方式：招商银行(1234)"), methodLabels))
    }

    @Test
    fun `valueAfterLabel 标签嵌在长句里没有分隔符时不取值`() {
        assertNull(PageParseSupport.valueAfterLabel(listOf("点击给商家对账"), PageParseSupport.counterpartyLabels))
        assertNull(PageParseSupport.valueAfterLabel(listOf("如需修改请点击支付方式按钮"), methodLabels))
    }

    @Test
    fun `valueAfterLabel 独占一行的标签取下一条可用行`() {
        assertEquals("零钱", PageParseSupport.valueAfterLabel(listOf("支付方式", "零钱"), methodLabels))
        // 下一条是金额/纯数字时跳过，继续往后找
        assertEquals("零钱", PageParseSupport.valueAfterLabel(listOf("支付方式", "¥12.00", "零钱"), methodLabels))
    }

    @Test
    fun `valueAfterLabel 拒绝金额与纯数字值`() {
        assertNull(PageParseSupport.valueAfterLabel(listOf("支付方式：¥12.80"), methodLabels))
        assertNull(PageParseSupport.valueAfterLabel(listOf("支付方式：1234"), methodLabels))
        assertNull(PageParseSupport.valueAfterLabel(listOf("支付方式", "12.80"), methodLabels))
    }

    // ---------- 商户兜底 ----------

    @Test
    fun `guessCounterparty 跳过金额取标题后的第一个名称`() {
        val lines = listOf("支付成功", "¥12.90", "老王奶茶店", "支付方式", "零钱")
        assertEquals("老王奶茶店", PageParseSupport.guessCounterparty(lines, listOf("支付成功")))
        // 不带货币符号的金额节点同样跳过
        assertEquals(
            "老王奶茶店",
            PageParseSupport.guessCounterparty(listOf("支付成功", "12.90", "老王奶茶店"), listOf("支付成功")),
        )
    }

    @Test
    fun `guessCounterparty 不把状态词与导航词当商户`() {
        assertNull(PageParseSupport.guessCounterparty(listOf("支付成功", "完成", "首页", "客服"), listOf("支付成功")))
        assertNull(PageParseSupport.guessCounterparty(listOf("支付成功", "已收钱", "查看订单"), listOf("支付成功")))
        // 找不到锚点直接返回空
        assertNull(PageParseSupport.guessCounterparty(listOf("老王奶茶店"), listOf("支付成功")))
    }

    @Test
    fun `looksLikeMerchant 排除金额与状态短语`() {
        assertFalse(PageParseSupport.looksLikeMerchant("¥12.90"))
        assertFalse(PageParseSupport.looksLikeMerchant("12.90"))
        assertFalse(PageParseSupport.looksLikeMerchant("支付成功"))
        assertFalse(PageParseSupport.looksLikeMerchant("对方已收钱"))
        assertFalse(PageParseSupport.looksLikeMerchant("完成"))
        assertTrue(PageParseSupport.looksLikeMerchant("老王奶茶店"))
    }

    // ---------- 时间 ----------

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `timeFromLine 解析三种日期写法`() {
        assertEquals(
            epochMillis(2026, 10, 6, 12, 34, 56),
            PageParseSupport.timeFromLine("2026-10-06 12:34:56")!!,
        )
        assertEquals(
            epochMillis(2026, 10, 6, 12, 34),
            PageParseSupport.timeFromLine("2026.10.6 12:34")!!,
        )
        assertEquals(
            epochMillis(2026, 10, 6, 12, 34),
            PageParseSupport.timeFromLine("2026年10月6日 12:34")!!,
        )
        // 标签行里夹在文字中间的时间同样能解析
        assertEquals(
            epochMillis(2026, 10, 6, 12, 34),
            PageParseSupport.timeFromLine("交易时间 2026-10-06 12:34")!!,
        )
    }

    @Test
    fun `timeFromLine 无日期文本返回空`() {
        assertNull(PageParseSupport.timeFromLine("支付成功"))
        assertNull(PageParseSupport.timeFromLine("2026-10-06"))
        assertNull(PageParseSupport.timeFromLine("12:34"))
    }

    // ---------- 单号 ----------

    @Test
    fun `orderIdFrom 取单号标签后面的字母数字组合`() {
        assertEquals("2026100612345678", PageParseSupport.orderIdFrom(listOf("交易单号：2026100612345678")))
        assertEquals("2026100699887766", PageParseSupport.orderIdFrom(listOf("订单编号", "2026100699887766")))
        assertEquals("ABCDEF123456", PageParseSupport.orderIdFrom(listOf("订单编号 ABCDEF123456")))
    }

    @Test
    fun `orderIdFrom 没有单号时返回空`() {
        assertNull(PageParseSupport.orderIdFrom(listOf("支付成功", "¥12.90", "老王奶茶店")))
        // 不足 6 位的数字不算单号
        assertNull(PageParseSupport.orderIdFrom(listOf("交易单号：12345")))
    }

    // ---------- 页面形状闸门 ----------

    @Test
    fun `nonResultPageReason 拒绝消息流页面`() {
        val s = snapshot("最近消息", "支付成功", "¥19.38")
        assertNotNull(PageParseSupport.nonResultPageReason(s, 1))
    }

    @Test
    fun `nonResultPageReason 拒绝应用主界面入口宫格`() {
        val s = snapshot("扫一扫", "收付款", "卡包", "出行")
        assertNotNull(PageParseSupport.nonResultPageReason(s, -1))
    }

    @Test
    fun `nonResultPageReason 正常结果页不拒绝`() {
        val s = snapshot("支付成功", "¥19.38", "老王奶茶店", "支付方式", "零钱")
        assertNull(PageParseSupport.nonResultPageReason(s, 0))
    }

    @Test
    fun `isStatusTitleNode 只认独立标题不认进度长句`() {
        assertTrue(PageParseSupport.isStatusTitleNode("支付成功¥19.38", listOf("支付成功")))
        assertTrue(PageParseSupport.isStatusTitleNode("支付成功。", listOf("支付成功")))
        assertFalse(PageParseSupport.isStatusTitleNode("提交订单 支付成功 商家接单", listOf("支付成功")))
    }

    @Test
    fun `looksLikeOrderPendingPage 识别未完成的订单`() {
        assertTrue(
            PageParseSupport.looksLikeOrderPendingPage(
                snapshot("订单详情", "待付款", "取消订单", "支付剩余时间"),
            ),
        )
        assertTrue(PageParseSupport.looksLikeOrderPendingPage(snapshot("去支付", "订单金额 ¥25.00")))
        assertFalse(
            PageParseSupport.looksLikeOrderPendingPage(snapshot("支付成功", "¥19.38", "老王奶茶店")),
        )
    }
}
