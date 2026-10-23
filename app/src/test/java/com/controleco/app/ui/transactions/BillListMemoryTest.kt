package com.controleco.app.ui.transactions

import com.controleco.app.data.PeriodMode
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账单列表「什么时候才回顶」：LaunchedEffect 在每次重新进入组合时都会重跑
 * （从编辑页返回、切 tab 回来都算），所以只有周期/筛选的键真的变了才允许回顶，
 * 否则 rememberLazyListState 刚恢复好的滚动位置会被立刻抹成 0。
 */
class BillListMemoryTest {

    @Test
    fun `首次进入组合不回顶`() {
        val key = billPeriodKey(YearMonth.of(2026, 10), PeriodMode.MONTH)
        assertFalse(shouldResetBillList(previousKey = null, currentKey = key))
    }

    @Test
    fun `点进账单返回_键没变不回顶`() {
        val key = billPeriodKey(YearMonth.of(2026, 10), PeriodMode.MONTH)
        // 返回时 rememberSaveable 把上一次的键带回来，本次算出的键与之一致
        assertFalse(shouldResetBillList(previousKey = key, currentKey = key))
    }

    @Test
    fun `上一月和下一月都要回顶`() {
        val current = YearMonth.of(2026, 10)
        val prev = YearMonth.of(2026, 9)
        val next = YearMonth.of(2026, 11)
        assertTrue(
            shouldResetBillList(
                previousKey = billPeriodKey(current, PeriodMode.MONTH),
                currentKey = billPeriodKey(prev, PeriodMode.MONTH),
            ),
        )
        assertTrue(
            shouldResetBillList(
                previousKey = billPeriodKey(current, PeriodMode.MONTH),
                currentKey = billPeriodKey(next, PeriodMode.MONTH),
            ),
        )
    }

    @Test
    fun `月年切换即使年月相同也要回顶`() {
        val month = YearMonth.of(2026, 10)
        assertTrue(
            shouldResetBillList(
                previousKey = billPeriodKey(month, PeriodMode.MONTH),
                currentKey = billPeriodKey(month, PeriodMode.YEAR),
            ),
        )
    }

    @Test
    fun `跨年键不同`() {
        assertFalse(
            billPeriodKey(YearMonth.of(2026, 12), PeriodMode.MONTH) ==
                billPeriodKey(YearMonth.of(2027, 1), PeriodMode.MONTH),
        )
    }

    @Test
    fun `筛选用名字当键_同值不回顶换值回顶`() {
        assertFalse(shouldResetBillList(previousKey = "ALL", currentKey = "ALL"))
        assertTrue(shouldResetBillList(previousKey = "ALL", currentKey = "EXPENSE"))
        assertTrue(shouldResetBillList(previousKey = "EXPENSE", currentKey = "INCOME"))
    }

    @Test
    fun `键格式稳定_同一年月与模式必然同键`() {
        val a = billPeriodKey(YearMonth.of(2026, 3), PeriodMode.YEAR)
        val b = billPeriodKey(YearMonth.of(2026, 3), PeriodMode.YEAR)
        assertEquals(a, b)
    }
}
