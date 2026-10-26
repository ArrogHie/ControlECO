package com.controleco.app.ui.common

import android.content.Context

/**
 * 帮助页的「看过 / 问过」状态（`eco_ui` 里两个布尔，与 [AnimationPrefs] 同一个 UI 偏好文件）。
 *
 * - `help_viewed`：用户进过帮助页（`SettingsHelpScreen` 打开即写入）；
 * - `help_prompted`：已经主动问过要不要看帮助（弹过就写，避免反复骚扰）。
 *
 * 口径：**只自动问一次**——既没看过、也没问过时才在冷启动问；问过之后即使没看也不再弹，
 * 之后只能从「设置 → 关于 → 帮助」进入。与冷启动权限提醒冲突时让权限提醒优先（见 `AccBookNav`）。
 */
object HelpOnboarding {
    private const val PREFS = "eco_ui"
    private const val KEY_VIEWED = "help_viewed"
    private const val KEY_PROMPTED = "help_prompted"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否该主动询问：既没看过帮助，也没问过 */
    fun shouldPrompt(context: Context): Boolean {
        val p = prefs(context)
        return !p.getBoolean(KEY_VIEWED, false) && !p.getBoolean(KEY_PROMPTED, false)
    }

    /** 弹过即记，之后不再自动询问 */
    fun markPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /** 进入帮助页即记「看过」 */
    fun markViewed(context: Context) {
        prefs(context).edit().putBoolean(KEY_VIEWED, true).apply()
    }
}
