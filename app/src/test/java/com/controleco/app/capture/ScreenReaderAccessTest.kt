package com.controleco.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 微信页面读取依赖的系统读屏服务检测。 */
class ScreenReaderAccessTest {

    @Test
    fun `随选朗读的长组件名被识别`() {
        assertTrue(
            ScreenReaderAccess.isScreenReaderComponent(
                "com.google.android.marvin.talkback/" +
                    "com.google.android.accessibility.selecttospeak.SelectToSpeakService",
            ),
        )
    }

    @Test
    fun `TalkBack组件也被识别`() {
        assertTrue(
            ScreenReaderAccess.isScreenReaderComponent(
                "com.google.android.marvin.talkback/" +
                    "com.google.android.marvin.talkback.TalkBackService",
            ),
        )
        assertTrue(
            ScreenReaderAccess.isScreenReaderComponent(
                "com.android.talkback/.TalkBackService",
            ),
        )
    }

    @Test
    fun `本应用无障碍服务不算读屏`() {
        assertFalse(
            ScreenReaderAccess.isScreenReaderComponent(
                "com.controleco.app/.capture.AccBookAccessibilityService",
            ),
        )
        assertFalse(
            ScreenReaderAccess.isScreenReaderComponent(
                "com.example.other/.MyAccessibilityService",
            ),
        )
    }

    @Test
    fun `本应用注册入口用了随选朗读类名也不算读屏`() {
        // 页面采集的注册入口类名与系统随选朗读相同（页面捕获兼容性，见
        // docs/无障碍页面捕获兼容性.md）。判定必须先看包名，否则应用会把自己的
        // 采集服务当成系统读屏，权限页就不再提示用户去开随选朗读。
        assertFalse(
            ScreenReaderAccess.isScreenReaderComponent(
                "com.controleco.app/" + AccBookAccessibilityService.SERVICE_CLASS_NAME,
            ),
        )
        assertFalse(
            ScreenReaderAccess.isScreenReaderComponent(
                "com.controleco.app/com.google.android.accessibility.selecttospeak.SelectToSpeakService",
            ),
        )
        assertFalse(
            ScreenReaderAccess.enabledFromSetting(
                "com.controleco.app/" + AccBookAccessibilityService.SERVICE_CLASS_NAME,
            ),
        )
    }

    @Test
    fun `启用列表按冒号分隔识别`() {
        val enabled = "com.controleco.app/.capture.AccBookAccessibilityService:" +
            "com.google.android.marvin.talkback/" +
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        assertTrue(ScreenReaderAccess.enabledFromSetting(enabled))
    }

    @Test
    fun `空值或无关服务不误报`() {
        assertFalse(ScreenReaderAccess.enabledFromSetting(null))
        assertFalse(ScreenReaderAccess.enabledFromSetting(""))
        assertFalse(
            ScreenReaderAccess.enabledFromSetting(
                "com.controleco.app/.capture.AccBookAccessibilityService",
            ),
        )
        assertFalse(ScreenReaderAccess.isScreenReaderComponent(""))
    }
}
