package com.controleco.app.capture

import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面采集服务的注册入口与运行时监听范围（纯 JVM 判定，不需要设备）。
 *
 * 这两件事都出现过"看起来对、实际错位"的问题，所以写成测试卡住：
 * - **组件身份错位**：系统启用的是新入口组件，应用却拿旧类名比对 → 误判"未授权"；
 * - **过滤口径错位**：系统级 `packageNames` 与服务内放行判定不一致 →
 *   专属解析器收不到页面事件（或用户关掉的应用仍在被采集）。
 */
class PageCaptureServiceEntryTest {

    @Test
    fun `注册入口类名与组件身份常量一致`() {
        assertEquals(
            AccBookAccessibilityService.SERVICE_CLASS_NAME,
            SelectToSpeakService::class.java.name,
        )
    }

    @Test
    fun `入口组件继承页面采集服务`() {
        assertTrue(
            AccBookAccessibilityService::class.java
                .isAssignableFrom(SelectToSpeakService::class.java),
        )
    }

    @Test
    fun `入口组件不重复实现任何方法`() {
        // 入口类只承载"注册组件身份"：不重复实现 onAccessibilityEvent()，
        // 也不新增第二套采集逻辑（一旦有人在入口类里写方法，这条会失败）
        assertEquals(0, SelectToSpeakService::class.java.declaredMethods.size)
    }

    private val catalog = setOf("com.tencent.mm", "com.eg.android.AlipayGphone", "me.ele")

    @Test
    fun `监听范围过滤只收用户监听的目录应用`() {
        val filter = AccBookAccessibilityService.servicePackageFilter(
            watched = setOf("com.tencent.mm", "me.ele"),
            dedicated = emptySet(),
            listed = catalog,
            captureOn = true,
            captureAll = false,
        )
        assertArrayEquals(arrayOf("com.tencent.mm", "me.ele"), filter?.sortedArray())
    }

    @Test
    fun `用户关掉微信后微信不再被监听`() {
        val filter = AccBookAccessibilityService.servicePackageFilter(
            watched = setOf("me.ele"),
            dedicated = emptySet(),
            listed = catalog,
            captureOn = true,
            captureAll = false,
        )
        assertFalse(filter!!.contains("com.tencent.mm"))
        assertTrue(filter.contains("me.ele"))
    }

    @Test
    fun `目录外的专属解析器来源不被过滤掉`() {
        // 目录没列、但有专属解析器的来源必须仍然收事件，否则解析器形同失效
        val filter = AccBookAccessibilityService.servicePackageFilter(
            watched = setOf("com.tencent.mm"),
            dedicated = setOf("com.tencent.mm", "com.example.newpay"),
            listed = catalog,
            captureOn = true,
            captureAll = false,
        )
        assertArrayEquals(arrayOf("com.example.newpay", "com.tencent.mm"), filter?.sortedArray())
    }

    @Test
    fun `页面采集关闭后不监听任何包名`() {
        assertArrayEquals(
            emptyArray<String>(),
            AccBookAccessibilityService.servicePackageFilter(
                watched = catalog,
                dedicated = emptySet(),
                listed = catalog,
                captureOn = false,
                captureAll = false,
            ),
        )
    }

    @Test
    fun `调试捕获所有页面时不设包名过滤`() {
        // null = 接收所有应用：调试开关的目的就是采样目录外的应用
        assertNull(
            AccBookAccessibilityService.servicePackageFilter(
                watched = setOf("com.tencent.mm"),
                dedicated = emptySet(),
                listed = catalog,
                captureOn = true,
                captureAll = true,
            ),
        )
    }
}
