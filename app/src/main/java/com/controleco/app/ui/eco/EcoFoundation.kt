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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.util.Money
import kotlin.math.roundToLong
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
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
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

        // 原型的 geo 层：等高线 / 细规线 / 点阵 / 十字 / 菱形，入场 0.6s 淡入
        val a = reveal.value
        if (a <= 0.001f) return@Canvas
        val line = if (c.isDark) 0.38f else 0.58f
        val thin = 1.dp.toPx()
        val inkLine = c.blue.copy(alpha = line * 0.55f * a)
        val ruleLine = c.rule.copy(alpha = line * a)
        val stroke = Stroke(width = thin, cap = StrokeCap.Round)

        // 右上大圆与内圈
        drawCircle(
            color = ruleLine,
            radius = w * 0.52f,
            center = Offset(w * 1.10f, h * 0.10f),
            style = stroke,
        )
        drawCircle(
            color = ruleLine.copy(alpha = ruleLine.alpha * 0.7f),
            radius = w * 0.37f,
            center = Offset(w * 1.02f, h * 0.22f),
            style = stroke,
        )
        // 顶部两道等高线，越向左侧越淡
        val contour1 = Path().apply {
            moveTo(-w * 0.12f, h * 0.115f)
            cubicTo(w * 0.18f, h * 0.055f, w * 0.48f, h * 0.13f, w * 1.12f, h * 0.255f)
        }
        drawPath(contour1, inkLine, style = stroke)
        val contour2 = Path().apply {
            moveTo(-w * 0.10f, h * 0.165f)
            cubicTo(w * 0.24f, h * 0.105f, w * 0.54f, h * 0.18f, w * 1.10f, h * 0.325f)
        }
        drawPath(contour2, inkLine.copy(alpha = inkLine.alpha * 0.62f), style = stroke)
        val contour3 = Path().apply {
            moveTo(-w * 0.06f, h * 0.70f)
            cubicTo(w * 0.20f, h * 0.60f, w * 0.46f, h * 0.79f, w * 0.92f, h * 0.70f)
        }
        drawPath(contour3, inkLine.copy(alpha = inkLine.alpha * 0.42f), style = stroke)

        // 左下点阵（向中心衰减）
        val cols = 7
        val rows = 6
        val gap = 19.dp.toPx()
        val ox = -w * 0.055f
        val oy = h * 0.755f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val fade = (1f - (row / rows.toFloat()) * 0.65f) * (1f - (col / cols.toFloat()) * 0.35f)
                drawCircle(
                    color = c.blue.copy(alpha = 0.24f * a * fade),
                    radius = 1.15.dp.toPx(),
                    center = Offset(ox + col * gap, oy + row * gap),
                )
            }
        }

        // 十字 / 菱形 / 小圆点
        fun cross(cx: Float, cy: Float, r: Float, color: Color) {
            drawLine(color, Offset(cx - r, cy), Offset(cx + r, cy), thin, StrokeCap.Round)
            drawLine(color, Offset(cx, cy - r), Offset(cx, cy + r), thin, StrokeCap.Round)
        }
        cross(w * 0.075f, h * 0.635f, 7.dp.toPx(), c.amber.copy(alpha = 0.35f * a))
        cross(w * 0.80f, h * 0.405f, 8.dp.toPx(), c.blue.copy(alpha = 0.25f * a))
        val diamond = Path().apply {
            moveTo(w * 0.925f, h * 0.455f - 9.dp.toPx())
            lineTo(w * 0.925f + 13.dp.toPx(), h * 0.455f)
            lineTo(w * 0.925f, h * 0.455f + 9.dp.toPx())
            lineTo(w * 0.925f - 13.dp.toPx(), h * 0.455f)
            close()
        }
        drawPath(diamond, c.rule.copy(alpha = 0.35f * a), style = stroke)
        drawCircle(c.blue.copy(alpha = 0.45f * a), radius = 2.6.dp.toPx(), center = Offset(w * 0.155f, h * 0.225f))
        drawCircle(c.amber.copy(alpha = 0.30f * a), radius = 1.8.dp.toPx(), center = Offset(w * 0.86f, h * 0.29f))

        // 斜规线：从左到右极淡消失
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, c.blue.copy(alpha = 0.11f * a), Color.Transparent),
            ),
            start = Offset(w * 0.02f, h * 0.84f),
            end = Offset(w * 0.92f, h * 0.75f),
            strokeWidth = thin,
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

/**
 * 金额滚数：按原型 940ms easeOutQuart 从 0 滚到目标值。
 * 每次目标变化都会重新滚（与原型每次 render 都执行 animateCounters 一致）。
 */
@Composable
fun EcoCountUpMoney(
    cents: Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    widthLimit: Dp? = null,
    minSize: TextUnit = 18.sp,
    textAlign: TextAlign = TextAlign.Center,
    durationMillis: Int = 940,
    format: (Long) -> String = { Money.formatYuanGroupedFixed(it) },
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(cents) {
        anim.snapTo(0f)
        anim.animateTo(
            targetValue = cents.toFloat(),
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f),
            ),
        )
    }
    EcoAutoMoney(
        text = format(anim.value.roundToLong()),
        style = style,
        color = color,
        modifier = modifier,
        widthLimit = widthLimit,
        minSize = minSize,
        textAlign = textAlign,
    )
}

/**
 * 个人页「散落弹入」：对应原型 .profile-arrival / ecoScatteredPop。
 * 每张卡延迟出现，带 25dp 上移、0.79→1 弹簧缩放和轻微旋转。
 */
@Composable
fun EcoArrival(
    delayMillis: Int = 0,
    turn: Float = 3f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMillis.toLong())
        shown = true
    }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.79f,
        animationSpec = spring(dampingRatio = 0.56f, stiffness = 420f),
        label = "arrival-scale",
    )
    val rise by animateFloatAsState(
        targetValue = if (shown) 0f else 25f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 420f),
        label = "arrival-rise",
    )
    val turnNow by animateFloatAsState(
        targetValue = if (shown) 0f else turn,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "arrival-turn",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(190),
        label = "arrival-alpha",
    )
    Box(
        modifier.graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
            translationY = rise.dp.toPx()
            rotationZ = turnNow
        },
    ) {
        content()
    }
}

/** 章节头：标题 / 右侧说明（可选） */
@Composable
fun EcoSectionHead(
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
        Text(title, style = EcoType.SectionTitle, color = c.ink)
        // 原型：标题和右侧 aside 之间有一条极细的延长线
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.rule))
        Spacer(Modifier.width(12.dp))
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
    val glow = rememberInfiniteTransition(label = "hero-glow")
    val glowPhase by glow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "hero-glow-phase",
    )
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
            // 原型 hero 右上柔光只能用 shader 画，但本机 Skia 会把大的 radialGradient
            // 插值成硬边黑块；改为只让装饰环缓慢漂移/呼吸，避免黑块回归。
            val ringCenter = Offset(
                size.width * (0.94f + 0.02f * glowPhase),
                -size.height * (0.08f - 0.035f * glowPhase),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = size.width * 0.34f,
                center = ringCenter,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = size.width * 0.24f,
                center = ringCenter,
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
    animationDelayMillis: Int = 0,
) {
    val target = progress.coerceIn(0f, 1f)
    val drawn = remember { Animatable(0f) }
    LaunchedEffect(target) {
        drawn.snapTo(0f)
        if (animationDelayMillis > 0) kotlinx.coroutines.delay(animationDelayMillis.toLong())
        drawn.animateTo(
            target,
            tween(900, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)),
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(drawn.value)
                .height(height)
                .clip(CircleShape)
                .background(fill),
        )
    }
}

/** 顶栏 micro 状态标签（不带前置圆点） */
@Composable
fun EcoMicro(text: String) {
    val c = MaterialTheme.eco
    Text(text, style = EcoType.Micro, color = c.blue)
}
