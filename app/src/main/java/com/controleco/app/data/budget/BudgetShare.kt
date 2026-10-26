package com.controleco.app.data.budget

/**
 * 「不计入预算 / AA 支出」的预算口径换算——纯函数，可单测。
 *
 * 一笔支出账单的**账面金额 m** 永远不变（账户余额、账单列表、统计页「实际支出」都用它）；
 * 只有「计入本期预算的金额」按这里的规则折算：
 * - 不计入预算 → 0；
 * - AA 支出 n 人 → m ÷ n（四舍五入到分）；
 * - 两者同时勾选 → 0（不计入优先，AA 人数保留但不生效）；
 * - 普通账单 → m。
 *
 * **与 DAO 里的 SQL 必须逐字一致**（见 `TransactionDao.observeBudgetSpendingTotal` /
 * `observeBudgetCategorySums`）：
 * ```
 * CASE WHEN budgetExcluded = 1 THEN 0
 *      WHEN aaShares >= 2 THEN (amountCents + aaShares / 2) / aaShares
 *      ELSE amountCents END
 * ```
 * SQLite 与 Kotlin 的整数除法都向零截断，金额恒为正，所以两边结果相同。
 */
object BudgetShare {

    /** AA 至少 2 人（1 人 = 不是 AA，按普通账单算） */
    const val MIN_AA_SHARES = 2

    /** AA 人数上限（两人以上的聚餐/合租场景够用，且避免误输入把金额摊成 0） */
    const val MAX_AA_SHARES = 99

    /** 勾选 AA 时的默认人数 */
    const val DEFAULT_AA_SHARES = 2

    /** 输入归一化：< 2 一律视为未开启（0），上限 [MAX_AA_SHARES] */
    fun normalizeShares(shares: Int): Int =
        if (shares < MIN_AA_SHARES) 0 else shares.coerceAtMost(MAX_AA_SHARES)

    /** 输入框文本 → 人数：非法或越界返回 null（由调用方给出错误提示） */
    fun parseShares(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in MIN_AA_SHARES..MAX_AA_SHARES }

    /** 计入预算的金额（分） */
    fun contributionCents(amountCents: Long, budgetExcluded: Boolean, aaShares: Int): Long = when {
        budgetExcluded -> 0L
        aaShares >= MIN_AA_SHARES -> (amountCents + aaShares / 2) / aaShares
        else -> amountCents
    }

    /**
     * 账单列表小标（与「已退 / 退款」小标同一位置）：不计预算 → `不计预算`；AA → `AA 3`；
     * 普通账单返回 null（不占位）。
     */
    fun tag(budgetExcluded: Boolean, aaShares: Int): String? = when {
        budgetExcluded -> "不计预算"
        aaShares >= MIN_AA_SHARES -> "AA $aaShares"
        else -> null
    }
}
