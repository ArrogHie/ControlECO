package com.controleco.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.controleco.app.R
import com.controleco.app.ui.theme.eco
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 开屏节奏：logo 淡入 240ms → 停留 300ms → logo 与纸面一起淡出 240ms */
private const val SPLASH_IN_MS = 240
private const val SPLASH_HOLD_MS = 300L
private const val SPLASH_OUT_MS = 240

/**
 * 冷启动开屏：纸面铺满 + 圆角 logo 淡入淡出。
 *
 * 它压在主内容之上绘制，而主内容此时已经在后面完成组合/布局/首绘，
 * 等于把“首帧要渲染的缓存”都趁开屏这段时间做掉，避免用户看到内容后立刻卡一下。
 * 结束回调后由调用方移除本组件。
 */
@Composable
fun ColdStartSplash(onFinished: () -> Unit) {
    val c = MaterialTheme.eco
    // 纸面一开始就是不透明的：先铺底再淡入 logo，避免第一帧透出后面的空屏
    val bgAlpha = remember { Animatable(1f) }
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.90f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { logoAlpha.animateTo(1f, tween(SPLASH_IN_MS, easing = FastOutSlowInEasing)) }
            launch { logoScale.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        }
        delay(SPLASH_HOLD_MS)
        coroutineScope {
            launch { logoAlpha.animateTo(0f, tween(SPLASH_OUT_MS, easing = FastOutSlowInEasing)) }
            launch { bgAlpha.animateTo(0f, tween(SPLASH_OUT_MS, easing = FastOutSlowInEasing)) }
        }
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = bgAlpha.value }
            .background(c.paper),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_art),
            contentDescription = null,
            modifier = Modifier
                .size(104.dp)
                .graphicsLayer {
                    alpha = logoAlpha.value
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                }
                .clip(RoundedCornerShape(26.dp)),
        )
    }
}
