package com.controleco.app.capture

import com.controleco.app.capture.page.ParseConfidence
import com.controleco.app.capture.page.PageCandidate
import com.controleco.app.capture.page.PageScene
import com.controleco.app.capture.page.TradeState
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨通道合并：10s 窗口内同包名 + 同方向视为同一笔；页面信息优先（覆盖通知侧的稀疏值与金额）。
 */
class CaptureMergeTest {

    private val base = 1_000_000L

    private fun pending(
        id: Long = 1,
        packageName: String = NotificationRules.WECHAT,
        type: TxType = TxType.EXPENSE,
        amount: Long = 1290,
        createdAt: Long = base,
        status: PendingStatus = PendingStatus.PENDING,
        counterparty: String = "",
        note: String = "微信支付",
        ruleName: String = "微信·支付",
        timestamp: Long = base,
        paymentInstrument: String = "",
        userEdited: Boolean = false,
        flowId: String = "",
        notificationInstanceId: String = "",
        bankInstanceId: String = "",
        orderId: String = "",
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
        paymentInstrument = paymentInstrument,
        userEdited = userEdited,
        flowId = flowId,
        notificationInstanceId = notificationInstanceId,
        bankInstanceId = bankInstanceId,
        orderId = orderId,
        status = status,
        createdAt = createdAt,
    )

    // ---------- 同笔判定 ----------

    @Test
    fun `ASK红包待确认不参与自动融合`() {
        // 红包领取页的用户确认条目（ASK）必须由用户决定，不能被同额转账/收款自动认领。
        val ask = pending(
            status = PendingStatus.ASK,
            type = TxType.INCOME,
            amount = 520,
            ruleName = "微信·红包待确认",
            flowId = "r:prompt",
        )
        assertNull(
            pickRelatedPending(
                listOf(ask), NotificationRules.WECHAT, TxType.INCOME, 520, base + 1_000,
            ),
        )
        assertNull(
            pickRelatedPendingForPage(
                listOf(ask), NotificationRules.WECHAT, TxType.INCOME, 520, base + 1_000, "p:transfer",
            ),
        )
    }

    @Test
    fun `同包同向同金额在十秒内命中`() {
        val hit = pickRelatedPending(listOf(pending()), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 3_000)!!
        assertEquals(1L, hit.pending.id)
        assertTrue(hit.sameAmount)
    }

    @Test
    fun `超过十秒不算同一笔`() {
        assertNull(pickRelatedPending(listOf(pending()), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 10_001))
        // 边界：正好 10s 仍算同笔
        assertTrue(
            pickRelatedPending(listOf(pending()), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 10_000) != null,
        )
    }

    @Test
    fun `金额不同时_页面侧也不自动合并_候选唯一不是同一笔的证据`() {
        // 先捕获 ¥1.00、随后捕获 ¥12.90：可能是通知误解析，也可能是两笔不同金额的付款。
        // 仅有"候选唯一"不能证明同笔——保留独立记录，交由用户确认（CR-003）。
        assertNull(
            pickRelatedPendingForPage(
                listOf(pending(amount = 100)), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 1_000,
            ),
        )
    }

    @Test
    fun `金额不同时_通知侧同样不认作同笔`() {
        // 通知侧：严格比金额
        assertNull(pickRelatedPending(listOf(pending(amount = 100)), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 1_000))
    }

    @Test
    fun `金额不同且候选不止一条时不算同笔`() {
        val pool = listOf(pending(id = 1, amount = 100), pending(id = 2, amount = 200))
        assertNull(
            pickRelatedPendingForPage(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 1_000),
        )
    }

    // ---------- 金额覆盖安全闸门（CR-003：金额不同一律独立成条） ----------

    @Test
    fun `实测回归_真实收款5_20不被聊天页7_50覆盖`() {
        // 实测根因：真实收款页先入账 ¥5.20，聊天页历史气泡解析出 ¥7.50 且窗口内候选唯一；
        // "候选唯一"不能证明同一笔，金额不同必须独立成条，不能覆盖既有 5.20。
        val pool = listOf(pending(type = TxType.INCOME, amount = 520))
        assertNull(
            pickRelatedPendingForPage(
                pool, NotificationRules.WECHAT, TxType.INCOME, 750, base + 3_000,
            ),
        )
    }

    @Test
    fun `即使页面候选金额已锚定_不同金额也不自动合并`() {
        // amountAnchored 只表示页面金额来自锚点、不是全页扫描，不能证明与既有 5.20 是同一笔；
        // 融合层不接收该标记，窗口内唯一候选同样不合并（CR-003）。
        val anchored = PageCandidate(
            type = TxType.INCOME,
            amountCents = 750,
            amountAnchored = true,
            scene = PageScene.RECEIVE_SUCCESS,
            tradeState = TradeState.SUCCESS,
            confidence = ParseConfidence.HIGH,
            sourceLabel = "微信",
            ruleName = "微信·收款到账页",
            dedupKey = "com.tencent.mm|INCOME|750|0",
        )
        assertTrue(anchored.amountAnchored)
        val pool = listOf(pending(type = TxType.INCOME, amount = 520))
        assertNull(
            pickRelatedPendingForPage(
                pool, NotificationRules.WECHAT, anchored.type, anchored.amountCents, base + 3_000,
            ),
        )
    }

    // ---------- 页面交易时间回并（重新打开同一收款页/进程重启） ----------

    @Test
    fun `页面交易时间相同_数分钟后重新打开不再插pending`() {
        val existing = pending(
            type = TxType.INCOME,
            amount = 520,
            createdAt = base,
            timestamp = base + 500_000L,
            ruleName = "微信·收款到账页",
            flowId = "p:old",
        )
        val hit = pickRelatedPendingForPage(
            listOf(existing), NotificationRules.WECHAT, TxType.INCOME, 520,
            captureAt = base + 900_000L,
            flowIdentity = "p:new",
            txTime = base + 500_000L,
        )!!
        assertEquals(1L, hit.pending.id)
        assertTrue(hit.sameAmount)
    }

    @Test
    fun `页面交易时间相差超过六十秒_不合并`() {
        val existing = pending(
            type = TxType.INCOME,
            amount = 520,
            createdAt = base,
            timestamp = base + 500_000L,
            ruleName = "微信·收款到账页",
            flowId = "p:old",
        )
        assertNull(
            pickRelatedPendingForPage(
                listOf(existing), NotificationRules.WECHAT, TxType.INCOME, 520,
                captureAt = base + 900_000L,
                flowIdentity = "p:new",
                txTime = base + 700_001L,
            ),
        )
    }

    @Test
    fun `包名方向或退款窗口不同不算同笔`() {
        val pool = listOf(pending())
        assertNull(pickRelatedPending(pool, NotificationRules.ALIPAY, TxType.EXPENSE, 1290, base + 1_000))
        assertNull(pickRelatedPending(pool, NotificationRules.WECHAT, TxType.INCOME, 1290, base + 1_000))
    }

    @Test
    fun `同金额多条取捕获时刻最近的一条`() {
        val pool = listOf(pending(id = 1, createdAt = base), pending(id = 2, createdAt = base + 8_000))
        val hit = pickRelatedPending(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 9_000)!!
        assertEquals(2L, hit.pending.id)
    }

    @Test
    fun `自动入账条目同样参与合并`() {
        val pool = listOf(pending(status = PendingStatus.AUTO))
        val hit = pickRelatedPending(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 500)!!
        assertEquals(PendingStatus.AUTO, hit.pending.status)
    }

    // ---------- 页面重捕窗口（只对美团外卖：弹窗干扰 / 页面重渲染） ----------

    @Test
    fun `美团外卖页面重捕_超过十秒但三分钟内同金额仍算同一笔`() {
        // 真实事故（2026-10-16 美团外卖）：支付成功后弹窗干扰、页面重渲染，同一笔 ¥18.00
        // 在 14:08 / 14:09 各捕获一条快照——10s 窗口拦不住，页面重捕窗口必须命中
        val pool = listOf(pending(packageName = NotificationRules.MEITUAN_TAKEOUT, amount = 1800))
        val hit = pickRelatedPendingForPage(
            pool, NotificationRules.MEITUAN_TAKEOUT, TxType.EXPENSE, 1800, base + 90_000,
        )!!
        assertEquals(1L, hit.pending.id)
        assertTrue(hit.sameAmount)
    }

    @Test
    fun `页面重捕窗口严格比金额_不同金额不并成一笔`() {
        // 3 分钟内两笔金额不同的付款（如先买 ¥18 再买 ¥25）绝不能被并成一条（丢记录）
        val pool = listOf(pending(packageName = NotificationRules.MEITUAN_TAKEOUT, amount = 1800))
        assertNull(
            pickRelatedPendingForPage(pool, NotificationRules.MEITUAN_TAKEOUT, TxType.EXPENSE, 2500, base + 90_000),
        )
    }

    @Test
    fun `页面重捕_十秒内金额不一致也不合并`() {
        // 金额不同绝不能因为时间近、候选唯一就并成一笔（页面来源优先不改变"是否同一笔"的判定）
        val pool = listOf(pending(packageName = NotificationRules.MEITUAN_TAKEOUT, amount = 100))
        assertNull(
            pickRelatedPendingForPage(
                pool, NotificationRules.MEITUAN_TAKEOUT, TxType.EXPENSE, 1800, base + 3_000,
            ),
        )
    }

    @Test
    fun `页面重捕窗口之外不算同一笔`() {
        val pool = listOf(pending(packageName = NotificationRules.MEITUAN_TAKEOUT, amount = 1800))
        assertNull(
            pickRelatedPendingForPage(pool, NotificationRules.MEITUAN_TAKEOUT, TxType.EXPENSE, 1800, base + 180_001),
        )
        // 边界：正好 3 分钟仍算同一笔
        assertTrue(
            pickRelatedPendingForPage(pool, NotificationRules.MEITUAN_TAKEOUT, TxType.EXPENSE, 1800, base + 180_000) != null,
        )
    }

    @Test
    fun `其他来源不享受页面重捕窗口_判重口径保持原样`() {
        // 本轮改动只针对美团外卖：微信/支付宝的页面捕获仍只走 10s 跨通道窗口（90s 后的同额页面不算同一笔）
        val pool = listOf(pending(packageName = NotificationRules.WECHAT, amount = 1290))
        assertNull(
            pickRelatedPendingForPage(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 90_000),
        )
        // 10s 内照旧命中
        assertTrue(
            pickRelatedPendingForPage(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 3_000) != null,
        )
    }

    // ---------- 页面优先双向融合（页面 ↔ 通知） ----------

    @Test
    fun `通知先到页面后到_页面商户金额支付工具整体覆盖通知侧`() {
        // 通知先捕获同一笔（商户空、note 是通知原文）→ 页面后捕获：页面信息最全，整体以页面为准
        val existing = pending(
            amount = 100,
            counterparty = "",
            note = "已支付¥1.00",
            ruleName = "微信·支出/消费",
            timestamp = base,
            createdAt = base,
            paymentInstrument = "",
        )
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = base + 4_000,
            pageCounterparty = "肯德基",
            pageNote = "肯德基",
            pagePaymentMethod = "零钱",
            pageRuleName = "微信·支付成功页",
            pageInstrument = "零钱",
        )
        assertEquals("肯德基", merged.counterparty)
        assertEquals(1290L, merged.amountCents) // 通知误解析的 ¥1.00 被页面金额覆盖
        assertEquals(base + 4_000, merged.timestamp)
        assertEquals("肯德基 · 支付方式 零钱", merged.note)
        assertTrue(merged.note.contains("支付方式 零钱"))
        assertEquals("微信·支付成功页", merged.ruleName)
        assertEquals("零钱", merged.paymentInstrument)
    }

    @Test
    fun `页面先到通知后到_五分钟内用通知发布时间校正页面的兜底时间`() {
        // 页面把捕获时刻当交易时间（timestamp == createdAt）→ 通知发布时间更接近真实交易时刻
        val existing = pending(timestamp = base, createdAt = base)
        assertEquals(base + 30_000, mergeNotificationInfo(existing, base + 30_000)!!)
        // 边界：正好 5 分钟仍采纳
        assertEquals(base + 300_000, mergeNotificationInfo(existing, base + 300_000)!!)
        // 超过 5 分钟不采纳
        assertNull(mergeNotificationInfo(existing, base + 300_001))
        // 与既有时间相同 = 不需要改动；没有发布时间（<= 0）也不动
        assertNull(mergeNotificationInfo(existing, base))
        assertNull(mergeNotificationInfo(existing, 0))
    }

    @Test
    fun `页面已给出明确交易时间时_通知时间不改写`() {
        // timestamp 与 createdAt 相差超过 60s = 页面已解析出真实交易时间，不要用通知时间覆盖
        val explicit = pending(timestamp = base - 120_000, createdAt = base)
        assertNull(mergeNotificationInfo(explicit, base - 119_000))
        // 边界：正好 60s 仍视为"页面用了捕获时刻"，窗口内采纳通知时间
        val edge = pending(timestamp = base - 60_000, createdAt = base)
        assertEquals(base - 30_000, mergeNotificationInfo(edge, base - 30_000)!!)
    }

    @Test
    fun `用户手改的条目_通知时间不改写`() {
        val edited = pending(timestamp = base, createdAt = base, userEdited = true)
        assertNull(mergeNotificationInfo(edited, base + 30_000))
    }

    @Test
    fun `用户手改保护_金额商户时间备注都保持既有值_只补空白支付工具`() {
        val existing = pending(
            amount = 1290,
            counterparty = "肯德基",
            note = "用户备注",
            ruleName = "微信·支付",
            timestamp = base,
            createdAt = base,
            userEdited = true,
        )
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 2006,
            pageTxTime = base + 9_000,
            pageCounterparty = "鸿润福连锁超市",
            pageNote = "页面备注",
            pagePaymentMethod = "零钱",
            pageRuleName = "支付宝·支付页",
            pageInstrument = "零钱",
        )
        // 用户输入优先：金额/商户/时间/备注/规则名保持既有值
        assertEquals(1290L, merged.amountCents)
        assertEquals("肯德基", merged.counterparty)
        assertEquals(base, merged.timestamp)
        assertEquals("用户备注", merged.note)
        assertEquals("微信·支付", merged.ruleName)
        // 只补空白：支付工具
        assertEquals("零钱", merged.paymentInstrument)
    }

    @Test
    fun `用户手改的条目_已有支付工具不被页面覆盖`() {
        val existing = pending(paymentInstrument = "招商银行(1234)", userEdited = true)
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = base + 9_000,
            pageCounterparty = "肯德基",
            pageNote = "",
            pagePaymentMethod = "零钱",
            pageRuleName = "微信·支付成功页",
            pageInstrument = "零钱",
        )
        assertEquals("招商银行(1234)", merged.paymentInstrument)
    }

    @Test
    fun `字段级保护_只改了商户时_备注和规则名仍按页面富化`() {
        // 用户在悬浮卡里只改了商户（2026-10-27 口径：没动过的字段照旧自动富化）
        val existing = pending(
            amount = 1290,
            counterparty = "我改的店名",
            note = "支付方式 零钱",
            ruleName = "微信·支付成功页",
            timestamp = base,
            createdAt = base,
        )
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = base + 5_000,
            pageCounterparty = "淘宝·某某旗舰店",
            pageNote = "支付方式 微信支付",
            pagePaymentMethod = "微信支付",
            pageRuleName = "淘宝·支付结果页",
            pageInstrument = "微信支付",
            guarded = setOf(GuardedField.COUNTERPARTY),
        )
        assertEquals("我改的店名", merged.counterparty) // 用户改过 → 保留
        assertEquals("支付方式 微信支付", merged.note) // 没改过 → 页面覆盖
        assertEquals("淘宝·支付结果页", merged.ruleName)
        assertEquals(base + 5_000, merged.timestamp)
    }

    @Test
    fun `字段级保护_只改了备注时_商户仍按页面富化`() {
        val existing = pending(
            amount = 1290,
            counterparty = "美团",
            note = "我写的备注",
            timestamp = base,
            createdAt = base,
        )
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = base + 5_000,
            pageCounterparty = "肯德基",
            pageNote = "支付方式 零钱",
            pagePaymentMethod = "零钱",
            pageRuleName = "淘宝·支付结果页",
            pageInstrument = "零钱",
            guarded = setOf(GuardedField.NOTE),
        )
        assertEquals("我写的备注", merged.note) // 用户改过 → 保留
        assertEquals("肯德基", merged.counterparty) // 没改过 → 页面覆盖
    }

    @Test
    fun `字段级保护_金额被用户改过时_保留用户金额`() {
        val existing = pending(amount = 1180, counterparty = "", note = "支付方式 零钱")
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = null,
            pageCounterparty = "肯德基",
            pageNote = "",
            pagePaymentMethod = "零钱",
            pageRuleName = "支付宝·付款成功页",
            pageInstrument = "零钱",
            guarded = setOf(GuardedField.AMOUNT),
        )
        assertEquals(1180L, merged.amountCents)
        assertEquals("肯德基", merged.counterparty)
    }

    @Test
    fun `没有登记受保护字段时_全部按页面优先富化`() {
        val existing = pending(amount = 1290, counterparty = "美团", note = "支付方式 零钱")
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = base + 5_000,
            pageCounterparty = "肯德基",
            pageNote = "支付方式 微信支付",
            pagePaymentMethod = "微信支付",
            pageRuleName = "淘宝·支付结果页",
            pageInstrument = "微信支付",
            guarded = emptySet(),
        )
        assertEquals("肯德基", merged.counterparty)
        assertEquals("支付方式 微信支付", merged.note)
    }

    @Test
    fun `页面备注为空且既有备注也为空时_不回写空串`() {
        val existing = pending(note = "")
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = null,
            pageCounterparty = "肯德基",
            pageNote = "",
            pagePaymentMethod = null,
            pageRuleName = "微信·支付成功页",
            pageInstrument = "",
        )
        assertEquals("", merged.note)
    }


    @Test
    fun `既有商户为空时_纯金额的页面商户同样不写入`() {
        val merged = mergePageInfo(
            existing = pending(counterparty = ""),
            pageAmountCents = 2006,
            pageTxTime = null,
            pageCounterparty = "¥20.06",
            pageNote = "",
            pagePaymentMethod = null,
            pageRuleName = "支付宝·支付页",
        )
        assertEquals("", merged.counterparty)
        assertEquals(2006L, merged.amountCents)
    }

    // ---------- 同额两笔 vs 同一笔（关键正确性） ----------

    @Test
    fun `页面流程标识命中同流程既有条目_不受十秒窗口限制`() {
        // 同一支付流程内的后续页面（支付结果页之后再打开账单详情）可能超过 10s：
        // 按流程标识直接并回同一条——这层与包名无关（页面重捕长窗口那层只对美团外卖生效）
        val pool = listOf(
            pending(
                amount = 1290,
                timestamp = base,
                createdAt = base,
                dedupKey = "com.tencent.mm|EXPENSE|1290|${base / 60_000}@f1",
            ),
        )
        val captureAt = base + 400_000
        // 同流程：命中
        val hit = pickRelatedPendingForPage(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, captureAt, "f1")!!
        assertEquals(1L, hit.pending.id)
        assertTrue(hit.sameAmount)
        // 不带流程标识时同一次调用不命中（4 分钟后已超出 10s 窗口，也非页面重捕来源）
        assertNull(pickRelatedPendingForPage(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, captureAt))
    }

    @Test
    fun `不同流程的既有条目_超出十秒窗口后各自成条`() {
        // 同额两笔独立交易（既有条目属于流程 @f1、本条候选属于 @f2）：超出跨通道窗口即互不相干
        val pool = listOf(
            pending(
                amount = 1290,
                timestamp = base,
                createdAt = base,
                dedupKey = "com.tencent.mm|EXPENSE|1290|${base / 60_000}@f1",
            ),
        )
        assertNull(
            pickRelatedPendingForPage(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 90_000, "f2"),
        )
    }

    @Test
    fun `同额两笔独立交易_流程标识不同时十秒窗口内也不合并`() {
        // 同额的**两笔独立交易**按流程标识隔离：既有条目属于流程 f1、本条候选属于 f2，
        // 即使金额相同、捕获时刻只差 3 秒，也绝不并成一条（第二笔不能丢记录）。
        val pool = listOf(
            pending(
                amount = 1290,
                timestamp = base,
                createdAt = base,
                dedupKey = "com.tencent.mm|EXPENSE|1290|${base / 60_000}@f1",
            ),
        )
        assertNull(
            pickRelatedPendingForPage(
                pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 3_000, "f2",
            ),
        )
        // 同一流程（f1）的后续页面仍应命中既有条目
        val sameFlow = pickRelatedPendingForPage(
            pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 3_000, "f1",
        )!!
        assertEquals(1L, sameFlow.pending.id)
    }

    @Test
    fun `流程身份持久化在独立列_旧键无后缀也能识别同流程`() {
        // CR-002：第一笔页面交易的持久化键可能没有后缀，但 flowId 列有身份。
        // 同一流程的详情页（可能超出 10s）必须凭该列命中，不能因键形态而重开一笔。
        val existing = pending(
            amount = 1290,
            timestamp = base,
            createdAt = base,
            dedupKey = "com.tencent.mm|EXPENSE|1290|${base / 60_000}",
            flowId = "p:flow-1",
        )
        val hit = pickRelatedPendingForPage(
            listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 600_000, "p:flow-1",
        )!!
        assertEquals(1L, hit.pending.id)
        // 另一流程不能凭 10s 同额窗口并进来
        assertNull(
            pickRelatedPendingForPage(
                listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 3_000, "p:flow-2",
            ),
        )
    }

    @Test
    fun `不同通知实例_同分钟同额也不合并`() {
        val existing = pending(
            amount = 1280,
            createdAt = base,
            notificationInstanceId = "n:first",
        )
        assertNull(
            pickRelatedPending(
                listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1280,
                base + 3_000, flowIdentity = "n:second",
            ),
        )
        // 同一条通知的重复回调仍命中
        assertTrue(
            pickRelatedPending(
                listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1280,
                base + 3_000, flowIdentity = "n:first",
            ) != null,
        )
    }

    @Test
    fun `订单号相同_忽略时间窗口认作同一笔`() {
        val existing = pending(
            amount = 1290,
            timestamp = base,
            createdAt = base,
            orderId = "202610170001",
        )
        val hit = pickRelatedPendingForPage(
            listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1290,
            base + 24 * 3_600_000, "p:another-flow", orderId = "202610170001",
        )!!
        assertEquals(1L, hit.pending.id)
        // 订单号不同 → 不凭金额/时间合并
        assertNull(
            pickRelatedPendingForPage(
                listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1290,
                base + 24 * 3_600_000, "p:another-flow", orderId = "202610170002",
            ),
        )
    }

    @Test
    fun `金额冲突只用于诊断_仍返回冲突条目但不参与合并`() {
        val pool = listOf(pending(amount = 100))
        val conflict = nearbyAmountConflict(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 2_000)
        assertEquals(100L, conflict?.amountCents)
        assertNull(pickRelatedPendingForPage(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 2_000))
    }

    @Test
    fun `流程身份匹配不依赖来源包名_跨应用合并后的后续页面仍能并回`() {
        // 页面先到、支付宝通知后到：条目保留了通知来源包名，但 flowId 仍是页面流程 p1。
        // 同一流程的详情页（美团包名）必须凭 flowId 命中，不能再记一笔。
        val existing = pending(
            packageName = NotificationRules.ALIPAY,
            amount = 1800,
            flowId = "p:meituan-flow-1",
            note = "支付方式 支付宝",
        )
        val hit = pickRelatedPendingForPage(
            listOf(existing), NotificationRules.MEITUAN_TAKEOUT, TxType.EXPENSE, 1800,
            base + 5 * 60_000, "p:meituan-flow-1",
        )!!
        assertEquals(1L, hit.pending.id)
    }

    @Test
    fun `明确交易时间几乎一致_进程重启后仍认回同一笔`() {
        // 进程重启后 flowId 变了，但详情页给出的交易时间与原条目几乎相同
        val existing = pending(
            amount = 1290,
            timestamp = base,
            createdAt = base + 120_000,
            flowId = "p:old-flow",
        )
        val hit = pickByExplicitTime(
            listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 5_000, false,
        )!!
        assertEquals(1L, hit.pending.id)
        // 时间差超过 30 秒：不因同额误并（可能是另一笔）
        assertNull(
            pickByExplicitTime(
                listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1290,
                base + EXPLICIT_TIME_MATCH_WINDOW_MS + 1, false,
            ),
        )
        // 金额/方向/退款标记不同同样不认
        assertNull(pickByExplicitTime(listOf(existing), NotificationRules.WECHAT, TxType.EXPENSE, 1291, base, false))
        assertNull(pickByExplicitTime(listOf(existing), NotificationRules.WECHAT, TxType.INCOME, 1290, base, false))
    }

    @Test
    fun `通知侧仍然严格比金额_窗口内两笔不同金额不命中`() {
        // 同来源、10s 窗口内的两笔不同金额：不允许金额错配（否则两笔会被并成一笔）
        val pool = listOf(pending(id = 1, amount = 100), pending(id = 2, amount = 200))
        assertNull(pickRelatedPending(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 3_000))
    }

    // ---------- 页面优先合并 ----------

    @Test
    fun `页面信息覆盖通知侧的稀疏值`() {
        val existing = pending(counterparty = "", note = "微信支付", ruleName = "微信·支付", timestamp = base)
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = base + 2_000,
            pageCounterparty = "肯德基",
            pageNote = "肯德基",
            pagePaymentMethod = "零钱",
            pageRuleName = "微信·支付成功页",
        )
        assertEquals("肯德基", merged.counterparty)
        assertEquals("肯德基 · 支付方式 零钱", merged.note)
        assertEquals("微信·支付成功页", merged.ruleName)
        assertEquals(base + 2_000, merged.timestamp)
        assertEquals(1290L, merged.amountCents)
    }

    @Test
    fun `页面金额覆盖通知误解析的金额`() {
        val merged = mergePageInfo(
            existing = pending(amount = 100),
            pageAmountCents = 1290,
            pageTxTime = null,
            pageCounterparty = "",
            pageNote = "",
            pagePaymentMethod = null,
            pageRuleName = "",
        )
        assertEquals(1290L, merged.amountCents)
        // 页面没有的保留原值
        assertEquals("微信支付", merged.note)
        assertEquals("微信·支付", merged.ruleName)
        assertEquals(base, merged.timestamp)
    }

    @Test
    fun `备注里已有的支付方式不重复追加`() {
        val merged = mergePageInfo(
            existing = pending(),
            pageAmountCents = 1290,
            pageTxTime = null,
            pageCounterparty = "肯德基",
            pageNote = "扫码付款（零钱）",
            pagePaymentMethod = "零钱",
            pageRuleName = "微信·支付成功页",
        )
        assertEquals("扫码付款（零钱）", merged.note)
    }

    @Test
    fun `纯金额的页面商户不覆盖已有商户`() {
        // 真实事故回归（2026-10-13 碰一碰）：页面解析把"20.06"当商户，合并回填要挡住坏值
        val merged = mergePageInfo(
            existing = pending(counterparty = "鸿润福连锁超市", amount = 2006),
            pageAmountCents = 2006,
            pageTxTime = null,
            pageCounterparty = "20.06",
            pageNote = "",
            pagePaymentMethod = null,
            pageRuleName = "支付宝·支付页",
        )
        assertEquals("鸿润福连锁超市", merged.counterparty)
        assertEquals(2006L, merged.amountCents)
    }

    @Test
    fun `美团外卖的定死商户按页面优先正常覆盖既有收单名`() {
        // 美团外卖的支付页商户是**定死**值（"美团外卖"，见 MeituanPageParser）：合并时按页面优先覆盖——
        // 银行侧商户多是"支付宝-美团"这类收单名，换成可读、可学习的"美团外卖"
        val merged = mergePageInfo(
            existing = pending(
                packageName = NotificationRules.MEITUAN_TAKEOUT,
                counterparty = "支付宝-美团",
                amount = 1800,
            ),
            pageAmountCents = 1800,
            pageTxTime = null,
            pageCounterparty = "美团外卖",
            pageNote = "支付方式 中国银行储蓄卡(8920)",
            pagePaymentMethod = "中国银行储蓄卡(8920)",
            pageRuleName = "美团外卖·支付页",
        )
        assertEquals("美团外卖", merged.counterparty)
    }

    // ---------- 去重键工具 ----------

    @Test
    fun `去重键解析兼容退款段的键`() {
        val plain = parseDedupKey("com.tencent.mm|EXPENSE|1290|29500")!!
        assertEquals("com.tencent.mm", plain.packageName)
        assertEquals("EXPENSE", plain.type)
        assertEquals(1290L, plain.amountCents)
        assertEquals(29500L, plain.minute)

        val refund = parseDedupKey("com.tencent.mm|INCOME|R|1290|29500")!!
        assertEquals("INCOME", refund.type)
        assertEquals(1290L, refund.amountCents)
        assertEquals(29500L, refund.minute)
    }

    @Test
    fun `去重键解析拒绝残缺键`() {
        assertNull(parseDedupKey("com.tencent.mm|EXPENSE|1290"))
        assertNull(parseDedupKey("com.tencent.mm|EXPENSE|abc|29500"))
    }

    @Test
    fun `去重键解析出流程后缀_分钟桶仍是后缀前的值`() {
        val plain = parseDedupKey("com.tencent.mm|EXPENSE|1280|678")!!
        assertEquals("com.tencent.mm", plain.packageName)
        assertEquals("EXPENSE", plain.type)
        assertEquals(1280L, plain.amountCents)
        assertEquals(678L, plain.minute)
        assertEquals("", plain.flowIdentity) // 无后缀 = 该分钟桶里的第一笔

        val second = parseDedupKey("com.tencent.mm|EXPENSE|1280|678@f1a2")!!
        assertEquals("com.tencent.mm", second.packageName)
        assertEquals("EXPENSE", second.type)
        assertEquals(1280L, second.amountCents)
        assertEquals(678L, second.minute)
        assertEquals("f1a2", second.flowIdentity)
    }

    @Test
    fun `流程标识解析_无后缀为空串_有后缀与分配时同形`() {
        // 约定：键尾 `@<标识>`，读回的标识与 DedupKeyAllocator 分配时传入的形态一致（都不带 @），
        // 否则"同流程"的比对会因为形态不一致而永远不命中
        assertEquals("", flowIdentityOf("com.tencent.mm|EXPENSE|1280|678"))
        assertEquals("f1a2", flowIdentityOf("com.tencent.mm|EXPENSE|1280|678@f1a2"))
        // 退款键多一段 R，尾部结构不变
        assertEquals("f1a2", flowIdentityOf("com.tencent.mm|INCOME|R|1280|678@f1a2"))
    }

    @Test
    fun `去重键换金额保留流程后缀与分钟桶`() {
        // 金额待补录回填后重算键：只替换金额段，来源/方向/退款段与分钟桶（含流程后缀）原样保留
        assertEquals(
            "com.tencent.mm|EXPENSE|1280|678@f1a2",
            dedupKeyWithAmount("com.tencent.mm|EXPENSE|0|678@f1a2", 1280)!!,
        )
        assertEquals(
            "com.tencent.mm|INCOME|R|1280|678@f1a2",
            dedupKeyWithAmount("com.tencent.mm|INCOME|R|0|678@f1a2", 1280)!!,
        )
        // 键格式不对 → 返回 null，调用方按原键处理
        assertNull(dedupKeyWithAmount("com.tencent.mm|EXPENSE|678", 1280))
    }

    // ---------- 跨应用链路身份（2026-10-19：美团微信支付被记两笔） ----------

    @Test
    fun `微信流程身份不再阻塞美团页面的跨应用融合`() {
        // 真实事故：微信支付成功页先入账（flowId=p:wechat），随后美团结果页（p:meituan）到达。
        // 两个流程身份按包名生成、永不相等；旧实现直接判"不同流程" → 美团页再记一笔。
        // 跨应用时身份不构成否定证据（同笔与否由金额/渠道/商户判定）。
        val wechatEntry = pending(
            packageName = NotificationRules.WECHAT,
            amount = 1920,
            timestamp = base,
            createdAt = base,
            flowId = "p:wechat",
            note = "支付方式 微信支付",
        )
        assertTrue(
            flowCompatible(wechatEntry, "p:meituan", NotificationRules.MEITUAN_TAKEOUT),
        )
        // 同应用仍然要按流程隔离（两笔同额订单不能互并）
        assertFalse(
            flowCompatible(wechatEntry, "p:another-wechat-flow", NotificationRules.WECHAT),
        )
        assertTrue(
            flowCompatible(wechatEntry, "p:wechat", NotificationRules.WECHAT),
        )
    }

    @Test
    fun `旧记录的通知实例身份不阻塞另一个应用的页面融合`() {
        // 通知条目的 n: 身份只属于通知侧；页面到达时按页面自己的流程判断
        val notificationEntry = pending(
            packageName = NotificationRules.WECHAT,
            amount = 1920,
            createdAt = base,
            notificationInstanceId = "n:abc",
        )
        assertTrue(
            flowCompatible(notificationEntry, "p:meituan", NotificationRules.MEITUAN_TAKEOUT),
        )
        // 同一条通知的重复回调仍按实例身份约束
        assertFalse(flowCompatible(notificationEntry, "n:other", NotificationRules.WECHAT))
        assertTrue(flowCompatible(notificationEntry, "n:abc", NotificationRules.WECHAT))
    }

    // ---------- 自动入账前的重复预检（±60s 数据库校验） ----------

    @Test
    fun `窗口内同来源同方向同金额视为已有同笔`() {
        val existing = listOf(pending(amount = 1290, createdAt = base))
        assertTrue(hasRecentSameEntry(existing, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 30_000))
        // 边界：正好 60 秒仍算
        assertTrue(hasRecentSameEntry(existing, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 60_000))
    }

    @Test
    fun `窗口外或特征不同不算同笔`() {
        val existing = listOf(pending(amount = 1290, createdAt = base))
        // 超过窗口
        assertFalse(hasRecentSameEntry(existing, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 60_001))
        // 金额不同 / 方向不同 / 来源不同
        assertFalse(hasRecentSameEntry(existing, NotificationRules.WECHAT, TxType.EXPENSE, 1291, base + 1_000))
        assertFalse(hasRecentSameEntry(existing, NotificationRules.WECHAT, TxType.INCOME, 1290, base + 1_000))
        assertFalse(hasRecentSameEntry(existing, NotificationRules.ALIPAY, TxType.EXPENSE, 1290, base + 1_000))
    }

    // ---------- 页面 ⇄ 银行同一笔时的备注合并 ----------

    @Test
    fun `页面并入银行条目时两边的正文都保留`() {
        // 银行正文里的"财付通-微信支付"是渠道线索，页面备注里的支付工具是真实扣款卡，谁都不能被覆盖
        val pageNote = "支付方式 招商银行储蓄卡(2532)"
        val bankNote = "招商银行 您账户2532于10月21日16:54在【财付通-微信支付-微信转账】发生快捷支付扣款，人民币200.00"
        val merged = combineNotes(pageNote, bankNote)
        assertTrue(merged.contains("招商银行储蓄卡(2532)"))
        assertTrue(merged.contains("财付通-微信支付"))
        assertEquals("$pageNote · $bankNote", merged)
    }

    @Test
    fun `备注合并去重_空值与互相包含`() {
        assertEquals("页面备注", combineNotes("页面备注", ""))
        assertEquals("银行备注", combineNotes("", "银行备注"))
        // 互相包含时只留长的那份（同一句话不写两遍）
        assertEquals("银行动账 招商银行", combineNotes("招商银行", "银行动账 招商银行"))
        assertEquals("银行动账 招商银行", combineNotes("银行动账 招商银行", "招商银行"))
    }
}
