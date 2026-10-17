package com.controleco.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知/短信时间兜底（CR-007）：异常时间不能污染分钟去重桶和账单日期。
 */
class EventTimeSanitizeTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    @Test
    fun `零值与异常值回落到采集时刻`() {
        assertEquals(now, sanitizeEventTime(0, now))
        assertEquals(now, sanitizeEventTime(-1, now))
        assertEquals(now, sanitizeEventTime(now + 6 * minute, now))
        assertEquals(now, sanitizeEventTime(now - 25 * hour, now))
    }

    @Test
    fun `合理范围内保留原始时间`() {
        assertEquals(now - 5 * minute, sanitizeEventTime(now - 5 * minute, now))
        assertEquals(now + 4 * minute, sanitizeEventTime(now + 4 * minute, now))
        assertEquals(now - 23 * hour, sanitizeEventTime(now - 23 * hour, now))
    }

    @Test
    fun `通知显示时间有效时优先用它`() {
        assertEquals(now - 2 * minute, resolveNotificationTime(now - 2 * minute, now - 3 * minute, now))
    }

    @Test
    fun `通知显示时间异常时用系统发布时间`() {
        assertEquals(now - 3 * minute, resolveNotificationTime(0, now - 3 * minute, now))
        assertEquals(now - 3 * minute, resolveNotificationTime(now + hour, now - 3 * minute, now))
    }

    @Test
    fun `显示时间与系统发布时间都异常时用采集时刻`() {
        assertEquals(now, resolveNotificationTime(0, 0, now))
        assertEquals(now, resolveNotificationTime(now + hour, now - 30 * hour, now))
    }
}
