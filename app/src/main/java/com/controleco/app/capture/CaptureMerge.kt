package com.controleco.app.capture

import com.controleco.app.capture.page.PageParseSupport
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 跨通道合并：通知监听、无障碍页面抓取、银行动账（短信/推送）可能先后捕获同一笔付款。
 *
 * 判定与合并规则做成纯函数（无 Android 依赖，可直接单测）：
 *
 * - 同一笔 = 同包名 + 同方向 + 两次捕获相隔 ≤ [SAME_ORDER_WINDOW_MS]；
 *   金额相同直接命中；金额不同时（通知误解析历史：支付宝 ¥1.00）只有候选唯一才认作同笔。
 * - 页面通道另有一层"页面重捕"判定（[pickRelatedPendingForPage]、[MEITUAN_RECAPTURE_WINDOW_MS]）：
 *   **只对美团外卖**——它的结果页会被弹窗干扰/重渲染而重新观察，按更长的窗口、严格同金额并回既有条目。
 * - 命中后**页面信息优先**：页面侧有商户口/支付方式/金额/时间，覆盖通知侧的稀疏值。
 * - **同金额的两笔独立交易不能被并成一笔**：页面候选带"流程标识"，标识不同即视为两笔。
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
 * 在合并池（待确认 + 自动入账条目）里找同一笔：同包名同方向、捕获时刻在窗口内，
 * 金额相同优先取时间最近的。
 *
 * [allowAmountMismatch] 只给"页面捕获"这一侧开：页面是权威来源，金额对不上时
 * （通知误解析历史：支付宝 ¥1.00）窗口内唯一候选即认作同笔、由页面金额覆盖。
 * 通知侧的捕获必须严格比金额——否则同 App 内 10s 内两笔金额不同的付款会被并成一笔，丢记录。
 *
 * [flowIdentity] 是本条候选所属"支付流程"的标识：既有条目若明确属于**另一个流程**
 * 则不参与合并（同额两笔独立交易的隔离）；无流程标识的既有条目（通知/银行侧创建）仍可跨渠道合并。
 */
internal fun pickRelatedPending(
    candidates: List<PendingTransactionEntity>,
    packageName: String,
    type: TxType,
    amountCents: Long,
    captureAt: Long,
    windowMs: Long = SAME_ORDER_WINDOW_MS,
    allowAmountMismatch: Boolean = false,
    flowIdentity: String? = null,
): RelatedPending? {
    val inWindow = candidates.filter {
        it.sourcePackage == packageName && it.type == type && abs(captureAt - it.createdAt) <= windowMs &&
            flowCompatible(it.dedupKey, flowIdentity)
    }
    inWindow.filter { it.amountCents == amountCents }
        .minByOrNull { abs(captureAt - it.createdAt) }
        ?.let { return RelatedPending(it, sameAmount = true) }
    if (!allowAmountMismatch) return null
    return inWindow.singleOrNull()?.let { RelatedPending(it, sameAmount = false) }
}

/**
 * 流程兼容判定：既有条目的流程标识为空（通知/银行侧创建，没有流程概念）→ 允许合并；
 * 有标识时必须与本次候选一致，否则视为**另一笔独立交易**（同额两笔各自成条，绝不并成一条）。
 */
internal fun flowCompatible(existingDedupKey: String, incomingFlowIdentity: String?): Boolean {
    if (incomingFlowIdentity.isNullOrEmpty()) return true
    val existing = flowIdentityOf(existingDedupKey)
    return existing.isEmpty() || existing == incomingFlowIdentity
}

/**
 * 页面通道的"同一笔"判定，三层：
 * 1. 同一支付流程内的后续页面（如结果页之后又打开账单详情）：[flowIdentity] 相同的既有条目
 *    直接认作同笔（金额必须一致，不受时间窗口限制）；
 * 2. 跨通道窗口（10s，允许金额不一致）——通知误解析金额时由页面金额覆盖；
 * 3. 美团外卖的重捕窗口（[MEITUAN_RECAPTURE_WINDOW_MS]，**严格同金额**）——只对美团外卖生效。
 *
 * 重捕窗口必须严格比金额：窗口内两笔**不同金额**的付款绝不能并成一笔（丢记录）。
 * 同额的**两笔独立交易**由 [flowIdentity] 隔离：既有条目属于另一流程时不参与合并。
 */
internal fun pickRelatedPendingForPage(
    candidates: List<PendingTransactionEntity>,
    packageName: String,
    type: TxType,
    amountCents: Long,
    captureAt: Long,
    flowIdentity: String? = null,
): RelatedPending? {
    if (!flowIdentity.isNullOrEmpty()) {
        candidates.firstOrNull {
            it.sourcePackage == packageName && it.type == type && it.amountCents == amountCents &&
                flowIdentityOf(it.dedupKey) == flowIdentity
        }?.let { return RelatedPending(it, sameAmount = true) }
    }
    pickRelatedPending(
        candidates, packageName, type, amountCents, captureAt,
        allowAmountMismatch = true, flowIdentity = flowIdentity,
    )?.let { return it }
    if (packageName != NotificationRules.MEITUAN_TAKEOUT) return null
    return pickRelatedPending(
        candidates, packageName, type, amountCents, captureAt,
        windowMs = MEITUAN_RECAPTURE_WINDOW_MS, flowIdentity = flowIdentity,
    )
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
    /** 同一分钟内同一来源的同额交易：无后缀 = 第一笔，带 `@xxx` = 另一笔独立交易的流程标识 */
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
 * 去重键里的流程标识（`…|12345@f1a2` → `f1a2`；无后缀为空串 = 该分钟桶里的第一笔）。
 * 返回值与调用方传入的 [flowCompatible] / [DedupKeyAllocator] 标识**同一形态**（都带不带 `@` 一致），
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
 * 在同一分钟桶内按"流程标识"分配去重键（纯逻辑，便于单测）。
 *
 * 背景：去重键的分钟桶保证"同一笔只入一次"，但会把"同一分钟内两笔同额交易"键成同一个值，
 * 第二笔会被唯一索引吞掉。这里按流程标识区分：同一流程复用同一个键；
 * 不同流程（用户确实又付了一笔）在键尾追加 `@<流程标识>`，两笔都能落账。
 */
internal class DedupKeyAllocator(
    private val ttlMs: Long = 5 * 60_000L,
    private val capacity: Int = 128,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val owners = LinkedHashMap<String, Pair<String, Long>>()

    fun allocate(baseKey: String, flowIdentity: String?): String = synchronized(this) {
        val now = clock()
        evictExpired(now)
        val identity = flowIdentity.orEmpty()
        if (identity.isEmpty()) return baseKey
        val owner = owners[baseKey]
        if (owner == null || owner.first == identity) {
            remember(baseKey, identity, now)
            return baseKey
        }
        val suffixed = "$baseKey@$identity"
        remember(suffixed, identity, now)
        suffixed
    }

    /** 键已释放（条目被丢弃/确认）：允许后来者复用同一个键 */
    fun release(key: String) = synchronized(this) { owners.remove(key) }

    private fun remember(key: String, identity: String, now: Long) {
        owners.remove(key)
        owners[key] = identity to now
        while (owners.size > capacity) {
            val eldest = owners.keys.firstOrNull() ?: break
            owners.remove(eldest)
        }
    }

    private fun evictExpired(now: Long) {
        val iterator = owners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.second > ttlMs) iterator.remove()
        }
    }
}

/**
 * 页面信息优先的字段合并：页面有值则覆盖（商户/金额/时间/支付工具），页面没有的保留通知侧原值；
 * 支付方式并入备注（页面独有信息）。
 *
 * 两道防护：
 * 1. **纯金额不能覆盖已有商户**——页面解析偶发把金额节点当商户
 *    （真实事故：碰一碰支付页"￥"与"20.06"分节点渲染，合并回填把"鸿润福连锁超市"改成了"20.06"）；
 * 2. **用户手改过（userEdited）时不覆盖金额/商户/时间**——用户输入优先于自动解析。
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
    val note = buildString {
        append(baseNote)
        if (pagePaymentMethod != null && !baseNote.contains(pagePaymentMethod)) {
            if (isNotEmpty()) append(" · ")
            append("支付方式 $pagePaymentMethod")
        }
    }.take(300)
    if (existing.userEdited) {
        // 用户改过：只补空白字段，不动金额、时间、商户
        return PageMerge(
            amountCents = existing.amountCents,
            timestamp = existing.timestamp,
            counterparty = existing.counterparty,
            note = note,
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
        note = note,
        ruleName = pageRuleName.ifBlank { existing.ruleName },
        paymentInstrument = pageInstrument.ifBlank { existing.paymentInstrument },
    )
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
