package com.controleco.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.controleco.app.BuildConfig
import com.controleco.app.capture.page.PageFlowTracker
import com.controleco.app.capture.page.PageNode
import com.controleco.app.capture.page.PageParserRegistry
import com.controleco.app.capture.page.PageSnapshot
import com.controleco.app.util.AppLog
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

/**
 * 无障碍页面采集服务：把"当前页面"读成结构化快照（[PageSnapshot]）交给 [CaptureCenter]。
 *
 * 采集能力（与 accessibility_service_config.xml 对应）：
 * - `flagRetrieveInteractiveWindows`：多窗口读取——支付成功页可能是 dialog/非焦点窗口，
 *   不会成为 rootInActiveWindow；
 * - `flagIncludeNotImportantViews`：不少支付页把金额/商户节点标成"不重要视图"，不开读不到；
 * - `flagReportViewIds`：保留控件 id（改版后按 id 定位比按文本稳，作为文本定位的补充证据）；
 * - 只读窗口内容，不申请手势/按键/截图能力（截图与 OCR 一律不用）。
 *
 * 节点读取有三个来源，互为补充（见 [buildSnapshot]）：
 * 1. `event.source`（[captureEventSource]）：事件仍然有效时读一次，**读成结构化数据**后立刻放手；
 * 2. `getRootInActiveWindow()`：活动窗口根节点（页面主体）；
 * 3. `getWindows()`：同包名的其余窗口（支付弹层/红包窗口/转账确认页等）。
 * 三条来源最终都汇入同一个 [PageSnapshot]，不存在第二个页面入口。
 *
 * 采样策略（替代"固定延迟 + 60 秒指纹去重"）：
 * 1. 高频事件先合并（[DEBOUNCE_MS] 内的连续事件只采一次）；
 * 2. 首采后若页面还在变化（连续两轮内容不一致）则按 [SAMPLE_INTERVAL_MS] 补采，
 *    最多 [MAX_SAMPLE_ROUNDS] 轮——支付成功页常有"结果先出来、明细后渲染"的过程；
 * 3. 采稳（连续两轮内容一致）或补采用尽才把**最后一份快照**交给采集中心，同一轮不会被处理多次；
 * 4. 同内容的重复上报只在 [FINGERPRINT_TTL_MS] 的极短窗口内抑制（防事件风暴），
 *    不再有"60 秒内同内容一律跳过"——那会把同额的第二笔真实交易吞掉；
 * 5. 真正的"同一笔不重复记账、同额两笔不合并"由解析器的流程上下文
 *    （见 page/AppPageParser.kt 的 PageFlowTracker）与采集中心的跨渠道归并负责。
 *
 * 监听范围由**运行时** [applyServiceInfo] 收敛到用户选择（「监听的 App」）：
 * 系统级包名过滤（`serviceInfo.packageNames`）+ 服务内 [shouldCapture] 两道闸门口径一致，
 * 用户关掉某个应用的页面采集后，它的事件既不会送进来、也不会被采集。
 * 调试「捕获所有页面」开启时放开系统级过滤（该开关的目的就是采样目录外的应用）。
 *
 * 本类可被继承：实际注册到 APK 的是入口子类
 * [com.google.android.accessibility.selecttospeak.SelectToSpeakService]，
 * 采集逻辑只有这一份（见 [SERVICE_CLASS_NAME]）。
 *
 * 事件回调里只做"有界的控件树读取"（节点数/深度双上限），解析在采集中心的 IO 线程完成。
 */
open class AccBookAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 实际注册到 APK 的无障碍组件类名（入口子类）。
         *
         * 设置页授权状态、[isEnabled] 双源检测、服务连接状态都以这个组件身份为准——
         * 用旧的 `…capture.AccBookAccessibilityService` 去比对，会出现"系统已启用新组件、
         * 应用却判定未授权"的误判。单元测试用它锁定"入口类名 = 声明组件类名"。
         */
        const val SERVICE_CLASS_NAME: String =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

        /** 高频事件合并窗口：窗口切换与内容变化的连续事件只采一次 */
        private const val DEBOUNCE_MS = 140L

        /** 稳定性补采间隔 */
        private const val SAMPLE_INTERVAL_MS = 320L

        /** 单次采集的最多采样轮次（首采算 1 轮） */
        private const val MAX_SAMPLE_ROUNDS = 4

        /** 连续两轮内容一致即视为页面已稳定 */
        private const val STABLE_ROUNDS_REQUIRED = 2

        /** 读到空树的补采次数（窗口切换过渡期内容未就绪） */
        private const val MAX_EMPTY_RETRIES = 3

        /** 单次快照的节点预算（控件树遍历上限，防大页面拖慢事件回调） */
        private const val MAX_NODES = 700
        private const val MAX_DEPTH = 40

        /**
         * 事件源（`event.source`）的结构化读取预算：比整页采集小得多。
         * 事件回调是高频路径（系统按 notificationTimeout 节流后仍可达 10 次/秒），
         * 这里只取事件真正指向的那一小段子树，够判定"能否读到节点"即可。
         */
        private const val MAX_SOURCE_NODES = 120
        private const val MAX_SOURCE_DEPTH = 16

        /** 同内容重复上报的抑制窗口（只防事件风暴，不承担去重语义） */
        private const val FINGERPRINT_TTL_MS = 2_500L
        private const val FINGERPRINT_CAP = 32

        private const val EVENT_LOG_INTERVAL_MS = 2_000L

        /**
         * 当前已连接的服务实例：用户改「监听的 App」或切换采集开关时，
         * 用它同步运行时 ServiceInfo（见 [refreshServiceInfo]）。
         * 服务未连接时为空，连接时 [onServiceConnected] 会重新应用一次，不会漏。
         */
        @Volatile
        private var runningInstance: AccBookAccessibilityService? = null

        /** 实际注册的组件（入口子类），授权检测与设置页展示都用它 */
        fun serviceComponent(context: Context): ComponentName =
            ComponentName(context, SelectToSpeakService::class.java)

        /**
         * 监听范围或采集开关变化后同步运行时 ServiceInfo。
         * 服务未连接时是空操作（连接时 [onServiceConnected] 会按最新配置重新应用）。
         */
        fun refreshServiceInfo() {
            runningInstance?.applyServiceInfo()
        }

        /**
         * 系统无障碍设置中是否已开启本服务（安卓 13+ 侧载需先「允许受限制的设置」才出现在列表里）。
         * **双源检测**：AccessibilityManager 的已启用服务列表 + Settings.Secure 字符串。
         * 只读 Settings 会在系统回写短组件名（`pkg/.Class` 与 `pkg/full.Class` 两种形态）时误报"未开启"
         * （自测踩过的坑：单源读取在部分 ROM 上不稳定，见 docs/自动记账捕获机制.md）。
         *
         * 组件身份只有一个：升级前注册的旧组件不再声明，因此它的残留记录**不算**已开启
         * （否则会显示"已授权"但服务其实没有运行）。
         */
        fun isEnabled(context: Context): Boolean {
            val component = serviceComponent(context)
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val fromManager = am?.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )?.any { it.resolveInfo?.serviceInfo?.let { si ->
                ComponentName(si.packageName, si.name) == component
            } == true } == true
            if (fromManager) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            return a11yEnabledFromSetting(
                enabled,
                longName = component.flattenToString(),
                shortName = component.flattenToShortString(),
            )
        }

        /**
         * Settings 字符串里的启用判定（纯函数便于单测）：系统写入的可能是长组件名
         * （`pkg/full.Class`）也可能是短形态（`pkg/.Class`），两种都要比对。
         */
        internal fun a11yEnabledFromSetting(value: String?, longName: String, shortName: String): Boolean {
            if (value.isNullOrBlank()) return false
            return value.split(':').any { entry ->
                val trimmed = entry.trim()
                trimmed.equals(longName, ignoreCase = true) || trimmed.equals(shortName, ignoreCase = true)
            }
        }

        /**
         * 运行时监听包名过滤（纯函数便于单测），返回值语义与 `serviceInfo.packageNames` 一致：
         * `null` = 不限包名（接收所有应用），空数组 = 不接收任何应用。
         *
         * - 调试「捕获所有页面」开启时返回 `null`：这个开关的目的就是采样目录外的应用，
         *   设了包名过滤就再也采不到新应用，等于把调试能力关掉；
         * - 页面采集关闭时返回空数组：用户关掉的功能不该继续在系统层收事件（省电、少隐私面）；
         * - 其余情况 = 用户监听的目录应用 ∪ 目录外但有专属解析器的来源。
         *   后半部分不能少：专属解析器覆盖的包名若不在监听目录里，设了过滤就永远收不到事件，
         *   解析器形同失效（"运行时包名过滤与解析器覆盖不一致"是必须避免的退化）。
         *
         * 刻意**不**写成"微信/支付宝/云闪付三包名硬限制"：那会让目录里其余已适配应用
         * （抖音/美团/京东/淘宝/饿了么等）全部收不到页面事件。
         */
        internal fun servicePackageFilter(
            watched: Set<String>,
            dedicated: Set<String>,
            listed: Set<String>,
            captureOn: Boolean,
            captureAll: Boolean,
        ): Array<String>? {
            if (captureAll) return null
            if (!captureOn) return emptyArray()
            return (watched + dedicated.filterNot { it in listed }).toTypedArray()
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 待采集的事件信息（事件风暴合并后只保留最后一条） */
    private var pendingPkg: String? = null
    private var pendingClass: String? = null
    private var pendingEventType: Int = 0
    private var pendingEventTime: Long = 0L
    private var collectScheduledAt = 0L

    /**
     * 事件源的结构化副本（见 [captureEventSource]）：**只存数据，不存节点引用**。
     * 仅在整页采集读不到任何文本时兜底使用（窗口过渡期/窗口未进入 `getWindows()` 结果）。
     */
    private var pendingSourceNodes: List<PageNode> = emptyList()
    private var pendingSourceWindowId: Int = -1

    /** 该应用最近一次窗口切换事件里的活动名（解析器的页面判定线索） */
    private val activities = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 16
    }

    /** 本轮的采集状态 */
    private var sampleRound = 0
    private var lastContentHash = 0
    private var stableRounds = 0
    private var emptyRetries = 0

    private val lastEventLogAt = HashMap<String, Long>()

    /** 已上报内容 → 上报时刻（极短窗口抑制重复上报） */
    private val recentFingerprints = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > FINGERPRINT_CAP
    }

    /**
     * 唯一的采集任务令牌：排新一轮前先移除旧回调，避免服务重连/切换应用后
     * 上一轮的补采任务在新会话里继续执行（旧实现会叠加多个 Handler 回调）。
     */
    private val collectRunnable = Runnable { collectCurrentPage() }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        AppLog.i("Page", "a11y service created component=${serviceComponent(this).flattenToShortString()}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runningInstance = this
        resetSession()
        CaptureCenter.markA11yConnected(true)
        applyServiceInfo()
    }

    /** 服务重连/解绑时清掉所有会话内状态，防止旧补采任务与旧指纹影响新会话 */
    private fun resetSession() {
        handler.removeCallbacks(collectRunnable)
        collectScheduledAt = 0L
        pendingPkg = null
        pendingClass = null
        pendingEventType = 0
        pendingEventTime = 0L
        sampleRound = 0
        lastContentHash = 0
        stableRounds = 0
        emptyRetries = 0
        // 事件源结构化副本随会话一起失效：重连后旧窗口的内容不该再当成当前页面
        pendingSourceNodes = emptyList()
        pendingSourceWindowId = -1
        synchronized(recentFingerprints) { recentFingerprints.clear() }
        activities.clear()
        lastEventLogAt.clear()
        // 服务重连后旧支付流程上下文已失效：清掉，避免新会话复用上一笔的金额/商户/流程身份
        PageFlowTracker.shared.clear()
    }

    /**
     * 运行时收敛服务配置，两件事：
     * 1. **事件类型与采集标志**：这里再确认一遍三个采集标志确实生效
     *    （部分 ROM 会忽略 XML 里的部分位）。用 `or` 叠加而不是整体覆盖——
     *    控件 id 采集（`flagReportViewIds`）是现有能力，覆盖成固定值会让它退化；
     * 2. **监听包名**：收敛到用户选择（见 [servicePackageFilter]），与 [shouldCapture] 口径一致。
     *
     * 失败只记日志，不影响服务运行。
     */
    internal fun applyServiceInfo() {
        runCatching {
            val info = serviceInfo ?: return
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                // 独立弹窗（红包窗口/转账确认/收款详情弹层）只发窗口集合变化事件，
                // 不订阅它就会漏掉"窗口换了但活动窗口没换"的采集时机
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            val packages = servicePackageFilter(
                watched = CaptureAppCatalog.watched(this),
                dedicated = PageParserRegistry.dedicatedPackages(),
                listed = CaptureAppCatalog.apps.map { it.packageName }.toSet(),
                captureOn = CaptureCenter.isCaptureEnabled() && CaptureCenter.isA11yCaptureEnabled(),
                captureAll = CaptureCenter.isDebugCaptureAllPages(BuildConfig.DEBUG),
            )
            info.packageNames = packages
            serviceInfo = info
            AppLog.i(
                "Page",
                "a11y service info applied flags=0x${Integer.toHexString(info.flags)} " +
                    "events=0x${Integer.toHexString(info.eventTypes)} " +
                    "windows=${AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS and info.flags != 0} " +
                    "notImportant=${AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS and info.flags != 0} " +
                    "viewIds=${AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS and info.flags != 0} " +
                    "pkgFilter=${packages?.size?.toString() ?: "all"}",
            )
        }.onFailure { AppLog.w("Page", "apply service info failed", it) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (runningInstance === this) runningInstance = null
        resetSession()
        CaptureCenter.markA11yConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (runningInstance === this) runningInstance = null
        resetSession()
        CaptureCenter.markA11yConnected(false)
        super.onDestroy()
    }

    override fun onInterrupt() {
        AppLog.w("Page", "a11y interrupted")
        resetSession()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        var pkg = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotEmpty()) {
            CaptureCenter.noteForegroundPackage(pkg)
            if (className.isNotEmpty()) activities[pkg] = className
        }
        // 窗口集合变化事件可能不带包名：用活动窗口根节点的包名兜底
        if (pkg.isEmpty() && type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull().orEmpty()
        }
        if (pkg.isEmpty()) return

        if (pkg == packageName) return // 自己的页面不采
        if (!shouldCapture(pkg)) return

        // 事件源节点：只在事件仍然有效时读，且**只对用户允许采集的应用读**
        val sourceNodeCount = captureEventSource(pkg, event)

        val now = SystemClock.elapsedRealtime()
        if (now - (lastEventLogAt[pkg] ?: 0L) > EVENT_LOG_INTERVAL_MS) {
            lastEventLogAt[pkg] = now
            AppLog.i(
                "Page",
                "a11y event pkg=$pkg type=0x${Integer.toHexString(type)} " +
                    "cls=${className.substringAfterLast('.')} win=${event.windowId} " +
                    "src=$sourceNodeCount",
            )
        }

        pendingPkg = pkg
        pendingClass = className
        pendingEventType = type
        pendingEventTime = now
        // 新事件到来 = 页面还在变：重置本轮稳定性判定
        sampleRound = 0
        stableRounds = 0
        emptyRetries = 0
        scheduleCollect(DEBOUNCE_MS)
    }

    /**
     * 采集范围：用户监听的目录应用（「监听的 App」开关对页面通道同样有效）
     * ∪ 目录外但有专属解析器的已知交易来源；「捕获所有页面（调试）」开启时放行全部应用。
     *
     * 这是服务内的第二道闸门，与 [applyServiceInfo] 写入的系统级 `packageNames` 同一口径：
     * 系统级过滤省掉不相关事件的开销，服务内判定兜住"ROM 忽略过滤"与"调试放开过滤"两种情况。
     */
    private fun shouldCapture(pkg: String): Boolean {
        if (CaptureCenter.isDebugCaptureAllPages(BuildConfig.DEBUG)) return true
        if (!CaptureCenter.isCaptureEnabled()) return false
        if (!CaptureCenter.isA11yCaptureEnabled()) return false
        return CaptureAppCatalog.pageCaptureAllowed(this, pkg)
    }

    /**
     * 读取事件源节点（`event.source`）：**只在事件回调内读一次，并立刻转成结构化数据**。
     *
     * 为什么必须在这里读：`AccessibilityNodeInfo` 只在事件仍然有效时可访问，
     * 把它（或 `AccessibilityEvent` 本身）缓存进 Handler 延迟任务，等采集真正执行时
     * 节点往往已经失效（读到 null 或抛 `IllegalStateException`）——这正是"延迟处理读不到节点"的根因。
     * 所以这里读完就放手，只把 [PageNode] 数据留在 [pendingSourceNodes] 里。
     *
     * 包名校验：节点所属包名必须与当前目标应用一致，否则一律丢弃（不读其他应用的内容）。
     * 文本为空的源节点不算数（节点有效但读不到文本 ≠ 读到了完整页面）。
     *
     * @return 读到的节点数（0 = 源不可读或没有文本），供事件日志做"节点读取失败诊断"
     */
    private fun captureEventSource(pkg: String, event: AccessibilityEvent): Int {
        pendingSourceNodes = emptyList()
        pendingSourceWindowId = -1
        val source = runCatching { event.source }.getOrNull() ?: return 0
        if (runCatching { source.packageName?.toString() }.getOrNull() != pkg) return 0
        val out = ArrayList<PageNode>(32)
        collectNode(source, 0, -1, out, MAX_SOURCE_NODES, MAX_SOURCE_DEPTH)
        if (out.none { !it.isEmpty }) return 0
        pendingSourceWindowId = runCatching { source.windowId }.getOrDefault(-1)
        pendingSourceNodes = out
        return out.size
    }

    private fun scheduleCollect(delayMs: Long) {
        val at = SystemClock.elapsedRealtime() + delayMs
        // 已有更早的计划则不重复排程（事件风暴合并）；更晚的计划会被新的更早计划取代，
        // 旧回调必须先移除，否则同一轮会出现多个 collectCurrentPage 并发读树。
        if (collectScheduledAt != 0L && collectScheduledAt <= at) return
        handler.removeCallbacks(collectRunnable)
        collectScheduledAt = at
        handler.postDelayed(collectRunnable, delayMs)
    }

    private fun collectCurrentPage() {
        collectScheduledAt = 0L
        val pkg = pendingPkg ?: return
        val snapshot = buildSnapshot(pkg)
        val contentHash = snapshot.fingerprint.hashCode()

        // 只在"调试全量采样"里出现的包名（桌面/系统界面等）不做稳定性补采：
        // 它们没有解析器，采多轮也只是白跑；走一轮留样本即可
        val samplingOnly = !CaptureAppCatalog.isListed(pkg) &&
            PageParserRegistry.parserFor(pkg) == null

        // 空树：窗口切换过渡期内容未就绪，限次补采
        if (snapshot.lines.isEmpty()) {
            if (emptyRetries < if (samplingOnly) 1 else MAX_EMPTY_RETRIES) {
                emptyRetries++
                AppLog.i("Page", "collect empty pkg=$pkg retry#$emptyRetries")
                scheduleCollect(SAMPLE_INTERVAL_MS)
            } else {
                AppLog.i(
                    "Page",
                    "collect empty pkg=$pkg give up（活动窗口/多窗口/事件源三条来源都没有文本）",
                )
            }
            return
        }

        sampleRound++
        stableRounds = if (contentHash == lastContentHash) stableRounds + 1 else 0
        lastContentHash = contentHash

        val settled = stableRounds + 1 >= STABLE_ROUNDS_REQUIRED
        val roundsExhausted = sampleRound >= MAX_SAMPLE_ROUNDS
        if (samplingOnly || settled || roundsExhausted) {
            deliver(snapshot, contentHash)
            return
        }
        // 页面还在渲染：补采一轮（成功页常有"结果先出来、明细后渲染"）
        scheduleCollect(SAMPLE_INTERVAL_MS)
    }

    /** 采稳后交付：同一内容的重复上报只在极短窗口内抑制 */
    private fun deliver(snapshot: PageSnapshot, contentHash: Int) {
        val key = "${snapshot.packageName}|$contentHash|${snapshot.windowId}"
        val now = SystemClock.elapsedRealtime()
        synchronized(recentFingerprints) {
            val prev = recentFingerprints[key]
            if (prev != null && now - prev < FINGERPRINT_TTL_MS) {
                AppLog.d("Page", "skip identical page (${now - prev}ms) pkg=${snapshot.packageName}")
                return
            }
            recentFingerprints[key] = now
        }
        AppLog.d(
            "Page",
            "snapshot pkg=${snapshot.packageName} cls=${snapshot.className.substringAfterLast('.')} " +
                "nodes=${snapshot.nodes.size} lines=${snapshot.lines.size}",
        )
        CaptureCenter.onPageCaptured(snapshot, BuildConfig.DEBUG)
    }

    /**
     * 采集当前页面为结构化快照，三条来源按顺序补齐：
     *
     * 1. **活动窗口根节点**（`getRootInActiveWindow()`）：页面主体，packageName 必须是目标应用；
     * 2. **同包名的其余窗口**（`getWindows()`）：支付弹层/红包窗口/转账确认页这类
     *    不是活动窗口的独立窗口，按窗口 id 去重，逐个窗口独立归属（节点保留自己的 `windowId`，
     *    解析器可用 [PageSnapshot.linesOfWindow] 区分弹层与背景页，不会把背景文本当成弹层内容）；
     * 3. **事件源结构化副本**（[pendingSourceNodes]）：仅在前面两条都没有文本时兜底
     *    （窗口过渡期读到空树、或该窗口没有出现在 `getWindows()` 结果里）。
     *
     * 每次都重新枚举窗口、重新取根节点，不缓存窗口对象与旧窗口 id 下的节点
     * （窗口关闭后旧 id 的节点一律失效）。
     */
    private fun buildSnapshot(pkg: String): PageSnapshot {
        val nodes = ArrayList<PageNode>(256)
        val collectedWindowIds = HashSet<Int>(8)
        var activeWindowId = -1
        var activeWindowType = -1
        var activeIsTarget = false

        val root = rootInActiveWindow
        if (root != null) {
            activeWindowId = root.windowId
            activeWindowType = windowTypeOf(activeWindowId)
            activeIsTarget = root.packageName?.toString() == pkg
            if (activeIsTarget) {
                collectNode(root, 0, -1, nodes)
                collectedWindowIds.add(activeWindowId)
            }
        }
        // 同包名的其余窗口（活动窗口已采过则跳过，避免重复文本）
        if (nodes.size < MAX_NODES) {
            runCatching {
                for (window in windows.orEmpty()) {
                    if (nodes.size >= MAX_NODES) break
                    if (!isCollectableWindowType(window.type)) continue
                    // 同一窗口只遍历一次（窗口集合里可能出现同一 id 的重复项）
                    if (!collectedWindowIds.add(window.id)) continue
                    val windowRoot = window.root ?: continue
                    if (windowRoot.packageName?.toString() != pkg) continue
                    // 窗口对象本身没有"对用户可见"API（只有节点有）：用窗口根节点的
                    // isVisibleToUser 作为等价判据。除聚焦/活动窗口外，红包封面这类
                    // 大尺寸可见覆盖层通常不是 focused/active，但它是当前页面主体，必须采集；
                    // 小尺寸浮窗（微信浮窗/APK 文件）仍排除。
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    val metrics = resources.displayMetrics
                    val windowArea = bounds.width().toLong() * bounds.height()
                    val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels
                    val largeVisible = screenArea > 0 && windowArea * 2 >= screenArea
                    if (!shouldCollectSecondaryWindow(
                            visible = windowRoot.isVisibleToUser,
                            focused = window.isFocused,
                            active = window.isActive,
                            largeVisible = largeVisible,
                        )
                    ) continue
                    collectNode(windowRoot, 0, -1, nodes)
                }
            }.onFailure { AppLog.w("Page", "windows enumerate failed", it) }
        }
        // 前两条来源都没有文本：用事件源的结构化副本兜底（数据是事件有效时读到的，不是失效节点引用）
        if (nodes.none { !it.isEmpty } && pendingSourceNodes.isNotEmpty()) {
            AppLog.d("Page", "fallback to event source nodes pkg=$pkg n=${pendingSourceNodes.size}")
            nodes.clear()
            nodes.addAll(pendingSourceNodes)
            activeWindowId = pendingSourceWindowId
            activeWindowType = windowTypeOf(activeWindowId)
            activeIsTarget = true
        }
        return PageSnapshot(
            packageName = pkg,
            className = pendingClass.orEmpty(),
            activity = activities[pkg].orEmpty(),
            eventType = pendingEventType,
            windowId = activeWindowId,
            windowType = activeWindowType,
            activeWindow = activeIsTarget,
            eventTime = pendingEventTime,
            captureTime = System.currentTimeMillis(),
            sampleRound = sampleRound,
            nodes = nodes,
        )
    }

    /**
     * 可采集的窗口类型：应用窗口 + 系统窗口。
     * 系统窗口只在**根节点包名与目标应用一致**时才收（调用处已校验），
     * 所以状态栏/导航栏/输入法/本应用自己的悬浮卡都不会被当成目标应用页面；
     * 而目标应用自己添加的弹层窗口（部分支付确认层、密码键盘层）能被读到。
     */
    private fun isCollectableWindowType(type: Int): Boolean =
        type == AccessibilityWindowInfo.TYPE_APPLICATION ||
            type == AccessibilityWindowInfo.TYPE_SYSTEM

    private fun windowTypeOf(windowId: Int): Int {
        if (windowId < 0) return -1
        return runCatching {
            windows?.firstOrNull { it.id == windowId }?.type ?: -1
        }.getOrDefault(-1)
    }

    /**
     * 控件树遍历：保留层级（父节点下标）、控件 id、类名与文本/描述。
     * 有界（节点数 + 深度双上限），并防环（控件树在个别 ROM 上出现过自引用）。
     * [maxNodes]/[maxDepth] 供事件源的小预算读取复用（见 [MAX_SOURCE_NODES]）。
     */
    private fun collectNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        parentIndex: Int,
        out: MutableList<PageNode>,
        maxNodes: Int = MAX_NODES,
        maxDepth: Int = MAX_DEPTH,
    ) {
        if (node == null || depth > maxDepth || out.size >= maxNodes) return
        val index = out.size
        out.add(
            PageNode(
                text = node.text?.toString()?.trim().orEmpty(),
                contentDescription = node.contentDescription?.toString()?.trim().orEmpty(),
                viewId = shortViewId(node.viewIdResourceName),
                className = node.className?.toString().orEmpty(),
                depth = depth,
                parentIndex = parentIndex,
                windowId = node.windowId,
                notImportant = !node.isImportantForAccessibility,
            ),
        )
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (out.size >= maxNodes) break
            val child = node.getChild(i) ?: continue
            if (child == node) continue // 自引用防护
            collectNode(child, depth + 1, index, out, maxNodes, maxDepth)
        }
    }

    /** 控件 id 只保留 `:id/` 之后的部分（前缀是包名，占用空间且无信息量） */
    private fun shortViewId(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val slash = raw.lastIndexOf('/')
        return if (slash >= 0 && slash < raw.length - 1) raw.substring(slash + 1) else raw
    }
}
