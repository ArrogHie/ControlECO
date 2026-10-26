package com.controleco.app.data.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不计入预算 / AA 支出」的口径换算（[BudgetShare]）。
 *
 * 这里的期望值同时也是 DAO 里 SQL 表达式的对照口径：
 * ```
 * CASE WHEN budgetExcluded = 1 THEN 0
 *      WHEN aaShares >= 2 THEN (amountCents + aaShares / 2) / aaShares
 *      ELSE amountCents END
 * ```
 */
class BudgetShareTest {

    @Test
    fun `普通支出全部计入预算`() {
        assertEquals(12_34L, BudgetShare.contributionCents(12_34L, budgetExcluded = false, aaShares = 0))
        // 1 人不是 AA：按普通账单算
        assertEquals(12_34L, BudgetShare.contributionCents(12_34L, budgetExcluded = false, aaShares = 1))
    }

    @Test
    fun `不计入预算计入 0`() {
        assertEquals(0L, BudgetShare.contributionCents(12_34L, budgetExcluded = true, aaShares = 0))
        // 同时勾 AA 也不恢复：不计入优先
        assertEquals(0L, BudgetShare.contributionCents(12_34L, budgetExcluded = true, aaShares = 3))
    }

    @Test
    fun `AA 整除`() {
        // 30 元 3 人 → 10 元
        assertEquals(10_00L, BudgetShare.contributionCents(30_00L, budgetExcluded = false, aaShares = 3))
        // 20 元 2 人 → 10 元
        assertEquals(10_00L, BudgetShare.contributionCents(20_00L, budgetExcluded = false, aaShares = 2))
    }

    @Test
    fun `AA 四舍五入到分`() {
        // 10.00 元 3 人 = 3.333… → 3.33
        assertEquals(3_33L, BudgetShare.contributionCents(10_00L, budgetExcluded = false, aaShares = 3))
        // 10.01 元 3 人 = 3.336… → 3.34
        assertEquals(3_34L, BudgetShare.contributionCents(10_01L, budgetExcluded = false, aaShares = 3))
        // 0.01 元 3 人 = 0.0033 → 0（分以下不进位）
        assertEquals(0L, BudgetShare.contributionCents(1L, budgetExcluded = false, aaShares = 3))
        // 0.05 元 3 人 = 0.0166 → 0.02（向上进位）
        assertEquals(2L, BudgetShare.contributionCents(5L, budgetExcluded = false, aaShares = 3))
        // 5.00 元 2 人 → 2.50
        assertEquals(2_50L, BudgetShare.contributionCents(5_00L, budgetExcluded = false, aaShares = 2))
        // 2 人时 0.01 元 → 0.005 → 进位到 0.01
        assertEquals(1L, BudgetShare.contributionCents(1L, budgetExcluded = false, aaShares = 2))
    }

    @Test
    fun `AA 多份时金额不会超过原额`() {
        val amount = 99_99L
        (BudgetShare.MIN_AA_SHARES..BudgetShare.MAX_AA_SHARES).forEach { n ->
            val share = BudgetShare.contributionCents(amount, budgetExcluded = false, aaShares = n)
            assertTrue("n=$n share=$share", share in 0..amount)
        }
    }

    @Test
    fun `人数归一化`() {
        assertEquals(0, BudgetShare.normalizeShares(0))
        assertEquals(0, BudgetShare.normalizeShares(1))
        assertEquals(0, BudgetShare.normalizeShares(-5))
        assertEquals(2, BudgetShare.normalizeShares(2))
        assertEquals(99, BudgetShare.normalizeShares(99))
        assertEquals(99, BudgetShare.normalizeShares(1000))
    }

    @Test
    fun `人数解析只接受 2 到 99`() {
        assertNull(BudgetShare.parseShares(""))
        assertNull(BudgetShare.parseShares("  "))
        assertNull(BudgetShare.parseShares("1"))
        assertNull(BudgetShare.parseShares("0"))
        assertNull(BudgetShare.parseShares("100"))
        assertNull(BudgetShare.parseShares("abc"))
        assertEquals(2, BudgetShare.parseShares("2"))
        assertEquals(12, BudgetShare.parseShares(" 12 "))
        assertEquals(99, BudgetShare.parseShares("99"))
    }

    @Test
    fun `列表小标`() {
        assertNull(BudgetShare.tag(budgetExcluded = false, aaShares = 0))
        assertNull(BudgetShare.tag(budgetExcluded = false, aaShares = 1))
        assertEquals("不计预算", BudgetShare.tag(budgetExcluded = true, aaShares = 0))
        // 同时勾选：列表只显示「不计预算」（预算计入 0，AA 不生效）
        assertEquals("不计预算", BudgetShare.tag(budgetExcluded = true, aaShares = 4))
        assertEquals("AA 4", BudgetShare.tag(budgetExcluded = false, aaShares = 4))
    }
}
