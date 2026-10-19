package com.controleco.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.controleco.app.ui.eco.EcoDialog
import com.controleco.app.ui.eco.EcoField
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.ui.theme.hsvToRgb
import com.controleco.app.ui.theme.rgbToHsv
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 自定义颜色（分类图标用）：**色环 + 内部饱和度/明度方块 + 十六进制/RGB 输入**，全部自绘。
 *
 * - 色环：扫描渐变描在圆环上（0° 在 3 点方向，顺时针），点按/拖动取色相；
 * - 方块：横向"白 → 纯色相"、纵向"透明 → 黑"，点按/拖动取饱和度与明度；
 * - 三个输入与环上取色双向同步：环上动 → 文本跟着变；文本输入合法值 → 光标跟着动。
 */
@Composable
fun ColorWheelDialog(
    initial: Color,
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember(initial) { rgbToHsv(initial.red, initial.green, initial.blue) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val preview = hsvColor(hue, sat, value)

    var hexText by remember { mutableStateOf(hexOf(preview)) }
    var rText by remember { mutableStateOf(channelOf(preview, 16).toString()) }
    var gText by remember { mutableStateOf(channelOf(preview, 8).toString()) }
    var bText by remember { mutableStateOf(channelOf(preview, 0).toString()) }

    /** 环上/方块上取色后把文本一并刷新 */
    fun refreshTexts() {
        val c = hsvColor(hue, sat, value)
        hexText = hexOf(c)
        rText = channelOf(c, 16).toString()
        gText = channelOf(c, 8).toString()
        bText = channelOf(c, 0).toString()
    }

    /** 文本 → 颜色：三个通道都合法（0..255）才应用 */
    fun applyRgbTexts() {
        val r = rText.toIntOrNull()
        val g = gText.toIntOrNull()
        val b = bText.toIntOrNull()
        if (r == null || g == null || b == null) return
        if (r !in 0..255 || g !in 0..255 || b !in 0..255) return
        val hsv = rgbToHsv(r / 255f, g / 255f, b / 255f)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
    }

    fun channelInvalid(text: String): Boolean =
        text.isNotEmpty() && (text.toIntOrNull()?.let { it !in 0..255 } ?: true)

    EcoDialog(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("自定义颜色", style = EcoType.SheetTitle, color = MaterialTheme.eco.ink)
            Spacer(Modifier.height(2.dp))

            Box(Modifier.size(202.dp), contentAlignment = Alignment.Center) {
                // 色环：点 / 拖取色相
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            fun pick(pos: Offset) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val deg = Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())).toFloat()
                                hue = ((deg % 360f) + 360f) % 360f
                                refreshTexts()
                            }
                            detectTapGestures { pick(it) }
                        }
                        .pointerInput(Unit) {
                            fun pick(pos: Offset) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val deg = Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())).toFloat()
                                hue = ((deg % 360f) + 360f) % 360f
                                refreshTexts()
                            }
                            detectDragGestures(
                                onDragStart = { pick(it) },
                                onDrag = { change, _ ->
                                    pick(change.position)
                                    change.consume()
                                },
                            )
                        },
                ) {
                    val stroke = size.minDimension * 0.16f
                    val radius = (size.minDimension - stroke) / 2f
                    drawCircle(
                        brush = Brush.sweepGradient(hueRingColors(), center = center),
                        radius = radius,
                        center = center,
                        style = Stroke(stroke),
                    )
                    // 色相光标：白圈 + 纯色相内点
                    val angle = Math.toRadians(hue.toDouble())
                    val marker = Offset(
                        center.x + radius * cos(angle).toFloat(),
                        center.y + radius * sin(angle).toFloat(),
                    )
                    drawCircle(Color.White, radius = stroke * 0.46f, center = marker)
                    drawCircle(hsvColor(hue, 1f, 1f), radius = stroke * 0.30f, center = marker)
                }

                // 饱和度 / 明度方块：点 / 拖取 S、V
                Box(
                    Modifier
                        .size(124.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(Color.White, hsvColor(hue, 1f, 1f))))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        .pointerInput(Unit) {
                            fun pick(pos: Offset) {
                                sat = (pos.x / size.width).coerceIn(0f, 1f)
                                value = (1f - pos.y / size.height).coerceIn(0f, 1f)
                                refreshTexts()
                            }
                            detectTapGestures { pick(it) }
                        }
                        .pointerInput(Unit) {
                            fun pick(pos: Offset) {
                                sat = (pos.x / size.width).coerceIn(0f, 1f)
                                value = (1f - pos.y / size.height).coerceIn(0f, 1f)
                                refreshTexts()
                            }
                            detectDragGestures(
                                onDragStart = { pick(it) },
                                onDrag = { change, _ ->
                                    pick(change.position)
                                    change.consume()
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val x = sat * size.width
                        val y = (1f - value) * size.height
                        drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(x, y), style = Stroke(2.dp.toPx()))
                        drawCircle(preview, radius = 9.dp.toPx(), center = Offset(x, y), style = Stroke(1.dp.toPx()))
                    }
                }
            }

            // 预览 + 取值文本
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(preview),
                )
                Spacer(Modifier.width(10.dp))
                Text(hexOf(preview), style = EcoType.RowTitle, color = MaterialTheme.eco.ink)
                Spacer(Modifier.weight(1f))
                Text(
                    "RGB ${channelOf(preview, 16)} ${channelOf(preview, 8)} ${channelOf(preview, 0)}",
                    style = EcoType.PageNote,
                    color = MaterialTheme.eco.quiet,
                )
            }

            EcoField(
                label = "十六进制",
                value = hexText,
                onValueChange = { input ->
                    hexText = input
                    input.trim().removePrefix("#").toLongOrNull(16)?.let { v ->
                        if (input.trim().removePrefix("#").length == 6) {
                            val rgb = (v and 0xFFFFFF).toInt()
                            val hsv = rgbToHsv(
                                ((rgb shr 16) and 0xFF) / 255f,
                                ((rgb shr 8) and 0xFF) / 255f,
                                (rgb and 0xFF) / 255f,
                            )
                            hue = hsv[0]
                            sat = hsv[1]
                            value = hsv[2]
                            rText = ((rgb shr 16) and 0xFF).toString()
                            gText = ((rgb shr 8) and 0xFF).toString()
                            bText = (rgb and 0xFF).toString()
                        }
                    }
                },
                placeholder = "#EF4444",
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoField(
                    label = "R",
                    value = rText,
                    onValueChange = { rText = it.filter { c -> c.isDigit() }.take(3); applyRgbTexts() },
                    keyboardType = KeyboardType.Number,
                    isError = channelInvalid(rText),
                    modifier = Modifier.weight(1f),
                )
                EcoField(
                    label = "G",
                    value = gText,
                    onValueChange = { gText = it.filter { c -> c.isDigit() }.take(3); applyRgbTexts() },
                    keyboardType = KeyboardType.Number,
                    isError = channelInvalid(gText),
                    modifier = Modifier.weight(1f),
                )
                EcoField(
                    label = "B",
                    value = bText,
                    onValueChange = { bText = it.filter { c -> c.isDigit() }.take(3); applyRgbTexts() },
                    keyboardType = KeyboardType.Number,
                    isError = channelInvalid(bText),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                EcoGhostButton("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                EcoPrimaryButton("确定", onClick = { onConfirm(hsvColor(hue, sat, value)) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 色相环的 13 个停靠色（0°→360°，首尾同色，扫描渐变才不会在接缝处跳色） */
internal fun hueRingColors(): List<Color> = (0..360 step 30).map { hsvColor(it.toFloat(), 1f, 1f) }

/** HSV → Compose 颜色 */
internal fun hsvColor(hue: Float, sat: Float, value: Float): Color {
    val rgb = hsvToRgb(hue, sat, value)
    return Color(rgb[0], rgb[1], rgb[2])
}

/** Compose 颜色 → 0xRRGGBB */
internal fun rgbOf(color: Color): Int = color.toArgb() and 0xFFFFFF

/** 取某个通道（shift：16=红 / 8=绿 / 0=蓝） */
internal fun channelOf(color: Color, shift: Int): Int = (rgbOf(color) shr shift) and 0xFF

/** 0xRRGGBB → "#RRGGBB" */
internal fun hexOf(color: Color): String = "#%06X".format(rgbOf(color))

/** 迷你色轮（色板里的"自定义"按钮底图） */
internal fun hueWheelBrush(): Brush = Brush.sweepGradient(hueRingColors().map { it })

/** 单色 brush（和 [hueWheelBrush] 一起用时统一类型） */
internal fun solidBrush(color: Color): Brush = SolidColor(color)
