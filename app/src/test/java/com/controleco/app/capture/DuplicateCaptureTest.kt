package com.controleco.app.capture

import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复捕获的两条判定（2026-10-30「重复捕获不弹第二个悬浮窗」）：
 *
 * - [pickRecentSameEntry]：合并池里"同一笔"是否已经存在（金额 + 时间 + **身份兼容**）；
 * - [mergeDuplicateCapture]：并入既有条目时只补空白/只追加备注，用户改过的字段一律不动。
 *
 * 这两条共同保证了"一次真实支付最多打扰一次、用户编辑不被覆盖"，同时不吞掉
 * 两笔真实的同额交易。
 */
class DuplicateCaptureTest {

    private val base = 1_734_000_000_000L
    private val wechat = NotificationRules.WECHAT

    private fun pending(
        id: Long = 1,
        packageName: String = wechat,
        type: TxType = TxType.EXPENSE,
        amount: Long = 1290,
        timestamp: Long = base,
        createdAt: Long = base,
        status: PendingStatus = PendingStatus.PENDING,
        counterparty: String = "",
        note: String = "微信支付",
        paymentInstrument: String = "",
        userEdited: Boolean = false,
        flowId: String = "",
        notificationInstanceId: String = "",
        bankInstanceId: String = "",
        dedupKey: String = "$packageName|$type|$amount|${timestamp / 60_000}",
    ) = PendingTransactionEntity(
        id = id,
        type = type,
        amountCents = amount,
        timestamp = timestamp,
        counterparty = counterparty,
        note = note,
        sourcePackage = packageName,
        ruleName = "微信·支付",
        dedupKey = dedupKey,
        refundMark = RefundMark.NONE,
        paymentInstrument = paymentInstrument,
        userEdited = userEdited,
        flowId = flowId,
        notificationInstanceId = notificationInstanceId,
        bankInstanceId = bankInstanceId,
        status = status,
        createdAt = createdAt,
    )

    // ---------- pickRecentSameEntry ----------

    @Test
    fun `窗口内同来源同方向同额认出同一笔`() {
        val existing = listOf(pending(notificationInstanceId = "n:aaa"))
        val hit = pickRecentSameEntry(
            existing, wechat, TxType.EXPENSE, 1290, base + 20_000,
            identity = "n:aaa",
        )
        assertNotNull(hit)
        assertEquals(1L, hit!!.id)
    }

    @Test
    fun `另一条通知的同额交易不算同一笔`() {
        val existing = listOf(pending(notificationInstanceId = "n:aaa"))
        // 同分钟同额，但通知实例标识不同 = 用户真的付了两次 12.90
        assertNull(
            pickRecentSameEntry(
                existing, wechat, TxType.EXPENSE, 1290, base + 20_000,
                identity = "n:bbb",
            ),
        )
    }

    @Test
    fun `跨通道身份不同时窗口收紧到两条通道的实际间隔`() {
        // 既有条目是通知身份，本次是页面流程身份：跨通道重复正是要配对的情形，
        // 但两侧身份**无法核对**（`n:` vs `p:`），所以只给通道间的窄窗口。
        val existing = listOf(pending(notificationInstanceId = "n:aaa"))
        assertNotNull(
            "10s 内：用户返回结果页紧接支付通知",
            pickRecentSameEntry(
                existing, wechat, TxType.EXPENSE, 1290, base + 5_000,
                identity = "p:page-1",
            ),
        )
        assertNull(
            "超过窄窗口：交给页面侧的跨应用证据去认（那里的证据更强，不能靠同额硬并）",
            pickRecentSameEntry(
                existing, wechat, TxType.EXPENSE, 1290, base + 20_000,
                identity = "p:page-1",
            ),
        )
    }

    @Test
    fun `同一条通知的重复回调可以放宽到 60 秒`() {
        // 身份可核对且相同 = 同一条通知的重复投递/折叠更新，宽窗口安全
        val existing = listOf(pending(notificationInstanceId = "n:aaa"))
        assertNotNull(
            pickRecentSameEntry(
                existing, wechat, TxType.EXPENSE, 1290, base + 50_000,
                identity = "n:aaa",
            ),
        )
    }

    @Test
    fun `窗口外_金额不同_方向不同_来源不同都不算`() {
        val existing = listOf(pending(notificationInstanceId = "n:aaa"))

        assertNull(
            "窗口外（>60s）",
            pickRecentSameEntry(
                existing, wechat, TxType.EXPENSE, 1290, base + 60_001,
                identity = "n:aaa",
            ),
        )
        assertNull(
            "金额不同",
            pickRecentSameEntry(
                existing, wechat, TxType.EXPENSE, 1291, base + 1_000,
                identity = "n:aaa",
            ),
        )
        assertNull(
            "方向不同",
            pickRecentSameEntry(
                existing, wechat, TxType.INCOME, 1290, base + 1_000,
                identity = "n:aaa",
            ),
        )
        assertNull(
            "来源不同",
            pickRecentSameEntry(
                existing, NotificationRules.ALIPAY, TxType.EXPENSE, 1290, base + 1_000,
                identity = "n:aaa",
            ),
        )
    }

    @Test
    fun `页面账单详情页按原始交易时刻也能认出同一笔`() {
        // 用户付款两分钟后又翻出这笔的账单详情页：捕获时刻差得远，但交易时刻一致
        val existing = listOf(pending(timestamp = base, flowId = "p:page-1"))
        val hit = pickRecentSameEntry(
            listOf(pending(timestamp = base, flowId = "p:page-1")),
            wechat, TxType.EXPENSE, 1290, base + 120_000,
            txTime = base,
            identity = "p:page-1",
        )
        assertNotNull(hit)
        assertNotNull(existing)
    }

    @Test
    fun `多条命中取最近的一条`() {
        val far = pending(id = 1, createdAt = base - 50_000)
        val near = pending(id = 2, createdAt = base - 1_000)
        val hit = pickRecentSameEntry(
            listOf(far, near), wechat, TxType.EXPENSE, 1290, base,
            identity = "n:aaa",
        )
        assertEquals(2L, hit!!.id)
    }

    // ---------- mergeDuplicateCapture ----------

    @Test
    fun `既有条目已经有商户时不被重复捕获覆盖`() {
        val existing = pending(counterparty = "鸿润福连锁超市", note = "支付方式 零钱")
        val merged = mergeDuplicateCapture(existing, "20.06", "微信支付", "零钱")
        assertEquals("鸿润福连锁超市", merged.counterparty)
        assertFalse("金额样式的商户名不能补进来", merged.counterparty == "20.06")
    }

    @Test
    fun `既有条目商户为空时用本次的补上`() {
        val existing = pending(counterparty = "")
        val merged = mergeDuplicateCapture(existing, "鸿润福连锁超市", "微信支付", "零钱")
        assertEquals("鸿润福连锁超市", merged.counterparty)
        assertTrue(merged.changed)
    }

    @Test
    fun `用户改过的商户字段不被重复捕获改写`() {
        // 用户把商户名改成了"午饭"，重复捕获带回系统原文"鸿润福连锁超市"
        val existing = pending(counterparty = "午饭")
        val merged = mergeDuplicateCapture(
            existing, "鸿润福连锁超市", "微信支付", "零钱",
            guarded = setOf(GuardedField.COUNTERPARTY),
        )
        assertEquals("午饭", merged.counterparty)
    }

    @Test
    fun `用户改过的备注字段不被重复捕获改写`() {
        val existing = pending(note = "请客")
        val merged = mergeDuplicateCapture(
            existing, "", "微信支付", "",
            guarded = setOf(GuardedField.NOTE),
        )
        assertEquals("请客", merged.note)
    }

    @Test
    fun `没改过的备注按原逻辑追加`() {
        val existing = pending(note = "微信支付")
        val merged = mergeDuplicateCapture(existing, "", "银行 招商银行(1234)", "招商银行(1234)")
        assertTrue(merged.note.contains("微信支付"))
        assertTrue("银行线索应被追加", merged.note.contains("招商银行"))
        assertEquals("招商银行(1234)", merged.paymentInstrument)
        assertTrue(merged.changed)
    }

    @Test
    fun `升级前置过 userEdited 的旧条目整条锁定`() {
        val existing = pending(counterparty = "午饭", note = "请客", userEdited = true)
        val merged = mergeDuplicateCapture(existing, "鸿润福连锁超市", "微信支付", "零钱")
        assertEquals("午饭", merged.counterparty)
        assertEquals("请客", merged.note)
    }

    @Test
    fun `没有任何新增信息时 changed 为 false`() {
        val existing = pending(counterparty = "鸿润福连锁超市", note = "微信支付", paymentInstrument = "零钱")
        val merged = mergeDuplicateCapture(existing, "", "微信支付", "零钱")
        assertEquals("鸿润福连锁超市", merged.counterparty)
        assertEquals("微信支付", merged.note)
        assertEquals("零钱", merged.paymentInstrument)
        assertFalse(merged.changed)
    }

    @Test
    fun `金额与时间永不改动_合并结果里没有金额字段`() {
        // DuplicateMerge 只暴露商户/备注/支付工具：金额与时间由调用方沿用既有值，
        // 这条测试锁住"重复捕获不能改金额"的接口形态（编译期即可保证）。
        val existing = pending(amount = 1290, timestamp = base)
        val merged = mergeDuplicateCapture(existing, "某商户", "备注", "")
        assertEquals(
            "合并结果只有商户/备注/支付工具三个可写字段",
            listOf("counterparty", "note", "paymentInstrument", "changed"),
            DuplicateMerge::class.java.declaredFields
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name },
        )
        assertEquals("某商户", merged.counterparty)
    }
}
