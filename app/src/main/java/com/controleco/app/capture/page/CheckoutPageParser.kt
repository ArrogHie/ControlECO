package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType
import com.controleco.app.util.Money

/**
 * 电商/外卖收银台家族的页面解析策略。
 *
 * 这些应用的付款路径高度同形：**收银台页（选好了但没付）→ 收银台内完成支付 → 支付结果页 →
 * 订单详情页**。差异集中在关键词与版式细节上，所以这里用"共用解析策略 + 每应用一份画像
 * （[CheckoutProfile]）"的方式实现——每个应用仍是独立解析器、独立包名分派、独立场景词表，
 * 而不是把所有应用塞进一个通用正则。
 *
 * 共同铁律：
 * - 收银台/待支付/处理中一律不生成候选（只记录流程上下文，成功页缺失字段时补齐）；
 * - 订单列表/订单进度/钱包账单页的"支付成功"不是结果页（列表形状闸门 + 标题位置约束）；
 * - 金额优先取"实付"类（实付款/实际支付/已支付），订单总额只作兜底，优惠/运费/余额一律不取。
 */
open class CheckoutPageParser(protected val profile: CheckoutProfile) : AppPageParser {

    override val packages: Set<String> get() = profile.packageNames
    override val label: String get() = profile.label

    override fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        if (lines.isEmpty()) return PageParseResult.NotATradePage
        val joined = snapshot.joined

        if (PageParseSupport.failureKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_FAILED, TradeState.FAILED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_FAILED, TradeState.FAILED, "$label 支付失败页")
        }
        if (PageParseSupport.cancelKeywords.any { joined.contains(it) } ||
            profile.orderCancelledMarkers.any { joined.contains(it) }
        ) {
            flow.noteScene(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED, "$label 支付取消页")
        }

        // 待支付订单页（下单但没付钱）：只记录流程，绝不记账
        if (isOrderPendingPage(snapshot)) {
            flow.noteScene(PageScene.ORDER_PENDING, TradeState.PENDING)
            return PageParseResult.SceneOnly(PageScene.ORDER_PENDING, TradeState.PENDING, "$label 订单待支付页")
        }

        // 收银台：记下金额/支付方式作为流程上下文（结果页缺字段时补齐），不生成候选
        if (isConfirmPage(snapshot)) {
            flow.noteConfirmPage(
                amountCents = confirmAmount(snapshot),
                counterparty = confirmCounterparty(snapshot),
                instrument = instrumentOf(snapshot, flow),
                atMillis = snapshot.captureTime,
            )
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CONFIRM, TradeState.PENDING, "$label 收银台/支付确认页")
        }

        // 订单列表/进度页：行内状态词不是结果页
        if (isListPage(snapshot)) {
            flow.noteScene(PageScene.LIST, TradeState.UNKNOWN)
            return PageParseResult.SceneOnly(PageScene.LIST, TradeState.UNKNOWN, "$label 订单列表页")
        }

        if (isRefundPage(snapshot)) return refundCandidate(snapshot, flow)
        if (isSuccessPage(snapshot)) return successCandidate(snapshot, flow)
        if (profile.receiveAnchors.isNotEmpty() && profile.receiveAnchors.any { joined.contains(it) }) {
            return receiveCandidate(snapshot, flow)
        }
        if (isDetailPage(snapshot)) return detailCandidate(snapshot, flow)
        return PageParseResult.NotATradePage
    }

    // ---------- 场景判定 ----------

    protected open fun isOrderPendingPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (profile.orderPendingMarkers.any { joined.contains(it) }) return true
        if (PageParseSupport.looksLikeOrderPendingPage(snapshot)) return true
        return (joined.contains("待付款") || joined.contains("待支付") || joined.contains("等待付款")) &&
            !successAnchor(snapshot).let { it != null && it.second }
    }

    protected open fun isConfirmPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (!profile.confirmMarkers.any { joined.contains(it) }) return false
        // 结果页优先：出现明确的成功/失败状态时不是收银台
        if (profile.successTitles.any { joined.contains(it) }) return false
        if (profile.detailTitles.any { joined.contains(it) }) return false
        if (joined.contains("订单编号") || joined.contains("交易单号")) return false
        return true
    }

    protected open fun isListPage(snapshot: PageSnapshot): Boolean {
        if (PageParseSupport.looksLikeListPage(snapshot)) return true
        val joined = snapshot.joined
        // "全部订单 + 待评价/退款/待收货"这类列表页结构
        val listCombos = listOf(
            listOf("全部订单", "待评价"), listOf("全部订单", "待收货"), listOf("我的订单", "全部"),
            listOf("订单列表", "全部"), listOf("待收货", "待评价", "退款"),
        )
        if (listCombos.any { combo -> combo.all { joined.contains(it) } }) return true
        val statusWord = profile.successTitles.any { joined.contains(it) } || joined.contains("交易失败")
        val amounts = PageParseSupport.collectAmounts(snapshot.lines).size
        // 列表行里"金额+状态"挤在同一长文本里；结果页是短标题节点
        return statusWord && amounts >= 2 && snapshot.lines.any { it.length > 40 && profile.successTitles.any { kw -> it.contains(kw) } }
    }

    protected open fun isRefundPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (profile.refundMarkers.any { joined.contains(it) }) return true
        return joined.contains("退款") && (joined.contains("退款金额") || joined.contains("退款去向") ||
            joined.contains("退款到账") || joined.contains("已全额退款"))
    }

    protected open fun isSuccessPage(snapshot: PageSnapshot): Boolean {
        val anchor = successAnchor(snapshot) ?: return false
        val (index, titleLike) = anchor
        if (titleLike) return true
        // 非标题位置：只有页面同时具备结果页锚点（付款方式/实付款/时间）才认
        return hasResultAnchors(snapshot) && index <= (profile.titleAnchorMaxIndex ?: Int.MAX_VALUE)
    }

    protected open fun isDetailPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (profile.detailTitles.none { joined.contains(it) }) return false
        val body = joined.contains("订单编号") || joined.contains("交易单号") || joined.contains("支付时间") ||
            joined.contains("付款时间") || joined.contains("下单时间") || joined.contains("交易时间") ||
            joined.contains("付款方式") || joined.contains("支付方式") || joined.contains("实付款")
        return body
    }

    /**
     * 结果页锚点：返回 (行下标, 是否标题式)，找不到返回 null。
     * [CheckoutProfile.titleAnchorMaxIndex] 约束标题位置（美团/美团外卖的订单进度条把"支付成功"埋在长句里）。
     */
    private fun successAnchor(snapshot: PageSnapshot): Pair<Int, Boolean>? {
        val keywords = profile.successTitles + profile.autoDebitMarkers
        val index = snapshot.lines.indexOfFirst { line -> keywords.any { line.contains(it) } }
        if (index < 0) return null
        val line = snapshot.lines[index]
        val titleLike = PageParseSupport.isStatusTitleNode(line, keywords)
        val withinLimit = profile.titleAnchorMaxIndex == null || index <= profile.titleAnchorMaxIndex
        return if (titleLike || withinLimit) index to titleLike else null
    }

    protected open fun hasResultAnchors(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        return listOf(
            "付款方式", "支付方式", "实付款", "实际支付", "已支付", "支付时间", "下单时间", "创建时间",
            "查看订单", "完成", "返回商家", "订单编号",
        ).any { joined.contains(it) }
    }

    // ---------- 候选构造 ----------

    protected open fun successCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val anchor = successAnchor(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "$label 结果页无锚点")
        val anchorIdx = anchor.first
        PageParseSupport.nonResultPageReason(snapshot, anchorIdx)?.let {
            return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "$label 页形状:$it")
        }
        val amountText = successAmount(snapshot, anchorIdx)
            ?: flow.confirmAmountCents?.let { Money.formatCents(it) }
            ?: return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "$label 结果页无金额")
        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "$label 结果页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "$label 结果页金额为零")
        }

        val instrument = instrumentOf(snapshot, flow)
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("co-success|${profile.label}|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) {
            flow.noteSuccess(instrument)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "$label 结果页重复事件")
        }
        flow.noteSuccess(instrument)

        val counterparty = merchantOf(snapshot, anchorIdx, flow)
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        val scene = if (profile.autoDebitMarkers.any { snapshot.joined.contains(it) }) {
            PageScene.AUTO_DEBIT
        } else {
            PageScene.PAYMENT_SUCCESS
        }
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.EXPENSE,
                amountCents = amountCents,
                counterparty = counterparty,
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = txTime,
                scene = scene,
                tradeState = TradeState.SUCCESS,
                confidence = if (counterparty.isNotBlank() && anchor.second) {
                    ParseConfidence.HIGH
                } else {
                    ParseConfidence.MEDIUM
                },
                sourceLabel = label,
                ruleName = "$label·支付结果页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = orderId,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.EXPENSE, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    protected open fun detailCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val joined = snapshot.joined
        val state = detailTradeState(joined)
        if (state != TradeState.SUCCESS) {
            flow.noteScene(PageScene.TRANSACTION_DETAIL, state)
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "$label 订单详情页未完成")
        }
        val lines = snapshot.lines
        val amountText = PageParseSupport.pickPaidAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "$label 详情页无金额")
        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.TRANSACTION_DETAIL, state, "$label 详情页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "$label 详情页金额为零")
        // 旧订单详情页（交易时间超过 24 小时）不记：用户在翻历史订单
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        if (PageParseSupport.isStaleDetail(snapshot, txTime)) {
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "$label 旧订单详情页")
        }
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("co-detail|${profile.label}|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "$label 详情页重复事件")

        val instrument = instrumentOf(snapshot, flow)
        flow.noteSuccess(instrument)
        val counterparty = merchantOf(snapshot, -1, flow)
        return PageParseResult.Candidate(
            PageCandidate(
                type = if (joined.contains("收入") || joined.contains("退款")) TxType.INCOME else TxType.EXPENSE,
                amountCents = amountCents,
                counterparty = counterparty,
                paymentMethod = instrument?.label,
                instrument = instrument,
                txTime = txTime,
                scene = PageScene.TRANSACTION_DETAIL,
                tradeState = state,
                confidence = ParseConfidence.HIGH,
                sourceLabel = label,
                ruleName = "$label·订单详情页",
                note = instrument?.let { "支付方式 ${it.label}" }.orEmpty(),
                orderId = orderId,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.EXPENSE, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    protected open fun refundCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val amountText = PageParseSupport.pickRefundAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "$label 退款页无金额")
        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.REFUND_SUCCESS, TradeState.REFUND, "$label 退款页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "$label 退款页金额为零")
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("co-refund|${profile.label}|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "$label 退款页重复事件")
        flow.noteScene(PageScene.REFUND_SUCCESS, TradeState.REFUND)

        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.INCOME,
                amountCents = amountCents,
                counterparty = merchantOf(snapshot, -1, flow),
                txTime = txTime,
                scene = PageScene.REFUND_SUCCESS,
                tradeState = TradeState.REFUND,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·退款页",
                orderId = orderId,
                isRefund = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.INCOME, true, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    protected open fun receiveCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val amountText = PageParseSupport.pickPaidAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "$label 收款页无金额")
        val amountCents = Money.parseCents(amountText) ?: return PageParseResult.SceneOnly(
            PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "$label 收款页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "$label 收款页金额为零")
        if (flow.markEmitted("co-receive|${profile.label}|$amountCents|${snapshot.fingerprint}") < 0) {
            return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "$label 收款页重复事件")
        }
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.INCOME,
                amountCents = amountCents,
                counterparty = PageParseSupport.valueAfterLabel(snapshot.lines, listOf("付款方", "付款人", "来自")).orEmpty(),
                txTime = txTime,
                scene = PageScene.RECEIVE_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·收款页",
                orderId = PageParseSupport.orderIdFrom(snapshot.lines),
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.INCOME, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    // ---------- 字段提取 ----------

    /**
     * 结果页金额：锚点节点内的金额 → 锚点**前后**的独立金额节点 → 实付类标签 → 通用优先级。
     * 电商结果页的版式差异大（"支付成功 ¥19.38" / "¥19.38 支付成功" / "已支付，多多支付已减¥2"），
     * 所以锚点前后都要看，但一律排除优惠/余额/运费上下文。
     */
    protected open fun successAmount(snapshot: PageSnapshot, anchorIdx: Int): String? {
        PageParseSupport.amountInAnchor(snapshot.lines[anchorIdx])?.let { return it }
        val window = listOf(anchorIdx - 1, anchorIdx + 1, anchorIdx - 2, anchorIdx + 2)
        for (i in window) {
            val line = snapshot.lines.getOrNull(i) ?: continue
            val kind = PageParseSupport.classifyAmount(line)
            if (kind != PageParseSupport.AmountKind.UNKNOWN && kind != PageParseSupport.AmountKind.PAID) continue
            PageParseSupport.amountValuesIn(line).firstOrNull()?.let { return it }
            PageParseSupport.yuanSuffixedAmount(line)?.let { return it }
        }
        PageParseSupport.amountAfterLabel(
            snapshot, listOf("实付款", "实际支付", "付款金额", "支付金额", "订单金额"),
        )?.let { return it }
        return PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
    }

    protected open fun detailTradeState(joined: String): TradeState = when {
        PageParseSupport.refundKeywords.any { joined.contains(it) } -> TradeState.REFUND
        PageParseSupport.failureKeywords.any { joined.contains(it) } -> TradeState.FAILED
        PageParseSupport.cancelKeywords.any { joined.contains(it) } -> TradeState.CANCELLED
        joined.contains("待付款") || joined.contains("待支付") || joined.contains("处理中") -> TradeState.PENDING
        joined.contains("支付成功") || joined.contains("交易成功") || joined.contains("已支付") ||
            joined.contains("已完成") || joined.contains("交易完成") ||
            joined.contains("付款方式") || joined.contains("实付款") -> TradeState.SUCCESS
        else -> TradeState.UNKNOWN
    }

    /**
     * 商户提取：标签优先 → 锚点后像名称的节点 → 收银台页记下的商户 → 空。
     * [CheckoutProfile.merchantIsSourceLabel]（美团外卖）直接取来源显示名：它的结果页不展示商户，
     * 兜底扫描只会抓到订单号或按钮文案。
     */
    protected open fun merchantOf(snapshot: PageSnapshot, anchorIdx: Int, flow: PageFlow): String {
        if (profile.merchantIsSourceLabel) return label
        PageParseSupport.valueAfterLabel(snapshot.lines, profile.merchantLabels)?.let {
            if (PageParseSupport.looksLikeMerchant(it)) return it
        }
        val anchorCandidates = profile.successTitles + profile.detailTitles + profile.autoDebitMarkers
        PageParseSupport.guessCounterparty(snapshot.lines, anchorCandidates)?.let { return it }
        if (anchorIdx >= 0) {
            val end = minOf(snapshot.lines.size, anchorIdx + 6)
            for (i in anchorIdx + 1 until end) {
                val t = snapshot.lines[i]
                if (PageParseSupport.looksLikeMerchant(t)) return t.take(30)
            }
        }
        return flow.confirmCounterparty.orEmpty()
    }

    /** 支付方式：标签值 → 独立的钱包类短节点 → 收银台页记下的 */
    protected open fun instrumentOf(snapshot: PageSnapshot, flow: PageFlow): PaymentInstrument? {
        PageParseSupport.valueAfterLabel(snapshot.lines, PageParseSupport.paymentMethodLabels)?.let { raw ->
            PageParseSupport.cleanPaymentMethod(raw)?.let { cleaned ->
                PaymentInstrumentParser.parse(cleaned, label)?.let { return it }
            }
        }
        for (line in snapshot.lines) {
            val t = line.trim()
            if (t.length > 16) continue
            PaymentInstrumentParser.parse(t, label)?.let { return it }
        }
        return flow.confirmInstrument
    }

    protected open fun confirmAmount(snapshot: PageSnapshot): Long? {
        val value = PageParseSupport.amountAfterLabel(
            snapshot, listOf("实付款", "付款金额", "订单金额", "合计", "金额"),
        ) ?: PageParseSupport.pickPaidAmount(snapshot) ?: return null
        return Money.parseCents(value)?.takeIf { it > 0 }
    }

    protected open fun confirmCounterparty(snapshot: PageSnapshot): String? =
        PageParseSupport.valueAfterLabel(snapshot.lines, profile.merchantLabels)?.takeIf { PageParseSupport.looksLikeMerchant(it) }

    protected fun anchorOrNull(flow: PageFlow, snapshot: PageSnapshot): Long? {
        val anchor = flow.anchorTime ?: return null
        val zone = java.time.ZoneId.systemDefault()
        val d1 = java.time.Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
        val d2 = java.time.Instant.ofEpochMilli(snapshot.captureTime).atZone(zone).toLocalDate()
        return if (d1 == d2) anchor else null
    }
}

/**
 * 一个电商/外卖应用的页面画像：包名、关键词与版式约束。
 *
 * 包名一律取自 [com.controleco.app.capture.CaptureAppCatalog] 的监听目录（不得凭印象编造）。
 */
data class CheckoutProfile(
    val packageNames: Set<String>,
    val label: String,
    val successTitles: List<String>,
    val detailTitles: List<String>,
    val confirmMarkers: List<String>,
    val refundMarkers: List<String> = listOf("退款成功", "退款到账", "退款金额", "已全额退款"),
    val orderCancelledMarkers: List<String> = listOf("订单已取消", "订单已关闭", "交易关闭"),
    val orderPendingMarkers: List<String> = emptyList(),
    val receiveAnchors: List<String> = emptyList(),
    val autoDebitMarkers: List<String> = listOf("自动扣款成功", "先用后付自动扣款成功", "极速支付成功"),
    val merchantLabels: List<String> = PageParseSupport.counterpartyLabels,
    /** 结果页不展示商户：商户直接取来源显示名（美团外卖实测如此） */
    val merchantIsSourceLabel: Boolean = false,
    /** 结果页标题允许的最大下标（订单进度条会把状态词埋进长句，见美团/美团外卖） */
    val titleAnchorMaxIndex: Int? = null,
)

/** 京东 / 京东支付 / 京东金融（同一套收银台与订单详情版式） */
object JdPageParser : CheckoutPageParser(
    CheckoutProfile(
        packageNames = setOf("com.jingdong.app.mall", "com.jd.jr.jdpay", "com.jd.jrapp"),
        label = "京东",
        successTitles = listOf("支付成功", "付款成功", "下单成功", "交易成功", "已支付", "支付完成"),
        detailTitles = listOf("订单详情", "账单详情", "交易详情", "支付详情"),
        confirmMarkers = listOf("提交订单", "立即支付", "确认支付", "请输入支付密码", "去支付", "确认付款", "京东支付"),
        orderPendingMarkers = listOf("待付款", "订单待支付", "等待付款"),
        merchantLabels = listOf("商家名称", "店铺名称", "收货人") + PageParseSupport.counterpartyLabels,
    ),
)

/** 淘宝 / 天猫 */
object TaobaoPageParser : CheckoutPageParser(
    CheckoutProfile(
        packageNames = setOf("com.taobao.taobao", "com.tmall.wireless"),
        label = "淘宝",
        successTitles = listOf("支付成功", "付款成功", "交易成功", "已支付", "支付完成"),
        detailTitles = listOf("订单详情", "交易详情", "账单详情"),
        confirmMarkers = listOf("提交订单", "立即支付", "确认支付", "请输入支付密码", "付款", "去支付"),
        orderPendingMarkers = listOf("等待付款", "待付款", "立即付款"),
        merchantLabels = listOf("店铺名称", "卖家昵称") + PageParseSupport.counterpartyLabels,
    ),
)

/** 拼多多 */
object PinduoduoPageParser : CheckoutPageParser(
    CheckoutProfile(
        packageNames = setOf("com.xunmeng.pinduoduo"),
        label = "拼多多",
        // 拼单成功/免拼成功也是真实的付款完成状态（拼多多特有）
        successTitles = listOf("支付成功", "付款成功", "拼单成功", "免拼成功", "下单成功", "已支付"),
        detailTitles = listOf("订单详情", "账单详情", "交易详情"),
        confirmMarkers = listOf("提交订单", "立即支付", "去支付", "确认支付", "免密支付", "请输入支付密码"),
        orderPendingMarkers = listOf("待支付", "待付款", "立即支付"),
        merchantLabels = listOf("店铺名称", "商家名称") + PageParseSupport.counterpartyLabels,
    ),
)

/**
 * 抖音 / 抖音直播 / 抖音商城。
 * 抖音的结果页与订单详情都用"实付款/支付方式/订单编号"，且支付结果页锚点靠前。
 */
object DouyinPageParser : CheckoutPageParser(
    CheckoutProfile(
        packageNames = setOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.live", "com.ss.android.ugc.livelite"),
        label = "抖音",
        successTitles = listOf("支付成功", "付款成功", "下单成功", "交易成功", "已支付"),
        detailTitles = listOf("订单详情", "账单详情", "交易详情", "支付详情"),
        confirmMarkers = listOf("提交订单", "立即支付", "确认支付", "去支付", "抖音支付", "请输入支付密码"),
        orderPendingMarkers = listOf("待付款", "待支付", "支付订单"),
        merchantLabels = listOf("店铺名称", "商家名称", "商品名称") + PageParseSupport.counterpartyLabels,
    ),
)

/**
 * 美团 / 美团外卖。
 *
 * 与电商家族的差别（真机结论）：
 * - 月付支付的结果页标题是"交易成功"而不是"支付成功"，两者都要认；
 * - 订单列表/订单进度页也会出现"支付成功"（进度步骤），所以结果页标题必须**靠前且像标题**
 *   （[CheckoutProfile.titleAnchorMaxIndex] = 2，与旧实现的 meituanRejectReason 同口径）；
 * - 美团外卖的结果页不展示商户（商户列直接取来源显示名）。
 */
object MeituanPageParser : CheckoutPageParser(
    CheckoutProfile(
        packageNames = setOf("com.sankuai.meituan", NotificationRules.MEITUAN_TAKEOUT),
        label = "美团",
        successTitles = listOf("支付成功", "交易成功", "付款成功", "下单成功", "已支付"),
        detailTitles = listOf("订单详情", "账单详情", "交易详情"),
        confirmMarkers = listOf("提交订单", "立即支付", "确认支付", "去支付", "极速支付", "月付", "请输入支付密码"),
        orderPendingMarkers = listOf("待付款", "待支付", "立即支付"),
        merchantLabels = listOf("商家名称", "店铺名称") + PageParseSupport.counterpartyLabels,
        titleAnchorMaxIndex = 2,
    ),
) {
    /**
     * 美团外卖的商户固定为来源名"美团外卖"（真机确认：结果页没有商户节点，
     * 兜底扫描只会抓到订单号或按钮文案）；美团 App 本身的订单详情有商家名称，正常提取。
     */
    override fun merchantOf(snapshot: PageSnapshot, anchorIdx: Int, flow: PageFlow): String =
        if (snapshot.packageName == NotificationRules.MEITUAN_TAKEOUT) "美团外卖"
        else super.merchantOf(snapshot, anchorIdx, flow)
}

/** 饿了么 */
object ElemePageParser : CheckoutPageParser(
    CheckoutProfile(
        packageNames = setOf("me.ele"),
        label = "饿了么",
        successTitles = listOf("支付成功", "付款成功", "下单成功", "交易成功", "已支付"),
        detailTitles = listOf("订单详情", "账单详情", "交易详情"),
        confirmMarkers = listOf("提交订单", "立即支付", "确认支付", "去支付", "支付宝支付", "微信支付"),
        orderPendingMarkers = listOf("待付款", "待支付"),
        merchantLabels = listOf("商家名称", "店铺名称") + PageParseSupport.counterpartyLabels,
    ),
)
