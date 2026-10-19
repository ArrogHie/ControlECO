package com.controleco.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监听范围目录（设置页「监听的 App」）的静态约定与纯判定。
 * 需要 Context 的读写部分（SharedPreferences）由真机走查覆盖，这里只锁"目录本身"的规则。
 */
class CaptureAppCatalogTest {

    @Test
    fun `目录包名不重复且全部分组非空`() {
        val packages = CaptureAppCatalog.apps.map { it.packageName }
        assertEquals(packages.size, packages.toSet().size)
        AppGroup.entries.forEach { group ->
            assertTrue(
                "分组 ${group.title} 不应为空",
                CaptureAppCatalog.apps.any { it.group == group },
            )
        }
    }

    @Test
    fun `支付三包与电商外卖都在目录里且默认开启`() {
        val defaults = CaptureAppCatalog.defaults()
        assertTrue(NotificationRules.WECHAT in defaults)
        assertTrue(NotificationRules.ALIPAY in defaults)
        assertTrue(NotificationRules.UNIONPAY in defaults)
        assertTrue("com.jingdong.app.mall" in defaults)
        assertTrue("me.ele" in defaults)
        // 银行推送默认也开（可逐项关）
        assertTrue("com.icbc" in defaults)
    }

    @Test
    fun `拼多多不在监听目录_通知与页面通道都不再放行`() {
        // 2026-10-19：拼多多补贴弹窗被页面解析器误记成支出，决定移除该应用的采集链路
        assertFalse("com.xunmeng.pinduoduo" in CaptureAppCatalog.defaults())
        assertFalse(CaptureAppCatalog.isListed("com.xunmeng.pinduoduo"))
        assertFalse(NotificationRules.isInterested("com.xunmeng.pinduoduo"))
        assertFalse(NotificationRules.isCheckoutPackage("com.xunmeng.pinduoduo"))
    }

    @Test
    fun `目录内应用按用户开关判定`() {
        val watched = setOf(NotificationRules.WECHAT)
        assertTrue(CaptureAppCatalog.watchedOrUnknown(watched, NotificationRules.WECHAT))
        // 目录内但被用户关掉 → 不采集
        assertFalse(CaptureAppCatalog.watchedOrUnknown(watched, NotificationRules.ALIPAY))
    }

    @Test
    fun `目录外来源不受目录开关影响`() {
        // 短信（SMS:xxx）与未知银行 App 的动账推送交给各自通道的签名闸门
        assertTrue(CaptureAppCatalog.watchedOrUnknown(emptySet(), "SMS:工商银行"))
        assertTrue(CaptureAppCatalog.watchedOrUnknown(emptySet(), "com.some.unknown.bank"))
    }

    @Test
    fun `美团外卖包名是 takeoutnew 而不是 takeaway`() {
        // 自测修正：此前误写成 takeaway（不存在的包名），美团外卖的通知与页面两条路被静默排除
        assertTrue("com.sankuai.meituan.takeoutnew" in CaptureAppCatalog.defaults())
        assertFalse("com.sankuai.meituan.takeaway" in CaptureAppCatalog.defaults())
        assertTrue(NotificationRules.isCheckoutPackage("com.sankuai.meituan.takeoutnew"))
        assertFalse(NotificationRules.isCheckoutPackage("com.sankuai.meituan.takeaway"))
    }

    @Test
    fun `目录新增应用按增量补入且不改动既有开关`() {
        val stored = setOf(NotificationRules.WECHAT) // 用户只留了微信（支付宝被主动关掉）
        val baseline = setOf(NotificationRules.WECHAT, NotificationRules.ALIPAY) // 上次见过的目录
        val catalog = baseline + "com.sankuai.meituan.takeoutnew"
        val merged = CaptureAppCatalog.watchedWithNew(stored, baseline, catalog)
        assertTrue("新增项要补进来", "com.sankuai.meituan.takeoutnew" in merged)
        assertFalse("用户关掉、且上次已见过的应用不被重新打开", NotificationRules.ALIPAY in merged)
        assertTrue(NotificationRules.WECHAT in merged)
    }
}
