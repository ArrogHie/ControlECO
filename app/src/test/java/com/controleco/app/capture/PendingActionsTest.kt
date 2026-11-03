package com.controleco.app.capture

import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionsTest {
    private val ordinary = CategoryEntity(id = 1, name = "其他", type = TxType.INCOME)
    private val refund = CategoryEntity(id = 2, name = "退款", type = TxType.INCOME)

    @Test
    fun `refund capture cannot bypass selection by choosing ordinary category`() {
        assertFalse(PendingActions.ordinaryCategoryAllowed(TxType.INCOME, RefundMark.REFUND_INCOME, ordinary))
        assertFalse(PendingActions.ordinaryCategoryAllowed(TxType.INCOME, RefundMark.REFUND_INCOME, refund))
    }

    @Test
    fun `ordinary capture cannot create refund income or use missing or opposite category`() {
        assertFalse(PendingActions.ordinaryCategoryAllowed(TxType.INCOME, RefundMark.NONE, refund))
        assertFalse(PendingActions.ordinaryCategoryAllowed(TxType.INCOME, RefundMark.NONE, null))
        assertFalse(PendingActions.ordinaryCategoryAllowed(TxType.EXPENSE, RefundMark.NONE, ordinary))
        assertTrue(PendingActions.ordinaryCategoryAllowed(TxType.INCOME, RefundMark.NONE, ordinary))
    }
}
