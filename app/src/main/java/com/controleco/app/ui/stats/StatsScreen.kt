package com.controleco.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.PeriodMode
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.PeriodPickerSheet
import com.controleco.app.ui.common.periodLabel
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.transactions.BalanceCard
import com.controleco.app.ui.transactions.CoverStyleStore
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 统计页（ECO 版）：顶栏 → 周期工具条 → 结论卡 → 每日/月度支出柱状图 → 分类占比环 → 预算对比。
 *
 * 数据流保持原样（[LedgerViewModel.periodRows] / [LedgerViewModel.periodTotals] /
 * [LedgerViewModel.periodCategorySums] / [LedgerViewModel.categoryBudgets]），
 * 月视角每日一柱、年视角每月一柱；柱状图与圆环都是自绘 Canvas，不引第三方图表库。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(vm: LedgerViewModel, scrollToTopTick: Int = 0) {
    val c = MaterialTheme.eco
    val month by vm.month.collectAsStateWithLifecycle()
    val mode by vm.periodMode.collectAsStateWithLifecycle()
    val currentAccountingMonth by vm.currentAccountingMonth.collectAsStateWithLifecycle()
    val monthStartDayState by vm.monthStartDay.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val totals by vm.periodTotals.collectAsStateWithLifecycle()
    val byCategory by vm.periodCategorySums.collectAsStateWithLifecycle()
    val rows by vm.periodRows.collectAsStateWithLifecycle()
    val categoryBudgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val cardImageUri by vm.cardImageUri.collectAsStateWithLifecycle()
    val cardTransform by vm.cardTransform.collectAsStateWithLifecycle()
    val cardImage by vm.cardImage.collectAsStateWithLifecycle()
    val cardImageColor by vm.cardImageColor.collectAsStateWithLifecycle()
    val cardImageLuminance by vm.cardImageLuminance.collectAsStateWithLifecycle()

    // 数据未到位前整页不画（避免先显示 0 / 空图再跳数）
    val rowsReady = rows ?: return
    val totalsReady = totals ?: return
    val byCategoryReady = byCategory ?: return

    val expense = totalsReady.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totalsReady.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L
    val balance = income - expense
    val today = LocalDate.now()
    val monthStartDay = monthStartDayState
    val isCurrentPeriod = if (mode == PeriodMode.MONTH) {
        month == currentAccountingMonth
    } else {
        month.year == currentAccountingMonth.year
    }

    // 柱状图数据：月视角每日一根、年视角每月一根（月视角按会计月分桶）
    val bars = remember(rowsReady, month, mode, monthStartDay) {
        if (mode == PeriodMode.MONTH) {
            ChartMath.monthBars(rowsReady, month, monthStartDay)
        } else {
            ChartMath.yearBars(rowsReady, month)
        }
    }
    // 选中柱由页面持有：卡片头部的 readout 要跟着它走。
    // 原型默认选中"今天"对应的柱；看历史周期时默认选中支出最高的一天。
    val defaultBarIndex = remember(bars, isCurrentPeriod, mode, monthStartDay) {
        if (bars.isEmpty()) {
            0
        } else if (isCurrentPeriod) {
            val raw = if (mode == PeriodMode.MONTH) {
                MonthPeriod.of(month, monthStartDay).dayIndexOf(today) - 1
            } else {
                today.monthValue - 1
            }
            raw.coerceIn(0, bars.lastIndex)
        } else {
            bars.indices.maxByOrNull { bars[it].cents } ?: 0
        }
    }
    var selectedBar by remember(bars) { mutableIntStateOf(defaultBarIndex) }

    // 分类切片：实际支出（本期）+ 分类预算（年视角按 12 个月合计）
    val slices = remember(byCategoryReady, categoryBudgets, categories, mode) {
        val actual = byCategoryReady.map { it.categoryName to it.totalCents }
        val budget = categoryBudgets.entries.mapNotNull { (id, cents) ->
            val category = categories.firstOrNull { it.id == id }
            if (category == null || category.type != TxType.EXPENSE || cents <= 0L) {
                null
            } else {
                category.name to if (mode == PeriodMode.YEAR) cents * 12 else cents
            }
        }
        ChartMath.mergeSlices(actual, budget)
    }
    // 圆环选中态与配色：**与分类图标同色**（图标自定义过颜色就用它；emoji/相册图片没有颜色，
    // 才由系统从调色板里挑"离已用色最远"的），选中态点空白处取消
    val selectedState = remember(slices) { mutableStateOf<String?>(null) }
    var selectedSlice by selectedState
    val palette = MaterialTheme.accBook.categoryPalette
    val sliceColors = remember(slices, palette, categories) {
        val iconByName = categories
            .filter { it.type == TxType.EXPENSE }
            .associate { it.name to it.icon }
        sliceColorsOf(palette, slices, iconByName)
    }

    // 预算对比：分类预算（年视角 ×12）与实际支出对齐，按实际降序
    val ratioRows = remember(categoryBudgets, categories, byCategoryReady, mode) {
        buildRatioRows(categoryBudgets, categories, byCategoryReady, mode)
    }
    val ratioActual = ratioRows.sumOf { it.actualCents }
    val ratioBudget = ratioRows.sumOf { it.budgetCents }

    val monthEn = month.atDay(1)
        .format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))
        .uppercase(Locale.ENGLISH)
    val listState = rememberLazyListState()
    // 再次点击底部「统计」标签：回到顶部
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "INSIGHTS / $monthEn",
            title = "统计",
            action = {
                Text(
                    "${month.year} / ${month.monthValue.toString().padStart(2, '0')}",
                    style = EcoType.Micro,
                    color = c.quiet,
                )
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 点页面上任意空白处 = 取消圆环选中（点环 / 点图例行会被它们自己接住）
                .pointerInput(selectedState) { detectTapGestures { selectedState.value = null } },
            // 底栏悬浮在内容之上；额外留出滚动余量，让 02 的圆环能真正吸顶
            contentPadding = PaddingValues(bottom = ecoBottomContentPadding(extra = 180.dp)),
        ) {
            // 与账单页共用同一个结余封面对象（同一张背景图 / 同一份 VM 状态），随内容滚动
            item(key = "cover") {
                Box(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                    BalanceCard(
                        mode = mode,
                        balanceCents = balance,
                        incomeCents = income,
                        expenseCents = expense,
                        imagePath = cardImageUri,
                        image = cardImage,
                        transform = cardTransform,
                        placeholderColor = cardImageColor,
                        placeholderLuminance = cardImageLuminance,
                        style = CoverStyleStore.current,
                        onModeChange = { vm.setPeriodMode(it) },
                        onOpenImageSettings = {},
                        showEdit = false,
                    )
                }
            }

            item(key = "toolbar") {
                StatsToolbar(
                    month = month,
                    mode = mode,
                    onPrev = { vm.prevPeriod() },
                    onNext = { vm.nextPeriod() },
                    onOpenPicker = { showPicker = true },
                    onMode = { vm.setPeriodMode(it) },
                )
            }

            // ---- 趋势卡：柱状图（可点按/滑动读数） ----
            item(key = "trendHead") {
                EcoSectionHead(
                    title = if (mode == PeriodMode.MONTH) "每日支出" else "月度支出",
                    aside = if (mode == PeriodMode.MONTH) "DAILY" else "MONTHLY",
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
            item(key = "trend") {
                TrendCard(
                    mode = mode,
                    bars = bars,
                    expenseCents = expense,
                    selected = selectedBar,
                    onSelect = { selectedBar = it },
                )
            }

            // ---- 唯一一个圆环：留在分类列表上方，向上滚动时吸顶 ----
            stickyHeader(key = "donutSticky") {
                Column(Modifier.fillMaxWidth().background(c.paper)) {
                    EcoSectionHead(
                        title = "分类预算与占比",
                        aside = "BUDGET / ACTUAL",
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                    DonutCard(
                        expenseCents = expense,
                        slices = slices,
                        sliceColors = sliceColors,
                        selected = selectedSlice,
                        onSelect = { selectedSlice = it },
                    )
                }
            }

            // ---- 分类清单：只保留文字行，不再重复圆环 ----
            item(key = "ratio") {
                RatioCard(
                    rows = ratioRows,
                    actualTotal = ratioActual,
                    budgetTotal = ratioBudget,
                    colors = sliceColors,
                    selected = selectedSlice,
                    onSelect = { selectedSlice = it },
                )
            }
        }
    }

    if (showPicker) {
        PeriodPickerSheet(
            mode = mode,
            current = month,
            onSelectMonth = { vm.setMonth(it) },
            onSelectYear = { vm.setMonth(YearMonth.of(it, month.monthValue)) },
            onDismiss = { showPicker = false },
            currentMonth = currentAccountingMonth,
        )
    }
}

/** 唯一保留的占比圆环：空心、圆心不写数字；实际/预算金额放在环两侧。 */
@Composable
private fun DonutCard(
    expenseCents: Long,
    slices: List<CategorySlice>,
    sliceColors: Map<String, Color>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val c = MaterialTheme.eco
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        if (slices.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyNote("暂无分类数据")
            }
        } else {
            val selectedData = slices.firstOrNull { it.name == selected }
            val actualShown = selectedData?.actualCents ?: expenseCents
            val budgetTotal = slices.sumOf { it.budgetCents }
            val actualShare = if (budgetTotal > 0) actualShown * 100f / budgetTotal else 0f
            val budgetShown = selectedData?.budgetCents ?: budgetTotal
            val budgetShare = if (selectedData == null) {
                if (budgetTotal > 0) 100f else 0f
            } else {
                ChartMath.sharePercent(budgetShown, budgetTotal)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        selectedData?.name ?: "实际支出",
                        style = EcoType.Micro.copy(fontSize = 8.sp),
                        color = c.quiet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    EcoAutoMoney(
                        text = Money.formatYuanGroupedFixed(actualShown),
                        style = EcoType.CatAmount.copy(fontSize = 16.sp),
                        color = c.ink,
                        widthLimit = 84.dp,
                        minSize = 10.sp,
                        textAlign = TextAlign.Start,
                    )
                    Text(
                        "${actualShare.roundToInt()}%",
                        style = EcoType.PageNote.copy(fontSize = 9.sp),
                        color = c.muted,
                    )
                }
                CategoryDonut(
                    slices = slices,
                    totalActualCents = expenseCents,
                    colors = sliceColors,
                    selected = selected,
                    onSelect = onSelect,
                    ringSize = 150.dp,
                    showCenterLabels = false,
                    singleRing = false,
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        "预算",
                        style = EcoType.Micro.copy(fontSize = 8.sp),
                        color = c.quiet,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    EcoAutoMoney(
                        text = Money.formatYuanGroupedFixed(budgetShown),
                        style = EcoType.CatAmount.copy(fontSize = 16.sp),
                        color = c.ink,
                        widthLimit = 84.dp,
                        minSize = 10.sp,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        "${budgetShare.roundToInt()}%",
                        style = EcoType.PageNote.copy(fontSize = 9.sp),
                        color = c.muted,
                    )
                }
            }
        }
    }
}

// ---------- 工具条 ----------

/** 周期工具条：‹ 2026年10月 / MONTH › + 右侧月/年分段 */
@Composable
private fun StatsToolbar(
    month: YearMonth,
    mode: PeriodMode,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenPicker: () -> Unit,
    onMode: (PeriodMode) -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .height(45.dp)
            .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepChevron(icon = EcoIcons.Back, description = "上一${mode.label}", onClick = onPrev)
        Column(
            Modifier
                .widthIn(min = 86.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenPicker,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                periodLabel(month, mode),
                style = EcoType.RowTitle.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (mode == PeriodMode.MONTH) "ACCOUNTING MONTH" else "ACCOUNTING YEAR",
                style = EcoType.PageNote.copy(
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp,
                    lineHeight = 10.sp,
                ),
                color = c.quiet,
                maxLines = 1,
            )
        }
        StepChevron(icon = EcoIcons.Next, description = "下一${mode.label}", onClick = onNext)
        Spacer(Modifier.weight(1f))
        EcoSegmented(
            options = listOf("月", "年"),
            selectedIndex = if (mode == PeriodMode.MONTH) 0 else 1,
            onSelect = { index -> onMode(if (index == 0) PeriodMode.MONTH else PeriodMode.YEAR) },
            modifier = Modifier.width(88.dp),
            height = 30.dp,
            small = true,
        )
    }
}

@Composable
private fun StepChevron(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.eco
    Box(
        Modifier
            .size(width = 27.dp, height = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .ecoPressable(scaleTo = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = c.muted, modifier = Modifier.size(13.dp))
    }
}

// ---------- 结论卡 ----------

/** 工具条下的一行统计摘要：替代原型里没有的大结论卡，信息不丢但不再占视觉主位。 */
@Composable
private fun StatsSummaryLine(
    expenseCents: Long,
    incomeCents: Long,
    balanceCents: Long,
    dailyBalanceCents: Long,
    count: Int,
    maxExpenseCents: Long,
    refundCents: Long,
    showRefund: Boolean,
) {
    val c = MaterialTheme.eco
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("支出 ${Money.formatYuanGrouped(expenseCents)}", style = EcoType.Micro.copy(fontSize = 9.sp), color = c.ink, maxLines = 1)
            Text("收入 ${Money.formatYuanGrouped(incomeCents)}", style = EcoType.Micro.copy(fontSize = 9.sp), color = c.blue, maxLines = 1)
            Text(
                "结余 ${Money.formatYuanGrouped(balanceCents)}",
                style = EcoType.Micro.copy(fontSize = 9.sp),
                color = if (balanceCents < 0) c.clay else c.jade,
                maxLines = 1,
            )
            Text("$count 笔", style = EcoType.Micro.copy(fontSize = 9.sp), color = c.muted, maxLines = 1)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append("日均结余 ${Money.formatYuanGrouped(dailyBalanceCents)}")
                if (maxExpenseCents > 0) append(" 最大单笔 ${Money.formatYuanGrouped(maxExpenseCents)}")
                if (showRefund && refundCents > 0) append(" 退款 ${Money.formatYuanGrouped(refundCents)}")
            },
            style = EcoType.Micro.copy(fontSize = 8.sp),
            color = c.quiet,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConclusionCard(
    mode: PeriodMode,
    expenseCents: Long,
    incomeCents: Long,
    balanceCents: Long,
    dailyBalanceCents: Long,
    count: Int,
    maxExpenseCents: Long,
    refundCents: Long,
    showRefund: Boolean,
) {
    val c = MaterialTheme.eco
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(top = 10.dp),
        shape = RoundedCornerShape(19.dp),
        elevation = 10.dp,
    ) {
        Box(Modifier.fillMaxWidth()) {
            // 右上装饰环（极淡，只让大片留白不空）
            Canvas(Modifier.matchParentSize()) {
                val ring = if (c.isDark) 0.4f else 0.75f
                drawCircle(
                    color = c.rule.copy(alpha = ring),
                    radius = size.width * 0.34f,
                    center = Offset(size.width * 1.04f, -size.height * 0.16f),
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(
                    color = c.rule.copy(alpha = ring * 0.7f),
                    radius = size.width * 0.22f,
                    center = Offset(size.width * 1.04f, -size.height * 0.16f),
                    style = Stroke(1.dp.toPx()),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 15.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (mode == PeriodMode.MONTH) "本月支出" else "本年支出",
                            style = EcoType.Micro.copy(fontSize = 9.5.sp),
                            color = c.muted,
                        )
                        Spacer(Modifier.height(1.dp))
                        EcoAutoMoney(
                            text = Money.formatYuanGrouped(expenseCents),
                            style = EcoType.ConclusionAmount,
                            color = c.ink,
                            widthLimit = 210.dp,
                            minSize = 18.sp,
                            textAlign = TextAlign.Start,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            "日均 ${Money.formatYuanGrouped(dailyBalanceCents)} / day",
                            style = EcoType.PageNote.copy(fontSize = 9.5.sp),
                            color = c.quiet,
                            maxLines = 1,
                        )
                        if (showRefund && refundCents > 0L) {
                            Text(
                                "退款到账 ${Money.formatYuanGrouped(refundCents)}",
                                style = EcoType.PageNote.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                color = c.jade,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        SideAmount(label = "收入", text = Money.formatYuanGrouped(incomeCents), color = c.jade)
                        Spacer(Modifier.height(6.dp))
                        SideAmount(
                            label = "结余",
                            text = Money.formatYuanGrouped(balanceCents),
                            color = if (balanceCents < 0L) c.clay else c.jade,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$count 笔",
                        style = EcoType.PageNote.copy(fontSize = 8.5.sp, letterSpacing = 0.2.sp),
                        color = c.quiet,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "最大单笔 ${Money.formatYuanGrouped(maxExpenseCents)}",
                        style = EcoType.PageNote.copy(fontSize = 8.5.sp, letterSpacing = 0.2.sp),
                        color = c.quiet,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SideAmount(label: String, text: String, color: Color) {
    val c = MaterialTheme.eco
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = EcoType.PageNote.copy(fontSize = 8.5.sp), color = c.quiet)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = EcoType.RowTrail.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
        )
    }
}

// ---------- 趋势卡 ----------

@Composable
private fun TrendCard(
    mode: PeriodMode,
    bars: List<BarPoint>,
    expenseCents: Long,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val c = MaterialTheme.eco
    val selectedBar = bars.getOrNull(selected)
    // 读数：选中柱给"日期 / 金额"，没选中就是本期合计
    val readoutLabel = selectedBar?.tip
        ?.substringBefore("  ")
        ?.takeIf { it.isNotBlank() }
        ?: "合计"
    val readoutAmount = Money.formatYuanGrouped(selectedBar?.cents ?: expenseCents)
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "支出节奏",
                    style = EcoType.SectionTitle.copy(fontSize = 12.5.sp),
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    readoutLabel,
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
                    color = c.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    readoutAmount,
                    style = EcoType.CatAmount.copy(fontSize = 15.sp),
                    color = c.ink,
                    maxLines = 1,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, end = 13.dp, top = 9.dp, bottom = 12.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
                Spacer(Modifier.height(11.dp))
                if (expenseCents == 0L) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(155.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (mode == PeriodMode.MONTH) "本月还没有支出" else "本年还没有支出",
                                style = EcoType.EmptyTitle.copy(fontSize = 12.sp),
                                color = c.muted,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "记一笔后这里会出现柱状图",
                                style = EcoType.PageNote.copy(fontSize = 9.sp),
                                color = c.quiet,
                            )
                        }
                    }
                } else {
                    ExpenseBarChart(
                        bars = bars,
                        selected = selected,
                        onSelect = onSelect,
                        height = 200.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ---------- 预算对比卡 ----------

@Composable
private fun RatioCard(
    rows: List<BudgetRatioRow>,
    actualTotal: Long,
    budgetTotal: Long,
    colors: Map<String, Color>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val c = MaterialTheme.eco
    EcoPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(top = 14.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 13.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("预算对比", style = EcoType.SectionTitle, color = c.ink)
                Spacer(Modifier.weight(1f))
                Text(
                    Money.formatYuanGrouped(actualTotal),
                    style = EcoType.CatAmount.copy(fontSize = 13.5.sp),
                    color = c.ink,
                    maxLines = 1,
                )
                Text(
                    " / ${Money.formatYuanGrouped(budgetTotal)}",
                    style = EcoType.RowTrail.copy(fontSize = 10.sp),
                    color = c.quiet,
                    maxLines = 1,
                )
            }
            if (rows.isEmpty()) {
                Text(
                    "暂无分类预算",
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
                    color = c.quiet,
                    modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
                )
            } else {
                Spacer(Modifier.height(10.dp))
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
                    }
                    RatioListRow(
                        row = row,
                        color = colors[row.name] ?: c.quiet,
                        actualShare = ratioPercent(row.actualCents, actualTotal),
                        budgetShare = ratioPercent(row.budgetCents, budgetTotal),
                        selected = row.name == selected,
                        dim = selected != null && row.name != selected,
                        onClick = { onSelect(row.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RatioSide(
    caption: String,
    amountCents: Long,
    percentText: String,
    percentColor: Color,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    Column(
        modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            caption,
            style = EcoType.PageNote.copy(fontSize = 8.sp, letterSpacing = 0.2.sp),
            color = c.muted,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        EcoAutoMoney(
            text = Money.formatYuanGrouped(amountCents),
            style = EcoType.CatAmount.copy(fontSize = 14.sp),
            color = c.ink,
            minSize = 9.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            percentText,
            style = EcoType.RowTrail.copy(fontSize = 11.5.sp),
            color = percentColor,
            maxLines = 1,
        )
    }
}

/** 预算对比清单的一行：左侧 9dp 竖色条 + 名称 / 实际 / 占比，第二行 预算 / 预算占比 */
@Composable
private fun RatioListRow(
    row: BudgetRatioRow,
    color: Color,
    actualShare: Float,
    budgetShare: Float,
    selected: Boolean,
    dim: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // 选中别的分类时其余行降到 46%（与原型 .dim 一致）
            .graphicsLayer { alpha = if (dim) 0.46f else 1f }
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.blueWash.copy(alpha = if (c.isDark) 0.5f else 0.85f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(9.dp)
                .fillMaxHeight()
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = EcoType.RowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    Money.formatYuanGrouped(row.actualCents),
                    style = EcoType.CatAmount.copy(fontSize = 14.5.sp),
                    color = c.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${actualShare.roundToInt()}%",
                    style = EcoType.RowTrail.copy(fontSize = 11.5.sp),
                    color = c.muted,
                    modifier = Modifier.width(34.dp),
                    textAlign = TextAlign.End,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    Money.formatYuanGrouped(row.budgetCents),
                    style = EcoType.RowTrail.copy(fontSize = 10.5.sp),
                    color = c.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${budgetShare.roundToInt()}%",
                    style = EcoType.PageNote.copy(fontSize = 10.sp),
                    color = c.quiet,
                    modifier = Modifier.width(34.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

// ---------- 小工具 ----------

/**
 * 预算对比的行数据：只取设了预算的**支出**分类，年视角预算 ×12，
 * 实际金额按分类名从本期分类合计里取（与圆环同一份数据），按实际降序。
 */
internal fun buildRatioRows(
    categoryBudgets: Map<Long, Long>,
    categories: List<CategoryEntity>,
    byCategory: List<CategorySumRow>,
    mode: PeriodMode,
): List<BudgetRatioRow> {
    if (categoryBudgets.isEmpty()) return emptyList()
    val actualByName = byCategory.associate { it.categoryName to it.totalCents }
    val byId = categories.associateBy { it.id }
    val multiplier = if (mode == PeriodMode.YEAR) 12L else 1L
    return categoryBudgets.entries
        .mapNotNull { (id, cents) ->
            val category = byId[id] ?: return@mapNotNull null
            if (category.type != TxType.EXPENSE || cents <= 0L) return@mapNotNull null
            BudgetRatioRow(
                name = category.name,
                actualCents = actualByName[category.name] ?: 0L,
                budgetCents = cents * multiplier,
            )
        }
        .sortedByDescending { it.actualCents }
}

/** 占比（0..100）；分母 ≤ 0 时返回 0 */
private fun ratioPercent(part: Long, total: Long): Float =
    if (total <= 0L) 0f else part * 100f / total

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = EcoType.PageNote.copy(fontSize = 9.5.sp, lineHeight = 15.sp),
        color = MaterialTheme.eco.quiet,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 18.dp),
    )
}
