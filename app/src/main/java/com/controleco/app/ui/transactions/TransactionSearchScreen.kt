package com.controleco.app.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.periodLabel
import com.controleco.app.ui.eco.EcoFilterStrip
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 搜索页（`search` 路由，从账单页右上角放大镜进入）。
 *
 * 条件：商户关键字 + 金额（精确 / 范围）+ 类型（全部/支出/收入）+ 分类多选，四项 AND。
 * 范围就是**当前会计周期**（与账单页同一个 `periodRows`），所以不跨月/跨年，也没有额外的
 * 时间筛选——要找别的月份的账单，先回账单页切周期。
 *
 * 结果按天分组，行样式与账单页完全一致（共用 [DayHeader] / [BillRow]），点行进编辑页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionSearchScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onEditTransaction: (Long) -> Unit,
) {
    val rows by vm.periodRows.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val mode by vm.periodMode.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    // 数据未就绪先不画（与账单页同一约定：避免先空后跳）
    val rowsReady = rows ?: return

    var keyword by rememberSaveable { mutableStateOf("") }
    var amountMode by rememberSaveable { mutableStateOf(AmountMode.EXACT) }
    var exactText by rememberSaveable { mutableStateOf("") }
    var minText by rememberSaveable { mutableStateOf("") }
    var maxText by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf(TxFilter.ALL) }
    // 分类多选与分页深度都要跨「进编辑页再返回」保留
    var categoryIds by rememberSaveable(stateSaver = CategoryIdSetSaver) { mutableStateOf(emptySet<Long>()) }
    var sort by rememberSaveable { mutableStateOf(BillSort.TIME_DESC) }
    var loadedLimit by rememberSaveable { mutableIntStateOf(FIRST_PAGE_ROWS) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val query = remember(keyword, amountMode, exactText, minText, maxText, typeFilter, categoryIds) {
        BillSearchQuery(
            keyword = keyword,
            amount = amountQueryOf(amountMode, exactText, minText, maxText),
            type = typeFilter,
            categoryIds = categoryIds,
        )
    }
    val results = remember(rowsReady, query) { searchBills(rowsReady, query) }
    val sorted = remember(results, sort) { sortBills(results, sort) }
    // 时间排序：按天分组；金额排序：平铺 + 行内补日期（与账单页同一口径）
    val byDay = sort.groupedByDay
    val grouped: Map<LocalDate, List<TransactionRow>> = remember(sorted, byDay) {
        if (byDay) groupRowsByDay(sorted) else emptyMap()
    }
    val allDays = grouped.keys.toList()
    val (shownDays, _) = remember(allDays, grouped, loadedLimit) {
        pageDays(allDays, grouped, loadedLimit)
    }
    val shownRows = remember(sorted, loadedLimit, byDay) {
        if (byDay) emptyList() else sorted.take(loadedLimit)
    }
    val hasMore = if (byDay) shownDays.size < allDays.size else shownRows.size < sorted.size

    val loadMore by rememberUpdatedState(hasMore)
    LaunchedEffect(listState, sorted) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, totalItems) ->
                if (loadMore && totalItems > 0 && lastVisible >= totalItems - 3) {
                    loadedLimit += PAGE_ROWS
                }
            }
    }

    // 条件或排序真的变了才回顶并重置分页：本 effect 在「从编辑页返回」时也会重跑，
    // 无条件 scrollToItem(0) 会把刚恢复好的滚动位置抹掉（同账单页，见 BillListMemory.kt）。
    val queryKey = remember(query, sort) { billSearchKey(query, sort) }
    var lastQueryKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(queryKey) {
        val previous = lastQueryKey
        lastQueryKey = queryKey
        if (!shouldResetBillList(previous, queryKey)) return@LaunchedEffect
        loadedLimit = FIRST_PAGE_ROWS
        listState.scrollToItem(0)
    }

    val focusRequester = remember { FocusRequester() }
    // 只在首次进入本页时自动聚焦（进编辑页再返回会重新进入组合，不能每次都弹键盘）
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoFocused) {
            autoFocused = true
            focusRequester.requestFocus()
        }
    }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val periodText = periodLabel(month, mode)

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            // 键盘可见时 imePadding 已经把底部让出来了，再叠 navigationBarsPadding 会多出一截（同快速记账/编辑页）
            .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding()),
    ) {
        EcoTopBar(micro = "SEARCH / $periodText", title = "搜索", onBack = onBack)

        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(4.dp))
            SearchField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = "搜索商户名",
                leadingIcon = EcoIcons.Search,
                focusRequester = focusRequester,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EcoSegmented(
                    options = AmountMode.entries.map { it.label },
                    selectedIndex = amountMode.ordinal,
                    onSelect = { amountMode = AmountMode.entries[it] },
                    modifier = Modifier.width(96.dp),
                    height = 40.dp,
                    small = true,
                )
                when (amountMode) {
                    AmountMode.EXACT -> SearchField(
                        value = exactText,
                        onValueChange = { exactText = it },
                        placeholder = "金额",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    AmountMode.RANGE -> {
                        SearchField(
                            value = minText,
                            onValueChange = { minText = it },
                            placeholder = "最小",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                        SearchField(
                            value = maxText,
                            onValueChange = { maxText = it },
                            placeholder = "最大",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EcoFilterStrip(
                    options = TxFilter.entries.map { it.label },
                    selectedIndex = typeFilter.ordinal,
                    onSelect = { typeFilter = TxFilter.entries[it] },
                )
                Spacer(Modifier.weight(1f))
                ToolbarIconAction(
                    icon = EcoIcons.Sort,
                    description = if (sort != BillSort.TIME_DESC) "排序方式（非默认）" else "排序方式",
                    active = sort != BillSort.TIME_DESC,
                    onClick = { showSortSheet = true },
                )
                Spacer(Modifier.width(6.dp))
                CategoryFilterButton(
                    active = categoryIds.isNotEmpty(),
                    count = categoryIds.size,
                    onClick = { showFilterSheet = true },
                )
            }
            if (query.isActive) {
                Text(
                    "${results.size} 笔",
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
                    color = MaterialTheme.eco.quiet,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                )
            } else {
                Spacer(Modifier.height(6.dp))
            }
        }

        when {
            // 整个周期都没有记录时，先说清“不是搜索没搜到”，否则用户会一直换关键字
            rowsReady.isEmpty() -> BillEmpty(
                title = "当前周期还没有记录",
                body = "回账单页点右下角「记账」补一笔，或到「个人」页导入账单。",
                modifier = Modifier.weight(1f),
            )
            !query.isActive -> BillEmpty(
                title = "输入商户或金额开始搜索",
                body = "范围是当前周期（$periodText），也可以只用类型、分类筛选。",
                modifier = Modifier.weight(1f),
            )
            results.isEmpty() -> BillEmpty(
                title = "没有符合条件的记录",
                body = "换个商户名、金额或筛选条件试试。",
                modifier = Modifier.weight(1f),
            )
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (byDay) {
                        shownDays.forEach { day ->
                            val dayRows = grouped[day].orEmpty()
                            stickyHeader(key = "d${day.toEpochDay()}") {
                                DayHeader(day = day, dayRows = dayRows)
                            }
                            items(dayRows, key = { it.id }) { row ->
                                BillListItem(
                                    row = row,
                                    selecting = false,
                                    selected = false,
                                    showDate = false,
                                    onClick = { onEditTransaction(row.id) },
                                    onLongClick = {},
                                )
                            }
                        }
                    } else {
                        // 金额排序：平铺，行内补日期
                        items(shownRows, key = { it.id }) { row ->
                            BillListItem(
                                row = row,
                                selecting = false,
                                selected = false,
                                showDate = true,
                                onClick = { onEditTransaction(row.id) },
                                onLongClick = {},
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        CategoryFilterSheet(
            categories = categories,
            selected = categoryIds,
            onApply = {
                categoryIds = it
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    if (showSortSheet) {
        BillSortSheet(
            current = sort,
            onSelect = {
                sort = it
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }
}

/**
 * 搜索/金额输入框：40dp 高、surface2 底、发丝描边；聚焦描边转主色；
 * 有内容时右侧出现清除按钮（与「监听的 App」页搜索框同一外观）。
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    val c = MaterialTheme.eco
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.surface2)
            .border(1.dp, if (focused) c.blue.copy(alpha = 0.65f) else c.rule, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = c.quiet, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = EcoType.Input.copy(color = c.ink),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            cursorBrush = SolidColor(c.blue),
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, style = EcoType.Input, color = c.quiet, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Icon(
                EcoIcons.Close,
                contentDescription = "清除",
                tint = c.quiet,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}

/** 分类筛选入口：未选时与筛选条同高的浅底胶囊，选中后转主色浅底并显示个数 */
@Composable
private fun CategoryFilterButton(active: Boolean, count: Int, onClick: () -> Unit) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) c.blueWash else c.surface2)
            .border(1.dp, if (active) c.blue.copy(alpha = 0.4f) else c.rule, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            EcoIcons.Filter,
            contentDescription = null,
            tint = if (active) c.blueDeep else c.muted,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (active) "分类 $count" else "分类",
            style = EcoType.Pill,
            color = if (active) c.blueDeep else c.muted,
            maxLines = 1,
        )
    }
}
