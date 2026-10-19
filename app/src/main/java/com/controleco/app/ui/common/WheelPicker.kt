package com.controleco.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.controleco.app.ui.theme.accBook
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 展开滚轮的触发方式 */
enum class WheelTrigger { TAP, LONG_PRESS, TAP_AND_LONG_PRESS, DRAG }

// ---------- 圆柱投影与滚动物理（纯函数，见 WheelPickerMathTest） ----------

/** 圆柱半径 = 3 行高：一行对应 1/3 弧度，接近机械密码锁的观感 */
internal const val WHEEL_RADIUS_ROWS = 3f

/** 透视相机距离（单位 = 半径的倍数）：越小近大远小越明显 */
internal const val WHEEL_CAMERA_FACTOR = 3f

/** 渐隐行数：距离中心超过该行数后透明度触底 */
internal const val WHEEL_FADE_ROWS = 2.6f

/** 惯性滑动的时间常数（秒）：指数衰减的总位移 = 初速度 × τ */
internal const val WHEEL_FLING_TAU = 0.25f

/** 常态只绘制"当前 ±1.5 行"：更远的行不露头（常态不超过行高） */
internal const val WHEEL_MAX_ABS_D = 1.5f

/** 边缘渐隐长度（行高的倍数）：内容在贴到上/下边缘前先淡出 */
internal const val WHEEL_EDGE_FADE_ROWS = 0.6f

/** 项差值 d（可含小数）→ 圆柱角位移（弧度）。一行 = 行高 / 半径 */
internal fun wheelPhi(d: Float, itemHeightPx: Float, radiusPx: Float = itemHeightPx * WHEEL_RADIUS_ROWS): Float =
    d * itemHeightPx / radiusPx

/** 角位移 → 屏幕纵向位置（正弦投影：中心 1:1、两端按圆柱压缩） */
internal fun wheelOffsetY(phi: Float, radiusPx: Float): Float = radiusPx * sin(phi)

/** 角位移 → 透视缩放（余弦决定深度，近大远小） */
internal fun wheelScale(phi: Float, radiusPx: Float): Float {
    val depth = radiusPx * (1f - cos(phi))
    return (WHEEL_CAMERA_FACTOR * radiusPx) / (WHEEL_CAMERA_FACTOR * radiusPx + depth)
}

/** 项差值 → 透明度（中心 1，向两端渐隐） */
internal fun wheelAlpha(d: Float): Float =
    (1f - 0.55f * (abs(d) / WHEEL_FADE_ROWS)).coerceIn(0.15f, 1f)

/**
 * 惯性初速度夹取（项/秒）：指数衰减的总位移 = v × τ，
 * 把速度夹到"刚好滑到边界"以内，避免冲过 1 日 / 月末再弹回来。
 */
internal fun clampFlingVelocity(
    vItemsPerSec: Float,
    posItems: Float,
    lastIndex: Int,
    tauSeconds: Float = WHEEL_FLING_TAU,
): Float {
    if (vItemsPerSec > 0f) {
        val maxDistance = (lastIndex - posItems).coerceAtLeast(0f)
        return minOf(vItemsPerSec, maxDistance / tauSeconds)
    }
    if (vItemsPerSec < 0f) return maxOf(vItemsPerSec, -posItems / tauSeconds)
    return 0f
}

/** 点击位置 → 项下标（投影反解：y = R·sin φ 反推 φ，再换算项差） */
internal fun wheelIndexAt(
    yFromCenter: Float,
    posItems: Float,
    itemHeightPx: Float,
    radiusPx: Float = itemHeightPx * WHEEL_RADIUS_ROWS,
): Int {
    val ratio = (yFromCenter / radiusPx).coerceIn(-1f, 1f)
    val d = asin(ratio) * radiusPx / itemHeightPx
    return (posItems + d).roundToInt()
}

/**
 * 立体滚轮选择器（机械密码锁观感）。
 *
 * 视觉：**单 Canvas 解析绘制**——每项按圆柱投影求角位移 φ，纵向位置 = R·sin φ
 * （中心 1:1、两端自然压缩），透视缩放由 cos 决定，透明度向两端渐隐、并在贴到边缘前淡出。
 * 不逐项建 composable、不逐帧分配对象：文本排版一次缓存，draw 阶段只做算术与 drawText。
 *
 * 交互：
 * - 默认收起：只 [rows] 行、**不可滑动**（在它上面拖 = 正常滚动页面）；
 * - [WheelTrigger.TAP] / [TAP_AND_LONG_PRESS]：**点击** → 原地浮出滚轮卡（点其他区域 / 返回键提交并收起；
 *   点某一行直接选中）；
 * - [WheelTrigger.LONG_PRESS] / [TAP_AND_LONG_PRESS]：**长按** → 不松手直接滑动，抬手后按速度惯性滚动、
 *   吸附到最近一项并收起；
 * - 拖动与手指 1:1 跟手（中心项按弧长线性对应）；动画期新手势直接接管（Animatable 互斥）；
 * - **两种触发的浮窗完全同一个**：同一张卡、同一尺寸、同一落点——卡的中心就是常态那一格的中心
 *   （尺寸也与常态那一格一致，所以行高与相邻行都不动）；卡面只留左右两条边、从中间向上下两端渐隐消失，
 *   投影只落在右侧；常态内容在浮窗期间隐去、收起后原位复现。
 *
 * @param itemHeight 单行高度（行高即弧长步长）
 * @param rows 常态可见行数（奇数，居中项为当前值）——**常态不超过行高**：这一格就是 [rows] 行高
 * @param expandedRows 浮出卡可见行数（奇数）——浮出允许超过行高（常态那几行之外再多露两行）
 * @param updateWhileScrolling true = 滚动过程中就回调选中值；false = 仅在提交（吸附/收起）时回调
 * @param label 无障碍描述前缀（如"每月第一天"）
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    itemHeight: Dp = 28.dp,
    rows: Int = 3,
    expandedRows: Int = 5,
    trigger: WheelTrigger = WheelTrigger.TAP_AND_LONG_PRESS,
    updateWhileScrolling: Boolean = false,
) {
    if (items.isEmpty()) return
    val lastIndex = items.lastIndex
    val safeIndex = selectedIndex.coerceIn(0, lastIndex)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val metrics = rememberWheelMetrics(items)
    val layouts = metrics.layouts
    val slotHeight = itemHeight * rows
    val cardHeight = itemHeight * expandedRows
    // 卡宽 = 最宽条目文本 + 一个汉字宽（两边各留半个字）
    val cardWidth = with(density) { (metrics.maxTextWidthPx + metrics.charWidthPx).toDp() }

    // 唯一事实源：连续位置（项，可含小数）。渲染在 draw 阶段读取 → 拖动/动画不触发重组。
    val pos = remember { Animatable(safeIndex.toFloat()) }
    val controller = remember(itemHeightPx, lastIndex) {
        WheelDragController(scope, pos, lastIndex, itemHeightPx)
    }

    var popupMounted by remember { mutableStateOf(false) }
    var inlineMounted by remember { mutableStateOf(false) }
    var popupToken by remember { mutableIntStateOf(0) }
    var inlineToken by remember { mutableIntStateOf(0) }
    val popupProgress = remember { Animatable(0f) }
    val inlineProgress = remember { Animatable(0f) }

    val expandedNow = popupMounted || inlineMounted
    // 收起（两个展开态都不在）时把位置对齐到外部值
    LaunchedEffect(safeIndex, expandedNow) {
        if (!expandedNow) pos.snapTo(safeIndex.toFloat())
    }

    // 高亮项（画在 draw 阶段读取；滚动中实时变化，不触发重组）
    val highlight by remember {
        derivedStateOf { pos.value.roundToInt().coerceIn(0, lastIndex) }
    }
    LaunchedEffect(updateWhileScrolling, lastIndex) {
        if (!updateWhileScrolling) return@LaunchedEffect
        var reported = pos.value.roundToInt().coerceIn(0, lastIndex)
        snapshotFlow { pos.value.roundToInt().coerceIn(0, lastIndex) }
            .distinctUntilChanged()
            .collect { v ->
                if (v != reported) {
                    reported = v
                    onSelected(v)
                }
            }
    }

    // 手势回调会跨越多次重组：必须用 rememberUpdatedState 读**最新**的外部值。
    // 直接闭包捕获 safeIndex 会拿到回调创建时的旧值——"把日子改回原来那个"会被误判为"没变"而丢设置
    val latestIndex by rememberUpdatedState(safeIndex)
    val latestOnSelected by rememberUpdatedState(onSelected)

    fun commitNow() {
        val snapped = pos.value.roundToInt().coerceIn(0, lastIndex)
        if (snapped != latestIndex) latestOnSelected(snapped)
    }

    fun openPopup() {
        popupToken += 1
        popupMounted = true
        scope.launch { popupProgress.animateTo(1f, tween(190, easing = FastOutSlowInEasing)) }
    }

    fun closePopup() {
        val token = popupToken
        scope.launch {
            popupProgress.animateTo(0f, tween(150))
            if (popupToken == token) {
                popupMounted = false
                commitNow()
            }
        }
    }

    fun openInline() {
        inlineToken += 1
        inlineMounted = true
        scope.launch { inlineProgress.animateTo(1f, tween(170, easing = FastOutSlowInEasing)) }
    }

    /** 抬手：先让惯性+吸附走完，再提交并收回（"手指离开屏幕后设定并回到默认"） */
    fun closeInlineAfterSettle() {
        val token = inlineToken
        scope.launch {
            controller.settleAsync().join()
            commitNow()
            inlineProgress.animateTo(0f, tween(140))
            if (inlineToken == token) inlineMounted = false
        }
    }

    // ---- 常态：微缩滚轮（浮窗期间整格隐去，收起后原位复现） ----
    Box(
        modifier = modifier
            .height(slotHeight)
            .width(96.dp)
            .then(
                if (label != null) {
                    Modifier.semantics { contentDescription = "$label，当前 ${items[safeIndex]}" }
                } else {
                    Modifier
                },
            )
            .then(
                if (trigger != WheelTrigger.LONG_PRESS && trigger != WheelTrigger.DRAG) {
                    Modifier.clickable(enabled = !inlineMounted) { openPopup() }
                } else {
                    Modifier
                },
            )
            .then(
                when (trigger) {
                    WheelTrigger.DRAG -> Modifier.pointerInput(lastIndex) {
                        detectDragGestures(
                            onDragStart = { controller.onStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                controller.onMove(change)
                                controller.drag(dragAmount.y)
                            },
                            onDragEnd = { controller.settleAsync() },
                            onDragCancel = { controller.settleAsync() },
                        )
                    }
                    WheelTrigger.TAP -> Modifier
                    else -> Modifier.pointerInput(lastIndex) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                controller.onStart()
                                openInline()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                controller.onMove(change)
                                controller.drag(dragAmount.y)
                            },
                            onDragEnd = { if (inlineMounted) closeInlineAfterSettle() },
                            onDragCancel = { if (inlineMounted) closeInlineAfterSettle() },
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        WheelCanvas(
            items = items,
            layouts = layouts,
            pos = { pos.value },
            itemHeightPx = itemHeightPx,
            highlightProvider = { highlight },
            // 常态行数由 rows 决定：rows=3 时仍是原来 3 行；rows=5 的底部弹层要露 5 行
            maxAbsD = rows / 2f + 0.6f,
            // 浮窗在场时整格隐去（卡下不重影、入场半透明期不穿帮）
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (popupMounted || inlineMounted) 0f else 1f
                },
        )

        // ---- 展开态一：点击 → 浮层（自带"点其他区域收起"） ----
        if (popupMounted) {
            Popup(
                popupPositionProvider = WheelCardPosition,
                onDismissRequest = { closePopup() },
                properties = PopupProperties(focusable = true),
            ) {
                WheelCard(
                    items = items,
                    layouts = layouts,
                    pos = { pos.value },
                    itemHeightPx = itemHeightPx,
                    highlightProvider = { highlight },
                    maxAbsD = expandedRows / 2f + 0.6f,
                    progress = { popupProgress.value },
                    modifier = Modifier.width(cardWidth).height(cardHeight),
                    onDragStart = controller::onStart,
                    onDrag = { change, deltaY ->
                        controller.onMove(change)
                        controller.drag(deltaY)
                    },
                    onDragEnd = { controller.settleAsync() },
                    onTapRow = { index ->
                        scope.launch {
                            pos.snapTo(index.coerceIn(0, lastIndex).toFloat())
                            closePopup()
                        }
                    },
                )
            }
        }

        // ---- 展开态二：长按 → 行内悬浮（不新开窗口，手势不被打断；与浮层同一张卡、同一落点） ----
        if (inlineMounted) {
            WheelCard(
                items = items,
                layouts = layouts,
                pos = { pos.value },
                itemHeightPx = itemHeightPx,
                highlightProvider = { highlight },
                maxAbsD = expandedRows / 2f + 0.6f,
                progress = { inlineProgress.value },
                modifier = Modifier
                    .wrapContentSize(Alignment.Center, unbounded = true)
                    .width(cardWidth)
                    .height(cardHeight),
            )
        }
    }
}

// ---------- 绘制 ----------

/** 文本排版与尺寸度量：排版一次缓存；最宽文本与"一个字宽"用于收紧卡宽（两边各留半个字） */
private class WheelTextMetrics(
    val layouts: List<Pair<TextLayoutResult, TextLayoutResult>>,
    val maxTextWidthPx: Float,
    /** 一个汉字的宽度（取"月"）：卡宽留白以它为准 */
    val charWidthPx: Float,
)

@Composable
private fun rememberWheelMetrics(items: List<String>): WheelTextMetrics {
    val style = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer(cacheSize = (items.size * 3).coerceAtLeast(16))
    return remember(items, style) {
        val bold = style.copy(fontWeight = FontWeight.SemiBold)
        val layouts = items.map { label -> measurer.measure(label, style) to measurer.measure(label, bold) }
        WheelTextMetrics(
            layouts = layouts,
            maxTextWidthPx = layouts.maxOf { it.first.size.width }.toFloat(),
            charWidthPx = measurer.measure("月", style).size.width.toFloat(),
        )
    }
}

/**
 * 滚轮本体：单 Canvas 解析绘制（圆柱投影）。无子 composable；
 * [pos] / [highlightProvider] 是 lambda，在 draw 阶段读取 → 拖动与动画不触发重组。
 */
@Composable
private fun WheelCanvas(
    items: List<String>,
    layouts: List<Pair<TextLayoutResult, TextLayoutResult>>,
    pos: () -> Float,
    itemHeightPx: Float,
    highlightProvider: () -> Int,
    maxAbsD: Float,
    modifier: Modifier = Modifier,
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val normalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bandColor = MaterialTheme.colorScheme.surfaceContainer
    val radiusPx = itemHeightPx * WHEEL_RADIUS_ROWS
    val bandRadius = with(LocalDensity.current) { 9.dp.toPx() }
    val corner = remember(bandRadius) { CornerRadius(bandRadius, bandRadius) }

    Canvas(modifier) {
        val p = pos()
        val highlight = highlightProvider()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val rowH = itemHeightPx
        val base = floor(p).toInt()
        val maxD = ceil(maxAbsD).toInt()
        val halfH = size.height / 2f
        val edgeFadeLen = rowH * WHEEL_EDGE_FADE_ROWS
        // 中心选中带（轻量背景高亮）
        drawRoundRect(
            color = bandColor,
            topLeft = Offset(0f, centerY - rowH / 2f),
            size = Size(size.width, rowH),
            cornerRadius = corner,
        )
        // 只画当前 ±1.5 行；行距走圆柱投影，越靠边越压缩、越淡
        for (d in -maxD..maxD) {
            val index = base + d
            if (index < 0 || index > items.lastIndex) continue
            val dd = index - p
            if (abs(dd) > maxAbsD) continue
            val phi = wheelPhi(dd, rowH, radiusPx)
            val y = centerY + wheelOffsetY(phi, radiusPx)
            // 贴到上/下边缘前先淡出：内容不会在边缘被硬切
            val edge = ((halfH - abs(y - centerY)) / edgeFadeLen).coerceIn(0f, 1f)
            if (edge <= 0f) continue
            val scale = wheelScale(phi, radiusPx)
            val alpha = wheelAlpha(dd) * edge
            val layout = if (index == highlight) layouts[index].second else layouts[index].first
            val w = layout.size.width
            val h = layout.size.height
            // 旋转的视觉：纵向按 cos φ 压扁（贴向圆柱侧面），再叠透视缩放
            val squash = cos(phi).coerceAtLeast(0.35f)
            withTransform({
                translate(centerX, y)
                scale(scale, scale * squash, pivot = Offset.Zero)
                translate(-w / 2f, -h / 2f)
            }) {
                drawText(
                    textLayoutResult = layout,
                    color = if (index == highlight) selectedColor else normalColor,
                    topLeft = Offset.Zero,
                    alpha = alpha,
                )
            }
        }
    }
}

/**
 * 浮窗滚轮卡（点击与长按共用同一张）：卡面 + 滚轮 + 入场/退场动画（scale/alpha 由 [progress] 驱动）。
 *
 * 卡面按需求做成"只留左右两侧的边"：背景与描边都用**纵向渐变**——中间实、上下两端透明，
 * 于是既没有上/下边缘、也没有上下两端的横线，左右两条边从中心向两端渐隐消失；
 * 投影只落在右侧（把卡面矩形右移几份、透明度递减地叠在卡身下层，同样带纵向渐隐）。
 */
@Composable
private fun WheelCard(
    items: List<String>,
    layouts: List<Pair<TextLayoutResult, TextLayoutResult>>,
    pos: () -> Float,
    itemHeightPx: Float,
    highlightProvider: () -> Int,
    maxAbsD: Float,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((PointerInputChange, Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onTapRow: ((Int) -> Unit)? = null,
) {
    val surface = MaterialTheme.colorScheme.surface
    val hairline = MaterialTheme.accBook.hairline
    val clear = Color.Transparent
    val surfaceFade = remember(surface) {
        Brush.verticalGradient(listOf(clear, surface, surface, clear))
    }
    val hairlineFade = remember(hairline) {
        Brush.verticalGradient(listOf(clear, hairline, hairline, clear))
    }
    val shadowFade = remember {
        Brush.verticalGradient(
            listOf(clear, Color.Black.copy(alpha = 0.075f), Color.Black.copy(alpha = 0.075f), clear),
        )
    }
    var card = modifier
        .graphicsLayer {
            val pr = progress()
            transformOrigin = TransformOrigin.Center
            scaleX = 0.90f + 0.10f * pr
            scaleY = 0.90f + 0.10f * pr
            alpha = pr
        }
        // 投影只落在右侧：卡面形状向右平移几份、透明度递减地叠在卡身下层
        .drawBehind {
            for (i in 0 until WHEEL_SHADOW_STEPS) {
                val dx = (i + 1) * WHEEL_SHADOW_STEP.toPx()
                val alpha = 1f - i / WHEEL_SHADOW_STEPS.toFloat()
                translate(left = dx) {
                    drawRect(brush = shadowFade, alpha = alpha, size = size)
                }
            }
        }
        .background(surfaceFade)
        .border(1.dp, hairlineFade, RectangleShape)
    if (onDrag != null) {
        card = card.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { onDragStart?.invoke() },
                onDragEnd = { onDragEnd?.invoke() },
                onDragCancel = { onDragEnd?.invoke() },
                onVerticalDrag = { change, delta ->
                    change.consume()
                    onDrag(change, delta)
                },
            )
        }
    }
    if (onTapRow != null) {
        card = card.pointerInput(Unit, itemHeightPx) {
            detectTapGestures { offset: Offset ->
                onTapRow(
                    wheelIndexAt(offset.y - size.height / 2f, pos(), itemHeightPx)
                        .coerceIn(0, items.lastIndex),
                )
            }
        }
    }
    Box(card, contentAlignment = Alignment.Center) {
        WheelCanvas(
            items = items,
            layouts = layouts,
            pos = pos,
            itemHeightPx = itemHeightPx,
            highlightProvider = highlightProvider,
            maxAbsD = maxAbsD,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 右侧投影的份数与步长（每份右移一点、透明度递减） */
private const val WHEEL_SHADOW_STEPS = 5
private val WHEEL_SHADOW_STEP = 1.2.dp

/**
 * 浮层卡片的位置：**中心对齐锚点（常态那一格）的中心**，并夹在窗口内。
 * 用 [PopupPositionProvider] 直接给出落点（与锚点同一坐标系），不再自己算窗口内偏移——
 * 点击与长按因此落在同一处，且不会受弹层窗口坐标系差异影响。
 */
private object WheelCardPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = 4
        val x = anchorBounds.center.x - popupContentSize.width / 2f
        val y = anchorBounds.center.y - popupContentSize.height / 2f
        return IntOffset(
            x.roundToInt().coerceIn(
                margin,
                (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin),
            ),
            y.roundToInt().coerceIn(
                margin,
                (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin),
            ),
        )
    }
}

// ---------- 手势与惯性 ----------

/**
 * 拖动 + 惯性 + 吸附的统一驱动（常态长按拖动与浮窗内拖动共用）。
 *
 * 惯性用**手写指数衰减**（每帧 v *= e^{-dt/τ}）而不是现成的 exponentialDecay：
 * 总位移严格等于 v·τ，恰好和 [clampFlingVelocity] 用同一个公式，边界可证不越界；
 * 落点再用轻弹簧吸附到最近一项（damping 0.85，不弹跳）。动画期新手势直接接管。
 */
private class WheelDragController(
    private val scope: CoroutineScope,
    private val pos: Animatable<Float, AnimationVector1D>,
    private val lastIndex: Int,
    private val itemHeightPx: Float,
) {
    private val tracker = VelocityTracker()
    private var settleJob: Job? = null

    /** 新手势开始：立刻接管（取消未完成的惯性/吸附） */
    fun onStart() {
        settleJob?.cancel()
        settleJob = null
        tracker.resetTracking()
    }

    fun onMove(change: PointerInputChange) {
        tracker.addPosition(change.uptimeMillis, change.position)
    }

    /** 跟手：项位移 = 手指位移 / 行高（中心项按弧长 1:1），夹在 [0, lastIndex] */
    fun drag(deltaY: Float) {
        settleJob?.cancel()
        settleJob = null
        scope.launch {
            pos.snapTo((pos.value - deltaY / itemHeightPx).coerceIn(0f, lastIndex.toFloat()))
        }
    }

    /** 抬手：惯性滚动 + 吸附；返回的任务可 join（长按路径等它结束再收起） */
    fun settleAsync(): Job {
        settleJob?.cancel()
        return scope.launch { settleAndWait() }.also { settleJob = it }
    }

    suspend fun settleAndWait() {
        val v0 = clampFlingVelocity(
            -tracker.calculateVelocity().y / itemHeightPx,
            pos.value,
            lastIndex,
        )
        if (abs(v0) > 0.5f) {
            var v = v0
            var lastNanos = withFrameNanos { it }
            while (abs(v) > 0.05f) {
                val now = withFrameNanos { it }
                val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastNanos = now
                pos.snapTo((pos.value + v * dt).coerceIn(0f, lastIndex.toFloat()))
                v *= exp(-dt / WHEEL_FLING_TAU)
            }
        }
        val target = pos.value.roundToInt().coerceIn(0, lastIndex)
        pos.animateTo(target.toFloat(), spring(dampingRatio = 0.85f, stiffness = 420f))
    }
}
