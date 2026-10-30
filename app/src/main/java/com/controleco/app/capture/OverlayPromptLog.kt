package com.controleco.app.capture

import com.controleco.app.util.AppLog

/**
 * "这笔已经给用户弹过悬浮确认卡"的登记簿（纯内存，按**逻辑交易**索引）。
 *
 * 为什么需要它（用户口径 2026-10-27）：**同一笔只在首次捕获时打扰一次**。
 * 用户在卡片上确认/关闭后，同一笔往往还会被另一条通道再抓一次（微信付款 → 返回电商结果页），
 * 富化时若再弹一张卡，用户会以为是"又抓了一笔"，点「忽略」就把唯一那笔已入账的账删掉。
 *
 * 但仍要保留一条**正当的补弹路径**：首次弹卡可能被华为/小米"支付保护层"或系统弹层拦截
 * （`addView` 失败、`overlayConditionsOk` 不满足），等用户回到结果页时补试一次是必要的。
 * 因此判定条件是"**是否成功展示过**"，而不是"是否尝试过"：
 * - 成功展示 → 登记 → 后续富化只刷新正在显示的卡片（`refreshCardIfShowing`），**不再新弹**；
 * - 没成功展示 → 未登记 → 富化时仍允许补试一次（`retryCardIfNoCard`）。
 *
 * **键口径（2026-10-30 修正）**：所有读写都按 [LogicalTransactions] 的等价关系判断，
 * 而不是字面比较去重键。同一笔真实支付从不同通道到达时会得到不同的去重键
 * （`@n:` 通知 / `@p:` 页面流程 / `@b:` 银行事件），只按原始去重键索引就会把
 * "同一笔的第二个通道"看成"从没弹过"——这正是重复弹卡的根因。
 * 归一到逻辑交易后，"一次真实支付最多打扰一次"才成立。
 *
 * 刻意不落库：与 [OverlayFieldGuards] 同一种取舍，进程重启后失效
 * （代价：重启后同一笔可能被弹第二次，用户已知并接受）。容量上限 [MAX_ENTRIES]，只留最近一批。
 */
internal object OverlayPromptLog {

    private const val MAX_ENTRIES = 500

    /** 按插入顺序淘汰；所有访问在同一把锁里（调用方来自采集协程与主线程） */
    private val shown: MutableSet<String> = LinkedHashSet()

    @Synchronized
    fun markShown(dedupKey: String) {
        if (dedupKey.isBlank()) return
        val key = LogicalTransactions.canonical(dedupKey)
        shown.add(key)
        while (shown.size > MAX_ENTRIES) {
            val it = shown.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
        AppLog.d("Overlay", "card already shown once key=$key")
    }

    /**
     * 是否已经为**同一笔**成功展示过卡片。
     *
     * 判据是"登记过的键里存在与本键同一逻辑交易的键"（[LogicalTransactions.sameLogical]）：
     * 别名可能晚于登记建立（页面先弹卡、银行侧后到才建别名），字面比较会漏判。
     */
    @Synchronized
    fun wasShown(dedupKey: String): Boolean {
        if (dedupKey.isBlank()) return false
        if (LogicalTransactions.canonical(dedupKey) in shown) return true
        return shown.any { LogicalTransactions.sameLogical(it, dedupKey) }
    }

    /** 显式清理单键（兼容旧调用/测试）：同一逻辑交易的所有登记一并移除 */
    @Synchronized
    fun clear(dedupKey: String) {
        if (dedupKey.isBlank()) return
        shown.remove(LogicalTransactions.canonical(dedupKey))
        shown.removeAll { LogicalTransactions.sameLogical(it, dedupKey) }
    }

    /** 仅测试用 */
    internal fun clearAll() {
        synchronized(this) { shown.clear() }
    }

    /** 仅测试用：登记条目数 */
    internal fun size(): Int = synchronized(this) { shown.size }
}
