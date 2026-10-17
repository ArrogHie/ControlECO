package com.controleco.app.capture

import com.controleco.app.capture.page.PageFlow
import com.controleco.app.capture.page.PageFlowTracker
import com.controleco.app.capture.page.PageScene
import com.controleco.app.capture.page.TradeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面支付流程身份（CR-001）：
 * 同一笔交易的确认页/结果页/详情页共享身份；用户真正开启下一笔时换身份；
 * 窗口切换、重复事件、重复采样都不能单独产生新身份。
 */
class PageFlowIdentityTest {

    @Test
    fun `同一流程内身份稳定_窗口变化不影响`() {
        val flow = PageFlow("com.tencent.mm", 1_000)
        val id = flow.flowInstanceId
        assertTrue(id.startsWith("p:"))
        flow.noteConfirmPage(1290, "肯德基", null, 1_000)
        assertEquals(id, flow.flowInstanceId)
        flow.noteSuccess(null)
        assertEquals(id, flow.flowInstanceId)
        flow.noteScene(PageScene.TRANSACTION_DETAIL, TradeState.SUCCESS)
        assertEquals(id, flow.flowInstanceId)
    }

    @Test
    fun `连续收银台采样不换身份_上一笔完成后再次进入收银台才换`() {
        val flow = PageFlow("com.tencent.mm", 1_000)
        flow.noteConfirmPage(1290, null, null, 1_000)
        val first = flow.flowInstanceId
        // 同一张收银台的重复事件（PENDING）不开启新流程
        flow.noteConfirmPage(1290, null, null, 1_500)
        assertEquals(first, flow.flowInstanceId)

        flow.noteSuccess(null)
        flow.noteConfirmPage(1290, null, null, 20_000)
        assertNotEquals(first, flow.flowInstanceId)
        // 新流程使用本次收银台的时间锚点，而不是上一笔的
        assertEquals(20_000L, flow.anchorTime)
        assertEquals(1290L, flow.confirmAmountCents)
    }

    @Test
    fun `失败或取消后重试也开启新流程`() {
        val flow = PageFlow("com.tencent.mm", 1_000)
        flow.noteScene(PageScene.PAYMENT_FAILED, TradeState.FAILED)
        val failed = flow.flowInstanceId
        flow.noteConfirmPage(1290, null, null, 5_000)
        assertNotEquals(failed, flow.flowInstanceId)
    }

    @Test
    fun `重复结果页事件不生成新候选`() {
        val flow = PageFlow("com.wechat", 1_000)
        flow.noteSuccess(null)
        assertEquals(0, flow.markEmitted("success|1290|fingerprint"))
        assertEquals(-1, flow.markEmitted("success|1290|fingerprint"))
    }

    @Test
    fun `结果页终结且离开过_同一结果页再次出现视为新的一笔`() {
        val flow = PageFlow("com.wechat", 1_000)
        flow.noteSuccess(null)
        assertEquals(0, flow.markEmitted("success|1290|fingerprint"))
        val first = flow.flowInstanceId
        // 离开结果页（页面已终结）后至少经过最小间隔再回到同一结果页
        flow.noteLeftTradePage(2_000)
        flow.updatedAt = 8_000
        assertEquals(0, flow.markEmitted("success|1290|fingerprint"))
        assertNotEquals(first, flow.flowInstanceId)
    }

    @Test
    fun `离开后立刻出现同一页面_仍视为重复事件`() {
        val flow = PageFlow("com.wechat", 1_000)
        flow.noteSuccess(null)
        assertEquals(0, flow.markEmitted("success|1290|fingerprint"))
        flow.noteLeftTradePage(2_000)
        flow.updatedAt = 2_500 // < RESULT_REENTRY_MIN_GAP_MS
        assertEquals(-1, flow.markEmitted("success|1290|fingerprint"))
    }

    @Test
    fun `流程超时后开启的新流程使用新身份`() {
        var now = 10_000L
        val tracker = PageFlowTracker(ttlMs = 1_000, clock = { now })
        val first = tracker.flow("com.tencent.mm")
        val firstId = first.flowInstanceId
        now += 2_000
        tracker.evictExpired()
        val second = tracker.flow("com.tencent.mm")
        assertNotEquals(firstId, second.flowInstanceId)
    }

    @Test
    fun `显式重启生成新身份`() {
        val tracker = PageFlowTracker(clock = { 1_000 })
        val first = tracker.flow("com.tencent.mm")
        val second = tracker.restart("com.tencent.mm")
        assertNotEquals(first.flowInstanceId, second.flowInstanceId)
    }
}
