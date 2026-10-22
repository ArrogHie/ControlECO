package com.controleco.app.capture

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 自动记账依赖的权限项（顺序 = 提醒弹窗的展示顺序）。
 *
 * 「随选朗读」**不在其中**：页面采集的注册组件改用与系统读屏一致的组件身份后，
 * 微信在"只授权本应用、系统读屏关闭"的情况下也能读到节点（真机实测见
 * docs/无障碍页面捕获兼容性.md），因此它不再是必须开启的权限项。
 * 读不到页面时的备用手段仍保留在设置 → 权限页（见 ScreenReaderAccess）。
 */
enum class CapturePermission(val label: String) {
    LISTENER("通知监听"),
    A11Y("无障碍"),
    NOTIFY("通知权限"),
    SMS("短信权限"),
    OVERLAY("悬浮窗"),
}

/**
 * 需要提醒的缺失项：入参 = 每项是否"已就绪"（功能未启用或权限已给都算就绪），
 * 也就是只提醒"功能开着但权限没给"的项，用户在开关里明确关掉的功能不唠叨。纯函数便于单测。
 */
internal fun missingPermissions(ready: Map<CapturePermission, Boolean>): List<CapturePermission> =
    CapturePermission.entries.filter { ready[it] != true }

/** 采集层权限自检（打开 App 时的提醒弹窗用） */
object CapturePermissions {

    /** 已开启功能所需但缺失的权限；空 = 权限齐全 */
    fun missing(context: Context): List<CapturePermission> {
        val app = context.applicationContext
        return missingPermissions(
            mapOf(
                CapturePermission.LISTENER to
                    ready(CaptureCenter.isCaptureEnabled(), hasListenerAccess(app)),
                CapturePermission.A11Y to
                    ready(CaptureCenter.isA11yCaptureEnabled(), AccBookAccessibilityService.isEnabled(app)),
                CapturePermission.NOTIFY to
                    ready(CaptureCenter.isCaptureNotifyEnabled(), canPostNotifications(app)),
                CapturePermission.SMS to
                    ready(CaptureCenter.isSmsCaptureEnabled(), hasSmsPermission(app)),
                CapturePermission.OVERLAY to
                    ready(CaptureCenter.isOverlayConfirmEnabled(), canDrawOverlays(app)),
            ),
        )
    }

    /** 功能未启用或权限已给 = 无需提醒 */
    private fun ready(needed: Boolean, granted: Boolean) = !needed || granted

    /** 权限提醒每次冷启动最多弹一次（Activity 重建/旋转不重复弹） */
    fun claimLaunchPrompt(): Boolean {
        if (launchPromptClaimed) return false
        launchPromptClaimed = true
        return true
    }

    private var launchPromptClaimed = false

    private fun hasListenerAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 安卓 13+ 的运行时通知权限（横幅提醒的前提） */
    private fun canPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** 收短信权限（银行动账短信通道的前提；安卓 13+ 侧载同样需运行时授权） */
    private fun hasSmsPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECEIVE_SMS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 悬浮窗权限（悬浮确认卡的前提；未授权时该通道自动回落通知，开关开着才提醒） */
    private fun canDrawOverlays(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)
}
