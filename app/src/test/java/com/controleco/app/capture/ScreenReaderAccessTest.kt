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
