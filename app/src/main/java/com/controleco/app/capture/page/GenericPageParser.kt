package com.controleco.app.capture.page

import com.controleco.app.capture.BankLedgerRules
import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType
import com.controleco.app.util.Money

/**
 * 兜底页面解析器：给**没有专属解析器**、但属于监听目录/已知收银台来源的应用用。
 *
 * 定位（刻意与专属解析器区分）：
 * - 只作为"谨慎的兜底识别层"，绝不取代专属解析器；
 * - 判据比专属解析器**更严**：必须命中明确的成功标题（且标题像结果页大标题），
 *   同时具备结果页锚点（付款方式/完成/查看订单等），金额必须落在实付/独立金额节点上；
 * - 说明文档里如实标注为"通用兜底（未逐应用校准）"，不宣称已适配。
 *
 * 为什么保留它：监听目录里还有大量应用（各银行 App、后续新增的电商），
 * 没有兜底层的话它们连"值得看一眼的样本"都留不下来；有了它至少能记录页面快照并尝试保守识别。
 */
object GenericPageParser : AppPageParser {

    override val packages: Set<String> = emptySet()

    override val label: String get() = "通用"

    /** 成功标题：必须**本身**是标题样节点，避免消息流/进度条里的状态词 */
    private val successTitles = listOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "交易成功", "扣款成功", "消费成功",
        "下单成功", "充值成功", "提现成功", "转账成功", "还款成功", "收款成功", "已支付",
    )

    private val detailTitles = listOf("账单详情", "交易详情", "订单详情", "支付详情")

    private val confirmMarkers = listOf(
        "确认付款", "确认支付", "立即支付", "提交订单", "请输入支付密码", "免密支付", "去支付", "确认转账",
    )

    /** 兜底适用来源：已知支付/收银台来源 + 银行 App（银行 App 的页面也可能是动账详情） */
    fun accepts(packageName: String): Boolean =
        NotificationRules.isInterested(packageName) || BankLedgerRules.bankNameOfPackage(packageName) != null

    override fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val packageName = snapshot.packageName
        if (!accepts(packageName)) return PageParseResult.NotATradePage
        val lines = snapshot.lines
        if (lines.isEmpty()) return PageParseResult.NotATradePage
        val joined = snapshot.joined
        val sourceLabel = NotificationRules.sourceLabel(packageName)

        if (PageParseSupport.failureKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_FAILED, TradeState.FAILED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_FAILED, TradeState.FAILED, "通用失败页")
        }
        if (PageParseSupport.cancelKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED, "通用取消页")
        }
        if (PageParseSupport.looksLikeOrderPendingPage(snapshot) || isPendingPage(snapshot)) {
            flow.noteScene(PageScene.ORDER_PENDING, TradeState.PENDING)
            return PageParseResult.SceneOnly(PageScene.ORDER_PENDING, TradeState.PENDING, "通用待支付页")
        }
        if (isConfirmPage(snapshot)) {
            flow.noteConfirmPage(
                amountCents = PageParseSupport.pickPaidAmount(snapshot)?.let { Money.parseCents(it) },
                counterparty = PageParseSupport.valueAfterLabel(lines, PageParseSupport.counterpartyLabels),
                instrument = instrumentOf(snapshot, flow),
                atMillis = snapshot.captureTime,
            )
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CONFIRM, TradeState.PENDING, "通用收银台页")
        }
        if (PageParseSupport.looksLikeListPage(snapshot)) {
            flow.noteScene(PageScene.LIST, TradeState.UNKNOWN)
            return PageParseResult.SceneOnly(PageScene.LIST, TradeState.UNKNOWN, "通用列表页")
        }

        val anchorIdx = lines.indexOfFirst { line -> successTitles.any { line.contains(it) } }
        val detailTitle = detailTitles.firstOrNull { joined.contains(it) }
        val isSuccess = anchorIdx >= 0 && PageParseSupport.isStatusTitleNode(lines[anchorIdx], successTitles)
        if (!isSuccess && detailTitle == null) return PageParseResult.NotATradePage

        val shapeReject = PageParseSupport.nonResultPageReason(snapshot, anchorIdx)
        if (shapeReject != null) {
            return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "通用页形状:$shapeReject")
        }
        // 兜底路径要求结果页锚点：只有成功标题、没有支付方式/时间/单号等锚点的页面大概率不是结果页
        if (!hasResultAnchors(snapshot)) {
            return PageParseResult.SceneOnly(
                PageScene.UNKNOWN, TradeState.UNKNOWN, "通用页缺少结果页锚点",
            )
        }
        val isRefund = joined.contains("退款") || joined.contains("退回")
        val amountText = if (isRefund) {
            PageParseSupport.pickRefundAmount(snapshot)
        } else {
            PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
        } ?: return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "通用页无金额")

        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.UNKNOWN, TradeState.UNKNOWN, "通用页金额非法",
        )
        if (amountCents <= 0 || amountCents > NotificationRules.MAX_AMOUNT_CENTS) {
            return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "通用页金额越界")
        }

        val type = when {
            isRefund -> TxType.INCOME
            joined.contains("收入") || joined.contains("收款") || joined.contains("到账") -> TxType.INCOME
            else -> TxType.EXPENSE
        }
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seqKey = "generic|$packageName|$type|$amountCents|${orderId ?: snapshot.fingerprint}"
        if (flow.markEmitted(seqKey) < 0) {
            return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.SUCCESS, "通用页重复事件")
        }

        val instrument = instrumentOf(snapshot, flow)
        flow.noteSuccess(instrument)
        val scene = when {
            isRefund -> PageScene.REFUND_SUCCESS
            type == TxType.INCOME -> PageScene.RECEIVE_SUCCESS
            detailTitle != null -> PageScene.TRANSACTION_DETAIL
            else -> PageScene.PAYMENT_SUCCESS
        }
        val state = if (isRefund) TradeState.REFUND else TradeState.SUCCESS
        return PageParseResult.Candidate(
            PageCandidate(
                type = type,
                amountCents = amountCents,
                counterparty = PageParseSupport.valueAfterLabel(lines, PageParseSupport.counterpartyLabels)
                    ?.takeIf { PageParseSupport.looksLikeMerchant(it) }
                    ?: PageParseSupport.guessCounterparty(lines, successTitles + detailTitles).orEmpty(),
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = PageParseSupport.timeFromPage(snapshot) ?: flow.anchorTime,
                scene = scene,
                tradeState = state,
                // 兜底解析没有逐应用校准：置信度一律 MEDIUM，日志与溯源里可识别
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = sourceLabel,
                ruleName = "$sourceLabel·通用结果页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = orderId,
                isRefund = isRefund,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    packageName, type, isRefund, amountCents, PageParseSupport.timeFromPage(snapshot) ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun isPendingPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        val pendingWord = listOf("待付款", "待支付", "等待付款", "处理中", "支付中", "处理中…").any { joined.contains(it) }
        if (!pendingWord) return false
        // "支付成功"标题 + "处理中" 的混合页面（如"退款处理中"）不当待支付
        return successTitles.none { title -> snapshot.lines.any { PageParseSupport.isStatusTitleNode(it, listOf(title)) } }
    }

    private fun isConfirmPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (!confirmMarkers.any { joined.contains(it) }) return false
        if (successTitles.any { joined.contains(it) }) return false
        if (detailTitles.any { joined.contains(it) }) return false
        if (joined.contains("订单编号") || joined.contains("交易单号")) return false
        return true
    }

    private fun hasResultAnchors(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        val anchors = listOf(
            "付款方式", "支付方式", "交易方式", "创建时间", "支付时间", "交易时间", "下单时间",
            "订单编号", "交易单号", "商户订单号", "完成", "查看订单", "返回商家",
        )
        return anchors.count { joined.contains(it) } >= 2
    }

    private fun instrumentOf(snapshot: PageSnapshot, flow: PageFlow): PaymentInstrument? {
        PageParseSupport.valueAfterLabel(snapshot.lines, PageParseSupport.paymentMethodLabels)?.let { raw ->
            PageParseSupport.cleanPaymentMethod(raw)?.let { cleaned ->
                PaymentInstrumentParser.parse(cleaned, snapshot.packageName)?.let { return it }
            }
        }
        return flow.confirmInstrument
    }
}
