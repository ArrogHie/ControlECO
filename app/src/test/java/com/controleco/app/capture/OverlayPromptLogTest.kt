package com.controleco.app.capture

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "这条条目已经成功弹过卡"的登记簿（2026-10-27：同一笔只打扰一次）。
 *
 * 语义要点：
 * - 只有**成功展示**过才登记 → 后续富化不再新弹（`retryCardIfNoCard` 直接跳过）；
 * - 未登记（首次被支付保护层拦截 / 根本没弹过）→ 仍允许补试一次；
 * - 条目被忽略/撤销/确认消失时必须清理，否则去重键复用时该弹却不弹；
 * - **刻意不落库**：进程重启后失效（用户明确选择"只存内存"）。
 */
class OverlayPromptLogTest {

    @After
    fun tearDown() {
        OverlayPromptLog.clearAll()
    }

    @Test
    fun `未弹过时不视为已打扰_允许补试`() {
        assertFalse(OverlayPromptLog.wasShown("k1"))
    }

    @Test
    fun `成功展示后登记_后续不再弹`() {
        OverlayPromptLog.markShown("k1")
        assertTrue(OverlayPromptLog.wasShown("k1"))
        // 重复登记幂等
        OverlayPromptLog.markShown("k1")
        assertTrue(OverlayPromptLog.wasShown("k1"))
    }

    @Test
    fun `不同去重键互不影响`() {
        OverlayPromptLog.markShown("k1")
        assertFalse(OverlayPromptLog.wasShown("k2"))
    }

    @Test
    fun `条目消失后清理_去重键复用时可以再弹`() {
        OverlayPromptLog.markShown("k1")
        OverlayPromptLog.clear("k1")
        assertFalse(OverlayPromptLog.wasShown("k1"))
    }

    @Test
    fun `空去重键不登记也不命中`() {
        OverlayPromptLog.markShown("")
        assertFalse(OverlayPromptLog.wasShown(""))
    }
}
