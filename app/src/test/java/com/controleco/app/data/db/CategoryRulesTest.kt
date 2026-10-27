package com.controleco.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类保留名与查重规则（[CategoryRules]）：
 * - 「其他」兜底、收入「退款」被锁（不能改名/改类型/删除）；
 * - 同类型不允许同名，支出与收入可各有一个同名；
 * - 名称按 trim 后比较。
 */
class CategoryRulesTest {

    private fun category(
        id: Long,
        name: String,
        type: TxType = TxType.EXPENSE,
    ) = CategoryEntity(id = id, name = name, type = type)

    private val all = listOf(
        category(1, "餐饮", TxType.EXPENSE),
        category(2, "交通", TxType.EXPENSE),
        category(3, "其他", TxType.EXPENSE),
        category(4, "餐饮", TxType.INCOME),
        category(5, "其他", TxType.INCOME),
        category(6, "退款", TxType.INCOME),
    )

    // ---------- 锁定 ----------

    @Test
    fun `兜底其他与收入退款被锁`() {
        assertTrue(CategoryRules.isLocked(category(3, "其他", TxType.EXPENSE)))
        assertTrue(CategoryRules.isLocked(category(5, "其他", TxType.INCOME)))
        assertTrue(CategoryRules.isLocked(category(6, "退款", TxType.INCOME)))
        assertFalse(CategoryRules.isLocked(category(1, "餐饮", TxType.EXPENSE)))
    }

    @Test
    fun `支出分类叫退款不算收入退款`() {
        assertFalse(CategoryRules.isRefundIncome(TxType.EXPENSE, "退款"))
        assertFalse(CategoryRules.isLocked(category(9, "退款", TxType.EXPENSE)))
    }

    // ---------- 改名 / 改类型 ----------

    @Test
    fun `其他不能改名或改类型`() {
        val other = category(3, "其他", TxType.EXPENSE)
        assertEquals("「其他」不能改名或修改类型", CategoryRules.editError(other, "杂项", TxType.EXPENSE))
        assertEquals("「其他」不能改名或修改类型", CategoryRules.editError(other, "其他", TxType.INCOME))
        // 只换图标/预算、名称类型不变 → 允许
        assertNull(CategoryRules.editError(other, "其他", TxType.EXPENSE))
    }

    @Test
    fun `收入退款不能改名或改类型`() {
        val refund = category(6, "退款", TxType.INCOME)
        assertEquals("「退款」不能改名或修改类型", CategoryRules.editError(refund, "退款收入", TxType.INCOME))
        assertEquals("「退款」不能改名或修改类型", CategoryRules.editError(refund, "退款", TxType.EXPENSE))
        assertNull(CategoryRules.editError(refund, "退款 ", TxType.INCOME))
    }

    @Test
    fun `普通分类改名不受限_新增永远允许`() {
        assertNull(CategoryRules.editError(category(1, "餐饮"), "吃喝", TxType.EXPENSE))
        assertNull(CategoryRules.editError(null, "任何", TxType.EXPENSE))
    }

    // ---------- 删除 ----------

    @Test
    fun `其他与收入退款不可删除`() {
        assertEquals("「其他」不能删除", CategoryRules.deleteError(category(3, "其他", TxType.EXPENSE)))
        assertEquals("「退款」不能删除", CategoryRules.deleteError(category(6, "退款", TxType.INCOME)))
        assertNull(CategoryRules.deleteError(category(1, "餐饮", TxType.EXPENSE)))
    }

    // ---------- 查重 ----------

    @Test
    fun `同类型同名被拒_支出与收入可同名`() {
        // 新增支出「交通」→ 撞车
        assertEquals("已存在同名分类", CategoryRules.duplicateNameError(all, 0, "交通", TxType.EXPENSE))
        // 新增收入「交通」→ 允许（不同分组）
        assertNull(CategoryRules.duplicateNameError(all, 0, "交通", TxType.INCOME))
    }

    @Test
    fun `编辑自己不算重名_名称按 trim 比较`() {
        // 编辑 id=1（餐饮·支出）保持原名 → 允许
        assertNull(CategoryRules.duplicateNameError(all, 1, "餐饮", TxType.EXPENSE))
        // 带空格也视为同名
        assertEquals("已存在同名分类", CategoryRules.duplicateNameError(all, 0, "  交通 ", TxType.EXPENSE))
    }

    @Test
    fun `改成另一类型时按新类型查重`() {
        // id=4 是收入「餐饮」，改成支出「餐饮」→ 与 id=1 撞车
        assertEquals("已存在同名分类", CategoryRules.duplicateNameError(all, 4, "餐饮", TxType.EXPENSE))
    }
}
