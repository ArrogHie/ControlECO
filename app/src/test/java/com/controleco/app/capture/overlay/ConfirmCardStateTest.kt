package com.controleco.app.capture.overlay

import com.controleco.app.capture.NotificationRules
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import org.junit.Assert.*
import org.junit.Test

class ConfirmCardStateTest {
    private val categories = listOf(
        CategoryEntity(id = 1, name = "其他", type = TxType.EXPENSE),
        CategoryEntity(id = 2, name = "餐饮", type = TxType.EXPENSE),
        CategoryEntity(id = 3, name = "交通", type = TxType.EXPENSE),
    )
    private val accounts = listOf(AccountEntity(id = 1, name = "零钱"), AccountEntity(id = 2, name = "现金"))
    private val pending = PendingTransactionEntity(
        id = 1, type = TxType.EXPENSE, amountCents = 2000, timestamp = 1000,
        sourcePackage = NotificationRules.WECHAT, ruleName = "支付", dedupKey = "k",
    )

    @Test fun `后到商户把其他更新为明确分类`() {
        val state = ConfirmCardState()
        state.bind(pending, categories, accounts)
        assertEquals(1L, state.categoryId)
        state.mergeFrom(pending.copy(counterparty = "滴滴出行"), categories, accounts)
        assertEquals(3L, state.categoryId)
    }

    @Test fun `用户所选分类账户不被后到捕获覆盖`() {
        val state = ConfirmCardState()
        state.bind(pending, categories, accounts)
        state.selectCategory(2)
        state.selectAccount(2)
        state.mergeFrom(pending.copy(counterparty = "滴滴出行", paymentInstrument = "零钱"), categories, accounts)
        assertEquals(2L, state.categoryId)
        assertEquals(2L, state.accountId)
    }

    @Test fun `自己的悬浮窗事件不能污染前台来源`() {
        val own = "com.controleco.app"
        assertEquals(NotificationRules.WECHAT, foregroundAfterEvent(NotificationRules.WECHAT, own, own, false))
        assertNull(foregroundAfterEvent(null, own, own, false))
        assertEquals(own, foregroundAfterEvent(NotificationRules.WECHAT, own, own, true))
        assertEquals("another.app", foregroundAfterEvent(NotificationRules.WECHAT, "another.app", own, false))
    }
}
