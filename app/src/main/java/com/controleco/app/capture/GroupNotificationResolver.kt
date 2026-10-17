package com.controleco.app.capture

/**
 * 折叠通知（系统把多条通知收成「[2条]微信支付…」）的金额补全
 * （见 docs/自动记账捕获机制.md 第一节「机制速览」）。
 *
 * 为什么要做：微信的"退款到账通知"、付款码与银行动账等常被系统折叠，**摘要 extras 里没有金额**，
 * 只读摘要必然漏记。做法是从系统当前活跃通知里找出同组的子通知，用它的文本与时间替代摘要。
 *
 * 自测中踩过的坑（见未合并分支 feat/capture-refs-integration 的早期草稿）：
 * 1. "同包 5 分钟内最近通知"式的回退会把 5 分钟前的**无关**支付通知配到当前摘要上，
 *    把旧金额记成新交易；这里把回退窗收紧到 90 秒，且候选必须自身就是一条可解析的账务明细；
 * 2. 候选挑选与判定是纯函数（只有 [NotificationText] 这个数据类，不依赖 Android 框架），可直接单测；
 * 3. 用子通知的 postTime 作为交易时间，与去重键的分钟桶一致。
 */
data class NotificationText(
    val key: String,
    val packageName: String,
    val groupKey: String?,
    val group: String?,
    val postTime: Long,
    /** 系统记录的发布时间（StatusBarNotification.postTime），`when` 异常时的可靠兜底 */
    val systemPostTime: Long = 0L,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val ticker: String = "",
) {
    /** 子通知的合并文本（title/text/bigText/ticker 去重并保长） */
    fun mergedText(): String = joinDistinct(listOf(title, text, bigText, ticker))

    companion object {
        /**
         * 同一份文本被 title/text/bigText 重复承载时只保留最长的那份——
         * 新文本包含旧的就替换，旧文本包含新的就丢弃（避免同一句话在正文里出现两遍）。
         */
        fun joinDistinct(parts: List<String>, separator: String = " "): String {
            val kept = ArrayList<String>(parts.size)
            for (raw in parts) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                if (kept.any { it.contains(s) }) continue
                kept.removeAll { s.contains(it) }
                kept.add(s)
            }
            return kept.joinToString(separator)
        }
    }
}

object GroupNotificationResolver {

    /** 同包名回退的时间窗：5 分钟级窗口过宽（会捞到无关的旧支付通知），收紧到 90 秒 */
    const val FALLBACK_WINDOW_MS = 90_000L

    /**
     * 摘要是否需要子通知补全：确实像折叠摘要，且自身解析不出账务明细。
     * 摘要自己就带金额时不该去捞兄弟通知（否则可能抓错）。
     */
    fun needsEnrichment(summary: NotificationText): Boolean {
        val merged = summary.mergedText()
        if (merged.isBlank()) return false
        if (!NotificationRules.looksLikeGroupedSummary(merged)) return false
        return !NotificationRules.looksLikeLedgerDetail(merged)
    }

    /**
     * 从活跃通知里挑出最可能承载金额的那条子通知。
     * 优先同 groupKey / notification.group；退而同包名 + 时间窗内、且自身像账务明细的最近一条。
     */
    fun pickChild(summary: NotificationText, candidates: List<NotificationText>): NotificationText? {
        val others = candidates.filter { it.key != summary.key && it.packageName == summary.packageName }
        if (others.isEmpty()) return null

        others.asSequence()
            .filter { sameGroup(summary, it) }
            .sortedByDescending { it.postTime }
            .firstOrNull { NotificationRules.looksLikeLedgerDetail(it.mergedText()) }
            ?.let { return it }

        return others.asSequence()
            .filter { kotlin.math.abs(it.postTime - summary.postTime) <= FALLBACK_WINDOW_MS }
            .filter { NotificationRules.looksLikeLedgerDetail(it.mergedText()) }
            .maxByOrNull { it.postTime }
    }

    private fun sameGroup(a: NotificationText, b: NotificationText): Boolean =
        (!a.groupKey.isNullOrBlank() && a.groupKey == b.groupKey) ||
            (!a.group.isNullOrBlank() && a.group == b.group)
}
