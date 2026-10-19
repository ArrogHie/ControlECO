package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 页面候选的"可记账性"边界：交易时刻的判定、旧账单闸门、时间锚点。
 *
 * 这些用例守住的是"时间"这条线索：页面上的交易时刻既用于去重键，也用于判断用户是不是在翻旧账。
 */
class PageFreshnessTest {

    private val now: Long = LocalDateTime.of(2026, 10, 6, 12, 35, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun parse(pkg: String, texts: List<String>, at: Long = now): PageParseResult =
        PageParserRegistry.parse(
            PageSnapshot(
                packageName = pkg,
                captureTime = at,
                windowId = 1,
                nodes = texts.map { PageNode(text = it) },
            ),
            PageFlowTracker().flow(pkg),
        )

    @Test
    fun `当天早些时候的账单详情页正常记账_取页面的交易时间`() {
        val texts = listOf(
            "账单详情", "¥128.00", "当前状态", "支付成功", "商户全称", "某某超市",
            "支付方式", "招商银行(1234)", "交易时间", "2026-10-06 09:12:00", "交易单号", "4200188",
        )
        val result = parse(NotificationRules.WECHAT, texts)
        assertTrue(result is PageParseResult.Candidate)
        val expected = LocalDateTime.of(2026, 10, 6, 9, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, (result as PageParseResult.Candidate).candidate.txTime)
        assertEquals("com.tencent.mm|EXPENSE|12800|${expected / 60_000}", result.candidate.dedupKey)
    }

    @Test
    fun `超过一天的旧账单详情页不生成候选_各应用各自版式一致`() {
        // 每应用一份该应用真实版式的详情页样本（字段标签不同但都是"旧账"）
        val samples = mapOf(
            NotificationRules.WECHAT to listOf(
                "账单详情", "¥128.00", "当前状态", "支付成功", "商户全称", "某某超市",
                "支付方式", "招商银行(1234)", "交易时间", "2026-10-04 09:12:00", "交易单号", "4200188",
            ),
            NotificationRules.ALIPAY to listOf(
                "账单详情", "¥128.00", "创建时间", "2026-10-04 09:12:00", "付款方式", "余额宝",
                "商品说明", "某某百货", "商户订单号", "2026100422001400001",
            ),
            // 同一应用的另一版式（与微信同形的字段组合）
            "com.eg.android.AlipayGphone#b" to listOf(
                "账单详情", "¥128.00", "当前状态", "支付成功", "商户全称", "某某超市",
                "支付方式", "中国银行储蓄卡(8920)", "交易时间", "2026-10-04 09:12:00", "交易单号", "4200188",
            ),
            NotificationRules.UNIONPAY to listOf(
                "云闪付交易详情", "交易金额", "¥128.00", "商户名称", "某某超市",
                "卡号", "尾号6789", "交易时间", "2026-10-04 09:12:00", "订单编号", "UP202610040012",
            ),
            "com.jingdong.app.mall" to listOf(
                "订单详情", "实付款", "¥59.00", "付款方式", "京东支付", "商家名称", "某某旗舰店",
                "下单时间", "2026-10-04 10:00:00", "订单编号", "12345678",
            ),
        )
        for ((key, old) in samples) {
            val pkg = key.substringBefore('#')
            val result = parse(pkg, old)
            // 旧账单不生成候选；支付宝不再识别详情页，因此返回 NotATradePage 而不是 SceneOnly
            assertFalse("$key 的旧账单不该成单（实际：$result）", result is PageParseResult.Candidate)
        }
    }

    @Test
    fun `订单详情页超过一天不生成候选`() {
        val old = listOf(
            "订单详情", "实付款", "¥59.00", "付款方式", "京东支付", "下单时间", "2026-10-04 10:00:00", "订单编号", "12345678",
        )
        assertTrue(parse("com.jingdong.app.mall", old) is PageParseResult.SceneOnly)
    }

    @Test
    fun `结果页不受旧账闸门影响_交易时间就是刚刚`() {
        val result = parse(
            NotificationRules.WECHAT,
            listOf("支付成功", "¥12.80", "收款方", "老王奶茶店", "付款方式", "零钱", "交易时间", "2026-10-06 12:34:30"),
        )
        assertTrue(result is PageParseResult.Candidate)
    }

    @Test
    fun `时间缺失时按捕获时刻兜底_并保留时间锚点`() {
        val flow = PageFlowTracker().flow(NotificationRules.WECHAT)
        // 收银台记录锚点 → 结果页无交易时间 → 用锚点时刻
        PageParserRegistry.parse(
            PageSnapshot(
                packageName = NotificationRules.WECHAT, captureTime = now, windowId = 1,
                nodes = listOf(PageNode(text = "¥25.00"), PageNode(text = "确认付款"), PageNode(text = "请输入支付密码")),
            ),
            flow,
        )
        val result = PageParserRegistry.parse(
            PageSnapshot(
                packageName = NotificationRules.WECHAT, captureTime = now + 5_000, windowId = 2,
                nodes = listOf(
                    PageNode(text = "支付成功"), PageNode(text = "¥25.00"),
                    PageNode(text = "收款方"), PageNode(text = "早餐店"), PageNode(text = "付款方式"), PageNode(text = "零钱"),
                ),
            ),
            flow,
        )
        assertTrue(result is PageParseResult.Candidate)
        assertEquals(now, (result as PageParseResult.Candidate).candidate.txTime)
    }
}
