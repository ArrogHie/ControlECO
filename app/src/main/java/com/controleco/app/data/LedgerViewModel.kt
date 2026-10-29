package com.controleco.app.data

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.controleco.app.AccBookApp
import com.controleco.app.BuildConfig
import com.controleco.app.capture.AccBookAccessibilityService
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.capture.CaptureDefaults
import com.controleco.app.capture.NotificationRules
import com.controleco.app.capture.MerchantRules
import com.controleco.app.capture.PendingActions
import com.controleco.app.capture.ScreenReaderAccess
import com.controleco.app.csv.BillParsers
import com.controleco.app.data.db.AccountBalanceRow
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CaptureTraceRow
import com.controleco.app.data.db.CapturedNotificationEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategoryRules
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundCategory
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TypeTotalRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.data.budget.Budget
import com.controleco.app.data.budget.BudgetShare
import com.controleco.app.data.budget.CategoryBudgetMath
import com.controleco.app.data.budget.CategoryBudgetMode
import com.controleco.app.data.budget.MonthPeriod
import com.controleco.app.data.model.BillSource
import com.controleco.app.data.model.ImportCommitResult
import com.controleco.app.data.model.ParsedBill
import com.controleco.app.update.AppUpdate
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 阶段一的全局 ViewModel：月份切换为流水/统计共用状态；
 * 后续阶段（自动记账）在此扩展捕获通道状态。
 */
class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as AccBookApp).db
    private val txDao = db.transactionDao()
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val pendingDao = db.pendingDao()
    private val captureDao = db.captureDao()
    private val traceDao = db.traceDao()
    private val merchantRuleDao = db.merchantRuleDao()

    private val zone: ZoneId = ZoneId.systemDefault()

    // ---------- 设置：显示退款记录 ----------

    private val prefs = getApplication<AccBookApp>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 预算：会计月首日 / 日·月预算 / 生效月预算 ----------

    /** 会计月首日（1..28）：决定"本月"的区间与全 App 按月统计的口径（见 MonthPeriod） */
    private val _monthStartDay = MutableStateFlow(
        MonthPeriod.clampStartDay(prefs.getInt(KEY_MONTH_START_DAY, 1)),
    )
    val monthStartDay: StateFlow<Int> = _monthStartDay

    fun setMonthStartDay(day: Int) {
        val v = MonthPeriod.clampStartDay(day)
        prefs.edit().putInt(KEY_MONTH_START_DAY, v).apply()
        _monthStartDay.value = v
        AppLog.i("settings", "monthStartDay=$v")
    }

    /**
     * "今天"（跨零点或从后台回来时由 [refreshToday] 刷新）。
     * 预算周期、今日净支出等一切"以今天为准"的口径都读它，避免零点后还停在昨天。
     */
    private val todayRef = MutableStateFlow(LocalDate.now(zone))

    /** 首页 ON_RESUME 时调用：跨天后把"今天"的统计范围换过来 */
    fun refreshToday() {
        val now = LocalDate.now(zone)
        if (now != todayRef.value) {
            todayRef.value = now
            AppLog.i("home", "today=$now")
        }
    }

    /** 当前会计月区间（随"今天 / 月首日"滚动） */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPeriod: StateFlow<MonthPeriod> = combine(todayRef, _monthStartDay) { d, s ->
        MonthPeriod.of(d, s)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value),
    )

    /** 当前会计月的"月标签"（账单/统计页的当前高亮与「回到本月」用；首日 ≠ 1 时可能不是本月） */
    val currentAccountingMonth: StateFlow<YearMonth> = currentPeriod
        .map { YearMonth.from(it.start) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MonthPeriod.monthOf(LocalDate.now(zone), _monthStartDay.value),
        )

    /** 预算按日设置（默认关）：开启后输入日预算，月预算 = 日预算 × 本期天数 */
    private val _dailyBudgetMode = MutableStateFlow(prefs.getBoolean(KEY_DAILY_BUDGET_MODE, false))
    val dailyBudgetMode: StateFlow<Boolean> = _dailyBudgetMode

    private val _monthlyBudgetAmount = MutableStateFlow(prefs.getLong(KEY_MONTHLY_BUDGET, 0L))
    private val _dailyBudgetAmount = MutableStateFlow(prefs.getLong(KEY_DAILY_BUDGET, 0L))

    /** 日预算金额（0 = 未设置；是否生效看 [dailyBudgetMode]） */
    val dailyBudgetCents: StateFlow<Long> = _dailyBudgetAmount

    /** 生效的月预算：按日模式 = 日预算 × 本期天数（跨月自动重算）；否则 = 月金额 */
    val monthlyBudgetCents: StateFlow<Long> = combine(
        _monthlyBudgetAmount, _dailyBudgetAmount, _dailyBudgetMode, currentPeriod,
    ) { amount, daily, dailyMode, period ->
        if (dailyMode) daily * period.days else amount
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), effectiveMonthlyBudget())

    private fun effectiveMonthlyBudget(): Long = if (_dailyBudgetMode.value) {
        _dailyBudgetAmount.value * MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value).days
    } else {
        _monthlyBudgetAmount.value
    }

    /** 月预算输入（金额模式）：cents ≤ 0 视为未设置 */
    fun setMonthlyBudget(cents: Long) {
        prefs.edit().putLong(KEY_MONTHLY_BUDGET, cents).apply()
        _monthlyBudgetAmount.value = cents
        AppLog.i("settings", "monthlyBudget=${Money.formatYuan(cents)}")
    }

    /** 日预算输入（按日模式）：cents ≤ 0 视为未设置 */
    fun setDailyBudget(cents: Long) {
        prefs.edit().putLong(KEY_DAILY_BUDGET, cents).apply()
        _dailyBudgetAmount.value = cents
        AppLog.i("settings", "dailyBudget=${Money.formatYuan(cents)}")
    }

    /**
     * 切换日/月预算：切到按日时若日预算还是空的，用"月预算 ÷ 本期天数"预填；
     * 反向切回月预算时同理用"日预算 × 本期天数"预填——个人页预算卡的日均靠月预算折算，
     * 月预算若是空的，卡片会突然显示「未设置」，看起来像数据丢了。
     */
    fun setDailyBudgetMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DAILY_BUDGET_MODE, enabled).apply()
        _dailyBudgetMode.value = enabled
        val days = MonthPeriod.of(LocalDate.now(zone), _monthStartDay.value).days
        if (enabled && _dailyBudgetAmount.value <= 0L && _monthlyBudgetAmount.value > 0L) {
            setDailyBudget((_monthlyBudgetAmount.value + days - 1) / days)
        } else if (!enabled && _monthlyBudgetAmount.value <= 0L && _dailyBudgetAmount.value > 0L) {
            setMonthlyBudget(_dailyBudgetAmount.value * days)
        }
        AppLog.i("settings", "budgetDailyMode=$enabled")
    }

    // ---------- 设置：账单页结余卡背景图（null = 默认几何背景） ----------

    private val _cardImageUri = MutableStateFlow(prefs.getString(KEY_CARD_IMAGE, null))
    val cardImageUri: StateFlow<String?> = _cardImageUri

    fun setCardImage(uri: String?) {
        prefs.edit().putString(KEY_CARD_IMAGE, uri).apply()
        _cardImageUri.value = uri
        if (uri == null) {
            prefs.edit().remove(KEY_CARD_COLOR).remove(KEY_CARD_LUMINANCE).apply()
            _cardImageColor.value = null
            _cardImageLuminance.value = null
            pruneCardImages() // 恢复默认背景：图片文件也一并清掉
        }
        loadCardImage(uri)
        AppLog.i("settings", "cardImage=${uri ?: "default"}")
    }

    /**
     * 清掉不再使用的卡面图（只留当前这张）。
     * 选图时**不**清：编辑器里点「取消」要把上一张原样恢复，得等真正「应用」时才收尾。
     */
    fun pruneCardImages() {
        val keep = _cardImageUri.value
        viewModelScope.launch(Dispatchers.IO) { deleteOtherCardImages(getApplication(), keep) }
    }

    /**
     * 卡面图片：进程启动（或换图）时就异步解码，账单页第一次组合时通常已经就绪，
     * 不会出现"先默认背景再闪回图片"。先出缩略图（几毫秒）再出全尺寸。
     */
    private val _cardImage = MutableStateFlow<CardImage?>(null)
    val cardImage: StateFlow<CardImage?> = _cardImage

    private var cardImageJob: Job? = null

    private fun loadCardImage(path: String?) {
        cardImageJob?.cancel()
        if (path.isNullOrBlank()) {
            _cardImage.value = null
            return
        }
        cardImageJob = viewModelScope.launch(Dispatchers.IO) {
            // 1) 超小缩略图：拿到真实（模糊）图 + 平均色，立刻可用
            val preview = CardImageLoader.decode(getApplication(), path, CardImageLoader.PREVIEW_SIZE)
            val persistedColor = prefs.getInt(KEY_CARD_COLOR, 0).takeIf { it != 0 }
            val persistedLum = prefs.getFloat(KEY_CARD_LUMINANCE, -1f).takeIf { it >= 0f }
            val color = preview?.let { CardImageLoader.averageColor(it) } ?: persistedColor
            val luminance = preview?.let { CardImageLoader.meanLuminance(it) } ?: persistedLum
            if (color != null && luminance != null) {
                // 老数据（图片在"平均色"功能之前导入）在这里回填一次，下次冷启动直接用
                if (persistedColor == null || persistedLum == null) setCardImageStats(color, luminance)
                _cardImage.value = CardImage(full = null, preview = preview, colorArgb = color, luminance = luminance)
            }
            // 2) 正常尺寸
            val full = CardImageLoader.decode(getApplication(), path, 1600)
            if (full != null) {
                val finalColor = color ?: CardImageLoader.averageColor(full)
                val finalLum = luminance ?: CardImageLoader.meanLuminance(full)
                _cardImage.value = CardImage(full, preview, finalColor, finalLum)
            }
        }
    }

    /**
     * 背景图的"平均色 + 整体亮度"：随图一起持久化。
     * 冷启动时位图还要解码，先拿这个颜色打底 → 不会先闪一帧默认几何背景。
     */
    private val _cardImageColor = MutableStateFlow(
        prefs.getInt(KEY_CARD_COLOR, 0).takeIf { it != 0 },
    )
    val cardImageColor: StateFlow<Int?> = _cardImageColor

    private val _cardImageLuminance = MutableStateFlow(
        prefs.getFloat(KEY_CARD_LUMINANCE, -1f).takeIf { it >= 0f },
    )
    val cardImageLuminance: StateFlow<Float?> = _cardImageLuminance

    fun setCardImageStats(argbColor: Int, luminance: Float) {
        prefs.edit()
            .putInt(KEY_CARD_COLOR, argbColor)
            .putFloat(KEY_CARD_LUMINANCE, luminance)
            .apply()
        _cardImageColor.value = argbColor
        _cardImageLuminance.value = luminance
    }

    /** 背景图的位移/缩放/旋转（选图后可在卡片上直接调） */
    private val _cardTransform = MutableStateFlow(
        CardTransform.parse(prefs.getString(KEY_CARD_TRANSFORM, null)),
    )
    val cardTransform: StateFlow<CardTransform> = _cardTransform

    init {
        // 冷启动预热：立刻在 IO 线程碰一次数据库，让 SQLite 打开/建连的开销
        // 与 UI 首帧并行，而不是等页面来查时才付（配合"未就绪先不画"减少空白时间）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { txDao.countAll() }
        }
        // 卡面图片同样在启动时就开始解码（先缩略图再全尺寸）
        loadCardImage(_cardImageUri.value)
        // 顺手清一次历史遗留图片（编辑器里没走完流程就退出的那几张）
        pruneCardImages()
        // 「退款」收入分类是退款入账的固定去向：老版本可能被改名/删除过，启动时补回一个
        // （用户不能改名/改类型/删除它，所以补回后不会再消失；已有则不重复插入）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val all = categoryDao.getAll()
                if (all.none { CategoryRules.isRefundIncome(it.type, it.name) }) {
                    categoryDao.insert(
                        CategoryEntity(
                            name = RefundCategory.NAME,
                            type = TxType.INCOME,
                            sortOrder = REFUND_INCOME_SORT_ORDER,
                        ),
                    )
                }
            }.onFailure { AppLog.e("category", "restore refund category failed", it) }
        }
    }

    fun setCardTransform(transform: CardTransform) {
        val t = transform.normalized()
        prefs.edit().putString(KEY_CARD_TRANSFORM, t.serialize()).apply()
        _cardTransform.value = t
    }

    // ---------- 设置：货币符号（人民币 / 美元 / 欧元 …；只改符号，不做换算） ----------

    private val _currencySymbol = MutableStateFlow(
        (prefs.getString(KEY_CURRENCY_SYMBOL, Money.DEFAULT_SYMBOL) ?: Money.DEFAULT_SYMBOL)
            .also { Money.symbol = it },
    )
    val currencySymbol: StateFlow<String> = _currencySymbol

    fun setCurrencySymbol(symbol: String) {
        val value = symbol.ifBlank { Money.DEFAULT_SYMBOL }
        prefs.edit().putString(KEY_CURRENCY_SYMBOL, value).apply()
        _currencySymbol.value = value
        Money.symbol = value
        AppLog.i("settings", "currencySymbol=$value")
    }

    /**
     * 首页「本月支出（本月已花）」：固定取**当前会计月**（不随流水页翻月变化；区间随月首日滚动）。
     *
     * 口径 = **计入预算的支出**（[com.controleco.app.data.budget.BudgetShare]）：
     * 未被标记「已退款」的支出账单，勾了「不计入预算」的算 0、AA 支出按 金额 ÷ n 折算。
     * 退款是成对记录：原支出标了「已退款」就不再计入支出，退款那笔照常计入收入；
     * **不再做「支出 − 退款到账」的相减**——那是账单还没有退款标记时的旧做法。
     * 剩余/建议日花/状态由 UI 用 Budget.compute 纯函数计算。
     *
     * 注意这是**预算口径**：统计页「实际支出」用 [periodSpendingTotal]（AA 折算，但「不计入预算」
     * 仍按全额算），两者在有「不计入预算」账单时会不同——这是设计口径，不是数据不一致。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeSpent: StateFlow<Long?> = currentPeriod.flatMapLatest { period ->
        val (start, end) = period.toMillisRange(zone)
        txDao.observeBudgetSpendingTotal(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 首页"分类剩余预算"预览：本期各支出分类**计入预算**的金额（同 [homeSpent] 口径）。
     * 与「本月支出」同一口径（各分类相加 = 本月支出）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeCategorySums: StateFlow<List<CategorySumRow>?> = currentPeriod.flatMapLatest { period ->
        val (start, end) = period.toMillisRange(zone)
        txDao.observeBudgetCategorySums(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 首页"资产"视图的走势数据：当前会计月全部交易行。
     * 只用于画两条很淡的趋势线（累计支出 / 存款），不复用账单页翻月状态。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homePeriodRows: StateFlow<List<TransactionRow>?> = currentPeriod.flatMapLatest { period ->
        val (start, end) = period.toMillisRange(zone)
        txDao.observeRows(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 首页"本日剩余"用：**今日计入预算的支出**（同 [homeSpent] 口径，只是范围是今天）。
     *
     * 工资/红包这类收入不参与（收入进来不会让今天的额度变多）；
     * 被标「已退款」的支出不算花掉；「不计入预算」不计入；AA 支出按 金额 ÷ n 折算。
     * 范围跟着"今天"走（[refreshToday]），跨零点或从后台回来时刷新一次，否则零点后还会一直算昨天的账。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeTodaySpent: StateFlow<Long?> = todayRef.flatMapLatest { day ->
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        txDao.observeBudgetSpendingTotal(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 月份 ----------

    private val _month = MutableStateFlow(YearMonth.now(zone))
    val month: StateFlow<YearMonth> = _month

    /** 统计周期：按月 / 按年（账单页与统计页共用） */
    private val _periodMode = MutableStateFlow(PeriodMode.MONTH)
    val periodMode: StateFlow<PeriodMode> = _periodMode

    fun setPeriodMode(mode: PeriodMode) {
        _periodMode.value = mode
        AppLog.i("period", "mode=$mode")
    }

    fun prevMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }

    /** 步进：月模式走一个月，年模式走一年 */
    fun prevPeriod() {
        _month.value = if (_periodMode.value == PeriodMode.YEAR) {
            _month.value.minusYears(1)
        } else {
            _month.value.minusMonths(1)
        }
    }

    fun nextPeriod() {
        _month.value = if (_periodMode.value == PeriodMode.YEAR) {
            _month.value.plusYears(1)
        } else {
            _month.value.plusMonths(1)
        }
    }

    /** 直接跳到指定月份（账单页月份胶囊的选择面板） */
    fun setMonth(m: YearMonth) { _month.value = m }

    /**
     * 翻月选中月份（语义 = 会计月开始所在的月）对应的毫秒区间。
     * 首日 = 1 时即自然月；首日 = 2 时"2026年10月"= 10-02 ~ 11-02（左闭右开）。
     */
    private fun range(m: YearMonth): Pair<Long, Long> =
        MonthPeriod.of(m, _monthStartDay.value).toMillisRange(zone)

    /** 年区间：12 个连续会计月（y 年 1 月首日（含）→ 次年 1 月首日（不含）） */
    private fun yearRange(y: Int): Pair<Long, Long> =
        MonthPeriod.year(y, _monthStartDay.value).toMillisRange(zone)

    /** 当前周期的毫秒区间（随月份/年份/周期模式/月首日变化） */
    private val periodRange: StateFlow<Pair<Long, Long>> =
        combine(_month, _periodMode, _monthStartDay) { m, mode, startDay ->
            when (mode) {
                PeriodMode.MONTH -> MonthPeriod.of(m, startDay).toMillisRange(zone)
                PeriodMode.YEAR -> MonthPeriod.year(m.year, startDay).toMillisRange(zone)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), range(YearMonth.now(zone)))

    // 账单/统计共用的当月数据：stateIn 缓存上一次结果 —— 切标签页回来先显示缓存、
    // 后台再刷新，不再"每次进页面从空闪到满"；没有订阅者 5 秒后自动停掉 Room 观察。
    // 退款记录始终参与（不再有"显示退款记录"开关）。
    @OptIn(ExperimentalCoroutinesApi::class)
    val periodRows: StateFlow<List<TransactionRow>?> =
        periodRange.flatMapLatest { (start, end) -> txDao.observeRows(start, end) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val periodTotals: StateFlow<List<TypeTotalRow>?> =
        periodRange.flatMapLatest { (start, end) -> txDao.observeTypeTotals(start, end) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 本期各支出分类的**实际支出**（AA 按 m ÷ n 折算，「不计入预算」不影响）：
     * 统计页分类圆环（占比）与**预算对比清单**都用它——两者同一口径，
     * 所以清单各行之恒等于 [periodSpendingTotal]、行占比加起来是 100%。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val periodCategorySums: StateFlow<List<CategorySumRow>?> =
        periodRange.flatMapLatest { (start, end) -> txDao.observeSpendingCategorySums(start, end) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 本期「实际支出」= 未被标记「已退款」的支出，**AA 按 m ÷ n 折算、「不计入预算」按全额**
     * （钱确实花了，只是不进预算额度）。统计页 `02` 的总支出与分类圆环用它；
     * 首页预算卡是预算口径（[homeSpent]）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val periodSpendingTotal: StateFlow<Long?> = periodRange.flatMapLatest { (start, end) ->
        txDao.observeSpendingTotal(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accounts: StateFlow<List<AccountEntity>> =
        accountDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accountBalances: Flow<List<AccountBalanceRow>> = txDao.observeBalances()

    /** 首页"总存款"预览：全部账户（初始金额 + 交易净额）合计 */
    val totalDepositCents: StateFlow<Long?> = accountBalances
        .map { rows -> rows.sumOf { it.initialBalanceCents + it.signedSumCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 交易 ----------

    fun saveTransaction(
        id: Long,
        type: TxType,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        timestamp: Long,
        note: String,
        /** 商户/收款方：通知捕获自带，手动记录可自定义（账单列表标题优先显示它） */
        counterparty: String = "",
        /** 不计入预算（只对支出生效；收入行强制 false） */
        budgetExcluded: Boolean = false,
        /** AA 人数（0 = 未开启；只对支出生效，超出 2..99 会被归一化为 0） */
        aaShares: Int = 0,
    ) {
        // 收入没有预算口径：统一按 0 落库，避免「先勾支出再切收入」把隐形标记写进库里
        val excluded = type == TxType.EXPENSE && budgetExcluded
        val shares = if (type == TxType.EXPENSE) BudgetShare.normalizeShares(aaShares) else 0
        viewModelScope.launch(Dispatchers.IO) {
            if (id > 0) {
                txDao.getById(id)?.let { old ->
                    txDao.update(
                        old.copy(
                            type = type, amountCents = amountCents, categoryId = categoryId,
                            accountId = accountId, timestamp = timestamp, note = note,
                            counterparty = counterparty,
                            budgetExcluded = excluded, aaShares = shares,
                        )
                    )
                    // 编辑的是捕获来的交易 → 学习这次归类（"越用越准"；手动记账不学习）
                    if (old.source != TxSource.MANUAL) {
                        MerchantRules.learn(merchantRuleDao, type, categoryId, accountId, counterparty, note)
                    }
                }
            } else {
                txDao.insert(
                    TransactionEntity(
                        type = type, amountCents = amountCents, categoryId = categoryId,
                        accountId = accountId, timestamp = timestamp, note = note,
                        counterparty = counterparty,
                        budgetExcluded = excluded, aaShares = shares,
                    )
                )
            }
        }
    }

    fun deleteTransaction(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteById(id)
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    // ---------- 批量删除（账单页：多选 / 按月 / 按年 / 全部） ----------

    suspend fun countTransactionsInRange(startMillis: Long, endMillis: Long): Int =
        withContext(Dispatchers.IO) { txDao.countByRange(startMillis, endMillis) }

    suspend fun countAllTransactions(): Int =
        withContext(Dispatchers.IO) { txDao.countAll() }

    fun deleteTransactionsByIds(ids: Collection<Long>, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteByIds(ids.toList())
                AppLog.i("tx", "batch deleted ${ids.size} by ids")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "batch delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    fun deleteTransactionsInRange(startMillis: Long, endMillis: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteByRange(startMillis, endMillis)
                AppLog.i("tx", "batch deleted in range [$startMillis, $endMillis)")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "range delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    fun deleteAllTransactions(onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteAll()
                AppLog.i("tx", "all transactions deleted")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "delete all failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    suspend fun transactionById(id: Long): TransactionEntity? =
        withContext(Dispatchers.IO) { txDao.getById(id) }

    // ---------- 账户 ----------

    fun saveAccount(id: Long, name: String, initialBalanceCents: Long, note: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (id > 0) {
                    accountDao.getById(id)?.let { old ->
                        accountDao.update(old.copy(name = name, initialBalanceCents = initialBalanceCents, note = note))
                    }
                } else {
                    accountDao.insert(AccountEntity(name = name, initialBalanceCents = initialBalanceCents, note = note))
                }
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("account", "save failed", e)
                withContext(Dispatchers.Main) { onResult("保存失败") }
            }
        }
    }

    fun deleteAccount(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val used = txDao.countByAccount(id)
            if (used > 0) {
                withContext(Dispatchers.Main) { onResult("该账户下有 $used 笔交易，无法删除") }
            } else {
                accountDao.deleteById(id)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    // ---------- 分类 ----------

    /** 保存分类（新增或改名 / 换图标 / 改类型）；icon 规格见 [CategoryIcon] */
    fun saveCategory(
        id: Long,
        name: String,
        type: TxType,
        icon: String,
        onResult: (savedId: Long, error: String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                withContext(Dispatchers.Main) { onResult(0L, "请输入名称") }
                return@launch
            }
            try {
                val all = categoryDao.getAll()
                val current = all.firstOrNull { it.id == id }
                // 保留名（「其他」/ 收入「退款」）不能改名或改类型——规则见 CategoryRules
                CategoryRules.editError(current, trimmed, type)?.let { err ->
                    withContext(Dispatchers.Main) { onResult(0L, err) }
                    return@launch
                }
                // 同类型下不允许同名（支出与收入各自可以有一个「其他」）
                CategoryRules.duplicateNameError(all, id, trimmed, type)?.let { err ->
                    withContext(Dispatchers.Main) { onResult(0L, err) }
                    return@launch
                }
                val savedId = if (id > 0) {
                    current?.let { old ->
                        // 换图标时清掉旧的图片文件，避免内部存储越攒越多
                        if (old.icon != icon) {
                            CategoryIcon.deleteImage(getApplication(), CategoryIcon.imageOf(old.icon))
                        }
                        categoryDao.update(old.copy(name = trimmed, type = type, icon = icon))
                    }
                    id
                } else {
                    categoryDao.insert(CategoryEntity(name = trimmed, type = type, icon = icon))
                }
                withContext(Dispatchers.Main) { onResult(savedId, null) }
            } catch (e: Exception) {
                AppLog.e("category", "save failed", e)
                withContext(Dispatchers.Main) { onResult(0L, "保存失败") }
            }
        }
    }

    /** 删除分类：其下流水改挂到同类型的「其他」；「其他」与收入「退款」不可删 */
    fun deleteCategory(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = categoryDao.getAll()
                val target = all.firstOrNull { it.id == id }
                if (target == null) {
                    withContext(Dispatchers.Main) { onResult("分类不存在") }
                    return@launch
                }
                CategoryRules.deleteError(target)?.let { err ->
                    withContext(Dispatchers.Main) { onResult(err) }
                    return@launch
                }
                var fallback = all.firstOrNull { it.type == target.type && it.name == CategoryRules.FALLBACK_NAME }
                if (fallback == null) {
                    val newId = categoryDao.insert(
                        CategoryEntity(name = CategoryRules.FALLBACK_NAME, type = target.type),
                    )
                    fallback = categoryDao.getAll().firstOrNull { it.id == newId }
                }
                if (fallback == null) {
                    withContext(Dispatchers.Main) { onResult("删除失败") }
                    return@launch
                }
                txDao.reassignCategory(id, fallback.id)
                categoryDao.deleteById(id)
                CategoryIcon.deleteImage(getApplication(), CategoryIcon.imageOf(target.icon))
                setCategoryBudget(id, 0L) // 分类删除时同步清理其分类预算
                setCategoryBudgetPercent(id, 0)
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("category", "delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    // ---------- 设置：分类预算（金额 / 比例两种方案，全局开关；首页"分类剩余预算"的数据源） ----------

    /** 全局方案：金额（月金额）或比例（占月预算百分比，见 [CategoryBudgetMath]） */
    private val _categoryBudgetMode = MutableStateFlow(
        if (prefs.getString(KEY_CAT_BUDGET_MODE, null) == MODE_PERCENT) {
            CategoryBudgetMode.PERCENT
        } else {
            CategoryBudgetMode.AMOUNT
        },
    )
    val categoryBudgetMode: StateFlow<CategoryBudgetMode> = _categoryBudgetMode

    private fun loadCategoryBudgetAmounts(): Map<Long, Long> = prefs.all.entries
        .mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_CAT_BUDGET_PREFIX)) return@mapNotNull null
            val id = key.removePrefix(KEY_CAT_BUDGET_PREFIX).toLongOrNull() ?: return@mapNotNull null
            val cents = (value as? Long) ?: return@mapNotNull null
            if (cents > 0) id to cents else null
        }
        .toMap()

    private fun loadCategoryBudgetPercents(): Map<Long, Int> = prefs.all.entries
        .mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_CAT_BUDGET_PCT_PREFIX)) return@mapNotNull null
            val id = key.removePrefix(KEY_CAT_BUDGET_PCT_PREFIX).toLongOrNull() ?: return@mapNotNull null
            val bp = (value as? Int) ?: return@mapNotNull null
            if (bp > 0) id to bp else null
        }
        .toMap()

    private val _categoryBudgetAmounts = MutableStateFlow(loadCategoryBudgetAmounts())
    private val _categoryBudgetPercents = MutableStateFlow(loadCategoryBudgetPercents())

    /** 各分类预算的比例（万分比，1% = 100；只含已设置项）——比例方案下列表/编辑框显示它 */
    val categoryBudgetPercents: StateFlow<Map<Long, Int>> = _categoryBudgetPercents

    /**
     * 各分类预算的**生效月金额**（分）：金额方案直接取金额；比例方案按当前月预算折算。
     * 首页分类环、统计页饼图与"还可分配"都读它——两种方案在下游看来没有区别。
     */
    val categoryBudgets: StateFlow<Map<Long, Long>> = combine(
        _categoryBudgetAmounts, _categoryBudgetPercents, _categoryBudgetMode, monthlyBudgetCents,
    ) { amounts, percents, mode, monthly ->
        if (mode == CategoryBudgetMode.AMOUNT) {
            amounts
        } else {
            percents.mapValues { (_, bp) -> CategoryBudgetMath.centsOf(bp, monthly) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), effectiveCategoryBudgets())

    private fun effectiveCategoryBudgets(): Map<Long, Long> =
        if (_categoryBudgetMode.value == CategoryBudgetMode.AMOUNT) {
            _categoryBudgetAmounts.value
        } else {
            val monthly = effectiveMonthlyBudget()
            _categoryBudgetPercents.value.mapValues { (_, bp) -> CategoryBudgetMath.centsOf(bp, monthly) }
        }

    /** 金额方案：cents ≤ 0 表示清除该分类预算（比例一侧的值保留，切换方案不丢） */
    fun setCategoryBudget(categoryId: Long, cents: Long) {
        val editor = prefs.edit()
        if (cents > 0) {
            editor.putLong(KEY_CAT_BUDGET_PREFIX + categoryId, cents)
        } else {
            editor.remove(KEY_CAT_BUDGET_PREFIX + categoryId)
        }
        editor.apply()
        _categoryBudgetAmounts.value = loadCategoryBudgetAmounts()
        AppLog.i("settings", "categoryBudget[$categoryId]=${Money.formatYuan(cents)}")
    }

    /** 比例方案：bp（万分比）≤ 0 表示清除该分类预算 */
    fun setCategoryBudgetPercent(categoryId: Long, bp: Int) {
        val v = bp.coerceIn(0, CategoryBudgetMath.MAX_PERCENT)
        val editor = prefs.edit()
        if (v > 0) {
            editor.putInt(KEY_CAT_BUDGET_PCT_PREFIX + categoryId, v)
        } else {
            editor.remove(KEY_CAT_BUDGET_PCT_PREFIX + categoryId)
        }
        editor.apply()
        _categoryBudgetPercents.value = loadCategoryBudgetPercents()
        AppLog.i("settings", "categoryBudgetPct[$categoryId]=${CategoryBudgetMath.formatPercent(v)}")
    }

    /**
     * 切换分类预算方案（全局）：切换时按当前月预算换算另一侧的值，
     * 保证两种方案的读数始终一致（月预算未设置时无法折算，保持原值不动）。
     * 规则：切到比例 = 有金额的分类重算比例（金额侧是当前事实）；切到金额 = 有比例的分类重算金额。
     */
    fun setCategoryBudgetMode(mode: CategoryBudgetMode) {
        prefs.edit().putString(KEY_CAT_BUDGET_MODE, if (mode == CategoryBudgetMode.PERCENT) MODE_PERCENT else MODE_AMOUNT).apply()
        _categoryBudgetMode.value = mode
        val monthly = effectiveMonthlyBudget()
        if (monthly > 0L) {
            if (mode == CategoryBudgetMode.PERCENT) {
                val percents = _categoryBudgetPercents.value.toMutableMap()
                var changed = false
                _categoryBudgetAmounts.value.forEach { (id, cents) ->
                    val bp = CategoryBudgetMath.percentOf(cents, monthly)
                    if (bp != null && percents[id] != bp) {
                        percents[id] = bp
                        changed = true
                    }
                }
                if (changed) {
                    persistCategoryBudgetPercents(percents)
                    _categoryBudgetPercents.value = loadCategoryBudgetPercents()
                }
            } else {
                val amounts = _categoryBudgetAmounts.value.toMutableMap()
                var changed = false
                _categoryBudgetPercents.value.forEach { (id, bp) ->
                    val cents = CategoryBudgetMath.centsOf(bp, monthly)
                    if (cents > 0L && amounts[id] != cents) {
                        amounts[id] = cents
                        changed = true
                    }
                }
                if (changed) {
                    persistCategoryBudgetAmounts(amounts)
                    _categoryBudgetAmounts.value = loadCategoryBudgetAmounts()
                }
            }
        }
        AppLog.i("settings", "categoryBudgetMode=$mode")
    }

    private fun persistCategoryBudgetPercents(percents: Map<Long, Int>) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_CAT_BUDGET_PCT_PREFIX) }.forEach(editor::remove)
        percents.forEach { (id, bp) -> editor.putInt(KEY_CAT_BUDGET_PCT_PREFIX + id, bp) }
        editor.apply()
    }

    private fun persistCategoryBudgetAmounts(amounts: Map<Long, Long>) {
        val editor = prefs.edit()
        // 注意排除比例键："category_budget_pct_" 也以 "category_budget_" 开头，前缀匹配不能顺手删掉它
        prefs.all.keys
            .filter { it.startsWith(KEY_CAT_BUDGET_PREFIX) && !it.startsWith(KEY_CAT_BUDGET_PCT_PREFIX) }
            .forEach(editor::remove)
        amounts.forEach { (id, cents) -> editor.putLong(KEY_CAT_BUDGET_PREFIX + id, cents) }
        editor.apply()
    }

    // ---------- 账单导入 ----------

    private val _parsedBill = MutableStateFlow<ParsedBill?>(null)
    val parsedBill: StateFlow<ParsedBill?> = _parsedBill

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    private val _importDone = MutableStateFlow<ImportCommitResult?>(null)
    val importDone: StateFlow<ImportCommitResult?> = _importDone

    fun parseBillFile(uriString: String) {
        _parsedBill.value = null
        _importError.value = null
        _importDone.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<AccBookApp>()
                val bytes = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取所选文件")
                val bill = BillParsers.parse(bytes)
                    ?: throw IllegalArgumentException("无法识别账单格式，请选择微信/支付宝导出的账单 CSV")
                _parsedBill.value = bill
            } catch (e: Exception) {
                AppLog.e("import", "parse failed", e)
                _importError.value = e.message ?: "解析失败"
            }
        }
    }

    fun commitImport(bill: ParsedBill, accountId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cats = categoryDao.getAll()
                val entities = bill.rows.map { row ->
                    // 退款收入一律归「退款」分类：导入时按商户名猜出的购物/餐饮对退款行没有意义
                    val categoryId = (if (row.refundMark == RefundMark.REFUND_INCOME) {
                        RefundCategory.incomeCategoryId(cats)
                    } else {
                        cats.firstOrNull { it.type == row.type && it.name == row.guessedCategoryName }
                            ?.id
                            ?: cats.firstOrNull { it.type == row.type && it.name == "其他" }?.id
                            ?: cats.firstOrNull { it.type == row.type }?.id
                    })
                        ?: error("缺少${if (row.type == TxType.EXPENSE) "支出" else "收入"}分类，请先在分类管理中添加")
                    TransactionEntity(
                        type = row.type,
                        amountCents = row.amountCents,
                        categoryId = categoryId,
                        accountId = accountId,
                        timestamp = row.timestamp,
                        note = row.note,
                        counterparty = row.counterparty,
                        source = when (bill.source) {
                            BillSource.WECHAT -> TxSource.WECHAT
                            BillSource.ALIPAY -> TxSource.ALIPAY
                        },
                        externalId = row.externalId,
                        refundMark = row.refundMark,
                        // 导入的退款账单视为「已处理」：账单本身已经把原支出标成「已退款」，
                        // 不需要用户再指认，也不进「待确认账单」。
                        refundPending = false,
                    )
                }
                val ids = txDao.insertAllIgnore(entities)
                val inserted = ids.count { it != -1L }
                val duplicated = ids.count { it == -1L }
                AppLog.i("import", "committed: inserted=$inserted duplicated=$duplicated")
                withContext(Dispatchers.Main) { _importDone.value = ImportCommitResult(inserted, duplicated) }
            } catch (e: Exception) {
                AppLog.e("import", "commit failed", e)
                withContext(Dispatchers.Main) { _importError.value = e.message ?: "导入失败" }
            }
        }
    }

    fun clearImportState() {
        _parsedBill.value = null
        _importError.value = null
        _importDone.value = null
    }

    // ---------- 自动记账（阶段二：通知监听通道） ----------

    val pendingList: StateFlow<List<PendingTransactionEntity>> =
        pendingDao.observePending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 「待确认账单」里的退款待处理条目：已入账的退款收入，等用户选被退款的支出或「记为收入」。
     * 与捕获待确认条目**并进同一页**，不单独分区（用户要求）。
     */
    val pendingRefunds: StateFlow<List<TransactionRow>> =
        txDao.observePendingRefunds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 待处理总数 = 捕获待确认 + 退款待处理。
     * 个人页「待确认」胶囊与设置页「等待处理」都用它，别再各算一套。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingCount: StateFlow<Int> =
        combine(pendingDao.observeCount(), txDao.observePendingRefundCount()) { captures, refunds ->
            captures + refunds
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 捕获日志通道筛选：null = 全部（通知 + 页面合并） */
    val captureFilter = MutableStateFlow<CaptureChannel?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val captureRows: StateFlow<List<CapturedNotificationEntity>> = captureFilter
        .flatMapLatest { channel ->
            if (channel == null) captureDao.observeRecent(300)
            else captureDao.observeRecentBySource(channel, 300)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setCaptureFilter(channel: CaptureChannel?) {
        captureFilter.value = channel
    }

    val listenerConnected: StateFlow<Boolean> = CaptureCenter.connected
    val a11yConnected: StateFlow<Boolean> = CaptureCenter.a11yConnected
    val lastCaptureAt: StateFlow<Long> = CaptureCenter.lastEventAt

    private val _captureEnabled = MutableStateFlow(CaptureCenter.isCaptureEnabled())
    val captureEnabled: StateFlow<Boolean> = _captureEnabled

    fun setCaptureEnabled(enabled: Boolean) {
        CaptureCenter.setCaptureEnabled(enabled)
        _captureEnabled.value = enabled
    }

    private val _a11yCaptureEnabled = MutableStateFlow(CaptureCenter.isA11yCaptureEnabled())
    val a11yCaptureEnabled: StateFlow<Boolean> = _a11yCaptureEnabled

    fun setA11yCaptureEnabled(enabled: Boolean) {
        CaptureCenter.setA11yCaptureEnabled(enabled)
        _a11yCaptureEnabled.value = enabled
    }

    private val _debugCaptureAll = MutableStateFlow(CaptureCenter.isDebugCaptureAll(BuildConfig.DEBUG))
    val debugCaptureAll: StateFlow<Boolean> = _debugCaptureAll

    fun setDebugCaptureAll(enabled: Boolean) {
        CaptureCenter.setDebugCaptureAll(enabled)
        _debugCaptureAll.value = enabled
    }

    private val _debugCaptureAllPages = MutableStateFlow(CaptureCenter.isDebugCaptureAllPages(BuildConfig.DEBUG))
    val debugCaptureAllPages: StateFlow<Boolean> = _debugCaptureAllPages

    fun setDebugCaptureAllPages(enabled: Boolean) {
        CaptureCenter.setDebugCaptureAllPages(enabled)
        _debugCaptureAllPages.value = enabled
    }

    // ---------- 自动记账溯源（开发者功能，默认关） ----------

    private val _traceEnabled = MutableStateFlow(CaptureCenter.isTraceEnabled())
    val traceEnabled: StateFlow<Boolean> = _traceEnabled

    fun setTraceEnabled(enabled: Boolean) {
        CaptureCenter.setTraceEnabled(enabled)
        _traceEnabled.value = enabled
    }

    // ---------- 商户归类学习（越用越准） ----------

    /** 学到的规则（最近在前） */
    val merchantRules: StateFlow<List<com.controleco.app.data.db.MerchantRuleEntity>> =
        merchantRuleDao.observeRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteMerchantRule(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { merchantRuleDao.deleteById(id) }
    }

    fun clearMerchantRules() {
        viewModelScope.launch(Dispatchers.IO) { merchantRuleDao.clearAll() }
    }

    /** 溯源记录（最新在前，联查已带对应账单 id 与待确认状态） */
    val traceRows: StateFlow<List<CaptureTraceRow>> =
        traceDao.observeRecent(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val traceCount: StateFlow<Int> =
        traceDao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun clearTraces() {
        viewModelScope.launch(Dispatchers.IO) {
            traceDao.clearAll()
            AppLog.i("Trace", "capture traces cleared")
        }
    }

    fun isListenerGranted(): Boolean {
        val app = getApplication<AccBookApp>()
        return NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
    }

    /** 覆盖安装后系统可能静默关闭通知监听：权限页据此显示复查提示（监听重连后自动清除） */
    fun isReauthNeeded(): Boolean = CaptureCenter.isReauthNeeded()

    private val _refundLinkEnabled = MutableStateFlow(CaptureCenter.isRefundLinkEnabled())
    val refundLinkEnabled: StateFlow<Boolean> = _refundLinkEnabled

    fun setRefundLinkEnabled(enabled: Boolean) {
        CaptureCenter.setRefundLinkEnabled(enabled)
        _refundLinkEnabled.value = enabled
    }

    private val _captureNotifyEnabled = MutableStateFlow(CaptureCenter.isCaptureNotifyEnabled())
    val captureNotifyEnabled: StateFlow<Boolean> = _captureNotifyEnabled

    fun setCaptureNotifyEnabled(enabled: Boolean) {
        CaptureCenter.setCaptureNotifyEnabled(enabled)
        _captureNotifyEnabled.value = enabled
    }

    private val _autoEntryEnabled = MutableStateFlow(CaptureCenter.isAutoEntryEnabled())
    val autoEntryEnabled: StateFlow<Boolean> = _autoEntryEnabled

    fun setAutoEntryEnabled(enabled: Boolean) {
        CaptureCenter.setAutoEntryEnabled(enabled)
        _autoEntryEnabled.value = enabled
        // 互斥：开启自动入账即关闭悬浮窗入账（CaptureCenter 侧已写入，这里同步 UI 状态）
        if (enabled) _overlayConfirmEnabled.value = false
    }

    // ---------- 银行动账（短信 / 银行 App 推送） ----------

    private val _bankPushEnabled = MutableStateFlow(CaptureCenter.isBankPushEnabled())
    val bankPushEnabled: StateFlow<Boolean> = _bankPushEnabled

    fun setBankPushEnabled(enabled: Boolean) {
        CaptureCenter.setBankPushEnabled(enabled)
        _bankPushEnabled.value = enabled
    }

    private val _smsCaptureEnabled = MutableStateFlow(CaptureCenter.isSmsCaptureEnabled())
    val smsCaptureEnabled: StateFlow<Boolean> = _smsCaptureEnabled

    fun setSmsCaptureEnabled(enabled: Boolean) {
        CaptureCenter.setSmsCaptureEnabled(enabled)
        _smsCaptureEnabled.value = enabled
    }

    /** 收短信权限（银行短信通道的前提） */
    fun isSmsPermissionGranted(): Boolean = CaptureCenter.hasSmsPermission()

    // ---------- 悬浮确认卡 ----------

    private val _overlayConfirmEnabled = MutableStateFlow(CaptureCenter.isOverlayConfirmEnabled())
    val overlayConfirmEnabled: StateFlow<Boolean> = _overlayConfirmEnabled

    fun setOverlayConfirmEnabled(enabled: Boolean) {
        CaptureCenter.setOverlayConfirmEnabled(enabled)
        _overlayConfirmEnabled.value = enabled
        // 互斥：开启悬浮窗入账即关闭自动入账（CaptureCenter 侧已写入，这里同步 UI 状态）
        if (enabled) _autoEntryEnabled.value = false
    }

    /** 悬浮窗权限（悬浮确认卡的前提；未授权自动回落通知） */
    fun isOverlayGranted(): Boolean = CaptureCenter.canDrawOverlays()

    /** 最近一次弹卡判定（"已弹出"或未弹原因）：权限页展示，排查"为什么没弹卡" */
    val overlayDecision: StateFlow<String?> = CaptureCenter.overlayDecision

    /** debug：弹一张样例确认卡（立即/延迟；不落库） */
    fun showOverlayTest(delayMs: Long = 0, onResult: (String) -> Unit) =
        CaptureCenter.showOverlayTest(delayMs, onResult)

    private val _autoUpdateEnabled = MutableStateFlow(AppUpdate.isAutoUpdateEnabled(getApplication()))
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled

    fun setAutoUpdateEnabled(enabled: Boolean) {
        AppUpdate.setAutoUpdateEnabled(getApplication(), enabled)
        _autoUpdateEnabled.value = enabled
    }

    /** 手动检查更新（设置页「检查更新」）：无视开关与间隔；发现新版本走全局弹窗，无更新/失败经回调提示 */
    fun checkUpdateNow(onResult: (String?) -> Unit) = AppUpdate.checkNow(getApplication(), onResult)

    /** 通知权限（安卓 13+ 运行时） */
    fun canPostNotifications(): Boolean = CaptureCenter.canPostNotifications()

    /** debug：20 秒后发测试捕获通知 */
    fun scheduleNotifyTest(onResult: (String) -> Unit) = CaptureCenter.scheduleNotifyTest(onResult)

    // ---------- 登记退款（无金额退款通知 → 选一笔被退款的账单） ----------

    /** 近 60 天还没标「已退款」的支出候选 */
    suspend fun refundCandidates(): List<TransactionRow> = withContext(Dispatchers.IO) {
        txDao.recentUnrefundedExpenses(System.currentTimeMillis() - 60L * 24 * 3_600_000)
    }

    /**
     * 登记退款：生成一条退款收入（REFUND_INCOME，金额可改以支持部分退款，入原账户），
     * 并把用户选中的那笔支出标成 REFUNDED_EXPENSE（不再计入支出）。
     *
     * **两笔记录之间不建立任何关联**：支出只是"被标了已退款"，退款收入只是"一笔收入"。
     * 生成的退款收入 `refundPending = false`，不会进「待确认账单」。
     */
    fun registerRefund(expenseId: Long, amountCents: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val expense = txDao.getById(expenseId)
                when {
                    expense == null -> { onDone("原支出记录不存在"); return@launch }
                    expense.refundMark != RefundMark.NONE -> { onDone("该笔支出已标记退款"); return@launch }
                    amountCents <= 0 -> { onDone("退款金额需大于 0"); return@launch }
                }
                val cats = categoryDao.getAll()
                // 退款收入归「退款」分类（不是收入「其他」）：账单里一眼能看出这是退回来的钱
                val incomeCatId = RefundCategory.incomeCategoryId(cats)
                    ?: error("缺少收入分类，请先在分类管理中添加")
                txDao.insert(
                    TransactionEntity(
                        type = TxType.INCOME,
                        amountCents = amountCents,
                        categoryId = incomeCatId,
                        accountId = expense.accountId,
                        timestamp = System.currentTimeMillis(),
                        note = "退款收入（手动登记）",
                        counterparty = expense.counterparty,
                        source = TxSource.NOTIFICATION,
                        refundMark = RefundMark.REFUND_INCOME,
                        refundPending = false,
                    ),
                )
                txDao.markExpenseRefunded(expenseId)
                AppLog.i("refund", "registered refund for expense #$expenseId amount=${Money.formatYuan(amountCents)}")
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                AppLog.e("refund", "register failed", e)
                withContext(Dispatchers.Main) { onDone("登记失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    // ---------- 待处理退款（已入账的退款收入，只等用户处理） ----------

    /** 同金额的支出候选：待处理退款点开后列出来给用户选 */
    suspend fun refundExpenseCandidates(amountCents: Long): List<TransactionRow> =
        withContext(Dispatchers.IO) { txDao.expenseCandidatesByAmount(amountCents) }

    /**
     * 处理一笔待处理的退款：**把用户选中的那笔支出标成「已退款」**（不再计入支出），
     * 退款自己则标成「已处理」（`refundPending = 0`，不再出现在待确认里）。
     *
     * 两笔记录之间**不写任何关联**：这就是两个独立的标记。支出统计的口径是
     * 「未退款的支出之和」，所以标记一打上，这笔钱就不再计入本月支出与分类预算。
     *
     * 校验：退款必须存在、是退款收入且还没处理；原支出必须存在、是支出且还没标过已退款。
     */
    fun handlePendingRefund(refundId: Long, expenseId: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val refund = txDao.getById(refundId)
                val expense = txDao.getById(expenseId)
                if (refund == null || refund.refundMark != RefundMark.REFUND_INCOME ||
                    !refund.refundPending || expense == null || expense.type != TxType.EXPENSE
                ) {
                    val message = when {
                        refund == null -> "退款记录不存在"
                        refund.refundMark != RefundMark.REFUND_INCOME -> "该记录不是退款收入"
                        !refund.refundPending -> "该退款已处理过"
                        expense == null -> "原支出记录不存在"
                        else -> "只能选择支出账单"
                    }
                    withContext(Dispatchers.Main) { onDone(message) }
                    return@launch
                }
                if (expense.refundMark != RefundMark.NONE) {
                    withContext(Dispatchers.Main) { onDone("该支出已标记过已退款") }
                    return@launch
                }
                txDao.markExpenseRefunded(expenseId)
                txDao.clearRefundPending(refundId)
                AppLog.i("refund", "refund #$refundId handled, expense #$expenseId marked refunded")
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                AppLog.e("refund", "handle pending refund failed", e)
                withContext(Dispatchers.Main) { onDone("处理失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * 「记为收入」：这笔退款没有对应的支出账单，只按普通收入处理。
     * 清掉退款标记后它不再出现在待处理里；原支出保持原样（照常计入支出）。
     */
    fun markRefundAsIncome(refundId: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val refund = txDao.getById(refundId)
                if (refund == null || refund.refundMark != RefundMark.REFUND_INCOME) {
                    withContext(Dispatchers.Main) { onDone("该记录不是退款收入") }
                    return@launch
                }
                txDao.markRefundAsIncome(refundId)
                AppLog.i("refund", "refund #$refundId kept as plain income")
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                AppLog.e("refund", "mark refund as income failed", e)
                withContext(Dispatchers.Main) { onDone("操作失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /** 系统无障碍设置中是否已开启采集服务 */
    fun isA11yEnabled(): Boolean = AccBookAccessibilityService.isEnabled(getApplication())

    /** 系统随选朗读/TalkBack 是否已开启（微信 8.0.52+ 页面节点白名单的前提） */
    fun isScreenReaderEnabled(): Boolean = ScreenReaderAccess.isEnabled(getApplication())

    fun discardPending(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            // 删除待确认条目只释放字段锁；保留已弹卡记录，阻止迟到的采集再次打扰
            pendingDao.getById(id)?.let {
                com.controleco.app.capture.OverlayFieldGuards.clear(it.dedupKey)
            }
            PendingActions.discard(pendingDao, id)
        }
    }

    // ---------- 金额待补录 ----------

    private val _amountReplyEnabled = MutableStateFlow(CaptureCenter.isAmountReplyEnabled())
    val amountReplyEnabled: StateFlow<Boolean> = _amountReplyEnabled

    fun setAmountReplyEnabled(enabled: Boolean) {
        CaptureCenter.setAmountReplyEnabled(enabled)
        _amountReplyEnabled.value = enabled
    }

    /**
     * 待确认列表里补录金额（0 元占位条）：重算去重键；同笔完整捕获已到则占位条退场。
     * 回调带说明文本（失败/已处理），供 UI 提示。
     */
    fun setPendingAmount(id: Long, amountCents: Long, onDone: (String?) -> Unit = {}) {
        CaptureCenter.applyPendingAmount(id, amountCents) { result ->
            val message = when (result) {
                is CaptureCenter.AmountUpdateResult.Ok -> null
                CaptureCenter.AmountUpdateResult.NotFound -> "这条已处理过（同笔的完整记录已到）"
                CaptureCenter.AmountUpdateResult.Invalid -> "金额无效，请重新输入"
            }
            onDone(message)
        }
    }

    fun confirmPending(
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String = item.counterparty,
    ) {
        // 金额待补条（0 元）不能直接入账：先在列表里补金额
        if (item.amountCents <= 0) {
            AppLog.w("pending", "confirm rejected (amount missing) #${item.id}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val txId = PendingActions.confirm(db, txDao, pendingDao, merchantRuleDao, item, categoryId, accountId, counterparty)
            // 条目确认后只释放字段锁；已弹卡标记用于拦截后到的重复事件。
            com.controleco.app.capture.OverlayFieldGuards.clear(item.dedupKey)
            if (txId != null) {
                // 先弹了悬浮卡、稍后在待确认列表里按“计入”的情况也发送最终入账通知。
                CaptureCenter.notifyConfirmedFromPendingList(item.copy(counterparty = counterparty), txId)
            }
        }
    }

    fun confirmAllPending() {
        viewModelScope.launch(Dispatchers.IO) {
            // 只确认金额完整且非 ASK 的条目：ASK（微信红包待确认）必须由用户逐条明确决定，
            // 不能被「全部确认」批量入账；金额待补的也必须逐条补录或丢弃。
            val items = pendingDao.getAllPending().filter {
                it.status == com.controleco.app.data.db.PendingStatus.PENDING && it.amountCents > 0
            }
            if (items.isEmpty()) return@launch
            val cats = categoryDao.getAll()
            val accs = accountDao.getAll()
            val entities = items.map { item ->
                val cat = cats.firstOrNull {
                    it.id == CaptureDefaults.categoryId(
                        cats, item.type, item.counterparty.ifBlank { item.note },
                        isRefund = item.refundMark == RefundMark.REFUND_INCOME,
                    )
                }
                    ?: return@launch
                TransactionEntity(
                    type = item.type,
                    amountCents = item.amountCents,
                    categoryId = cat.id,
                    accountId = CaptureDefaults.accountId(
                        accs,
                        item.sourcePackage,
                        CaptureDefaults.bankNameOf(item.sourcePackage, item.ruleName),
                        instrumentLabel = item.paymentInstrument,
                    ) ?: return@launch,
                    timestamp = item.timestamp,
                    note = item.note.ifBlank { item.ruleName },
                    counterparty = item.counterparty,
                    source = CaptureDefaults.txSource(item.sourcePackage),
                    externalId = "NTF:${item.dedupKey}",
                    refundMark = item.refundMark,
                    // 批量确认的退款收入同样要指认原支出
                    refundPending = item.refundMark == RefundMark.REFUND_INCOME,
                    flowId = item.flowId,
                )
            }
            val inserted = db.withTransaction {
                val ids = txDao.insertAllIgnore(entities)
                items.forEach { pendingDao.deleteById(it.id) }
                ids.count { it != -1L }
            }
            // 批量入账只释放字段锁，已弹卡记录继续拦截后到的重复采集
            items.forEach {
                com.controleco.app.capture.OverlayFieldGuards.clear(it.dedupKey)
            }
            AppLog.i("pending", "confirmAll: ${items.size} items, inserted=$inserted")
        }
    }

    /** 待确认确认弹窗的默认分类（退款收入固定「退款」；其余为该类型的"其他"，缺省取该类型第一个） */
    fun guessCategoryId(type: TxType, merchantHint: String = "", isRefund: Boolean = false): Long? =
        CaptureDefaults.categoryId(categories.value, type, merchantHint, isRefund = isRefund)

    /**
     * 按来源推测账户：微信→微信零钱、支付宝→支付宝、银行侧（短信/银行推送）→ 先按银行名匹配
     * 账户名，再退化到"银行"关键词。[ruleName] 供号码兜底的短信取回银行名用。
     */
    fun guessAccountId(sourcePackage: String, ruleName: String = "", instrument: String? = null): Long? =
        CaptureDefaults.accountId(
            accounts.value, sourcePackage, CaptureDefaults.bankNameOf(sourcePackage, ruleName),
            instrumentLabel = instrument,
        )

    fun clearCaptureLog() {
        viewModelScope.launch(Dispatchers.IO) {
            captureDao.clearAll()
            AppLog.i("Ntf", "capture log cleared")
        }
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_CARD_IMAGE = "card_image_uri"
        const val KEY_CARD_TRANSFORM = "card_image_transform"
        const val KEY_CARD_COLOR = "card_image_color"
        const val KEY_CARD_LUMINANCE = "card_image_luminance"
        const val KEY_MONTHLY_BUDGET = "monthly_budget_cents"
        const val KEY_MONTH_START_DAY = "month_start_day"
        const val KEY_DAILY_BUDGET_MODE = "budget_daily_mode"
        const val KEY_DAILY_BUDGET = "daily_budget_cents"
        const val KEY_CURRENCY_SYMBOL = "currency_symbol"
        const val KEY_CAT_BUDGET_PREFIX = "category_budget_"
        const val KEY_CAT_BUDGET_PCT_PREFIX = "category_budget_pct_"
        const val KEY_CAT_BUDGET_MODE = "category_budget_mode"
        const val MODE_PERCENT = "percent"
        const val MODE_AMOUNT = "amount"

        /** 补回「退款」收入分类时的排序位（默认收入分类里排在「红包」之后、「其他」之前） */
        const val REFUND_INCOME_SORT_ORDER = 3
    }
}
