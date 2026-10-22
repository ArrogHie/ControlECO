package com.controleco.app.capture

import com.controleco.app.capture.page.PageParseSupport
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 跨通道合并：通知监听、无障碍页面抓取、银行动账（短信/推送）可能先后捕获同一笔付款。
 *
 * 判定与合并规则做成纯函数（无 Android 依赖，可直接单测）：
 *
 * - 同一笔 = 同包名 + 同方向 + 两次捕获相隔 ≤ [SAME_ORDER_WINDOW_MS]，且**金额必须相同**；
 *   金额不同一律不自动合并——"候选唯一"不能证明是同一笔，宁可保留两条交给用户。
 * - 页面通道另有一层"页面重捕"判定（[pickRelatedPendingForPage]、[MEITUAN_RECAPTURE_WINDOW_MS]）：
 *   **只对美团外卖**——它的结果页会被弹窗干扰/重渲染而重新观察，按更长的窗口、严格同金额并回既有条目。
 * - 命中后**页面信息优先**：页面侧有商户口/支付方式/金额/时间，覆盖通知侧的稀疏值。
 * - **同金额的两笔独立交易不能被并成一笔**：页面候选带"支付流程标识"、通知候选带"通知实例标识"，
 *   同类标识不同即视为两笔；不同通道之间的标识（页面 p: / 通知 n:）不互相阻塞。
 * - **用户手改过的字段不被覆盖**（[PendingTransactionEntity.userEdited]）。
 */

/** 两通道"同时捕获到一笔"的判定窗口：捕获时刻相差 ≤ 10s */
internal const val SAME_ORDER_WINDOW_MS = 10_000L

/**
 * 美团外卖的页面重捕窗口：支付成功后弹窗干扰/页面重渲染会让它的付款结果页被重新观察，
 * 快照内容略有出入（营销节点增减）、捕获时刻相差一两分钟——只靠 10s 窗口会把同一笔记成两条。
 * **只对美团外卖生效**。
 */
internal const val MEITUAN_RECAPTURE_WINDOW_MS = 180_000L

/** 通知侧时间对页面侧兜底时间的修正窗口：页面把捕获时刻当交易时间时，用通知的发布时间校正 */
internal const val NOTIFICATION_TIME_FIX_WINDOW_MS = 300_000L

/** 命中结果：既有条目 + 金额是否一致（不一致 = 页面金额覆盖通知金额） */
internal data class RelatedPending(
    val pending: PendingTransactionEntity,
    val sameAmount: Boolean,
)

/**
 * 在合并池（待确认 + 自动入账条目）里找同一笔：同包名同方向、捕获时刻在窗口内、**金额相同**，
 * 且流程/通知实例身份不冲突。金额不同一律不合并——"窗口内候选唯一"不能证明是同一笔。
 *
 * [flowIdentity] 是本条候选所属的身份：页面流程标识（`p:…`）只与既有页面流程比较，
 * 通知实例标识（`n:…`）只与既有通知实例比较；跨通道时另一侧的标识为空，不阻塞合并。
 */
internal fun pickRelatedPending(
    candidates: List<PendingTransactionEntity>,
    packageName: String,
    type: TxType,
    amountCents: Long,
    captureAt: Long,
    windowMs: Long = SAME_ORDER_WINDOW_MS,
    flowIdentity: String? = null,
): RelatedPending? {
    val inWindow = candidates.filter {
        it.status != PendingStatus.ASK &&
            it.sourcePackage == packageName && it.type == type && abs(captureAt - it.createdAt) <= windowMs &&
            flowCompatible(it, flowIdentity, packageName)
    }
    return inWindow.filter { it.amountCents == amountCents }
        .minByOrNull { abs(captureAt - it.createdAt) }
        ?.let { RelatedPending(it, sameAmount = true) }
}

/**
 * 判断一条候选能否与既有条目视作同一笔（按"同类身份"比较）：
 * - 页面流程标识只在**同一个应用**内有意义：微信的 `p:xxx` 与美团的 `p:yyy` 按包名分别生成，
 *   跨应用时身份不同不能证明是两笔（真实链路：微信/支付宝先捕获，用户返回后电商页再捕获）；
 * - 既有通知实例标识非空：本条也是通知候选时必须相同，否则是另一条通知；
 * - 跨通道（页面 ⇄ 通知）时对侧字段为空，允许融合。
 *
 * 旧版本的去重键后缀（`@f1a2` 这类短标识）按页面流程兼容处理；通知历史条目没有身份，
 * 只按 10s 窗口与严格同额参与合并（保守策略，不假设它属于任意新流程）。
 */
internal fun flowCompatible(
    existing: PendingTransactionEntity,
    incomingIdentity: String?,
    /** 本条候选的来源包名：页面流程身份按应用分别生成，只与同应用的既有流程比较 */
    incomingPackage: String? = null,
): Boolean {
    if (incomingIdentity.isNullOrEmpty()) return true
    if (incomingIdentity.startsWith(BANK_ID_PREFIX)) {
        val existingBank = entryBankInstanceId(existing)
        return existingBank.isEmpty() || existingBank == incomingIdentity
    }
    if (incomingIdentity.startsWith(NOTIFICATION_ID_PREFIX)) {
        val existingNotification = entryNotificationInstanceId(existing)
        return existingNotification.isEmpty() || existingNotification == incomingIdentity
    }
    // 跨应用：身份作用域是各自的应用，不能拿微信流程去否定美团结果页
    if (!incomingPackage.isNullOrBlank() && incomingPackage != existing.sourcePackage) return true
    val existingFlow = entryFlowId(existing)
    return existingFlow.isEmpty() || existingFlow == incomingIdentity
}

/** 兼容旧测试/调用点的两参数形态：只比较去重键里的后缀。 */
internal fun flowCompatible(existingDedupKey: String, incomingFlowIdentity: String?): Boolean {
    if (incomingFlowIdentity.isNullOrEmpty()) return true
    val existing = flowIdentityOf(existingDedupKey)
    if (incomingFlowIdentity.startsWith(BANK_ID_PREFIX)) {
        return existing.isEmpty() || existing == incomingFlowIdentity
    }
    if (incomingFlowIdentity.startsWith(NOTIFICATION_ID_PREFIX)) {
        val existingNotification = existing.takeIf { it.startsWith(NOTIFICATION_ID_PREFIX) }.orEmpty()
        return existingNotification.isEmpty() || existingNotification == incomingFlowIdentity
    }
    val existingFlow = existing.takeIf { !it.startsWith(NOTIFICATION_ID_PREFIX) && !it.startsWith(BANK_ID_PREFIX) }.orEmpty()
    return existingFlow.isEmpty() || existingFlow == incomingFlowIdentity
}

/** 既有条目的页面流程标识：优先新列，旧版本页面键的后缀兜底。 */
internal fun entryFlowId(pending: PendingTransactionEntity): String {
    if (pending.flowId.isNotBlank()) return pending.flowId
    val suffix = flowIdentityOf(pending.dedupKey)
    return suffix.takeIf {
        it.isNotEmpty() && !it.startsWith(NOTIFICATION_ID_PREFIX) && !it.startsWith(BANK_ID_PREFIX)
    }.orEmpty()
}

/** 既有条目的通知实例标识：优先新列，通知去重键后缀兜底。 */
internal fun entryNotificationInstanceId(pending: PendingTransactionEntity): String {
    if (pending.notificationInstanceId.isNotBlank()) return pending.notificationInstanceId
    val suffix = flowIdentityOf(pending.dedupKey)
    return suffix.takeIf { it.startsWith(NOTIFICATION_ID_PREFIX) }.orEmpty()
}

/** 既有条目的银行事件标识：优先新列，银行去重键后缀兜底。 */
internal fun entryBankInstanceId(pending: PendingTransactionEntity): String {
    if (pending.bankInstanceId.isNotBlank()) return pending.bankInstanceId
    val suffix = flowIdentityOf(pending.dedupKey)
    return suffix.takeIf { it.startsWith(BANK_ID_PREFIX) }.orEmpty()
}

/**
 * 页面通道的"同一笔"判定，四层（全部要求金额相同）：
 * 0. 同一订单号（最强证据，忽略时间窗口）；
 * 1. 同一支付流程内的后续页面（结果页之后又打开账单详情）：[flowIdentity] 相同的既有条目；
 * 2. 跨通道窗口（10s）；
 * 3. 美团外卖的重捕窗口（[MEITUAN_RECAPTURE_WINDOW_MS]）——只对美团外卖生效。
 *
 * 金额不同时**没有任何一层**允许合并：页面来源优先不能替代"是否同一笔"的判定。
 * 金额是否被锚定（[com.controleco.app.capture.page.PageCandidate.amountAnchored]）只说明
 * 页面金额可信，不能证明两条金额不同的记录是同一笔——5.20 的真实收款绝不能被 7.50 覆盖。
 *
 * 微信红包领取页产生的 ASK 待确认条目**不参与任何自动融合**（[PendingStatus.ASK] 不在
 * `getMergeable()` 池里，这里也显式排除）：红包是否真实、金额多少都由用户确认，
 * 不能被同额转账/收款自动认领；用户确认后待确认条目即删除，也不会留下可融合的残影。
 */
internal fun pickRelatedPendingForPage(
    candidates: List<PendingTransactionEntity>,
    packageName: String,
    type: TxType,
    amountCents: Long,
    captureAt: Long,
    flowIdentity: String? = null,
    orderId: String? = null,
    txTime: Long? = null,
): RelatedPending? {
    val pool = candidates.filter { it.status != PendingStatus.ASK }
    // 页面明确交易时间：同一笔的重复观察/进程重启后重新打开同一收款页，
    // 数据库里已有同额同时间的条目时直接回并，不能再走 pending（避免重复待确认/弹卡）。
    if (txTime != null && txTime > 0) {
        pool.firstOrNull {
            it.sourcePackage == packageName && it.type == type && it.amountCents == amountCents &&
                it.timestamp > 0 && kotlin.math.abs(txTime - it.timestamp) <= PAGE_TIME_MATCH_WINDOW_MS
        }?.let { return RelatedPending(it, sameAmount = true) }
    }
    if (!orderId.isNullOrBlank()) {
        pool.firstOrNull {
            it.sourcePackage == packageName && it.type == type && it.amountCents == amountCents &&
                it.orderId.isNotBlank() && it.orderId == orderId
        }?.let { return RelatedPending(it, sameAmount = true) }
    }
    if (!flowIdentity.isNullOrEmpty()) {
        // 同一页面流程的后续页面可能落在"跨应用合并后保留了通知来源"的条目上
        // （页面先到、通知后到），此时 sourcePackage 可能不同；流程身份本身已足够强。
        pool.firstOrNull {
            it.type == type && it.amountCents == amountCents && entryFlowId(it) == flowIdentity
        }?.let { return RelatedPending(it, sameAmount = true) }
    }
    pickRelatedPending(
        pool, packageName, type, amountCents, captureAt,
        flowIdentity = flowIdentity,
    )?.let { return it }
    if (packageName != NotificationRules.MEITUAN_TAKEOUT) return null
    return pickRelatedPending(
        pool, packageName, type, amountCents, captureAt,
        windowMs = MEITUAN_RECAPTURE_WINDOW_MS, flowIdentity = flowIdentity,
    )
}

/** 页面候选带明确交易时间时，与既有条目匹配的窄窗口（进程重启后重新打开同一详情页的防重） */
internal const val EXPLICIT_TIME_MATCH_WINDOW_MS = 30_000L

/**
 * 页面候选的"交易时间 → 既有条目时间戳"回并窗口（收款页/红包页通用）。
 *
 * 收款页的页面交易时间精确到秒，但旧捕获可能把 captureTime 当时间戳，两者通常仍在一分钟内；
 * 同额同时间在 60 秒内回并，避免重新打开同一收款页时插一条重复 pending。
 * 两笔真实同额交易的交易时间相差 60 秒以上才可能是独立两笔，不能在这里吞掉。
 */
internal const val PAGE_TIME_MATCH_WINDOW_MS = 60_000L

/**
 * 页面候选带**明确交易时间**时的同笔判定：同来源/同方向/同金额、退款标记一致，
 * 且交易时间相差 ≤ [EXPLICIT_TIME_MATCH_WINDOW_MS]。
 *
 * 用途：进程重启后 page flow 身份已丢失，用户再打开同一笔的账单详情页时，
 * 不能因为 flowId 对不上就新建一条。窗口刻意收紧到 30 秒（同一笔的详情页交易时间应与
 * 原捕获时间几乎相同；两笔同额真实交易在同一 30 秒内且时间戳完全接近属于极小概率）。
 */
internal fun pickByExplicitTime(
    candidates: List<PendingTransactionEntity>,
    packageName: String,
    type: TxType,
    amountCents: Long,
    txTime: Long,
    isRefund: Boolean,
): RelatedPending? = candidates.firstOrNull {
    it.sourcePackage == packageName && it.type == type && it.amountCents == amountCents &&
        (it.refundMark == RefundMark.REFUND_INCOME) == isRefund &&
        abs(txTime - it.timestamp) <= EXPLICIT_TIME_MATCH_WINDOW_MS
}?.let { RelatedPending(it, sameAmount = true) }

/**
 * 金额冲突候选：同包名/同方向、时间接近但金额不同的既有条目。
 * 只用于诊断日志（说明"为什么没有合并"），不参与任何自动合并。
 */
internal fun nearbyAmountConflict(
    candidates: List<PendingTransactionEntity>,
    packageName: String,
    type: TxType,
    amountCents: Long,
    captureAt: Long,
    windowMs: Long = SAME_ORDER_WINDOW_MS,
): PendingTransactionEntity? = candidates.firstOrNull {
    it.sourcePackage == packageName && it.type == type && it.amountCents != amountCents &&
        abs(captureAt - it.createdAt) <= windowMs
}


/** 页面优先合并后的字段值 */
internal data class PageMerge(
    val amountCents: Long,
    val timestamp: Long,
    val counterparty: String,
    val note: String,
    val ruleName: String,
    val paymentInstrument: String,
)

/** 去重键解析：包名 | 方向（退款多一段 R）| 金额分 | 分钟桶[@流程标识] */
internal data class DedupKey(
    val packageName: String,
    val type: String,
    val amountCents: Long,
    val minute: Long,
    /** 同一分钟内同一来源的同额交易：新数据一律带 `@<身份>`；无后缀只存在于旧版本记录 */
    val flowIdentity: String = "",
)

internal fun parseDedupKey(key: String): DedupKey? {
    val parts = key.split('|')
    if (parts.size < 4) return null
    val tail = parts.last()
    val minute = tail.substringBefore('@').toLongOrNull() ?: return null
    return DedupKey(
        packageName = parts[0],
        type = parts[1],
        amountCents = parts[parts.size - 2].toLongOrNull() ?: return null,
        minute = minute,
        flowIdentity = flowIdentityOf(key),
    )
}

/**
 * 去重键里的身份后缀（`…|12345@p:uuid` → `p:uuid`；无后缀为空串 = 旧版本记录/无身份来源）。
 * 返回值与调用方传入的 [flowCompatible] 标识**同一形态**（都带前缀、不带 `@`），
 * 避免比较时两边形态不一致导致隔离失效。
 */
internal fun flowIdentityOf(dedupKey: String): String {
    val tail = dedupKey.substringAfterLast('|', "")
    val at = tail.indexOf('@')
    return if (at >= 0) tail.substring(at + 1) else ""
}

/**
 * 去重键换金额：金额待补录回填后重算键（保留来源段/方向段/退款段与分钟桶，只替换金额段）。
 * 返回 null = 键格式不对（调用方放弃重算、按原键处理）。
 */
internal fun dedupKeyWithAmount(key: String, amountCents: Long): String? {
    val parts = key.split('|')
    if (parts.size < 4) return null
    val head = parts.dropLast(2)
    val tail = parts.last()
    return (head + amountCents.toString() + tail).joinToString("|")
}

/**
 * 页面信息优先的字段合并：页面有值则覆盖（商户/金额/时间/支付工具），页面没有的保留通知侧原值；
 * 支付方式并入备注（页面独有信息）。
 *
 * 两道防护：
 * 1. **纯金额不能覆盖已有商户**——页面解析偶发把金额节点当商户
 *    （真实事故：碰一碰支付页"￥"与"20.06"分节点渲染，合并回填把"鸿润福连锁超市"改成了"20.06"）；
 * 2. **用户手改过（userEdited）时所有可见字段保持既有值**，只补空白支付工具——
 *    用户输入优先于自动解析，且不允许自动拼装备注导致两处不一致。
 */
internal fun mergePageInfo(
    existing: PendingTransactionEntity,
    pageAmountCents: Long,
    pageTxTime: Long?,
    pageCounterparty: String,
    pageNote: String,
    pagePaymentMethod: String?,
    pageRuleName: String,
    pageInstrument: String = "",
): PageMerge {
    val baseNote = pageNote.ifBlank { existing.note }
    val mergedNote = buildString {
        append(baseNote)
        if (pagePaymentMethod != null && !baseNote.contains(pagePaymentMethod)) {
            if (isNotEmpty()) append(" · ")
            append("支付方式 $pagePaymentMethod")
        }
    }.take(300)
    if (existing.userEdited) {
        // 用户改过：金额/时间/商户/备注/规则名都保持既有值，只补空白字段。
        // 备注不能再用页面内容重新拼装——否则正式账单与待确认条目的备注会不一致。
        return PageMerge(
            amountCents = existing.amountCents,
            timestamp = existing.timestamp,
            counterparty = existing.counterparty,
            note = existing.note,
            ruleName = existing.ruleName,
            paymentInstrument = existing.paymentInstrument.ifBlank { pageInstrument },
        )
    }
    val usableCounterparty = pageCounterparty.takeIf {
        it.isNotBlank() && !PageParseSupport.isAmountLikeNode(it)
    }
    return PageMerge(
        amountCents = pageAmountCents,
        timestamp = pageTxTime ?: existing.timestamp,
        counterparty = usableCounterparty ?: existing.counterparty,
        note = mergedNote,
        ruleName = pageRuleName.ifBlank { existing.ruleName },
        paymentInstrument = pageInstrument.ifBlank { existing.paymentInstrument },
    )
}

/**
 * 备注合并（页面 ⇄ 银行侧是同一笔时，两边的正文都要留着）。
 *
 * 为什么不能简单"页面优先"：银行动账正文里的「在【财付通-微信支付-微信转账】…」是**渠道线索**，
 * 页面备注里的「支付方式 招商银行储蓄卡(2532)」是**真实支付工具**，谁被覆盖掉都会让后续
 * 同笔判定少一条证据。互相包含时只留长的那份，避免同一句话写两遍。
 */
internal fun combineNotes(pageNote: String, bankNote: String): String = when {
    bankNote.isBlank() -> pageNote
    pageNote.isBlank() -> bankNote
    bankNote.contains(pageNote) || pageNote.contains(bankNote) ->
        if (bankNote.length >= pageNote.length) bankNote else pageNote
    else -> "$pageNote · $bankNote"
}

/**
 * 通知 → 页面 方向的补充（页面先到、通知后到）：只补页面缺失的字段，绝不覆盖页面已确认的信息。
 *
 * 目前唯一安全的补充是**时间**：页面没有交易时间时会把捕获时刻当交易时间，
 * 而通知的发布时间更接近真实交易时刻（两者相差在 [NOTIFICATION_TIME_FIX_WINDOW_MS] 内才采纳）。
 * 返回 null = 不需要改动。
 */
internal fun mergeNotificationInfo(
    existing: PendingTransactionEntity,
    notificationTime: Long,
): Long? {
    if (existing.userEdited) return null
    if (notificationTime <= 0) return null
    // 页面已给出明确交易时间（与条目创建时刻明显不同）→ 不覆盖
    val looksLikePageTime = abs(existing.timestamp - existing.createdAt) > 60_000L
    if (looksLikePageTime) return null
    if (abs(notificationTime - existing.timestamp) > NOTIFICATION_TIME_FIX_WINDOW_MS) return null
    return notificationTime.takeIf { it != existing.timestamp }
}
