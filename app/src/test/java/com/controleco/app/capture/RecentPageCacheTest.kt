package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳定页面去重缓存：同一笔结果页重复采样只处理一次，不同交易时间/金额不被吞；
 * TTL 过期与容量上限都可验证（注入 clock，不依赖真实时间）。
 */
class RecentPageCacheTest {

    private var now = 1_000_000L

    private fun newCache(capacity: Int = 8) = RecentPageCache(
        capacity = capacity,
        ttlMs = RecentPageCache.DEFAULT_TTL_MS,
        clock = { now },
    )

    private fun key(
        packageName: String = NotificationRules.WECHAT,
        type: TxType = TxType.INCOME,
        isRefund: Boolean = false,
        ruleName: String = "微信·收款到账页",
        amountCents: Long = 520,
        txTime: Long = 1_000_000L,
    ) = stablePageKey(packageName, type, isRefund, ruleName, amountCents, txTime)

    @Test
    fun `稳定页面重复_第二份应被跳过`() {
        val cache = newCache()
        val page = key()
        assertFalse("首次出现应放行", cache.markIfDuplicate(page))
        assertTrue("同一交易时间的同一页面再次出现应判重复", cache.markIfDuplicate(page))
        assertTrue(cache.markIfDuplicate(page))
    }

    @Test
    fun `TTL 内重复_过期后放行`() {
        val cache = newCache()
        val page = key()
        cache.markIfDuplicate(page)

        now += RecentPageCache.DEFAULT_TTL_MS
        assertTrue("正好到 TTL 边界仍算重复", cache.markIfDuplicate(page))

        now += 1
        assertFalse("超过 TTL 后重新放行", cache.markIfDuplicate(page))
    }

    @Test
    fun `交易时间不同_同额页面不被吞`() {
        val cache = newCache()
        // 两笔真实同额交易：页面文本可能完全相同，但页面交易时间不同。
        assertFalse(cache.markIfDuplicate(key(txTime = 1_000_000L)))
        assertFalse(cache.markIfDuplicate(key(txTime = 1_000_001L)))
    }

    @Test
    fun `金额规则方向或退款标记不同_各自放行`() {
        val cache = newCache()
        assertFalse(cache.markIfDuplicate(key(amountCents = 520)))
        assertFalse(cache.markIfDuplicate(key(amountCents = 750)))
        assertFalse(cache.markIfDuplicate(key(ruleName = "微信·账单详情页")))
        assertFalse(cache.markIfDuplicate(key(type = TxType.EXPENSE)))
        assertFalse(cache.markIfDuplicate(key(isRefund = true)))
    }

    @Test
    fun `容量有界_最旧身份被淘汰`() {
        val cache = newCache(capacity = 4)
        val first = key(txTime = 1_000_000L)
        cache.markIfDuplicate(first)
        for (i in 1..10) {
            cache.markIfDuplicate(key(txTime = 1_000_000L + i))
        }
        assertEquals("容量不得超过上限", 4, cache.size())

        // 最旧的键已被淘汰：再次出现按新页面处理（宁可多处理一次，也不无限增长）。
        assertFalse(cache.markIfDuplicate(first))
        assertEquals(4, cache.size())
    }
}
