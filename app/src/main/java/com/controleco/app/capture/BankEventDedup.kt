package com.controleco.app.capture

import com.controleco.app.data.db.TxType

/**
 * 同一家银行的多通道去重（短信 ⇄ 银行 App 推送 ⇄ 银行 App 页面）。
 *
 * 场景：一笔卡消费在同一家银行侧往往留下**多条**消息——动账短信、银行 App 推送，
 * 有时还有银行 App 的交易详情页。三者的来源键互不相同（`SMS:招商银行` / `cmb.pb`），
 * 于是去重键（`NotificationRules.dedupKey`）与银行事件身份（`bankInstanceId`）也天然不同，
 * 不归并就会变成两三条账单（用户实测：微信卡支付 → 微信页面 + 银行短信 + 银行通知 → 记了两次）。
 *
 * 判定（2026-10-27 用户规则）：
 * ① 同一家银行（[standardBankName] 归一后相等，`招行` ≡ `招商银行`）；
 * ② 金额分全等、方向相同、退款标记相同；
 * ③ 时间差 ≤ [WINDOW_MS]；
 * ④ 卡尾号：两侧都有则**必须相等**；缺一侧视为通配（真实招行推送只写「您账户2532」，
 *    [BankLedgerRules.extractCardTail] 取不到尾号，若要求两侧都有就永远配不上）；
 * ⑤ 两条必须来自**不同通道**（同一通道的重复消息由 `bankInstanceId` 负责）。
 *
 * 为什么不是"同额就并"：同一家银行 180 秒内完全可能出现两笔真实同额消费，无限吸收会丢一笔。
 * 因此配对按 **1:1**（每条条目对每个通道最多吸收一条），实现见 [BankEventClaims]。
 */
internal object BankEventDedup {

    /** 配对窗口：银行短信到达通常比推送慢，与「微信 ⇄ 银行」保持同一口径 */
    const val WINDOW_MS = LogicalTransactions.CAPTURE_WINDOW_MS

    /** 消息来自哪条通道：同通道的重复由 bankInstanceId 处理，跨通道才需要本模块归并 */
    enum class Channel { SMS, PUSH, PAGE }

    /** 一条银行侧动账消息的关键特征（银行名已归一） */
    data class Key(
        val bankName: String,
        val amountCents: Long,
        val type: TxType,
        val isRefund: Boolean,
        /** 卡尾号；null/空 = 该消息没写尾号（通配） */
        val cardTail: String?,
    )

    /**
     * 构造特征。[bankName] 可以是别名（"招行"）或包名映射出的标准名；归一后为空则不参与归并。
     */
    fun keyOf(
        bankName: String,
        amountCents: Long,
        type: TxType,
        isRefund: Boolean,
        cardTail: String?,
    ): Key? {
        val standard = standardBankName(bankName)
        if (standard.isEmpty()) return null
        val tail = cardTail?.trim()?.takeIf { it.length == 4 }
        return Key(standard, amountCents, type, isRefund, tail)
    }

    /** 银行名归一：`招行`→`招商银行`、`中国工商银行`→`工商银行`；认不出原样返回（去空白） */
    fun standardBankName(bankName: String): String {
        val trimmed = bankName.trim()
        if (trimmed.isEmpty()) return ""
        return BankLedgerRules.detectBankName(trimmed) ?: trimmed
    }

    /** 尾号是否兼容：都有则必须相等；缺一侧视为通配 */
    fun tailsCompatible(a: String?, b: String?): Boolean {
        val tailA = a?.trim().orEmpty()
        val tailB = b?.trim().orEmpty()
        if (tailA.isEmpty() || tailB.isEmpty()) return true
        return tailA == tailB
    }

    /** 是否描述同一笔动账（不含通道与时间判定，时间由 [BankEventClaims] 负责） */
    fun matches(a: Key, b: Key): Boolean =
        a.bankName == b.bankName &&
            a.amountCents == b.amountCents &&
            a.type == b.type &&
            a.isRefund == b.isRefund &&
            tailsCompatible(a.cardTail, b.cardTail)
}

/**
 * 内存中的"银行事件归属"登记表：某条待确认/自动入账条目已吸收了哪条通道的哪一笔。
 *
 * 只放在内存里（用户 2026-10-27 决定：短信/通知都只投递一次，不做身份持久化）：
 * 配对窗口只有 [BankEventDedup.WINDOW_MS]，窗口一过本就不再合并，因此内存标记足够，
 * 且不需要为它升级数据库。
 *
 * 语义（1:1 配对）：[find] 只返回"还没吸收过该通道"的条目，
 * 于是 180 秒内两笔真实同额消费各自产生的短信/推送不会被并成一条（丢账）。
 * 代价（已知边界）：进程在这 180 秒内被杀掉/重启后标记丢失，该窗口内的 1:1 保护失效。
 */
internal class BankEventClaims(
    private val ttlMs: Long = 5 * 60_000L,
    private val cap: Int = 64,
) {

    private data class Claim(
        val key: BankEventDedup.Key,
        val pendingId: Long,
        val channels: MutableSet<BankEventDedup.Channel>,
        /** 首次捕获时刻：后到短信/推送/页面不能刷新这个值 */
        val firstCapturedAt: Long,
        var at: Long,
    )

    private val claims = LinkedHashMap<Long, Claim>()

    /** 登记：条目 [pendingId] 已吸收来自 [channel] 的这笔动账（同一条目可来自多个通道） */
    fun claim(
        pendingId: Long,
        key: BankEventDedup.Key,
        channel: BankEventDedup.Channel,
        eventTime: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        if (pendingId <= 0) return
        prune(now)
        val existing = claims[pendingId]
        if (existing != null) {
            existing.channels += channel
            // 允许登记新通道，但不刷新首次捕获时刻。
            existing.at = now
            return
        }
        claims[pendingId] = Claim(key, pendingId, mutableSetOf(channel), eventTime, now)
        while (claims.size > cap) {
            val eldestId = claims.keys.firstOrNull() ?: break
            claims.remove(eldestId)
        }
    }

    /**
     * 找出可以吸收本次[channel]消息的既有条目；没有则返回 null（调用方另建一条）。
     * 多条命中时取消息时间最接近的一条（1:1 配对，避免把两笔同额合成一笔）。
     */
    fun find(
        key: BankEventDedup.Key,
        channel: BankEventDedup.Channel,
        eventTime: Long,
        now: Long = System.currentTimeMillis(),
    ): Long? {
        prune(now)
        var best: Claim? = null
        var bestDelta = Long.MAX_VALUE
        for (claim in claims.values) {
            if (channel in claim.channels) continue
            if (!BankEventDedup.matches(key, claim.key)) continue
            val delta = eventTime - claim.firstCapturedAt
            if (delta !in 0..BankEventDedup.WINDOW_MS) continue
            if (delta < bestDelta) {
                best = claim
                bestDelta = delta
            }
        }
        return best?.pendingId
    }

    /** 条目已被用户确认/丢弃（从库里消失）时清掉登记，避免继续向不存在的条目合并 */
    fun forget(pendingId: Long) {
        claims.remove(pendingId)
    }

    /** 仅供测试：清空 */
    fun clear() = claims.clear()

    fun size(): Int = claims.size

    private fun prune(now: Long) {
        val iterator = claims.entries.iterator()
        while (iterator.hasNext()) {
            val claim = iterator.next().value
            if (now - claim.at > ttlMs || now - claim.firstCapturedAt > BankEventDedup.WINDOW_MS) {
                iterator.remove()
            }
        }
    }
}
