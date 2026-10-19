package com.controleco.app.ui.transactions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.data.CardImage
import com.controleco.app.data.CardTransform
import com.controleco.app.data.PeriodMode
import com.controleco.app.ui.common.relativeLuminance
import com.controleco.app.ui.common.scrimAlphaFor
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.util.Money
import kotlin.math.max

/**
 * 账单页顶部结余封面（对应草稿 `.bill-cover`）：
 *  - 高 150、圆角 22；默认夜色渐变 + 星点/光球/蚀刻环，可换相册图片；
 *  - 左上「月/年」周期切换，右上背景设置；
 *  - 大字结余 = 收入 − 支出，右侧两行收入/支出；
 *  - 图片按亮度自动选浅字/深字，并按 WCAG 叠遮罩。
 */
@Composable
fun BalanceCard(
    mode: PeriodMode,
    balanceCents: Long,
    incomeCents: Long,
    expenseCents: Long,
    imagePath: String?,
    image: CardImage?,
    transform: CardTransform = CardTransform.DEFAULT,
    placeholderColor: Int? = null,
    placeholderLuminance: Float? = null,
    style: String = CoverStyleStore.NIGHT,
    onModeChange: (PeriodMode) -> Unit,
    onOpenImageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawable = remember(image?.full, image?.preview) { image?.asImage() }
    val customImage = image != null || !imagePath.isNullOrBlank()
    val placeholder = if (drawable == null && customImage) placeholderColor?.let { Color(it) } else null
    val effectiveLuminance = image?.luminance ?: placeholderLuminance ?: 0.9f
    val mist = !customImage && style == CoverStyleStore.MIST
    val clay = !customImage && style == CoverStyleStore.CLAY
    val lightText = if (!customImage) !mist else effectiveLuminance < 0.34f
    val ink = when {
        mist -> Color(0xFF183740)
        lightText -> Color(0xFFF1FAF5)
        else -> Color(0xFF152F36)
    }
    val sub = when {
        mist -> Color(0xFF4D6A6D)
        lightText -> Color(0xFFB6D8D1)
        else -> Color(0xFF4D6A6D)
    }
    val income = when {
        mist -> Color(0xFF1C7B62)
        lightText -> Color(0xFF93E3C2)
        else -> Color(0xFF1C7B62)
    }
    val expense = when {
        mist -> Color(0xFFB7503F)
        lightText -> Color(0xFFF0A18F)
        else -> Color(0xFFB7503F)
    }
    val scrimAlpha = if (!customImage || (drawable == null && placeholder == null)) {
        0f
    } else {
        maxOf(
            scrimAlphaFor(effectiveLuminance, relativeLuminance(ink)),
            scrimAlphaFor(effectiveLuminance, relativeLuminance(income)),
            scrimAlphaFor(effectiveLuminance, relativeLuminance(expense)),
        )
    }
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = Color(0x33103539),
                spotColor = Color(0x4D103539),
            )
            .clip(shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            when {
                drawable != null -> {
                    val t = transform.normalized()
                    val scale = max(size.width / drawable.width, size.height / drawable.height) * t.scale
                    val w = drawable.width * scale
                    val h = drawable.height * scale
                    withTransform({
                        translate(t.offsetX * size.width, t.offsetY * size.height)
                        rotate(t.rotation, pivot = center)
                    }) {
                        drawImage(
                            image = drawable,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(drawable.width, drawable.height),
                            dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
                            dstSize = IntSize(w.toInt(), h.toInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                    // 左侧压暗，保证结余读数始终可读
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color(0x8C08161C), Color(0x2208161C), Color.Transparent),
                        ),
                    )
                    if (scrimAlpha > 0.01f) {
                        drawRect((if (lightText) Color.Black else Color.White).copy(alpha = scrimAlpha * 0.55f))
                    }
                }
                placeholder != null -> {
                    drawRect(placeholder)
                    drawRect(Color.Black.copy(alpha = 0.30f))
                }
                customImage -> Unit
                else -> {
                    if (mist) {
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFE7ECE5), Color(0xFFF1F0E7), Color(0xFFDDE5DF)),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height),
                            ),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0x808CABAA), Color.Transparent),
                                center = Offset(size.width * 0.78f, size.height * 1.12f),
                                radius = size.width * 0.78f,
                            ),
                            radius = size.width * 0.78f,
                            center = Offset(size.width * 0.78f, size.height * 1.12f),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0x66A7C7C0), Color.Transparent),
                                center = Offset(size.width * 0.66f, size.height * 0.02f),
                                radius = size.width * 0.5f,
                            ),
                            radius = size.width * 0.5f,
                            center = Offset(size.width * 0.66f, size.height * 0.02f),
                        )
                        drawCircle(
                            color = Color(0xFF183740).copy(alpha = 0.08f),
                            radius = size.height * 0.58f,
                            center = Offset(size.width * 0.96f, size.height * 1.10f),
                            style = Stroke(
                                width = 1f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 9f)),
                            ),
                        )
                        drawCircle(
                            color = Color(0xFF183740).copy(alpha = 0.05f),
                            radius = size.height * 0.42f,
                            center = Offset(size.width * 0.96f, size.height * 1.10f),
                            style = Stroke(1f),
                        )
                    } else if (clay) {
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF5E2F2A), Color(0xFF7C3C32), Color(0xFF8E5241)),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height),
                            ),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.09f),
                            radius = size.height * 0.64f,
                            center = Offset(size.width * 0.96f, size.height * 1.10f),
                            style = Stroke(
                                width = 1f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 9f)),
                            ),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0x33FFE1C4), Color.Transparent),
                                center = Offset(size.width * 0.82f, size.height * 0.02f),
                                radius = size.width * 0.55f,
                            ),
                            radius = size.width * 0.55f,
                            center = Offset(size.width * 0.82f, size.height * 0.02f),
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.08f),
                            start = Offset(0f, size.height * 0.7f),
                            end = Offset(size.width, size.height * 0.05f),
                            strokeWidth = size.height * 0.45f,
                            cap = StrokeCap.Round,
                        )
                    } else {
                    // 夜色底：125° 深青渐变
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF102F3B), Color(0xFF135565), Color(0xFF225867)),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height),
                        ),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x33A9D9D0), Color.Transparent),
                            center = Offset(size.width * 0.80f, size.height * 0.05f),
                            radius = size.width * 0.55f,
                        ),
                        radius = size.width * 0.55f,
                        center = Offset(size.width * 0.80f, size.height * 0.05f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x26F2C9A0), Color.Transparent),
                            center = Offset(size.width * 0.12f, size.height * 0.95f),
                            radius = size.width * 0.42f,
                        ),
                        radius = size.width * 0.42f,
                        center = Offset(size.width * 0.12f, size.height * 0.95f),
                    )
                    // 星点两层
                    val star = Color.White.copy(alpha = 0.34f)
                    listOf(
                        Offset(size.width * 0.13f, size.height * 0.37f),
                        Offset(size.width * 0.20f, size.height * 0.24f),
                        Offset(size.width * 0.10f, size.height * 0.62f),
                        Offset(size.width * 0.30f, size.height * 0.14f),
                        Offset(size.width * 0.72f, size.height * 0.20f),
                        Offset(size.width * 0.86f, size.height * 0.45f),
                    ).forEachIndexed { i, p ->
                        drawCircle(star, radius = if (i % 2 == 0) 1.2f else 2f, center = p)
                    }
                    // 光球
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xCC9BE0D4), Color(0x223F8E88), Color.Transparent),
                            center = Offset(size.width * 0.88f, size.height * 0.08f),
                            radius = size.height * 0.78f,
                        ),
                        radius = size.height * 0.72f,
                        center = Offset(size.width * 0.88f, size.height * 0.08f),
                    )
                    // 蚀刻环
                    drawCircle(
                        color = Color.White.copy(alpha = 0.10f),
                        radius = size.height * 0.68f,
                        center = Offset(size.width * 0.97f, size.height * 1.22f),
                        style = Stroke(
                            width = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 9f)),
                        ),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.05f),
                        radius = size.height * 0.52f,
                        center = Offset(size.width * 0.97f, size.height * 1.22f),
                        style = Stroke(1f),
                    )
                    // 斜向高光
                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                        ),
                        start = Offset(0f, size.height * 0.7f),
                        end = Offset(size.width, size.height * 0.05f),
                        strokeWidth = size.height * 0.55f,
                        cap = StrokeCap.Round,
                    )
                    }
                }
            }
        }

        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White.copy(alpha = if (lightText) 0.13f else 0.42f))
                        .border(
                            1.dp,
                            Color.White.copy(alpha = if (lightText) 0.16f else 0.55f),
                            RoundedCornerShape(9.dp),
                        )
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("月" to PeriodMode.MONTH, "年" to PeriodMode.YEAR).forEach { (label, m) ->
                        val on = mode == m
                        Box(
                            Modifier
                                .height(22.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (on) Color.White.copy(alpha = if (lightText) 0.20f else 0.78f) else Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onModeChange(m) }
                                .padding(horizontal = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = EcoType.Pill.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = if (on) ink else sub,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(29.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (lightText) 0.12f else 0.55f))
                        .border(1.dp, Color.White.copy(alpha = if (lightText) 0.15f else 0.65f), CircleShape)
                        .clickable(onClick = onOpenImageSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        EcoIcons.Edit,
                        contentDescription = "更换结余卡背景",
                        tint = sub,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (mode == PeriodMode.MONTH) "本月结余" else "本年结余",
                        style = EcoType.Pill.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
                        color = sub,
                    )
                    Spacer(Modifier.height(1.dp))
                    EcoAutoMoney(
                        text = Money.formatYuanGroupedFixed(balanceCents),
                        style = EcoType.CoverMoney,
                        color = ink,
                        widthLimit = 206.dp,
                        minSize = 19.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(bottom = 3.dp),
                ) {
                    Text(
                        Money.formatSignedPlain(incomeCents),
                        style = EcoType.Pill.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                        color = income,
                    )
                    Text(
                        Money.formatSignedPlain(-expenseCents),
                        style = EcoType.Pill.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                        color = expense,
                    )
                }
            }
        }
    }
}
