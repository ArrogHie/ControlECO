package com.controleco.app.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.ui.common.AutoScaledText
import com.controleco.app.ui.eco.LocalAnimationsEnabled
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 统计页图表（全部自绘，不引第三方库），配色/字体统一走 ECO 设计系统：
 * - [ExpenseBarChart] 柱状图：点按 / 横向拖动选柱，选中柱换蓝→玉渐变并弹一颗玉色点；
 * - [CategoryDonut] 单环占比图：中心显示当前分类（或总支出）的金额与占比；
 * - [DonutLegend] 图例：7dp 圆点 + 分类名 + 占比，点一行 = 在圆环上选中该分类；
 * - [RatioRing] 预算对比双环：外环 = 分类预算构成，内环 = 分类实际构成，中心是"已用百分比"；
 * - [RatioKey] 双环图例（外环 预算 / 内环 实际）。
 */

/** 预算对比的一行：分类实际支出 vs 该分类预算（年视角的预算已由调用方 ×12） */
data class BudgetRatioRow(
    val name: String,
    val actualCents: Long,
    val budgetCents: Long,
)

// ---------- 柱状图 ----------

/** 柱状图：整宽均分，没有纵轴留白（数值改由卡头 readout 显示） */
private fun barPitch(widthPx: Float, count: Int): Float =
    if (count <= 0) 1f else widthPx / count

private fun barIndexAt(x: Float, widthPx: Float, count: Int): Int =
    if (count <= 0) -1 else (x / barPitch(widthPx, count)).toInt().coerceIn(0, count - 1)

/** 上圆下直的柱子：圆角 5 / 5 / 1 / 1，与原型 `.chart-bar` 一致 */
private fun barPath(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radiusTop: Float,
    radiusBottom: Float,
): Path {
    val rt = min(radiusTop, min(width / 2f, height))
    val rb = min(radiusBottom, min(width / 2f, height))
    return Path().apply {
        moveTo(left + rb, top + height)
        lineTo(left + width - rb, top + height)
        quadraticBezierTo(left + width, top + height, left + width, top + height - rb)
        lineTo(left + width, top + rt)
        quadraticBezierTo(left + width, top, left + width - rt, top)
        lineTo(left + rt, top)
        quadraticBezierTo(left, top, left, top + rt)
        lineTo(left, top + height - rb)
        quadraticBezierTo(left, top + height, left + rb, top + height)
        close()
    }
}

/**
 * 可交互柱状图：点按选中一柱，横向拖动时读数跟手（纵向滑动不拦截，页面照常滚动）。
 * 选中态由调用方持有（卡片头部要显示"日期 + 金额"），这里只负责画和上报。
 * [selected] = -1 表示未选中：柱子全亮；选中后其余柱降透明，选中柱带玉色点和投影。
 */
@Composable
fun ExpenseBarChart(
    bars: List<BarPoint>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 155.dp,
) {
    val c = MaterialTheme.eco
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(c.quiet) {
        TextStyle(color = c.quiet, fontSize = 7.5.sp, fontWeight = FontWeight.Medium)
    }
    // 文字量测缓存：拖动手势每帧都会重画，避免重复量同一串数字
    val labelCache = remember(measurer, labelStyle) { mutableMapOf<String, TextLayoutResult>() }

    // 柱子从轴线向上生长，31 根柱子依次错开 6ms（原型 .chart-bar 的 scaleY 入场，每次进入重播）
    val grow = remember { Animatable(0f) }
    val animationsEnabled = LocalAnimationsEnabled.current
    LaunchedEffect(bars, animationsEnabled) {
        if (!animationsEnabled) {
            // 关闭美化动效：柱子直接到位
            grow.snapTo(1f)
            return@LaunchedEffect
        }
        grow.snapTo(0f)
        grow.animateTo(1f, tween(820, easing = FastOutSlowInEasing))
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(bars) {
                detectTapGestures { offset ->
                    val index = barIndexAt(offset.x, size.width.toFloat(), bars.size)
                    if (index >= 0) onSelect(index)
                }
            }
            .pointerInput(bars) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val index = barIndexAt(offset.x, size.width.toFloat(), bars.size)
                        if (index >= 0) onSelect(index)
                    },
                    onHorizontalDrag = { change, _ ->
                        val index = barIndexAt(change.position.x, size.width.toFloat(), bars.size)
                        if (index >= 0) onSelect(index)
                        change.consume()
                    },
                )
            },
    ) {
        if (bars.isEmpty()) return@Canvas
        val count = bars.size
        val pitch = barPitch(size.width, count)
        // 柱间距固定 2dp、柱宽上限 13dp（原型 .chart-bar：flex + gap 2 + max-width 13）
        val gap = 2.dp.toPx()
        val barWidth = (pitch - gap).coerceIn(2.dp.toPx(), 9.dp.toPx())
        val labelBand = 18.dp.toPx()
        val axisY = size.height - labelBand
        val plotTop = 6.dp.toPx()
        val plotH = (axisY - plotTop).coerceAtLeast(1f)
        val maxYuan = ChartMath.niceCeil(bars.maxOf { it.cents / 100f })

        fun yAt(cents: Long): Float =
            axisY - (cents / 100f / maxYuan).coerceIn(0f, 1f) * plotH

        // 1) 细网格（上限 / 2/3 / 1/3），原型是实线而不是虚线
        listOf(1f, 2f / 3f, 1f / 3f).forEach { fraction ->
            val y = axisY - fraction * plotH
            drawLine(
                color = c.rule.copy(alpha = 0.55f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // 2) 柱子：有支出画渐变柱，零支出画一根贴着轴线的短刻度（保持"每一天都在"）
        bars.forEachIndexed { index, bar ->
            val left = index * pitch + (pitch - barWidth) / 2f
            val isSelected = index == selected
            val dimmed = selected >= 0 && !isSelected
            if (bar.cents > 0L) {
                val progress = ((grow.value - index * 0.006f) / 0.82f).coerceIn(0f, 1f)
                val fullTop = yAt(bar.cents)
                val top = axisY - (axisY - fullTop) * progress
                val barHeight = (axisY - top).coerceAtLeast(2.dp.toPx())
                if (isSelected) {
                    // 柔和投影：同一根柱子下移 2dp 先画一层低透明度
                    drawPath(
                        path = barPath(
                            left = left,
                            top = top + 2.dp.toPx(),
                            width = barWidth,
                            height = barHeight,
                            radiusTop = 5.dp.toPx(),
                            radiusBottom = 1.dp.toPx(),
                        ),
                        color = c.blueDeep.copy(alpha = 0.22f * progress),
                    )
                }
                val alpha = if (dimmed) 0.3f else 1f
                drawPath(
                    path = barPath(
                        left = left,
                        top = top,
                        width = barWidth,
                        height = barHeight,
                        radiusTop = 5.dp.toPx(),
                        radiusBottom = 1.dp.toPx(),
                    ),
                    brush = Brush.verticalGradient(
                        colors = if (isSelected) {
                            // 选中柱：强调色深→亮（原来深蓝→玉绿，会让柱子下半截永远发绿）
                            listOf(c.blueDeep, c.blueHi)
                        } else {
                            listOf(
                                c.blue.copy(alpha = 0.67f * alpha),
                                c.blueHi.copy(alpha = 0.92f * alpha),
                            )
                        },
                        startY = top,
                        endY = axisY,
                    ),
                )
                if (isSelected && progress > 0.92f) {
                    // 柱顶指示点用强调色，不掺语义绿
                    val dotX = left + barWidth / 2f
                    val dotY = (fullTop - 9.dp.toPx()).coerceAtLeast(4.dp.toPx())
                    drawCircle(c.blueSoft, radius = 5.dp.toPx(), center = Offset(dotX, dotY))
                    drawCircle(c.blue, radius = 2.5.dp.toPx(), center = Offset(dotX, dotY))
                }
            } else {
                drawLine(
                    color = c.ruleStrong.copy(alpha = 0.5f),
                    start = Offset(left, axisY - 1.dp.toPx()),
                    end = Offset(left + barWidth, axisY - 1.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // 3) 轴线 + X 轴小刻度（ChartMath 已经算好"哪几根标数字"）
        drawLine(c.rule, Offset(0f, axisY), Offset(size.width, axisY), strokeWidth = 1.dp.toPx())
        bars.forEachIndexed { index, bar ->
            val label = bar.axisLabel ?: return@forEachIndexed
            val layout = labelCache.getOrPut(label) { measurer.measure(label, labelStyle) }
            val centerX = index * pitch + pitch / 2f
            val x = (centerX - layout.size.width / 2f)
                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            drawText(layout, topLeft = Offset(x, axisY + 4.dp.toPx()))
        }
    }
}

// ---------- 占比环 ----------

/**
 * 分类占比环：外环 = 分类预算构成，内环 = 实际支出构成（同一分类同色，便于上下对照）。
 * 中心读的是"当前选中分类（没选中就是总支出）"的金额与占比；点环 / 横向滑动选分类，
 * 点空白（圆心）取消。选中后其余扇区降到 22% 混合度，颜色不会糊成一片。
 */
@Composable
fun CategoryDonut(
    slices: List<CategorySlice>,
    totalActualCents: Long,
    colors: Map<String, Color>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    ringSize: Dp = 171.dp,
    showCenterLabels: Boolean = true,
    /** 统计页只要一个环：外环按预算构成，不再叠内环 */
    singleRing: Boolean = false,
) {
    val c = MaterialTheme.eco
    val selectedSlice = slices.firstOrNull { it.name == selected }
    val shownCents = selectedSlice?.actualCents ?: totalActualCents
    val share = if (selectedSlice == null) 100f else ChartMath.sharePercent(shownCents, totalActualCents)

    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(slices) {
                    detectTapGestures { pos ->
                        onSelect(donutGeometry(size.width.toFloat(), this).sliceAt(pos, slices))
                    }
                }
                .pointerInput(slices) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            onSelect(donutGeometry(size.width.toFloat(), this).sliceAt(pos, slices))
                        },
                        onHorizontalDrag = { change, _ ->
                            onSelect(donutGeometry(size.width.toFloat(), this).sliceAt(change.position, slices))
                            change.consume()
                        },
                    )
                },
        ) {
            val geo = donutGeometry(size.width, this)
            val center = Offset(size.width / 2f, size.height / 2f)

            // 底轨：单环只画外环，双环模式两环都画
            drawCircle(c.track.copy(alpha = 0.55f), radius = geo.rOuter, center = center, style = Stroke(geo.strokeOuter))
            if (!singleRing) {
                drawCircle(c.track.copy(alpha = 0.55f), radius = geo.rInner, center = center, style = Stroke(geo.strokeInner))
            }

            fun ring(radius: Float, stroke: Float, alpha: Float, value: (CategorySlice) -> Long, total: Long) {
                if (total <= 0L) return
                // 相邻切片不留缝：每片两端各多画一点，后画的盖住接缝；
                // 颜色先与底轨混成实色再画——半透明叠加会在重叠处加深出深色细线
                val arcs = slices.count { value(it) > 0L }
                val bleed = if (arcs > 1) 0.5f else 0f
                var start = -90f
                slices.forEach { slice ->
                    val v = value(slice)
                    val sweep = v * 360f / total
                    if (v > 0L) {
                        val isOn = selected == null || selected == slice.name
                        val base = colors[slice.name] ?: c.quiet
                        val mix = if (isOn) alpha else alpha * 0.24f
                        drawArc(
                            color = lerp(c.track, base, mix),
                            startAngle = start - bleed / 2f,
                            sweepAngle = sweep + bleed,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(
                                width = if (selected == slice.name) stroke + 3.dp.toPx() else stroke,
                            ),
                        )
                    }
                    start += sweep
                }
            }
            ring(geo.rOuter, geo.strokeOuter, alpha = 0.62f, value = { it.budgetCents }, total = slices.sumOf { it.budgetCents })
            if (!singleRing) {
                ring(geo.rInner, geo.strokeInner, alpha = 1f, value = { it.actualCents }, total = slices.sumOf { it.actualCents })
            }
        }

        // 圆心读数：分类名（未选中 = 总支出）/ 金额 / 占比。
        // 统计页按原型把读数放到环左右两侧，这里可以关掉圆心文字，露出空心。
        if (showCenterLabels) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = selectedSlice?.name ?: "总支出",
                style = EcoType.Micro.copy(fontSize = 8.sp),
                color = c.quiet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(ringSize * 0.46f),
            )
            Spacer(Modifier.height(2.dp))
            AutoScaledText(
                text = Money.formatYuanGrouped(shownCents),
                style = EcoType.CatAmount.copy(fontSize = 16.sp),
                color = c.ink,
                maxWidth = ringSize * 0.46f,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                "${share.roundToInt()}%",
                style = EcoType.PageNote.copy(fontSize = 7.5.sp),
                color = c.muted,
            )
        }
    }
}

/**
 * 占比图例：行首 7dp 圆点 + 分类名 9.5 / 占比 8.5。
 * 点一行 = 在圆环上选中该分类（同一份选中态，两边同时高亮）；别项降到 34% 不透明度。
 */
@Composable
fun DonutLegend(
    slices: List<CategorySlice>,
    totalActualCents: Long,
    colors: Map<String, Color>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    Column(modifier.fillMaxWidth()) {
        slices.forEach { slice ->
            val isOn = slice.name == selected
            val dim = selected != null && !isOn
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (dim) 0.34f else 1f }
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isOn) c.blueWash.copy(alpha = if (c.isDark) 0.55f else 0.9f) else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(slice.name) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors[slice.name] ?: c.quiet),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    slice.name,
                    style = EcoType.RowSub.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${ChartMath.sharePercent(slice.actualCents, totalActualCents).roundToInt()}%",
                    style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                    color = c.muted,
                )
            }
        }
    }
}

// ---------- 预算对比双环 ----------

/**
 * 预算对比双环：外环 = 各分类预算占"总预算"的份额（整圈），内环 = 各分类实际支出占"总预算"的份额
 * （没花完就留缺口，一眼看出整体用了几成）。中心大字是整体已用百分比，超预算转陶土色。
 */
@Composable
fun RatioRing(
    rows: List<BudgetRatioRow>,
    actualTotalCents: Long,
    budgetTotalCents: Long,
    colors: Map<String, Color>,
    modifier: Modifier = Modifier,
    ringSize: Dp = 174.dp,
) {
    val c = MaterialTheme.eco
    val palette = MaterialTheme.accBook.categoryPalette
    val percent = if (budgetTotalCents > 0L) actualTotalCents * 100f / budgetTotalCents else 0f
    val over = percent > 100f
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeOuter = 13.dp.toPx()
            val gap = 7.dp.toPx()
            val strokeInner = 10.dp.toPx()
            val rOuter = size.minDimension / 2f - strokeOuter / 2f
            val rInner = rOuter - strokeOuter / 2f - gap - strokeInner / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawCircle(c.track.copy(alpha = 0.5f), radius = rOuter, center = center, style = Stroke(strokeOuter))
            drawCircle(c.track.copy(alpha = 0.5f), radius = rInner, center = center, style = Stroke(strokeInner))
            if (budgetTotalCents <= 0L) return@Canvas

            fun arcs(radius: Float, stroke: Float, alpha: Float, total: Long, value: (BudgetRatioRow) -> Long) {
                if (total <= 0L) return
                var start = -90f
                rows.forEach { row ->
                    val v = value(row)
                    val sweep = v * 360f / total
                    if (v > 0L) {
                        val tint = colors[row.name] ?: categoryColor(palette, row.name)
                        drawArc(
                            color = lerp(c.track, tint, alpha),
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(stroke),
                        )
                    }
                    start += sweep
                }
            }
            arcs(rOuter, strokeOuter, alpha = 0.42f, total = budgetTotalCents, value = { it.budgetCents })
            // 内环以"预算"为分母：没花完就留缺口；超预算时以实际为分母（画满且中心转陶土色）
            arcs(
                rInner,
                strokeInner,
                alpha = 1f,
                total = maxOf(budgetTotalCents, actualTotalCents),
                value = { it.actualCents },
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${percent.roundToInt()}%",
                style = EcoType.CatAmount.copy(fontSize = 19.sp),
                color = if (over) c.clay else c.ink,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                if (over) "已超预算" else "已用",
                style = EcoType.PageNote.copy(fontSize = 7.5.sp),
                color = if (over) c.clay else c.quiet,
            )
        }
    }
}

/** 双环图例：外环（空心）预算 / 内环（实心）实际 */
@Composable
fun RatioKey(modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatioKeyItem(label = "外环 预算占比", color = c.blue, filled = false)
        RatioKeyItem(label = "内环 实际占比", color = c.jade, filled = true)
    }
}

@Composable
private fun RatioKeyItem(label: String, color: Color, filled: Boolean) {
    val c = MaterialTheme.eco
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .then(if (filled) Modifier.background(color) else Modifier.border(1.5.dp, color, CircleShape)),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, style = EcoType.PageNote.copy(fontSize = 8.5.sp), color = c.muted)
    }
}

// ---------- 圆环几何（绘制与手势命中共用） ----------

/** 饼图几何：绘制与命中共用（半径按画布最小边推导，与绘制逻辑一致） */
internal class DonutGeometry(
    private val center: Offset,
    val rOuter: Float,
    val rInner: Float,
    val strokeOuter: Float,
    val strokeInner: Float,
) {
    /** 触点落在哪一环的哪个分类上（外环优先；都不中返回 null 表示清除选中） */
    fun sliceAt(pos: Offset, slices: List<CategorySlice>): String? {
        val dx = pos.x - center.x
        val dy = pos.y - center.y
        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val onOuter = kotlin.math.abs(dist - rOuter) <= strokeOuter * 0.9f
        val onInner = kotlin.math.abs(dist - rInner) <= strokeInner * 0.9f
        if (!onOuter && !onInner) return null
        var deg = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (deg < 0f) deg += 360f
        val list = if (onOuter) slices.filter { it.budgetCents > 0L } else slices.filter { it.actualCents > 0L }
        val total = list.sumOf { if (onOuter) it.budgetCents else it.actualCents }
        if (total <= 0L) return null
        var acc = 0f
        list.forEach { s ->
            val sweep = (if (onOuter) s.budgetCents else s.actualCents) * 360f / total
            if (deg >= acc && deg < acc + sweep) return s.name
            acc += sweep
        }
        return list.lastOrNull()?.name
    }
}

internal fun donutGeometry(widthPx: Float, density: Density): DonutGeometry {
    val strokeOuter = with(density) { 20.dp.toPx() }
    val strokeInner = with(density) { 16.dp.toPx() }
    val ringGap = with(density) { 8.dp.toPx() }
    val rOuter = widthPx / 2f - strokeOuter / 2f
    val rInner = rOuter - strokeOuter / 2f - ringGap - strokeInner / 2f
    return DonutGeometry(Offset(widthPx / 2f, widthPx / 2f), rOuter, rInner, strokeOuter, strokeInner)
}
