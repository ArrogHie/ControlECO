package com.controleco.app.capture

import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 采集层与 UI 层共用的辅助判定（纯函数，便于单测）。
 */

/**
 * 自动入账前的重复预检窗口（±60s 数据库校验）：
 * 合并池里已有"同来源 + 同方向 + 同金额、时间在窗口内"的条目时不再静默入账——
 * 重复通知/分钟边界重发不会造成双记账；调用方回落到"入队 + 提醒"，用户仍然看得到这笔。
 */
internal const val AUTO_ENTRY_DEDUP_WINDOW_MS = 60_000L

/** 通知 `when` 的合理范围：早于 24 小时或晚于当前 5 分钟都视为异常值 */
internal const val EVENT_TIME_MAX_AGE_MS = 24 * 3_600_000L
internal const val EVENT_TIME_MAX_FUTURE_SKEW_MS = 5 * 60_000L

/**
 * 通知/短信事件时间的兜底（纯函数）：
 * - `when <= 0`（部分应用不设置）、明显在未来、或早于一天前 → 用采集时刻；
 * - 其余保留原值（交易可能发生在几分钟前，不能粗暴改成采集时刻）。
 *
 * 该值同时用于分钟去重桶与入账时间，避免零值/异常值把账记到 1970 年或未来。
 */
internal fun sanitizeEventTime(
    rawEventTime: Long,
    capturedAt: Long,
    maxAgeMs: Long = EVENT_TIME_MAX_AGE_MS,
    maxFutureSkewMs: Long = EVENT_TIME_MAX_FUTURE_SKEW_MS,
): Long {
    if (rawEventTime <= 0) return capturedAt
    if (rawEventTime > capturedAt + maxFutureSkewMs) return capturedAt
    if (rawEventTime < capturedAt - maxAgeMs) return capturedAt
    return rawEventTime
}

/**
 * 通知时间的优先级（纯函数）：
 * 1. `notification.when` 合理 → 用它（显示时间，通常就是交易发生时刻）；
 * 2. `when` 为 0/异常 → 用系统 `StatusBarNotification.postTime`；
 * 3. 两者都不可用 → 采集时刻。
 *
 * 区分三者是为了避免零值/未来值污染分钟去重桶与账单日期，同时尽量保留真实交易时刻。
 */
internal fun resolveNotificationTime(
    displayTime: Long,
    systemPostTime: Long,
    capturedAt: Long,
): Long {
    val display = sanitizeEventTime(displayTime, capturedAt)
    if (displayTime > 0 && display == displayTime) return display
    val posted = sanitizeEventTime(systemPostTime, capturedAt)
    if (systemPostTime > 0 && posted == systemPostTime) return posted
    return capturedAt
}

internal fun hasRecentSameEntry(
    candidates: List<PendingTransactionEntity>,
    sourcePackage: String,
    type: TxType,
    amountCents: Long,
    now: Long,
    windowMs: Long = AUTO_ENTRY_DEDUP_WINDOW_MS,
    /**
     * 本条候选的交易时间（页面**账单详情页**会带上"原始交易时刻"）：同额同来源时，
     * 既比"捕获时刻"也比"交易时刻"——否则用户在付款一分钟后再翻出该笔的账单详情页，
     * 会被当成新的一笔自动入账（重复记账）。传 null 时行为与只比捕获时刻一致。
     */
    txTime: Long? = null,
): Boolean = candidates.any {
    it.sourcePackage == sourcePackage && it.type == type && it.amountCents == amountCents &&
        (abs(now - it.createdAt) <= windowMs ||
            (txTime != null && txTime > 0 && abs(txTime - it.timestamp) <= windowMs))
}

/**
 * 入账方式的启动校正（纯函数，便于单测）。
 *
 * 默认取向（定稿）：自动入账开、悬浮窗入账关。互斥逻辑只在"切换开关"时生效，
 * 旧版本两个开关彼此独立，机内可能留下"两个都开"的历史状态——启动时收敛，
 * 保证任何时刻只有一种"免确认"方式在生效：
 * - 悬浮窗是**显式**开启、自动入账从未设置过（"开"只是新默认值带来的）→ 尊重用户的悬浮窗选择；
 * - 其余"两个都开"→ 按定稿默认取向：自动入账优先，关掉悬浮窗入账。
 */
internal enum class EntryModeFix { NONE, DISABLE_OVERLAY, DISABLE_AUTO }

internal fun entryModeFix(
    autoSet: Boolean,
    overlaySet: Boolean,
    autoEntry: Boolean,
    overlayConfirm: Boolean,
): EntryModeFix = when {
    autoEntry && overlayConfirm && !autoSet && overlaySet -> EntryModeFix.DISABLE_AUTO
    autoEntry && overlayConfirm -> EntryModeFix.DISABLE_OVERLAY
    else -> EntryModeFix.NONE
}

/**
 * 捕获到账单后是否"先直接入账"（纯函数，便于单测）。
 *
 * 定稿语义：三种入账方式（入账提醒 / 悬浮窗入账 / 自动入账）任一开启，捕获即先入账——
 * - 悬浮窗入账：入账后弹卡，「忽略」删除这笔、改完关掉卡片即同步（卡片弹不出时账也不丢）；
 * - 入账提醒：入账后发通知告诉用户"记了一笔账"（通知不是"去待确认列表处理"的入口）；
 * - 自动入账：入账后不打扰。
 * 三个都关 = 完全手动模式：退回待确认队列（保留"只攒着让我自己确认"的用法）。
 */
internal fun directEntryEnabled(
    autoEntry: Boolean,
    overlayConfirm: Boolean,
    captureNotify: Boolean,
): Boolean = autoEntry || overlayConfirm || captureNotify
