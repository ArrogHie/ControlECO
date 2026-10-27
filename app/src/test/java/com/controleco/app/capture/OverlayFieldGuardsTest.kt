package com.controleco.app.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮卡"用户改过哪些字段"的登记簿（2026-10-27 字段级保护）。
 *
 * 语义要点：
 * - 同一去重键多次编辑取并集（用户改过的字段只增不减）；
 * - 未登记 = 空集 = 后续照常自动富化；
 * - 条目被忽略/撤销/确认消失时必须清理，避免去重键复用后误锁；
 * - **刻意不落库**：进程重启后登记丢失（用户明确选择"只存内存"）。
 */
class OverlayFieldGuardsTest {

    @After
    fun tearDown() {
        OverlayFieldGuards.clearAll()
    }

    @Test
    fun `未登记时返回空集_全部照常富化`() {
        assertTrue(OverlayFieldGuards.fieldsOf("k1").isEmpty())
        assertFalse(OverlayFieldGuards.isGuarded("k1", GuardedField.COUNTERPARTY))
    }

    @Test
    fun `多次编辑取并集`() {
        OverlayFieldGuards.mark("k1", setOf(GuardedField.COUNTERPARTY))
        OverlayFieldGuards.mark("k1", setOf(GuardedField.NOTE))
        assertEquals(setOf(GuardedField.COUNTERPARTY, GuardedField.NOTE), OverlayFieldGuards.fieldsOf("k1"))
    }

    @Test
    fun `不同去重键互不影响`() {
        OverlayFieldGuards.mark("k1", setOf(GuardedField.AMOUNT))
        assertTrue(OverlayFieldGuards.fieldsOf("k2").isEmpty())
        assertTrue(OverlayFieldGuards.isGuarded("k1", GuardedField.AMOUNT))
    }

    @Test
    fun `清理后不再保护`() {
        OverlayFieldGuards.mark("k1", setOf(GuardedField.ACCOUNT))
        OverlayFieldGuards.clear("k1")
        assertTrue(OverlayFieldGuards.fieldsOf("k1").isEmpty())
    }

    @Test
    fun `空去重键与空字段集合不登记`() {
        OverlayFieldGuards.mark("", setOf(GuardedField.AMOUNT))
        OverlayFieldGuards.mark("k1", emptySet())
        assertTrue(OverlayFieldGuards.fieldsOf("").isEmpty())
        assertTrue(OverlayFieldGuards.fieldsOf("k1").isEmpty())
    }
}
