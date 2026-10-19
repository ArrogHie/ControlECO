package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType
import com.controleco.app.util.Money

/**
 * 云闪付（com.unionpay）页面解析器。
 *
 * 覆盖场景：
 * - 支付成功页：`支付成功` + `付款方式`
 * - 交易详情页：`交易详情`/`云闪付交易详情` + `交易时间`/`付款方式`/`卡号`（字段最全）
 * - 转账页：`转账成功`/`转账金额` + `确认转账`
 * - 收款页：`收款方式` + `订单时间`/`订单编号`
 * - 退款页：`退款成功`/`退款金额`
 *
 * 云闪付的账户线索比其他应用更具体（`卡号 尾号1234`、`付款卡 工商银行储蓄卡(1234)`），
 * 这也是"支付方式 → 实际记账账户"最典型的输入场景，因此这里额外做卡号/银行的拼接。
 */
object UnionPayPageParser : AppPageParser {

    override val packages: Set<String> = setOf(NotificationRules.UNIONPAY)
    override val label: String = "云闪付"

    private val successTitles = listOf("支付成功", "付款成功", "交易成功", "支付完成", "充值成功")
    private val detailTitles = listOf("云闪付交易详情", "银联交易详情", "交易详情", "账单详情")
    private val transferAnchors = listOf("转账成功", "转账金额", "转出成功", "收款成功", "已收款")
    private val confirmMarkers = listOf("确认付款", "确认支付", "正在付款", "立即支付", "请输入支付密码", "确认转账", "立即还款")

    /** 还款/信用卡页：出现即视为非消费结果（避免把还款额记成消费） */
    private val repaymentMarkers = listOf("本次还款金额", "本期账单", "立即还款")

    override fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        if (lines.isEmpty()) return PageParseResult.NotATradePage
        val joined = snapshot.joined

        if (PageParseSupport.failureKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_FAILED, TradeState.FAILED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_FAILED, TradeState.FAILED, "云闪付支付失败页")
        }
        if (PageParseSupport.cancelKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED, "云闪付支付取消页")
        }
        if (isRepaymentPage(snapshot)) {
            flow.noteScene(PageScene.TRANSFER_SUCCESS, TradeState.PENDING)
            return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.PENDING, "云闪付信用卡还款页")
        }
        if (isConfirmPage(snapshot)) {
            flow.noteConfirmPage(
                amountCents = confirmAmount(snapshot),
                counterparty = null,
                instrument = instrumentOf(snapshot, flow),
                atMillis = snapshot.captureTime,
            )
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CONFIRM, TradeState.PENDING, "云闪付收银台/密码键盘")
        }
        if (isListPage(snapshot)) {
            flow.noteScene(PageScene.LIST, TradeState.UNKNOWN)
            return PageParseResult.SceneOnly(PageScene.LIST, TradeState.UNKNOWN, "云闪付交易列表页")
        }
        if (isRefundPage(snapshot)) return refundCandidate(snapshot, flow)

        val detailTitle = detailTitles.firstOrNull { joined.contains(it) }
        if (detailTitle != null && hasDetailBody(snapshot)) return detailCandidate(snapshot, flow, detailTitle)

        if (successTitles.any { joined.contains(it) } && hasResultAnchors(snapshot)) {
            return successCandidate(snapshot, flow)
        }
        if (transferAnchors.any { joined.contains(it) }) return transferCandidate(snapshot, flow)

        return PageParseResult.NotATradePage
    }

    private fun isRepaymentPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        return repaymentMarkers.count { joined.contains(it) } >= 2
    }

    private fun isConfirmPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (!confirmMarkers.any { joined.contains(it) }) return false
        if (joined.contains("交易详情") || joined.contains("订单编号")) return false
        if (successTitles.any { joined.contains(it) }) return false
        return true
    }

    private fun isListPage(snapshot: PageSnapshot): Boolean {
        if (PageParseSupport.looksLikeListPage(snapshot)) return true
        val joined = snapshot.joined
        val amounts = PageParseSupport.collectAmounts(snapshot.lines).size
        val listish = joined.contains("卡管理") || joined.contains("收支明细") ||
            (joined.contains("交易记录") && amounts >= 2)
        return listish && !joined.contains("交易时间")
    }

    private fun isRefundPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (joined.contains("退款成功") || joined.contains("退款金额")) return true
        return joined.contains("退款") && (joined.contains("退款方式") || joined.contains("退款时间") || joined.contains("商户订单号"))
    }

    private fun hasDetailBody(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        return joined.contains("交易时间") || joined.contains("订单时间") || joined.contains("卡号") ||
            joined.contains("付款方式") || joined.contains("商户名称") || joined.contains("订单编号")
    }

    private fun hasResultAnchors(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        return listOf("付款方式", "收款方式", "付款卡", "订单金额", "交易金额", "完成", "查看详情", "订单时间")
            .any { joined.contains(it) }
    }

    // ---------- 候选构造 ----------

    private fun successCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val anchorIdx = lines.indexOfFirst { line -> successTitles.any { line.contains(it) } }
        val amountText = PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
            ?: return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "云闪付结果页无金额")
        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "云闪付结果页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "云闪付结果页金额为零")
        }
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("up-success|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "云闪付结果页重复事件")

        val instrument = instrumentOf(snapshot, flow)
        flow.noteSuccess(instrument)
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        val counterparty = merchantOf(snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.EXPENSE,
                amountCents = amountCents,
                counterparty = counterparty,
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = txTime,
                scene = PageScene.PAYMENT_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = if (instrument != null) ParseConfidence.HIGH else ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·支付成功页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = orderId,
                amountAnchored = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.EXPENSE, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun detailCandidate(snapshot: PageSnapshot, flow: PageFlow, title: String): PageParseResult {
        val lines = snapshot.lines
        val joined = snapshot.joined
        val state = detailTradeState(joined)
        if (state != TradeState.SUCCESS && state != TradeState.REFUND) {
            flow.noteScene(PageScene.TRANSACTION_DETAIL, state)
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "云闪付详情页未完成状态")
        }
        val amountText = PageParseSupport.pickPaidAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "云闪付详情页无金额")
        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.TRANSACTION_DETAIL, state, "云闪付详情页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "云闪付详情页金额为零")

        // 旧账单详情页（交易时间超过 24 小时）不记：用户在翻历史账单
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        if (PageParseSupport.isStaleDetail(snapshot, txTime)) {
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "云闪付旧账单详情页")
        }
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("up-detail|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "云闪付详情页重复事件")

        val instrument = instrumentOf(snapshot, flow)
        flow.noteSuccess(instrument)
        val isRefund = state == TradeState.REFUND
        val type = if (isRefund || joined.contains("收入")) TxType.INCOME else TxType.EXPENSE

        return PageParseResult.Candidate(
            PageCandidate(
                type = type,
                amountCents = amountCents,
                counterparty = merchantOf(snapshot),
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = txTime,
                scene = PageScene.TRANSACTION_DETAIL,
                tradeState = state,
                confidence = ParseConfidence.HIGH,
                sourceLabel = label,
                ruleName = "$label·$title",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = orderId,
                amountAnchored = true,
                isRefund = isRefund,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, type, isRefund, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun transferCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val joined = snapshot.joined
        val income = joined.contains("收款成功") || joined.contains("已收款")
        val amountText = PageParseSupport.amountAfterLabel(lines, listOf("转账金额", "收款金额", "订单金额", "金额"))
            ?: PageParseSupport.pickPaidAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "云闪付转账页无金额")
        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "云闪付转账页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "云闪付转账页金额为零")
        val type = if (income) TxType.INCOME else TxType.EXPENSE
        if (flow.markEmitted("up-transfer|$type|$amountCents|${snapshot.fingerprint}") < 0) {
            return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "云闪付转账页重复事件")
        }
        val instrument = instrumentOf(snapshot, flow)
        flow.noteSuccess(instrument)
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = type,
                amountCents = amountCents,
                counterparty = PageParseSupport.valueAfterLabel(lines, listOf("收款方", "付款方", "收款人")).orEmpty(),
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = txTime,
                scene = PageScene.TRANSFER_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·转账页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = PageParseSupport.orderIdFrom(lines),
                amountAnchored = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, type, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun refundCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val amountText = PageParseSupport.pickRefundAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "云闪付退款页无金额")
        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.REFUND_SUCCESS, TradeState.REFUND, "云闪付退款页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "云闪付退款页金额为零")
        val orderId = PageParseSupport.orderIdFrom(lines)
        if (flow.markEmitted("up-refund|$amountCents|${orderId ?: snapshot.fingerprint}") < 0) {
            return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "云闪付退款页重复事件")
        }
        flow.noteScene(PageScene.REFUND_SUCCESS, TradeState.REFUND)
        val instrument = instrumentOf(snapshot, flow)
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.INCOME,
                amountCents = amountCents,
                counterparty = merchantOf(snapshot),
                instrument = instrument,
                txTime = txTime,
                scene = PageScene.REFUND_SUCCESS,
                tradeState = TradeState.REFUND,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·退款页",
                orderId = orderId,
                amountAnchored = true,
                isRefund = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.INCOME, true, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    // ---------- 字段提取 ----------

    private fun detailTradeState(joined: String): TradeState = when {
        PageParseSupport.refundKeywords.any { joined.contains(it) } -> TradeState.REFUND
        PageParseSupport.failureKeywords.any { joined.contains(it) } -> TradeState.FAILED
        PageParseSupport.cancelKeywords.any { joined.contains(it) } -> TradeState.CANCELLED
        joined.contains("待付款") || joined.contains("处理中") || joined.contains("银行处理中") -> TradeState.PENDING
        joined.contains("交易成功") || joined.contains("支付成功") || joined.contains("已支付") ||
            joined.contains("交易详情") -> TradeState.SUCCESS
        else -> TradeState.UNKNOWN
    }

    private fun merchantOf(snapshot: PageSnapshot): String {
        PageParseSupport.valueAfterLabel(snapshot.lines, listOf("商户名称", "商户全称", "收款方", "付款方", "商户"))
            ?.let { if (PageParseSupport.looksLikeMerchant(it)) return it }
        return PageParseSupport.guessCounterparty(snapshot.lines, listOf("支付成功", "交易详情", "转账成功", "收款成功"))
            .orEmpty()
    }

    /**
     * 支付工具提取：`付款方式`/`收款方式`/`付款卡` 标签值 → "卡号 尾号1234" 拼接 →
     * "付款方式，已选择…"合并文本。云闪付的卡号信息最完整，能与账户尾号精确对上。
     */
    private fun instrumentOf(snapshot: PageSnapshot, flow: PageFlow): PaymentInstrument? {
        val lines = snapshot.lines
        val labels = listOf("付款方式", "收款方式", "付款卡", "付款卡号", "支付方式", "扣款方式")
        PageParseSupport.valueAfterLabel(lines, labels)?.let { raw ->
            PageParseSupport.cleanPaymentMethod(raw)?.let { cleaned ->
                PaymentInstrumentParser.parse(cleaned, label)?.let { return it }
                PaymentInstrumentParser.parse(raw, label)?.let { return it }
            }
        }
        // "卡号 → 尾号1234" / "卡号 → **** 1234"：只有尾号，仍可用于账户尾号匹配
        val cardIndex = lines.indexOfFirst { it.trim() == "卡号" || it.trim() == "付款卡号" }
        if (cardIndex >= 0) {
            val next = lines.getOrNull(cardIndex + 1).orEmpty()
            val tail = PaymentInstrumentParser.tailOf(next)
            if (tail.isNotEmpty()) {
                return PaymentInstrument(
                    raw = next.trim(),
                    kind = InstrumentKind.BANK_CARD,
                    channel = label,
                    tail = tail,
                    matchKeys = listOfNotNull(if (tail.isNotEmpty()) tail else null),
                )
            }
        }
        // "付款方式，已选择工商银行储蓄卡(1234)"
        for (line in lines) {
            if (!labels.any { line.contains(it) }) continue
            val tail = line.substringAfter("已选择", "")
            if (tail.isBlank()) continue
            PageParseSupport.cleanPaymentMethod(tail)?.let { cleaned ->
                PaymentInstrumentParser.parse(cleaned, label)?.let { return it }
            }
        }
        return flow.confirmInstrument
    }

    private fun confirmAmount(snapshot: PageSnapshot): Long? {
        val value = PageParseSupport.amountAfterLabel(
            snapshot.lines, listOf("订单金额", "付款金额", "转账金额", "金额"),
        ) ?: PageParseSupport.pickPaidAmount(snapshot) ?: return null
        return Money.parseCents(value)?.takeIf { it > 0 }
    }

    private fun anchorOrNull(flow: PageFlow, snapshot: PageSnapshot): Long? {
        val anchor = flow.anchorTime ?: return null
        val zone = java.time.ZoneId.systemDefault()
        val d1 = java.time.Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
        val d2 = java.time.Instant.ofEpochMilli(snapshot.captureTime).atZone(zone).toLocalDate()
        return if (d1 == d2) anchor else null
    }
}
