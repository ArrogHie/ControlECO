package com.controleco.app.capture

import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 逻辑交易键 + 终态登记（2026-10-30「重复捕获不再弹第二个悬浮窗」的判定内核）。
 *
 * 覆盖的语义：
 * - 同一笔真实支付的多个通道去重键（`@n:` / `@p:` / `@b:`）归一成同一个逻辑交易键；
 * - 悬浮窗登记与字段锁按逻辑交易索引 → 第二个通道到达时"已经弹过/已经改过"照样生效；
 * - 迟到的重复捕获按终态登记认出"这笔已有结论"→ 只留溯源，不入队、不弹卡；
 * - **两笔真实同额交易不被吞掉**（身份不同 → 不配对）。
 */
class LogicalTransactionsTest {

    private val base = 1_734_000_000_000L

    private fun pending(
        id: Long = 1,
        packageName: String = NotificationRules.WECHAT,
        type: TxType = TxType.EXPENSE,
        amount: Long = 1290,
        timestamp: Long = base,
        createdAt: Long = base,
        status: PendingStatus = PendingStatus.PENDING,
        counterparty: String = "",
        note: String = "微信支付",
        ruleName: String = "微信·支付",
        userEdited: Boolean = false,
        flowId: String = "",
        notificationInstanceId: String = "",
        bankInstanceId: String = "",
        isRefund: Boolean = false,
        dedupKey: String = "$packageName|$type|$amount|${timestamp / 60_000}",
    ) = PendingTransactionEntity(
        id = id,
        type = type,
        amountCents = amount,
        timestamp = timestamp,
        counterparty = counterparty,
        note = note,
        sourcePackage = packageName,
        ruleName = ruleName,
        dedupKey = dedupKey,
        refundMark = if (isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
        userEdited = userEdited,
        flowId = flowId,
        notificationInstanceId = notificationInstanceId,
        bankInstanceId = bankInstanceId,
        status = status,
        createdAt = createdAt,
    )

    @Before
    fun setUp() {
        LogicalTransactions.clearAll()
        OverlayPromptLog.clearAll()
        OverlayFieldGuards.clearAll()
    }

    @After
    fun tearDown() {
        LogicalTransactions.clearAll()
        OverlayPromptLog.clearAll()
        OverlayFieldGuards.clearAll()
    }

    // ---------- 别名归一 ----------

    @Test
    fun `未登记时规范键就是自己`() {
        assertEquals("k1", LogicalTransactions.canonical("k1"))
        // 空键原样返回（调用方靠它做 no-op）
        assertEquals("", LogicalTransactions.canonical(""))
    }

    @Test
    fun `同一笔的第二个通道去重键归一到首个键`() {
        val notificationKey = "com.tencent.mm|EXPENSE|1290|28900@n:aaa"
        val pageKey = "com.tencent.mm|EXPENSE|1290|28900@p:bbb"
        LogicalTransactions.link(pageKey, notificationKey)
        assertEquals(notificationKey, LogicalTransactions.canonical(pageKey))
        assertEquals(notificationKey, LogicalTransactions.canonical(notificationKey))
        assertTrue(LogicalTransactions.sameLogical(pageKey, notificationKey))
    }

    @Test
    fun `别名链只有一层_指向已是别名的键也会收敛`() {
        val first = "k|n:1"
        val second = "k|p:2"
        val third = "k|b:3"
        LogicalTransactions.link(second, first)
        // third 指向 second，而 second 已经是 first 的别名 → 应直接挂到 first
        LogicalTransactions.link(third, second)
        assertEquals(first, LogicalTransactions.canonical(second))
        assertEquals(first, LogicalTransactions.canonical(third))
        assertEquals(2, LogicalTransactions.aliasCount())
    }

    @Test
    fun `不同逻辑交易不互相归一`() {
        val a = "com.tencent.mm|EXPENSE|1290|28900@n:aaa"
        val b = "com.tencent.mm|EXPENSE|1290|28900@n:bbb"
        LogicalTransactions.link(a, a)
        assertFalse(LogicalTransactions.sameLogical(a, b))
        assertEquals(b, LogicalTransactions.canonical(b))
    }

    @Test
    fun `自环与空键不登记`() {
        LogicalTransactions.link("k1", "k1")
        LogicalTransactions.link("", "k1")
        LogicalTransactions.link("k1", "")
        assertEquals(0, LogicalTransactions.aliasCount())
    }

    // ---------- 悬浮窗登记按逻辑交易生效 ----------

    @Test
    fun `页面通道弹过卡之后_银行通道不再视为未弹过`() {
        val pageKey = "com.tencent.mm|EXPENSE|1290|28900@p:page-1"
        val bankKey = "com.tencent.mm|EXPENSE|1290|28900@b:bank-1"
        OverlayPromptLog.markShown(pageKey)
        // 别名还没建立：此时银行键确实"没弹过"
        assertFalse(OverlayPromptLog.wasShown(bankKey))

        LogicalTransactions.link(bankKey, pageKey)
        // 别名建立后：同一笔已经打扰过，银行通道不许再弹第二张卡
        assertTrue(OverlayPromptLog.wasShown(bankKey))
    }

    @Test
    fun `清理一个通道键会连带清掉同一逻辑交易的登记`() {
        val a = "k|n:1"
        val b = "k|p:2"
        LogicalTransactions.link(b, a)
        OverlayPromptLog.markShown(a)
        OverlayPromptLog.clear(b)
        assertFalse(OverlayPromptLog.wasShown(a))
        assertFalse(OverlayPromptLog.wasShown(b))
        assertEquals(0, OverlayPromptLog.size())
    }

    // ---------- 字段锁按逻辑交易生效 ----------

    @Test
    fun `用户在前一个通道改过的字段_后续通道查得到`() {
        val pageKey = "com.tencent.mm|EXPENSE|1290|28900@p:page-1"
        val bankKey = "com.tencent.mm|EXPENSE|1290|28900@b:bank-1"
        OverlayFieldGuards.mark(pageKey, setOf(GuardedField.COUNTERPARTY, GuardedField.NOTE))
        LogicalTransactions.link(bankKey, pageKey)

        assertEquals(
            setOf(GuardedField.COUNTERPARTY, GuardedField.NOTE),
            OverlayFieldGuards.fieldsOf(bankKey),
        )
        assertTrue(OverlayFieldGuards.isGuarded(bankKey, GuardedField.NOTE))
        assertFalse(OverlayFieldGuards.isGuarded(bankKey, GuardedField.AMOUNT))
    }

    @Test
    fun `清理同一逻辑交易的字段锁`() {
        val a = "k|n:1"
        val b = "k|p:2"
        LogicalTransactions.link(b, a)
        OverlayFieldGuards.mark(a, setOf(GuardedField.AMOUNT))
        OverlayFieldGuards.clear(b)
        assertTrue(OverlayFieldGuards.fieldsOf(a).isEmpty())
        assertTrue(OverlayFieldGuards.fieldsOf(b).isEmpty())
    }

    // ---------- 终态登记 ----------

    @Test
    fun `忽略之后迟到捕获认出这笔已有结论`() {
        val key = "com.tencent.mm|EXPENSE|1290|28900@n:aaa"
        val item = pending(dedupKey = key, notificationInstanceId = "n:aaa")
        LogicalTransactions.markResolved(item, CaptureResolution.REJECTED, now = base)

        val hit = LogicalTransactions.findResolved(
            sourcePackage = NotificationRules.WECHAT,
            type = TxType.EXPENSE,
            amountCents = 1290,
            isRefund = false,
            identity = "n:aaa",
            eventTime = base + 20_000,
            now = base + 20_000,
        )
        assertNotNull(hit)
        assertEquals(CaptureResolution.REJECTED, hit!!.resolution)
        assertEquals(key, hit.dedupKey)
    }

    @Test
    fun `无身份的迟到捕获不能宽窗口误吞同额交易`() {
        // 升级前的旧规则没有身份后缀：宽窗口 + 无身份 = 把 10 分钟内的另一笔同额真实交易吞掉。
        // 因此无身份时窗口收紧到两条通道的间隔量级。
        LogicalTransactions.markResolved(
            pending(dedupKey = "com.tencent.mm|EXPENSE|1290|28900"),
            CaptureResolution.REJECTED,
            now = base,
        )
        assertNotNull(
            "同一时间点：仍是这笔的重复",
            LogicalTransactions.findResolved(
                NotificationRules.WECHAT, TxType.EXPENSE, 1290, false, "", base + 3_000,
                now = base + 3_000,
            ),
        )
        assertNull(
            "5 分钟后：可能是另一笔同额真实交易，不能被吞",
            LogicalTransactions.findResolved(
                NotificationRules.WECHAT, TxType.EXPENSE, 1290, false, "", base + 300_000,
                now = base + 300_000,
            ),
        )
    }

    @Test
    fun `同来源另一笔同额真实交易不被终态登记吞掉`() {
        val key = "com.tencent.mm|EXPENSE|1290|28900@n:aaa"
        LogicalTransactions.markResolved(
            pending(dedupKey = key, notificationInstanceId = "n:aaa"),
            CaptureResolution.HANDLED,
            now = base,
        )
        // 另一条通知（不同实例标识）在同分钟内也是 12.90：这是真实的第二笔
        assertNull(
            LogicalTransactions.findResolved(
                sourcePackage = NotificationRules.WECHAT,
                type = TxType.EXPENSE,
                amountCents = 1290,
                isRefund = false,
                identity = "n:bbb",
                eventTime = base + 20_000,
                now = base + 20_000,
            ),
        )
        // 没有身份的旧条目（无后缀）在窄窗口内仍允许配对，避免升级后重复弹卡
        assertNotNull(
            LogicalTransactions.findResolved(
                sourcePackage = NotificationRules.WECHAT,
                type = TxType.EXPENSE,
                amountCents = 1290,
                isRefund = false,
                identity = "",
                eventTime = base + 5_000,
                now = base + 5_000,
            ),
        )
    }

    @Test
    fun `金额_方向_退款标记或来源不同都不算同一笔`() {
        val key = "com.tencent.mm|EXPENSE|1290|28900@n:aaa"
        LogicalTransactions.markResolved(pending(dedupKey = key), CaptureResolution.HANDLED, now = base)

        fun find(
            source: String = NotificationRules.WECHAT,
            type: TxType = TxType.EXPENSE,
            amount: Long = 1290,
            refund: Boolean = false,
            eventTime: Long = base,
        ) = LogicalTransactions.findResolved(
            source, type, amount, refund, "n:aaa", eventTime, now = base,
        )

        assertNull("金额不同", find(amount = 1291))
        assertNull("方向不同", find(type = TxType.INCOME))
        assertNull("退款标记不同", find(refund = true))
        assertNull("来源不同", find(source = NotificationRules.ALIPAY))
        assertNotNull("完全一致", find())
    }

    @Test
    fun `终态窗口之外的迟到捕获不算同一笔`() {
        val key = "com.tencent.mm|EXPENSE|1290|28900@n:aaa"
        LogicalTransactions.markResolved(
            pending(dedupKey = key),
            CaptureResolution.HANDLED,
            now = base,
        )
        assertNull(
            LogicalTransactions.findResolved(
                sourcePackage = NotificationRules.WECHAT,
                type = TxType.EXPENSE,
                amountCents = 1290,
                isRefund = false,
                identity = "n:aaa",
                eventTime = base + LogicalTransactions.FINISHED_MEMORY_MS + 1,
                now = base + LogicalTransactions.FINISHED_MEMORY_MS + 1,
            ),
        )
    }

    @Test
    fun `忘记一笔会清掉它的别名与终态`() {
        val canonicalKey = "com.tencent.mm|EXPENSE|1290|28900@n:aaa"
        val aliasKey = "com.tencent.mm|EXPENSE|1290|28900@p:bbb"
        LogicalTransactions.link(aliasKey, canonicalKey)
        LogicalTransactions.markResolved(
            pending(dedupKey = canonicalKey),
            CaptureResolution.HANDLED,
            now = base,
        )

        LogicalTransactions.forget(canonicalKey)

        assertEquals(0, LogicalTransactions.aliasCount())
        assertNull(
            LogicalTransactions.findResolved(
                sourcePackage = NotificationRules.WECHAT,
                type = TxType.EXPENSE,
                amountCents = 1290,
                isRefund = false,
                identity = "n:aaa",
                eventTime = base,
                now = base,
            ),
        )
    }

    // ---------- matchesFinishedCapture 纯函数 ----------

    @Test
    fun `时间差在窗口内才算同一笔`() {
        val finished = FinishedCapture(
            sourcePackage = NotificationRules.WECHAT,
            type = TxType.EXPENSE,
            amountCents = 1290,
            isRefund = false,
            eventTime = base,
            identity = "n:aaa",
            dedupKey = "com.tencent.mm|EXPENSE|1290|28900@n:aaa",
            resolution = CaptureResolution.HANDLED,
            at = base,
        )
        assertTrue(
            matchesFinishedCapture(
                finished, NotificationRules.WECHAT, TxType.EXPENSE, 1290, false, "n:aaa",
                base + 5_000, 10_000,
            ),
        )
        assertFalse(
            matchesFinishedCapture(
                finished, NotificationRules.WECHAT, TxType.EXPENSE, 1290, false, "n:aaa",
                base + 10_001, 10_000,
            ),
        )
    }
}
