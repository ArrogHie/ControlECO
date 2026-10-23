package com.controleco.app.ui.stats

import com.controleco.app.data.PeriodMode
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算对比清单的行数据单测：清单 = **设了预算的支出分类** ∪ **本期有账单记录的支出分类**。
 *
 * 关键回归（用户反馈）：只列设过预算的分类时，"有账单但没设预算"的分类整行消失；
 * 现在它们必须出现，预算记 0、占比 0%。
 */
class BudgetRatioRowsTest {

    private fun category(id: Long, name: String, type: TxType = TxType.EXPENSE) =
        CategoryEntity(id = id, name = name, type = type)

    private fun sum(id: Long, name: String, cents: Long) =
        CategorySumRow(categoryId = id, categoryName = name, totalCents = cents)

    private val categories = listOf(
        category(1, "餐饮"),
        category(2, "交通"),
        category(3, "购物"),
        category(4, "工资", TxType.INCOME),
    )

    @Test
    fun `没设预算但有账单的分类也要出现_预算记0`() {
        val rows = buildRatioRows(
            categoryBudgets = mapOf(1L to 80_000L),
            categories = categories,
            byCategory = listOf(sum(1, "餐饮", 50_000), sum(2, "交通", 12_000)),
            mode = PeriodMode.MONTH,
        )

        assertEquals(listOf("餐饮", "交通"), rows.map { it.name })
        assertEquals(50_000L, rows[0].actualCents)
        assertEquals(80_000L, rows[0].budgetCents)
        assertEquals(12_000L, rows[1].actualCents) // 交通：有账单、没预算
        assertEquals(0L, rows[1].budgetCents)
    }

    @Test
    fun `完全没有预算时清单不再是空的`() {
        val rows = buildRatioRows(
            categoryBudgets = emptyMap(),
            categories = categories,
            byCategory = listOf(sum(2, "交通", 12_000), sum(3, "购物", 30_000)),
            mode = PeriodMode.MONTH,
        )

        assertEquals(listOf("购物", "交通"), rows.map { it.name })
        assertTrue(rows.all { it.budgetCents == 0L })
    }

    @Test
    fun `只设预算没花的分保留并排在有支出的分类之后`() {
        val rows = buildRatioRows(
            categoryBudgets = mapOf(1L to 80_000L, 3L to 30_000L),
            categories = categories,
            byCategory = listOf(sum(1, "餐饮", 50_000)),
            mode = PeriodMode.MONTH,
        )

        assertEquals(listOf("餐饮", "购物"), rows.map { it.name })
        assertEquals(0L, rows[1].actualCents)      // 购物只设了预算、本期没花
        assertEquals(30_000L, rows[1].budgetCents)
    }

    @Test
    fun `年视角预算乘12_没预算的行不受影响`() {
        val rows = buildRatioRows(
            categoryBudgets = mapOf(1L to 80_000L),
            categories = categories,
            byCategory = listOf(sum(1, "餐饮", 50_000), sum(2, "交通", 12_000)),
            mode = PeriodMode.YEAR,
        )

        assertEquals(960_000L, rows[0].budgetCents)
        assertEquals(0L, rows[1].budgetCents)
        assertEquals(12_000L, rows[1].actualCents)
    }

    @Test
    fun `收入分类设了预算也不进清单_零额分类同样不进`() {
        val rows = buildRatioRows(
            categoryBudgets = mapOf(4L to 50_000L),   // 工资是收入分类
            categories = categories,
            byCategory = listOf(sum(2, "交通", 0L)),  // 本期合计为 0
            mode = PeriodMode.MONTH,
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `没有任何预算与支出时清单为空`() {
        val rows = buildRatioRows(
            categoryBudgets = emptyMap(),
            categories = categories,
            byCategory = emptyList(),
            mode = PeriodMode.MONTH,
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `分类净额为负也要列出_本期退款大于支出`() {
        val rows = buildRatioRows(
            categoryBudgets = emptyMap(),
            categories = categories,
            byCategory = listOf(sum(1, "餐饮", 10_000), sum(2, "交通", -3_000)),
            mode = PeriodMode.MONTH,
        )

        assertEquals(listOf("餐饮", "交通"), rows.map { it.name })
        assertEquals(-3_000L, rows[1].actualCents)
        assertEquals(0L, rows[1].budgetCents)
    }

    @Test
    fun `设了预算的分类净额为负时也保留预算`() {
        val rows = buildRatioRows(
            categoryBudgets = mapOf(2L to 30_000L),
            categories = categories,
            byCategory = listOf(sum(2, "交通", -3_000)),
            mode = PeriodMode.MONTH,
        )

        assertEquals(1, rows.size)
        assertEquals(-3_000L, rows.single().actualCents)
        assertEquals(30_000L, rows.single().budgetCents)
    }

    @Test
    fun `同额时按预算降序再按名字升序_顺序稳定`() {
        val rows = buildRatioRows(
            categoryBudgets = mapOf(3L to 30_000L),
            categories = categories,
            byCategory = listOf(sum(1, "餐饮", 10_000), sum(2, "交通", 10_000), sum(3, "购物", 10_000)),
            mode = PeriodMode.MONTH,
        )

        // 实际同为 100.00 → 购物（有预算）在前，其余预算都是 0 → 按分类名升序（UTF-16：交 < 餐）
        assertEquals(listOf("购物", "交通", "餐饮"), rows.map { it.name })
    }
}
