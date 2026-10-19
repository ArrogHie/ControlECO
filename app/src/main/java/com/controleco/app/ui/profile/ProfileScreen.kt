package com.controleco.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.BuildConfig
import com.controleco.app.R
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.budget.Budget
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.WheelPicker
import com.controleco.app.ui.eco.EcoAutoMoney
import com.controleco.app.ui.eco.EcoBudgetSheet
import com.controleco.app.ui.eco.EcoCategoryTile
import com.controleco.app.ui.eco.EcoDialog
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoHeroCard
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRow
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoSegmented
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoBottomContentPadding
import com.controleco.app.ui.theme.AccentThemeStore
import com.controleco.app.ui.theme.EcoAccent
import com.controleco.app.ui.theme.EcoColors
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money
import java.time.LocalDate
import kotlin.math.roundToInt

/** 开源仓库（个人页「更多」里的一行，点按用系统浏览器打开） */
private const val GITHUB_REPO_PATH = "ArrogHie/ControlECO"
private const val GITHUB_REPO_URL = "https://github.com/$GITHUB_REPO_PATH"

// ---- 版式常量（与原型 5.6 profile 对齐；高度固定，保证四块视觉节奏一致） ----
private val HERO_HEIGHT = 197.dp
private val DUO_HEIGHT = 161.dp
private val PENDING_WIDTH = 109.dp
private val UTILITY_WIDTH = 127.dp
private val START_CARD_HEIGHT = 132.dp
private val SETTINGS_CARD_HEIGHT = 52.dp
private val MID_GAP = 8.dp
private val BOTTOM_HEIGHT = 66.dp

// 深色 hero 上的前景色（hero 渐变永远是深色，这组色不随深浅主题变）
private val HERO_MINT = Color(0xFFBDE9DA)
private val HERO_MINT_SOFT = Color(0xFFD0F6D5)
private val HERO_INK = Color(0xFFEAFBF2)
private val HERO_BTN_INK = Color(0xFFD8F7E8)

/**
 * 个人页：预算 hero + 月/日预算双列（待确认椭圆）+ 分类/每月首日/设置三卡 + 底排（导入/打赏/关于）+ 更多。
 *
 * 版式与原型 1:1（5.6 profile）：hero 197 高、非对称圆角 12/33/12/33；
 * 双列左侧是分段 + 日预算瓦片，右侧是待确认椭圆（跨两行）；
 * 中排左侧分类卡（25/9/25/9），右侧「每月首日」（内置常驻滚轮）+「设置」；
 * 底排 63 / 1.03fr / 1.27fr 三个胶囊；其余既有入口（货币、主题色、GitHub、版本、关于）收进「更多」。
 */
@Composable
fun ProfileScreen(
    vm: LedgerViewModel,
    onOpenAccount: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    val context = LocalContext.current
    val budget by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val spent by vm.homeNetSpent.collectAsStateWithLifecycle()
    val currency by vm.currencySymbol.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val monthStartDay by vm.monthStartDay.collectAsStateWithLifecycle()
    val dailyBudgetMode by vm.dailyBudgetMode.collectAsStateWithLifecycle()
    val dailyBudgetCents by vm.dailyBudgetCents.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    // 与首页同口径：本月净支出未到位前整页不画（避免预算卡先显示 0 再跳数）
    val spentReady = spent ?: return
    val today = LocalDate.now()
    val period = remember(today, monthStartDay) { MonthPeriod.of(today, monthStartDay) }
    val snapshot = remember(budget, spentReady, period) { Budget.compute(budget, spentReady, today, period = period) }

    var showBudgetSheet by remember { mutableStateOf(false) }
    var showCurrencySheet by remember { mutableStateOf(false) }
    var showReward by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // 开源仓库链接：用系统浏览器打开（没有浏览器时静默失败，不影响其它功能）
    val openRepo: () -> Unit = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))) }
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(micro = "PERSONAL SPACE", title = "我的")
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 2.dp, bottom = ecoBottomContentPadding(extra = 10.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BudgetHero(
                budgetCents = budget,
                spentCents = spentReady,
                snapshot = snapshot,
                currency = currency,
                onCurrency = { showCurrencySheet = true },
                onOpenAccount = onOpenAccount,
                onManageBudget = { showBudgetSheet = true },
            )

            BudgetDuo(
                dailyMode = dailyBudgetMode,
                dailyBudgetCents = dailyBudgetCents,
                monthlyBudgetCents = budget,
                pendingCount = pendingCount,
                onModeChange = { vm.setDailyBudgetMode(it) },
                onManageBudget = { showBudgetSheet = true },
                onOpenPending = onOpenPending,
            )

            MidRow(
                categories = categories,
                monthStartDay = monthStartDay,
                onOpenCategories = onOpenCategories,
                onOpenSettings = onOpenSettings,
                onMonthStartDay = { vm.setMonthStartDay(it) },
            )

            BottomRow(
                onOpenImport = onOpenImport,
                onReward = { showReward = true },
                onAbout = { showAbout = true },
            )

            MoreSection(
                currency = currency,
                accent = AccentThemeStore.current,
                onPickAccent = { AccentThemeStore.set(context, it) },
                onCurrency = { showCurrencySheet = true },
                onOpenRepo = openRepo,
                onAbout = { showAbout = true },
            )
        }
    }

    if (showBudgetSheet) {
        EcoBudgetSheet(
            dailyMode = dailyBudgetMode,
            daysInPeriod = period.days,
            currentCents = if (dailyBudgetMode) dailyBudgetCents else budget,
            onSave = {
                if (dailyBudgetMode) vm.setDailyBudget(it) else vm.setMonthlyBudget(it)
                showBudgetSheet = false
            },
            onDismiss = { showBudgetSheet = false },
        )
    }

    if (showCurrencySheet) {
        CurrencySheet(
            current = currency,
            onPick = { vm.setCurrencySymbol(it); showCurrencySheet = false },
            onDismiss = { showCurrencySheet = false },
        )
    }

    if (showReward) {
        RewardDialog(onDismiss = { showReward = false })
    }

    if (showAbout) {
        AboutDialog(onOpenRepo = openRepo, onDismiss = { showAbout = false })
    }
}

// ---------- 1. 预算 hero ----------

/**
 * 本月预算 hero：非对称圆角 12/33/12/33、197 高，用强调色 heroA/B/C 渐变 + 投影。
 * 四行内容：眉毛 + 货币/账户动作、48 号金额（可点开预算卡）、6dp 进度轨、已花/百分比/剩余。
 */
@Composable
private fun BudgetHero(
    budgetCents: Long,
    spentCents: Long,
    snapshot: Budget.Snapshot?,
    currency: String,
    onCurrency: () -> Unit,
    onOpenAccount: () -> Unit,
    onManageBudget: () -> Unit,
) {
    val ratio = (snapshot?.spentRatio ?: if (budgetCents > 0) spentCents.toFloat() / budgetCents else 0f)
        .coerceIn(0f, 1f)
    val pct = (ratio * 100f).roundToInt()
    val remaining = budgetCents - spentCents
    // 未设置时给一个更短的字样，避免 48 号字只有"¥0.00"显得像真有钱
    val amountText = if (budgetCents > 0) Money.formatYuanGrouped(budgetCents) else "未设置"

    EcoHeroCard(modifier = Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        Column(Modifier.fillMaxSize().padding(17.dp)) {
            // 眉毛 + 右侧两个动作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本月预算",
                    style = EcoType.Micro.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight(740),
                        letterSpacing = 1.58.sp,
                    ),
                    color = HERO_MINT,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .height(27.dp)
                        .widthIn(min = 30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color.White.copy(alpha = 0.09f))
                        .border(1.dp, Color(0xFFE2FFF1).copy(alpha = 0.22f), RoundedCornerShape(15.dp))
                        .clickable(onClick = onCurrency)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        currency,
                        style = EcoType.Chip.copy(fontSize = 12.sp, fontWeight = FontWeight(760)),
                        color = HERO_BTN_INK,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Row(
                    Modifier
                        .width(77.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(11.dp, 16.dp, 11.dp, 16.dp))
                        .background(Color(0xFFF4FFF9).copy(alpha = 0.13f))
                        .clickable(onClick = onOpenAccount),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        EcoIcons.Wallet,
                        contentDescription = null,
                        tint = HERO_INK,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "账户",
                        style = EcoType.Chip.copy(fontSize = 10.5.sp, fontWeight = FontWeight(760)),
                        color = HERO_INK,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 金额（点金额或右侧铅笔都能改预算）
            Row(verticalAlignment = Alignment.CenterVertically) {
                EcoAutoMoney(
                    text = amountText,
                    style = EcoType.HeroAmount,
                    color = Color(0xFFF7FFFA),
                    modifier = Modifier.weight(1f).clickable(onClick = onManageBudget),
                    textAlign = TextAlign.Start,
                    minSize = 24.sp,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(onClick = onManageBudget),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        EcoIcons.Edit,
                        contentDescription = "设置本月预算",
                        tint = HERO_MINT.copy(alpha = 0.9f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 进度轨
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFFC3F1CB), Color(0xFF57DBAA))),
                        ),
                )
            }
            Spacer(Modifier.height(10.dp))

            // 元信息：已花 / 百分比 / 剩余
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已花 ${Money.formatYuanGrouped(spentCents)}",
                    style = EcoType.RowTrail,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$pct%",
                    style = EcoType.RowTrail.copy(fontSize = 11.sp, fontWeight = FontWeight(760)),
                    color = HERO_MINT_SOFT,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "剩余 ${Money.formatYuanGrouped(remaining)}",
                    style = EcoType.RowTrail,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------- 2. 双列：分段 + 日预算瓦片 / 待确认椭圆 ----------

@Composable
private fun BudgetDuo(
    dailyMode: Boolean,
    dailyBudgetCents: Long,
    monthlyBudgetCents: Long,
    pendingCount: Int,
    onModeChange: (Boolean) -> Unit,
    onManageBudget: () -> Unit,
    onOpenPending: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(DUO_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            EcoSegmented(
                options = listOf("月预算", "日预算"),
                selectedIndex = if (dailyMode) 1 else 0,
                onSelect = { onModeChange(it == 1) },
                modifier = Modifier.fillMaxWidth(),
                height = 42.dp,
            )
            DailyTile(
                amountCents = if (dailyMode) dailyBudgetCents else monthlyBudgetCents,
                monthMode = !dailyMode,
                onManage = onManageBudget,
                modifier = Modifier.weight(1f),
            )
        }
        PendingPill(
            count = pendingCount,
            onClick = onOpenPending,
            modifier = Modifier.width(PENDING_WIDTH).fillMaxHeight(),
        )
    }
}

/** 日预算瓦片：非对称圆角 10/27/10/27，右侧竖排「预算管理」开口打开预算卡。 */
@Composable
private fun DailyTile(
    amountCents: Long,
    monthMode: Boolean,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(10.dp, 27.dp, 10.dp, 27.dp)
    Box(
        modifier
            .shadow(
                elevation = 11.dp,
                shape = shape,
                clip = false,
                ambientColor = c.ink.copy(alpha = if (c.isDark) 0.3f else 0.08f),
                spotColor = c.ink.copy(alpha = if (c.isDark) 0.4f else 0.14f),
            )
            .clip(shape)
            .background(softGradient(c, c.blueSoft, 0.62f, 0.5f))
            .border(1.dp, c.rule, shape),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 15.dp, top = 12.dp, end = 60.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (amountCents > 0) {
                    EcoAutoMoney(
                        text = Money.formatYuanGrouped(amountCents),
                        style = if (monthMode) {
                            EcoType.DailyValue.copy(fontSize = 26.sp, lineHeight = 30.sp)
                        } else {
                            EcoType.DailyValue
                        },
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                        minSize = 15.sp,
                    )
                } else {
                    Text(
                        "未设置",
                        style = EcoType.RowTitle,
                        color = c.muted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
                Text(
                    "/day",
                    style = EcoType.Micro.copy(fontSize = 10.sp, fontWeight = FontWeight(700), letterSpacing = 0.sp),
                    color = c.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp, start = 2.dp),
                )
            }
        }

        // 右侧竖排管理钮（铅笔 + 预算管理）
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 11.dp)
                .width(51.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onManage)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                EcoIcons.Edit,
                contentDescription = "预算管理",
                tint = c.blue.copy(alpha = 0.6f),
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "预算管理",
                style = EcoType.Chip.copy(fontSize = 10.sp, fontWeight = FontWeight(800), letterSpacing = 0.sp),
                color = c.blue,
                maxLines = 1,
            )
        }
    }
}

/**
 * 待确认椭圆：宽 109、跨双列两行；双层虚线环 + 收据/对勾图标 + 笔数 + 待确认。
 * 有未确认时用陶土浅底（clay），清零时转玉绿（jade）；点按进待确认页。
 */
@Composable
private fun PendingPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    val alert = count > 0
    val shape = RoundedCornerShape(50)
    val bgTop: Color
    val bgBottom: Color
    val borderColor: Color
    val fg: Color
    if (alert) {
        bgTop = if (c.isDark) lerp(c.claySoft, Color.White, 0.10f) else Color(0xFFFFF8ED)
        bgBottom = if (c.isDark) lerp(c.claySoft, c.surface, 0.18f) else Color(0xFFF9DED0)
        borderColor = if (c.isDark) c.clay.copy(alpha = 0.42f) else Color(0xFFF0CEBC)
        fg = if (c.isDark) c.clayHi else Color(0xFFA74E42)
    } else {
        bgTop = if (c.isDark) lerp(c.jadeSoft, Color.White, 0.05f) else Color(0xFFF2FAF1)
        bgBottom = if (c.isDark) lerp(c.jadeSoft, c.surface, 0.16f) else Color(0xFFDCEFE1)
        borderColor = if (c.isDark) c.jade.copy(alpha = 0.36f) else Color(0xFFCBE4D2)
        fg = if (c.isDark) c.jade else Color(0xFF356B4E)
    }

    Box(
        modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = fg.copy(alpha = if (c.isDark) 0.22f else 0.16f),
                spotColor = fg.copy(alpha = if (c.isDark) 0.28f else 0.20f),
            )
            .clip(shape)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(bgTop, bgBottom),
                        center = Offset(size.width * 0.24f, size.height * 0.12f),
                        radius = maxOf(size.width, size.height),
                    ),
                )
            }
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick),
    ) {
        // 内置双层环：外层虚线（inset 6）、内层实线（inset 18x17）
        Canvas(Modifier.matchParentSize()) {
            val stroke = 1.dp.toPx()
            val inset = 6.dp.toPx()
            drawRoundRect(
                color = fg.copy(alpha = 0.30f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                cornerRadius = CornerRadius((size.width - inset * 2f) / 2f),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(3.dp.toPx(), 4.dp.toPx()),
                    ),
                ),
            )
            val ix = 18.dp.toPx()
            val iy = 17.dp.toPx()
            drawRoundRect(
                color = fg.copy(alpha = 0.15f),
                topLeft = Offset(ix, iy),
                size = Size(size.width - ix * 2f, size.height - iy * 2f),
                cornerRadius = CornerRadius((size.width - ix * 2f) / 2f),
                style = Stroke(width = stroke),
            )
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                if (alert) EcoIcons.Receipt else EcoIcons.Check,
                contentDescription = null,
                tint = fg.copy(alpha = 0.92f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "%02d".format(count),
                style = EcoType.PendingCount,
                color = fg,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                "待确认",
                style = EcoType.RowTrail.copy(fontSize = 10.5.sp, fontWeight = FontWeight(740)),
                color = fg.copy(alpha = 0.86f),
                maxLines = 1,
            )
        }
    }
}

// ---------- 3. 中排：分类卡 / 每月首日（常驻滚轮）/ 设置 ----------

@Composable
private fun MidRow(
    categories: List<CategoryEntity>,
    monthStartDay: Int,
    onOpenCategories: () -> Unit,
    onOpenSettings: () -> Unit,
    onMonthStartDay: (Int) -> Unit,
) {
    val tiles = remember(categories) {
        val expense = categories.filter { it.type == TxType.EXPENSE }
        (if (expense.isNotEmpty()) expense else categories).take(4)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CategoryCard(
            tiles = tiles,
            onClick = onOpenCategories,
            modifier = Modifier
                .weight(1f)
                .height(START_CARD_HEIGHT + MID_GAP + SETTINGS_CARD_HEIGHT),
        )
        Column(
            Modifier.width(UTILITY_WIDTH).zIndex(1f),
            verticalArrangement = Arrangement.spacedBy(MID_GAP),
        ) {
            MonthStartCard(
                monthStartDay = monthStartDay,
                onMonthStartDay = onMonthStartDay,
                modifier = Modifier.fillMaxWidth().height(START_CARD_HEIGHT),
            )
            SettingsCard(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(SETTINGS_CARD_HEIGHT),
            )
        }
    }
}

/** 分类卡：玉绿渐变、圆角 25/9/25/9；标题 + 4 个真实分类图标瓦片，整卡点按进分类管理。 */
@Composable
private fun CategoryCard(
    tiles: List<CategoryEntity>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(25.dp, 9.dp, 25.dp, 9.dp)
    Box(
        modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = c.jade.copy(alpha = if (c.isDark) 0.24f else 0.16f),
                spotColor = c.ink.copy(alpha = if (c.isDark) 0.4f else 0.12f),
            )
            .clip(shape)
            .background(softGradient(c, c.jadeSoft, 0.92f, 0.62f))
            .border(1.dp, c.jade.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick),
    ) {
        // 右上小装饰环（opacity .3）
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = c.jade.copy(alpha = if (c.isDark) 0.16f else 0.22f),
                radius = minOf(size.width, size.height) * 0.42f,
                center = Offset(size.width * 0.97f, size.height * 0.02f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(start = 15.dp, end = 9.dp, top = 15.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "分类设置",
                style = EcoType.CatAmount.copy(
                    fontSize = 19.sp,
                    fontWeight = FontWeight(790),
                    letterSpacing = 0.66.sp,
                    lineHeight = 24.sp,
                ),
                color = c.ink,
                maxLines = 1,
            )
            // 瓦片宽度随可用宽度自适应（窄屏 30dp、常规 42dp），保证 4 个永远排得下
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 5.dp
                val tile = ((maxWidth - gap * 3f) / 4f).coerceIn(30.dp, 42.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tiles.forEach { category ->
                        EcoCategoryTile(
                            categoryName = category.name,
                            icon = category.icon,
                            size = tile,
                            radius = 12.dp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「每月首日」卡：圆角 12/28/12/28、蓝色系渐变；日历图标 + 标题 + 当前值 + chevron。
 *
 * **滚轮常驻在卡内**（3 行 / 96x84）：点击 → 原位浮出滚轮卡选择；长按 → 不松手直接拖动，
 * 抬手后由 WheelPicker 自己惯性吸附并回写 —— 与改造前完全一致，只是从"整行右侧"变成"卡片内下方"。
 * 卡面不用 clip（用 background(brush, shape) 画圆角），长按展开的滚轮卡可以溢出卡边不被裁掉。
 */
@Composable
private fun MonthStartCard(
    monthStartDay: Int,
    onMonthStartDay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(12.dp, 28.dp, 12.dp, 28.dp)
    val options = remember { MonthPeriod.startDayOptions() }
    val dayLabel = MonthPeriod.formatStartDay(monthStartDay).replace("日", " 日")

    Box(
        modifier
            .zIndex(1f)
            .shadow(
                elevation = 11.dp,
                shape = shape,
                clip = false,
                ambientColor = c.ink.copy(alpha = if (c.isDark) 0.3f else 0.08f),
                spotColor = c.ink.copy(alpha = if (c.isDark) 0.4f else 0.14f),
            )
            .background(softGradient(c, c.blueSoft, 0.72f, 0.5f), shape)
            .border(1.dp, c.blue.copy(alpha = 0.16f), shape),
    ) {
        // 右上 88px 装饰环
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = c.blue.copy(alpha = if (c.isDark) 0.14f else 0.18f),
                radius = minOf(size.width, size.height) * 0.46f,
                center = Offset(size.width * 0.98f, size.height * 0.04f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    EcoIcons.Calendar,
                    contentDescription = null,
                    tint = c.blue,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "每月首日",
                        style = EcoType.Chip.copy(fontSize = 10.sp, fontWeight = FontWeight(730), letterSpacing = 0.sp),
                        color = c.muted,
                        maxLines = 1,
                    )
                    Text(
                        dayLabel,
                        style = EcoType.CatAmount.copy(fontSize = 16.sp, fontWeight = FontWeight(750), lineHeight = 20.sp),
                        color = c.ink,
                        maxLines = 1,
                    )
                }
                Icon(
                    EcoIcons.ChevDown,
                    contentDescription = null,
                    tint = c.quiet,
                    modifier = Modifier.size(11.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            // 常驻滚轮：点击浮出 / 长按就地拖动（WheelPicker 原行为，未改）
            WheelPicker(
                items = options,
                selectedIndex = MonthPeriod.startDayIndex(monthStartDay),
                onSelected = { onMonthStartDay(MonthPeriod.startDayAtIndex(it)) },
                label = "每月首日",
            )
        }
    }
}

/** 「设置」卡：圆角 28/12/28/12、surface→amber-soft 渐变，齿轮 + 设置。 */
@Composable
private fun SettingsCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    val shape = RoundedCornerShape(28.dp, 12.dp, 28.dp, 12.dp)
    Box(
        modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                clip = false,
                ambientColor = c.ink.copy(alpha = if (c.isDark) 0.3f else 0.07f),
                spotColor = c.ink.copy(alpha = if (c.isDark) 0.4f else 0.12f),
            )
            .clip(shape)
            .background(softGradient(c, c.amberSoft, 0.5f, 0.42f))
            .border(1.dp, c.rule, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Icon(EcoIcons.Gear, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "设置",
                style = EcoType.CatAmount.copy(fontSize = 16.sp, fontWeight = FontWeight(750), lineHeight = 20.sp),
                color = c.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(EcoIcons.Chev, contentDescription = null, tint = c.quiet, modifier = Modifier.size(13.dp))
        }
    }
}

// ---------- 4. 底排：导入 / 打赏 / 关于 ----------

@Composable
private fun BottomRow(
    onOpenImport: () -> Unit,
    onReward: () -> Unit,
    onAbout: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(BOTTOM_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ImportOrb(
            onClick = onOpenImport,
            modifier = Modifier.size(63.dp).align(Alignment.CenterVertically),
        )
        RewardTile(onClick = onReward, modifier = Modifier.weight(1.03f).fillMaxHeight())
        AboutTile(onClick = onAbout, modifier = Modifier.weight(1.27f).fillMaxHeight())
    }
}

/** 账单导入：63 的圆形胶囊（hero 渐变），下载/文件图标 + 「导入账单」。 */
@Composable
private fun ImportOrb(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .shadow(
                elevation = 13.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = c.heroB.copy(alpha = 0.36f),
                spotColor = c.heroB.copy(alpha = 0.42f),
            )
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c.heroC, c.heroB), start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                EcoIcons.File,
                contentDescription = "账单导入",
                tint = Color(0xFFF2FCF6),
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "导入账单",
                style = EcoType.Micro.copy(fontSize = 8.5.sp, fontWeight = FontWeight(800), letterSpacing = 0.sp),
                color = Color(0xFFEAFBF2),
                maxLines = 1,
            )
        }
    }
}

/** 打赏：陶土渐变胶囊，咖啡 + 打赏。 */
@Composable
private fun RewardTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(50),
                clip = false,
                ambientColor = Color(0xFFC45854).copy(alpha = 0.34f),
                spotColor = Color(0xFFC45854).copy(alpha = 0.4f),
            )
            .clip(RoundedCornerShape(50))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFE78C74), Color(0xFFC45854)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                EcoIcons.Coffee,
                contentDescription = null,
                tint = Color(0xFFFFF4EE),
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "打赏",
                style = EcoType.Chip.copy(fontSize = 14.sp, fontWeight = FontWeight(800), letterSpacing = 0.sp),
                color = Color(0xFFFFF6F1),
                maxLines = 1,
            )
        }
    }
}

/** 关于：与导入同族 hero 渐变，信息图标 + 关于。 */
@Composable
private fun AboutTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(50),
                clip = false,
                ambientColor = c.heroB.copy(alpha = 0.34f),
                spotColor = c.heroB.copy(alpha = 0.4f),
            )
            .clip(RoundedCornerShape(50))
            .background(Brush.linearGradient(listOf(c.heroC, c.heroB), start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                EcoIcons.Info,
                contentDescription = null,
                tint = Color(0xFFF2FCF6),
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "关于",
                style = EcoType.Chip.copy(fontSize = 15.sp, fontWeight = FontWeight(800), letterSpacing = 0.sp),
                color = Color(0xFFF2FCF6),
                maxLines = 1,
            )
        }
    }
}

// ---------- 5. 更多（主题色 / 货币 / 仓库 / 版本 / 关于） ----------

@Composable
private fun MoreSection(
    currency: String,
    accent: EcoAccent,
    onPickAccent: (EcoAccent) -> Unit,
    onCurrency: () -> Unit,
    onOpenRepo: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        EcoSectionHead(title = "更多", aside = "MORE")
        EcoPane {
            Column(Modifier.fillMaxWidth()) {
                ThemeRow(selected = accent, onPick = onPickAccent)
                EcoRowDivider()
                EcoRow(
                    title = "货币符号",
                    trail = "$currency  ${currencyName(currency)}",
                    onClick = onCurrency,
                    showChevron = true,
                )
                EcoRowDivider()
                EcoRow(
                    title = "GitHub 仓库",
                    subtitle = GITHUB_REPO_PATH,
                    icon = EcoIcons.Link,
                    onClick = onOpenRepo,
                    showChevron = true,
                )
                EcoRowDivider()
                EcoRow(
                    title = "版本",
                    subtitle = if (BuildConfig.DEBUG) "debug 构建" else "release 构建",
                    trail = "v${BuildConfig.VERSION_NAME}",
                )
                EcoRowDivider()
                EcoRow(
                    title = "关于 我ECO",
                    icon = EcoIcons.Info,
                    onClick = onAbout,
                    showChevron = true,
                )
            }
        }
    }
}

/** 主题色：6 个圆形色板（EcoAccent 六套），选中项描环，点一下即时生效。 */
@Composable
private fun ThemeRow(selected: EcoAccent, onPick: (EcoAccent) -> Unit) {
    val c = MaterialTheme.eco
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主题色", style = EcoType.RowTitle, color = c.ink)
            Spacer(Modifier.weight(1f))
            Text(selected.label, style = EcoType.RowTrail, color = c.muted)
        }
        Spacer(Modifier.height(11.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EcoAccent.entries.forEach { entry ->
                val dot = Color(if (c.isDark) entry.dark.blue else entry.light.blue)
                val on = entry == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(entry) }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                ) {
                    Box(
                        Modifier
                            .size(if (on) 32.dp else 30.dp)
                            .clip(CircleShape)
                            .background(dot)
                            .then(
                                if (on) {
                                    Modifier.border(2.dp, c.ink, CircleShape)
                                } else {
                                    Modifier.border(1.dp, c.rule, CircleShape)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) {
                            Icon(
                                EcoIcons.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.label,
                        style = EcoType.Pill,
                        color = if (on) c.ink else c.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------- 6. 弹层：货币 / 打赏 / 关于 ----------

/** 货币符号选择（9 种）：只改显示符号，不做换算。 */
@Composable
private fun CurrencySheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.eco
    EcoSheet(
        onDismiss = onDismiss,
        title = "货币符号",
        subtitle = "只改显示符号，不做汇率换算、也不改金额",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Money.CURRENCIES.forEach { (symbol, name) ->
                val selected = symbol == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) c.blueSoft else Color.Transparent)
                        .clickable { onPick(symbol) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        symbol,
                        style = EcoType.CatAmount,
                        color = if (selected) c.blueDeep else c.ink,
                        modifier = Modifier.width(52.dp),
                        maxLines = 1,
                    )
                    Text(
                        name,
                        style = EcoType.RowTitle,
                        color = if (selected) c.blueDeep else c.muted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (selected) {
                        Icon(
                            EcoIcons.Check,
                            contentDescription = null,
                            tint = c.blue,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 打赏：两张收款码并排（微信 / 支付宝），标题 + 说明 + 小注。 */
@Composable
private fun RewardDialog(onDismiss: () -> Unit) {
    val c = MaterialTheme.eco
    EcoDialog(onDismiss = onDismiss) {
        Column {
            Text("打赏一杯咖啡", style = EcoType.ModalTitle, color = c.ink)
            Spacer(Modifier.height(5.dp))
            Text(
                "我ECO 完全本地运行、没有广告，也没有云端账号。如果它帮你省下了对账的时间，可以请作者喝一杯咖啡。",
                style = EcoType.ModalBody,
                color = c.muted,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RewardQr(
                    resId = R.drawable.reward_wechat,
                    label = "微信",
                    tint = Color(0xFF2E9E5B),
                    modifier = Modifier.weight(1f),
                )
                RewardQr(
                    resId = R.drawable.reward_alipay,
                    label = "支付宝",
                    tint = Color(0xFF2E6FBF),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "长按识别二维码，或截图后用对应 App 扫一扫。打赏纯属自愿，不影响任何功能。",
                style = EcoType.PageNote,
                color = c.quiet,
            )
            Spacer(Modifier.height(16.dp))
            EcoGhostButton(text = "关闭", onClick = onDismiss, height = 40.dp)
        }
    }
}

@Composable
private fun RewardQr(resId: Int, label: String, tint: Color, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, c.rule, RoundedCornerShape(12.dp))
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(resId),
                contentDescription = "$label 收款码",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = EcoType.Pill.copy(fontWeight = FontWeight(800), fontSize = 10.sp),
            color = tint,
            maxLines = 1,
        )
    }
}

/** 关于：品牌块 + 版本/构建/仓库三行 + 隐私一行 + 打开仓库。 */
@Composable
private fun AboutDialog(onOpenRepo: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.eco
    EcoDialog(onDismiss = onDismiss) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF52A47B), Color(0xFF2E7358)),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        EcoIcons.Leaf,
                        contentDescription = null,
                        tint = Color(0xFFF7FBF2),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("我ECO 账本", style = EcoType.ModalTitle, color = c.ink, maxLines = 1)
                    Spacer(Modifier.height(1.dp))
                    Text(
                        "本地记账 · 账单导入 · 自动捕获",
                        style = EcoType.PageNote,
                        color = c.muted,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            AboutLine("版本号", "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            AboutLine("构建类型", if (BuildConfig.DEBUG) "debug" else "release")
            AboutLine("开源仓库", GITHUB_REPO_PATH)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    EcoIcons.Shield,
                    contentDescription = null,
                    tint = c.jade,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "只写本机数据库：不联网、不上传，无云端账号与第三方统计。",
                    style = EcoType.PageNote,
                    color = c.muted,
                )
            }
            Spacer(Modifier.height(16.dp))
            EcoPrimaryButton(
                text = "在浏览器中打开仓库",
                onClick = onOpenRepo,
                leading = EcoIcons.External,
                height = 40.dp,
            )
            Spacer(Modifier.height(8.dp))
            EcoGhostButton(text = "关闭", onClick = onDismiss, height = 40.dp)
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    val c = MaterialTheme.eco
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = EcoType.RowSub, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, style = EcoType.RowTrail, color = c.ink, maxLines = 1)
    }
}

// ---------- 公用 ----------

/** 卡片底：surface → 语义色的斜向渐变（深色模式下压暗，避免浅色块在暗色纸面上过亮）。 */
private fun softGradient(c: EcoColors, tint: Color, lightAmount: Float, darkAmount: Float): Brush =
    Brush.linearGradient(
        colors = listOf(c.surface, lerp(c.surface, tint, if (c.isDark) darkAmount else lightAmount)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

/** 货币名称（找不到时只显示符号本身） */
private fun currencyName(symbol: String): String =
    Money.CURRENCIES.firstOrNull { it.first == symbol }?.second ?: ""
