package com.controleco.app.ui.home

import com.controleco.app.data.budget.Budget
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页分类模式实际渲染用的 [buildCategoryBars] 口径。
 * 「未设预算分类」= 兜住没设预算的支出分类，与旧口径 [buildCategoryRings] 同规则。
 */
class CategoryBarsTest {

    private fun category(id: Long, name: String, type: TxType = TxType.EXPENSE) =
        CategoryEntity(id = id, name = name, type = type)

    private fun sum(id: Long, name: String, cents: Long) =
        CategorySumRow(categoryId = id, categoryName = name, totalCents = cents)

    @Test
    fun `没设预算的分类不单独成条_支出落进未设预算分类条`() {
        val bars = buildCategoryBars(
            budgets = mapOf(1L to 1_000_00),
            sums = listOf(sum(1, "餐饮", 400_00), sum(2, "医疗", 300_00)),
            categories = listOf(category(1, "餐饮"), category(2, "医疗")),
            monthlyBudgetCents = 3_000_00,
            totalSpentCents = 700_00,
        )
        assertTrue(bars.none { it.name == "医疗" })
        val bucket = bars.first { it.isOther }
        assertEquals(NO_BUDGET_BUCKET_NAME, bucket.name)
        assertEquals(2_000_00, bucket.budgetCents) // 月预算 3000 − 已分配 1000
        assertEquals(300_00, bucket.spentCents) // 700 − 400，就是医疗那笔
        assertEquals(1_700_00, bucket.remainingCents)
    }

    @Test
    fun `分类预算之和超过月预算但有未设预算支出时_仍然出条且剩余为负`() {
        val bars = buildCategoryBars(
            budgets = mapOf(1L to 1_200_00),
            sums = listOf(sum(1, "餐饮", 500_00), sum(2, "医疗", 700_00)),
            categories = listOf(category(1, "餐饮"), category(2, "医疗")),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 1_200_00,
        )
        val bucket = bars.first { it.isOther }
        assertEquals(NO_BUDGET_BUCKET_NAME, bucket.name)
        assertEquals(0L, bucket.budgetCents) // 残值为负按 0，不给负预算
        assertEquals(700_00, bucket.spentCents)
        assertEquals(-700_00, bucket.remainingCents)
        assertEquals(Budget.Tone.LOW, bucket.tone)
    }

    @Test
    fun `既没有残值也没有支出时_不出未设预算分类条`() {
        val bars = buildCategoryBars(
            budgets = mapOf(1L to 1_000_00),
            sums = emptyList(),
            categories = listOf(category(1, "餐饮")),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 0L,
        )
        assertEquals(listOf("餐饮"), bars.map { it.name })
        assertTrue(bars.none { it.isOther })
    }

    @Test
    fun `无预算的合成条排在正常分类之前`() {
        val bars = buildCategoryBars(
            budgets = mapOf(1L to 1_000_00),
            sums = listOf(sum(1, "餐饮", 100_00), sum(2, "医疗", 700_00)),
            categories = listOf(category(1, "餐饮"), category(2, "医疗")),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 800_00,
        )
        // 餐饮剩 90%（ratio 0.9）；合成条 ratio 用占位 −1 → 排最前
        assertEquals(NO_BUDGET_BUCKET_NAME, bars.first().name)
        assertEquals(0.9f, bars.last().ratio, 0.0001f)
    }

    @Test
    fun `真实的其他分类与合成条并存且不再重名`() {
        val bars = buildCategoryBars(
            budgets = mapOf(1L to 500_00, 2L to 300_00),
            sums = listOf(sum(1, "餐饮", 200_00), sum(2, "其他", 100_00), sum(3, "医疗", 400_00)),
            categories = listOf(category(1, "餐饮"), category(2, "其他"), category(3, "医疗")),
            monthlyBudgetCents = 2_000_00,
            totalSpentCents = 700_00,
        )
        val real = bars.first { it.name == "其他" }
        assertFalse(real.isOther)
        assertEquals(300_00, real.budgetCents)
        val bucket = bars.first { it.isOther }
        assertEquals(NO_BUDGET_BUCKET_NAME, bucket.name)
        assertEquals(1_200_00, bucket.budgetCents) // 2000 − 500 − 300
        assertEquals(400_00, bucket.spentCents) // 700 − 200 − 100
    }

    @Test
    fun `收入分类与已删除分类的预算不占已分配`() {
        val bars = buildCategoryBars(
            budgets = mapOf(1L to 100_00, 2L to 200_00, 9L to 300_00),
            sums = listOf(sum(1, "工资", 50_00)),
            categories = listOf(
                category(1, "工资", TxType.INCOME),
                category(2, "餐饮", TxType.EXPENSE),
                // 9L 已删除：不在 categories 中
            ),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 100_00,
        )
        assertEquals(listOf("餐饮", NO_BUDGET_BUCKET_NAME), bars.sortedBy { it.isOther }.map { it.name })
        val bucket = bars.first { it.isOther }
        assertEquals(800_00, bucket.budgetCents) // 1000 − 200（收入/已删分类的预算不算已分配）
        assertEquals(100_00, bucket.spentCents)
    }
}
