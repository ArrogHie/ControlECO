package com.controleco.app.capture

import com.controleco.app.data.db.TxType

/**
 * 稳定页面的短期去重缓存（页面通道专用）。
 *
 * 解决的问题：同一份带**明确交易时间**的结果页（收款到账页/账单详情页）会因为
 * 重复采样、跨分钟、支付流程重置（再次进入收银台会换一个流程身份）而再次生成候选，
 * 数据库去重键（分钟桶 + 流程标识）拦不住它，最终重复入账/重复弹卡。
 *
 * 只缓存 [com.controleco.app.capture.page.PageCandidate.txTime] 非空的候选：
 * 交易时间来自页面本身（"转账时间 2026年10月17日 17:36:13"），不随捕获分钟变化，
 * 也不受流程重置影响。没有交易时间的成功页**不缓存**——两笔真实同额交易的页面文本
 * 可能完全相同，能否区分必须交给流程身份（PageFlow）与"最近同额"逻辑，
 * 不能在这里静默吞掉第二笔。
 *
 * 容量有界、TTL 短：页面身份只在"同一笔的重复观察"这种短时间尺度内有意义，
 * 过期后重新出现视为新交易（宁可让后续的流程/同额预检再判一次，也不长期压住页面）。
 * 线程安全（采集中心在 IO 线程处理快照），时钟可注入便于单测。
 */
internal class RecentPageCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 页面身份 → 首次观察时刻（重复命中不刷新，TTL 从首次观察起算） */
    private val seenAt = LinkedHashMap<String, Long>()
    private val lock = Any()

    /**
     * 记录一次页面身份。返回 true = 该身份在 TTL 内已经出现过，调用方应跳过
     * 融合/入账/pending/提醒，只把本次原始快照标记为重复。
     */
    fun markIfDuplicate(key: String): Boolean = synchronized(lock) {
        val now = clock()
        removeExpired(now)
        if (seenAt.containsKey(key)) {
            true
        } else {
            seenAt[key] = now
            trimToCapacity()
            false
        }
    }

    /** 当前缓存条数（单测验证容量有界） */
    fun size(): Int = synchronized(lock) { seenAt.size }

    fun clear() = synchronized(lock) { seenAt.clear() }

    private fun removeExpired(now: Long) {
        val iterator = seenAt.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > ttlMs) iterator.remove()
        }
    }

    private fun trimToCapacity() {
        while (seenAt.size > capacity) {
            val iterator = seenAt.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    companion object {
        /** 5 分钟内同一应用正常只会出现个位数结果页；128 足以覆盖异常重渲染风暴 */
        const val DEFAULT_CAPACITY = 128

        /** 去重窗口：覆盖同一页面的重复采样与流程重置后的再次识别 */
        const val DEFAULT_TTL_MS = 5 * 60_000L
    }
}

/**
 * 稳定页面身份：`包名 | 方向 | 是否退款 | 规则名 | 金额 | 页面交易时间`。
 *
 * 用**精确交易时间**而不是捕获分钟：同一笔收款/详情页在下一分钟被重新采样、
 * 或支付流程被重置后再次识别，身份都不变。调用方只对 `txTime > 0` 的候选生成该键。
 */
internal fun stablePageKey(
    packageName: String,
    type: TxType,
    isRefund: Boolean,
    ruleName: String,
    amountCents: Long,
    txTime: Long,
): String = listOf(
    packageName,
    type.name,
    isRefund.toString(),
    ruleName,
    amountCents.toString(),
    txTime.toString(),
).joinToString("|")
