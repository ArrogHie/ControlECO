package com.controleco.app.capture

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.controleco.app.AccBookApp
import com.controleco.app.BuildConfig
import com.controleco.app.capture.overlay.canSyncOverlayEdits
import com.controleco.app.capture.overlay.ConfirmCardWindow
import com.controleco.app.capture.overlay.DismissOutcome
import com.controleco.app.capture.overlay.dismissOutcome
import com.controleco.app.capture.overlay.OVERLAY_DELAY_MS
import com.controleco.app.capture.overlay.OverlayConditions
import com.controleco.app.capture.overlay.shouldShowOverlay
import com.controleco.app.capture.page.PageCandidate
import com.controleco.app.capture.page.PageFlow
import com.controleco.app.capture.page.PageFlowTracker
import com.controleco.app.capture.page.PageParseResult
import com.controleco.app.capture.page.PageParserRegistry
import com.controleco.app.capture.page.PageScene
import com.controleco.app.capture.page.PageSnapshot
import com.controleco.app.data.db.AccountDao
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CaptureDao
import com.controleco.app.data.db.CaptureTraceEntity
import com.controleco.app.data.db.CapturedNotificationEntity
import com.controleco.app.data.db.MerchantRuleDao
import com.controleco.app.data.db.CategoryDao
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingDao
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TraceDao
import com.controleco.app.data.db.TraceOutcome
import com.controleco.app.data.db.TransactionDao
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 采集中心：通知监听、无障碍页面、银行动账短信三条通道的唯一入口。
 * 通道流程一致：原始快照落库（不解析）→ 规则引擎 → 待确认队列。
 * 跨通道去重分两层：
 * - 同源（通知⇄页面，10s 窗口，见 CaptureMerge）：只保留一条，**页面信息优先**；
 * - 异源（支付类⇄银行类，±130s，见 CrossChannelDedup）：同一笔消费被微信通知与银行卡
 *   短信/推送同时捕获时归并成一条，银行侧只补充账户线索。
 * 捕获后的处置由三个设置开关决定（见各开关的注释）：
 * - 「入账提醒」：捕获时发横幅通知（也是卡片弹不出时的兜底）；
 * - 「悬浮窗入账」：弹出底部确认卡，关闭卡片/长时间无操作视为入账，点「忽略」才不入账；
 * - 「自动入账」：识别到即直接记账（与悬浮窗入账互斥），条目留 AUTO 记录供页面回填。
 * debug 构建可全量记录（设置开关），Logcat tag=ControlECO 便于真实样本调试。
 * 开发者功能「记录记账来源」开启时，另把每次入账相关捕获的原始来源写入溯源表（见 traceCapture）。
 */
object CaptureCenter {

    private const val PREFS_NAME = "settings"
    private const val KEY_CAPTURE_ENABLED = "auto_capture_enabled"
    private const val KEY_A11Y_CAPTURE_ENABLED = "a11y_capture_enabled"
    private const val KEY_REFUND_LINK_ENABLED = "refund_link_enabled"
    private const val KEY_CAPTURE_NOTIFY_ENABLED = "capture_notify_enabled"
    private const val KEY_AUTO_ENTRY_ENABLED = "auto_entry_enabled"
    private const val KEY_DEBUG_CAPTURE_ALL = "debug_capture_all"
    private const val KEY_DEBUG_CAPTURE_ALL_PAGES = "debug_capture_all_pages"
    private const val KEY_TRACE_ENABLED = "capture_trace_enabled"
    private const val KEY_BANK_PUSH_ENABLED = "bank_push_enabled"
    private const val KEY_SMS_CAPTURE_ENABLED = "sms_capture_enabled"
    private const val KEY_OVERLAY_CONFIRM_ENABLED = "overlay_confirm_enabled"
    private const val KEY_AMOUNT_REPLY_ENABLED = "amount_reply_enabled"
    private const val KEY_NEEDS_REAUTH = "needs_reauth_after_update"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"

    /** 点击通知跳转的路由（MainActivity intent extra） */
    const val EXTRA_ROUTE = "controleco.route"
    const val ROUTE_PENDING = "pending"
    const val ROUTE_REFUND_LINK = "refundLink"

    /** 「已自动入账」横幅直达该笔记录的编辑页（route = 前缀 + 交易 id，追加路由，不改既有契约） */
    const val ROUTE_EDIT_PREFIX = "edit/"

    /** 分享导入：route = 前缀 + 文件 uri（ShareImportActivity 落盘后跳转，AppNav 解析并预解析账单） */
    const val ROUTE_IMPORT_FILE_PREFIX = "import?file="

    /** 悬浮确认卡样例（调试路由：弹一张不入库的样例卡，等价开发者功能里的测试入口） */
    const val ROUTE_OVERLAY_TEST = "overlayTest"

    /** 渠道 v2：横幅提醒（IMPORTANCE_HIGH 才会弹屏幕上方横幅）；旧渠道 capture（DEFAULT 静默）删除 */
    const val CHANNEL_CAPTURE = "capture_alert"
    private const val CHANNEL_LEGACY = "capture"
    private const val NOTIF_TAG = "capture"

    /** 「输入金额」通知的动作前缀（同一个意图按 pendingId 区分） */
    private const val ACTION_AMOUNT_REPLY = "com.controleco.app.action.AMOUNT_REPLY"

    /** 同一内容通知的重发窗口（微信/支付宝常会更新同一条通知导致重复回调） */
    private const val REPOST_WINDOW_MS = 10_000L
    private const val RECENT_KEY_CAP = 256

    /** 捕获日志保留策略 */
    private const val KEEP_DAYS = 7L
    private const val KEEP_ROWS = 2_000

    /** 溯源记录保留条数：只按条数封顶、不按时间清理（开发者证据），可手动清空 */
    private const val TRACE_KEEP_ROWS = 1_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentKeys = LinkedHashMap<String, Long>()

    /**
     * 去重键分配：同一分钟内同来源同金额的第二笔独立交易会拿到带流程后缀的键
     * （否则第二笔会被 dedupKey 唯一索引吞掉，见 DedupKeyAllocator）。
     */
    private val dedupKeyAllocator = DedupKeyAllocator()

    private lateinit var appContext: Context
    private lateinit var txDao: TransactionDao
    private lateinit var captureDao: CaptureDao
    private lateinit var pendingDao: PendingDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var accountDao: AccountDao
    private lateinit var traceDao: TraceDao
    private lateinit var merchantRuleDao: MerchantRuleDao
    private val initialized get() = this::appContext.isInitialized

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _a11yConnected = MutableStateFlow(false)
    val a11yConnected: StateFlow<Boolean> = _a11yConnected

    private val _overlayDecision = MutableStateFlow<String?>(null)

    /** 最近一次弹卡判定（"已弹出"或未弹原因）：权限页展示，便于回看"为什么没弹卡" */
    val overlayDecision: StateFlow<String?> = _overlayDecision
    private var lastOverlayDecision: String?
        get() = _overlayDecision.value
        set(value) {
            _overlayDecision.value = value
        }

    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt

    /** 本应用是否在前台（MainActivity onResume/onPause 维护）：前台时不弹悬浮卡，用应用内列表 */
    @Volatile
    private var appInForeground = false

    /** 无障碍服务跟踪到的当前前台应用包名（未开无障碍时为 null）：悬浮卡的前台匹配用 */
    @Volatile
    private var foregroundPackage: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val db = (appContext as AccBookApp).db
        txDao = db.transactionDao()
        captureDao = db.captureDao()
        pendingDao = db.pendingDao()
        categoryDao = db.categoryDao()
        accountDao = db.accountDao()
        traceDao = db.traceDao()
        merchantRuleDao = db.merchantRuleDao()
        ConfirmCardWindow.init(appContext)
        reconcileEntryModePrefs()
        createNotificationChannel()
        cleanup()
    }

    /**
     * 启动时校正入账方式（升级迁移，纯判定见 [reconcileEntryModes]）：
     * 旧版本两个开关彼此独立，机内可能同时为开（互斥只在切换时生效）——
     * 按定稿默认取向（自动入账优先）清掉悬浮窗入账；
     * 例外：悬浮窗是显式开启、自动入账从未设置过（旧版默认关）时，尊重用户的悬浮窗选择。
     */
    private fun reconcileEntryModePrefs() {
        val p = prefs()
        val fix = entryModeFix(
            autoSet = p.contains(KEY_AUTO_ENTRY_ENABLED),
            overlaySet = p.contains(KEY_OVERLAY_CONFIRM_ENABLED),
            autoEntry = isAutoEntryEnabled(),
            overlayConfirm = isOverlayConfirmEnabled(),
        )
        when (fix) {
            EntryModeFix.DISABLE_OVERLAY ->
                p.edit().putBoolean(KEY_OVERLAY_CONFIRM_ENABLED, false).apply()
                    .also { AppLog.i("Overlay", "entry modes reconciled: overlayConfirm=false (auto entry wins)") }
            EntryModeFix.DISABLE_AUTO ->
                p.edit().putBoolean(KEY_AUTO_ENTRY_ENABLED, false).apply()
                    .also { AppLog.i("Ntf", "entry modes reconciled: autoEntry=false (explicit overlay mode)") }
            EntryModeFix.NONE -> Unit
        }
    }

    private fun createNotificationChannel() {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.deleteNotificationChannel(CHANNEL_LEGACY)
            val channel = android.app.NotificationChannel(
                CHANNEL_CAPTURE,
                "自动记账提醒",
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "捕获到动账/退款时横幅提醒确认入账"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }.onFailure { AppLog.w("Ntf", "create channel failed", it) }
    }

    /** 系统通知（横幅）：点击跳转 App 内对应页面（该笔编辑页 / 待确认队列 / 退款关联）；可带动作按钮 */
    private fun postCaptureNotification(
        title: String,
        text: String,
        route: String,
        notifId: Int = title.hashCode(),
        action: androidx.core.app.NotificationCompat.Action? = null,
    ) {
        if (!isCaptureNotifyEnabled()) return
        if (!canPostNotifications()) {
            AppLog.i("Ntf", "notify skipped (POST_NOTIFICATIONS not granted)")
            return
        }
        val intent = android.content.Intent(appContext, com.controleco.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ROUTE, route)
        val pi = android.app.PendingIntent.getActivity(
            appContext,
            (NOTIF_TAG + route + title + text).hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = androidx.core.app.NotificationCompat.Builder(appContext, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
        action?.let { builder.addAction(it) }
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(appContext).notify(NOTIF_TAG, notifId, builder.build())
            AppLog.i("Ntf", "capture notification posted: $title")
        }.onFailure { AppLog.w("Ntf", "post notification failed", it) }
    }

    /** 待确认入账通知的确定性 id（同一笔被处理时按 id 撤销通知） */
    private fun pendingNotifId(dedupKey: String): Int = "pending|$dedupKey".hashCode()

    /** 已入账通知的确定性 id（与旧「已自动入账」共用同一 id 方案） */
    private fun entryNotifId(dedupKey: String): Int = "auto|$dedupKey".hashCode()

    /** 撤销捕获提醒通知（卡片里确认/忽略后调用；通知没发过则静默失败） */
    private fun cancelCaptureNotification(notifId: Int) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(appContext).cancel(NOTIF_TAG, notifId)
        }.onFailure { AppLog.w("Ntf", "cancel notification failed", it) }
    }

    private fun pendingNotifText(sourceLabel: String, amountCents: Long, counterparty: String): String =
        "$sourceLabel ${Money.formatYuan(amountCents)}" +
            (if (counterparty.isNotBlank()) " · $counterparty" else "") +
            " · 点击确认入账"

    /**
     * 捕获入队后的提醒（卡片/通知互斥）：
     * 能弹悬浮确认卡就弹卡（人还在支付页，关掉卡片即按卡内值入账），否则回落横幅通知
     * （IMPORTANCE_HIGH，点击进待确认队列；受「入账提醒」开关控制）。
     * 两条路径的确定化 id 一致，同一笔处理时可直接撤销。
     */
    private suspend fun announceCapture(
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        sourceLabel: String,
        counterparty: String,
        paymentMethod: String?,
        dedupKey: String,
        ruleName: String,
        txTime: Long?,
        note: String,
        pendingId: Long = -1L,
    ) {
        if (pendingId > 0) {
            val pending = pendingDao.getById(pendingId)
            if (pending != null &&
                tryShowConfirmCard(pending, alreadyEntered = false, isRefund, sourceLabel, counterparty, dedupKey)
            ) {
                return
            }
        }
        postPendingNotification(type, isRefund, amountCents, sourceLabel, counterparty, dedupKey)
    }

    /**
     * 已入账后的提醒（卡片/通知互斥）：
     * - 悬浮窗入账：弹「已记入（可改/可删）」卡片；卡片弹不出来账也在——这正是"先入账后弹卡"的意义；
     * - 入账提醒：卡片没弹（或没开悬浮窗）时发「已记入一笔 X」通知（点击进该笔编辑页，附「撤销」）。
     */
    private suspend fun announceEntry(
        entered: Entered,
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        sourceLabel: String,
        counterparty: String,
        dedupKey: String,
    ) {
        val pending = if (entered.pendingId > 0) pendingDao.getById(entered.pendingId) else null
        if (pending != null &&
            tryShowConfirmCard(pending, alreadyEntered = true, isRefund, sourceLabel, counterparty, dedupKey)
        ) {
            return
        }
        postEntryNotification(entered.txId, type, isRefund, amountCents, counterparty, sourceLabel, dedupKey)
    }

    /** 横幅通知（卡片不可用/收起后的兜底提醒；「已自动入账」等其它提醒另走 postCaptureNotification） */
    private fun postPendingNotification(
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        sourceLabel: String,
        counterparty: String,
        dedupKey: String,
        pendingId: Long = -1L,
    ) {
        val kind = when {
            isRefund -> "退款"
            type == TxType.EXPENSE -> "支出"
            else -> "收入"
        }
        val text = if (amountCents > 0) {
            pendingNotifText(sourceLabel, amountCents, counterparty)
        } else {
            "$sourceLabel 金额待补 · 点击补录或忽略"
        }
        postCaptureNotification(
            "捕获到一笔$kind",
            text,
            ROUTE_PENDING,
            pendingNotifId(dedupKey),
            action = pendingId.takeIf { it > 0 }?.let { ignoreAction(IgnoreTarget(it, dedupKey)) },
        )
    }

    /**
     * 「已记入一笔账」通知（入账提醒的常态）：告知账已记好，**不是**"去待确认列表处理"的入口——
     * 点击进该笔的编辑页，另附「撤销」一键删除。
     */
    private fun postEntryNotification(
        txId: Long,
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        counterparty: String,
        sourceLabel: String,
        dedupKey: String,
    ) {
        if (txId <= 0) return
        val kind = when {
            isRefund -> "退款"
            type == TxType.EXPENSE -> "支出"
            else -> "收入"
        }
        val who = counterparty.ifBlank { sourceLabel }
        postCaptureNotification(
            "已记入一笔$kind",
            Money.formatYuan(amountCents) + " · $who · 点击可修改",
            "$ROUTE_EDIT_PREFIX$txId",
            entryNotifId(dedupKey),
            action = undoEntryAction(txId, dedupKey),
        )
    }

    // ---------- 金额待补录（方向可辨、金额缺失） ----------

    /** 「忽略」动作的目标（通知栏一键丢弃用） */
    private data class IgnoreTarget(val pendingId: Long, val dedupKey: String)

    /**
     * 方向可辨、金额缺失 → 入队 0 元条目 + 发「输入金额」通知。
     * 0 元条目不参与「全部确认」（见 LedgerViewModel），必须补录或忽略。
     */
    private suspend fun handleAmountMissing(
        packageName: String,
        rawId: Long,
        postTime: Long,
        title: String,
        text: String,
        bigText: String,
        hint: NotificationRules.DirectionHint,
    ) {
        val keyTime = if (postTime > 0) postTime else System.currentTimeMillis()
        val dedupKey = NotificationRules.dedupKey(packageName, hint.type, false, 0L, keyTime)
        val pendingId = pendingDao.insert(
            PendingTransactionEntity(
                type = hint.type,
                amountCents = 0L,
                timestamp = keyTime,
                note = hint.note,
                sourcePackage = packageName,
                ruleName = hint.ruleName,
                dedupKey = dedupKey,
                rawNotificationId = rawId,
            ),
        )
        captureDao.updateMatch(rawId, hint.ruleName + "·金额待补", true)
        AppLog.i("Ntf", "amount missing, queued stub #$pendingId ${hint.ruleName} pkg=$packageName")
        if (pendingId > 0) {
            postAmountRequestNotification(pendingId, dedupKey, hint)
        }
        traceCapture(
            channel = CaptureChannel.NOTIFICATION,
            packageName = packageName,
            title = title,
            text = text,
            bigText = bigText,
            capturedAt = keyTime,
            matchedRule = hint.ruleName,
            type = hint.type,
            amountCents = 0,
            note = hint.note,
            dedupKey = dedupKey,
            outcome = TraceOutcome.PENDING,
        )
    }

    /** 「输入金额」通知：RemoteInput 直接回复（先落库后回填，零跳转） + 「忽略」一键丢弃 */
    private fun postAmountRequestNotification(
        pendingId: Long,
        dedupKey: String,
        hint: NotificationRules.DirectionHint,
    ) {
        if (!isCaptureNotifyEnabled() || !canPostNotifications()) return
        val replyIntent = android.content.Intent(appContext, AmountReplyReceiver::class.java).apply {
            action = "$ACTION_AMOUNT_REPLY.$pendingId"
            putExtra(AmountReplyReceiver.EXTRA_PENDING_ID, pendingId)
            putExtra(AmountReplyReceiver.EXTRA_DEDUP_KEY, dedupKey)
        }
        // RemoteInput 要求可变 PendingIntent（Android 12+ 硬要求）；接收器里做组件来源校验兜底
        val replyPi = android.app.PendingIntent.getBroadcast(
            appContext,
            ("amount|$dedupKey").hashCode(),
            replyIntent,
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remoteInput = androidx.core.app.RemoteInput.Builder(AmountReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("输入金额")
            .build()
        val contentIntent = android.content.Intent(appContext, com.controleco.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ROUTE, ROUTE_PENDING)
        val contentPi = android.app.PendingIntent.getActivity(
            appContext,
            ("amount-content|$dedupKey").hashCode(),
            contentIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val kind = if (hint.type == TxType.EXPENSE) "支出" else "收入"
        val builder = androidx.core.app.NotificationCompat.Builder(appContext, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle("${hint.sourceLabel} 有一笔${kind}金额待补")
            .setContentText("直接在通知里输入金额，或点进待确认列表处理")
            .setContentIntent(contentPi)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(
                androidx.core.app.NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_save,
                    "输入金额",
                    replyPi,
                ).addRemoteInput(remoteInput).build(),
            )
            .addAction(ignoreAction(IgnoreTarget(pendingId, dedupKey)))
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(appContext)
                .notify(NOTIF_TAG, pendingNotifId(dedupKey), builder.build())
        }.onFailure { AppLog.w("Ntf", "post amount request failed", it) }
    }

    /** 「忽略」action：广播到 CaptureActionReceiver（显式组件、非导出） */
    private fun ignoreAction(
        target: IgnoreTarget,
    ): androidx.core.app.NotificationCompat.Action {
        val intent = android.content.Intent(appContext, CaptureActionReceiver::class.java).apply {
            action = CaptureActionReceiver.ACTION_IGNORE
            putExtra(CaptureActionReceiver.EXTRA_PENDING_ID, target.pendingId)
            putExtra(CaptureActionReceiver.EXTRA_DEDUP_KEY, target.dedupKey)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            appContext,
            ("ignore|${target.dedupKey}").hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return androidx.core.app.NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "忽略", pi).build()
    }

    /** 「撤销」action（已入账通知）：一键删除这笔已记的账（显式组件、非导出） */
    private fun undoEntryAction(txId: Long, dedupKey: String): androidx.core.app.NotificationCompat.Action {
        val intent = android.content.Intent(appContext, CaptureActionReceiver::class.java).apply {
            action = CaptureActionReceiver.ACTION_UNDO
            putExtra(CaptureActionReceiver.EXTRA_TX_ID, txId)
            putExtra(CaptureActionReceiver.EXTRA_DEDUP_KEY, dedupKey)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            appContext,
            ("undo|$dedupKey").hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return androidx.core.app.NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "撤销", pi).build()
    }

    /** 通知栏「撤销」入口（CaptureActionReceiver 调用）：删除已入账的那笔 + 对应 AUTO 条目 */
    fun undoEntryFromNotification(txId: Long, dedupKey: String) {
        if (!initialized) return
        scope.launch {
            runCatching { if (txId > 0) txDao.deleteById(txId) }
                .onFailure { AppLog.w("Ntf", "undo entry delete failed", it) }
            if (dedupKey.isNotBlank()) {
                pendingDao.deleteByDedupKey(dedupKey)
                dedupKeyAllocator.release(dedupKey)
                cancelCaptureNotification(entryNotifId(dedupKey))
                if (ConfirmCardWindow.showingPendingId > 0 &&
                    ConfirmCardWindow.showingPendingId == pendingIdOfDedup(dedupKey)
                ) {
                    mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                }
            }
            toast("已撤销")
            AppLog.i("Ntf", "undo entry from notification tx=#$txId key=$dedupKey")
        }
    }

    private suspend fun pendingIdOfDedup(dedupKey: String): Long =
        pendingDao.getMergeable().firstOrNull { it.dedupKey == dedupKey }?.id ?: -1L

    /** 通知栏「忽略」入口（CaptureActionReceiver 调用） */
    fun ignoreFromNotification(pendingId: Long, dedupKey: String) {
        if (!initialized) return
        scope.launch {
            if (pendingId > 0) {
                PendingActions.discard(pendingDao, pendingId)
            } else {
                pendingDao.deleteByDedupKey(dedupKey)
            }
            // 条目已丢弃：释放去重键，同分钟桶里的下一笔真实交易可以复用
            if (dedupKey.isNotBlank()) dedupKeyAllocator.release(dedupKey)
            cancelCaptureNotification(pendingNotifId(dedupKey))
            if (ConfirmCardWindow.showingPendingId == pendingId) {
                mainHandler.post { ConfirmCardWindow.dismissConfirm() }
            }
            AppLog.i("Ntf", "ignored from notification pending#$pendingId key=$dedupKey")
        }
    }

    /** 金额补录结果（供通知栏回执与调试） */
    sealed interface AmountUpdateResult {
        data class Ok(val amountCents: Long, val newDedupKey: String) : AmountUpdateResult
        object NotFound : AmountUpdateResult
        object Invalid : AmountUpdateResult
    }

    /**
     * 把补录金额写回 0 元占位条：重算去重键（保留分钟桶）。
     * 若新键对应的条目已存在（同笔的完整捕获先到了）→ 删除占位条，以既有条目为准。
     */
    fun applyPendingAmount(pendingId: Long, amountCents: Long, onResult: (AmountUpdateResult) -> Unit) {
        if (!initialized) return
        if (amountCents <= 0 || amountCents > NotificationRules.MAX_AMOUNT_CENTS) {
            onResult(AmountUpdateResult.Invalid)
            return
        }
        scope.launch {
            try {
                val stub = pendingDao.getById(pendingId)
                if (stub == null || stub.amountCents != 0L) {
                    onResult(AmountUpdateResult.NotFound)
                    return@launch
                }
                val newKey = dedupKeyWithAmount(stub.dedupKey, amountCents)
                if (newKey == null) {
                    onResult(AmountUpdateResult.Invalid)
                    return@launch
                }
                if (pendingDao.countByDedupKey(newKey) > 0) {
                    // 同笔的完整捕获已入队：占位条退场（键释放，允许后续同分钟桶复用）
                    pendingDao.deleteById(pendingId)
                    dedupKeyAllocator.release(stub.dedupKey)
                    AppLog.i("Ntf", "amount stub superseded by existing entry key=$newKey")
                    onResult(AmountUpdateResult.NotFound)
                    return@launch
                }
                pendingDao.updateAmountAndKey(pendingId, amountCents, newKey)
                AppLog.i("Ntf", "amount backfilled pending#$pendingId -> ${Money.formatYuan(amountCents)}")
                onResult(AmountUpdateResult.Ok(amountCents, newKey))
            } catch (e: Exception) {
                AppLog.e("Ntf", "apply pending amount failed", e)
                onResult(AmountUpdateResult.Invalid)
            }
        }
    }

    /** 通知栏回执：撤销「输入金额」通知，补录成功后重新提醒（可弹卡，走正常待确认流程） */
    fun onAmountReplied(pendingId: Long, stubDedupKey: String, result: AmountUpdateResult) {
        if (!initialized) return
        when (result) {
            is AmountUpdateResult.Ok -> {
                cancelCaptureNotification(pendingNotifId(stubDedupKey))
                toast("已补录 " + Money.formatYuan(result.amountCents))
                scope.launch {
                    val row = pendingDao.getById(pendingId) ?: return@launch
                    announceCapture(
                        type = row.type,
                        isRefund = row.refundMark == RefundMark.REFUND_INCOME,
                        amountCents = row.amountCents,
                        sourceLabel = NotificationRules.sourceLabel(row.sourcePackage),
                        counterparty = row.counterparty,
                        paymentMethod = null,
                        dedupKey = row.dedupKey,
                        ruleName = row.ruleName,
                        txTime = row.timestamp,
                        note = row.note,
                        pendingId = row.id,
                    )
                }
            }
            AmountUpdateResult.NotFound -> {
                cancelCaptureNotification(pendingNotifId(stubDedupKey))
                toast("这条已处理过")
            }
            AmountUpdateResult.Invalid -> toast("金额无效，请重新输入")
        }
    }

    /** 完整捕获到达时清掉同笔的"金额待补"占位条（占位条只用于兜底提醒，不保留） */
    private suspend fun supersedeAmountStubs(packageName: String, type: TxType, timeMillis: Long) {
        val removed = pendingDao.deleteAmountStubs(packageName, type, timeMillis - 10 * 60_000, timeMillis + 60_000)
        if (removed > 0) AppLog.i("Ntf", "superseded $removed amount stub(s) pkg=$packageName")
    }

    // ---------- 悬浮确认卡（卡片/通知互斥） ----------

    /**
     * 悬浮窗入账（默认关：定稿默认取向是自动入账；授权了悬浮窗权限才生效，未授权自动回落通知）：
     * 捕获时弹出底部确认卡——关闭卡片（✕/点空白/长时间不操作）视为入账，点「忽略」才不入账。
     * 与「自动入账」互斥（开启本项时自动关闭自动入账）。
     */
    fun isOverlayConfirmEnabled(): Boolean = prefs().getBoolean(KEY_OVERLAY_CONFIRM_ENABLED, false)

    fun setOverlayConfirmEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_OVERLAY_CONFIRM_ENABLED, enabled).apply()
        if (enabled) {
            prefs().edit().putBoolean(KEY_AUTO_ENTRY_ENABLED, false).apply() // 互斥：关掉自动入账
        } else {
            mainHandler.post { ConfirmCardWindow.dismissAll() }
        }
        AppLog.i("Overlay", "overlayConfirmEnabled=$enabled (autoEntry=${isAutoEntryEnabled()})")
    }

    fun canDrawOverlays(): Boolean = initialized && Settings.canDrawOverlays(appContext)

    /** MainActivity 前台变化：进入前台立即收起卡片（应用内用待确认列表，卡片悬在本应用上会挡操作） */
    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
        if (foreground) mainHandler.post { ConfirmCardWindow.dismissAll() }
    }

    /** 通知点击进入应用时收起卡片（同 setAppForeground，兜底路由入口） */
    fun onRouteOpened(route: String) {
        AppLog.d("Overlay", "route opened: $route")
        mainHandler.post { ConfirmCardWindow.dismissAll() }
    }

    /** 无障碍服务上报前台应用（悬浮卡只在"人还在支付页"时弹） */
    fun noteForegroundPackage(packageName: String) {
        foregroundPackage = packageName
    }

    /**
     * 卡片显示条件（每次弹出前重新判定：延迟期间用户可能已锁屏/切走/进入本应用）。
     * [requireForegroundMatch] = false 时忽略"前台匹配"这一条（延迟前预检用：此刻的前台窗口
     * 可能还是支付保护/安全键盘等系统弹层，等 3 秒后再看用户到底在哪）。
     * 不满足时把原因写进 [lastOverlayDecision]，权限页/日志可直接回看"为什么没弹卡"。
     */
    private fun overlayConditionsOk(sourcePackage: String, requireForegroundMatch: Boolean): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val fg = foregroundPackage
        val enabled = isOverlayConfirmEnabled()
        val canDraw = Settings.canDrawOverlays(appContext)
        val screenOn = pm.isInteractive
        val locked = km.isKeyguardLocked
        val matchable = requireForegroundMatch && fg != null
        val contextMatch = fg != null && CaptureAppCatalog.isWatched(appContext, fg)
        val ok = shouldShowOverlay(
            OverlayConditions(
                enabled = enabled,
                canDrawOverlays = canDraw,
                appInForeground = appInForeground,
                screenInteractive = screenOn,
                keyguardLocked = locked,
                foregroundPackage = if (requireForegroundMatch) fg else null,
                sourcePackage = sourcePackage,
                foregroundIsPaymentContext = contextMatch,
            ),
        )
        if (!ok) {
            val reason = when {
                !enabled -> "「悬浮窗入账」开关未开"
                !canDraw -> "未授权悬浮窗权限"
                appInForeground -> "本应用在前台（用应用内列表）"
                !screenOn -> "屏幕未点亮"
                locked -> "已锁屏"
                matchable -> "当时前台是 $fg，与捕获来源 $sourcePackage 不符"
                else -> "条件不满足"
            }
            lastOverlayDecision = reason
            AppLog.i(
                "Overlay",
                "conditions not met: $reason (enabled=$enabled canDraw=$canDraw appInForeground=$appInForeground " +
                    "screen=$screenOn locked=$locked fg=$fg source=$sourcePackage)",
            )
        }
        return ok
    }

    /**
     * 尝试弹确认卡。返回 true = 卡片已展示（本次不再发通知）。
     * [alreadyEntered] = true（悬浮窗入账的常态）：卡片是"已入账，可改/可删"；
     * false：待确认条目（金额待补 / 无可推测分类 / 三个开关全关的完全手动模式）。
     * 延迟 [OVERLAY_DELAY_MS] 再弹：支付保护层在支付页停留期会拦截悬浮窗（阶段三旧结论），
     * 延迟后重新判定条件与条目（可能已被处理）；`addView` 失败同样回落通知。
     */
    private suspend fun tryShowConfirmCard(
        pending: PendingTransactionEntity,
        alreadyEntered: Boolean,
        isRefund: Boolean,
        sourceLabel: String,
        counterparty: String,
        dedupKey: String,
    ): Boolean {
        // 预检只判"硬条件"（开关/权限/本应用前台/屏幕），不看前台匹配：支付完成瞬间的前台窗口
        // 可能还是支付保护/安全键盘等系统弹层，等 3 秒后再看用户到底在哪（实测：预检查前台匹配会把卡拒掉）
        if (!overlayConditionsOk(pending.sourcePackage, requireForegroundMatch = false)) return false
        delay(OVERLAY_DELAY_MS)
        if (!overlayConditionsOk(pending.sourcePackage, requireForegroundMatch = true)) return false
        // 延迟期间条目可能已被确认/丢弃/被同笔富化改写（dedupKey 变化即视为已处理）
        val fresh = pendingDao.getById(pending.id)
        if (fresh == null || fresh.dedupKey != dedupKey) {
            lastOverlayDecision = "等待期间条目已处理"
            return false
        }
        val categories = categoryDao.getAll()
        val accounts = accountDao.getAll()
        val learned = merchantRuleDao.getAll()
        val shown = showCardOnMain(fresh, categories, accounts, learned, alreadyEntered, isRefund, sourceLabel, counterparty, dedupKey)
        lastOverlayDecision = if (shown) "已弹出" else "系统拦截了悬浮窗（支付保护等）"
        AppLog.i("Overlay", if (shown) "confirm card shown for #${pending.id}" else "confirm card unavailable, fallback to notification")
        return shown
    }

    /**
     * 富化后补一次"卡片优先"提醒：同笔被后续通道捕获（页面合并/跨通道归并）时，
     * 若卡片没在展示就重新判定一次——首次尝试可能因当时的系统弹层/时机被拒，
     * 而此刻用户往往已回到结果页。卡片成功则撤销早先的横幅通知（同一笔不重复打扰）。
     */
    private suspend fun retryCardIfNoCard(pendingId: Long) {
        if (ConfirmCardWindow.showingPendingId == pendingId) return
        val pending = pendingDao.getById(pendingId) ?: return
        val alreadyEntered = pending.status == PendingStatus.AUTO
        val shown = tryShowConfirmCard(
            pending = pending,
            alreadyEntered = alreadyEntered,
            isRefund = pending.refundMark == RefundMark.REFUND_INCOME,
            sourceLabel = NotificationRules.sourceLabelOrRule(pending.sourcePackage, pending.ruleName),
            counterparty = pending.counterparty,
            dedupKey = pending.dedupKey,
        )
        if (shown) {
            cancelCaptureNotification(
                if (alreadyEntered) entryNotifId(pending.dedupKey) else pendingNotifId(pending.dedupKey),
            )
        }
    }

    /** WindowManager 必须在主线程操作；DAO 数据已在 IO 侧取好 */
    private suspend fun showCardOnMain(
        pending: PendingTransactionEntity,
        categories: List<com.controleco.app.data.db.CategoryEntity>,
        accounts: List<com.controleco.app.data.db.AccountEntity>,
        learned: List<com.controleco.app.data.db.MerchantRuleEntity>,
        alreadyEntered: Boolean,
        isRefund: Boolean,
        sourceLabel: String,
        counterparty: String,
        dedupKey: String,
    ): Boolean = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val shown = ConfirmCardWindow.showConfirm(
                pending = pending,
                categories = categories,
                accounts = accounts,
                learnedRules = learned,
                alreadyEntered = alreadyEntered,
                onConfirm = { amountCents, categoryId, accountId, counterpartyText ->
                    // 已入账模式：「保存」= 同步修改（细条可撤销整笔）；待确认模式：「记一笔」= 入账
                    mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                    scope.launch {
                        if (alreadyEntered) {
                            saveEnteredEdits(pending, amountCents, categoryId, accountId, counterpartyText, withStrip = true)
                        } else {
                            confirmFromOverlay(pending, amountCents, categoryId, accountId, counterpartyText)
                        }
                    }
                },
                onIgnore = {
                    mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                    scope.launch {
                        if (alreadyEntered) ignoreEntered(pending) else ignoreFromOverlay(pending.id, pending.dedupKey)
                    }
                },
                onDismiss = { snapshot ->
                    // 卡片已移除，这里再调一次是幂等兜底
                    mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                    val catId = snapshot.categoryId
                    val accId = snapshot.accountId
                    if (alreadyEntered) {
                        // 关闭 = 保留这笔已入账；用户改过的字段同步回去（没改过就不写库）
                        if (snapshot.edited && canSyncOverlayEdits(snapshot) && catId != null && accId != null) {
                            AppLog.i("Overlay", "card dismissed -> sync edits #${pending.id} ${Money.formatYuan(snapshot.amountCents)}")
                            scope.launch { saveEnteredEdits(pending, snapshot.amountCents, catId, accId, snapshot.counterparty, withStrip = true) }
                        } else {
                            AppLog.i("Overlay", "card dismissed -> entry kept #${pending.id} (edited=${snapshot.edited})")
                        }
                    } else if (dismissOutcome(snapshot) == DismissOutcome.ENTER && catId != null && accId != null) {
                        // 待确认条目：关闭 = 视为入账（旧语义）
                        AppLog.i("Overlay", "card dismissed -> enter #${pending.id} ${Money.formatYuan(snapshot.amountCents)}")
                        scope.launch { confirmFromOverlay(pending, snapshot.amountCents, catId, accId, snapshot.counterparty) }
                    } else {
                        AppLog.i("Overlay", "card dismissed -> keep pending #${pending.id} (amount/category/account incomplete)")
                        scope.launch {
                            postPendingNotification(pending.type, isRefund, pending.amountCents, sourceLabel, counterparty, dedupKey, pending.id)
                        }
                    }
                },
            )
            if (cont.isActive) cont.resume(shown)
        }
    }

    /** 已入账卡片的「保存」/关闭同步：卡内当前值写回交易与 AUTO 条目（细条可撤销整笔） */
    private suspend fun saveEnteredEdits(
        pending: PendingTransactionEntity,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
        withStrip: Boolean,
    ) {
        val ok = runCatching {
            PendingActions.updateEntered(txDao, pendingDao, merchantRuleDao, pending, categoryId, accountId, counterparty, amountCents)
        }.onFailure { AppLog.e("Overlay", "sync entered edits failed", it) }.getOrDefault(false)
        if (!ok) {
            toast("这笔已被撤销，未更新")
            return
        }
        cancelCaptureNotification(entryNotifId(pending.dedupKey))
        toast("已更新")
        if (withStrip) {
            mainHandler.post {
                ConfirmCardWindow.showSaved(pending.type, amountCents) {
                    scope.launch {
                        PendingActions.undoEntered(txDao, pendingDao, pending.dedupKey)
                        toast("已撤销")
                    }
                }
            }
        }
    }

    /** 已入账卡片的「忽略」：删除这笔账（含 AUTO 条目），一条点击纠错 */
    private suspend fun ignoreEntered(pending: PendingTransactionEntity) {
        val removed = PendingActions.undoEntered(txDao, pendingDao, pending.dedupKey)
        pendingDao.deleteById(pending.id)
        cancelCaptureNotification(entryNotifId(pending.dedupKey))
        toast(if (removed) "已忽略，这笔未记账" else "这笔已被撤销")
        AppLog.i("Overlay", "ignored (entry deleted=$removed) #${pending.id} key=${pending.dedupKey}")
    }

    /** 卡片确认入账：写账 + 撤销细条 + 撤销即删除该笔 */
    private suspend fun confirmFromOverlay(
        pending: PendingTransactionEntity,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        counterparty: String,
    ) {
        val txId = try {
            PendingActions.confirm(txDao, pendingDao, merchantRuleDao, pending, categoryId, accountId, counterparty, amountCents)
        } catch (e: Exception) {
            AppLog.e("Overlay", "confirm from overlay failed", e)
            toast("入账失败：${e.message ?: "未知错误"}")
            return
        }
        cancelCaptureNotification(pendingNotifId(pending.dedupKey))
        val kindType = pending.type
        toast("已记入")
        if (txId == null) return // 已入过账（重复通知/双通道竞态）
        mainHandler.post {
            ConfirmCardWindow.showSaved(kindType, amountCents) {
                scope.launch {
                    runCatching { txDao.deleteById(txId) }
                        .onSuccess { AppLog.i("Overlay", "undo confirmed entry tx=#$txId") }
                        .onFailure { AppLog.w("Overlay", "undo failed", it) }
                    toast("已撤销")
                }
            }
        }
    }

    /** 卡片「忽略」：丢弃待确认条目（误报纠错，一条点击） */
    private suspend fun ignoreFromOverlay(pendingId: Long, dedupKey: String) {
        PendingActions.discard(pendingDao, pendingId)
        if (dedupKey.isNotBlank()) dedupKeyAllocator.release(dedupKey)
        cancelCaptureNotification(pendingNotifId(dedupKey))
        AppLog.i("Overlay", "ignored from card #$pendingId")
    }

    /** 条目被后续捕获富化后刷新卡片（用户已改过的字段由卡片状态自行保护） */
    private suspend fun refreshCardIfShowing(pendingId: Long) {
        if (ConfirmCardWindow.showingPendingId != pendingId) return
        val fresh = pendingDao.getById(pendingId) ?: return
        val categories = categoryDao.getAll()
        val accounts = accountDao.getAll()
        val learned = merchantRuleDao.getAll()
        mainHandler.post { ConfirmCardWindow.updatePending(fresh, categories, accounts, learned) }
    }

    /** debug：立即或延迟弹一张样例卡片（配置页开发者功能用；不落库） */
    fun showOverlayTest(delayMs: Long, onResult: (String) -> Unit) {
        if (!initialized) return
        if (!isOverlayConfirmEnabled()) {
            onResult("「悬浮确认卡」开关未开：设置 → 权限 → 悬浮确认卡")
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            onResult("未授权悬浮窗权限：设置 → 权限 → 悬浮确认卡 → 去授权")
            return
        }
        scope.launch {
            val categories = categoryDao.getAll()
            val accounts = accountDao.getAll()
            if (delayMs > 0) delay(delayMs)
            mainHandler.post {
                val sample = PendingTransactionEntity(
                    id = -1L,
                    type = TxType.EXPENSE,
                    amountCents = 1L,
                    timestamp = System.currentTimeMillis(),
                    counterparty = "测试商户（样例，不入账）",
                    note = "悬浮确认卡测试",
                    sourcePackage = NotificationRules.WECHAT,
                    ruleName = "微信·支出/消费（测试）",
                    dedupKey = "overlay-test",
                )
                val shown = ConfirmCardWindow.showConfirm(
                    pending = sample,
                    categories = categories,
                    accounts = accounts,
                    learnedRules = emptyList(),
                    alreadyEntered = true, // 样例按真实形态展示（"已记入一笔支出"，改/删都只是 Toast）
                    onConfirm = { _, _, _, _ ->
                        mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                        toast("测试卡片：保存（样例不入账）")
                    },
                    onIgnore = {
                        mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                        toast("测试卡片：忽略（样例不入账）")
                    },
                    onDismiss = {
                        mainHandler.post { ConfirmCardWindow.dismissConfirm() }
                        toast("测试卡片：关闭（样例不入账）")
                    },
                )
                // Toast 是系统级窗口不受支付保护层拦截：Toast 可见而卡片不可见 = 卡片被系统/支付保护层拦截
                toast(if (shown) "悬浮确认卡已弹出（若看不到，看本 Toast：卡片被支付保护层拦截）" else "卡片弹不出来：检查悬浮窗权限/支付保护")
            }
        }
    }

    fun markConnected(connected: Boolean) {
        _connected.value = connected
        AppLog.i("Ntf", "listener connected=$connected")
    }

    /**
     * 收到通知即证明监听活着（进程被杀后状态可能显示未连接/假死）：直接自愈为已连接，
     * 修掉"状态显示已连接但实际假死"与"实际活着而状态为假"两类误报。
     */
    fun markAliveFromNotification() {
        if (!_connected.value) {
            _connected.value = true
            AppLog.i("Ntf", "listener self-healed (notification received)")
        }
    }

    /** 监听重连成功：清掉"更新后需重新授权"提示 */
    fun onListenerHealthy() {
        if (isReauthNeeded()) {
            clearReauthNeeded()
            AppLog.i("Ntf", "reauth flag cleared (listener healthy)")
        }
    }

    // ---------- 更新后需重新授权提示（MIUI 等 ROM 更新应用后通知使用权会静默失效） ----------

    fun isReauthNeeded(): Boolean = initialized && prefs().getBoolean(KEY_NEEDS_REAUTH, false)

    private fun setReauthNeeded(needed: Boolean) {
        prefs().edit().putBoolean(KEY_NEEDS_REAUTH, needed).apply()
    }

    fun clearReauthNeeded() = setReauthNeeded(false)

    /**
     * 覆盖安装后调用（PackageReplacedReceiver）：versionCode 变化即视为"应用更新了"，
     * 若通知监听有授权则提示复查——系统不保证更新后监听仍被绑定（小米系尤为常见）。
     */
    fun checkAppUpdated(versionCode: Int) {
        if (!initialized) return
        val last = prefs().getInt(KEY_LAST_VERSION_CODE, 0)
        if (last == versionCode) return
        prefs().edit().putInt(KEY_LAST_VERSION_CODE, versionCode).apply()
        if (last != 0 && wasListenerGranted()) {
            setReauthNeeded(true)
            AppLog.w("Ntf", "app updated ($last -> $versionCode), reauth hint set")
        }
    }

    private fun wasListenerGranted(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)

    fun markA11yConnected(connected: Boolean) {
        _a11yConnected.value = connected
        AppLog.i("Page", "a11y service connected=$connected")
    }

    fun isCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_CAPTURE_ENABLED, true)

    fun setCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Ntf", "captureEnabled=$enabled")
    }

    fun isA11yCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_A11Y_CAPTURE_ENABLED, true)

    fun setA11yCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_A11Y_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Page", "a11yCaptureEnabled=$enabled")
    }

    fun isRefundLinkEnabled(): Boolean =
        prefs().getBoolean(KEY_REFUND_LINK_ENABLED, true)

    fun setRefundLinkEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_REFUND_LINK_ENABLED, enabled).apply()
        AppLog.i("Ntf", "refundLinkEnabled=$enabled")
    }

    fun isCaptureNotifyEnabled(): Boolean =
        prefs().getBoolean(KEY_CAPTURE_NOTIFY_ENABLED, true)

    fun setCaptureNotifyEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_CAPTURE_NOTIFY_ENABLED, enabled).apply()
        AppLog.i("Ntf", "captureNotifyEnabled=$enabled")
    }

    /**
     * 自动入账（默认开：定稿默认取向）：识别到付款后按推测分类/账户直接记账，不再逐笔确认。
     * 与「悬浮窗入账」互斥（开启本项时自动关闭悬浮窗入账并收起已弹出的卡片）。
     */
    fun isAutoEntryEnabled(): Boolean =
        prefs().getBoolean(KEY_AUTO_ENTRY_ENABLED, true)

    fun setAutoEntryEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_AUTO_ENTRY_ENABLED, enabled).apply()
        if (enabled) {
            prefs().edit().putBoolean(KEY_OVERLAY_CONFIRM_ENABLED, false).apply() // 互斥：关掉悬浮窗入账
            mainHandler.post { ConfirmCardWindow.dismissAll() }
        }
        AppLog.i("Ntf", "autoEntryEnabled=$enabled (overlay=${isOverlayConfirmEnabled()})")
    }

    /**
     * 银行 App 动账推送（默认开）：银行 App 的动账通知与动账短信同源同形，
     * 走 BankLedgerRules 的「正文【银行名】签名」闸门 + 银行包名短名单兜底。
     * 不需要额外权限（走既有通知监听授权）。
     */
    fun isBankPushEnabled(): Boolean =
        prefs().getBoolean(KEY_BANK_PUSH_ENABLED, true)

    fun setBankPushEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_BANK_PUSH_ENABLED, enabled).apply()
        AppLog.i("Sms", "bankPushEnabled=$enabled")
    }

    /**
     * 银行动账短信（默认关）：银行卡直接刷卡/被扣款而没有任何 App 通知时，短信是唯一线索。
     * 只收新到短信（RECEIVE_SMS），不读历史、不解码彩信、非默认短信应用；
     * 非银行号码与无银行签名的短信在采集层丢弃（内容不入库）。
     */
    fun isSmsCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_SMS_CAPTURE_ENABLED, false)

    fun setSmsCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_SMS_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Sms", "smsCaptureEnabled=$enabled")
    }

    /** 是否已授予收短信权限（安卓 13+ 侧载同样需运行时授权） */
    fun hasSmsPermission(): Boolean =
        initialized && androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.RECEIVE_SMS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 金额待补录（默认开）：方向可辨但金额缺失的通知（如无金额的支付骨架通知）不再直接丢弃，
     * 入队 0 元条目并发「输入金额」通知，用户下拉通知栏直接打字补录。
     */
    fun isAmountReplyEnabled(): Boolean = prefs().getBoolean(KEY_AMOUNT_REPLY_ENABLED, true)

    fun setAmountReplyEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_AMOUNT_REPLY_ENABLED, enabled).apply()
        AppLog.i("Ntf", "amountReplyEnabled=$enabled")
    }

    /** 横幅通知提醒确认入账（安卓 13+ 需 POST_NOTIFICATIONS 运行时授权） */
    fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    fun isDebugCaptureAll(isDebugBuild: Boolean): Boolean =
        prefs().getBoolean(KEY_DEBUG_CAPTURE_ALL, isDebugBuild)

    fun setDebugCaptureAll(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_CAPTURE_ALL, enabled).apply()
        AppLog.i("Ntf", "debugCaptureAll=$enabled")
    }

    /**
     * 捕获所有页面（调试，默认关）：开启后无障碍通道记录**所有应用**的页面快照
     * （配置已不限包名，由服务内运行时判断放行），用于采样新应用的支付页扩规则。
     */
    fun isDebugCaptureAllPages(isDebugBuild: Boolean): Boolean =
        prefs().getBoolean(KEY_DEBUG_CAPTURE_ALL_PAGES, false)

    fun setDebugCaptureAllPages(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_CAPTURE_ALL_PAGES, enabled).apply()
        AppLog.i("Page", "debugCaptureAllPages=$enabled")
    }

    /**
     * 自动记账溯源（开发者功能，默认关）：开启后每次与入账有关的捕获都把
     * 原始来源（通知原文 / 页面快照）连同解析结果与处置方式留档，供回查"这笔账从哪来"。
     * 关闭时零写入（连判断都只有一次 SharedPreferences 读取）。
     */
    fun isTraceEnabled(): Boolean =
        prefs().getBoolean(KEY_TRACE_ENABLED, false)

    fun setTraceEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_TRACE_ENABLED, enabled).apply()
        AppLog.i("Trace", "captureTraceEnabled=$enabled")
    }

    /**
     * 溯源落库：原始来源 + 解析结果 + 处置方式。写入失败只记日志，绝不影响入账主流程
     * （开发者功能不能成为记账链路的故障点）。
     */
    private suspend fun traceCapture(
        channel: CaptureChannel,
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        capturedAt: Long,
        matchedRule: String,
        type: TxType?,
        amountCents: Long,
        counterparty: String = "",
        note: String = "",
        dedupKey: String,
        outcome: TraceOutcome,
    ) {
        if (!isTraceEnabled()) return
        runCatching {
            traceDao.insert(
                CaptureTraceEntity(
                    channel = channel,
                    packageName = packageName,
                    title = title.take(200),
                    text = text.take(4000),
                    bigText = bigText.take(4000),
                    capturedAt = capturedAt,
                    matchedRule = matchedRule,
                    type = type,
                    amountCents = amountCents,
                    counterparty = counterparty,
                    note = note,
                    dedupKey = dedupKey,
                    outcome = outcome,
                ),
            )
        }.onFailure { AppLog.w("Trace", "trace insert failed", it) }
    }

    private fun cleanup() {
        scope.launch {
            captureDao.deleteOlderThan(System.currentTimeMillis() - KEEP_DAYS * 24 * 3_600_000)
            captureDao.keepNewest(KEEP_ROWS)
            traceDao.keepNewest(TRACE_KEEP_ROWS)
            // 自动入账条目只在合并窗口（10s）内有意义，留一天足够，避免长期堆积
            pendingDao.deleteAutoOlderThan(System.currentTimeMillis() - 24 * 3_600_000)
            merchantRuleDao.keepNewest(MerchantRules.MAX_RULES)
        }
    }

    // ---------- 通道一：通知监听 ----------

    /** 由通知监听服务回调（主线程）；数据库操作全部转移到 IO */
    fun onNotificationPosted(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        postTime: Long,
        isDebugBuild: Boolean,
        groupEnriched: Boolean = false,
    ) {
        _lastEventAt.value = System.currentTimeMillis()

        if (!initialized || packageName == appContext.packageName) return
        if (title.isBlank() && text.isBlank() && bigText.isBlank()) return

        // debug 构建全量 Logcat（用户要求：便于真实样本测试与排查）
        AppLog.d("Ntf", "onPosted pkg=$packageName title=[$title] text=[$text] bigText=[$bigText] postTime=$postTime")

        val key = "$packageName|$title|$text|$bigText"
        val now = SystemClock.elapsedRealtime()
        synchronized(recentKeys) {
            val prev = recentKeys[key]
            if (prev != null && now - prev < REPOST_WINDOW_MS) {
                AppLog.d("Ntf", "skip repost (+${now - prev}ms) pkg=$packageName title=[$title]")
                return
            }
            recentKeys[key] = now
            while (recentKeys.size > RECENT_KEY_CAP) {
                val eldest = recentKeys.keys.first()
                recentKeys.remove(eldest)
            }
        }

        // 采集范围 = 规则认识的来源 ∩ 用户开启的目录应用（设置页「监听的 App」）
        val interested = NotificationRules.isInterested(packageName) &&
            CaptureAppCatalog.isWatched(appContext, packageName)
        val captureAll = isDebugCaptureAll(isDebugBuild)
        // 银行 App 动账推送：不在支付白名单里，走「正文【银行名】签名 / 银行包名短名单」两道线索。
        // 是否真的入库要在解析通过后决定（银行 App 的营销/通知类推送极密集，未命中不入库）。
        // 与 debug 全量记录无关：签名线索在就尝试解析，避免采样模式下银行通道失效。
        val bankCandidate = !interested && isBankPushEnabled() && isCaptureEnabled() &&
            CaptureAppCatalog.isWatchedOrUnknown(appContext, packageName) &&
            (BankLedgerRules.detectBankName("$title $text $bigText") != null ||
                BankLedgerRules.bankNameOfPackage(packageName) != null)
        if (!interested && !captureAll && !bankCandidate) {
            AppLog.d("Ntf", "skip (not interested) pkg=$packageName title=[$title]")
            return
        }

        scope.launch {
            try {
                if (!interested) {
                    // 银行推送优先：签名闸门命中即走银行通道；未命中且是 debug 全量模式时，
                    // 仍留原始快照（扩规则的样本池），否则直接丢弃
                    if (bankCandidate && handleBankPush(packageName, title, text, bigText, postTime)) return@launch
                    if (!captureAll) return@launch
                    val rawOnlyId = captureDao.insert(
                        CapturedNotificationEntity(
                            packageName = packageName,
                            title = title,
                            text = text,
                            bigText = bigText,
                            postTime = postTime,
                        ),
                    )
                    AppLog.d("Ntf", "captured #$rawOnlyId (debug all) pkg=$packageName title=[$title]")
                    return@launch
                }
                val rawId = captureDao.insert(
                    CapturedNotificationEntity(
                        packageName = packageName,
                        title = title,
                        text = text,
                        bigText = bigText,
                        postTime = postTime,
                    ),
                )
                AppLog.d("Ntf", "captured #$rawId pkg=$packageName title=[$title]")

                if (!interested || !isCaptureEnabled()) return@launch

                val parsed = NotificationRules.parse(packageName, title, text, bigText, postTime)
                if (parsed == null) {
                    AppLog.d("Ntf", "no rule matched pkg=$packageName title=[$title] text=[$text]")
                    if (interested && isCaptureEnabled()) {
                        // 无金额的退款到账通知（微信折叠通知实测样本）→ 转退款关联提醒
                        if (NotificationRules.isAmountlessRefundNotice(packageName, title, text)) {
                            handleRefundNotice(packageName, rawId, postTime, title, text, bigText)
                            return@launch
                        }
                        // 方向可辨但金额缺失（如支付骨架通知）→ 入队 0 元条目 + 通知栏「输入金额」补录
                        val hint = NotificationRules.parseDirectionOnly(packageName, title, text, bigText)
                        if (hint != null && isAmountReplyEnabled()) {
                            handleAmountMissing(packageName, rawId, postTime, title, text, bigText, hint)
                        }
                    }
                    return@launch
                }
                // 同笔的"金额待补"占位条被完整捕获取代（占位条只用于兜底提醒，不保留）
                supersedeAmountStubs(packageName, parsed.type, postTime)
                // 溯源用来源时刻：通知的 when 偶有异常值，退回落库时刻（只影响展示，不回写通知流程）
                val sourceAt = if (postTime > 0) postTime else System.currentTimeMillis()
                // 跨通道去重（10s 窗口）：页面通道先捕获/自动入账了同一笔 → 通知侧信息更少，不覆盖。
                // 通知侧严格比金额：同 App 内 10s 内两笔金额不同的付款不能被并成一笔（丢记录）
                val related = pickRelatedPending(
                    pendingDao.getMergeable(), packageName, parsed.type, parsed.amountCents,
                    System.currentTimeMillis(),
                )
                if (related != null) {
                    AppLog.d("Ntf", "skip duplicate ${parsed.dedupKey} (page side won, related #${related.pending.id})")
                    // 通知 → 页面 方向的补充：页面先到时只补页面缺失的字段（更接近真实交易时刻的发布时间），
                    // 绝不覆盖页面已确认的金额/商户/支付方式，也不产生第二条账目
                    val fixedTime = mergeNotificationInfo(related.pending, sourceAt)
                    val enrichedPending = related.pending
                    if (fixedTime != null) {
                        pendingDao.updateFromPage(
                            enrichedPending.id, enrichedPending.amountCents, fixedTime,
                            enrichedPending.counterparty, enrichedPending.note, enrichedPending.ruleName,
                        )
                        if (enrichedPending.status == PendingStatus.AUTO) {
                            txDao.updateCapturedByExternalId(
                                "NTF:${enrichedPending.dedupKey}",
                                enrichedPending.amountCents,
                                fixedTime,
                                enrichedPending.counterparty,
                                enrichedPending.note,
                            )
                        }
                        AppLog.i("Ntf", "enriched page entry #${enrichedPending.id} with notification time")
                        refreshCardIfShowing(enrichedPending.id)
                    }
                    traceCapture(
                        channel = CaptureChannel.NOTIFICATION,
                        packageName = packageName,
                        title = title, text = text, bigText = bigText,
                        capturedAt = sourceAt,
                        matchedRule = parsed.ruleName,
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        note = parsed.note,
                        dedupKey = related.pending.dedupKey,
                        outcome = TraceOutcome.SKIPPED_DUPLICATE,
                    )
                    return@launch
                }
                // 入账方式：任一"入账"开关开启即先直接入账（三种方式全关才退回待确认的完全手动模式）
                if (directEntryEnabled()) {
                    val entered = enterEntry(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        timestamp = postTime,
                        counterparty = "",
                        note = parsed.note,
                        sourcePackage = packageName,
                        dedupKey = parsed.dedupKey,
                        ruleName = parsed.ruleName,
                        isRefund = parsed.isRefund,
                    )
                    if (entered != null) {
                        captureDao.updateMatch(rawId, parsed.ruleName, true)
                        AppLog.i(
                            "Ntf",
                            "entered: ${parsed.ruleName} ${parsed.type} ${Money.formatYuan(parsed.amountCents)}" +
                                (if (parsed.isRefund) " [退款]" else "") + " pkg=$packageName",
                        )
                        traceCapture(
                            channel = CaptureChannel.NOTIFICATION,
                            packageName = packageName,
                            title = title, text = text, bigText = bigText,
                            capturedAt = sourceAt,
                            matchedRule = parsed.ruleName,
                            type = parsed.type,
                            amountCents = parsed.amountCents,
                            note = parsed.note,
                            dedupKey = parsed.dedupKey,
                            outcome = TraceOutcome.AUTO_ENTRY,
                        )
                        announceEntry(
                            entered = entered,
                            type = parsed.type,
                            isRefund = parsed.isRefund,
                            amountCents = parsed.amountCents,
                            sourceLabel = NotificationRules.sourceLabel(packageName),
                            counterparty = "",
                            dedupKey = parsed.dedupKey,
                        )
                        return@launch
                    }
                }
                val pendingId = pendingDao.insert(
                    PendingTransactionEntity(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        timestamp = postTime,
                        note = parsed.note,
                        sourcePackage = packageName,
                        ruleName = parsed.ruleName,
                        dedupKey = parsed.dedupKey,
                        rawNotificationId = rawId,
                        refundMark = if (parsed.isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                    ),
                )
                captureDao.updateMatch(rawId, parsed.ruleName, true)
                AppLog.i(
                    "Ntf",
                    "queued pending: ${parsed.ruleName} ${parsed.type} ${Money.formatYuan(parsed.amountCents)}" +
                        (if (parsed.isRefund) " [退款]" else "") + " pkg=$packageName",
                )
                traceCapture(
                    channel = CaptureChannel.NOTIFICATION,
                    packageName = packageName,
                    title = title, text = text, bigText = bigText,
                    capturedAt = sourceAt,
                    matchedRule = parsed.ruleName,
                    type = parsed.type,
                    amountCents = parsed.amountCents,
                    note = parsed.note,
                    dedupKey = parsed.dedupKey,
                    outcome = TraceOutcome.PENDING,
                )
                announceCapture(
                    type = parsed.type,
                    isRefund = parsed.isRefund,
                    amountCents = parsed.amountCents,
                    sourceLabel = NotificationRules.sourceLabel(packageName),
                    counterparty = "",
                    paymentMethod = null,
                    dedupKey = parsed.dedupKey,
                    ruleName = parsed.ruleName,
                    txTime = null,
                    note = parsed.note,
                    pendingId = pendingId,
                )
            } catch (e: Exception) {
                AppLog.e("Ntf", "capture handling failed", e)
            }
        }
    }

    // ---------- 通道三：银行动账（短信 / 银行 App 推送，共用解析与入队） ----------

    /**
     * 短信通道入口（由 SmsCaptureReceiver 在 IO 协程里调用，goAsync 保证写完才 finish）。
     * 隐私闸门在最前面：既不是已知银行号码、正文里也没有银行签名 → 立即丢弃，不解析不入库
     * （银行动账短信才有记账价值，私人短信一律不碰）。
     */
    suspend fun onSmsReceived(sender: String, body: String, eventTime: Long) {
        if (!initialized || body.isBlank()) return
        if (!isSmsCaptureEnabled() || !isCaptureEnabled()) {
            AppLog.d("Sms", "skip (sms capture disabled)")
            return
        }
        val normalized = NotificationRules.normalize(body)
        val bankName = BankLedgerRules.detectBankName(normalized)
        if (!BankLedgerRules.isBankSender(sender) && bankName == null) {
            AppLog.d("Sms", "skip (not a bank message) sender=$sender")
            return
        }
        val sourceKey = NotificationRules.SMS_SOURCE_PREFIX + (bankName ?: sender.takeLast(5))
        // 先落原始快照（只有银行侧短信会走到这里）；未匹配的银行短信留在捕获日志里供扩规则
        val rawId = captureDao.insert(
            CapturedNotificationEntity(
                packageName = sourceKey,
                title = "短信 $sender",
                text = normalized.take(4000),
                postTime = eventTime,
                source = CaptureChannel.SMS,
            ),
        )
        val ledger = BankLedgerRules.parse(normalized, sourceKey, "短信", eventTime, bankName)
        if (ledger == null) {
            AppLog.i("Sms", "bank sms not matched (#$rawId) sender=$sender text=[${normalized.take(160)}]")
            return
        }
        enqueueBankLedger(ledger, rawId, eventTime, CaptureChannel.SMS)
    }

    /**
     * 银行 App 动账推送：与短信共用 BankLedgerRules。推送没有号码闸门，
     * 用「正文签名 + 包名短名单」两道线索；解析不通过就不落快照（营销推送不入库）。
     * 返回 true = 命中并已走入账流程。
     */
    private suspend fun handleBankPush(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        postTime: Long,
    ): Boolean {
        val body = NotificationRules.normalize(
            listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString(" "),
        )
        val fallbackBankName = BankLedgerRules.bankNameOfPackage(packageName)
        val ledger = BankLedgerRules.parse(body, packageName, "通知", postTime, fallbackBankName)
        if (ledger == null) {
            AppLog.d("Ntf", "bank push not matched pkg=$packageName body=[${body.take(160)}]")
            return false
        }
        val rawId = captureDao.insert(
            CapturedNotificationEntity(
                packageName = packageName,
                title = title,
                text = text,
                bigText = bigText,
                postTime = postTime,
            ),
        )
        enqueueBankLedger(ledger, rawId, postTime, CaptureChannel.NOTIFICATION)
        return true
    }

    /** 银行动账统一入队：跨通道归并 → 自动入账 → 待确认（与通知/页面通道同一套下游） */
    private suspend fun enqueueBankLedger(
        ledger: BankLedgerRules.BankLedger,
        rawId: Long,
        eventTime: Long,
        channel: CaptureChannel,
    ) {
        val sourceAt = if (eventTime > 0) eventTime else System.currentTimeMillis()

        // 跨通道归并（±130s）：支付侧（微信/支付宝）已捕获同笔 → 银行侧只补充账户线索，
        // 不再产生第二条（真实场景：银行卡绑定微信支付一笔，支付通知 + 银行短信同时到）
        val related = findCrossChannelPending(ledger, sourceAt)
        if (related != null) {
            mergeBankInfoIntoPending(related, ledger)
            // 同笔的悬浮卡正在展示时同步刷新（银行侧只补账户线索）；没在展示就补试一次弹卡
            refreshCardIfShowing(related.id)
            retryCardIfNoCard(related.id)
            captureDao.updateMatch(rawId, ledger.ruleName + "·并入待确认", true)
            traceCapture(
                channel = channel,
                packageName = ledger.sourceKey,
                title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
                text = ledger.note,
                bigText = "",
                capturedAt = sourceAt,
                matchedRule = ledger.ruleName,
                type = ledger.type,
                amountCents = ledger.amountCents,
                counterparty = ledger.merchant,
                note = ledger.note,
                dedupKey = related.dedupKey,
                outcome = TraceOutcome.MERGED_CROSS,
            )
            return
        }

        // 入账方式：任一"入账"开关开启即先直接入账（与通知/页面通道同一套口径）
        if (directEntryEnabled()) {
            val entered = enterEntry(
                type = ledger.type,
                amountCents = ledger.amountCents,
                timestamp = sourceAt,
                counterparty = ledger.merchant,
                note = ledger.note,
                sourcePackage = ledger.sourceKey,
                dedupKey = ledger.dedupKey,
                ruleName = ledger.ruleName,
                isRefund = ledger.isRefund,
                source = TxSource.BANK,
                bankName = ledger.bankName,
            )
            if (entered != null) {
                captureDao.updateMatch(rawId, ledger.ruleName, true)
                AppLog.i(
                    "Sms",
                    "entered: ${ledger.ruleName} ${ledger.type} ${Money.formatYuan(ledger.amountCents)}" +
                        (if (ledger.isRefund) " [退款]" else "") + " ${ledger.displayLabel}",
                )
                traceCapture(
                    channel = channel,
                    packageName = ledger.sourceKey,
                    title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
                    text = ledger.note,
                    bigText = "",
                    capturedAt = sourceAt,
                    matchedRule = ledger.ruleName,
                    type = ledger.type,
                    amountCents = ledger.amountCents,
                    counterparty = ledger.merchant,
                    note = ledger.note,
                    dedupKey = ledger.dedupKey,
                    outcome = TraceOutcome.AUTO_ENTRY,
                )
                announceEntry(
                    entered = entered,
                    type = ledger.type,
                    isRefund = ledger.isRefund,
                    amountCents = ledger.amountCents,
                    sourceLabel = ledger.displayLabel,
                    counterparty = ledger.merchant,
                    dedupKey = ledger.dedupKey,
                )
                return
            }
        }

        val bankPendingId = pendingDao.insert(
            PendingTransactionEntity(
                type = ledger.type,
                amountCents = ledger.amountCents,
                timestamp = sourceAt,
                counterparty = ledger.merchant,
                note = ledger.note,
                sourcePackage = ledger.sourceKey,
                ruleName = ledger.ruleName,
                dedupKey = ledger.dedupKey,
                rawNotificationId = rawId,
                refundMark = if (ledger.isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
            ),
        )
        captureDao.updateMatch(rawId, ledger.ruleName, true)
        AppLog.i(
            "Sms",
            "queued pending: ${ledger.ruleName} ${ledger.type} ${Money.formatYuan(ledger.amountCents)}" +
                " 账户=${ledger.accountLabel}",
        )
        traceCapture(
            channel = channel,
            packageName = ledger.sourceKey,
            title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
            text = ledger.note,
            bigText = "",
            capturedAt = sourceAt,
            matchedRule = ledger.ruleName,
            type = ledger.type,
            amountCents = ledger.amountCents,
            counterparty = ledger.merchant,
            note = ledger.note,
            dedupKey = ledger.dedupKey,
            outcome = TraceOutcome.PENDING,
        )
        announceCapture(
            type = ledger.type,
            isRefund = ledger.isRefund,
            amountCents = ledger.amountCents,
            sourceLabel = ledger.displayLabel,
            counterparty = ledger.merchant,
            paymentMethod = null,
            dedupKey = ledger.dedupKey,
            ruleName = ledger.ruleName,
            txTime = sourceAt,
            note = ledger.note,
            pendingId = bankPendingId,
        )
    }

    /** 跨通道归并候选：合并池里"通道类别相异（支付类⇄银行类）且特征全等"的同笔条目 */
    private suspend fun findCrossChannelPending(
        ledger: BankLedgerRules.BankLedger,
        eventTime: Long,
    ): PendingTransactionEntity? {
        val incoming = CrossChannelDedup.Signature(
            amountCents = ledger.amountCents,
            type = ledger.type,
            isRefund = ledger.isRefund,
            source = ledger.sourceKey,
            time = eventTime,
        )
        return pendingDao.getMergeable().firstOrNull { candidate ->
            CrossChannelDedup.isSameTransaction(
                CrossChannelDedup.signatureOf(
                    amountCents = candidate.amountCents,
                    type = candidate.type,
                    refundMark = candidate.refundMark,
                    source = candidate.sourcePackage,
                    time = candidate.timestamp,
                ),
                incoming,
            )
        }
    }

    /**
     * 银行侧信息并入既有条目：支付侧信息更全（商户/支付方式），只追加银行侧独有的账户线索
     * （"银行 工商银行(1234)"），不改金额/时间/方向（金额全等才可能命中）；
     * 银行侧的卡信息同时写进支付工具列（页面没给出支付工具时用它做账户匹配）。
     * 用户手动改过的条目只补账户线索，不动金额/商户。
     * 目标若是自动入账条目，同步回填交易本身（与页面回填同一路径）。
     */
    private suspend fun mergeBankInfoIntoPending(
        existing: PendingTransactionEntity,
        ledger: BankLedgerRules.BankLedger,
    ) {
        val extra = "银行 ${ledger.accountLabel}"
        val note = if (existing.note.contains(ledger.accountLabel)) {
            existing.note
        } else {
            (if (existing.note.isBlank()) extra else "${existing.note} · $extra").take(300)
        }
        val counterparty = existing.counterparty.ifBlank { ledger.merchant }
        pendingDao.updateFromPage(
            existing.id,
            existing.amountCents,
            existing.timestamp,
            counterparty,
            note,
            existing.ruleName,
        )
        if (existing.paymentInstrument.isBlank()) {
            pendingDao.updatePaymentInstrument(existing.id, ledger.accountLabel)
        }
        if (existing.status == PendingStatus.AUTO && !existing.userEdited) {
            txDao.updateCapturedByExternalId(
                "NTF:${existing.dedupKey}",
                existing.amountCents,
                existing.timestamp,
                counterparty,
                note,
            )
        }
        AppLog.i(
            "Sms",
            "merged bank info into " +
                (if (existing.status == PendingStatus.AUTO) "auto entry" else "pending") +
                " #${existing.id} ${ledger.ruleName} 账户=${ledger.accountLabel}" +
                (if (existing.userEdited) " [用户已手改，仅补账户线索]" else ""),
        )
    }

    // ---------- 退款关联提醒（无金额退款通知） ----------

    /** 微信"退款到账通知"没有金额，无法直接入队：发通知提醒用户进 App 选择被退款的账单 */
    private suspend fun handleRefundNotice(
        packageName: String,
        rawId: Long,
        postTime: Long,
        title: String,
        text: String,
        bigText: String,
    ) {
        captureDao.updateMatch(rawId, "退款·待关联", false)
        AppLog.i("Ntf", "amountless refund notice -> link reminder pkg=$packageName")
        // 溯源记录不产生账目：dedupKey 用提醒 id 的同一构造，永远不会和交易对上（状态固定为"仅提醒"）
        traceCapture(
            channel = CaptureChannel.NOTIFICATION,
            packageName = packageName,
            title = title, text = text, bigText = bigText,
            capturedAt = if (postTime > 0) postTime else System.currentTimeMillis(),
            matchedRule = "退款·待关联",
            type = null,
            amountCents = 0,
            dedupKey = "refund|$packageName|$postTime",
            outcome = TraceOutcome.REFUND_NOTICE,
        )
        if (!isRefundLinkEnabled()) return
        val label = NotificationRules.sourceLabel(packageName)
        val notifId = ("refund|$packageName|$postTime").hashCode()
        postCaptureNotification(
            "${label}退款到账",
            "点击选择被退款的账单，记录退款收入",
            ROUTE_REFUND_LINK,
            notifId,
        )
    }

    // ---------- 通道二：无障碍页面抓取 ----------

    /**
     * 由无障碍服务回调（采集线程）：处理一份结构化页面快照。
     *
     * 调用链（页面通道的唯一入口）：
     * `PageParserRegistry.parse`（按包名分派专属解析器）→ 页面候选 → 与既有条目融合 → 入账/待确认。
     * 旧的"通用关键词解析 + 按包名白名单再过滤一次"入口已删除，不存在第二个页面解析入口。
     */
    fun onPageCaptured(snapshot: PageSnapshot, isDebugBuild: Boolean) {
        _lastEventAt.value = System.currentTimeMillis()

        if (!initialized || snapshot.lines.isEmpty()) return
        val packageName = snapshot.packageName
        // 「捕获所有页面（调试）」优先于监听目录：这个开关的目的就是采样任意应用的页面扩规则
        val captureAll = isDebugCaptureAllPages(isDebugBuild)
        if (!captureAll && packageName != appContext.packageName &&
            !CaptureAppCatalog.pageCaptureAllowed(appContext, packageName)
        ) {
            AppLog.d("Page", "skip (app not watched) pkg=$packageName")
            return
        }
        // 总开关 + 页面通道开关：两个都要看（此前总开关没有约束页面通道，自测发现的缺陷）
        if (!isCaptureEnabled()) {
            AppLog.d("Page", "skip (capture disabled) pkg=$packageName")
            return
        }
        if (!isA11yCaptureEnabled() && !captureAll) {
            AppLog.d("Page", "skip (a11y capture disabled) pkg=$packageName")
            return
        }

        scope.launch {
            try {
                handlePageSnapshot(snapshot, captureAll, isDebugBuild)
            } catch (e: Exception) {
                AppLog.e("Page", "page capture handling failed", e)
            }
        }
    }

    /** 页面快照的处理主体：解析 → 留样 → 融合 → 入账 */
    private suspend fun handlePageSnapshot(snapshot: PageSnapshot, captureAll: Boolean, isDebugBuild: Boolean) {
        val packageName = snapshot.packageName
        val capturedAt = snapshot.captureTime

        val flows = PageFlowTracker.shared
        flows.evictExpired()
        val flow = flows.flow(packageName)
        val result = PageParserRegistry.parse(snapshot, flow)
        val candidate = (result as? PageParseResult.Candidate)?.candidate

        val interesting = candidate != null || isInterestingPage(snapshot, result)
        if (!interesting && !captureAll) {
            AppLog.d("Page", "skip (not interesting) pkg=$packageName lines=${snapshot.lines.size}")
            return
        }

        // 支付类页面快照进 INFO 日志（华为默认隐藏 DEBUG 级日志）；文本做卡尾号遮罩后再落日志与样本库。
        // 日志里只对"交易相关页面"给足正文（调规则要看它）：无关页面（桌面/系统界面/聊天）只给短摘要——
        // 通知栏正文、桌面图标清单属于用户隐私，不该进日志（样本库仍在本地留全量，供扩规则）。
        val snapshotText = NotificationRules.maskCardTails(snapshot.lines.joinToString(" | ")).take(4000)
        // 注意不要写成 `(result as? SceneOnly)?.scene != UNKNOWN`：转换失败时左边是 null，
        // `null != UNKNOWN` 恒为真，反而把无关页面判成交易相关（真机日志里踩到过）
        val tradeRelated = candidate != null ||
            (result is PageParseResult.SceneOnly && result.scene != PageScene.UNKNOWN) ||
            PageParserRegistry.hasDedicatedParser(packageName)
        val logLimit = if (tradeRelated || isDebugBuild) 600 else 120
        if (candidate != null) {
            AppLog.i(
                "Page",
                "page matched pkg=$packageName rule=${candidate.ruleName} scene=${candidate.scene} " +
                    "state=${candidate.tradeState} ${Money.formatYuan(candidate.amountCents)} " +
                    "收款方=${candidate.counterparty} 支付方式=${candidate.paymentMethod ?: "-"} " +
                    "texts=[${snapshotText.take(logLimit)}]",
            )
        } else {
            AppLog.i(
                "Page",
                "page scene pkg=$packageName " +
                    "detail=${(result as? PageParseResult.SceneOnly)?.let { "${it.scene}/${it.tradeState} ${it.reason}" } ?: "-"} " +
                    "texts=[${snapshotText.take(logLimit)}]",
            )
        }

        val rawId = captureDao.insert(
            CapturedNotificationEntity(
                packageName = packageName,
                title = candidate?.ruleName ?: "页面快照",
                text = snapshotText,
                postTime = capturedAt,
                source = CaptureChannel.A11Y,
            ),
        )
        if (candidate == null) {
            captureDao.updateMatch(rawId, "页面快照", false)
            return
        }

        if (!isA11yCaptureEnabled()) {
            // 采样模式（页面抓取关 + 调试全量开）：只留证据，不入队不弹窗
            captureDao.updateMatch(rawId, candidate.ruleName, false)
            AppLog.i("Page", "skip enqueue（设置页「自动记录支付页面」开关关闭）${candidate.ruleName}")
            return
        }

        // 同笔的"金额待补"占位条被完整页面捕获取代（占位条只用于兜底提醒，不保留）
        supersedeAmountStubs(packageName, candidate.type, capturedAt)
        // 去重键：同分钟同额的第二笔独立交易加流程后缀，避免被唯一索引吞掉
        val flowIdentity = flowIdentityFor(flow, snapshot)
        val dedupKey = dedupKeyAllocator.allocate(candidate.dedupKey, flowIdentity)

        // 融合（页面 → 通知 / 同流程的后续页面）：命中即合并，不产生第二条
        val mergeable = pendingDao.getMergeable()
        val related = pickRelatedPendingForPage(
            mergeable, packageName, candidate.type, candidate.amountCents, capturedAt, flowIdentity,
        )
        if (related != null) {
            mergePageIntoExisting(related, candidate, rawId, snapshotText, capturedAt)
            return
        }

        // 页面信息最全：任一入账开关开启即直接入账
        if (directEntryEnabled()) {
            val entered = enterEntry(
                type = candidate.type,
                amountCents = candidate.amountCents,
                timestamp = candidate.txTime ?: capturedAt,
                counterparty = candidate.counterparty,
                note = candidate.note,
                sourcePackage = packageName,
                dedupKey = dedupKey,
                ruleName = candidate.ruleName,
                isRefund = candidate.isRefund,
                source = CaptureDefaults.txSource(packageName),
                paymentInstrument = candidate.instrument?.label,
            )
            if (entered != null) {
                captureDao.updateMatch(rawId, candidate.ruleName, true)
                AppLog.i(
                    "Page",
                    "entered: ${candidate.ruleName} ${Money.formatYuan(candidate.amountCents)} " +
                        "收款方=${candidate.counterparty} 支付方式=${candidate.paymentMethod ?: "-"}",
                )
                traceCapture(
                    channel = CaptureChannel.A11Y,
                    packageName = packageName,
                    title = "页面快照",
                    text = snapshotText,
                    bigText = "",
                    capturedAt = capturedAt,
                    matchedRule = candidate.ruleName,
                    type = candidate.type,
                    amountCents = candidate.amountCents,
                    counterparty = candidate.counterparty,
                    note = candidate.note,
                    dedupKey = dedupKey,
                    outcome = TraceOutcome.AUTO_ENTRY,
                )
                announceEntry(
                    entered = entered,
                    type = candidate.type,
                    isRefund = candidate.isRefund,
                    amountCents = candidate.amountCents,
                    sourceLabel = candidate.sourceLabel,
                    counterparty = candidate.counterparty,
                    dedupKey = dedupKey,
                )
                return
            }
        }

        val pendingId = pendingDao.insert(
            PendingTransactionEntity(
                type = candidate.type,
                amountCents = candidate.amountCents,
                timestamp = candidate.txTime ?: capturedAt,
                counterparty = candidate.counterparty,
                note = candidate.note,
                sourcePackage = packageName,
                ruleName = candidate.ruleName,
                dedupKey = dedupKey,
                rawNotificationId = rawId,
                refundMark = if (candidate.isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                paymentInstrument = candidate.instrument?.label.orEmpty(),
            ),
        )
        captureDao.updateMatch(rawId, candidate.ruleName, true)
        AppLog.i(
            "Page",
            "queued pending: ${candidate.ruleName} ${Money.formatYuan(candidate.amountCents)} " +
                "收款方=${candidate.counterparty} 支付方式=${candidate.paymentMethod ?: "-"}",
        )
        traceCapture(
            channel = CaptureChannel.A11Y,
            packageName = packageName,
            title = "页面快照",
            text = snapshotText,
            bigText = "",
            capturedAt = capturedAt,
            matchedRule = candidate.ruleName,
            type = candidate.type,
            amountCents = candidate.amountCents,
            counterparty = candidate.counterparty,
            note = candidate.note,
            dedupKey = dedupKey,
            outcome = TraceOutcome.PENDING,
        )
        announceCapture(
            type = candidate.type,
            isRefund = candidate.isRefund,
            amountCents = candidate.amountCents,
            sourceLabel = candidate.sourceLabel,
            counterparty = candidate.counterparty,
            paymentMethod = candidate.paymentMethod,
            dedupKey = dedupKey,
            ruleName = candidate.ruleName,
            txTime = candidate.txTime,
            note = candidate.note,
            pendingId = pendingId,
        )
    }

    /**
     * 页面候选并入既有条目（页面信息优先）。
     * 覆盖金额/时间/商户/备注/支付工具；用户手改过的条目只补空白（见 mergePageInfo）；
     * 已自动入账的条目同步回填交易本身。
     */
    private suspend fun mergePageIntoExisting(
        related: RelatedPending,
        candidate: PageCandidate,
        rawId: Long,
        snapshotText: String,
        capturedAt: Long,
    ) {
        val existing = related.pending
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = candidate.amountCents,
            pageTxTime = candidate.txTime,
            pageCounterparty = candidate.counterparty,
            pageNote = candidate.note,
            pagePaymentMethod = candidate.paymentMethod,
            pageRuleName = candidate.ruleName,
            pageInstrument = candidate.instrument?.label.orEmpty(),
        )
        pendingDao.updateFromPage(
            existing.id, merged.amountCents, merged.timestamp, merged.counterparty, merged.note, merged.ruleName,
        )
        if (merged.paymentInstrument.isNotBlank() && merged.paymentInstrument != existing.paymentInstrument) {
            pendingDao.updatePaymentInstrument(existing.id, merged.paymentInstrument)
        }
        val autoEntered = existing.status == PendingStatus.AUTO
        if (autoEntered && !existing.userEdited) {
            // 自动入账的那笔：把页面信息同步回交易本身
            txDao.updateCapturedByExternalId(
                "NTF:${existing.dedupKey}",
                merged.amountCents,
                merged.timestamp,
                merged.counterparty,
                merged.note,
            )
        }
        captureDao.updateMatch(rawId, candidate.ruleName, true)
        AppLog.i(
            "Page",
            (if (autoEntered) "filled auto entry #" else "merged page info into pending #") +
                "${existing.id} ${candidate.ruleName} ${Money.formatYuan(candidate.amountCents)}" +
                (if (related.sameAmount) "" else " (原金额 ${Money.formatYuan(existing.amountCents)}，页面金额为准)") +
                (if (existing.userEdited) " [用户已手改，仅补空白]" else ""),
        )
        // 溯源：本页捕获的字段是**合并后真正生效的值**，附上页面原始快照，
        // 键取同笔条目的 dedupKey——金额被页面改写时通知侧的键已对不上交易，只有这个键能解析到账目
        traceCapture(
            channel = CaptureChannel.A11Y,
            packageName = existing.sourcePackage,
            title = "页面快照",
            text = snapshotText,
            bigText = "",
            capturedAt = capturedAt,
            matchedRule = merged.ruleName,
            type = candidate.type,
            amountCents = merged.amountCents,
            counterparty = merged.counterparty,
            note = merged.note,
            dedupKey = existing.dedupKey,
            outcome = TraceOutcome.MERGED_PAGE,
        )
        // 页面富化：卡片在展示就刷新内容，没在展示就按"卡片优先"再试一次
        refreshCardIfShowing(existing.id)
        retryCardIfNoCard(existing.id)
    }

    /**
     * 页面是否值得进捕获日志（未匹配样本池）：解析器判定的交易场景，或页面命中交易锚点。
     * 聊天/朋友圈等噪音页不进库，避免捕获日志被无障碍事件刷爆。
     */
    private fun isInterestingPage(snapshot: PageSnapshot, result: PageParseResult): Boolean {
        if (result is PageParseResult.SceneOnly && result.scene != PageScene.UNKNOWN) return true
        val joined = snapshot.joined
        return pageInterestingMarkers.any { joined.contains(it) }
    }

    private val pageInterestingMarkers = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "账单详情", "交易详情", "订单详情",
        "收款成功", "退款成功", "转账成功", "付款方式", "支付方式", "交易单号", "订单编号",
    )

    /** 页面候选的流程标识：同一次支付流程内的多张页面共享，另一笔同额交易必然不同 */
    private fun flowIdentityFor(flow: PageFlow, snapshot: PageSnapshot): String {
        val raw = "${flow.pageKey}|w${snapshot.windowId}"
        return "f" + Integer.toHexString(raw.hashCode())
    }

    /** 直接入账结果：新交易 id + 对应的 AUTO 条目 id（卡片与通知都按它们工作） */
    private data class Entered(val txId: Long, val pendingId: Long)

    /** 三种入账方式（入账提醒/悬浮窗入账/自动入账）任一开启即"先直接入账"；全关 = 完全手动模式 */
    private fun directEntryEnabled(): Boolean =
        com.controleco.app.capture.directEntryEnabled(
            autoEntry = isAutoEntryEnabled(),
            overlayConfirm = isOverlayConfirmEnabled(),
            captureNotify = isCaptureNotifyEnabled(),
        )

    /**
     * 直接入账（三种入账方式共用）：按推测分类/账户先落账——这就是"先入账"的落点，
     * 后续的卡片（改/删）与通知（提醒/撤销）都围绕这笔已有账目工作，卡片弹不出来账也不会丢。
     * 条目以 AUTO 状态留在合并池里——页面通道随后捕获同笔时回填更全的信息。
     * 返回 null = 没有可用的分类/账户、重复预检命中或写入失败，调用方回落待确认流程（不丢单）。
     */
    private suspend fun enterEntry(
        type: TxType,
        amountCents: Long,
        timestamp: Long,
        counterparty: String,
        note: String,
        sourcePackage: String,
        dedupKey: String,
        ruleName: String,
        isRefund: Boolean = false,
        source: TxSource = TxSource.NOTIFICATION,
        bankName: String? = null,
        paymentInstrument: String? = null,
    ): Entered? {
        val learned = merchantRuleDao.getAll()
        val categories = categoryDao.getAll()
        val accounts = accountDao.getAll()
        val categoryId = CaptureDefaults.categoryId(
            categories, type, counterparty.ifBlank { note },
            learnedRules = learned, counterparty = counterparty, note = note,
        )
        // 账户：页面解析出的支付工具（"招商银行(1234)"/"零钱"）优先参与匹配——
        // "从微信付的"不等于"从微信零钱付的"，匹配不到才回落渠道兜底
        val accountId = CaptureDefaults.accountId(
            accounts, sourcePackage, bankName,
            learnedAccountId = MerchantRules.accountIdFrom(learned, accounts, type, counterparty, note),
            instrumentLabel = paymentInstrument,
        )
        if (categoryId == null || accountId == null) {
            AppLog.w("Ntf", "direct entry skipped (no category/account to use)")
            return null
        }
        // 重复预检（±60s 同数据库校验）：同来源+同方向+同金额在窗口内已有条目（待确认或已入账）
        // → 不静默入账，回落"入队 + 提醒"（重复通知/分钟边界重发不会造成双记账，可疑的第二笔仍让用户看到）
        if (hasRecentSameEntry(
                pendingDao.getMergeable(), sourcePackage, type, amountCents, System.currentTimeMillis(),
                txTime = timestamp,
            )
        ) {
            AppLog.i("Ntf", "direct entry skipped (recent same entry) $sourcePackage $type ${Money.formatYuan(amountCents)}")
            return null
        }
        return try {
            val txId = txDao.insert(
                TransactionEntity(
                    type = type,
                    amountCents = amountCents,
                    categoryId = categoryId,
                    accountId = accountId,
                    timestamp = timestamp,
                    note = note.ifBlank { ruleName },
                    counterparty = counterparty,
                    source = source,
                    externalId = "NTF:$dedupKey",
                    refundMark = if (isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                ),
            )
            val pendingId = pendingDao.insert(
                PendingTransactionEntity(
                    type = type,
                    amountCents = amountCents,
                    timestamp = timestamp,
                    counterparty = counterparty,
                    note = note,
                    sourcePackage = sourcePackage,
                    ruleName = ruleName,
                    dedupKey = dedupKey,
                    refundMark = if (isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                    status = PendingStatus.AUTO,
                    paymentInstrument = paymentInstrument.orEmpty(),
                ),
            )
            AppLog.i(
                "Ntf",
                "entered #$txId ${Money.formatYuan(amountCents)} dedup=$dedupKey" +
                    (paymentInstrument?.let { " 支付工具=$it" } ?: ""),
            )
            Entered(txId, pendingId)
        } catch (e: Exception) {
            // externalId 唯一索引冲突 = 这笔已经入过账（重复通知/双通道）：找回原交易，卡片/通知仍可用
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                val existing = txDao.findIdByExternalId("NTF:$dedupKey")
                AppLog.i("Ntf", "direct entry skipped (already recorded) dedup=$dedupKey existing=$existing")
                if (existing != null) Entered(existing, -1L) else null
            } else {
                AppLog.w("Ntf", "direct entry failed, fallback to pending", e)
                null
            }
        }
    }

    // ---------- 通知测试（debug）----------

    /** debug：延迟 20 秒发一条样例捕获通知（验证通知通道可用性与点击跳转） */
    fun scheduleNotifyTest(onResult: (String) -> Unit) {
        if (!initialized) return
        if (!canPostNotifications()) {
            onResult("未授权通知权限：设置页「动账通知提醒」→ 申请权限")
            return
        }
        mainHandler.postDelayed({
            postCaptureNotification("测试通知（我ECO）", "这是一条样例动账提醒 · 点击应进入待确认队列", ROUTE_PENDING)
            toast("延迟测试：通知已发送")
        }, 20_000L)
        onResult("已安排：20 秒后发送测试通知（可切到任意界面等待）")
    }

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
