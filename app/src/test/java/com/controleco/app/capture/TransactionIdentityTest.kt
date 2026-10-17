package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交易身份与去重键（CR-002 / CR-010）：
 * - 页面/通知/银行候选第一次生成就带身份后缀，不再依赖内存分配器；
 * - 同一流程/同一通知实例重复生成得到同一个键；
 * - 不同流程/不同通知实例即使同分钟同额也不会互相覆盖；
 * - 摘要使用完整 SHA-256，不用容易碰撞的短哈希承担交易身份。
 */
class TransactionIdentityTest {

    private val base = "com.tencent.mm|EXPENSE|1290|29500"

    @Test
    fun `页面第一笔也带流程身份_不再使用裸键`() {
        val first = dedupKeyWithIdentity(base, "p:flow-1")
        assertEquals("$base@p:flow-1", first)
        // 同一流程的重复页面得到同一个键
        assertEquals(first, dedupKeyWithIdentity(base, "p:flow-1"))
        // 另一流程同分钟同额得到不同的键（唯一索引不会吞掉第二笔）
        assertNotEquals(first, dedupKeyWithIdentity(base, "p:flow-2"))
    }

    @Test
    fun `没有身份时保留原键`() {
        assertEquals(base, dedupKeyWithIdentity(base, null))
        assertEquals(base, dedupKeyWithIdentity(base, ""))
    }

    @Test
    fun `通知实例标识_同一实例稳定_不同实例分开`() {
        val a = notificationInstanceId("0|com.tencent.mm|1|null|10001", 1_700_000_000_000, TxType.EXPENSE, false)!!
        val same = notificationInstanceId("0|com.tencent.mm|1|null|10001", 1_700_000_000_000, TxType.EXPENSE, false)!!
        val anotherTime = notificationInstanceId("0|com.tencent.mm|1|null|10001", 1_700_000_060_000, TxType.EXPENSE, false)!!
        val anotherKey = notificationInstanceId("0|com.tencent.mm|2|null|10001", 1_700_000_000_000, TxType.EXPENSE, false)!!
        assertEquals(a, same)
        assertNotEquals(a, anotherTime)
        assertNotEquals(a, anotherKey)
        assertTrue(a.startsWith("n:"))
    }

    @Test
    fun `通知实例标识_没有系统通知键时退化为无身份`() {
        assertEquals(null, notificationInstanceId(null, 1_700_000_000_000, TxType.EXPENSE, false))
        assertEquals(null, notificationInstanceId("", 1_700_000_000_000, TxType.EXPENSE, false))
    }

    @Test
    fun `银行事件标识_重复投递相同_不同事件分开`() {
        val a = bankInstanceId("SMS:工商银行", 1_700_000_000_000, TxType.EXPENSE, 5000, false, "1234")
        val same = bankInstanceId("SMS:工商银行", 1_700_000_000_000, TxType.EXPENSE, 5000, false, "1234")
        val other = bankInstanceId("SMS:工商银行", 1_700_000_060_000, TxType.EXPENSE, 5000, false, "1234")
        val otherCard = bankInstanceId("SMS:工商银行", 1_700_000_000_000, TxType.EXPENSE, 5000, false, "5678")
        assertEquals(a, same)
        assertNotEquals(a, other)
        assertNotEquals(a, otherCard)
        assertTrue(a.startsWith("b:"))
    }

    @Test
    fun `摘要长度固定64位十六进制`() {
        val digest = stableDigest("hello")
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" })
        assertNotEquals(digest, stableDigest("hello!"))
    }

    @Test
    fun `去重键仍能解析出完整身份后缀`() {
        val identity = "p:550e8400-e29b-41d4-a716-446655440000"
        val key = dedupKeyWithIdentity(base, identity)
        assertEquals(identity, flowIdentityOf(key))
        assertEquals(29500L, parseDedupKey(key)!!.minute)
        assertEquals(identity, parseDedupKey(key)!!.flowIdentity)
    }

    @Test
    fun `关联条目未写入时必须抛异常以触发事务回滚`() {
        // CR-008：正式交易插入成功后，关联条目返回 -1 不能被静默吞掉
        try {
            requireEntryAssociation(-1L)
            org.junit.Assert.fail("应抛出 EntryAssociationException")
        } catch (_: EntryAssociationException) {
            // expected
        }
        try {
            requireEntryAssociation(0L)
            org.junit.Assert.fail("应抛出 EntryAssociationException")
        } catch (_: EntryAssociationException) {
            // expected
        }
        requireEntryAssociation(1L)
    }
}
