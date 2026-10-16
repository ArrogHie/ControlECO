package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 应用专属页面解析器测试（微信 / 支付宝 / 云闪付）。
 *
 * 样本来源：项目内捕获日志与开发日志记录的真实页面文本（节点顺序按控件树），
 * 版式推演的样本在用例里注明"推演"字样——真实样本与推演样本分开标注，
 * 便于后续拿到真机快照时逐个替换（覆盖状态见 docs/自动记账捕获机制.md 的支持矩阵）。
 */
class PaymentPageParserTest {

    private val capturedAt: Long = LocalDateTime.of(2026, 10, 6, 12, 35, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun snapshot(
        pkg: String,
        texts: List<String>,
        at: Long = capturedAt,
        activity: String = "",
        windowId: Int = 1,
        className: String = "",
    ): PageSnapshot = PageSnapshot(
        packageName = pkg,
        className = className,
        activity = activity,
        captureTime = at,
        windowId = windowId,
        nodes = texts.map { PageNode(text = it) },
    )

    private fun parse(pkg: String, texts: List<String>, flow: PageFlow = newFlow(pkg), at: Long = capturedAt): PageParseResult =
        PageParserRegistry.parse(snapshot(pkg, texts, at), flow)

    private fun newFlow(pkg: String): PageFlow = PageFlowTracker().flow(pkg)

    private fun candidate(pkg: String, texts: List<String>, flow: PageFlow = newFlow(pkg)) =
        (parse(pkg, texts, flow) as PageParseResult.Candidate).candidate

    // ---------- 微信 ----------

    @Test
    fun `微信支付成功页_标签分行提取商户与支付方式`() {
        val r = candidate(
            NotificationRules.WECHAT,
            listOf("支付成功", "¥12.80", "收款方", "老王奶茶店", "支付方式", "零钱", "完成"),
        )
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1280L, r.amountCents)
        assertEquals("老王奶茶店", r.counterparty)
        assertEquals("零钱", r.paymentMethod)
        assertEquals(PageScene.PAYMENT_SUCCESS, r.scene)
        assertEquals(TradeState.SUCCESS, r.tradeState)
        assertEquals("微信·支付成功页", r.ruleName)
        // 无交易时间 → 分钟桶用捕获时刻，与通知通道同构
        assertEquals("com.tencent.mm|EXPENSE|1280|${capturedAt / 60_000}", r.dedupKey)
    }

    @Test
    fun `微信支付成功页_金额在标题节点内且带千分位`() {
        val r = candidate(NotificationRules.WECHAT, listOf("支付成功￥1,280.00", "付款方式 零钱", "完成"))
        assertEquals(128_000L, r.amountCents)
        assertEquals("零钱", r.paymentMethod)
    }

    @Test
    fun `微信账单详情页_字段最全并带交易时间与单号`() {
        val flow = newFlow(NotificationRules.WECHAT)
        val r = candidate(
            NotificationRules.WECHAT,
            listOf(
                "账单详情", "¥128.00", "当前状态", "支付成功", "商户全称", "某某超市有限公司",
                "支付方式", "招商银行(1234)", "交易时间", "2026-10-06 12:34:56",
                "交易单号", "4200182202610061234567890",
            ),
            flow,
        )
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(128_00L, r.amountCents)
        assertEquals("某某超市有限公司", r.counterparty)
        assertEquals("招商银行(1234)", r.paymentMethod)
        assertEquals("招商银行", r.instrument?.brand)
        assertEquals("1234", r.instrument?.tail)
        val expected = LocalDateTime.of(2026, 10, 6, 12, 34, 56)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, r.txTime)
        assertEquals("4200182202610061234567890", r.orderId)
        assertEquals("微信·账单详情", r.ruleName)
        // 有交易时间 → 分钟桶对齐交易时间，与该笔支付通知天然去重
        assertEquals("com.tencent.mm|EXPENSE|12800|${expected / 60_000}", r.dedupKey)
    }

    @Test
    fun `微信收银台页_只记录流程上下文不生成候选`() {
        val flow = newFlow(NotificationRules.WECHAT)
        val r = parse(
            NotificationRules.WECHAT,
            listOf("¥25.00", "向商家付款", "付款方式", "零钱", "请输入支付密码"),
            flow,
        )
        assertTrue(r is PageParseResult.SceneOnly)
        assertEquals(PageScene.PAYMENT_CONFIRM, (r as PageParseResult.SceneOnly).scene)
        assertEquals(TradeState.PENDING, r.tradeState)
        assertEquals(2500L, flow.confirmAmountCents)
        assertEquals(capturedAt, flow.anchorTime)
    }

    @Test
    fun `微信收银台时刻作为成功页的时间锚点`() {
        val flow = newFlow(NotificationRules.WECHAT)
        // 先看到收银台（记录锚点），再看到结果页（无交易时间）
        parse(NotificationRules.WECHAT, listOf("¥25.00", "确认付款", "请输入支付密码"), flow)
        val r = candidate(
            NotificationRules.WECHAT,
            listOf("支付成功", "¥25.00", "收款方", "早餐店", "付款方式", "零钱", "完成"),
            flow,
        )
        assertEquals(capturedAt, r.txTime)
    }

    @Test
    fun `微信支付失败页与取消页_都不生成候选`() {
        val failed = parse(NotificationRules.WECHAT, listOf("支付失败", "¥25.00", "重新支付"))
        assertTrue(failed is PageParseResult.SceneOnly)
        assertEquals(TradeState.FAILED, (failed as PageParseResult.SceneOnly).tradeState)

        val cancelled = parse(NotificationRules.WECHAT, listOf("订单已取消", "¥25.00", "返回首页"))
        assertTrue(cancelled is PageParseResult.SceneOnly)
        assertEquals(TradeState.CANCELLED, (cancelled as PageParseResult.SceneOnly).tradeState)
    }

    @Test
    fun `微信零钱明细列表页_不是单笔结果页`() {
        val r = parse(
            NotificationRules.WECHAT,
            listOf("零钱明细", "筛选", "全部", "本月", "¥12.00", "¥25.00", "¥8.00"),
        )
        assertTrue(r is PageParseResult.SceneOnly)
        assertEquals(PageScene.LIST, (r as PageParseResult.SceneOnly).scene)
    }

    @Test
    fun `微信消息流里的支付成功预览_不生成候选`() {
        val r = parse(
            NotificationRules.WECHAT,
            listOf("最近消息", "微信支付", "支付成功 ¥21.00", "1小时前"),
        )
        assertFalse(r is PageParseResult.Candidate)
    }

    @Test
    fun `微信收款到账页_收入方向`() {
        val r = candidate(
            NotificationRules.WECHAT,
            listOf("微信转账", "已收钱", "¥50.00", "来自 张三", "已存入零钱"),
        )
        assertEquals(TxType.INCOME, r.type)
        assertEquals(5000L, r.amountCents)
        assertEquals("张三", r.counterparty)
        assertEquals(PageScene.RECEIVE_SUCCESS, r.scene)
        assertEquals("微信·收款到账页", r.ruleName)
    }

    @Test
    fun `微信转账支出页_对方已收钱是支出`() {
        val r = candidate(NotificationRules.WECHAT, listOf("转账成功", "¥88.00", "对方已收钱", "转账给 李四"))
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(8800L, r.amountCents)
    }

    @Test
    fun `微信退款到账页_收入方向并携带退款标记`() {
        val r = candidate(
            NotificationRules.WECHAT,
            listOf("退款成功", "¥19.90", "退款金额", "¥19.90", "商户全称", "某某便利店", "退款时间", "2026-10-06 12:34:00"),
        )
        assertEquals(TxType.INCOME, r.type)
        assertTrue(r.isRefund)
        assertEquals(1990L, r.amountCents)
        assertTrue(r.dedupKey.contains("|R|"))
        assertEquals(PageScene.REFUND_SUCCESS, r.scene)
    }

    @Test
    fun `微信账单详情待付款_不生成候选`() {
        val r = parse(
            NotificationRules.WECHAT,
            listOf("账单详情", "¥25.00", "当前状态", "待付款", "订单编号", "42001822"),
        )
        assertTrue(r is PageParseResult.SceneOnly)
        assertEquals(TradeState.PENDING, (r as PageParseResult.SceneOnly).tradeState)
    }

    @Test
    fun `同一成功页重复事件只生成一条候选`() {
        val flow = newFlow(NotificationRules.WECHAT)
        val texts = listOf("支付成功", "¥12.80", "收款方", "老王奶茶店", "付款方式", "零钱", "完成")
        assertTrue(parse(NotificationRules.WECHAT, texts, flow) is PageParseResult.Candidate)
        val second = parse(NotificationRules.WECHAT, texts, flow)
        assertTrue(second is PageParseResult.SceneOnly)
    }

    @Test
    fun `再次进入收银台开启新流程_同额第二笔仍能生成候选`() {
        val flow = newFlow(NotificationRules.WECHAT)
        val success = listOf("支付成功", "¥12.80", "收款方", "老王奶茶店", "付款方式", "零钱", "完成")
        assertNotNull(candidate(NotificationRules.WECHAT, success, flow))
        // 用户又走了一遍支付：收银台出现 → 流程重置
        parse(NotificationRules.WECHAT, listOf("¥12.80", "确认付款", "请输入支付密码"), flow)
        val second = parse(NotificationRules.WECHAT, success, flow)
        assertTrue("同额的第二笔必须能再次生成候选", second is PageParseResult.Candidate)
    }

    // ---------- 支付宝 ----------

    @Test
    fun `支付宝付款成功页_同行标签提取`() {
        val r = candidate(
            NotificationRules.ALIPAY,
            listOf("付款成功", "¥14.90", "收款方：某某百货", "付款方式：余额宝"),
        )
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1490L, r.amountCents)
        assertEquals("某某百货", r.counterparty)
        assertEquals("余额宝", r.paymentMethod)
        assertEquals("支付宝·付款成功页", r.ruleName)
    }

    @Test
    fun `支付宝账单详情页_实付优先于订单金额`() {
        val r = candidate(
            NotificationRules.ALIPAY,
            listOf(
                "账单详情", "¥19.38", "商户全称", "拼多多平台商户", "订单金额", "¥25.00",
                "优惠", "-¥5.62", "付款方式", "中国银行储蓄卡(8920)", "创建时间", "2026-10-06 12:30:00",
                "商户订单号", "2026100622001400001234567890",
            ),
        )
        assertEquals(1938L, r.amountCents)
        assertEquals("拼多多平台商户", r.counterparty)
        assertEquals("中国银行储蓄卡(8920)", r.paymentMethod)
        assertEquals("中国银行", r.instrument?.brand)
        assertEquals("8920", r.instrument?.tail)
    }

    @Test
    fun `支付宝退款页_收入且带退款标记`() {
        val r = candidate(NotificationRules.ALIPAY, listOf("退款成功", "¥14.90", "退款金额", "收款方", "某某百货"))
        assertEquals(TxType.INCOME, r.type)
        assertTrue(r.isRefund)
        assertEquals(1490L, r.amountCents)
    }

    @Test
    fun `支付宝确认付款页_不生成候选`() {
        val r = parse(NotificationRules.ALIPAY, listOf("确认付款", "¥14.90", "付款方式 余额宝", "立即付款"))
        assertTrue(r is PageParseResult.SceneOnly)
        assertEquals(PageScene.PAYMENT_CONFIRM, (r as PageParseResult.SceneOnly).scene)
    }

    // ---------- 云闪付 ----------

    @Test
    fun `云闪付支付成功页_提取卡信息`() {
        val r = candidate(
            NotificationRules.UNIONPAY,
            listOf("支付成功", "¥36.00", "付款方式", "工商银行储蓄卡(1234)", "订单时间", "2026-10-06 12:34:00"),
        )
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(3600L, r.amountCents)
        assertEquals("工商银行", r.instrument?.brand)
        assertEquals("1234", r.instrument?.tail)
        assertEquals("云闪付·支付成功页", r.ruleName)
    }

    @Test
    fun `云闪付交易详情页_按卡号只取尾号`() {
        val r = candidate(
            NotificationRules.UNIONPAY,
            listOf("云闪付交易详情", "交易金额", "¥58.00", "商户名称", "某某餐厅", "卡号", "尾号6789", "交易时间", "2026-10-06 12:34:00"),
        )
        assertEquals(5800L, r.amountCents)
        assertEquals("6789", r.instrument?.tail)
        assertEquals("某某餐厅", r.counterparty)
    }

    @Test
    fun `云闪付转账页_方向按收付判定`() {
        val r = candidate(NotificationRules.UNIONPAY, listOf("转账成功", "转账金额", "¥200.00", "收款方", "王五", "完成"))
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(20000L, r.amountCents)
    }

    // ---------- 通用约束 ----------

    @Test
    fun `无金额页面不生成候选`() {
        val r = parse(NotificationRules.WECHAT, listOf("支付成功", "收款方", "老王奶茶店", "完成"))
        assertTrue(r is PageParseResult.SceneOnly)
    }

    @Test
    fun `零元实付页不生成候选`() {
        val r = parse(NotificationRules.ALIPAY, listOf("付款成功", "¥0.00", "订单金额", "¥0.00", "付款方式", "余额宝"))
        assertTrue(r is PageParseResult.SceneOnly)
    }

    @Test
    fun `金额为负或异常的节点不会被当成实付`() {
        val r = candidate(
            NotificationRules.WECHAT,
            listOf("支付成功", "支付遇财神 -￥0.01", "¥12.80", "收款方", "老王奶茶店", "付款方式", "零钱"),
        )
        assertEquals(1280L, r.amountCents)
    }

    @Test
    fun `包名没有专属解析器时走兜底_不在目录内则不处理`() {
        // 目录内的电商应用走兜底解析器
        val generic = candidate(
            "com.jingdong.app.mall",
            listOf("支付成功", "¥59.00", "付款方式", "京东支付", "支付时间", "2026-10-06 12:34:00", "订单编号", "12345678"),
        )
        assertEquals(5900L, generic.amountCents)

        // 目录外且非已知来源的包名：解析器不处理（采集层也不会为其留样本）
        val unknown = parse("com.example.unknown", listOf("支付成功", "¥59.00", "付款方式", "零钱"))
        assertEquals(PageParseResult.NotATradePage, unknown)
        assertNull(PageParserRegistry.parserFor("com.example.unknown"))
    }
}
