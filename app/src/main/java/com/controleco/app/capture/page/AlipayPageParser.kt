package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType

/**
 * 支付宝（com.eg.android.AlipayGphone）页面解析器。
 *
 * 覆盖场景：
 * - 付款成功页：`付款成功`/`支付成功` + `付款方式`（商户为标题后的商户节点）
 * - 账单详情页：`账单详情`/`交易详情` + `创建时间`/`付款方式`/`商品说明`（字段最全）
 * - 收款到账页：`收款成功`/`已收款` + `收款时间`
 * - 退款页：`退款成功`/`退款金额`（收入 + 退款标记）
 * - 转账/充值/提现/还款页：`转账成功`/`充值成功`/`提现成功`/`还款成功`
 * - 自动扣款/免密支付页：`免密支付`/`自动扣款` + `扣款成功`
 *
 * 明确不生成候选：收银台（`确认付款`/`请输入支付密码`，记为流程上下文）、
 * 失败/取消页、转账记录/账单列表、首页宫格与"最近消息"里的历史预览。
 *
 * "原价 vs 实付"：支付宝账单详情常同时出现 `订单金额` 与 `优惠`，
 * 金额提取走"实付类标签 → 独立金额节点 → 订单金额"的固定优先级（见 PageParseSupport.pickPaidAmount）。
 */
object AlipayPageParser : AppPageParser {

    override val packages: Set<String> = setOf(NotificationRules.ALIPAY)
    override val label: String = "支付宝"

    private val detailTitles = listOf("账单详情", "交易详情")

    /** 付款结果页锚点 */
    private val successTitles = listOf("付款成功", "支付成功", "支付完成", "付款完成", "交易成功", "扣款成功", "代付成功")

    /** 收款到账锚点 */
    private val receiveAnchors = listOf("收款成功", "已收款", "收款到账", "到账成功")

    /** 资金操作锚点（充值/提现/转账/还款/转出转入） */
    private val transferAnchors = listOf(
        "转账成功", "转账到账", "充值成功", "提现成功", "转出成功", "转入成功", "还款成功", "提现到账", "缴费成功",
    )

    private val confirmMarkers = listOf(
        "确认付款", "确认支付", "立即支付", "请输入支付密码", "使用指纹支付", "免密支付", "向商家付款",
        "付款金额", "确认转账", "立即还款",
    )

    private val listMarkers = listOf("转账记录", "账单", "交易记录", "收支明细", "全部账单", "筛选")

    override fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        if (lines.isEmpty()) return PageParseResult.NotATradePage
        val joined = snapshot.joined

        if (isHomeShell(snapshot)) return PageParseResult.NotATradePage

        if (PageParseSupport.failureKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_FAILED, TradeState.FAILED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_FAILED, TradeState.FAILED, "支付宝支付失败页")
        }
        if (PageParseSupport.cancelKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED, "支付宝支付取消页")
        }

        if (isConfirmPage(snapshot)) {
            flow.noteConfirmPage(
                amountCents = amountAt(snapshot, confirmAmountText(snapshot)),
                counterparty = null,
                instrument = instrumentOf(snapshot, flow),
                atMillis = snapshot.captureTime,
            )
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CONFIRM, TradeState.PENDING, "支付宝收银台/密码键盘")
        }

        if (isListPage(snapshot)) {
            flow.noteScene(PageScene.LIST, TradeState.UNKNOWN)
            return PageParseResult.SceneOnly(PageScene.LIST, TradeState.UNKNOWN, "支付宝账单/转账列表页")
        }

        val detailTitle = detailTitles.firstOrNull { joined.contains(it) }
        // 详情页正文的判据放宽到"任一详情字段"：支付宝在不同版本/入口下会换成
        // 支付方式/交易单号/商户全称这套写法（与微信同形），只看 创建时间/付款方式 会漏识别
        val hasDetailBody = joined.contains("创建时间") || joined.contains("付款方式") ||
            joined.contains("支付方式") || joined.contains("商品说明") ||
            joined.contains("商户订单号") || joined.contains("交易单号") ||
            joined.contains("收款方") || joined.contains("商户全称")

        if (isRefundPage(snapshot)) return refundCandidate(snapshot, flow)

        if (detailTitle != null && hasDetailBody) return detailCandidate(snapshot, flow, detailTitle)

        if (successTitles.any { joined.contains(it) } && !joined.contains("当前状态")) {
            return successCandidate(snapshot, flow)
        }

        if (receiveAnchors.any { joined.contains(it) }) return receiveCandidate(snapshot, flow)

        if (transferAnchors.any { joined.contains(it) }) return transferCandidate(snapshot, flow)

        return PageParseResult.NotATradePage
    }

    // ---------- 场景判定 ----------

    /** 首页/生活号壳：宫格入口多且没有结果页锚点 */
    private fun isHomeShell(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (joined.contains("最近消息") || joined.contains("点击查看全部消息")) return true
        val gridWords = listOf("扫一扫", "收付款", "卡包", "出行", "市民中心", "余额宝", "花呗", "蚂蚁森林", "我的快递")
        val hits = gridWords.count { joined.contains(it) }
        if (hits >= 4 && successTitles.none { joined.contains(it) } && detailTitles.none { joined.contains(it) }) return true
        return false
    }

    private fun isConfirmPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (!confirmMarkers.any { joined.contains(it) }) return false
        if (joined.contains("交易详情") || joined.contains("创建时间")) return false
        if (successTitles.any { joined.contains(it) }) return false
        return true
    }

    private fun isListPage(snapshot: PageSnapshot): Boolean {
        if (PageParseSupport.looksLikeListPage(snapshot)) return true
        val joined = snapshot.joined
        if (listMarkers.none { joined.contains(it) }) return false
        val amounts = PageParseSupport.collectAmounts(snapshot.lines).size
        val listish = joined.contains("筛选") || joined.contains("全部") || joined.contains("收支") || amounts >= 3
        return listish && !joined.contains("商户订单号") && !joined.contains("商品说明")
    }

    private fun isRefundPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (joined.contains("退款成功") || joined.contains("退款到账") || joined.contains("退款金额")) return true
        if (joined.contains("退款") && (joined.contains("退款方式") || joined.contains("退款去向") || joined.contains("退款时间"))) {
            return true
        }
        return false
    }

    // ---------- 候选构造 ----------

    private fun successCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val anchorIdx = lines.indexOfFirst { line -> successTitles.any { line.contains(it) } }
        if (anchorIdx < 0) {
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页无锚点")
        }
        PageParseSupport.nonResultPageReason(snapshot, anchorIdx)?.let {
            return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "支付宝页形状:$it")
        }
        val token = successTitles.toList()
        val titleLike = PageParseSupport.isStatusTitleNode(lines[anchorIdx], token) ||
            lines[anchorIdx].let { line -> token.any { line.trim().startsWith(it) } && line.length <= 12 }
        if (!titleLike && !hasResultAnchors(snapshot)) {
            return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "支付宝成功词非标题位置")
        }

        val amountText = PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
            ?: return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页金额为零")
        }

        val instrument = instrumentOf(snapshot, flow)
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("ali-success|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) {
            flow.noteSuccess(instrument)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "支付宝成功页重复事件")
        }
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
                confidence = if (counterparty.isNotBlank()) ParseConfidence.HIGH else ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·付款成功页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = orderId,
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
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "支付宝详情页未完成状态")
        }
        if (PageParseSupport.looksLikeOrderPendingPage(snapshot)) {
            flow.noteScene(PageScene.ORDER_PENDING, TradeState.PENDING)
            return PageParseResult.SceneOnly(PageScene.ORDER_PENDING, TradeState.PENDING, "支付宝订单待支付页")
        }
        val amountText = PageParseSupport.pickPaidAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "支付宝详情页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.TRANSACTION_DETAIL, state, "支付宝详情页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "支付宝详情页金额为零")
        }
        // 旧账单详情页（交易时间超过 24 小时）不记：用户在翻历史账单
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        if (PageParseSupport.isStaleDetail(snapshot, txTime)) {
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "支付宝旧账单详情页")
        }
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("ali-detail|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "支付宝详情页重复事件")

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
                isRefund = isRefund,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, type, isRefund, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun receiveCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val anchorIdx = lines.indexOfFirst { line -> receiveAnchors.any { line.contains(it) } }
        val amountText = PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
            ?: PageParseSupport.amountAfterLabel(lines, listOf("收款金额", "金额"))
            ?: return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "支付宝收款页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "支付宝收款页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "支付宝收款页金额为零")
        }
        val seq = flow.markEmitted("ali-receive|$amountCents|${snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "支付宝收款页重复事件")
        flow.noteSuccess(null)

        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.INCOME,
                amountCents = amountCents,
                counterparty = PageParseSupport.valueAfterLabel(lines, listOf("付款方", "付款人", "来自")).orEmpty(),
                txTime = txTime,
                scene = PageScene.RECEIVE_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·收款到账页",
                orderId = PageParseSupport.orderIdFrom(lines),
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.INCOME, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun transferCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val joined = snapshot.joined
        val income = joined.contains("转入成功") || joined.contains("已转入") || joined.contains("已到账") ||
            joined.contains("到账成功")
        val amountText = PageParseSupport.pickPaidAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "支付宝资金操作页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "支付宝资金操作页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "支付宝资金操作页金额为零")
        }
        val type = if (income) TxType.INCOME else TxType.EXPENSE
        val seq = flow.markEmitted("ali-transfer|$type|$amountCents|${snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "支付宝资金操作页重复事件")
        val instrument = instrumentOf(snapshot, flow)
        flow.noteSuccess(instrument)

        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = type,
                amountCents = amountCents,
                counterparty = merchantOf(snapshot),
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = txTime,
                scene = PageScene.TRANSFER_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·转账/资金操作页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = PageParseSupport.orderIdFrom(lines),
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
            ?: return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "支付宝退款页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.REFUND_SUCCESS, TradeState.REFUND, "支付宝退款页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "支付宝退款页金额为零")

        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("ali-refund|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "支付宝退款页重复事件")
        flow.noteScene(PageScene.REFUND_SUCCESS, TradeState.REFUND)

        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.INCOME,
                amountCents = amountCents,
                counterparty = merchantOf(snapshot),
                txTime = txTime,
                scene = PageScene.REFUND_SUCCESS,
                tradeState = TradeState.REFUND,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·退款到账页",
                orderId = orderId,
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
        joined.contains("待付款") || joined.contains("待支付") || joined.contains("处理中") ||
            joined.contains("银行处理中") -> TradeState.PENDING
        joined.contains("交易成功") || joined.contains("支付成功") || joined.contains("已支付") ||
            (joined.contains("创建时间") && joined.contains("付款方式")) -> TradeState.SUCCESS
        else -> TradeState.UNKNOWN
    }

    private fun merchantOf(snapshot: PageSnapshot): String {
        PageParseSupport.valueAfterLabel(
            snapshot.lines,
            listOf("商户全称", "收款方全称", "商户名称", "收款方", "收款商户", "商家名称"),
        )?.let { if (PageParseSupport.looksLikeMerchant(it)) return it }
        return PageParseSupport.guessCounterparty(
            snapshot.lines,
            listOf("付款成功", "支付成功", "账单详情", "交易详情", "退款成功", "收款成功"),
        ).orEmpty()
    }

    private fun instrumentOf(snapshot: PageSnapshot, flow: PageFlow): PaymentInstrument? {
        val lines = snapshot.lines
        PageParseSupport.valueAfterLabel(lines, PageParseSupport.paymentMethodLabels)?.let { raw ->
            PageParseSupport.cleanPaymentMethod(raw)?.let { cleaned ->
                PaymentInstrumentParser.parse(cleaned, label)?.let { return it }
            }
        }
        // "账户余额"/"余额宝" 这类词常独立出现（收银台选择项）
        for (line in lines) {
            val t = line.trim()
            if (t.length > 12) continue
            if (t == "账户余额" || t == "余额宝" || t == "花呗") {
                PaymentInstrumentParser.parse(t, label)?.let { return it }
            }
        }
        return flow.confirmInstrument
    }

    private fun confirmAmountText(snapshot: PageSnapshot): String? =
        PageParseSupport.amountAfterLabel(snapshot, listOf("付款金额", "支付金额", "金额"))
            ?: PageParseSupport.pickPaidAmount(snapshot)

    private fun amountAt(snapshot: PageSnapshot, text: String?): Long? =
        text?.let { com.controleco.app.util.Money.parseCents(it) }?.takeIf { it > 0 }

    private fun hasResultAnchors(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        return listOf("付款方式", "支付方式", "交易方式", "查看账单详情", "完成", "收款方", "商品说明").any {
            joined.contains(it)
        }
    }

    private fun anchorOrNull(flow: PageFlow, snapshot: PageSnapshot): Long? {
        val anchor = flow.anchorTime ?: return null
        val zone = java.time.ZoneId.systemDefault()
        val d1 = java.time.Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
        val d2 = java.time.Instant.ofEpochMilli(snapshot.captureTime).atZone(zone).toLocalDate()
        return if (d1 == d2) anchor else null
    }
}
