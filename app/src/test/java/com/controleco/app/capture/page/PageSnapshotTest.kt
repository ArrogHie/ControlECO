package com.controleco.app.capture.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面快照模型的行为约束：
 * - [PageSnapshot.lines]：空白节点剔除 + 连续重复折叠（比较对象是"上一条保留的行"）；
 * - [PageNode.display]：text 为空时回退无障碍描述；
 * - 整页文本 [PageSnapshot.joined] 与内容指纹 [PageSnapshot.fingerprint]；
 * - 按控件 id 后缀定位节点 [PageSnapshot.hasViewId] / [PageSnapshot.nodeIndexByViewId]；
 * - 按窗口取行 [PageSnapshot.linesOfWindow]（与 lines 不同：不做窗口过滤、不折叠重复）。
 */
class PageSnapshotTest {

    private val pkg = "com.controleco.app"

    /** 从纯文本列表造快照：每个字符串一个根级节点（parentIndex = -1） */
    private fun snapshotOf(
        texts: List<String>,
        activity: String = "",
        windowId: Int = -1,
    ): PageSnapshot = PageSnapshot(
        packageName = pkg,
        activity = activity,
        windowId = windowId,
        nodes = texts.map { PageNode(text = it, parentIndex = -1) },
    )

    private fun snapshot(vararg texts: String): PageSnapshot = snapshotOf(texts.toList())

    // ---------- lines ----------

    @Test
    fun `lines 跳过空白节点并折叠连续重复`() {
        val s = snapshot("支付成功", "", "　 ", "支付成功", "¥19.38", "¥19.38", "零钱")
        // 空白节点被跳过，所以中间夹空白的两次"支付成功"在结果序列里相邻，第二次被折叠
        assertEquals(listOf("支付成功", "¥19.38", "零钱"), s.lines)
    }

    @Test
    fun `lines 不折叠被其他文本隔开的重复`() {
        assertEquals(listOf("微信", "零钱", "微信"), snapshot("微信", "零钱", "微信").lines)
    }

    @Test
    fun `lines 在 text 为空时使用无障碍描述`() {
        val s = PageSnapshot(
            packageName = pkg,
            nodes = listOf(
                PageNode(text = "", contentDescription = "关闭"),
                PageNode(text = "支付成功", contentDescription = "标题"),
            ),
        )
        assertEquals(listOf("关闭", "支付成功"), s.lines)
    }

    @Test
    fun `全空白快照没有任何行`() {
        val s = snapshot("", "   ", "　")
        assertTrue(s.lines.isEmpty())
        assertEquals("", s.joined)
    }

    // ---------- display ----------

    @Test
    fun `display 为空文本回退到无障碍描述`() {
        assertEquals("关闭", PageNode(text = "", contentDescription = "关闭").display)
        assertEquals("关闭", PageNode(text = "  ", contentDescription = " 关闭 ").display)
        assertEquals("零钱", PageNode(text = "零钱", contentDescription = "关闭").display)
        assertTrue(PageNode(text = "", contentDescription = "").isEmpty)
        assertTrue(PageNode(text = "  ", contentDescription = "　").isEmpty)
        assertFalse(PageNode(text = "", contentDescription = "关闭").isEmpty)
    }

    // ---------- joined / fingerprint ----------

    @Test
    fun `joined 用空格连接各行`() {
        assertEquals("支付成功 ¥19.38 老王奶茶店", snapshot("支付成功", "¥19.38", "老王奶茶店").joined)
    }

    @Test
    fun `fingerprint 相同内容一致_文本变化后不同`() {
        val a = snapshotOf(listOf("支付成功", "¥19.38"), activity = "PaySuccessActivity")
        val same = snapshotOf(listOf("支付成功", "¥19.38"), activity = "PaySuccessActivity")
        val changedText = snapshotOf(listOf("支付成功", "¥19.39"), activity = "PaySuccessActivity")
        val changedActivity = snapshotOf(listOf("支付成功", "¥19.38"), activity = "PayDetailActivity")

        assertEquals(a.fingerprint, same.fingerprint)
        assertNotEquals(a.fingerprint, changedText.fingerprint)
        // 活动名是指纹的一部分：同一段文本在不同页面上不算同一张页面
        assertNotEquals(a.fingerprint, changedActivity.fingerprint)
    }

    // ---------- contains ----------

    @Test
    fun `contains 与 containsAll 按整页文本判定`() {
        val s = snapshot("支付成功", "¥19.38", "老王奶茶店")
        assertTrue(s.contains("支付成功"))
        assertTrue(s.contains("19.38"))
        assertFalse(s.contains("支付失败"))
        // 集合版本：任一命中即真
        assertTrue(s.contains(listOf("支付失败", "老王奶茶店")))
        assertFalse(s.contains(listOf("支付失败", "已退款")))
        assertTrue(s.containsAll(listOf("支付成功", "老王奶茶店")))
        assertFalse(s.containsAll(listOf("支付成功", "支付失败")))
    }

    // ---------- 行号查询 ----------

    @Test
    fun `lineIndex 与 firstLineContains 返回首个命中的行号`() {
        val s = snapshot("支付成功", "¥19.38", "老王奶茶店", "¥9.90")
        assertEquals(1, s.lineIndex { it.startsWith("¥") })
        assertEquals(-1, s.lineIndex { it.contains("支付失败") })
        assertEquals(2, s.firstLineContains("奶茶"))
        assertEquals(-1, s.firstLineContains("咖啡"))
    }

    // ---------- 控件 id 定位 ----------

    @Test
    fun `hasViewId 与 nodeIndexByViewId 命中裸资源名`() {
        val s = PageSnapshot(
            packageName = pkg,
            nodes = listOf(
                PageNode(text = "支付成功"),
                PageNode(text = "¥19.38", viewId = "amount"),
            ),
        )
        assertTrue(s.hasViewId("amount"))
        assertFalse(s.hasViewId("title"))
        assertEquals(1, s.nodeIndexByViewId("amount"))
        assertEquals(-1, s.nodeIndexByViewId("absent_id"))
        // 空 id 不进集合
        assertEquals(setOf("amount"), s.viewIds)
    }

    @Test
    fun `hasViewId 支持冒号后缀写法_带斜杠的 id 只认全名`() {
        val colonSuffix = PageSnapshot(
            packageName = pkg,
            nodes = listOf(PageNode(text = "金额", viewId = "container:amount")),
        )
        assertTrue(colonSuffix.hasViewId("amount"))
        assertEquals(0, colonSuffix.nodeIndexByViewId("amount"))

        // 判定口径是"全等或 ':后缀' 结尾"；"id/amount" 这类带斜杠的写法只与全名相等时命中
        val slashed = PageSnapshot(
            packageName = pkg,
            nodes = listOf(PageNode(text = "金额", viewId = "id/amount")),
        )
        assertTrue(slashed.hasViewId("id/amount"))
        assertFalse(slashed.hasViewId("amount"))
        assertEquals(-1, slashed.nodeIndexByViewId("amount"))
    }

    // ---------- 按窗口取行 ----------

    @Test
    fun `linesOfWindow 只取指定窗口的展示文本且不去重`() {
        val s = PageSnapshot(
            packageName = pkg,
            windowId = 0,
            nodes = listOf(
                PageNode(text = "支付成功", windowId = 0),
                PageNode(text = "弹层提示", windowId = 7),
                PageNode(text = "支付成功", windowId = 0),
                PageNode(text = "   ", windowId = 7),
                PageNode(text = "零钱", windowId = 0),
            ),
        )
        // 同窗口的重复行原样保留（lines 才会折叠连续重复）
        assertEquals(listOf("支付成功", "支付成功", "零钱"), s.linesOfWindow(0))
        assertEquals(listOf("弹层提示"), s.linesOfWindow(7))
        assertTrue(s.linesOfWindow(99).isEmpty())
        // lines 不做窗口过滤
        assertEquals(listOf("支付成功", "弹层提示", "支付成功", "零钱"), s.lines)
    }
}
