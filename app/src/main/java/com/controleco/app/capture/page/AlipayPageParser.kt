package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType
import com.controleco.app.util.Money

/**
 * 支付宝（com.eg.android.AlipayGphone）页面解析器。
 *
 * **只保留有支付宝真机样本的动账结果页**：付款成功页。两种真实版式（2026-10 真机）：
 *
 * 1. 实付与标题分节点：
 *    `支付成功 | 回首页 | ￥ | 9.17 | 获得森林能量 | 鸿润福连锁超市 | ￥9.27 | 碰一下立减 | -￥0.10 | 付款方式 | 中国银行储蓄卡(****) …`
 * 2. 实付与标题同节点：
 *    `支付成功￥19.70 | 支付成功 | 19.70 | 订单金额 | ￥22.20 | 红包 | -￥2.50 | 付款方式 | 支付宝小荷包(…) …`
 *
 * 判据只有三条：
 * - 第一段可见文字必须以 `支付成功` 开头；
 * - 能取到标题内金额，或标题后的第一个金额（支持 `￥` 与数字拆成两个节点）；
 * - 不命中历史账单详情页字段（详情页正文里也可能出现“支付成功”作为当前状态）。
 *
 * **不识别**首页、列表、收银台、失败/取消、收款、转账、退款、详情页，也没有对应的
 * 页面规则：这些场景在支付宝侧没有可追溯的真机样本（收入/退款由通知与短信通道负责）。
 * 新增任何页面锚点/字段前必须先在 docs/自动记账捕获机制.md 记录来源，无明确证据不加入。
 */
object AlipayPageParser : AppPageParser {

    override val packages: Set<String> = setOf(NotificationRules.ALIPAY)
    override val label: String = "支付宝"

    private const val PAYMENT_SUCCESS = "支付成功"

    /**
     * 历史账单详情页标记（仅用于拒绝，不生成账目）。
     *
     * 来源：旧版生产页面规则中的 `billDetailKeywords`（账单详情/交易详情）、
     * 成功页的“当前状态”守卫、详情页字段 `创建时间`/`交易单号`。
     * 这些标记在旧版规则中已用于真实页面，避免把历史账单里的“支付成功”记成新支付。
     */
    private val historyDetailMarkers = listOf(
        "账单详情", "交易详情", "当前状态", "创建时间", "交易单号",
    )

    /**
     * 成功页明细区标记：从这些标签开始，后面的金额不是实付。
     * 出处：真实样本的 `订单金额`、`付款方式`、`红包`，以及收银台/优惠样本里的 `优惠`、`立减`。
     */
    private val breakdownMarkers = listOf("订单金额", "付款方式", "优惠", "立减", "红包")

    /**
     * 成功页徽章/按钮：与商户名同形，找商户时剔除。
     * 出处：2026-10-18 真实样本（获得森林能量/恭喜你获得/去领取/碰一下立减）。
     */
    private val badgeWords = listOf("获得森林能量", "恭喜你获得", "去领取", "碰一下立减")

    override fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val first = lines.firstOrNull()?.trim().orEmpty()
        if (!first.startsWith(PAYMENT_SUCCESS)) return PageParseResult.NotATradePage

        // 历史账单详情页：正文里的“支付成功”是状态字段，不是新的一笔
        if (historyDetailMarkers.any { snapshot.joined.contains(it) }) return PageParseResult.NotATradePage

        val amountText = firstPaymentAmount(lines)
            ?: return PageParseResult.SceneOnly(
                PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页无金额",
            )
        val amountCents = Money.parseCents(amountText)?.takeIf { it > 0 }
            ?: return PageParseResult.SceneOnly(
                PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页金额非法",
            )

        val instrument = paymentInstrumentOf(lines)
        val seq = flow.markEmitted("ali-success|$amountCents|${snapshot.fingerprint}")
        if (seq < 0) {
            flow.noteSuccess(instrument)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页重复事件")
        }
        flow.noteSuccess(instrument)

        val counterparty = merchantOf(lines)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.EXPENSE,
                amountCents = amountCents,
                counterparty = counterparty,
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = null,
                scene = PageScene.PAYMENT_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = if (counterparty.isNotBlank()) ParseConfidence.HIGH else ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·付款成功页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                amountAnchored = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.EXPENSE, false, amountCents, snapshot.captureTime,
                ),
            ),
        )
    }

    /**
     * 实付金额：标题行内金额 → 标题后第一个金额。
     *
     * 版式 2 的标题就是 `支付成功￥19.70`；版式 1 的实付被拆成 `￥` 与 `9.17` 两个节点，
     * 而订单原价 `￥9.27`、立减 `-￥0.10` 都在其后。无货币符号的裸金额要求带小数点，
     * 避免把徽章计数（如“600葵花籽”拆出的 600）当成金额。
     */
    private fun firstPaymentAmount(lines: List<String>): String? {
        PageParseSupport.amountInAnchor(lines.first())?.let { return it }
        var i = 1
        while (i < lines.size) {
            val text = lines[i].trim()
            if (text.isEmpty()) {
                i++
                continue
            }
            if (breakdownMarkers.any { text.contains(it) }) break
            if (text == "¥" || text == "￥") {
                amountToken(lines.getOrNull(i + 1).orEmpty())?.let { return it }
                i++
                continue
            }
            if (text.startsWith("¥") || text.startsWith("￥")) {
                PageParseSupport.amountInAnchor(text)?.let { return it }
            }
            amountToken(text, allowInteger = false)?.let { return it }
            i++
        }
        return null
    }

    /** 整行是纯金额节点时返回数值文本；负号开头的优惠项不算 */
    private fun amountToken(text: String, allowInteger: Boolean = true): String? {
        val t = text.trim()
        if (!PageParseSupport.isAmountLikeNode(t)) return null
        if (t.startsWith("-") || t.startsWith("−") || t.startsWith("－")) return null
        val cleaned = t.trimStart('¥', '￥', '+', ' ').trimEnd('元').trim()
        if (!allowInteger && !cleaned.contains('.')) return null
        return cleaned.takeIf { Money.parseCents(it)?.let { c -> c > 0 } == true }
    }

    /**
     * 无标签商户：从标题向后找第一个不是金额/按钮/明细标签的短文本。
     * 真实样本里商户紧跟实付金额（`鸿润福连锁超市`）；营销徽章先剔除。
     */
    private fun merchantOf(lines: List<String>): String {
        val filtered = lines.filterNot { line -> badgeWords.any { line.contains(it) } }
        val start = filtered.indexOfFirst { it.trim().startsWith(PAYMENT_SUCCESS) }
        if (start < 0) return ""
        for (i in start + 1 until minOf(filtered.size, start + 7)) {
            val text = filtered[i].trim()
            if (text.isEmpty()) continue
            if (text == "回首页" || text.startsWith(PAYMENT_SUCCESS)) continue
            if (breakdownMarkers.any { text.contains(it) }) break
            if (text.startsWith("¥") || text.startsWith("￥")) continue
            if (PageParseSupport.isAmountLikeNode(text)) continue
            return text.take(30)
        }
        return ""
    }

    /**
     * 支付方式：真实样本里 `付款方式` 独占一行，下一行是支付工具。
     * 不做多标签猜测；解析失败返回 null（宁可不匹配账户，也不猜）。
     */
    private fun paymentInstrumentOf(lines: List<String>): PaymentInstrument? {
        val index = lines.indexOfFirst { it.trim() == "付款方式" }
        if (index < 0) return null
        val raw = lines.getOrNull(index + 1)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return PaymentInstrumentParser.parse(raw, label)
    }
}
