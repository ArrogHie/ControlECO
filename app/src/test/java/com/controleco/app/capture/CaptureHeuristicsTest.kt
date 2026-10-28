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

    // ---------- 银行动账推送的候选闸门（2026-10-27：只认银行 App 包名） ----------

    private fun candidate(
        packageName: String,
        interested: Boolean = false,
        bankPushEnabled: Boolean = true,
        captureEnabled: Boolean = true,
        watchedOrUnknown: Boolean = true,
    ) = isBankPushCandidate(
        packageName = packageName,
        interested = interested,
        bankPushEnabled = bankPushEnabled,
        captureEnabled = captureEnabled,
        watchedOrUnknown = watchedOrUnknown,
    )

    @Test
    fun `已知银行包名_纳入银行通道`() {
        assertTrue(candidate("cmb.pb"))
        assertTrue(candidate("com.icbc"))
        assertTrue(candidate("com.chinamworld.main"))
    }

    @Test
    fun `目录外App一律不纳入_即使正文含银行名`() {
        // 旧口径"正文出现银行名即候选"会让任意 App（营销/资讯/别的记账软件）被记成假账；
        // 现在闸门只看包名，正文内容根本不参与判定
        assertFalse(candidate("com.example.shopping"))
        assertFalse(candidate("com.example.news"))
        assertFalse(candidate("com.unknown.bank"))   // 未收录的银行 App 也要先加白名单
    }

    @Test
    fun `支付渠道包名不走银行通道`() {
        assertFalse(candidate(NotificationRules.WECHAT, interested = true))
        assertFalse(candidate(NotificationRules.ALIPAY, interested = true))
    }

    @Test
    fun `系统短信App的通知不重复入账`() {
        // 真实样本（2026-10-27）：同一条工行 ¥36.40 短信既走了短信通道，又被系统短信 App 的通知
        // 捕获，于是记了两笔（transaction 26 / 27）。com.android.mms 不是银行 App 包名 → 不入账。
        assertFalse(candidate("com.android.mms"))
    }

    @Test
    fun `用户关掉该应用或总开关关闭_不采集`() {
        assertFalse(candidate("cmb.pb", watchedOrUnknown = false))
        assertFalse(candidate("cmb.pb", bankPushEnabled = false))
        assertFalse(candidate("cmb.pb", captureEnabled = false))
    }
}
