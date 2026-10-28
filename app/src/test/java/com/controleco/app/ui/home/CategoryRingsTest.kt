package com.controleco.app.ui.home

import com.controleco.app.data.budget.Budget
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryRingsTest {

    private fun category(id: Long, name: String, type: TxType = TxType.EXPENSE) =
        CategoryEntity(id = id, name = name, type = type)

    private fun sum(id: Long, name: String, cents: Long) =
        CategorySumRow(categoryId = id, categoryName = name, totalCents = cents)

    @Test
    fun `无分类预算时仅其他环_覆盖全部预算与支出`() {
        val rings = buildCategoryRings(
            budgets = emptyMap(),
            sums = listOf(sum(1, "餐饮", 400_00)),
            categories = listOf(category(1, "餐饮")),
            monthlyBudgetCents = 5_000_00,
            totalSpentCents = 400_00,
        )
        assertEquals(1, rings.size)
        assertTrue(rings[0].isOther)
        assertEquals(NO_BUDGET_BUCKET_NAME, rings[0].name)
        assertEquals(4_600_00, rings[0].remainingCents)
    }

    @Test
    fun `剩余与档位按分类预算计算`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 1_000_00),
            sums = listOf(sum(1, "餐饮", 400_00)),
            categories = listOf(category(1, "餐饮")),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 400_00,
        )
        // 分类预算 = 总预算 → 无"其他"
        assertEquals(listOf("餐饮"), rings.map { it.name })
        assertEquals(600_00, rings[0].remainingCents)
        assertEquals(0.6f, rings[0].ratio, 0.0001f)
        assertEquals(Budget.Tone.OK, rings[0].tone)
    }

    @Test
    fun `其他环预算与支出为总量减去已分配分类`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 1_000_00, 2L to 500_00),
            sums = listOf(sum(1, "餐饮", 400_00), sum(2, "交通", 200_00)),
            categories = listOf(category(1, "餐饮"), category(2, "交通")),
            monthlyBudgetCents = 3_000_00,
            totalSpentCents = 900_00, // 餐饮 400 + 交通 200 + 其他 300
        )
        val other = rings.first { it.isOther }
        assertEquals(NO_BUDGET_BUCKET_NAME, other.name)
        // 其他预算 = 3000 − 1000 − 500 = 1500；其他支出 = 900 − 400 − 200 = 300
        assertEquals(1_200_00, other.remainingCents)
        // 分类剩余 + 其他剩余 = 月总剩余（模式①口径）
        assertEquals(3_000_00 - 900_00, rings.sumOf { it.remainingCents })
    }

    @Test
    fun `未花过的分类视为全额剩余`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 500_00),
            sums = emptyList(),
            categories = listOf(category(1, "交通")),
            monthlyBudgetCents = 500_00,
            totalSpentCents = 0L,
        )
        assertEquals(500_00, rings[0].remainingCents)
        assertEquals(1f, rings[0].ratio, 0.0001f)
    }

    @Test
    fun `超支分类剩余为负且档位为紧张`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 300_00),
            sums = listOf(sum(1, "购物", 450_00)),
            categories = listOf(category(1, "购物")),
            monthlyBudgetCents = 300_00,
            totalSpentCents = 450_00,
        )
        assertEquals(-150_00, rings[0].remainingCents)
        assertEquals(Budget.Tone.LOW, rings[0].tone)
    }

    @Test
    fun `按剩余比例升序_最紧张的在前`() {
        val budgets: Map<Long, Long> =
            mapOf(1L to 100_00, 2L to 100_00, 3L to 100_00, 4L to 100_00, 5L to 100_00)
        val sums = listOf(
            sum(1, "甲", 10_00),  // 剩 90%
            sum(2, "乙", 90_00),  // 剩 10%
            sum(3, "丙", 50_00),  // 剩 50%
            sum(4, "丁", 70_00),  // 剩 30%
            sum(5, "戊", 95_00),  // 剩 5%
        )
        val categories = listOf(
            category(1, "甲"), category(2, "乙"), category(3, "丙"), category(4, "丁"), category(5, "戊"),
        )
        val rings = buildCategoryRings(budgets, sums, categories, 500_00, 315_00)
        assertEquals(listOf("戊", "乙", "丁", "丙", "甲"), rings.map { it.name })
    }

    @Test
    fun `不截断_超过四个环全部保留并弹性排布`() {
        val budgets: Map<Long, Long> =
            (1L..7L).associateWith { 100_00L }
        val sums = (1L..7L).map { id -> sum(id, "分类$id", id * 10_00) }
        val categories = (1L..7L).map { id -> category(id, "分类$id") }
        val rings = buildCategoryRings(
            budgets, sums, categories,
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 280_00,
        )
        // 7 个分类环 + 1 个"其他"环（预算 1000 − 700 = 300）
        assertEquals(8, rings.size)
        assertEquals(1, rings.count { it.isOther })
    }

    @Test
    fun `收入分类与已删除分类不参与_其预算归入其他`() {
        val rings = buildCategoryRings(
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
        assertEquals(listOf("餐饮", NO_BUDGET_BUCKET_NAME), rings.sortedBy { it.isOther }.map { it.name })
        val other = rings.first { it.isOther }
        // 其他预算 = 1000 − 200（收入/已删分类的预算不计入已分配）
        assertEquals(800_00 - 100_00, other.remainingCents)
    }

    @Test
    fun `分类预算超过总预算且无支出时无合成环`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 1_500_00),
            sums = emptyList(),
            categories = listOf(category(1, "餐饮")),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 0L,
        )
        assertEquals(listOf("餐饮"), rings.map { it.name })
        assertTrue(rings.none { it.isOther })
    }

    @Test
    fun `分类预算超过总预算但有未设预算支出时_合成环剩余为负`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 1_200_00),
            sums = listOf(sum(1, "餐饮", 500_00), sum(2, "医疗", 700_00)),
            categories = listOf(category(1, "餐饮"), category(2, "医疗")),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 1_200_00,
        )
        assertTrue(rings.none { it.name == "医疗" })
        val bucket = rings.first { it.isOther }
        assertEquals(NO_BUDGET_BUCKET_NAME, bucket.name)
        assertEquals(-700_00, bucket.remainingCents)
        assertEquals(Budget.Tone.LOW, bucket.tone)
    }

    // ---------- 超支警示强度（心跳/摇晃幅度与红色饱和度） ----------

    @Test
    fun `警示强度随剩余减少而加深并封顶`() {
        assertEquals(0f, Budget.warningSeverity(0.2f, 0.2f), 0.0001f)   // 红线处：无警示
        assertEquals(0f, Budget.warningSeverity(0.8f, 0.2f), 0.0001f)
        assertEquals(0.4f, Budget.warningSeverity(0f, 0.2f), 0.0001f)   // 用完预算
        assertEquals(1f, Budget.warningSeverity(-0.3f, 0.2f), 0.0001f)  // 超支 30% 封顶
        assertEquals(1f, Budget.warningSeverity(-2f, 0.2f), 0.0001f)    // 更深超支不超上限
    }

    @Test
    fun `分类环携带同一套动态阈值下的警示强度`() {
        // 动态阈值（剩 21 天 / 30 天）：绿线 66.7%，红线 35%
        val thresholds = Budget.thresholds(daysLeft = 21, daysInMonth = 30)
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 1_000_00),
            sums = listOf(sum(1, "餐饮", 700_00)), // 剩 30% < 红线 → 红
            categories = listOf(category(1, "餐饮")),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 700_00,
            thresholds = thresholds,
        )
        assertEquals(Budget.Tone.LOW, rings[0].tone)
        assertEquals(Budget.warningSeverity(0.3f, thresholds.redBelow), rings[0].severity, 0.0001f)
        // 剩 70% > 绿线 → 绿
        val okRings = buildCategoryRings(
            budgets = mapOf(1L to 1_000_00),
            sums = listOf(sum(1, "餐饮", 300_00)),
            categories = listOf(category(1, "餐饮")),
            monthlyBudgetCents = 1_000_00,
            totalSpentCents = 300_00,
            thresholds = thresholds,
        )
        assertEquals(Budget.Tone.OK, okRings[0].tone)
    }
}
