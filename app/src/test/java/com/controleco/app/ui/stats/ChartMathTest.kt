package com.controleco.app.ui.stats

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计图表纯计算单测：柱子聚合（月/年）、坐标轴取整与数字标记、分类切片合并。
 */
class ChartMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun row(
        type: TxType,
        cents: Long,
        date: LocalDateTime,
        id: Long = 0L,
    ) = TransactionRow(
        id = id,
        type = type,
        amountCents = cents,
        timestamp = date.atZone(zone).toInstant().toEpochMilli(),
        note = "",
        counterparty = "",
        source = TxSource.MANUAL,
        categoryId = 1L,
        categoryName = "餐饮",
        accountId = 1L,
        accountName = "微信零钱",
        refundMark = RefundMark.NONE,
    )

    // ---------- 月视角：每日一根柱 ----------

    @Test
    fun `月柱子按天聚合且忽略收入与其他月份的记录`() {
        val month = YearMonth.of(2026, 10)
        val rows = listOf(
            row(TxType.EXPENSE, 1_250, LocalDateTime.of(2026, 10, 3, 9, 0)),
            row(TxType.EXPENSE, 750, LocalDateTime.of(2026, 10, 3, 20, 0)),
            row(TxType.EXPENSE, 9_900, LocalDateTime.of(2026, 10, 31, 23, 59)),
            row(TxType.INCOME, 50_000, LocalDateTime.of(2026, 10, 5, 8, 0)),
            row(TxType.EXPENSE, 30_000, LocalDateTime.of(2026, 9, 30, 23, 0)),
        )
        val bars = ChartMath.monthBars(rows, month, startDay = 1, zone = zone)

        assertEquals(31, bars.size)
        assertEquals(2_000L, bars[2].cents)      // 10-03 两笔合计
        assertEquals(2, bars[2].count)
        assertEquals(9_900L, bars[30].cents)     // 10-31
        assertEquals(0L, bars[4].cents)          // 10-05 只有收入 → 支出为 0
        assertEquals(0L, bars[0].cents)
        assertTrue(bars[2].tip.contains("10月3日"))
        assertTrue(bars[2].tip.contains("2 笔"))
        assertTrue(bars[4].tip.endsWith("无支出"))
    }

    @Test
    fun `月柱子横轴固定标 1日_9日_16日_24日与月末`() {
        // 原型统计稿：横轴固定 01 / 09 / 16 / 24 / 月末（补零），不再每五天均分
        val feb = ChartMath.monthBars(emptyList(), YearMonth.of(2026, 2), startDay = 1, zone = zone)
        assertEquals(
            listOf(1, 9, 16, 24, 28),
            feb.mapIndexedNotNull { i, b -> b.axisLabel?.let { i + 1 } },
        )
        assertEquals("01", feb[0].axisLabel)
        assertEquals("28", feb[27].axisLabel)

        val jan = ChartMath.monthBars(emptyList(), YearMonth.of(2026, 1), startDay = 1, zone = zone)
        assertEquals(
            listOf(1, 9, 16, 24, 31),
            jan.mapIndexedNotNull { i, b -> b.axisLabel?.let { i + 1 } },
        )
        assertEquals("31", jan[30].axisLabel)
    }

    // ---------- 年视角：每月一根柱 ----------

    @Test
    fun `年柱子按月聚合且跨月不串行`() {
        val month = YearMonth.of(2026, 5)
        val rows = listOf(
            row(TxType.EXPENSE, 1_000, LocalDateTime.of(2026, 1, 15, 12, 0)),
            row(TxType.EXPENSE, 2_000, LocalDateTime.of(2026, 1, 20, 12, 0)),
            row(TxType.EXPENSE, 5_000, LocalDateTime.of(2026, 12, 31, 23, 0)),
            row(TxType.EXPENSE, 7_000, LocalDateTime.of(2025, 12, 31, 23, 0)),
        )
        val bars = ChartMath.yearBars(rows, month, zone)

        assertEquals(12, bars.size)
        assertEquals(3_000L, bars[0].cents)
        assertEquals(2, bars[0].count)
        assertEquals(5_000L, bars[11].cents)
        assertEquals(0L, bars[5].cents)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"), bars.map { it.axisLabel })
        assertTrue(bars[11].tip.startsWith("12月"))
    }

    // ---------- 坐标轴 ----------

    @Test
    fun `纵轴上限取整数刻度`() {
        assertEquals(1f, ChartMath.niceCeil(0f))
        assertEquals(1f, ChartMath.niceCeil(0.8f))
        assertEquals(2f, ChartMath.niceCeil(1.2f))
        assertEquals(2.5f, ChartMath.niceCeil(2.4f))
        assertEquals(5f, ChartMath.niceCeil(4.2f))
        assertEquals(10f, ChartMath.niceCeil(6.7f))
        assertEquals(200f, ChartMath.niceCeil(123f))
        assertEquals(2_000f, ChartMath.niceCeil(1_234f))
        assertEquals(5_000_000f, ChartMath.niceCeil(3_400_000f))
    }

    @Test
    fun `纵轴数字用简单数_千分位与K与M`() {
        assertEquals("0", ChartMath.axisAmount(0f))
        assertEquals("25", ChartMath.axisAmount(25f))
        assertEquals("1,250", ChartMath.axisAmount(1_250f))
        assertEquals("12.5K", ChartMath.axisAmount(12_500f))
        assertEquals("20K", ChartMath.axisAmount(20_000f))
        assertEquals("1.5M", ChartMath.axisAmount(1_500_000f))
    }

    // ---------- 分类切片 ----------

    @Test
    fun `切片合并实际与预算_并集去重且按实际支出降序`() {
        val slices = ChartMath.mergeSlices(
            actual = listOf("餐饮" to 5_000L, "交通" to 1_000L),
            budgets = listOf("餐饮" to 8_000L, "购物" to 3_000L, "空预算" to 0L),
        )

        assertEquals(listOf("餐饮", "交通", "购物"), slices.map { it.name })
        assertEquals(8_000L, slices[0].budgetCents)  // 实际与预算合并到同一行
        assertEquals(0L, slices[1].budgetCents)      // 没设预算的分类
        assertEquals(0L, slices[2].actualCents)      // 只有预算、还没花钱
        assertEquals(3_000L, slices[2].budgetCents)
    }

    @Test
    fun `切片占比计算与零分母保护`() {
        assertEquals(50f, ChartMath.sharePercent(50L, 100L), 0.001f)
        assertEquals(0f, ChartMath.sharePercent(10L, 0L), 0.001f)
        assertEquals(0f, ChartMath.sharePercent(0L, 0L), 0.001f)
        assertEquals(100f, ChartMath.sharePercent(7L, 7L), 0.001f)
    }

    @Test
    fun `空数据不产生柱子`() {
        val bars = ChartMath.monthBars(emptyList(), YearMonth.of(2026, 1), startDay = 1, zone = zone)
        assertEquals(31, bars.size)
        assertTrue(bars.all { it.cents == 0L && it.count == 0 })
        assertTrue(ChartMath.mergeSlices(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `跨时区边界按给定时区落格`() {
        // UTC 时间 2026-10-03T20:00Z，在东八区属于 10-04
        val row = row(TxType.EXPENSE, 100, LocalDateTime.of(2026, 10, 4, 4, 0)).copy(
            timestamp = java.time.Instant.parse("2026-10-03T20:00:00Z").toEpochMilli(),
        )
        val bars = ChartMath.monthBars(listOf(row), YearMonth.of(2026, 10), startDay = 1, zone = zone)
        assertEquals(0L, bars[2].cents)   // 10-03 本地日：None
        assertEquals(100L, bars[3].cents) // 10-04
    }

    @Test
    fun `本地日期格式化不依赖系统默认时区`() {
        val bars = ChartMath.monthBars(
            listOf(row(TxType.EXPENSE, 500, LocalDateTime.of(2026, 3, 9, 12, 0))),
            YearMonth.of(2026, 3),
            startDay = 1,
            zone = zone,
        )
        assertEquals(LocalDate.of(2026, 3, 9).dayOfMonth, bars.indexOfFirst { it.cents > 0 } + 1)
    }
}
