package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType

/**
 * 微信（com.tencent.mm）页面解析器。
 *
 * 覆盖场景（真机与规则推演样本，见 docs/自动记账捕获机制.md 的覆盖矩阵）：
 * - 支付成功页：`支付成功` + `付款方式`/`完成`/`返回商家`（商户是标题后第一个像名称的节点）
 * - 账单详情/交易详情页：`账单详情`+`当前状态`+`交易单号`（字段最全：商户全称/支付方式/时间/单号）
 * - 收款到账页：`已收钱`/`收款到账`/`已存入零钱`/`待确认收款`（收入方向）
 * - 转账页：`转账成功`/`已转入`/`已转出`/`零钱提现`（转出=支出、转入=收入）
 * - 红包页：`红包详情`/`已领取`/`领取了…的红包`
 * - 退款页：`退款成功`/`已退款`/`退款到账`（收入 + 退款标记）
 *
 * 明确不生成候选的页面：收银台/密码键盘（记录为流程上下文）、失败页、取消页、
 * 零钱明细/账单列表（列表页）、聊天与朋友圈（主界面壳）。
 *
 * 时间锚点：收银台/确认页出现时记下时刻，成功页没有交易时间时用它（比捕获时刻更接近真实支付时间）。
 */
object WechatPageParser : AppPageParser {

    override val packages: Set<String> = setOf(NotificationRules.WECHAT)
    override val label: String = "微信"

    /** 微信账单详情页标题 */
    private val detailTitles = listOf("账单详情", "交易详情", "转账详情", "收款详情", "红包详情")

    /** 成功页锚点（支付成功页的大标题） */
    private val successTitles = listOf("支付成功", "付款成功", "支付完成", "付款完成", "已支付")

    /** 收款到账锚点 */
    private val receiveAnchors = listOf("已收钱", "收款到账", "已存入零钱", "待确认收款", "收款成功", "已收款")

    /** 收款页排除：付款方视角的"对方已收钱"、待收款、明细列表 */
    private val receiveExcludes = listOf("对方已收钱", "待收钱", "等待对方", "零钱明细", "交易明细", "账单列表", "收支统计")

    /** 转账/充值提现锚点 */
    private val transferAnchors = listOf("转账成功", "已转入", "已转出", "零钱提现", "充值成功", "提现成功", "到账成功", "零钱通")

    /** 明细列表标题：命中即视为列表页（不是单笔结果页） */
    private val listTitles = listOf("零钱明细", "账单", "交易记录", "收支统计", "月账单")

    /** 密码键盘/收银台：记录流程锚点，不生成候选 */
    private val confirmMarkers = listOf(
        "请输入支付密码", "使用密码", "使用指纹", "指纹支付", "免密支付", "确认付款", "向商家付款",
        "付款金额", "确认支付", "立即支付",
    )

    override fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        if (lines.isEmpty()) return PageParseResult.NotATradePage
        val joined = snapshot.joined

        // 1) 聊天/通讯录/朋友圈主界面壳：不是交易页
        if (isWechatShell(snapshot)) return PageParseResult.NotATradePage

        // 2) 失败/取消：记录状态但不生成候选（失败与取消绝不能记账）
        if (PageParseSupport.failureKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_FAILED, TradeState.FAILED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_FAILED, TradeState.FAILED, "微信支付失败页")
        }
        if (PageParseSupport.cancelKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED, "微信支付取消页")
        }

        // 3) 收银台/密码键盘：记录流程上下文（金额/商户/支付方式/时间锚点），不生成候选
        if (isConfirmPage(snapshot)) {
            flow.noteConfirmPage(
                amountCents = confirmAmount(snapshot),
                counterparty = null,
                instrument = confirmInstrument(snapshot),
                atMillis = snapshot.captureTime,
            )
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CONFIRM, TradeState.PENDING, "微信收银台/密码键盘")
        }

        // 4) 明细/账单列表页：不是单笔结果页
        if (isListPage(snapshot)) {
            flow.noteScene(PageScene.LIST, TradeState.UNKNOWN)
            return PageParseResult.SceneOnly(PageScene.LIST, TradeState.UNKNOWN, "微信明细列表页")
        }

        val detailTitle = detailTitles.firstOrNull { joined.contains(it) }
        val hasDetailBody = joined.contains("当前状态") || joined.contains("交易状态") ||
            joined.contains("交易单号") || joined.contains("商户全称") || joined.contains("收款方")

        // 5) 退款页（独立资金流：收入 + 退款标记）
        if (isRefundPage(snapshot)) {
            return refundCandidate(snapshot, flow, detailPage = detailTitle != null || hasDetailBody)
        }

        // 6) 账单详情/交易详情页：字段最全
        if (detailTitle != null && hasDetailBody) {
            return detailCandidate(snapshot, flow, detailTitle)
        }

        // 7) 支付成功页
        if (successTitles.any { joined.contains(it) } && !joined.contains("当前状态")) {
            return successCandidate(snapshot, flow)
        }

        // 8) 收款到账页
        if (receiveAnchors.any { joined.contains(it) } && receiveExcludes.none { joined.contains(it) }) {
            return receiveCandidate(snapshot, flow)
        }

        // 9) 转账/充值/提现页
        if (transferAnchors.any { joined.contains(it) }) {
            return transferCandidate(snapshot, flow)
        }

        // 10) 红包页
        if (isRedPacketPage(snapshot)) return redPacketCandidate(snapshot, flow)

        return PageParseResult.NotATradePage
    }

    // ---------- 场景判定 ----------

    /** 聊天列表/通讯录/发现页壳：底栏三件套同时出现，或明显的会话列表特征 */
    private fun isWechatShell(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        val shellTabs = listOf("微信", "通讯录", "发现", "我").count { joined.contains(it) }
        if (shellTabs >= 3 && !joined.contains("支付成功") && !joined.contains("账单详情")) return true
        if (joined.contains("朋友圈") && joined.contains("扫一扫") && joined.contains("收付款")) return true
        return false
    }

    private fun isConfirmPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        val marker = confirmMarkers.any { joined.contains(it) }
        if (!marker) return false
        // "付款金额"也出现在账单详情页 → 详情页优先（有交易单号/当前状态就不是收银台）
        if (joined.contains("交易单号") || joined.contains("当前状态")) return false
        if (successTitles.any { joined.contains(it) }) return false
        return true
    }

    private fun isListPage(snapshot: PageSnapshot): Boolean {
        if (PageParseSupport.looksLikeListPage(snapshot)) return true
        val joined = snapshot.joined
        val titleHit = listTitles.any { joined.contains(it) }
        if (!titleHit) return false
        // 列表页特征：出现多笔金额或分页/筛选控件；详情页有"当前状态/交易单号"
        val amountCount = PageParseSupport.collectAmounts(snapshot.lines).size
        val listish = joined.contains("筛选") || joined.contains("全部") || joined.contains("本月") || amountCount >= 3
        return listish && !joined.contains("交易单号")
    }

    private fun isRefundPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (joined.contains("退款成功") || joined.contains("已全额退款") || joined.contains("退款到账")) return true
        val refundMark = joined.contains("退款") || joined.contains("已退款")
        val refundBody = joined.contains("退款金额") || joined.contains("退款单号") || joined.contains("退款状态") ||
            joined.contains("退款方式")
        return refundMark && refundBody
    }

    private fun isRedPacketPage(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (joined.contains("红包详情") || joined.contains("微信红包")) return true
        return (joined.contains("已领取") || joined.contains("领取了") || joined.contains("已存入零钱")) &&
            joined.contains("红包")
    }

    // ---------- 候选构造 ----------

    private fun successCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val anchorIdx = lines.indexOfFirst { line -> successTitles.any { line.contains(it) } }
        if (anchorIdx < 0) {
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "微信成功页无锚点")
        }
        val shapeReject = PageParseSupport.nonResultPageReason(snapshot, anchorIdx)
        if (shapeReject != null) {
            return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "微信页形状:$shapeReject")
        }
        // 支付成功页的"支付成功"必须像标题（短节点），否则是消息/进度条里的状态词
        val titleLike = PageParseSupport.isStatusTitleNode(lines[anchorIdx], successTitles)
        if (!titleLike && !hasResultPageAnchors(snapshot)) {
            return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "微信成功词非标题位置")
        }

        val amount = PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
            ?: PageParseSupport.amountAfterLabel(snapshot, listOf("付款金额", "金额"))
            ?: return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "微信成功页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amount) ?: return PageParseResult.SceneOnly(
            PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "微信成功页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "微信成功页金额为零")
        }

        val pageKey = "wx-success|$amountCents|${snapshot.fingerprint}"
        val seq = flow.markEmitted(pageKey)
        if (seq < 0) {
            flow.noteSuccess(null)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_SUCCESS, TradeState.SUCCESS, "微信成功页重复事件")
        }
        flow.noteSuccess(instrumentOf(snapshot, flow))

        val instrument = instrumentOf(snapshot, flow)
        val counterparty = merchantOf(snapshot, anchorIdx)
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        val note = noteOf(instrument, txTime)

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
                ruleName = "$label·支付成功页",
                note = note,
                orderId = PageParseSupport.orderIdFrom(lines),
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
        val anchorIdx = lines.indexOfFirst { line -> detailTitles.any { line.contains(it) } }
        val state = detailTradeState(joined)
        // 未完成的详情页（待付款/处理中）不是账
        if (state != TradeState.SUCCESS && state != TradeState.REFUND) {
            flow.noteScene(PageScene.TRANSACTION_DETAIL, state)
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "微信详情页未完成状态")
        }
        if (PageParseSupport.looksLikeOrderPendingPage(snapshot)) {
            flow.noteScene(PageScene.ORDER_PENDING, TradeState.PENDING)
            return PageParseResult.SceneOnly(PageScene.ORDER_PENDING, TradeState.PENDING, "微信订单待支付页")
        }
        val amount = detailAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "微信详情页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amount) ?: return PageParseResult.SceneOnly(
            PageScene.TRANSACTION_DETAIL, state, "微信详情页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "微信详情页金额为零")
        }

        // 旧账单详情页（交易时间超过 24 小时）不记：用户在翻历史账单，不是刚发生的交易
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        if (PageParseSupport.isStaleDetail(snapshot, txTime)) {
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "微信旧账单详情页")
        }
        val orderId = PageParseSupport.orderIdFrom(lines)
        val pageKey = "wx-detail|$amountCents|${orderId ?: snapshot.fingerprint}"
        val seq = flow.markEmitted(pageKey)
        if (seq < 0) {
            return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "微信详情页重复事件")
        }

        val instrument = instrumentOf(snapshot, flow)
        flow.noteSuccess(instrument)
        val isRefund = state == TradeState.REFUND
        val direction = detailDirection(joined, isRefund)

        return PageParseResult.Candidate(
            PageCandidate(
                type = direction,
                amountCents = amountCents,
                counterparty = merchantOf(snapshot, anchorIdx),
                paymentMethod = instrument?.label ?: PageParseSupport.cleanPaymentMethod(
                    PageParseSupport.valueAfterLabel(lines, PageParseSupport.paymentMethodLabels).orEmpty(),
                ),
                instrument = instrument,
                txTime = txTime,
                scene = PageScene.TRANSACTION_DETAIL,
                tradeState = state,
                confidence = ParseConfidence.HIGH,
                sourceLabel = label,
                ruleName = "$label·$title",
                note = noteOf(instrument, txTime),
                orderId = orderId,
                isRefund = isRefund,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, direction, isRefund, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun receiveCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val anchorIdx = lines.indexOfFirst { line -> receiveAnchors.any { line.contains(it) } }
        val amount = PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
            ?: PageParseSupport.amountAfterLabel(lines, listOf("收款金额", "金额"))
            ?: return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amount) ?: return PageParseResult.SceneOnly(
            PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页金额为零")
        }
        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("wx-receive|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页重复事件")
        flow.noteSuccess(null)

        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.INCOME,
                amountCents = amountCents,
                counterparty = payerOf(snapshot),
                instrument = null,
                txTime = txTime,
                scene = PageScene.RECEIVE_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·收款到账页",
                orderId = orderId,
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
        val incoming = joined.contains("已转入") || joined.contains("转入到零钱通") ||
            (joined.contains("转账") && joined.contains("已收款"))
        val outgoing = joined.contains("已转出") || joined.contains("零钱提现") ||
            (joined.contains("转账成功") && !incoming)
        if (!incoming && !outgoing) {
            return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.UNKNOWN, "微信转账页方向不明")
        }
        val anchorIdx = lines.indexOfFirst { line -> transferAnchors.any { line.contains(it) } }
        val amount = PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
            ?: return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "微信转账页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amount) ?: return PageParseResult.SceneOnly(
            PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "微信转账页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "微信转账页金额为零")
        }
        val type = if (incoming) TxType.INCOME else TxType.EXPENSE
        val seq = flow.markEmitted("wx-transfer|$type|$amountCents|${snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.TRANSFER_SUCCESS, TradeState.SUCCESS, "微信转账页重复事件")
        flow.noteSuccess(instrumentOf(snapshot, flow))

        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = type,
                amountCents = amountCents,
                counterparty = merchantOf(snapshot, anchorIdx),
                paymentMethod = null,
                txTime = txTime,
                scene = PageScene.TRANSFER_SUCCESS,
                tradeState = TradeState.SUCCESS,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·转账${if (incoming) "到账页" else "成功页"}",
                orderId = PageParseSupport.orderIdFrom(lines),
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, type, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun redPacketCandidate(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val lines = snapshot.lines
        val joined = snapshot.joined
        val income = joined.contains("已领取") || joined.contains("已存入零钱") || joined.contains("已收钱") ||
            joined.contains("收到") || joined.contains("你领取了")
        val outgoing = joined.contains("已发出") || joined.contains("待领取") || joined.contains("发出红包")
        val type = when {
            income -> TxType.INCOME
            outgoing -> TxType.EXPENSE
            else -> return PageParseResult.SceneOnly(PageScene.RED_PACKET, TradeState.UNKNOWN, "微信红包页方向不明")
        }
        val anchorIdx = lines.indexOfFirst { line -> line.contains("红包") }
        val amount = PageParseSupport.pickPaidAmount(snapshot, anchorIdx)
            ?: return PageParseResult.SceneOnly(PageScene.RED_PACKET, TradeState.UNKNOWN, "微信红包页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amount) ?: return PageParseResult.SceneOnly(
            PageScene.RED_PACKET, TradeState.UNKNOWN, "微信红包页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.RED_PACKET, TradeState.UNKNOWN, "微信红包页金额为零")

        val seq = flow.markEmitted("wx-redpacket|$type|$amountCents|${snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.RED_PACKET, TradeState.SUCCESS, "微信红包页重复事件")

        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = type,
                amountCents = amountCents,
                counterparty = payerOf(snapshot),
                txTime = txTime,
                scene = PageScene.RED_PACKET,
                tradeState = TradeState.SUCCESS,
                confidence = ParseConfidence.MEDIUM,
                sourceLabel = label,
                ruleName = "$label·${if (type == TxType.INCOME) "红包收款" else "发红包"}",
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, type, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun refundCandidate(snapshot: PageSnapshot, flow: PageFlow, detailPage: Boolean): PageParseResult {
        val lines = snapshot.lines
        val amount = PageParseSupport.pickRefundAmount(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "微信退款页无金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amount) ?: return PageParseResult.SceneOnly(
            PageScene.REFUND_SUCCESS, TradeState.REFUND, "微信退款页金额非法",
        )
        if (amountCents <= 0) return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "微信退款页金额为零")

        val orderId = PageParseSupport.orderIdFrom(lines)
        val seq = flow.markEmitted("wx-refund|$amountCents|${orderId ?: snapshot.fingerprint}")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.REFUND_SUCCESS, TradeState.REFUND, "微信退款页重复事件")
        flow.noteScene(PageScene.REFUND_SUCCESS, TradeState.REFUND)

        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.INCOME,
                amountCents = amountCents,
                counterparty = PageParseSupport.valueAfterLabel(lines, listOf("商户全称", "商户名称", "退款方", "退款去向"))
                    ?: PageParseSupport.guessCounterparty(lines, listOf("退款成功", "已退款", "退款到账")).orEmpty(),
                instrument = null,
                txTime = txTime,
                scene = PageScene.REFUND_SUCCESS,
                tradeState = TradeState.REFUND,
                confidence = if (detailPage) ParseConfidence.HIGH else ParseConfidence.MEDIUM,
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

    /** 详情页金额：优先页面顶部的独立金额节点（大字），其次"金额"标签，最后通用优先级 */
    private fun detailAmount(snapshot: PageSnapshot): String? {
        val head = snapshot.lines.take(8)
        for (line in head) {
            val t = line.trim()
            if (!(t.startsWith("¥") || t.startsWith("￥"))) continue
            val kind = PageParseSupport.classifyAmount(t)
            if (kind == PageParseSupport.AmountKind.PAID || kind == PageParseSupport.AmountKind.UNKNOWN) {
                PageParseSupport.amountValuesIn(t).firstOrNull()?.let { return it }
            }
        }
        return PageParseSupport.pickPaidAmount(snapshot)
    }

    private fun detailTradeState(joined: String): TradeState = when {
        PageParseSupport.refundKeywords.any { joined.contains(it) } -> TradeState.REFUND
        PageParseSupport.failureKeywords.any { joined.contains(it) } -> TradeState.FAILED
        PageParseSupport.cancelKeywords.any { joined.contains(it) } -> TradeState.CANCELLED
        joined.contains("待付款") || joined.contains("待支付") || joined.contains("处理中") ||
            joined.contains("等待付款") -> TradeState.PENDING
        joined.contains("当前状态") || joined.contains("交易状态") || joined.contains("支付成功") ||
            joined.contains("已支付") || joined.contains("交易成功") -> TradeState.SUCCESS
        else -> TradeState.UNKNOWN
    }

    private fun detailDirection(joined: String, isRefund: Boolean): TxType = when {
        isRefund -> TxType.INCOME
        joined.contains("收入") || joined.contains("已存入") -> TxType.INCOME
        else -> TxType.EXPENSE
    }

    /** 商户：标签优先，其次锚点后第一个像名称的节点 */
    private fun merchantOf(snapshot: PageSnapshot, anchorIdx: Int): String {
        PageParseSupport.valueAfterLabel(snapshot.lines, PageParseSupport.counterpartyLabels)
            ?.let { if (PageParseSupport.looksLikeMerchant(it)) return it }
        val anchorIdxSafe = if (anchorIdx >= 0) anchorIdx else -1
        val fromAnchor = PageParseSupport.guessCounterparty(
            snapshot.lines,
            listOf("支付成功", "付款成功", "账单详情", "交易详情", "转账成功", "已收钱", "红包详情"),
        )
        if (fromAnchor != null) return fromAnchor
        // 锚点已知但兜底失败时，退回锚点附近扫描
        if (anchorIdxSafe >= 0) {
            val end = minOf(snapshot.lines.size, anchorIdxSafe + 6)
            for (i in anchorIdxSafe + 1 until end) {
                val t = snapshot.lines[i]
                if (PageParseSupport.looksLikeMerchant(t) && !PageParseSupport.isNumberLike(t)) return t.take(30)
            }
        }
        return ""
    }

    /** 付款方（收款/红包页的"对方"）：来自 X / X 向你转账 / 领取了 X 的红包 */
    private fun payerOf(snapshot: PageSnapshot): String {
        val patterns = listOf(
            Regex("""来自\s*([^\s，,。;；]{1,12})"""),
            Regex("""([^\s，,。;；]{1,12})\s*向你转账"""),
            Regex("""领取了\s*([^\s，,。;；]{1,12})\s*的?红包"""),
            Regex("""([^\s，,。;；]{1,12})\s*的红包"""),
        )
        for (line in snapshot.lines) {
            for (pattern in patterns) {
                val v = pattern.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (v.isEmpty() || PageParseSupport.isAmountLikeNode(v)) continue
                if (v in listOf("转账", "红包", "微信红包", "微信支付")) continue
                return v.take(20)
            }
        }
        val labeled = PageParseSupport.valueAfterLabel(snapshot.lines, listOf("付款方", "付款人", "转账方", "来自"))
        return labeled?.take(20).orEmpty()
    }

    /** 支付方式：标签值优先（"付款方式"→下一节点），其次"付款方式，已选择 X"合并文本 */
    private fun instrumentOf(snapshot: PageSnapshot, flow: PageFlow): PaymentInstrument? {
        val lines = snapshot.lines
        val labeled = PageParseSupport.valueAfterLabel(lines, PageParseSupport.paymentMethodLabels)
        PageParseSupport.cleanPaymentMethod(labeled.orEmpty())?.let { cleaned ->
            PaymentInstrumentParser.parse(cleaned, label)?.let { return it }
            // 只有"零钱"这类别名才可能被清洗后丢失；原样再试一次
            PaymentInstrumentParser.parse(labeled.orEmpty(), label)?.let { return it }
        }
        for (line in lines) {
            if (!PageParseSupport.paymentMethodLabels.any { line.contains(it) }) continue
            val tail = line.substringAfter("已选择", "")
            if (tail.isBlank()) continue
            PageParseSupport.cleanPaymentMethod(tail)?.let { cleaned ->
                PaymentInstrumentParser.parse(cleaned, label)?.let { return it }
            }
        }
        // 结果页缺失支付方式时，用收银台页记下的（同一流程内）
        return flow.confirmInstrument
    }

    private fun confirmAmount(snapshot: PageSnapshot): Long? {
        val value = PageParseSupport.amountAfterLabel(snapshot, listOf("付款金额", "支付金额", "金额"))
            ?: PageParseSupport.pickPaidAmount(snapshot) ?: return null
        return com.controleco.app.util.Money.parseCents(value)?.takeIf { it > 0 }
    }

    private fun confirmInstrument(snapshot: PageSnapshot): PaymentInstrument? {
        val labeled = PageParseSupport.valueAfterLabel(snapshot.lines, PageParseSupport.paymentMethodLabels) ?: return null
        return PaymentInstrumentParser.parse(labeled, label)
    }

    /** 成功页有这些锚点之一，才认为它是真正的支付结果页（防止消息流里的"支付成功"预览） */
    private fun hasResultPageAnchors(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        return listOf("付款方式", "支付方式", "完成", "返回商家", "确认收款", "查看订单", "账单详情").any {
            joined.contains(it)
        }
    }

    /** 时间锚点：收银台时刻，只在与捕获时刻同一天时使用（跨天不锚，避免错记日期） */
    private fun anchorOrNull(flow: PageFlow, snapshot: PageSnapshot): Long? {
        val anchor = flow.anchorTime ?: return null
        val sameDay = sameLocalDay(anchor, snapshot.captureTime)
        return if (sameDay) anchor else null
    }

    private fun sameLocalDay(a: Long, b: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        val d1 = java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
        val d2 = java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        return d1 == d2
    }

    private fun noteOf(instrument: PaymentInstrument?, txTime: Long?): String =
        instrument?.let { "支付方式 ${it.label}" }.orEmpty()
}
