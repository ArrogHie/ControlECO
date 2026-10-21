package com.controleco.app.data.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetTest {

    private val day = LocalDate.of(2026, 10, 6)

    @Test
    fun `未设置预算返回 null`() {
        assertNull(Budget.compute(0, 1_000, day))
        assertNull(Budget.compute(-1, 1_000, day))
    }

    @Test
    fun `正常状态_剩余与建议日花`() {
        val s = Budget.compute(5_000_00, 2_116_00, day)!!
        assertEquals(2_884_00, s.remainingCents)
        assertEquals(31 - 6 + 1, s.daysLeft) // 10 月 31 天，含今天剩 26 天
        // 2884 元 / 26 天 = 110.92 元 → 11092 分（整数分向下取整）
        assertEquals(2_884_00 / 26, s.suggestedDailyCents)
        assertEquals(Budget.State.NORMAL, s.state)
    }

    @Test
    fun `接近上限进入警示`() {
        val s = Budget.compute(1_000_00, 750_00, day)!!
        assertEquals(Budget.State.WARNING, s.state)
        // 刚好 74.9% 仍是正常
        assertEquals(Budget.State.NORMAL, Budget.compute(1_000_00, 749_00, day)!!.state)
    }

    @Test
    fun `用完或超支进入危险_建议日花为 0`() {
        val used = Budget.compute(1_000_00, 1_000_00, day)!!
        assertEquals(Budget.State.CRITICAL, used.state)
        assertEquals(0L, used.suggestedDailyCents)
        assertEquals(0L, used.remainingCents)

        val over = Budget.compute(1_000_00, 1_200_00, day)!!
        assertEquals(Budget.State.CRITICAL, over.state)
        assertEquals(-200_00, over.remainingCents)
        assertEquals(0L, over.suggestedDailyCents)
    }

    @Test
    fun `月末最后一天建议日花等于剩余`() {
        val last = LocalDate.of(2026, 10, 31)
        val s = Budget.compute(1_000_00, 400_00, last)!!
        assertEquals(1, s.daysLeft)
        assertEquals(600_00, s.suggestedDailyCents)
    }

    @Test
    fun `消耗比例上限内`() {
        val s = Budget.compute(2_000_00, 1_000_00, day)!!
        assertEquals(0.5f, s.spentRatio, 0.0001f)
    }

    // ---------- 动态阈值（按剩余天数占本期比例，自测新口径） ----------

    @Test
    fun `阈值按剩余天数占本期比例计算`() {
        // 30 天月、第 10 天（含今天剩 21 天）：绿线 = 20/30，红线 = 10.5/30
        val t = Budget.thresholds(daysLeft = 21, daysInMonth = 30)
        assertEquals(20f / 30f, t.greenAbove, 0.0001f)
        assertEquals(10.5f / 30f, t.redBelow, 0.0001f)
        // 最后一天：绿线 0（只要没超支就是绿），红线 = 半天占本期比例
        val last = Budget.thresholds(daysLeft = 1, daysInMonth = 30)
        assertEquals(0f, last.greenAbove, 0.0001f)
        assertEquals(0.5f / 30f, last.redBelow, 0.0001f)
    }

    @Test
    fun `用色三档按动态阈值分档`() {
        val t = Budget.thresholds(daysLeft = 21, daysInMonth = 30) // 绿线 66.7%，红线 35%
        // 已花 30% → 剩 70% ≥ 绿线 → 绿
        assertEquals(Budget.Tone.OK, Budget.tone(0.70f, t))
        // 已花 50% → 剩 50% 在红线与绿线之间 → 黄
        assertEquals(Budget.Tone.MID, Budget.tone(0.50f, t))
        // 已花 70% → 剩 30% < 红线 → 红
        assertEquals(Budget.Tone.LOW, Budget.tone(0.30f, t))
        // 超支必红
        assertEquals(Budget.Tone.LOW, Budget.tone(-0.1f, t))
    }

    @Test
    fun `最后一天用完预算不是绿色`() {
        // 绿线 = 0："超过 0%" 不成立（严格大于），用完预算落黄档之下 → 红
        val last = Budget.thresholds(daysLeft = 1, daysInMonth = 30)
        assertEquals(Budget.Tone.LOW, Budget.tone(0f, last))
        // 还剩一点点：超过绿线 0 → 绿（最后一天剩得下就是好事）
        assertEquals(Budget.Tone.OK, Budget.tone(0.01f, last))
    }

    @Test
    fun `快照携带剩余比例与档位`() {
        // 10-06（31 天月，剩 26 天）：绿线 25/31 = 80.6%，红线 13/31 = 41.9%
        val ok = Budget.compute(1_000_00, 100_00, day)!!
        assertEquals(0.9f, ok.remainingRatio, 0.0001f)
        assertEquals(Budget.Tone.OK, ok.tone)

        val mid = Budget.compute(1_000_00, 400_00, day)!!
        assertEquals(0.6f, mid.remainingRatio, 0.0001f)
        assertEquals(Budget.Tone.MID, mid.tone)

        val low = Budget.compute(1_000_00, 900_00, day)!!
        assertEquals(0.1f, low.remainingRatio, 0.0001f)
        assertEquals(Budget.Tone.LOW, low.tone)
        assertEquals(Budget.State.WARNING, low.state) // 消耗 90% 同时 State 为警示

        val over = Budget.compute(1_000_00, 1_200_00, day)!!
        assertEquals(-0.2f, over.remainingRatio, 0.0001f)
        assertEquals(Budget.Tone.LOW, over.tone)
    }

    // ---------- 档位短标签（个人页预算卡/图例） ----------

    @Test
    fun `档位标签与用色边界一致`() {
        val t = Budget.thresholds(daysLeft = 21, daysInMonth = 30) // 绿线 66.7%，红线 35%
        assertEquals("充裕", Budget.toneLabel(0.70f, t))
        assertEquals("留意", Budget.toneLabel(0.50f, t))
        assertEquals("留意", Budget.toneLabel(0.35f, t))
        assertEquals("偏紧", Budget.toneLabel(0.34f, t))
        assertEquals("偏紧", Budget.toneLabel(0f, t))
        // 超支单独成词，但颜色仍落到 LOW（红）
        assertEquals("已超支", Budget.toneLabel(-0.01f, t))
        assertEquals(Budget.Tone.LOW, Budget.tone(-0.01f, t))
    }

    // ---------- 会计月区间（首日 ≠ 1） ----------

    @Test
    fun `自定义会计月的天数与剩余天数按区间算`() {
        // 首日 = 2：10-02 ~ 11-02，本期 31 天；10-06 距期末还有 27 天（含今天）
        val period = MonthPeriod.of(day, 2)
        assertEquals(LocalDate.of(2026, 10, 2), period.start)
        assertEquals(LocalDate.of(2026, 11, 2), period.endExclusive)
        assertEquals(31, period.days)
        val s = Budget.compute(1_000_00, 400_00, day, period = period)!!
        assertEquals(27, s.daysLeft)
        assertEquals(31, s.daysInMonth)
    }

    // ---------- 日预算模式 ----------

    @Test
    fun `日预算模式本日剩余按日预算算`() {
        // 月预算 3000（30 天 × 100），日预算 100；今日已花 30 → 本日剩余 70
        val s = Budget.compute(
            3_000_00, 1_000_00, day, todaySpentCents = 30_00,
            period = MonthPeriod.of(day, 1), dailyBudgetCents = 100_00,
        )!!
        assertEquals(100_00, s.suggestedDailyCents)
        assertEquals(70_00, s.todayLeftCents)
    }

    @Test
    fun `日预算模式超支月预算仍按日额给本日剩余`() {
        // 月预算已超支，但日预算模式下"今天还能花多少"看的是日额
        val s = Budget.compute(
            3_000_00, 3_500_00, day, todaySpentCents = 30_00,
            period = MonthPeriod.of(day, 1), dailyBudgetCents = 100_00,
        )!!
        assertEquals(100_00, s.suggestedDailyCents)
        assertEquals(70_00, s.todayLeftCents)
        assertEquals(Budget.Tone.LOW, s.tone)
    }

    // ---------- 本日剩余（建议日额 − 今日净支出） ----------

    @Test
    fun `本日剩余把今天的净支出整额扣掉`() {
        // 预算 5000、已花 2116（其中今天花了 300），10 月 6 日 → 剩 26 天
        val base = 2_884_00L / 26
        val s = Budget.compute(5_000_00, 2_116_00, day, todaySpentCents = 300_00)!!
        assertEquals(base, s.suggestedDailyCents)
        assertEquals(300_00, s.todaySpentCents)
        assertEquals(base - 300_00, s.todayLeftCents)
    }

    @Test
    fun `今天没花钱时本日剩余就是建议日额`() {
        val s = Budget.compute(5_000_00, 2_116_00, day)!!
        assertEquals(s.suggestedDailyCents, s.todayLeftCents)
    }

    @Test
    fun `今天退款到账会让本日剩余变多`() {
        // 今日净支出为负 = 今天"钱回来了"（退款）：预算口径里退款要正常算回来
        // 注：工资等收入不参与本日剩余（VM 侧只取 支出 − 退款到账）
        val s = Budget.compute(5_000_00, 2_116_00, day, todaySpentCents = -1_000_00)!!
        assertEquals(2_884_00L / 26 + 1_000_00, s.todayLeftCents)
    }

    @Test
    fun `今天花超时本日剩余为负`() {
        val s = Budget.compute(5_000_00, 2_116_00, day, todaySpentCents = 500_00)!!
        assertEquals(2_884_00L / 26 - 500_00, s.todayLeftCents)
        assertTrue(s.todayLeftCents < 0L)
    }

    @Test
    fun `本月已超支时建议日额为 0_本日剩余为负的今日净支出`() {
        val s = Budget.compute(1_000_00, 1_200_00, day, todaySpentCents = 50_00)!!
        assertEquals(0L, s.suggestedDailyCents)
        assertEquals(-50_00, s.todayLeftCents)
    }

    // ---------- 警示强度（心跳/摇晃幅度与红色饱和度） ----------

    @Test
    fun `警示强度随剩余减少而加深并封顶`() {
        assertEquals(0f, Budget.warningSeverity(0.2f, 0.2f), 0.0001f)   // 红线处：无警示
        assertEquals(0f, Budget.warningSeverity(0.8f, 0.2f), 0.0001f)
        assertEquals(0.4f, Budget.warningSeverity(0f, 0.2f), 0.0001f)   // 用完预算
        assertEquals(1f, Budget.warningSeverity(-0.3f, 0.2f), 0.0001f)  // 超支 30% 封顶
        assertEquals(1f, Budget.warningSeverity(-2f, 0.2f), 0.0001f)    // 更深超支不超上限
    }

    // ---------- 月预算折算日均（个人页预算卡） ----------

    @Test
    fun `月预算折算日均_四舍五入`() {
        // 5000 元 / 31 天 = 161.29 元 → 161.29 元（16129 分）
        assertEquals(161_29, Budget.dailyEquivalent(5_000_00, 31))
        // 整除时不引入误差
        assertEquals(100_00, Budget.dailyEquivalent(3_100_00, 31))
        // 5000 元 / 30 天 = 166.67 → 166.67 元（16667 分）
        assertEquals(166_67, Budget.dailyEquivalent(5_000_00, 30))
    }

    @Test
    fun `月预算未设置或天数非法时折算为 0`() {
        assertEquals(0L, Budget.dailyEquivalent(0L, 31))
        assertEquals(0L, Budget.dailyEquivalent(-1L, 31))
        assertEquals(0L, Budget.dailyEquivalent(5_000_00, 0))
        assertEquals(0L, Budget.dailyEquivalent(5_000_00, -1))
    }
}
