package com.controleco.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 打开 App 时的权限提醒：只列"功能开着但没授权"的项。 */
class CapturePermissionsTest {

    @Test
    fun `权限齐全时不提醒`() {
        val ready = CapturePermission.entries.associateWith { true }
        assertTrue(missingPermissions(ready).isEmpty())
    }

    @Test
    fun `按固定顺序列出缺失项`() {
        val ready = mapOf(
            CapturePermission.LISTENER to true,
            CapturePermission.A11Y to false,
            CapturePermission.NOTIFY to false,
            CapturePermission.SMS to true,
            CapturePermission.OVERLAY to true,
        )
        assertEquals(
            listOf(CapturePermission.A11Y, CapturePermission.NOTIFY),
            missingPermissions(ready),
        )
    }

    @Test
    fun `功能未启用（视为就绪）不提醒`() {
        // 用户主动关掉「自动记录支付页面」= 无障碍未授权也不算问题；短信/悬浮卡开关默认状态同理不提醒
        val ready = mapOf(
            CapturePermission.LISTENER to false,
            CapturePermission.A11Y to true,
            CapturePermission.NOTIFY to true,
            CapturePermission.SMS to true,
            CapturePermission.OVERLAY to true,
        )
        assertEquals(listOf(CapturePermission.LISTENER), missingPermissions(ready))
    }

    @Test
    fun `短信开关开着但没授权时提醒`() {
        val ready = mapOf(
            CapturePermission.LISTENER to true,
            CapturePermission.A11Y to true,
            CapturePermission.NOTIFY to true,
            CapturePermission.SMS to false,
            CapturePermission.OVERLAY to true,
        )
        assertEquals(listOf(CapturePermission.SMS), missingPermissions(ready))
    }

    @Test
    fun `悬浮卡开关开着但没授权时提醒`() {
        val ready = mapOf(
            CapturePermission.LISTENER to true,
            CapturePermission.A11Y to true,
            CapturePermission.NOTIFY to true,
            CapturePermission.SMS to true,
            CapturePermission.OVERLAY to false,
        )
        assertEquals(listOf(CapturePermission.OVERLAY), missingPermissions(ready))
    }

    @Test
    fun `缺省未提供的项按缺失处理`() {
        assertEquals(CapturePermission.entries.toList(), missingPermissions(emptyMap()))
    }

    @Test
    fun `权限项短标签`() {
        assertEquals("通知监听", CapturePermission.LISTENER.label)
        assertEquals("无障碍", CapturePermission.A11Y.label)
        assertEquals("通知权限", CapturePermission.NOTIFY.label)
        assertEquals("短信权限", CapturePermission.SMS.label)
        assertEquals("悬浮窗", CapturePermission.OVERLAY.label)
    }

    @Test
    fun `随选朗读不再是要提醒的权限项`() {
        // 页面采集的注册组件改用与系统读屏一致的组件身份后，只授权本应用就能读到微信节点
        // （真机实测见 docs/无障碍页面捕获兼容性.md），随选朗读只作为读不到时的备用手段，
        // 不能再出现在冷启动的"待开启"提醒里。
        assertFalse(CapturePermission.entries.any { it.label == "随选朗读" })
        assertEquals(
            listOf("通知监听", "无障碍", "通知权限", "短信权限", "悬浮窗"),
            CapturePermission.entries.map { it.label },
        )
    }

    @Test
    fun `无障碍启用判定同时接受长短组件名`() {
        // 系统回写的形态可能是长组件名或短形态（pkg/.Class）——只比长名会误报"未开启"
        val long = "com.controleco.app/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        val short = "com.controleco.app/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        assertTrue(
            AccBookAccessibilityService.a11yEnabledFromSetting(
                "hello.litiaotiao.app/.LttService:$long", long, short,
            ),
        )
        assertTrue(
            AccBookAccessibilityService.a11yEnabledFromSetting(
                "hello.litiaotiao.app/.LttService:$short", long, short,
            ),
        )
        assertFalse(
            AccBookAccessibilityService.a11yEnabledFromSetting(
                "hello.litiaotiao.app/.LttService", long, short,
            ),
        )
        // 升级前注册的旧组件不再算"已开启"：否则会出现"显示已授权、服务其实没运行"
        assertFalse(
            AccBookAccessibilityService.a11yEnabledFromSetting(
                "com.controleco.app/.capture.AccBookAccessibilityService", long, short,
            ),
        )
        assertFalse(AccBookAccessibilityService.a11yEnabledFromSetting(null, long, short))
        assertFalse(AccBookAccessibilityService.a11yEnabledFromSetting("", long, short))
    }
}
