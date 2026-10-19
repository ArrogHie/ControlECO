package com.controleco.app.capture.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器注册表与稳定性/边界测试。
 *
 * 关注三件事：
 * 1. **分派正确**：包名 → 专属解析器；目录内无专属解析器的包名 → 兜底；未知包名 → 不处理；
 * 2. **异常隔离**：任何解析器出问题都不得让整条页面采集链路失败（注册表降级为"与交易无关"）；
 * 3. **边界输入不崩**：超大页面/异常文本/只有金额的页面/超深嵌套/重复节点都只返回结果，不抛异常。
 */
class PageParserRegistryTest {

    private fun snapshot(pkg: String, texts: List<String>, windowId: Int = 1): PageSnapshot =
        PageSnapshot(
            packageName = pkg,
            captureTime = 1_800_000_000_000L,
            windowId = windowId,
            nodes = texts.map { PageNode(text = it) },
        )

    private fun parse(pkg: String, texts: List<String>): PageParseResult =
        PageParserRegistry.parse(snapshot(pkg, texts), PageFlowTracker().flow(pkg))

    // ---------- 分派 ----------

    @Test
    fun `支付与电商包名分派到各自专属解析器`() {
        assertEquals(WechatPageParser, PageParserRegistry.parserFor("com.tencent.mm"))
        assertEquals(AlipayPageParser, PageParserRegistry.parserFor("com.eg.android.AlipayGphone"))
        assertEquals(UnionPayPageParser, PageParserRegistry.parserFor("com.unionpay"))
        assertEquals(JdPageParser, PageParserRegistry.parserFor("com.jingdong.app.mall"))
        assertEquals(JdPageParser, PageParserRegistry.parserFor("com.jd.jrapp"))
        assertEquals(TaobaoPageParser, PageParserRegistry.parserFor("com.tmall.wireless"))
        assertEquals(DouyinPageParser, PageParserRegistry.parserFor("com.ss.android.ugc.aweme"))
        assertEquals(MeituanPageParser, PageParserRegistry.parserFor("com.sankuai.meituan.takeoutnew"))
        assertEquals(ElemePageParser, PageParserRegistry.parserFor("me.ele"))
    }

    @Test
    fun `拼多多不再解析_真实误记账样本不会生成候选`() {
        // 2026-10-19 真机：拼多多「补贴领取」弹窗被 PinduoduoPageParser 当成支付结果页自动入账 ¥26.80。
        // 决定移除该应用的解析链路，这里锁住"不会再解析"。
        assertNull(PageParserRegistry.parserFor("com.xunmeng.pinduoduo"))
        assertFalse(PageParserRegistry.hasDedicatedParser("com.xunmeng.pinduoduo"))
        assertFalse(PageParserRegistry.fallbackAccepts("com.xunmeng.pinduoduo"))
        val popup = listOf(
            "拼多多", "关闭弹窗", "ab0b819e-ca7b-40f3-903d-2009635661e2.png.slim",
            "本单已省14.99元，拼多多累计为你省7367.23元", "拼单成功", "本单获得福建地区补贴",
            "26.80元", "一键领取", "即将失效", "查看订单详情",
        )
        assertEquals(
            PageParseResult.NotATradePage,
            PageParserRegistry.parse(snapshot("com.xunmeng.pinduoduo", popup), PageFlowTracker().flow("com.xunmeng.pinduoduo")),
        )
    }

    @Test
    fun `目录内其余应用走兜底解析器_未知包名不处理`() {
        // 银行 App 等目录内应用：兜底解析器接住（判据更严，置信度标注为中等）
        assertEquals(GenericPageParser, PageParserRegistry.parserFor("cmb.pb"))
        assertTrue(PageParserRegistry.fallbackAccepts("cmb.pb"))
        // 目录外且非已知来源：不处理（采集层也不会为它留样本）
        assertNull(PageParserRegistry.parserFor("com.example.notes"))
    }

    @Test
    fun `专属解析器覆盖集是可见的_不得把注册包名当成已支持`() {
        val dedicated = PageParserRegistry.dedicatedPackages()
        assertTrue(dedicated.contains("com.tencent.mm"))
        assertTrue(dedicated.contains("me.ele"))
        // 兜底覆盖的包名不进入"专属"集合（文档矩阵据此区分"已适配"与"仅兜底"）
        assertTrue(!dedicated.contains("cmb.pb"))
        assertTrue(!PageParserRegistry.hasDedicatedParser("cmb.pb"))
    }

    // ---------- 异常隔离 ----------

    @Test
    fun `解析器异常被隔离为与交易无关_不向上传播`() {
        val result = SafeParseResult.of("com.tencent.mm", "微信", IllegalStateException("boom"))
        assertTrue(result is PageParseResult.SceneOnly)
        assertEquals("解析器异常已隔离", (result as PageParseResult.SceneOnly).reason)
    }

    // ---------- 边界输入 ----------

    @Test
    fun `空快照返回与交易无关`() {
        assertEquals(PageParseResult.NotATradePage, parse("com.tencent.mm", emptyList()))
        assertEquals(PageParseResult.NotATradePage, parse("me.ele", listOf("", "   ", "\n")))
    }

    @Test
    fun `超大页面与异常文本不抛异常`() {
        val huge = buildList {
            add("支付成功")
            add("¥1234.56")
            repeat(500) { add("推荐商品 $it 限时优惠") }
            add("支付方式 零钱")
        }
        val result = PageParserRegistry.parse(snapshot("com.tencent.mm", huge), PageFlowTracker().flow("com.tencent.mm"))
        assertNotNull(result)

        val weird = listOf(
            "支付成功", "¥" + "9".repeat(400), "\u0000\u0001", "  ", "支付方式", "零钱",
            "商户全称", "🙂🙂🙂", "交易时间", "2099-13-45 99:99:99",
        )
        for (pkg in PageParserRegistry.dedicatedPackages()) {
            val r = PageParserRegistry.parse(snapshot(pkg, weird), PageFlowTracker().flow(pkg))
            assertNotNull("包名 $pkg 的解析器不得对异常文本抛异常", r)
        }
    }

    @Test
    fun `只有金额没有场景词的页面不生成候选`() {
        val result = parse("com.controleco.app", listOf("¥12.80", "¥25.00", "完成"))
        assertEquals(PageParseResult.NotATradePage, result)
    }

    @Test
    fun `深层嵌套与重复节点不抛异常`() {
        val nodes = ArrayList<PageNode>()
        var parent = -1
        repeat(40) { depth ->
            val index = nodes.size
            nodes.add(PageNode(text = if (depth == 39) "支付成功 ¥8.00 付款方式 零钱" else "", depth = depth, parentIndex = parent))
            parent = index
        }
        repeat(30) { nodes.add(PageNode(text = "支付成功", depth = 1, parentIndex = 0)) }
        val result = PageParserRegistry.parse(
            PageSnapshot(packageName = "com.tencent.mm", captureTime = 1L, nodes = nodes),
            PageFlowTracker().flow("com.tencent.mm"),
        )
        assertNotNull(result)
    }

    @Test
    fun `流程上下文按包名隔离且可超时回收`() {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000L)
        val tracker = PageFlowTracker(ttlMs = 60_000L, clock = clock::get)
        val wechat = tracker.flow("com.tencent.mm")
        wechat.noteConfirmPage(1280, null, null, clock.get())
        val alipay = tracker.flow("com.eg.android.AlipayGphone")
        assertNull("另一应用的流程上下文必须为空（上一笔不污染下一笔）", alipay.confirmAmountCents)
        assertEquals(1280L, wechat.confirmAmountCents)
        // 超过存活时长：条目被回收，重建的流程不带上一笔的字段
        clock.addAndGet(61_000L)
        tracker.evictExpired()
        assertNull(tracker.peek("com.tencent.mm"))
        val fresh = tracker.flow("com.tencent.mm")
        assertNull(fresh.confirmAmountCents)
    }
}
