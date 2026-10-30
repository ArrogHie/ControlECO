package com.controleco.app.capture

import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog

/** 已确认/自动入账或已忽略的逻辑交易。 */
internal enum class CaptureResolution { HANDLED, REJECTED }

/**
 * 一次真实捕获的证据。time 是实际捕获时刻，不是页面显示的交易时间，也不是用户点击时间。
 * 同一应用的 p:/n:/b: 身份分别独立；跨应用的 p: 身份不能直接互相比较。
 */
internal data class CaptureObservation(
    val evidence: CrossAppLinker.Evidence,
    val identity: String = "",
    val dedupKey: String = "",
) {
    val sourcePackage: String get() = evidence.sourcePackage

    companion object {
        fun fromPending(pending: PendingTransactionEntity): CaptureObservation {
            val guards = OverlayFieldGuards.fieldsOf(pending.dedupKey)
            return CaptureObservation(
                CrossAppLinker.Evidence(
                    sourcePackage = pending.sourcePackage,
                    amountCents = pending.amountCents,
                    type = pending.type,
                    isRefund = pending.refundMark == RefundMark.REFUND_INCOME,
                    time = pending.createdAt,
                    counterparty = pending.counterparty,
                    instrument = pending.paymentInstrument,
                    note = pending.note,
                    ruleName = pending.ruleName,
                    orderId = pending.orderId,
                    counterpartyEdited = pending.userEdited || GuardedField.COUNTERPARTY in guards,
                    noteEdited = pending.userEdited || GuardedField.NOTE in guards,
                ),
                // dedupKey 是首次捕获身份；flowId 列可能由另一个应用的页面后补，不能拿来替代它。
                identity = flowIdentityOf(pending.dedupKey),
                dedupKey = pending.dedupKey,
            )
        }
    }
}

/** 终态记忆：首次捕获时间恒定，已并入的多应用/多通道身份一同保留。 */
internal data class FinishedCapture(
    val sourcePackage: String,
    val type: TxType,
    val amountCents: Long,
    val isRefund: Boolean,
    /** 历史名称，现始终表示首次捕获时间，绝不使用合并后的 pending.timestamp */
    val eventTime: Long,
    val identity: String,
    val dedupKey: String,
    val resolution: CaptureResolution,
    /** 仅记录用户操作时间，不参与窗口计算和淘汰 */
    val at: Long,
    val observations: List<CaptureObservation> = emptyList(),
) {
    val firstCapturedAt: Long get() = eventTime
}

/**
 * 同一笔逻辑交易：首次捕获为唯一时间锚点；每次确认同笔后追加来源/通道身份。
 * 未处理和已处理使用同一个 [matchesObservedCapture]，不会因为 Pending 被删除而失去跨应用匹配。
 * 全部为内存状态；重启后仍依赖原有 Room 待确认池，不新增表/数据库迁移。
 */
internal object LogicalTransactions {
    const val CAPTURE_WINDOW_MS = 180_000L
    const val FINISHED_MEMORY_MS = CAPTURE_WINDOW_MS
    private const val MAX_ALIASES = 500
    private const val MAX_OBSERVED = 500
    private const val MAX_FINISHED = 200
    private const val MAX_SOURCES_PER_TX = 12

    private val aliases = LinkedHashMap<String, String>()
    private val observed = LinkedHashMap<String, MutableList<CaptureObservation>>()
    private val finished = LinkedHashMap<String, FinishedCapture>()

    @Synchronized
    fun canonical(key: String): String {
        if (key.isBlank()) return key
        var current = key
        repeat(8) {
            val next = aliases[current] ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    @Synchronized
    fun link(key: String, canonicalKey: String) {
        if (key.isBlank() || canonicalKey.isBlank()) return
        val target = canonical(canonicalKey)
        val from = canonical(key)
        if (from == target) return
        if (from == key && target == key) return
        aliases[key] = target
        // 别名登记可能晚于来源记录，已登记的来源也必须迁移到首次捕获的键下面。
        observed.remove(from)?.let { sources ->
            val dest = observed.getOrPut(target) { mutableListOf() }
            sources.forEach { addSource(dest, it) }
        }
        while (aliases.size > MAX_ALIASES) aliases.remove(aliases.keys.first())
        AppLog.d("Dedup", "logical tx alias $key -> $target")
    }

    @Synchronized
    fun sameLogical(a: String, b: String): Boolean =
        a.isNotBlank() && b.isNotBlank() && canonical(a) == canonical(b)

    /** 已确认并入（或原条目正在使用）的捕获：登记身份和首次来源，永不改变锚点。 */
    @Synchronized
    fun observe(pending: PendingTransactionEntity, incoming: CaptureObservation) {
        val key = canonical(pending.dedupKey)
        val dest = observed.getOrPut(key) { mutableListOf() }
        if (dest.isEmpty()) addSource(dest, CaptureObservation.fromPending(pending))
        addSource(dest, incoming)
        if (incoming.dedupKey.isNotBlank()) link(incoming.dedupKey, key)
        // 合并协程可能与确认操作交错：如果此时已被标记为终态，也要同步登记
        // 最后一个来源，避免第三次捕获因漏掉第二次的证据再次弹卡。
        finished[key]?.let { resolved -> finished[key] = resolved.copy(observations = dest.toList()) }
        while (observed.size > MAX_OBSERVED) observed.remove(observed.keys.first())
    }

    /**
     * 待确认 / AUTO 关联池中的统一同笔判定。候选不唯一则不自动合并（防止把不同账单串起来）。
     * 不登记新身份：只有调用方实际完成融合后，才能调用 [observe]。
     */
    @Synchronized
    fun findActive(
        candidates: List<PendingTransactionEntity>,
        incoming: CaptureObservation,
        capturedAt: Long,
    ): PendingTransactionEntity? {
        val matches = candidates.filter { pending ->
            pending.status != PendingStatus.ASK &&
                pending.amountCents > 0 &&
                matchesObservedCapture(
                    sources = observed[canonical(pending.dedupKey)]?.toList()
                        ?: listOf(CaptureObservation.fromPending(pending)),
                    firstCapturedAt = pending.createdAt,
                    incoming = incoming,
                    capturedAt = capturedAt,
                    windowMs = CAPTURE_WINDOW_MS,
                )
        }
        return matches.singleOrNull()
    }

    /** 保留用户处理前的已捕获来源集合：可能已合并微信/商家/银行三侧。 */
    @Synchronized
    fun markResolved(
        pending: PendingTransactionEntity,
        resolution: CaptureResolution,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = canonical(pending.dedupKey)
        val known = observed[key]?.toList().orEmpty().ifEmpty { listOf(CaptureObservation.fromPending(pending)) }
        remember(
            dedupKey = pending.dedupKey,
            sourcePackage = pending.sourcePackage,
            type = pending.type,
            amountCents = pending.amountCents,
            isRefund = pending.refundMark == RefundMark.REFUND_INCOME,
            eventTime = pending.createdAt,
            resolution = resolution,
            now = now,
            sources = known,
        )
    }

    /** 兼容补录/撤销调用：eventTime 必须传最初捕获时刻（不是后续回填/处理时刻）。 */
    @Synchronized
    fun remember(
        dedupKey: String,
        sourcePackage: String,
        type: TxType,
        amountCents: Long,
        isRefund: Boolean,
        eventTime: Long,
        resolution: CaptureResolution,
        now: Long = System.currentTimeMillis(),
        sources: List<CaptureObservation> = emptyList(),
    ) {
        if (dedupKey.isBlank()) return
        val key = canonical(dedupKey)
        val known = sources.ifEmpty { observed[key]?.toList().orEmpty() }
        val observations = if (known.isEmpty()) {
            listOf(CaptureObservation(
                CrossAppLinker.Evidence(sourcePackage, amountCents, type, isRefund, eventTime),
                flowIdentityOf(key), key,
            ))
        } else known
        finished[key] = FinishedCapture(
            sourcePackage, type, amountCents, isRefund,
            eventTime, flowIdentityOf(key), key, resolution, now, observations,
        )
        prune(now)
        while (finished.size > MAX_FINISHED) finished.remove(finished.keys.first())
        AppLog.d("Dedup", "logical tx resolved ${resolution.name} key=$key firstAt=$eventTime")
    }

    /** 终态与在池时同规则；命中后追加别名，第三、第四次的顺序不再影响识别。 */
    @Synchronized
    fun findResolved(
        incoming: CaptureObservation,
        capturedAt: Long,
        windowMs: Long = CAPTURE_WINDOW_MS,
    ): FinishedCapture? {
        prune(capturedAt)
        val matches = finished.values.filter {
            matchesFinishedCapture(it, incoming, capturedAt, minOf(windowMs, CAPTURE_WINDOW_MS))
        }
        val hit = matches.singleOrNull() ?: return null
        val key = canonical(hit.dedupKey)
        val dest = observed.getOrPut(key) { hit.observations.toMutableList() }
        addSource(dest, incoming)
        if (incoming.dedupKey.isNotBlank()) link(incoming.dedupKey, key)
        val updated = hit.copy(observations = dest.toList())
        finished[key] = updated  // 不修改 eventTime/at，后续捕获绝不续期
        return updated
    }

    /** 旧调用和测试兼容接口；生产代码用带来源证据的版本。 */
    @Synchronized
    fun findResolved(
        sourcePackage: String,
        type: TxType,
        amountCents: Long,
        isRefund: Boolean,
        identity: String?,
        eventTime: Long,
        now: Long = System.currentTimeMillis(),
        windowMs: Long = FINISHED_MEMORY_MS,
    ): FinishedCapture? = findResolved(
        CaptureObservation(
            CrossAppLinker.Evidence(sourcePackage, amountCents, type, isRefund, eventTime),
            identity.orEmpty(),
        ),
        capturedAt = eventTime,
        windowMs = windowMs,
    )

    @Synchronized
    fun forget(key: String) {
        if (key.isBlank()) return
        val target = canonical(key)
        finished.remove(target)
        observed.remove(target)
        aliases.remove(key)
        aliases.entries.removeAll { it.value == target }
    }

    @Synchronized
    internal fun clearAll() {
        aliases.clear()
        observed.clear()
        finished.clear()
    }

    @Synchronized
    internal fun aliasCount(): Int = aliases.size

    private fun addSource(sources: MutableList<CaptureObservation>, incoming: CaptureObservation) {
        if (sources.any { it.sourcePackage == incoming.sourcePackage && it.identity == incoming.identity &&
                (it.dedupKey == incoming.dedupKey || incoming.dedupKey.isBlank()) }) return
        if (sources.size < MAX_SOURCES_PER_TX) sources.add(incoming)
    }

    private fun prune(now: Long) {
        val iterator = finished.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.firstCapturedAt > CAPTURE_WINDOW_MS) iterator.remove()
        }
        // 观察记录不会让终态记录续期，独立别名容量也有上限。
    }
}

private fun identityKind(identity: String): String = when {
    identity.isBlank() -> ""
    identity.startsWith(NOTIFICATION_ID_PREFIX) -> "n"
    identity.startsWith(BANK_ID_PREFIX) -> "b"
    else -> "p"
}

/**
 * 同一笔所有来源共享一次固定时间窗口。相同应用、相同捕获通道存在不同实例身份则拒绝；
 * 跨应用根据实际支付链路判断，不把微信和支付宝两笔支付仅凭同额并起来。
 */
internal fun matchesObservedCapture(
    sources: List<CaptureObservation>,
    firstCapturedAt: Long,
    incoming: CaptureObservation,
    capturedAt: Long,
    windowMs: Long = LogicalTransactions.CAPTURE_WINDOW_MS,
): Boolean {
    if (sources.isEmpty()) return false
    if (sources.first().dedupKey.startsWith("ASK|") && incoming.dedupKey != sources.first().dedupKey) return false
    val elapsed = capturedAt - firstCapturedAt
    if (elapsed < 0 || elapsed > minOf(windowMs, LogicalTransactions.CAPTURE_WINDOW_MS)) return false
    val target = sources.first().evidence
    val fresh = incoming.evidence
    if (target.amountCents <= 0 || target.amountCents != fresh.amountCents ||
        target.type != fresh.type || target.isRefund != fresh.isRefund) return false
    val kind = identityKind(incoming.identity)
    // 多次已吸收的来源在同一个包内不能出现两种不同的 p:/n:/b: 实例。
    if (kind.isNotBlank() && sources.any {
            it.sourcePackage == incoming.sourcePackage &&
                identityKind(it.identity) == kind && it.identity.isNotBlank() && it.identity != incoming.identity
        }) return false
    return sources.any { source ->
        if (source.sourcePackage == incoming.sourcePackage) return@any true
        val other = source.evidence.copy(time = firstCapturedAt)
        val current = fresh.copy(time = capturedAt)
        // 支付应用 <-> 商家订单页，保留商户、支付渠道及资金操作冲突判断。
        when {
            other.isCheckoutPage -> CrossAppLinker.isSamePurchase(other, current)
            current.isCheckoutPage -> CrossAppLinker.isSamePurchase(current, other)
            CrossChannelDedup.isBankLedgerSide(other.sourcePackage) &&
                CrossChannelDedup.isBankLedgerSide(current.sourcePackage) ->
                isSameBankCapture(other, current)
            CrossChannelDedup.isPayChannelSide(other.sourcePackage) &&
                CrossChannelDedup.isBankLedgerSide(current.sourcePackage) ->
                isPayBankSame(other, current)
            CrossChannelDedup.isPayChannelSide(current.sourcePackage) &&
                CrossChannelDedup.isBankLedgerSide(other.sourcePackage) ->
                isPayBankSame(current, other)
            else -> false
        }
    }
}

/** 同一家银行短信、推送、页面在确认/忽略后也能匹配；不同银行或卡尾号冲突不能并。 */
private fun isSameBankCapture(a: CrossAppLinker.Evidence, b: CrossAppLinker.Evidence): Boolean {
    val aBank = CrossAppLinker.bankNameOf(a) ?: return false
    val bBank = CrossAppLinker.bankNameOf(b) ?: return false
    val aTail = BankLedgerRules.extractCardTail(
        listOf(a.instrument, a.merchantText, a.noteText).joinToString(" "),
    )
    val bTail = BankLedgerRules.extractCardTail(
        listOf(b.instrument, b.merchantText, b.noteText).joinToString(" "),
    )
    return BankEventDedup.matches(
        BankEventDedup.keyOf(aBank, a.amountCents, a.type, a.isRefund, aTail) ?: return false,
        BankEventDedup.keyOf(bBank, b.amountCents, b.type, b.isRefund, bTail) ?: return false,
    )
}

private fun isPayBankSame(pay: CrossAppLinker.Evidence, bank: CrossAppLinker.Evidence): Boolean {
    if (pay.type != TxType.EXPENSE || bank.type != TxType.EXPENSE || pay.isRefund || bank.isRefund) return false
    val bankName = CrossAppLinker.bankNameOf(bank).orEmpty()
    val payText = listOf(pay.instrument, pay.merchantText, pay.noteText, pay.ruleName).joinToString(" ")
    val bankText = listOf(bank.instrument, bank.merchantText, bank.noteText, bank.ruleName).joinToString(" ")
    val tail = Regex("""(?:尾号|末四位|末4位|\(|\*{2,})\s*(\d{4})""")
        .find(bankText)?.groupValues?.getOrNull(1)
    return CrossChannelDedup.payBankLink(payText, bankText, bankName, tail, pay.sourcePackage) != null
}

/** 纯函数形式，供旧的终态判定测试复用。 */
internal fun matchesFinishedCapture(
    candidate: FinishedCapture,
    sourcePackage: String,
    type: TxType,
    amountCents: Long,
    isRefund: Boolean,
    identity: String?,
    eventTime: Long,
    windowMs: Long,
): Boolean = matchesFinishedCapture(
    candidate,
    CaptureObservation(CrossAppLinker.Evidence(sourcePackage, amountCents, type, isRefund, eventTime), identity.orEmpty()),
    eventTime,
    windowMs,
)

internal fun matchesFinishedCapture(
    candidate: FinishedCapture,
    incoming: CaptureObservation,
    capturedAt: Long,
    windowMs: Long = LogicalTransactions.CAPTURE_WINDOW_MS,
): Boolean = matchesObservedCapture(
    candidate.observations.ifEmpty {
        listOf(CaptureObservation(
            CrossAppLinker.Evidence(
                candidate.sourcePackage, candidate.amountCents, candidate.type,
                candidate.isRefund, candidate.firstCapturedAt,
            ), candidate.identity, candidate.dedupKey,
        ))
    },
    candidate.firstCapturedAt,
    incoming,
    capturedAt,
    windowMs,
)
