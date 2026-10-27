package com.controleco.app.ui.stats

import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxType
import com.controleco.app.util.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 柱状图一根柱子的数据（纯数据，Canvas 绘制与单测共用） */
data class BarPoint(
    /** 金额（分），零支出柱为 0 */
    val cents: Long,
    /** 该柱内的支出笔数 */
    val count: Int,
    /** x 轴刻度数字（null = 这一根不标数字） */
    val axisLabel: String?,
    /** 点按 / 滑动时的读数：日期　金额　笔数（用空白间隔，不用「·」） */
    val tip: String,
)

/** 分类切片：实际支出 + 该分类的预算（年视角时预算按 12 个月合计） */
data class CategorySlice(
    val name: String,
    val actualCents: Long,
    val budgetCents: Long,
)

/**
 * 统计图表的纯计算：柱子聚合、坐标轴取整与刻度文字、分类切片合并。
 * 不依赖 Compose，全部可单测（`ChartMathTest`）。
 */
object ChartMath {

    /**
     * 月视角：每日一根柱（含零支出日，保持横轴时间连续）。
     * [startDay] = 会计月首日（1 = 自然月；首日 ≠ 1 时区间与刻度都按会计月走）。
     */
    fun monthBars(
        rows: List<TransactionRow>,
        month: YearMonth,
        startDay: Int = 1,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<BarPoint> {
        val period = MonthPeriod.of(month, startDay)
        val days = period.days
        val cents = LongArray(days)
        val counts = IntArray(days)
        rows.filter { it.type == TxType.EXPENSE }.forEach { r ->
            val d = Instant.ofEpochMilli(r.timestamp).atZone(zone).toLocalDate()
            if (period.contains(d)) {
                val i = period.dayIndexOf(d) - 1
                cents[i] += r.amountCents
                counts[i]++
            }
        }
        return (1..days).map { pos ->
            // 刻度文字用**真实日期**（首日 = 1 时即 1..N 号，与旧行为一致）
            val date = period.start.plusDays((pos - 1).toLong())
            val c = cents[pos - 1]
            val n = counts[pos - 1]
            BarPoint(
                cents = c,
                count = n,
                // 原型横轴固定 5 个刻度：01 / 09 / 16 / 24 / 月末（两位补零）
                axisLabel = if (pos == 1 || pos == 9 || pos == 16 || pos == 24 || pos == days) {
                    "%02d".format(date.dayOfMonth)
                } else {
                    null
                },
                tip = if (c > 0) {
                    "${date.monthValue}月${date.dayOfMonth}日  ${Money.formatYuanGrouped(c)}  $n 笔"
                } else {
                    "${date.monthValue}月${date.dayOfMonth}日  无支出"
                },
            )
        }
    }

    /**
     * 年视角：每**会计月**一根柱，横轴标 1–12 月。
     *
     * 会计年区间是 `MonthPeriod.year(year, startDay)`（y 年 1 月首日 → 次年 1 月首日）；
     * 首日 ≠ 1 时区间尾部会带上次年 1 月 1 日到首日前的账单，它们属于**最后一个会计月**（12 月桶）。
     * 所以按 `MonthPeriod.of(date, startDay).start` 的自然月归属分桶，而不是按 `date.monthValue`，
     * 否则这些账单会被静默丢掉、柱子合计小于封面/实际支出。`startDay = 1` 时与自然年完全一致。
     */
    fun yearBars(
        rows: List<TransactionRow>,
        month: YearMonth,
        startDay: Int = 1,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<BarPoint> {
        val period = MonthPeriod.year(month.year, startDay)
        val baseMonth = YearMonth.of(month.year, 1)
        val cents = LongArray(12)
        val counts = IntArray(12)
        rows.filter { it.type == TxType.EXPENSE }.forEach { r ->
            val d = Instant.ofEpochMilli(r.timestamp).atZone(zone).toLocalDate()
            if (!period.contains(d)) return@forEach
            val accountingMonth = YearMonth.from(MonthPeriod.of(d, startDay).start)
            val i = ChronoUnit.MONTHS.between(baseMonth, accountingMonth).toInt()
            if (i in 0..11) {
                cents[i] += r.amountCents
                counts[i]++
            }
        }
        return (1..12).map { m ->
            val c = cents[m - 1]
            val n = counts[m - 1]
            BarPoint(
                cents = c,
                count = n,
                axisLabel = "$m",
                tip = if (c > 0) "${m}月  ${Money.formatYuanGrouped(c)}  $n 笔" else "${m}月  无支出",
            )
        }
    }

    /**
     * 纵轴上限取整到 1 / 2 / 2.5 / 5 × 10^n（输入 ≤ 0 时回落到 1，保证仍有刻度）。
     */
    fun niceCeil(value: Float): Float {
        if (value <= 0f || !value.isFinite()) return 1f
        val exp = floor(log10(value.toDouble())).toInt()
        val base = 10.0.pow(exp).toFloat()
        val m = value / base
        val mult = when {
            m <= 1f -> 1f
            m <= 2f -> 2f
            m <= 2.5f -> 2.5f
            m <= 5f -> 5f
            else -> 10f
        }
        return mult * base
    }

    /** 纵轴刻度文字：简单数字，无货币符号（0 / 25 / 1,250 / 12.5K / 1.5M） */
    fun axisAmount(yuan: Float): String = when {
        yuan <= 0f -> "0"
        yuan >= 1_000_000f -> trim1(yuan / 1_000_000f) + "M"
        yuan >= 10_000f -> trim1(yuan / 1_000f) + "K"
        else -> java.text.DecimalFormat("#,##0.#").format(yuan.toDouble())
    }

    private fun trim1(v: Float): String = BigDecimal.valueOf(v.toDouble())
        .setScale(1, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

    /**
     * 合并「实际支出」与「分类预算」为图例切片：并集去重、按实际支出降序，
     * 没有实际支出但设了预算的分类排在后面（点名"预算没用上"）。
     */
    fun mergeSlices(
        actual: List<Pair<String, Long>>,
        budgets: List<Pair<String, Long>>,
    ): List<CategorySlice> {
        val byName = LinkedHashMap<String, CategorySlice>()
        actual.forEach { (name, cents) ->
            if (name.isNotBlank()) {
                byName[name] = CategorySlice(name, cents, budgets.firstOrNull { it.first == name }?.second ?: 0L)
            }
        }
        budgets.forEach { (name, cents) ->
            if (name.isNotBlank() && cents > 0 && byName[name] == null) {
                byName[name] = CategorySlice(name, 0L, cents)
            }
        }
        return byName.values
            .filter { it.actualCents > 0 || it.budgetCents > 0 }
            .sortedWith(compareByDescending<CategorySlice> { it.actualCents }.thenByDescending { it.budgetCents })
    }

    /** 占比（0..100）；分母 ≤ 0 时返回 0 */
    fun sharePercent(part: Long, total: Long): Float =
        if (total <= 0L) 0f else part * 100f / total
}
