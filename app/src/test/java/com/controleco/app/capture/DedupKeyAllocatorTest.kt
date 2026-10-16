package com.controleco.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 同一分钟桶内的去重键分配：同额的多笔交易按"流程标识"区分——
 * 同一流程复用同一个键（同笔重复捕获不产生第二条）；不同流程在键尾追加 `@<标识>`
 * （用户确实又付了一笔时，两笔都能落账，不被唯一索引吞掉）。
 *
 * 时钟可注入，保证判定确定（存活窗口/容量淘汰都按注入的时钟走）。
 */
class DedupKeyAllocatorTest {

    private val base = "com.tencent.mm|EXPENSE|1290|29500"

    @Test
    fun `同一流程两次分配复用同一个键`() {
        var now = 0L
        val allocator = DedupKeyAllocator(clock = { now })
        assertEquals(base, allocator.allocate(base, "f1"))
        now += 1_000
        assertEquals(base, allocator.allocate(base, "f1"))
    }

    @Test
    fun `同一分钟桶内的另一笔流程追加后缀_两笔都能落账`() {
        var now = 0L
        val allocator = DedupKeyAllocator(clock = { now })
        val first = allocator.allocate(base, "f1")
        now += 2_000
        val second = allocator.allocate(base, "f2")
        assertEquals(base, first)
        assertEquals("$base@f2", second)
        assertNotEquals(first, second)
    }

    @Test
    fun `流程标识为空直接返回原键`() {
        val allocator = DedupKeyAllocator(clock = { 0L })
        assertEquals(base, allocator.allocate(base, null))
        assertEquals(base, allocator.allocate(base, ""))
        // 键已被别的流程占用时，无标识的捕获（通知侧）仍然直接用原键
        val busy = DedupKeyAllocator(clock = { 0L })
        busy.allocate(base, "f1")
        assertEquals(base, busy.allocate(base, null))
    }

    @Test
    fun `同一流程再来一次_键与第一次一致`() {
        val allocator = DedupKeyAllocator(clock = { 0L })
        assertEquals(base, allocator.allocate(base, "f1"))
        assertEquals("$base@f2", allocator.allocate(base, "f2"))
        // 回到第一次的流程：仍然是原键，不是 `base@f2`
        assertEquals(base, allocator.allocate(base, "f1"))
    }

    @Test
    fun `释放之后同一流程再分配仍得到同一个键`() {
        val allocator = DedupKeyAllocator(clock = { 0L })
        assertEquals(base, allocator.allocate(base, "f1"))
        assertEquals("$base@f2", allocator.allocate(base, "f2"))
        // 条目被丢弃/确认后释放键：同一流程再分配得到同一个键（可复用）
        allocator.release("$base@f2")
        assertEquals("$base@f2", allocator.allocate(base, "f2"))
        // 原键释放后回到"没人占用"：后来的流程直接复用原键
        allocator.release(base)
        assertEquals(base, allocator.allocate(base, "f9"))
    }

    @Test
    fun `分配出的键_流程标识带at前缀_与分配时传入的标识不同形`() {
        // 第一笔流程只用原键（无后缀）；第二笔流程的键尾是 `@f2`，flowIdentityOf 读回来是 "f2"，
        // 与分配时传入的形态一致——"同流程"的比对因此能命中。
        val allocator = DedupKeyAllocator(clock = { 0L })
        val first = allocator.allocate("com.tencent.mm|EXPENSE|1280|678", "f1")
        val second = allocator.allocate("com.tencent.mm|EXPENSE|1280|678", "f2")
        assertEquals("com.tencent.mm|EXPENSE|1280|678", first)
        assertEquals("com.tencent.mm|EXPENSE|1280|678@f2", second)
        assertEquals("", flowIdentityOf(first))
        assertEquals("f2", flowIdentityOf(second))
    }

    @Test
    fun `超过存活窗口后占用被清空_后来的流程复用原键`() {
        var now = 0L
        val allocator = DedupKeyAllocator(ttlMs = 300_000L, clock = { now })
        assertEquals(base, allocator.allocate(base, "f1"))
        // 窗口内：另一笔流程要让位（追加后缀）
        now += 1_000
        assertEquals("$base@f2", allocator.allocate(base, "f2"))
        // 超过存活窗口：占用被淘汰，键回到第一笔的形态（分钟桶本身也已翻页）
        now += 300_001
        assertEquals(base, allocator.allocate(base, "f3"))
    }
}
