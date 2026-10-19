package com.controleco.app.ui.eco

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.LocalIndication
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.theme.EcoColors
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 纸面背景：纸色 + 两团极淡径向光。整页共用一张，位于所有内容之下；
 * 状态栏区域也铺满，配合 edge-to-edge 让顶部没有色带。
 */
@Composable
fun EcoBackdrop(modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Canvas(modifier.fillMaxSize()) {
        drawRect(c.paper)
        val w = size.width
        val h = size.height
        // 右上偏青、左下偏玉：与原型 .screen 的两层 radial-gradient 对应
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c.blue.copy(alpha = if (c.isDark) 0.075f else 0.05f), Color.Transparent),
                center = Offset(w * 0.91f, h * 0.14f),
                radius = w * 0.72f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c.jade.copy(alpha = if (c.isDark) 0.055f else 0.04f), Color.Transparent),
                center = Offset(w * 0.01f, h * 0.76f),
                radius = w * 0.78f,
            ),
        )
        // 极细装饰线：不抢内容，只让大块纸面不空
        drawCircle(
            color = c.rule.copy(alpha = if (c.isDark) 0.22f else 0.34f),
            radius = w * 0.36f,
            center = Offset(w * 1.03f, h * 0.19f),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = c.rule.copy(alpha = if (c.isDark) 0.18f else 0.28f),
            radius = w * 0.30f,
            center = Offset(-w * 0.08f, h * 0.86f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** 陶瓷卡片：surface 面 + 1dp 描边 + 四段阴影 + 顶部中间玉色发丝线 */
fun Modifier.ecoPane(
    colors: EcoColors,
    shape: Shape = RoundedCornerShape(19.dp),
    elevation: Dp = 10.dp,
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = colors.ink.copy(alpha = if (colors.isDark) 0.34f else 0.10f),
        spotColor = colors.ink.copy(alpha = if (colors.isDark) 0.42f else 0.16f),
    )
    .clip(shape)
    .background(
        Brush.linearGradient(
            colors = listOf(colors.surface, lerp(colors.surface, colors.blueSoft, 0.13f)),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
    )
    .border(1.dp, colors.rule, shape)

@Composable
fun EcoPane(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(19.dp),
    elevation: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .ecoPane(c, shape, elevation)
            .drawWithContent {
                drawContent()
                val inset = 21.dp.toPx()
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, c.jade.copy(alpha = 0.19f), Color.Transparent),
                        startX = inset,
                        endX = size.width - inset,
                    ),
                    start = Offset(inset, 0.5f),
                    end = Offset(size.width - inset, 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(contentPadding),
    ) {
        content()
    }
}

@Composable
fun EcoHairline(modifier: Modifier = Modifier, color: Color = MaterialTheme.eco.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 行间分隔：与原型一致，左右缩进 16dp */
@Composable
fun EcoRowDivider(inset: Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(MaterialTheme.eco.rule),
    )
}

/** 可按压缩放（替代默认水波纹，接近原型的 scale(.97) 手感） */
@Composable
fun Modifier.ecoPressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.97f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleTo else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "eco-press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * 大金额自适应：先按设计字号测量，超宽依次降到 88% / 78% / 68%，不换行。
 * 用法：`EcoAutoMoney(text, EcoType.MeterMoney, maxWidth = 220.dp)`
 */
@Composable
fun EcoAutoMoney(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    widthLimit: Dp? = null,
    minSize: TextUnit = 18.sp,
    textAlign: TextAlign = TextAlign.Center,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val available = widthLimit ?: maxWidth
        val availablePx = if (available == Dp.Infinity || available.value <= 0f) {
            with(density) { 320.dp.toPx() }
        } else {
            with(density) { available.toPx() }
        }
        val fitted = remember(text, style, availablePx) {
            var size = style.fontSize
            var guard = 0
            while (guard++ < 12) {
                val result = measurer.measure(
                    text = AnnotatedString(text),
                    style = style.copy(fontSize = size),
                    maxLines = 1,
                    softWrap = false,
                )
                if (result.size.width <= availablePx || size.value <= minSize.value) break
                size = (size.value * 0.88f).coerceAtLeast(minSize.value).sp
            }
            size
        }
        Text(
            text = text,
            style = style.copy(fontSize = fitted),
            color = color,
            maxLines = 1,
            softWrap = false,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 章节头：01 / 标题 / 右侧说明（可选） */
@Composable
fun EcoSectionHead(
    number: String? = null,
    title: String,
    aside: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(
                Modifier
                    .size(19.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(c.blue),
                contentAlignment = Alignment.Center,
            ) {
                Text(number, style = EcoType.SectionNo, color = Color.White)
            }
            Spacer(Modifier.width(9.dp))
        }
        Text(title, style = EcoType.SectionTitle, color = c.ink)
        Spacer(Modifier.weight(1f))
        if (aside != null) Text(aside, style = EcoType.SectionAside, color = c.quiet)
        trailing?.invoke()
    }
}

@Composable
fun EcoPageLead(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = EcoType.PageLead,
        color = MaterialTheme.eco.muted,
        modifier = modifier.padding(horizontal = 3.dp, vertical = 5.dp),
    )
}

@Composable
fun EcoPageNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = EcoType.PageNote,
        color = MaterialTheme.eco.quiet,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
fun EcoEmpty(
    title: String,
    body: String? = null,
    icon: ImageVector = EcoIcons.Spark,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(c.blueSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.blue, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = EcoType.EmptyTitle, color = c.ink, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = EcoType.EmptyBody, color = c.muted, textAlign = TextAlign.Center)
        }
    }
}

/** 深色 hero 卡（预算/封面/关于等）：渐变 + 右上装饰环 */
@Composable
fun EcoHeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp, 33.dp, 12.dp, 33.dp),
    colors: List<Color>? = null,
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    val gradient = colors ?: listOf(c.heroA, c.heroB, c.heroC)
    Box(
        modifier
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x55103539),
                spotColor = Color(0x66103539),
            )
            .clip(shape)
            .background(Brush.linearGradient(gradient, start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = size.width * 0.34f,
                center = Offset(size.width * 0.94f, -size.height * 0.08f),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = size.width * 0.24f,
                center = Offset(size.width * 0.94f, -size.height * 0.08f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        content()
    }
}

/** sheet 顶部的把手（38 x 4，track 色） */
@Composable
fun EcoHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 9.dp, bottom = 5.dp)
            .width(38.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.eco.track),
    )
}

/** 细进度条（分类/时间/解析） */
@Composable
fun EcoProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 9.dp,
    track: Color = MaterialTheme.eco.surface3,
    fill: Brush = Brush.horizontalGradient(
        listOf(MaterialTheme.eco.blue.copy(alpha = 0.55f), MaterialTheme.eco.blue),
    ),
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(fill),
        )
    }
}

/** 小圆点 + 文字的状态标签（顶栏 micro 用） */
@Composable
fun EcoMicro(text: String, dotColor: Color = MaterialTheme.eco.jade) {
    val c = MaterialTheme.eco
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        Text(text, style = EcoType.Micro, color = c.blue)
    }
}
