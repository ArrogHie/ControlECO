package com.controleco.app.data.budget

/**
 * 「不计入预算 / AA 支出」的金额换算——纯函数，可单测。
 *
 * 一笔支出账单的**账面金额 m** 永远不变（账户余额、账单列表用原值），但下游有两个折算口径：
 * 1. **AA 折算后的支出**（[aaSplitCents]，统计页「实际支出」与分类占比用）：
 *    AA n 人 → m ÷ n（四舍五入到分）；普通账单 → m。**「不计入预算」不影响它**——钱确实花了。
 * 2. **计入本期预算的金额**（[contributionCents]，首页预算卡 / 分类剩余预算 / 统计页预算对比用）：
 *    不计入预算 → 0；否则同 1（AA → m ÷ n）。
 *
 * 两者同时勾选时：统计口径按 AA 折算，预算口径为 0（不计入优先）。
 *
 * **与 DAO 里的 SQL 必须逐字一致**（见 `TransactionDao`）：
 * - AA 折算：`CASE WHEN aaShares >= 2 THEN (amountCents + aaShares / 2) / aaShares ELSE amountCents END`
 * - 计入预算：`CASE WHEN budgetExcluded = 1 THEN 0 WHEN aaShares >= 2 THEN (amountCents + aaShares / 2) / aaShares ELSE amountCents END`
 *
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

    /**
     * **AA 折算后的支出**（不看「不计入预算」）：AA n 人 → m ÷ n（四舍五入到分），否则 m。
     * 统计页「实际支出」= Σ 这个值（再排除已退款）。
     */
    fun aaSplitCents(amountCents: Long, aaShares: Int): Long =
        if (aaShares >= MIN_AA_SHARES) (amountCents + aaShares / 2) / aaShares else amountCents

    /**
     * **计入预算的金额**（分）：不计入预算 → 0，否则同 [aaSplitCents]。
     * 首页预算卡、分类剩余预算、统计页预算对比用它。
     */
    fun contributionCents(amountCents: Long, budgetExcluded: Boolean, aaShares: Int): Long =
        if (budgetExcluded) 0L else aaSplitCents(amountCents, aaShares)

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
