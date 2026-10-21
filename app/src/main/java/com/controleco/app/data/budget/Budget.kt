package com.controleco.app.data.budget

import java.time.LocalDate

/**
 * 预算周期状态计算——纯函数，可单测。
 *
 * 首页三层信息：
 * 1. 剩余可用 = 预算 − 本期已花（第一视觉层级）
 * 2. 本期已花 / 预算消耗进度
 * 3. 今天建议最多花 = 剩余 ÷ 本期剩余天数（含今天）；日预算模式下直接取日预算
 *
 * "月" = 会计月（首日可配置，区间由 [MonthPeriod] 计算）。
 *
 * 用色三档（绿/黄/红）按**剩余天数占本期比例**动态计算（自测新口径，见开发日志）：
 * 绿线 =（剩余天数 − 1）÷ 本期天数，红线 =（剩余天数 ÷ 2）÷ 本期天数。
 * 语义 = 花销速度与时间进度的对照：剩余预算够按天平均花到期末（绿）、
 * 不够覆盖"剩余时间的一半"（红）。月初就能对超前消费报警，月末不冤枉正常花销。
 * 消耗比例 State（75% 警示）与用色 Tone 相互独立。
 */
object Budget {

    enum class State { NORMAL, WARNING, CRITICAL }

    /** 用色档位：充足绿 / 中等黄 / 紧张红 */
    enum class Tone { OK, MID, LOW }

    /** Warning 阈值：已消耗预算比例达到 75% 时进入警示 */
    const val WARNING_RATIO = 0.75

    /**
     * 用色三档（绿-黄-红）的动态阈值，量纲 = 剩余比例。
     * 与 State（按固定消耗比例判定）不同，Tone 直接按剩余比例与时间进度的对照分档。
     */
    data class Thresholds(val greenAbove: Float, val redBelow: Float)

    /**
     * 动态阈值：绿线 =（剩余天数 − 1）÷ 本期天数；红线 =（剩余天数 ÷ 2）÷ 本期天数。
     * 例（30 天月、剩 21 天）：绿线 = 20/30 = 66.7%，红线 = 10.5/30 = 35%。
     * 剩余 ≥ 绿线 = 充裕；红线与绿线之间 = 留意；< 红线 = 偏紧。
     */
    fun thresholds(daysLeft: Int, daysInMonth: Int): Thresholds {
        val d = daysInMonth.coerceAtLeast(1).toFloat()
        return Thresholds(
            greenAbove = ((daysLeft - 1).coerceAtLeast(0) / d),
            redBelow = ((daysLeft / 2f) / d),
        )
    }

    /** 剩余比例 → 档位（绿 OK / 黄 MID / 红 LOW）："超过绿线为绿、低于红线为红"（严格大于/小于，与口径文字一致） */
    fun tone(remainingRatio: Float, thresholds: Thresholds): Tone = when {
        remainingRatio > thresholds.greenAbove -> Tone.OK
        remainingRatio >= thresholds.redBelow -> Tone.MID
        else -> Tone.LOW
    }

    /**
     * 剩余比例档位的中文短标签（个人页预算卡与图例用）。与 [tone] 同边界，
     * 保证"说法"与"颜色"永远一致；剩余为负时单独显示「已超支」（同属偏紧状态）。
     */
    fun toneLabel(remainingRatio: Float, thresholds: Thresholds): String = when {
        remainingRatio < 0f -> "已超支"
        remainingRatio > thresholds.greenAbove -> "充裕"
        remainingRatio >= thresholds.redBelow -> "留意"
        else -> "偏紧"
    }

    /**
     * 超支警示强度 0..1：红线处为 0，超支 30%（比例 -0.3）封顶 1。
     * 用于心跳/摇晃幅度与红色饱和度（上限即"防止过度动画"的封顶）。
     */
    fun warningSeverity(remainingRatio: Float, redBelow: Float): Float =
        ((redBelow - remainingRatio) / (redBelow + 0.3f)).coerceIn(0f, 1f)

    /**
     * 月预算折算成日均：月预算 ÷ 本期天数，四舍五入到分。
     *
     * 个人页预算卡永远显示日均——日预算模式下直接用日预算，月预算模式下用这个折算值，
     * 所以改完月预算卡片会立刻跟着变，不会停在旧日预算上。
     * 月预算 ≤ 0（未设置）或天数非法时返回 0，卡片显示「未设置」。
     */
    fun dailyEquivalent(monthlyCents: Long, days: Int): Long {
        if (monthlyCents <= 0L || days <= 0) return 0L
        return (monthlyCents + days / 2) / days
    }

    data class Snapshot(
        val budgetCents: Long,
        val spentCents: Long,
        /** 剩余可用（可为负 = 超支） */
        val remainingCents: Long,
        /** 剩余比例 = 剩余 / 预算（可为负），饼图与档位用色的数据源 */
        val remainingRatio: Float,
        /** 剩余额度档位（绿-黄-红，按剩余天数动态阈值） */
        val tone: Tone,
        /** 动态阈值（主页细环、个人页文案与警示强度共用同一套数字） */
        val thresholds: Thresholds,
        /** 本期剩余天数（含今天，最少 1） */
        val daysLeft: Int,
        /** 本期总天数（会计月） */
        val daysInMonth: Int,
        /** 预算消耗比例 0..1+ */
        val spentRatio: Float,
        /** 建议今日消费上限（日预算模式下 = 日预算；剩余 ≤ 0 时为 0） */
        val suggestedDailyCents: Long,
        /** 今日净支出 = 今日支出 − 今日退款到账（**不含工资等收入**：预算看的是花销），正数 = 今天净花掉的钱 */
        val todaySpentCents: Long,
        /**
         * **本日剩余** = 建议日额 − 今日净支出，可为负（今天已经花超了）。
         * 只按"剩余 ÷ 天数"算的话，今天花掉的钱只摊掉 1/N，读数几乎不动（收入更是完全不参与），
         * 所以把今天的净支出**整额**扣掉：花一笔就能立刻看到今天还剩多少可花。
         */
        val todayLeftCents: Long,
        val state: State,
    )

    /**
     * 预算未设置（≤ 0）返回 null，首页降级为"未设预算"引导。
     *
     * @param period 会计月区间（默认按自然月，便于既有用例）；跨月首日时天数与剩余都按区间算
     * @param dailyBudgetCents > 0 = 日预算模式：本日剩余按"日预算 − 今日净支出"算
     */
    fun compute(
        budgetCents: Long,
        spentCents: Long,
        today: LocalDate,
        todaySpentCents: Long = 0L,
        period: MonthPeriod = MonthPeriod.of(today, 1),
        dailyBudgetCents: Long = 0L,
    ): Snapshot? {
        if (budgetCents <= 0) return null
        val daysLeft = period.daysLeft(today)
        val remaining = budgetCents - spentCents
        val ratio = if (budgetCents > 0) spentCents.toDouble() / budgetCents else 0.0
        val remainingRatio = remaining.toDouble() / budgetCents
        val thresholds = thresholds(daysLeft, period.days)
        val state = when {
            remaining <= 0 -> State.CRITICAL
            ratio >= WARNING_RATIO -> State.WARNING
            else -> State.NORMAL
        }
        val suggested = when {
            dailyBudgetCents > 0 -> dailyBudgetCents
            remaining > 0 -> remaining / daysLeft
            else -> 0L
        }
        return Snapshot(
            budgetCents = budgetCents,
            spentCents = spentCents,
            remainingCents = remaining,
            remainingRatio = remainingRatio.toFloat(),
            tone = tone(remainingRatio.toFloat(), thresholds),
            thresholds = thresholds,
            daysLeft = daysLeft,
            daysInMonth = period.days,
            spentRatio = ratio.toFloat(),
            suggestedDailyCents = suggested,
            todaySpentCents = todaySpentCents,
            todayLeftCents = suggested - todaySpentCents,
            state = state,
        )
    }
}
