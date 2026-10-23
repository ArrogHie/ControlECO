package com.controleco.app.ui.transactions

import com.controleco.app.data.PeriodMode
import java.time.YearMonth

/**
 * 账单列表「什么时候才回顶」的纯逻辑（不依赖 Compose，便于单测）。
 *
 * 背景：账单页用 `LaunchedEffect(month, mode)` / `LaunchedEffect(filter)` 实现
 * 「周期/筛选一变就回顶并重置分页」。问题是 LaunchedEffect 只要**重新进入组合**就会重跑，
 * 从 `edit/{txId}` 返回、切 tab 回来都算一次重新进入组合；而同一帧里
 * `rememberLazyListState` 刚把滚动位置恢复回来，无条件 `scrollToItem(0)` 立刻又抹掉，
 * 表现就是「点进某笔账单 → 返回 → 列表跳回顶端」。
 *
 * 解法：把「上一次生效的键」记在 `rememberSaveable` 里，只有键真的变了才回顶；
 * 首次进入（没有历史键）和重进组合（键相同）都保留恢复好的滚动位置。
 */

/** 周期键：周期模式 + 年 + 月，用来判断「周期是否真的变了」 */
internal fun billPeriodKey(month: YearMonth, mode: PeriodMode): String =
    "$mode:${month.year}-${month.monthValue}"

/**
 * @param previousKey 上一次生效的键；null = 本次是首次进入组合
 * @return true 表示需要回顶并重置分页（键确实变化过）
 */
internal fun shouldResetBillList(previousKey: String?, currentKey: String): Boolean =
    previousKey != null && previousKey != currentKey
