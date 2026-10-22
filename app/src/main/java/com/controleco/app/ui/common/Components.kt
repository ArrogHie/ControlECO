package com.controleco.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 收入/支出/结余汇总行；cancelledCents 非空时额外显示"已取消收支"列（退款到账合计） */
@Composable
fun SummaryRow(incomeCents: Long, expenseCents: Long, cancelledCents: Long? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SummaryColumn("支出", Money.formatYuan(expenseCents), Modifier.weight(1f))
        SummaryColumn("收入", Money.formatYuan(incomeCents), Modifier.weight(1f))
        SummaryColumn("结余", Money.formatYuan(incomeCents - expenseCents), Modifier.weight(1f))
        if (cancelledCents != null) {
            SummaryColumn("已取消", Money.formatYuan(cancelledCents), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 分类徽标：分类名首字符 + 稳定取色的淡色圆底（替代 emoji/图标，全 App 同分类同色）。
 */
@Composable
fun CategoryBadge(categoryName: String, size: Dp = 36.dp) {
    val color = categoryColor(MaterialTheme.accBook.categoryPalette, categoryName)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            categoryName.firstOrNull()?.toString() ?: "·",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

/**
 * 分类方牌（账单列表行首）：与 [CategoryBadge] 同一套"分类名 → 稳定取色"，
 * 但用圆角方块，贴近账单类应用的图标位观感。
 */
@Composable
fun CategoryTile(categoryName: String, size: Dp = 40.dp) {
    val color = categoryColor(MaterialTheme.accBook.categoryPalette, categoryName)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            categoryName.firstOrNull()?.toString() ?: "·",
            style = MaterialTheme.typography.titleSmall,
            color = color,
        )
    }
}

/** 通用下拉选择（非实验性 API 实现） */@Composable
fun DropdownSelector(
    label: String,
    options: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.firstOrNull { it.first == selectedId }?.second ?: "请选择"
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label：$selectedText", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}

/** 日期选择按钮（系统日期选择器，毫秒值为 UTC 零点） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerButton(date: LocalDate, onPick: (LocalDate) -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    show = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } },
        ) {
            DatePicker(state = state)
        }
    }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text("日期：${date.year}年${date.monthValue}月${date.dayOfMonth}日")
    }
}

fun dayOfWeekZh(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

private val tsFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun formatTimestamp(millis: Long): String = tsFormatter.format(Instant.ofEpochMilli(millis))

/**
 * 预算设置对话框（首页/个人页共用）：按月（金额）或按日（日预算）两种口径。
 * 月模式：输入每月预算金额，保存 0 = 清除预算；
 * 日模式（[dailyMode]）：输入每日预算，保存 0 = 清除；下方跟一行"≈ 月预算"读数（不写说明文字）。
 */
@Composable
fun BudgetEditDialog(
    dailyMode: Boolean,
    daysInPeriod: Int,
    currentCents: Long,
    onSave: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(if (currentCents > 0) Money.formatCents(currentCents) else "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (dailyMode) "每日预算" else "每月预算") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("预算金额") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                if (dailyMode) {
                    val typed = Money.parseCents(text.trim())
                    if (typed != null && typed > 0) {
                        Text(
                            "≈ ${Money.formatYuanGrouped(typed * daysInPeriod)}/月",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (currentCents > 0) {
                    TextButton(onClick = { onSave(0) }) { Text("清除预算") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseCents(text)
                if (cents == null || cents <= 0) {
                    error = "请输入有效金额"
                    return@TextButton
                }
                onSave(cents)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 系统"减少动画"（动画时长缩放为 0）时返回 true；ON_RESUME 刷新，供动效降级使用 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduced by remember { mutableStateOf(readReducedMotion(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = readReducedMotion(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reduced
}

private fun readReducedMotion(context: android.content.Context): Boolean = runCatching {
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}.getOrDefault(false)

/**
 * 悬浮"记一笔"胶囊（首页/账单页共用）：主色实心 + 阴影悬浮，
 * 轻微呼吸缩放提示"方便快速记账"，按压回弹；系统减少动画时保持静态。
 * 连续缩放只在 draw 阶段读取动画状态，不触发重组。
 */
@Composable
fun QuickAddPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "quickAddPress",
    )
    val pulse = rememberInfiniteTransition(label = "quickAddPulse")
    val breathe: State<Float>? = if (reduced) {
        null
    } else {
        pulse.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "quickAddBreathe",
        )
    }
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .graphicsLayer {
                val b = breathe?.value ?: 1f
                scaleX = pressScale * b
                scaleY = pressScale * b
            }
            .shadow(elevation = 8.dp, shape = shape)
            .clip(shape)
            .background(MaterialTheme.eco.blueDeep)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Text(
            "记账",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * 单行金额文本：超出可用宽度时按比例缩小字号（下限 0.55 倍），
 * 保证长数字不与圆环/卡片边界重叠；配合 K/M/B 紧凑格式，通常无需缩放。
 */
@Composable
fun AutoScaledText(
    text: String,
    style: TextStyle,
    color: Color,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxPx = with(density) { maxWidth.toPx() }
    val fitted = remember(text, style, maxPx) {
        val measured = measurer.measure(text, style, maxLines = 1, softWrap = false)
        val width = measured.size.width.toFloat()
        if (maxPx > 0f && width > maxPx) {
            style.copy(fontSize = style.fontSize * (maxPx / width).coerceAtLeast(0.55f))
        } else {
            style
        }
    }
    Text(
        text = text,
        style = fitted,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
