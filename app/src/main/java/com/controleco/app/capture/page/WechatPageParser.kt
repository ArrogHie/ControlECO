package com.controleco.app.capture.page

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.TxType

/**
 * 微信（com.tencent.mm）页面解析器。
 *
 * 覆盖场景（真机与规则推演样本，见 docs/自动记账捕获机制.md 的覆盖矩阵）：
 * - 支付成功页：`支付成功` + `付款方式`/`完成`/`返回商家`（商户是标题后第一个像名称的节点）
 * - 账单详情/交易详情页：`账单详情`+`当前状态`+`交易单号`（字段最全：商户全称/支付方式/时间/单号）；
 *   商户详情没有标题节点时，`当前状态/交易状态/交易单号/商户全称/商户单号/收款方` 至少两个 + 成功状态也认
 * - 收款到账页：`你已收款`/`已收钱`/`收款到账`/`已存入零钱`/`待确认收款`（收入方向，结果页标记 + 锚点金额）
 * - 转账页：`转账成功`/`已转入`/`已转出`/`零钱提现`（转出=支出、转入=收入）
 * - 红包领取页：`红包封面`/`回复表情到聊天`/`了解红包封面` + 非发红包/非已领取；
 *   **只生成"需要用户确认"的候选**，由悬浮卡询问用户，绝不自动入账
 * - 退款页：`退款成功`/`已退款`/`退款到账`（收入 + 退款标记）
 *
 * **红包自动记账已废弃**：聊天页里的"你领取了…红包"、红包详情/结果页都不再生成候选，
 * 避免历史聊天记录、重复打开旧红包、群红包总额与个人份额不一致时污染账本。
 * 转账仍保留自动记账：它有独立结果页、明确交易时间，可用时间/金额/流程身份做去重。
 *
 * 明确不生成候选的页面：收银台/密码键盘（记录为流程上下文）、失败页、取消页、
 * 零钱明细/账单列表（列表页）、聊天与朋友圈（主界面壳）。聊天页里的历史转账气泡
 * 带 `已收款`，必须先过聊天页闸门，绝不能把整页第一笔金额当成收款。
 *
 * 时间锚点：收银台/确认页出现时记下时刻，成功页没有交易时间时用它（比捕获时刻更接近真实支付时间）。
 */
object WechatPageParser : AppPageParser {

    override val packages: Set<String> = setOf(NotificationRules.WECHAT)
    override val label: String = "微信"

    /** 微信账单详情页标题 */
    private val detailTitles = listOf("账单详情", "交易详情", "转账详情", "收款详情")

    /** 详情页正文锚点：没有标题节点时，命中两个以上 + 成功/退款状态即视为详情页 */
    private val detailBodyAnchors = listOf("当前状态", "交易状态", "交易单号", "商户全称", "商户单号", "收款方")

    /** 成功页锚点（支付成功页的大标题） */
    private val successTitles = listOf("支付成功", "付款成功", "支付完成", "付款完成", "已支付")

    /** 收款到账锚点：只认结果页短语；裸 `已收款` 会命中聊天页的历史转账气泡，绝不能单列 */
    private val receiveAnchors = listOf("你已收款", "已收钱", "收款到账", "已存入零钱", "待确认收款", "收款成功")

    /** 收款页的结果页标记：至少两个同时出现才认为这是一张收款结果页 */
    private val receiveResultMarkers = listOf(
        "你已收款", "已收钱", "收款到账", "资金已存入零钱", "已存入零钱", "待确认收款", "收款成功",
        "收款时间", "账单详情", "零钱余额", "转入零钱通",
    )

    /** 收款页排除：付款方视角的"对方已收钱"、待收款、明细列表 */
    private val receiveExcludes = listOf("对方已收钱", "待收钱", "等待对方", "零钱明细", "交易明细", "账单列表", "收支统计")

    /** 转账/充值提现锚点 */
    private val transferAnchors = listOf("转账成功", "已转入", "已转出", "零钱提现", "充值成功", "提现成功", "到账成功", "零钱通")

    /**
     * 红包领取页（封面页）锚点：至少命中两个才认为可能是一张待领取红包页。
     * 该页面只生成"需要用户确认"的候选，不直接成单；金额缺失也允许提示，由用户补录。
     */
    private val redPacketClaimMarkers = listOf("红包封面", "回复表情到聊天", "了解红包封面")

    /** 发红包页：用户自己发红包，不是收入，绝不能弹确认卡 */
    private val redPacketSendMarkers = listOf(
        "塞钱进红包", "发红包", "发送红包", "添加表情",
        "未领取的红包，将于24小时后发起退款",
    )

    /** 已领取/红包详情：历史红包不再提示，避免重复打开旧红包时重复记账 */
    private val redPacketClaimedMarkers = listOf(
        "已领取", "你领取了", "领取了你的红包", "红包详情", "已存入零钱",
    )

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

        // 2) 红包领取页（封面页）：生成"需要用户确认"的候选，绝不自动入账。
        //    放在聊天页闸门之前：封面可能是叠加在聊天页上的大尺寸窗口；聊天页的历史“已领取”不能把真实封面挡掉。
        if (isRedPacketClaimPage(snapshot)) return redPacketClaimResult(snapshot, flow)

        // 3) 聊天会话页：历史转账/红包气泡里也有"已收款/已领取"，一律不是交易页。
        //    红包不再从聊天页自动成单；只由领取页弹悬浮卡交给用户确认。
        if (PageParseSupport.looksLikeChatPage(snapshot)) return PageParseResult.NotATradePage

        // 4) 其它红包页（详情/已领取/聊天结果/发红包）：一律不自动记账。
        //    放在失败/退款/详情判断之前，避免"红包详情+当前状态"被当成普通账单详情。
        if (isRedPacketSurface(snapshot)) {
            flow.noteScene(PageScene.RED_PACKET, TradeState.UNKNOWN)
            return PageParseResult.SceneOnly(PageScene.RED_PACKET, TradeState.UNKNOWN, "微信红包页不自动记账")
        }

        // 5) 失败/取消：记录状态但不生成候选（失败与取消绝不能记账）
        if (PageParseSupport.failureKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_FAILED, TradeState.FAILED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_FAILED, TradeState.FAILED, "微信支付失败页")
        }
        if (PageParseSupport.cancelKeywords.any { joined.contains(it) }) {
            flow.noteScene(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED)
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CANCELLED, TradeState.CANCELLED, "微信支付取消页")
        }

        // 6) 收银台/密码键盘：记录流程上下文（金额/商户/支付方式/时间锚点），不生成候选
        if (isConfirmPage(snapshot)) {
            flow.noteConfirmPage(
                amountCents = confirmAmount(snapshot),
                counterparty = null,
                instrument = confirmInstrument(snapshot),
                atMillis = snapshot.captureTime,
            )
            return PageParseResult.SceneOnly(PageScene.PAYMENT_CONFIRM, TradeState.PENDING, "微信收银台/密码键盘")
        }

        val detailTitle = detailTitles.firstOrNull { joined.contains(it) }
        val detailBodyAnchorCount = detailBodyAnchors.count { joined.contains(it) }
        val untitledDetail = looksLikeUntitledDetail(snapshot)

        // 7) 明细/账单列表页：不是单笔结果页。
        //    无标题商户详情也可能命中"交易状态+收款方"的列表组合，正文锚点 + 单笔金额优先。
        if (isListPage(snapshot) && !untitledDetail) {
            flow.noteScene(PageScene.LIST, TradeState.UNKNOWN)
            return PageParseResult.SceneOnly(PageScene.LIST, TradeState.UNKNOWN, "微信明细列表页")
        }

        // 8) 退款页（独立资金流：收入 + 退款标记）
        if (isRefundPage(snapshot)) {
            return refundCandidate(snapshot, flow, detailPage = detailTitle != null || detailBodyAnchorCount >= 1)
        }

        // 9) 账单详情/交易详情页：字段最全；没有标题时按正文锚点识别
        if ((detailTitle != null && detailBodyAnchorCount >= 1) || untitledDetail) {
            return detailCandidate(snapshot, flow, detailTitle)
        }

        // 10) 支付成功页
        if (successTitles.any { joined.contains(it) } && !joined.contains("当前状态")) {
            return successCandidate(snapshot, flow)
        }

        // 11) 收款到账页：结果页短语 + 至少两个结果页标记 + 锚点邻域金额
        if (receiveAnchors.any { joined.contains(it) } && receiveExcludes.none { joined.contains(it) }) {
            return receiveCandidate(snapshot, flow)
        }

        // 12) 转账/充值/提现页（保留自动记账：有明确结果页与交易时间，可按时间/金额去重）
        if (transferAnchors.any { joined.contains(it) }) {
            return transferCandidate(snapshot, flow)
        }

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

    /**
     * 无标题商户详情页：正文锚点（当前状态/交易单号/商户全称…）至少两个 + 成功/退款状态。
     * 列表页通常有多笔金额；详情页只有本笔金额（偶尔带一个余额），因此金额数 ≤2 时正文锚点优先。
     */
    private fun looksLikeUntitledDetail(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (detailTitles.any { joined.contains(it) }) return false
        if (detailBodyAnchors.count { joined.contains(it) } < 2) return false
        val state = detailTradeState(joined)
        if (state != TradeState.SUCCESS && state != TradeState.REFUND) return false
        return PageParseSupport.collectAmounts(snapshot.lines).size <= 2
    }

    /**
     * 红包领取页（封面页）：`红包封面/回复表情到聊天/了解红包封面` 至少两个，
     * 且不是发红包页、不是已领取/详情页。**不要求一定有金额**——
     * 群红包/自定义封面可能不展示金额，由悬浮卡让用户补录。
     */
    private fun isRedPacketClaimPage(snapshot: PageSnapshot): Boolean {
        val lines = snapshot.lines
        val joined = snapshot.joined
        if (!joined.contains("红包")) return false
        if (redPacketClaimMarkers.count { joined.contains(it) } < 2) return false
        // 发红包页：整页出现发送按钮/提示就不是领取页
        if (redPacketSendMarkers.any { joined.contains(it) }) return false
        // “已领取”只在封面标记附近才算已领取页；同包名快照可能同时含聊天页历史，
        // 不能整页排除（否则真实封面会被聊天里的历史“已领取”挡掉）。
        val anchor = lines.indexOfFirst { line -> redPacketClaimMarkers.any { line.contains(it) } }
        if (anchor < 0) return false
        val nearby = lines.subList(maxOf(0, anchor - 2), minOf(lines.size, anchor + 4)).joinToString(" ")
        return redPacketClaimedMarkers.none { nearby.contains(it) }
    }

    /**
     * 其它红包页面（详情/已领取/聊天结果/发红包）：一律不自动记账。
     * 放在领取页判定之后，避免把领取页误判成"已领取"。
     */
    private fun isRedPacketSurface(snapshot: PageSnapshot): Boolean {
        val joined = snapshot.joined
        if (!joined.contains("红包")) return false
        return redPacketSendMarkers.any { joined.contains(it) } ||
            redPacketClaimedMarkers.any { joined.contains(it) } ||
            joined.contains("微信红包")
    }

    /** 领取页金额：`元` 行或紧邻行的独立金额（真实封面把 "5.20" 与 "元" 渲染成两个节点）；缺失返回 null */
    private fun redPacketClaimAmountCents(snapshot: PageSnapshot): Long? {
        val lines = snapshot.lines
        val unitIndex = lines.indexOfFirst { it.trim() == "元" || it.trim().endsWith("元") }
        if (unitIndex < 0) return null
        val token = PageParseSupport.amountNearAnchor(lines, unitIndex) ?: return null
        return com.controleco.app.util.Money.parseCents(token)?.takeIf { it > 0 }
    }

    // ---------- 候选构造 ----------

    /**
     * 红包领取页候选：只标记 [PageCandidate.requiresUserConfirmation]，由采集中心落 ASK 条目并弹悬浮卡。
     * 金额只作为卡片预填值，用户可改/可清空；没有金额就显示"待补"。
     * 这里绝不判断"红包已成立"——是否真实红包、是否记账由用户决定。
     */
    private fun redPacketClaimResult(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val amountCents = redPacketClaimAmountCents(snapshot)
        val counterparty = payerOf(snapshot)
        val pageKey = "wx-redpacket-ask|${amountCents ?: 0L}|$counterparty|${snapshot.fingerprint}"
        val seq = flow.markEmitted(pageKey)
        if (seq < 0) {
            return PageParseResult.SceneOnly(PageScene.RED_PACKET, TradeState.UNKNOWN, "微信红包领取页重复事件")
        }
        flow.noteScene(PageScene.RED_PACKET, TradeState.UNKNOWN)
        return PageParseResult.Candidate(
            PageCandidate(
                type = TxType.INCOME,
                amountCents = amountCents ?: 0L,
                counterparty = counterparty,
                scene = PageScene.RED_PACKET,
                tradeState = TradeState.UNKNOWN,
                confidence = ParseConfidence.LOW,
                sourceLabel = label,
                ruleName = "$label·红包待确认",
                note = "微信红包",
                amountAnchored = amountCents != null,
                requiresUserConfirmation = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.INCOME, false, amountCents ?: 0L, snapshot.captureTime,
                ),
            ),
        )
    }

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
                amountAnchored = true,
                flowKey = flow.pageKey,
                dedupKey = NotificationRules.dedupKey(
                    snapshot.packageName, TxType.EXPENSE, false, amountCents, txTime ?: snapshot.captureTime,
                ),
            ),
        )
    }

    private fun detailCandidate(snapshot: PageSnapshot, flow: PageFlow, title: String?): PageParseResult {
        val lines = snapshot.lines
        val joined = snapshot.joined
        val anchorIdx = if (title != null) {
            lines.indexOfFirst { line -> detailTitles.any { line.contains(it) } }
        } else {
            -1
        }
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
        val amountPick = detailAmountPick(snapshot)
            ?: return PageParseResult.SceneOnly(PageScene.TRANSACTION_DETAIL, state, "微信详情页无金额")
        val amountCents = amountPick.cents
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
        // 顶部金额显式带符号时以符号为准（-12.35 = 支出，+12.35 = 收入）；
        // 无符号才沿用文字方向判断。退款页始终是收入（独立资金流）。
        val direction = when {
            isRefund -> TxType.INCOME
            amountPick.direction != null -> amountPick.direction
            else -> detailDirection(joined, false)
        }

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
                ruleName = "$label·${title ?: "账单详情页"}",
                note = noteOf(instrument, txTime),
                orderId = orderId,
                amountAnchored = amountPick.anchored,
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
        val joined = snapshot.joined
        // 裸"已收款"会命中聊天页的历史转账气泡；必须同时具备两个以上结果页标记
        if (receiveResultMarkers.count { joined.contains(it) } < 2) {
            return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页结果标记不足")
        }
        val anchorIdx = lines.indexOfFirst { line -> receiveAnchors.any { line.contains(it) } }
        // 金额必须在收款锚点行或紧邻行，且邻域内只能有一个不同金额（不做整页第一笔兜底）
        val amount = PageParseSupport.amountNearAnchor(lines, anchorIdx)
            ?: return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页无锚定金额")
        val amountCents = com.controleco.app.util.Money.parseCents(amount) ?: return PageParseResult.SceneOnly(
            PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页金额非法",
        )
        if (amountCents <= 0) {
            return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页金额为零")
        }
        val orderId = PageParseSupport.orderIdFrom(lines)
        val txTime = PageParseSupport.timeFromPage(snapshot) ?: anchorOrNull(flow, snapshot)
        // 有页面交易时间时优先用它做页面身份与去重时间：同一收款页在不同分钟重复采样仍是同一笔
        val pageIdentity = orderId ?: txTime ?: snapshot.fingerprint
        val seq = flow.markEmitted("wx-receive|$amountCents|$pageIdentity")
        if (seq < 0) return PageParseResult.SceneOnly(PageScene.RECEIVE_SUCCESS, TradeState.SUCCESS, "微信收款页重复事件")
        flow.noteSuccess(null)

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
                amountAnchored = true,
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
        val anchored = PageParseSupport.amountNearAnchor(lines, anchorIdx) != null
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
                amountAnchored = anchored,
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

    /** 详情页金额：数值（分）+ 可选的显式方向 + 是否来自页面锚点 */
    private data class DetailAmountPick(val cents: Long, val direction: TxType?, val anchored: Boolean)

    /**
     * 详情页金额，按可信度依次取：
     * 1. 页首带符号的独立金额（`-12.35` / `+12.35`，无 ¥ 也认）；
     * 2. 页首的独立 ¥ 金额（大字金额）；
     * 3. 金额标签（`金额`/`交易金额`/`付款金额`/`实付款`）后的金额；
     * 4. 通用优先级兜底（此时不算锚定金额）。
     *
     * 前三种来源都带 [DetailAmountPick.anchored]；第四种只用于兼容旧版式，不能覆盖既有金额。
     */
    private fun detailAmountPick(snapshot: PageSnapshot): DetailAmountPick? {
        for (line in snapshot.lines.take(8)) {
            // 负号也是优惠项的写法（"-¥2.00"）；优惠金额不能当成账单正文金额
            if (PageParseSupport.classifyAmount(line) == PageParseSupport.AmountKind.DISCOUNT) continue
            val signed = PageParseSupport.signedStandaloneAmount(line) ?: continue
            val direction = if (signed.negative) TxType.EXPENSE else TxType.INCOME
            return DetailAmountPick(signed.cents, direction, anchored = true)
        }
        for (line in snapshot.lines.take(8)) {
            val t = line.trim()
            if (!(t.startsWith("¥") || t.startsWith("￥"))) continue
            val kind = PageParseSupport.classifyAmount(t)
            if (kind == PageParseSupport.AmountKind.PAID || kind == PageParseSupport.AmountKind.UNKNOWN) {
                val value = PageParseSupport.amountValuesIn(t).firstOrNull() ?: continue
                val cents = com.controleco.app.util.Money.parseCents(value) ?: continue
                if (cents > 0) return DetailAmountPick(cents, null, anchored = true)
            }
        }
        PageParseSupport.amountAfterLabel(
            snapshot,
            listOf("金额", "交易金额", "付款金额", "支付金额", "实付款"),
        )?.let { value ->
            com.controleco.app.util.Money.parseCents(value)?.takeIf { it > 0 }?.let {
                return DetailAmountPick(it, null, anchored = true)
            }
        }
        PageParseSupport.pickPaidAmount(snapshot)?.let { value ->
            com.controleco.app.util.Money.parseCents(value)?.takeIf { it > 0 }?.let {
                return DetailAmountPick(it, null, anchored = false)
            }
        }
        return null
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
