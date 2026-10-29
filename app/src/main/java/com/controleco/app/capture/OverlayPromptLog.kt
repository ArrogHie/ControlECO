package com.controleco.app.capture

import com.controleco.app.util.AppLog

/**
 * "这条条目已经给用户弹过悬浮确认卡"的登记簿（纯内存，按条目去重键索引）。
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
 * 用去重键而不是自增 id 作索引：条目被删后自增 id 可能被复用，会让本该补弹的条目被误判成"已弹过"。
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
        shown.add(dedupKey)
        while (shown.size > MAX_ENTRIES) {
            val it = shown.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
        AppLog.d("Overlay", "card already shown once key=$dedupKey")
    }

    @Synchronized
    fun wasShown(dedupKey: String): Boolean = dedupKey.isNotBlank() && dedupKey in shown

    /** 显式清理单键（兼容旧调用/测试）；普通确认或忽略不应调用，否则迟到的采集会重复弹卡。 */
    @Synchronized
    fun clear(dedupKey: String) {
        if (dedupKey.isNotBlank()) shown.remove(dedupKey)
    }

    /** 仅测试用 */
    internal fun clearAll() {
        synchronized(this) { shown.clear() }
    }
}
