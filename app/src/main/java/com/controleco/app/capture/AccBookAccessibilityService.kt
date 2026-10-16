package com.controleco.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.controleco.app.BuildConfig
import com.controleco.app.capture.page.PageNode
import com.controleco.app.capture.page.PageParserRegistry
import com.controleco.app.capture.page.PageSnapshot
import com.controleco.app.util.AppLog

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
 * 采集范围由运行时开关决定（配置里不限包名，便于调试采样）：
 * 已知交易来源（见 [PageParserRegistry]）∪ 用户监听的目录应用 ∪ 调试全量开关。
 *
 * 事件回调里只做"有界的控件树读取"（节点数/深度双上限），解析在采集中心的 IO 线程完成。
 */
class AccBookAccessibilityService : AccessibilityService() {

    companion object {
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

        /** 同内容重复上报的抑制窗口（只防事件风暴，不承担去重语义） */
        private const val FINGERPRINT_TTL_MS = 2_500L
        private const val FINGERPRINT_CAP = 32

        private const val EVENT_LOG_INTERVAL_MS = 2_000L

        fun serviceComponent(context: Context): ComponentName =
            ComponentName(context, AccBookAccessibilityService::class.java)

        /**
         * 系统无障碍设置中是否已开启本服务（安卓 13+ 侧载需先「允许受限制的设置」才出现在列表里）。
         * **双源检测**：AccessibilityManager 的已启用服务列表 + Settings.Secure 字符串。
         * 只读 Settings 会在系统回写短组件名（`pkg/.Class` 与 `pkg/full.Class` 两种形态）时误报"未开启"
         * （自测踩过的坑：单源读取在部分 ROM 上不稳定，见 docs/自动记账捕获机制.md）。
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
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 待采集的事件信息（事件风暴合并后只保留最后一条） */
    private var pendingPkg: String? = null
    private var pendingClass: String? = null
    private var pendingEventType: Int = 0
    private var pendingEventTime: Long = 0L
    private var collectScheduledAt = 0L

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureCenter.markA11yConnected(true)
        applyServiceInfo()
    }

    /**
     * 运行时收敛服务配置：这里再确认一遍三个采集标志确实生效（部分 ROM 会忽略 XML 里的部分位），
     * 失败只记日志，不影响服务运行。
     */
    private fun applyServiceInfo() {
        runCatching {
            val info = serviceInfo ?: return
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
            AppLog.i(
                "Page",
                "a11y service info applied flags=0x${Integer.toHexString(info.flags)} " +
                    "windows=${AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS and info.flags != 0} " +
                    "notImportant=${AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS and info.flags != 0} " +
                    "viewIds=${AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS and info.flags != 0}",
            )
        }.onFailure { AppLog.w("Page", "apply service info failed", it) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CaptureCenter.markA11yConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CaptureCenter.markA11yConnected(false)
        super.onDestroy()
    }

    override fun onInterrupt() {
        AppLog.w("Page", "a11y interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CaptureCenter.noteForegroundPackage(pkg)
            if (className.isNotEmpty()) activities[pkg] = className
        }

        if (pkg == packageName) return // 自己的页面不采
        if (!shouldCapture(pkg)) return

        val now = SystemClock.elapsedRealtime()
        if (now - (lastEventLogAt[pkg] ?: 0L) > EVENT_LOG_INTERVAL_MS) {
            lastEventLogAt[pkg] = now
            AppLog.i(
                "Page",
                "a11y event pkg=$pkg type=0x${Integer.toHexString(event.eventType)} " +
                    "cls=${className.substringAfterLast('.')}",
            )
        }

        pendingPkg = pkg
        pendingClass = className
        pendingEventType = event.eventType
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
     */
    private fun shouldCapture(pkg: String): Boolean {
        if (CaptureCenter.isDebugCaptureAllPages(BuildConfig.DEBUG)) return true
        if (!CaptureCenter.isCaptureEnabled()) return false
        if (!CaptureCenter.isA11yCaptureEnabled()) return false
        return CaptureAppCatalog.pageCaptureAllowed(this, pkg)
    }

    private fun scheduleCollect(delayMs: Long) {
        val at = SystemClock.elapsedRealtime() + delayMs
        // 已有更早的计划则不重复排程（事件风暴合并）
        if (collectScheduledAt != 0L && collectScheduledAt <= at) return
        collectScheduledAt = at
        handler.postDelayed({ collectCurrentPage() }, delayMs)
    }

    private fun collectCurrentPage() {
        collectScheduledAt = 0L
        val pkg = pendingPkg ?: return
        val snapshot = buildSnapshot(pkg)
        val contentHash = snapshot.fingerprint.hashCode()

        // 空树：窗口切换过渡期内容未就绪，限次补采
        if (snapshot.lines.isEmpty()) {
            if (emptyRetries < MAX_EMPTY_RETRIES) {
                emptyRetries++
                AppLog.i("Page", "collect empty pkg=$pkg retry#$emptyRetries")
                scheduleCollect(SAMPLE_INTERVAL_MS)
            } else {
                AppLog.i("Page", "collect empty pkg=$pkg give up（内容不可读或窗口未就绪）")
            }
            return
        }

        sampleRound++
        stableRounds = if (contentHash == lastContentHash) stableRounds + 1 else 0
        lastContentHash = contentHash

        val settled = stableRounds + 1 >= STABLE_ROUNDS_REQUIRED
        val roundsExhausted = sampleRound >= MAX_SAMPLE_ROUNDS
        if (settled || roundsExhausted) {
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
                "nodes=${snapshot.nodes.size} lines=${snapshot.lines.size} " +
                "texts=[${NotificationRules.maskCardTails(snapshot.lines.joinToString(" | ")).take(600)}]",
        )
        CaptureCenter.onPageCaptured(snapshot, BuildConfig.DEBUG)
    }

    /**
     * 采集当前页面为结构化快照：活动窗口优先，再补充同包名的其余窗口
     * （支付成功页可能是 dialog/非焦点窗口，不会成为 rootInActiveWindow）。
     */
    private fun buildSnapshot(pkg: String): PageSnapshot {
        val nodes = ArrayList<PageNode>(256)
        var activeWindowId = -1
        var activeWindowType = -1
        var activeIsTarget = false
        val root = rootInActiveWindow
        if (root != null) {
            activeWindowId = root.windowId
            activeWindowType = windowTypeOf(activeWindowId)
            activeIsTarget = root.packageName?.toString() == pkg
            if (activeIsTarget) collectNode(root, 0, -1, nodes)
        }
        // 同包名的其余窗口（活动窗口已采过则跳过，避免重复文本）
        if (nodes.size < MAX_NODES) {
            runCatching {
                for (window in windows.orEmpty()) {
                    if (nodes.size >= MAX_NODES) break
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    if (window.id == activeWindowId) continue
                    val windowRoot = window.root ?: continue
                    if (windowRoot.packageName?.toString() != pkg) continue
                    collectNode(windowRoot, 0, -1, nodes)
                }
            }.onFailure { AppLog.w("Page", "windows enumerate failed", it) }
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

    private fun windowTypeOf(windowId: Int): Int {
        if (windowId < 0) return -1
        return runCatching {
            windows?.firstOrNull { it.id == windowId }?.type ?: -1
        }.getOrDefault(-1)
    }

    /**
     * 控件树遍历：保留层级（父节点下标）、控件 id、类名与文本/描述。
     * 有界（节点数 + 深度双上限），并防环（控件树在个别 ROM 上出现过自引用）。
     */
    private fun collectNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        parentIndex: Int,
        out: MutableList<PageNode>,
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
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
            if (out.size >= MAX_NODES) break
            val child = node.getChild(i) ?: continue
            if (child == node) continue // 自引用防护
            collectNode(child, depth + 1, index, out)
        }
    }

    /** 控件 id 只保留 `:id/` 之后的部分（前缀是包名，占用空间且无信息量） */
    private fun shortViewId(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val slash = raw.lastIndexOf('/')
        return if (slash >= 0 && slash < raw.length - 1) raw.substring(slash + 1) else raw
    }
}
