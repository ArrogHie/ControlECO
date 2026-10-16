package com.controleco.app.capture

import com.controleco.app.capture.page.PageParseResult
import com.controleco.app.capture.page.PageParserRegistry
import com.controleco.app.capture.page.PageSnapshot
import com.controleco.app.capture.page.PageFlowTracker
import com.controleco.app.capture.page.PageNode
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨来源隔离的补充用例：
 * 1. 两个不同应用在同一分钟产生同额交易 → 去重键不同、也不会互相归并；
 * 2. 页面识别失败（走到兜底也认不出）不影响通知通道：页面通道返回"与交易无关"，不抛异常、不产生候选。
 */
class CaptureIsolationTest {

    private fun pending(
        packageName: String,
        amount: Long,
        createdAt: Long,
        key: String,
        status: PendingStatus = PendingStatus.PENDING,
    ) = PendingTransactionEntity(
        id = 1L,
        type = TxType.EXPENSE,
        amountCents = amount,
        timestamp = createdAt,
        sourcePackage = packageName,
        ruleName = "测试",
        dedupKey = key,
        status = status,
        createdAt = createdAt,
    )

    @Test
    fun `两个不同应用同分钟同额_去重键不同且不互相归并`() {
        val minute = 29_640_000L
        val wechatKey = NotificationRules.dedupKey(NotificationRules.WECHAT, TxType.EXPENSE, false, 1290, minute * 60_000)
        val alipayKey = NotificationRules.dedupKey(NotificationRules.ALIPAY, TxType.EXPENSE, false, 1290, minute * 60_000)
        assertNotEquals("不同应用的同一分钟同额交易必须落在不同的键上", wechatKey, alipayKey)

        // 微信条目在场时，支付宝的同额候选不得被并进去（归并按包名限定）
        val pool = listOf(pending(NotificationRules.WECHAT, 1290, minute * 60_000, wechatKey))
        assertNull(
            pickRelatedPending(
                pool, NotificationRules.ALIPAY, TxType.EXPENSE, 1290, minute * 60_000,
            ),
        )
        assertNull(
            pickRelatedPendingForPage(
                pool, NotificationRules.ALIPAY, TxType.EXPENSE, 1290, minute * 60_000, "f-x",
            ),
        )
        // 同应用的同额同分钟候选仍然命中既有条目（这是"同一笔只落一笔账"的基础）
        assertEquals(
            1L,
            pickRelatedPending(
                pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, minute * 60_000 + 2_000,
            )?.pending?.id,
        )
    }

    @Test
    fun `页面识别失败不影响通知通道_只返回与交易无关且不抛异常`() {
        // 页面文本在目录内应用的页面上确实出现过、但不符合任何交易场景（营销页/帮助页）
        val marketing = PageSnapshot(
            packageName = NotificationRules.WECHAT,
            captureTime = 1_800_000_000_000L,
            windowId = 1,
            nodes = listOf(
                PageNode(text = "限时活动"),
                PageNode(text = "立即领取"),
                PageNode(text = "更多精彩内容"),
            ),
        )
        val result = PageParserRegistry.parse(marketing, PageFlowTracker().flow(marketing.packageName))
        assertTrue(result is PageParseResult.NotATradePage)

        // 同一时刻通知通道的解析完全不受影响（两条通道彼此独立）
        val parsed = NotificationRules.parse(
            NotificationRules.WECHAT,
            "微信支付",
            "已支付¥12.80",
            "",
            1_800_000_000_000L,
        )
        assertEquals(1280L, parsed?.amountCents)
        assertEquals(TxType.EXPENSE, parsed?.type)
    }
}
