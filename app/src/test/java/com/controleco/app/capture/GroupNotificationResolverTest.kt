package com.controleco.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠摘要子通知补全的测试（GroupNotificationResolver + NotificationRules 的判定部分）。
 * 场景来自真实通知形态：微信把"退款到账通知"折叠成「[2条]」后摘要没有金额，金额只存在于子通知里
 * （实测样本见 docs/开发日志.md；机制见 docs/自动记账捕获机制.md 第一节）。
 */
class GroupNotificationResolverTest {

    private val baseTime = 1_700_000_000_000L

    private fun summary(
        text: String,
        key: String = "summary",
        groupKey: String? = "g1",
        group: String? = "g1",
        postTime: Long = baseTime,
    ) = NotificationText(
        key = key,
        packageName = NotificationRules.WECHAT,
        groupKey = groupKey,
        group = group,
        postTime = postTime,
        title = "微信支付",
        text = text,
    )

    private fun child(
        text: String,
        key: String,
        groupKey: String? = "g1",
        group: String? = "g1",
        postTime: Long = baseTime,
        pkg: String = NotificationRules.WECHAT,
    ) = NotificationText(
        key = key,
        packageName = pkg,
        groupKey = groupKey,
        group = group,
        postTime = postTime,
        title = "微信支付",
        text = text,
    )

    // ---------- 是否需要补全 ----------

    @Test
    fun `grouped summary without amount needs enrichment`() {
        assertTrue(GroupNotificationResolver.needsEnrichment(summary("[2条]微信支付: 退款到账通知")))
    }

    @Test
    fun `summary carrying its own amount does not need enrichment`() {
        // 摘要自己就能解析出账务明细时不该去捞兄弟通知，否则可能抓到无关的那条
        assertFalse(GroupNotificationResolver.needsEnrichment(summary("[2条]微信支付: 已支付¥12.80")))
    }

    @Test
    fun `deduction summary is already a ledger detail`() {
        // 真实样本（2026-10-22）：[11条]微信支付: 已扣费¥6.50 自带金额与支出动词，
        // 不能因为"带计数前缀"就去捞兄弟通知（捞到的那条金额可能属于另一笔）
        assertFalse(GroupNotificationResolver.needsEnrichment(summary("[11条]微信支付: 已扣费¥6.50")))
        assertTrue(NotificationRules.looksLikeLedgerDetail("[11条]微信支付: 已扣费¥6.50"))
    }

    @Test
    fun `plain notification is not a grouped summary`() {
        assertFalse(GroupNotificationResolver.needsEnrichment(summary("已支付")))
    }

    // ---------- 挑子通知 ----------

    @Test
    fun `picks same-group child carrying the amount`() {
        val picked = GroupNotificationResolver.pickChild(
            summary("[2条]微信支付: 退款到账通知"),
            listOf(
                child("微信支付服务通知", key = "c1"),
                child("已支付¥12.80", key = "c2"),
            ),
        )
        assertEquals("c2", picked?.key)
    }

    @Test
    fun `prefers newest same-group child`() {
        val picked = GroupNotificationResolver.pickChild(
            summary("[2条]微信支付: 退款到账通知"),
            listOf(
                child("已支付¥12.80", key = "old", postTime = baseTime - 5_000),
                child("已支付¥30.00", key = "new", postTime = baseTime),
            ),
        )
        assertEquals("new", picked?.key)
    }

    @Test
    fun `ignores candidates from other packages`() {
        assertNull(
            GroupNotificationResolver.pickChild(
                summary("[2条]微信支付: 退款到账通知"),
                listOf(child("已支付¥12.80", key = "c1", pkg = NotificationRules.ALIPAY)),
            ),
        )
    }

    @Test
    fun `ignores itself`() {
        assertNull(
            GroupNotificationResolver.pickChild(
                summary("[2条]微信支付: 退款到账通知", key = "summary"),
                listOf(summary("[2条]微信支付: 退款到账通知", key = "summary")),
            ),
        )
    }

    @Test
    fun `ignores candidates that are not ledger details`() {
        // 群消息、服务通知一类不该被当成金额来源
        assertNull(
            GroupNotificationResolver.pickChild(
                summary("[2条]微信支付: 退款到账通知"),
                listOf(
                    child("张三点赞了你的朋友圈", key = "c1"),
                    child("微信支付服务通知", key = "c2"),
                ),
            ),
        )
    }

    // ---------- 同包回退（无 groupKey 的机型） ----------

    @Test
    fun `falls back to recent same-package child within window`() {
        val picked = GroupNotificationResolver.pickChild(
            summary("[2条]微信支付: 退款到账通知", groupKey = null, group = null),
            listOf(
                child("已支付¥12.80", key = "c1", groupKey = null, group = null, postTime = baseTime - 30_000),
            ),
        )
        assertEquals("c1", picked?.key)
    }

    @Test
    fun `fallback rejects stale child outside window`() {
        // 5 分钟级窗口会把很久以前的支付通知配到当前摘要上（把旧金额记成新交易）；这里收紧到 90 秒
        assertNull(
            GroupNotificationResolver.pickChild(
                summary("[2条]微信支付: 退款到账通知", groupKey = null, group = null),
                listOf(
                    child(
                        "已支付¥12.80",
                        key = "stale",
                        groupKey = null,
                        group = null,
                        postTime = baseTime - GroupNotificationResolver.FALLBACK_WINDOW_MS - 1,
                    ),
                ),
            ),
        )
    }

    // ---------- 文本合并（去重保长） ----------

    @Test
    fun `joinDistinct keeps the longer duplicate`() {
        assertEquals(
            "收款金额¥0.01",
            NotificationText.joinDistinct(listOf("收款金额¥0.01", "收款金额", "")),
        )
    }

    @Test
    fun `joinDistinct keeps distinct parts`() {
        assertEquals("微信支付 已支付¥12.80", NotificationText.joinDistinct(listOf("微信支付", "已支付¥12.80")))
    }
}
