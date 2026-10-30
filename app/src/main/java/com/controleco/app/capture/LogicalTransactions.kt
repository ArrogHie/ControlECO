package com.controleco.app.capture

import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.util.AppLog
import kotlin.math.abs

/**
 * 一次捕获被判定为"同一笔"之后的终态：这笔已经有了结论，迟到的重复捕获不许再打扰用户。
 * - [HANDLED]：已入账（卡片保存 / 待确认列表确认 / 全部确认）——库里可能已经没有待确认条目，
 *   迟到捕获只静默并入或只留溯源，绝不再记一笔；
 * - [REJECTED]：用户点过「忽略」或「撤销」——这笔是误报/不需要，迟到捕获只留溯源。
 */
internal enum class CaptureResolution { HANDLED, REJECTED }

/** 已终结的一笔捕获：用"可重新识别的特征"记住它，库里的条目删掉之后仍然认得出来 */
internal data class FinishedCapture(
    val sourcePackage: String,
    val type: com.controleco.app.data.db.TxType,
    val amountCents: Long,
    val isRefund: Boolean,
    /** 交易时间（条目时间戳）：与迟到捕获的事件时间比对 */
    val eventTime: Long,
    /** 该笔的身份后缀（`p:`/`n:`/`b:`），用于区分"同一笔的重复"与"另一笔同额真实交易" */
    val identity: String,
    val dedupKey: String,
    val resolution: CaptureResolution,
    /** 登记时刻（用于淘汰） */
    val at: Long,
)

/**
 * **逻辑交易**的身份别名表 + 终态登记（纯内存）。
 *
 * 为什么需要它：去重键 `dedupKey` 是"一次捕获"的键（来源 + 方向 + 金额 + 分钟桶 + 通道身份后缀），
 * 同一笔真实支付从不同通道到达时**必然**得到不同的去重键：
 * 微信支付通知是 `…@n:<摘要>`，随后的微信支付结果页是 `…@p:<uuid>`，银行短信又是 `…@b:<摘要>`。
 * 而悬浮窗"只打扰一次"（[OverlayPromptLog]）和"用户改过的字段不再被改写"（[OverlayFieldGuards]）
 * 都按去重键索引——于是同一笔在第二个通道到达时看起来"从没弹过"，用户就会看到第二张卡，
 * 点「忽略」把唯一那笔已入账的账删掉（2026-10-30 用户反馈的根因）。
 *
 * 本对象给"同一笔真实支付"一个稳定键（逻辑交易键 = 首次落库那条的 dedupKey），
 * 其余通道的去重键都登记成它的**别名**；悬浮窗状态与字段锁一律先经 [canonical] 归一。
 *
 * 三条不变的边界：
 * - **只登记"确认是同一笔"的别名**（跨通道命中 / 重复预检命中），不按金额粗合并；
 * - 两笔真实同额交易的身份后缀不同（`n:A` ⇄ `n:B`、`p:A` ⇄ `p:B`），[matchesFinishedCapture]
 *   与 `flowCompatible` 会拒绝配对，不会被这套归一吞掉；
 * - 刻意不落库（用户 2026-10-30 决定：只存内存、只统一键口径）。进程被杀后别名与终态丢失，
 *   代价是重启后同一笔可能再弹一次卡 / 用户改过的字段可能被再富化一次——已知边界，写入文档。
 */
internal object LogicalTransactions {

    /** 别名表容量：与 [OverlayPromptLog] 同量级，只留最近一批 */
    private const val MAX_ALIASES = 500

    /** 终态登记容量 */
    private const val MAX_FINISHED = 200

    /**
     * 终态记忆窗口。比合并窗口（10s）宽得多：用户在卡片上点「忽略」后，页面/银行侧的
     * 迟到捕获往往在几十秒后才到；但必须靠身份后缀挡住"同来源的另一笔同额交易"。
     */
    const val FINISHED_MEMORY_MS = 10 * 60_000L

    private val aliases = LinkedHashMap<String, String>()
    private val finished = LinkedHashMap<String, FinishedCapture>()

    /**
     * 归一到一个逻辑交易键：有别名跟着别名链走（最多 4 跳，防脏数据成环）。
     * 没有登记过的键原样返回（它自己就是那一笔的规范键）。
     */
    @Synchronized
    fun canonical(key: String): String {
        if (key.isBlank()) return key
        var current = key
        var hops = 0
        while (hops < 4) {
            val next = aliases[current] ?: return current
            if (next == current) return current
            current = next
            hops++
        }
        return current
    }

    /**
     * 登记"去重键 [key] 与 [canonicalKey] 是同一笔"。
     * [canonicalKey] 自己可能已是别名，先归一，保证别名链只有一层。
     */
    @Synchronized
    fun link(key: String, canonicalKey: String) {
        if (key.isBlank() || canonicalKey.isBlank() || key == canonicalKey) return
        if (canonical(key) == canonical(canonicalKey)) return
        val target = canonical(canonicalKey)
        if (target == key) return
        aliases[key] = target
        while (aliases.size > MAX_ALIASES) {
            val eldest = aliases.keys.firstOrNull() ?: break
            aliases.remove(eldest)
        }
        AppLog.d("Dedup", "logical tx alias $key -> $target")
    }

    /**
     * 两个去重键是否属于同一笔逻辑交易。
     *
     * 为什么要它：别名**可能晚于登记**——页面通道先弹卡（登记 `…@p:`），
     * 几百毫秒后银行通道到达才建立 `…@b: → …@p:` 的别名。
     * 只按写入时的规范键查表就会漏掉这条"先登记、后建别名"的记录，
     * 于是同一笔又被当成新的一笔（第二次弹卡）。登记簿用本函数做等价判断。
     */
    @Synchronized
    fun sameLogical(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        return canonical(a) == canonical(b)
    }

    /** 登记一笔已经有了结论（已入账 / 已忽略）：迟到捕获按它判定"不必再打扰" */
    @Synchronized
    fun markResolved(
        pending: PendingTransactionEntity,
        resolution: CaptureResolution,
        now: Long = System.currentTimeMillis(),
    ) {
        remember(
            dedupKey = pending.dedupKey,
            sourcePackage = pending.sourcePackage,
            type = pending.type,
            amountCents = pending.amountCents,
            isRefund = pending.refundMark == com.controleco.app.data.db.RefundMark.REFUND_INCOME,
            eventTime = pending.timestamp,
            resolution = resolution,
            now = now,
        )
    }

    /**
     * 同 [markResolved]，但用调用方手上的解析结果。
     *
     * 为什么需要它：撤销/忽略发生在条目已经产出之后，条目里的金额可能已经被页面改写，
     * 而"迟到的重复捕获"带的是**原始金额**。用解析结果登记能对上迟到的那条，
     * 避免同一笔因为金额被富化过而认不出来（典型：通知 0 元占位 → 补录 12.80 → 撤销）。
     */
    @Synchronized
    fun remember(
        dedupKey: String,
        sourcePackage: String,
        type: com.controleco.app.data.db.TxType,
        amountCents: Long,
        isRefund: Boolean,
        eventTime: Long,
        resolution: CaptureResolution,
        now: Long = System.currentTimeMillis(),
    ) {
        if (dedupKey.isBlank()) return
        val key = canonical(dedupKey)
        finished[key] = FinishedCapture(
            sourcePackage = sourcePackage,
            type = type,
            amountCents = amountCents,
            isRefund = isRefund,
            eventTime = eventTime,
            identity = flowIdentityOf(key),
            dedupKey = key,
            resolution = resolution,
            at = now,
        )
        prune(now)
        while (finished.size > MAX_FINISHED) {
            val eldest = finished.keys.firstOrNull() ?: break
            finished.remove(eldest)
        }
        AppLog.d("Dedup", "logical tx resolved ${resolution.name} key=$key")
    }

    /**
     * 本次捕获是否正好是"已经有过结论的那一笔"。
     * 判定见 [matchesFinishedCapture]：同来源 + 同方向 + 同额 + 时间接近 + 身份兼容。
     *
     * 窗口是**分级**的（与 `pickRecentSameEntry` 同一口径）：本次捕获带身份 → 用 [windowMs]
     * （默认 10 分钟，够覆盖页面/银行侧的迟到回传）；**本次捕获没有身份**（升级前的旧规则、
     * 或读不到实例标识的来源）→ 收紧到 [SAME_ORDER_WINDOW_MS]，否则"同一应用 10 分钟内的
     * 另一笔同额真实交易"会被当成已忽略的那一笔吞掉（丢账比重复弹卡严重得多）。
     */
    @Synchronized
    fun findResolved(
        sourcePackage: String,
        type: com.controleco.app.data.db.TxType,
        amountCents: Long,
        isRefund: Boolean,
        identity: String?,
        eventTime: Long,
        now: Long = System.currentTimeMillis(),
        windowMs: Long = FINISHED_MEMORY_MS,
    ): FinishedCapture? {
        prune(now)
        if (finished.isEmpty()) return null
        val window = if (identity.isNullOrEmpty()) minOf(windowMs, SAME_ORDER_WINDOW_MS) else windowMs
        return finished.values
            .filter {
                matchesFinishedCapture(
                    it, sourcePackage, type, amountCents, isRefund, identity, eventTime, window,
                )
            }
            .minByOrNull { abs(it.eventTime - eventTime) }
    }

    /** 条目被用户真正删除（不是"忽略"结论）时清掉别名与终态，避免键复用时误判 */
    @Synchronized
    fun forget(key: String) {
        if (key.isBlank()) return
        val target = canonical(key)
        finished.remove(target)
        aliases.remove(key)
        aliases.entries.removeAll { it.value == target }
    }

    /** 仅测试用 */
    @Synchronized
    internal fun clearAll() {
        aliases.clear()
        finished.clear()
    }

    /** 仅测试用：已登记的别名条数 */
    @Synchronized
    internal fun aliasCount(): Int = aliases.size

    private fun prune(now: Long) {
        val iterator = finished.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.at > FINISHED_MEMORY_MS) iterator.remove()
        }
    }
}

/**
 * 迟到捕获与"已有结论的那一笔"是否同一笔（纯函数，便于单测）。
 *
 * 与 `hasRecentSameEntry`（只比金额）的关键差别：这里**必须身份兼容**——
 * 同来源的另一笔同额交易带着不同的 `n:`/`p:`/`b:` 后缀，`flowCompatible` 会拒绝配对；
 * 而同一笔的跨通道重复（通知 `n:` ⇄ 页面 `p:`）后缀类型不同，不会被错误否定。
 */
internal fun matchesFinishedCapture(
    candidate: FinishedCapture,
    sourcePackage: String,
    type: com.controleco.app.data.db.TxType,
    amountCents: Long,
    isRefund: Boolean,
    identity: String?,
    eventTime: Long,
    windowMs: Long,
): Boolean {
    if (candidate.sourcePackage != sourcePackage) return false
    if (candidate.type != type) return false
    if (candidate.amountCents != amountCents) return false
    // 退款到账与消费扣款金额可能相同（"退款 12.80"vs"消费 12.80"），方向与退款标记都要对上
    if (candidate.isRefund != isRefund) return false
    if (abs(candidate.eventTime - eventTime) > windowMs) return false
    return flowCompatible(candidate.dedupKey, identity)
}
