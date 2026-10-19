package com.controleco.app.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 系统读屏服务检测（随选朗读 / TalkBack）。
 *
 * 微信 8.0.52+ 只把页面节点暴露给系统读屏服务：只开启本应用的无障碍服务时，
 * 微信页面可能读到 0×0 空树（日志 `collect empty pkg=com.tencent.mm`）。
 * 同时开启随选朗读后，本应用的无障碍服务才能读到完整页面树。
 *
 * 这里只做“是否已启用”的只读检测，不申请、不控制、不读取读屏服务的内容；
 * TalkBack 也满足微信的白名单条件，但随选朗读更轻量，优先引导用户开随选朗读。
 */
object ScreenReaderAccess {

    /** 系统无障碍设置中是否已启用随选朗读或 TalkBack */
    fun isEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val fromManager = am?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        )?.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            isScreenReaderComponent(serviceInfo.packageName, serviceInfo.name)
        } == true
        if (fromManager) return true

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return enabledFromSetting(enabled)
    }

    /** Settings 字符串里的启用判定：多个服务用 `:` 分隔 */
    internal fun enabledFromSetting(value: String?): Boolean =
        value?.split(':')?.any { isScreenReaderComponent(it) } == true

    /** 组件是否属于随选朗读/TalkBack（兼容不同 ROM 的包名与长短组件名） */
    internal fun isScreenReaderComponent(packageName: String?, className: String?): Boolean =
        isScreenReaderComponent("${packageName.orEmpty()}/${className.orEmpty()}")

    internal fun isScreenReaderComponent(component: String): Boolean {
        val lower = component.trim().lowercase()
        if (lower.isEmpty()) return false
        if (lower.startsWith("com.controleco.app/")) return false
        return lower.contains("selecttospeak") || lower.contains("talkback")
    }
}
