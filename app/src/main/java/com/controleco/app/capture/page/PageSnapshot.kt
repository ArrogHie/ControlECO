package com.controleco.app.capture.page

import kotlin.math.abs

/**
 * 页面快照模型：无障碍服务采集到的一次页面读取结果。
 *
 * 为什么不是"一坨扁平字符串"：支付结果页的字段提取依赖**节点之间的相对位置**
 * （"支付成功"标题节点的下一个节点是商户、金额标签的下一个节点是金额…），
 * 同时部分应用（改版后）用控件 id 定位比用文本更稳。所以快照同时保留
 * 有序文本序列与结构化节点（id/层级/窗口），解析器按需取用。
 *
 * 纯数据、无 Android 依赖：无障碍服务负责把 AccessibilityNodeInfo 树转成 [PageNode] 列表，
 * 解析器与测试都只面对这个模型。
 */
data class PageNode(
    /** 控件文本（node.text） */
    val text: String = "",
    /** 无障碍描述（node.contentDescription）：图标按钮的语义常在这里 */
    val contentDescription: String = "",
    /** 控件 id 的资源名，已截去 `包名:id/` 前缀；未开启 flagReportViewIds 时为空 */
    val viewId: String = "",
    /** 控件类名（TextView / Button / android.webkit.WebView …） */
    val className: String = "",
    /** 在控件树中的深度（根为 0） */
    val depth: Int = 0,
    /** 父节点在快照 [nodes] 列表里的下标；-1 = 根 */
    val parentIndex: Int = -1,
    /** 所属窗口 id（多窗口采集时用；单窗口为 -1） */
    val windowId: Int = -1,
    /** 该节点是否标记为不重要视图（flagIncludeNotImportantViews 采集到的"隐藏"节点） */
    val notImportant: Boolean = false,
) {
    /** 展示文本：text 优先，为空时退回 contentDescription（图标按钮只有后者） */
    val display: String
        get() = text.trim().ifEmpty { contentDescription.trim() }

    val isEmpty: Boolean get() = display.isEmpty()
}

/**
 * 一次页面读取的完整结果。
 *
 * [className] 是事件里的控件/活动类名，[activity] 是该应用最近一次窗口切换事件里的活动名——
 * 支付结果的"确认页 / 成功页 / 详情页"在不同应用里由不同活动承载，是分派与状态判断的第一手线索。
 */
data class PageSnapshot(
    val packageName: String,
    /** 触发本次采集的事件类名（活动名或控件类名） */
    val className: String = "",
    /** 该应用最近一次窗口切换事件里的活动名（同包名窗口跟踪得到） */
    val activity: String = "",
    /** 触发采集的事件类型（AccessibilityEvent 常量；采样补采时为上一次事件类型） */
    val eventType: Int = 0,
    /** 采集时的活动窗口 id */
    val windowId: Int = -1,
    /** 活动窗口类型（AccessibilityWindowInfo 常量） */
    val windowType: Int = -1,
    /** 活动窗口是否属于本应用（弹层/系统键盘时为 false） */
    val activeWindow: Boolean = true,
    /** 触发采集的事件时间（系统 since boot 时钟，仅用于间距判断） */
    val eventTime: Long = 0L,
    /** 本次采集的墙钟时刻（入账时间兜底） */
    val captureTime: Long = 0L,
    /** 采集轮次：0 = 事件触发的首采，1.. = 稳定性补采（同一轮 flow 内递增） */
    val sampleRound: Int = 0,
    val nodes: List<PageNode> = emptyList(),
) {

    /**
     * 有文本的节点序列（文档顺序，去连续重复）——解析器的主体输入。
     *
     * 派生字段一律在构造时算好（不是 lazy）：快照在无障碍采集线程创建、在采集中心的 IO 线程解析，
     * 跨线程共享的不可变值不能依赖延迟初始化。
     */
    val lines: List<String> = buildList(nodes.size) {
        for (node in nodes) {
            val text = node.display
            if (text.isEmpty()) continue
            if (isNotEmpty() && last() == text) continue
            add(text)
        }
    }

    /** 整页文本（标签匹配/关键词判定用；字段提取仍应在 [lines] 上做位置运算） */
    val joined: String = lines.joinToString(" ")

    /** 页面内容指纹：文本序列 + 活动名 + 窗口。内容相同 = 同一张页面（不含金额语义） */
    val fingerprint: String = "$packageName|$activity|$windowId|${lines.size}|${lines.joinToString("\u0001").hashCode()}"

    /** 采集到的控件 id 集合（已截前缀）；未开启 flagReportViewIds 时为空集 */
    val viewIds: Set<String> = nodes.asSequence().map { it.viewId }.filter { it.isNotEmpty() }.toSet()

    fun contains(anyOf: Collection<String>): Boolean = anyOf.any { joined.contains(it) }

    fun contains(text: String): Boolean = joined.contains(text)

    fun containsAll(allOf: Collection<String>): Boolean = allOf.all { joined.contains(it) }

    fun lineIndex(predicate: (String) -> Boolean): Int = lines.indexOfFirst(predicate)

    fun firstLineContains(needle: String): Int = lines.indexOfFirst { it.contains(needle) }

    /** 指定窗口的文本序列（多窗口时区分弹层与本应用的页面主体） */
    fun linesOfWindow(windowId: Int): List<String> =
        nodes.asSequence()
            .filter { it.windowId == windowId }
            .map { it.display }
            .filter { it.isNotEmpty() }
            .toList()

    fun hasViewId(suffix: String): Boolean = viewIds.any { it == suffix || it.endsWith(":$suffix") }

    /** 节点下标：按控件 id 后缀找（id 命中时比文本命中更稳） */
    fun nodeIndexByViewId(suffix: String): Int =
        nodes.indexOfFirst { it.viewId.isNotEmpty() && (it.viewId == suffix || it.viewId.endsWith(":$suffix")) }

    /**
     * 标签值提取的结构化版本：命中标签的节点在 [lines] 中的下标 ±1 取值。
     * 需要跳到"同一父节点下的兄弟"时用 [siblingLineAfter]。
     */
    fun siblingLineAfter(lineIndex: Int): String? =
        if (lineIndex in 0 until lines.size - 1) lines[lineIndex + 1] else null

    /** 金额是否出现在该行（含 ¥/￥ 前缀或"元"后缀的裸数字） */
    fun isAmountAt(lineIndex: Int): Boolean =
        lineIndex in lines.indices && PageParseSupport.isAmountLikeNode(lines[lineIndex])

    /**
     * 页面上是否存在"进度/列表"特征：金额与状态词在较长文本里连续出现（列表行/进度条），
     * 结果页的金额与状态总是短标题式的独立节点。给各应用的"列表页排除"用。
     */
    fun looksLikeListRow(lineIndex: Int): Boolean =
        lineIndex in lines.indices && lines[lineIndex].length > 24
}

/** 两个时刻是否在 [windowMs] 内（页面流程聚类的公共判定） */
internal fun withinWindow(a: Long, b: Long, windowMs: Long): Boolean = abs(a - b) <= windowMs
