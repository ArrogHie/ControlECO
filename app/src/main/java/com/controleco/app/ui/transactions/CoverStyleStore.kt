package com.controleco.app.ui.transactions

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 结余封面底色：night（默认夜色）/ mist（薄雾）/ clay（陶土）。
 * 仅保存在本机；有自定义图片时图片优先，底色只在移除图片后生效。
 */
object CoverStyleStore {
    const val NIGHT = "night"
    const val MIST = "mist"
    const val CLAY = "clay"

    private const val PREFS = "eco_ui"
    private const val KEY = "cover_style"

    var current by mutableStateOf(NIGHT)
        private set

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = sp.getString(KEY, NIGHT).orEmpty().ifBlank { NIGHT }
    }

    fun set(context: Context, style: String) {
        val safe = if (style == MIST || style == CLAY || style == NIGHT) style else NIGHT
        current = safe
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, safe).apply()
    }
}
