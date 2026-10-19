package com.controleco.app.capture.page

import com.controleco.app.data.db.TxType
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微信页面解析回归：聊天页历史气泡、收款锚定金额、红包领取页用户确认、无标题商户详情。
 *
 * 样本按真机捕获日志的节点顺序脱敏重排（对方/商户等均为占位文本），
 * 只保留页面结构与金额，用于锁定"聊天页不得产生收款候选""红包只能询问不能自动成单"等边界。
 */
class WechatPageRegressionTest {

    private val capturedAt: Long = millis(2026, 10, 17, 17, 43, 0)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun snapshot(texts: List<String>, at: Long = capturedAt): PageSnapshot = PageSnapshot(
        packageName = "com.tencent.mm",
        captureTime = at,
        windowId = 1,
        nodes = texts.map { PageNode(text = it) },
    )

    private fun parse(texts: List<String>, flow: PageFlow, at: Long = capturedAt): PageParseResult =
        WechatPageParser.parse(snapshot(texts, at), flow)

    private fun newFlow(): PageFlow = PageFlowTracker().flow("com.tencent.mm")

    private fun candidate(result: PageParseResult): PageCandidate {
        assertTrue("应生成候选（实际：$result）", result is PageParseResult.Candidate)
        return (result as PageParseResult.Candidate).candidate
    }

    /** 聊天页：顶部"聊天信息"、输入栏特征齐全，历史气泡里同时有 7.50 与 5.20 两笔转账 */
    private val chatPage = listOf(
        "返回", "105", "聊天信息",
        "8月30日 15:26", "￥7.50", "已收款", "转账", "转账头像",
        "17:36", "转账头像", "￥5.20", "已被接收", "转账", "红包头像",
        "恭喜发财，大吉大利", "已领取", "微信红包", "图片",
        "17:41", "￥5.20", "已收款", "转账", "聊天头像",
        "你领取了对方的红包",
        "切换到按住说话", "听写", "表情", "更多功能按钮，已折叠",
    )

    /** 真实收款到账页：标题行自带锚定金额，另有转账时间/收款时间/零钱余额/账单详情 */
    private val receivePage = listOf(
        "你已收款，资金已存入零钱¥5.20",
        "你已收款，资金已存入零钱",
        "¥5.20",
        "零钱余额",
        "转账时间",
        "2026年10月17日 17:36:13",
        "收款时间",
        "2026年10月17日 17:41:12",
        "零钱通 七日年化0.94%",
        "转入零钱通，能赚又能花",
        "转入",
        "账单详情",
    )

    /** 红包封面页：`5.20` 与 `元` 是两个独立节点 */
    private val redPacketCoverPage = listOf(
        "图片", "返回", "更多", "封面由个人制作",
        "对方的红包", "恭喜发财，大吉大利",
        "5.20", "元", "回复表情到聊天", "了解红包封面",
    )

    /** 无标题商户账单详情：顶部是裸负数金额 */
    private val merchantDetailPage = listOf(
        "-12.35",
        "当前状态", "支付成功",
        "交易单号", "4200000000000000000",
        "商户全称", "某某商户",
        "交易时间", "2026-10-17 17:35:00",
        "支付方式", "零钱",
    )

    // ---------- 聊天页 ----------

    @Test
    fun `聊天页历史已收款气泡不生成候选_也不会取整页第一笔7点50`() {
        val flow = newFlow()
        val first = parse(chatPage, flow)
        assertEquals(PageParseResult.NotATradePage, first)

        // 同一聊天页重复采集（跨分钟）仍然不成单
        val second = parse(chatPage, flow, at = capturedAt + 60_000)
        assertEquals(PageParseResult.NotATradePage, second)
    }

    @Test
    fun `普通聊天页即使没有红包领取文字也不成单`() {
        val plainChat = listOf(
            "返回", "聊天信息", "8月30日 15:26",
            "￥7.50", "已收款", "转账",
            "￥5.20", "已收款", "转账",
            "切换到按住说话", "听写", "更多功能按钮，已折叠",
        )
        assertEquals(PageParseResult.NotATradePage, parse(plainChat, newFlow()))
    }

    // ---------- 收款到账页 ----------

    @Test
    fun `真实收款页按锚点金额生成恰好一笔_交易时间优先`() {
        val flow = newFlow()
        val r = candidate(parse(receivePage, flow))
        assertEquals(TxType.INCOME, r.type)
        assertEquals(520L, r.amountCents)
        assertEquals("微信·收款到账页", r.ruleName)
        assertTrue(r.amountAnchored)
        val expectedTxTime = millis(2026, 10, 17, 17, 36, 13)
        assertEquals(expectedTxTime, r.txTime)
        assertEquals(
            "com.tencent.mm|INCOME|520|${expectedTxTime / 60_000}",
            r.dedupKey,
        )

        // 同一页面在下一分钟再次采集：仍不重复生成
        val again = parse(receivePage, flow, at = capturedAt + 60_000)
        assertFalse("同一收款页不得重复成单（实际：$again）", again is PageParseResult.Candidate)

        // 页面混入浮窗等无关文本（指纹变化）但交易时间相同：页面身份仍按交易时间稳定
        val noisy = receivePage + listOf("浮窗", "退出浮窗", "APK文件")
        val noisyAgain = parse(noisy, flow, at = capturedAt + 90_000)
        assertFalse("指纹变化不得让同一收款页重复成单（实际：$noisyAgain）", noisyAgain is PageParseResult.Candidate)
    }

    @Test
    fun `收款锚点邻域出现两个不同金额时拒绝`() {
        val ambiguous = listOf(
            "微信转账", "已收钱 ¥7.50", "¥5.20",
            "收款时间", "2026-10-17 17:41:12", "账单详情",
        )
        val r = parse(ambiguous, newFlow())
        assertFalse("锚点金额歧义必须拒绝（实际：$r）", r is PageParseResult.Candidate)
    }

    // ---------- 红包 ----------

    @Test
    fun `红包领取页生成待用户确认候选_不自动成单`() {
        val r = candidate(parse(redPacketCoverPage, newFlow()))
        assertEquals(TxType.INCOME, r.type)
        assertEquals(520L, r.amountCents)
        assertEquals("微信·红包待确认", r.ruleName)
        assertTrue("红包领取页必须标记为用户确认", r.requiresUserConfirmation)
        assertEquals(PageScene.RED_PACKET, r.scene)
        assertEquals(TradeState.UNKNOWN, r.tradeState)
    }

    @Test
    fun `红包领取页没有金额也提示用户补录`() {
        val noAmount = listOf(
            "图片", "返回", "对方的红包", "恭喜发财，大吉大利",
            "回复表情到聊天", "了解红包封面",
        )
        val r = candidate(parse(noAmount, newFlow()))
        assertTrue(r.requiresUserConfirmation)
        assertEquals(0L, r.amountCents)
    }

    @Test
    fun `聊天页历史已领取不会挡掉叠加的红包领取页`() {
        // 封面叠加在聊天页之上时，同包名快照可能同时含聊天输入栏和历史“已领取”；
        // 领取页判定必须先于聊天页闸门，且只按封面标记附近判断“已领取”。
        val combined = listOf(
            "聊天信息", "你领取了对方的红包", "已领取", "微信红包",
            "切换到按住说话", "听写", "更多功能按钮，已折叠",
        ) + redPacketCoverPage
        val r = candidate(parse(combined, newFlow()))
        assertTrue(r.requiresUserConfirmation)
        assertEquals(520L, r.amountCents)
    }

    @Test
    fun `同一领取页重复采样不重复提示`() {
        val flow = newFlow()
        assertTrue(parse(redPacketCoverPage, flow) is PageParseResult.Candidate)
        val again = parse(redPacketCoverPage, flow, at = capturedAt + 1_000)
        assertFalse("同一领取页不得重复生成待确认候选（实际：$again）", again is PageParseResult.Candidate)
    }

    @Test
    fun `聊天页红包领取文字不生成候选`() {
        // 历史消息里同时有 7.50/5.20 和"你领取了…红包"：红包自动链路已停用，聊天页一律不成单。
        assertEquals(PageParseResult.NotATradePage, parse(chatPage, newFlow()))
    }

    @Test
    fun `红包详情页不自动生成候选`() {
        val receiptPage = listOf(
            "红包详情", "已领取", "￥5.20", "微信红包",
            "当前状态", "成功", "交易单号", "4200000000000000000",
        )
        val r = parse(receiptPage, newFlow())
        assertFalse("红包详情页不得自动成单（实际：$r）", r is PageParseResult.Candidate)
        assertTrue(r is PageParseResult.SceneOnly)
        assertEquals(PageScene.RED_PACKET, (r as PageParseResult.SceneOnly).scene)
    }

    @Test
    fun `发红包页面不会生成待确认候选`() {
        val sendPage = listOf(
            "发红包", "更多信息", "金额", "¥0.00", "恭喜发财，大吉大利", "添加表情",
            "红包封面", "¥0.00", "塞钱进红包", "未领取的红包，将于24小时后发起退款",
        )
        val r = parse(sendPage, newFlow())
        assertFalse("发红包页不得提示（实际：$r）", r is PageParseResult.Candidate)
    }

    @Test
    fun `别人领取你发出的红包不生成候选`() {
        val chat = listOf(
            "聊天信息", "恭喜发财，大吉大利", "已领取", "微信红包",
            "对方领取了你的红包", "按住说话", "听写", "更多功能按钮，已折叠",
        )
        assertEquals(PageParseResult.NotATradePage, parse(chat, newFlow()))
    }

    @Test
    fun `转账页仍保留自动记账`() {
        val transferPage = listOf(
            "转账成功", "已转出", "¥5.20", "零钱",
            "转账时间", "2026-10-17 17:40:00", "账单详情",
        )
        val r = candidate(parse(transferPage, newFlow()))
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(520L, r.amountCents)
        assertFalse("转账不应走用户确认", r.requiresUserConfirmation)
    }

    // ---------- 无标题商户账单详情 ----------

    @Test
    fun `无标题商户详情页裸负数金额按支出入账`() {
        val r = candidate(parse(merchantDetailPage, newFlow()))
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1235L, r.amountCents)
        assertEquals("微信·账单详情页", r.ruleName)
        assertTrue(r.amountAnchored)
        assertEquals(millis(2026, 10, 17, 17, 35, 0), r.txTime)
        assertEquals("4200000000000000000", r.orderId)
        assertEquals("某某商户", r.counterparty)
    }

    @Test
    fun `无标题商户详情页超过二十四小时不生成候选`() {
        val old = merchantDetailPage.map {
            if (it == "2026-10-17 17:35:00") "2026-10-15 17:35:00" else it
        }
        val r = parse(old, newFlow())
        assertFalse("旧账单详情页不得成单（实际：$r）", r is PageParseResult.Candidate)
    }

    @Test
    fun `无标题商户详情页带正号金额按收入入账`() {
        val incoming = listOf(
            "+12.35", "当前状态", "支付成功", "交易单号", "4200000000000000001",
            "商户全称", "某某商户", "交易时间", "2026-10-17 17:35:00",
        )
        val r = candidate(parse(incoming, newFlow()))
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1235L, r.amountCents)
    }
}
