package com.controleco.app.capture

import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class RefundCaptureTest {
    @After fun clear() { LogicalTransactions.clearAll() }
    private fun notice(key: String? = "wechat-1", stamp: Long = 100_000) = refundNoticePending(
        NotificationRules.WECHAT, 8L, stamp, "微信支付", "退款到账通知", "", key, stamp,
    )

    @Test fun `无金额退款是可持久化的待处理收入候选`() {
        val pending = notice()
        assertEquals(PendingStatus.PENDING, pending.status)
        assertEquals(RefundMark.REFUND_INCOME, pending.refundMark)
        assertEquals(TxType.INCOME, pending.type)
        assertEquals(0L, pending.amountCents)
        assertEquals(8L, pending.rawNotificationId)
        assertTrue(pending.dedupKey.contains("|INCOME|R|0|"))
    }

    @Test fun `退款即使有金额也不得直接入账`() {
        assertFalse(canDirectlyEnterCapture(true, 5000))
        assertFalse(canDirectlyEnterCapture(true, 0))
        assertFalse(canDirectlyEnterCapture(false, 0))
        assertTrue(canDirectlyEnterCapture(false, 5000))
    }

    @Test fun `同通知身份稳定不同实例同分钟不合并`() {
        assertEquals(notice().dedupKey, notice().dedupKey)
        assertNotEquals(notice().dedupKey, notice("wechat-2").dedupKey)
        assertNotEquals(notice(null).dedupKey, notice(null, 100_001).dedupKey)
        assertEquals(notificationInstanceId("wechat-1", 100_000, TxType.INCOME, true), notice().notificationInstanceId)
    }

    @Test fun `只有待处理无金额退款允许同通知补金额`() {
        assertTrue(canFillRefundAmount(notice(), true, 1200))
        assertFalse(canFillRefundAmount(notice(), false, 1200))
        assertFalse(canFillRefundAmount(notice(), true, 0))
        assertFalse(canFillRefundAmount(notice().copy(amountCents = 100), true, 1200))
        assertFalse(canFillRefundAmount(notice().copy(status = PendingStatus.AUTO), true, 1200))
        assertFalse(canFillRefundAmount(notice().copy(refundMark = RefundMark.NONE), true, 1200))
    }

    @Test fun `无金额退款忽略后同键不再出现但不吞另一条退款`() {
        val pending = notice().copy(createdAt = 100_000)
        LogicalTransactions.markResolved(pending, CaptureResolution.REJECTED, now = 100_001)
        assertNotNull(LogicalTransactions.findResolvedByKey(pending.dedupKey, 100_002))
        assertNull(LogicalTransactions.findResolvedByKey(notice("wechat-2").dedupKey, 100_002))
        assertNull(LogicalTransactions.findResolvedByKey(pending.dedupKey, 280_001))
    }
}
