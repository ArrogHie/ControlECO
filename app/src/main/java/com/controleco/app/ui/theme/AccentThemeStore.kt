package com.controleco.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 强调色偏好：7 套主题色会改变强调色族、hero 渐变、主操作渐变，
 * 「纯白」还会额外把中性面（背景/卡片/分隔线）换成白与石墨；
 * 收支/警告/危险语义色永远不变。
 *
 * 用独立 SharedPreferences 保存，启动时在 [com.controleco.app.AccBookApp] 初始化；
 * 不放进 LedgerViewModel，避免为一个纯展示偏好污染账本数据层。
 */
object AccentThemeStore {
    private const val PREFS = "eco_ui"
    private const val KEY = "accent_theme"

    var current: EcoAccent by mutableStateOf(EcoAccent.Tide)
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        current = EcoAccent.fromId(p.getString(KEY, EcoAccent.Tide.id))
    }

    fun set(context: Context, accent: EcoAccent) {
        current = accent
        val p = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
        p.edit().putString(KEY, accent.id).apply()
    }
}
