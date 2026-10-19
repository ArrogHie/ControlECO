package com.controleco.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同包名次要窗口的采集过滤：收可见且（聚焦/活动或大尺寸覆盖层）的窗口，
 * 既要防止微信小浮窗/文件面板污染页面快照，也要保证红包封面等大尺寸覆盖层被采集。
 */
class CaptureHeuristicsTest {

    @Test
    fun `可见且聚焦_纳入`() {
        assertTrue(shouldCollectSecondaryWindow(visible = true, focused = true, active = false))
    }

    @Test
    fun `可见且活动_纳入`() {
        assertTrue(shouldCollectSecondaryWindow(visible = true, focused = false, active = true))
    }

    @Test
    fun `可见的大尺寸覆盖层_即使失焦非活动也纳入`() {
        assertTrue(
            shouldCollectSecondaryWindow(
                visible = true, focused = false, active = false, largeVisible = true,
            ),
        )
    }

    @Test
    fun `可见但失焦且非活动的小尺寸浮窗_排除`() {
        assertFalse(
            shouldCollectSecondaryWindow(
                visible = true, focused = false, active = false, largeVisible = false,
            ),
        )
    }

    @Test
    fun `不可见_即使聚焦活动或大尺寸也排除`() {
        assertFalse(shouldCollectSecondaryWindow(visible = false, focused = true, active = true))
        assertFalse(shouldCollectSecondaryWindow(visible = false, focused = true, active = false))
        assertFalse(shouldCollectSecondaryWindow(visible = false, focused = false, active = true))
        assertFalse(
            shouldCollectSecondaryWindow(
                visible = false, focused = false, active = false, largeVisible = true,
            ),
        )
    }
}
