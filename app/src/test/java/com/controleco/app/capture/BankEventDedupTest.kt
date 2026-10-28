package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同一家银行的多通道去重（短信 ⇄ 银行推送 ⇄ 银行页面）。
 *
 * 真实事故（2026-10-27 用户实测）：微信绑卡支付 → 同时捕获到微信页面、银行短信、银行通知，
 * 结果记成两笔。根因之一是"银行短信 ⇄ 银行推送"从不互相去重（来源键不同 → dedupKey/身份不同）。
 */
class BankEventDedupTest {

    private val t0 = 1_700_000_000_000L

    private fun key(
        bankName: String = "招商银行",
        amountCents: Long = 20_000,
        type: TxType = TxType.EXPENSE,
        isRefund: Boolean = false,
        cardTail: String? = "2532",
    ) = BankEventDedup.keyOf(bankName, amountCents, type, isRefund, cardTail)!!

    // ---------- 银行名归一 ----------

    @Test
    fun `银行别名归一为标准名`() {
        assertEquals("招商银行", BankEventDedup.standardBankName("招行"))
        assertEquals("招商银行", BankEventDedup.standardBankName("招商银行"))
        assertEquals("工商银行", BankEventDedup.standardBankName("中国工商银行"))
        assertEquals("工商银行", BankEventDedup.standardBankName("工行"))
        assertEquals("建设银行", BankEventDedup.standardBankName("建行"))
        // 认不出的原样返回（去空白），不会把两家银行错误归一
        assertEquals("某某银行", BankEventDedup.standardBankName(" 某某银行 "))
        assertEquals("", BankEventDedup.standardBankName("   "))
    }

    @Test
    fun `银行名为空不参与归并`() {
        assertNull(BankEventDedup.keyOf("", 100, TxType.EXPENSE, false, null))
        assertNull(BankEventDedup.keyOf("   ", 100, TxType.EXPENSE, false, null))
    }

    @Test
    fun `尾号只认四位其余视为未知`() {
        assertNull(key(cardTail = null).cardTail)
        assertNull(key(cardTail = "").cardTail)
        assertNull(key(cardTail = "123").cardTail)   // 非四位 = 未知，按通配处理
        assertEquals("2532", key(cardTail = "2532").cardTail)
    }

    // ---------- 同笔判定 ----------

    @Test
    fun `同一家银行同额同向同退款即同一笔`() {
        assertTrue(BankEventDedup.matches(key(), key()))
        // 别名与标准名等价
        assertTrue(BankEventDedup.matches(key(bankName = "招商银行"), key(bankName = "招行")))
    }

    @Test
    fun `金额方向退款银行名任一不同即不同笔`() {
        assertFalse(BankEventDedup.matches(key(), key(amountCents = 20_001)))
        assertFalse(BankEventDedup.matches(key(), key(type = TxType.INCOME)))
        assertFalse(BankEventDedup.matches(key(), key(isRefund = true)))
        assertFalse(BankEventDedup.matches(key(bankName = "招商银行"), key(bankName = "工商银行")))
    }

    @Test
    fun `尾号都有则必须相等缺一侧视为通配`() {
        assertTrue(BankEventDedup.tailsCompatible("2532", "2532"))
        assertFalse(BankEventDedup.tailsCompatible("2532", "8888"))
        // 真实招行推送只写"您账户2532"，extractCardTail 取不到 → null，必须仍能与短信配对
        assertTrue(BankEventDedup.tailsCompatible("2532", null))
        assertTrue(BankEventDedup.tailsCompatible(null, "2532"))
        assertTrue(BankEventDedup.tailsCompatible(null, null))
        // 空串等同于"没有尾号"
        assertTrue(BankEventDedup.tailsCompatible("2532", ""))
    }

    // ---------- 1:1 配对登记 ----------

    @Test
    fun `短信与推送互相吸收一次`() {
        val claims = BankEventClaims()
        claims.claim(pendingId = 7, key = key(), channel = BankEventDedup.Channel.PUSH, eventTime = t0, now = t0)

        // 短信到达：命中同笔的推送条目
        assertEquals(7L, claims.find(key(), BankEventDedup.Channel.SMS, t0 + 8_000, now = t0 + 8_000))
        claims.claim(7, key(), BankEventDedup.Channel.SMS, t0 + 8_000, now = t0 + 8_000)

        // 同一通道再来一条（第二条短信）→ 不再吸收（否则两笔真实同额会被并成一条）
        assertNull(claims.find(key(), BankEventDedup.Channel.SMS, t0 + 9_000, now = t0 + 9_000))
        // 页面通道仍可吸收
        assertEquals(7L, claims.find(key(), BankEventDedup.Channel.PAGE, t0 + 9_000, now = t0 + 9_000))
    }

    @Test
    fun `超过窗口不配对`() {
        val claims = BankEventClaims()
        claims.claim(7, key(), BankEventDedup.Channel.PUSH, t0, now = t0)
        assertNotNull(claims.find(key(), BankEventDedup.Channel.SMS, t0 + BankEventDedup.WINDOW_MS, now = t0))
        assertNull(
            claims.find(key(), BankEventDedup.Channel.SMS, t0 + BankEventDedup.WINDOW_MS + 1, now = t0),
        )
    }

    @Test
    fun `多条候选取时间最接近的一条`() {
        val claims = BankEventClaims()
        claims.claim(1, key(), BankEventDedup.Channel.PUSH, t0, now = t0)
        claims.claim(2, key(), BankEventDedup.Channel.PUSH, t0 + 60_000, now = t0)
        // 短信时间更靠近 #2
        assertEquals(2L, claims.find(key(), BankEventDedup.Channel.SMS, t0 + 70_000, now = t0))
        // 更靠近 #1
        assertEquals(1L, claims.find(key(), BankEventDedup.Channel.SMS, t0 + 10_000, now = t0))
    }

    @Test
    fun `金额不同的候选不会被吸收`() {
        val claims = BankEventClaims()
        claims.claim(7, key(amountCents = 20_000), BankEventDedup.Channel.PUSH, t0, now = t0)
        assertNull(claims.find(key(amountCents = 19_200), BankEventDedup.Channel.SMS, t0, now = t0))
    }

    @Test
    fun `条目消失后可被清掉`() {
        val claims = BankEventClaims()
        claims.claim(7, key(), BankEventDedup.Channel.PUSH, t0, now = t0)
        claims.forget(7)
        assertNull(claims.find(key(), BankEventDedup.Channel.SMS, t0, now = t0))
        assertEquals(0, claims.size())
    }

    @Test
    fun `登记有 TTL 与容量上限`() {
        val claims = BankEventClaims(ttlMs = 60_000, cap = 2)
        claims.claim(1, key(), BankEventDedup.Channel.PUSH, t0, now = t0)
        // 超过 TTL 自动淘汰
        assertNull(
            claims.find(key(), BankEventDedup.Channel.SMS, t0, now = t0 + 60_001),
        )
        assertEquals(0, claims.size())

        // 容量上限：超出后淘汰最早登记的一条
        claims.claim(1, key(amountCents = 1), BankEventDedup.Channel.PUSH, t0, now = t0)
        claims.claim(2, key(amountCents = 2), BankEventDedup.Channel.PUSH, t0, now = t0)
        claims.claim(3, key(amountCents = 3), BankEventDedup.Channel.PUSH, t0, now = t0)
        assertEquals(2, claims.size())
        assertNull(claims.find(key(amountCents = 1), BankEventDedup.Channel.SMS, t0, now = t0))
        assertNotNull(claims.find(key(amountCents = 3), BankEventDedup.Channel.SMS, t0, now = t0))
    }

    @Test
    fun `非法条目 id 不登记`() {
        val claims = BankEventClaims()
        claims.claim(0, key(), BankEventDedup.Channel.SMS, t0, now = t0)
        claims.claim(-1, key(), BankEventDedup.Channel.SMS, t0, now = t0)
        assertEquals(0, claims.size())
    }

    @Test
    fun `收入方向同样去重`() {
        val income = key(bankName = "招商银行", type = TxType.INCOME, cardTail = null)
        val claims = BankEventClaims()
        claims.claim(9, income, BankEventDedup.Channel.PUSH, t0, now = t0)
        assertEquals(9L, claims.find(income, BankEventDedup.Channel.SMS, t0 + 5_000, now = t0 + 5_000))
    }

    // ---------- 真实样本（2026-10-27 工行 ¥36.40 盒马：短信 + 系统短信 App 通知，记了两笔） ----------

    @Test
    fun `真实样本_同一条短信的短信通道与通知通道会被一比一吸收`() {
        // 两条消息都取不到尾号（正文是「尾号卡10月27日…」，没有四位数字）→ 按通配处理
        val smsKey = BankEventDedup.keyOf("工商银行", 3640, TxType.EXPENSE, false, null)!!
        val noticeKey = BankEventDedup.keyOf("工商银行", 3640, TxType.EXPENSE, false, null)!!
        assertTrue(BankEventDedup.matches(smsKey, noticeKey))

        // 短信先入账（#26）→ 同银行同额的通知到达时并入，不再记第二笔
        val claims = BankEventClaims()
        claims.claim(26, smsKey, BankEventDedup.Channel.SMS, t0, now = t0)
        assertEquals(26L, claims.find(noticeKey, BankEventDedup.Channel.PUSH, t0, now = t0))
        claims.claim(26, noticeKey, BankEventDedup.Channel.PUSH, t0, now = t0)
        // 之后同一通道再来（例如第二条短信）不再吸收 → 不会把两笔真实同额并成一条
        assertNull(claims.find(smsKey, BankEventDedup.Channel.SMS, t0 + 1_000, now = t0))
    }

    @Test
    fun `真实样本_不同银行或不同金额不会误并`() {
        val icbc = BankEventDedup.keyOf("工商银行", 3640, TxType.EXPENSE, false, null)!!
        val cmb = BankEventDedup.keyOf("招商银行", 3640, TxType.EXPENSE, false, null)!!
        assertFalse(BankEventDedup.matches(icbc, cmb))
        val claims = BankEventClaims()
        claims.claim(26, icbc, BankEventDedup.Channel.SMS, t0, now = t0)
        assertNull(claims.find(cmb, BankEventDedup.Channel.PUSH, t0, now = t0))
        assertNull(
            claims.find(
                BankEventDedup.keyOf("工商银行", 3641, TxType.EXPENSE, false, null)!!,
                BankEventDedup.Channel.PUSH, t0, now = t0,
            ),
        )
    }
}
