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
            CapturePermission.SCREEN_READER to false,
            CapturePermission.NOTIFY to false,
            CapturePermission.SMS to true,
            CapturePermission.OVERLAY to true,
        )
        assertEquals(
            listOf(CapturePermission.A11Y, CapturePermission.SCREEN_READER, CapturePermission.NOTIFY),
            missingPermissions(ready),
        )
    }

    @Test
    fun `功能未启用（视为就绪）不提醒`() {
        // 用户主动关掉「自动记录支付页面」= 无障碍未授权也不算问题；短信/悬浮卡开关默认状态同理不提醒
        val ready = mapOf(
            CapturePermission.LISTENER to false,
            CapturePermission.A11Y to true,
            CapturePermission.SCREEN_READER to true,
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
            CapturePermission.SCREEN_READER to true,
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
            CapturePermission.SCREEN_READER to true,
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
        assertEquals("随选朗读", CapturePermission.SCREEN_READER.label)
        assertEquals("通知权限", CapturePermission.NOTIFY.label)
        assertEquals("短信权限", CapturePermission.SMS.label)
        assertEquals("悬浮窗", CapturePermission.OVERLAY.label)
    }

    @Test
    fun `随选朗读仅在页面采集且微信监听时需要`() {
        assertTrue(CapturePermissions.screenReaderNeeded(a11yCaptureEnabled = true, wechatWatched = true))
        assertFalse(CapturePermissions.screenReaderNeeded(a11yCaptureEnabled = false, wechatWatched = true))
        assertFalse(CapturePermissions.screenReaderNeeded(a11yCaptureEnabled = true, wechatWatched = false))
    }

    @Test
    fun `无障碍启用判定同时接受长短组件名`() {
        // 系统回写的形态可能是长组件名或短形态（pkg/.Class）——只比长名会误报"未开启"
        val long = "com.controleco.app/com.controleco.app.capture.AccBookAccessibilityService"
        val short = "com.controleco.app/.capture.AccBookAccessibilityService"
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
        assertFalse(AccBookAccessibilityService.a11yEnabledFromSetting(null, long, short))
        assertFalse(AccBookAccessibilityService.a11yEnabledFromSetting("", long, short))
    }
}
