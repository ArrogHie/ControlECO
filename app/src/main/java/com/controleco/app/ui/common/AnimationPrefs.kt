package com.controleco.app.ui.common

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 美化动效总开关（设置里的「显示动画」），默认开启。
 *
 * 关闭后所有“表演型”动画直接定格到终值：金额不滚数、圆环/进度条直接到位、
 * 柱状图不升起、个人页卡片不弹入、切 tab 不做位移、开屏不播淡入淡出。
 *
 * 与 [com.controleco.app.ui.theme.AccentThemeStore] 一样用独立 SharedPreferences 保存，
 * 启动时在 [com.controleco.app.AccBookApp] 初始化，避免污染账本数据层。
 */
object AnimationPrefs {
    private const val PREFS = "eco_ui"
    private const val KEY = "show_animations"

    /** true = 显示美化动效（默认） */
    var enabled by mutableStateOf(true)
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        val p = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
        p.edit().putBoolean(KEY, value).apply()
    }
}
