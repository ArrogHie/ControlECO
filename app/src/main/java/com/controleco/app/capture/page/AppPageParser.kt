package com.controleco.app.capture.page

/**
 * 应用专属页面解析器。
 *
 * 设计要点（与采集层的分工）：
 * - 无障碍服务只负责"把页面读成 [PageSnapshot]"与"在合适时机采样本"；
 * - 解析器负责"这张页面属于哪个应用、处于什么场景与状态、能提取哪些字段、是否允许生成交易候选"；
 * - 采集中心负责"候选与通知/短信候选的融合与入账"。
 *
 * 一个包名对应一个解析器（同一家的多个包名可共用一个解析器实例），
 * 由 [PageParserRegistry] 按包名分派；找不到专属解析器时才落到兜底解析器。
 */
interface AppPageParser {

    /** 本解析器负责的包名（含同系列变体，如极速版/直播版/外卖版） */
    val packages: Set<String>

    /** 展示名（日志与规则名用），如"微信" */
    val label: String

    /**
     * 解析一张页面快照。
     *
     * [flow] 是同一"支付流程"的短期上下文（跨页面累积、由 [PageFlowTracker] 维护）：
     * 收银台页记下的金额/商户/支付方式可在结果页缺失时补齐；结果页生成的候选会登记到 flow，
     * 同一页面的重复事件不会重复生成候选；用户重新走一遍支付（再次出现收银台）则会开启新流程。
     *
     * 实现约定：**不得抛异常**到调用方——异常会让整次采集失败；解析器内部对不可读/异常结构
     * 一律返回 [PageParseResult.NotATradePage] 或 [PageParseResult.SceneOnly]。
     */
    fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult
}

/** 页面解析结果 */
sealed interface PageParseResult {

    /** 与交易无关的页面（主界面/聊天/设置…） */
    data object NotATradePage : PageParseResult

    /**
     * 识别为交易流程里的页面，但不能（或不该）生成候选：
     * 支付确认页/处理中/失败/取消/列表页/待支付订单等，[reason] 进日志便于回溯。
     */
    data class SceneOnly(
        val scene: PageScene,
        val tradeState: TradeState,
        val reason: String,
    ) : PageParseResult

    /** 生成交易候选 */
    data class Candidate(val candidate: PageCandidate) : PageParseResult
}

/**
 * 一次支付流程的短期上下文（由 [PageFlowTracker] 维护，随流程结束/超时回收）。
 *
 * 为什么需要它：支付结果的字段分散在多张页面（收银台有金额与商户、结果页有最终状态、
 * 账单详情页有支付方式与单号），且同一张结果页会连续触发多次无障碍事件。
 * 但状态必须"按流程隔离"——上一笔的金额/商户绝不能污染下一笔。
 */
class PageFlow(val packageName: String, internal val startedAt: Long) {

    /** 当前流程状态 */
    var tradeState: TradeState = TradeState.UNKNOWN
        private set

    /** 最近一次识别的场景 */
    var scene: PageScene = PageScene.UNKNOWN
        private set

    /** 支付确认页（未完成）上看到的金额（分）：只作为上下文，绝不单独生成候选 */
    var confirmAmountCents: Long? = null
        private set

    /** 支付确认页上的商户（结果页缺失时补齐） */
    var confirmCounterparty: String? = null
        private set

    /** 支付确认页上的支付方式（结果页缺失时补齐） */
    var confirmInstrument: PaymentInstrument? = null
        private set

    /**
     * 支付确认时刻（墙钟）：成功页若没有交易时间，用它比"捕获时刻"更接近真实支付时间
     * （与参考实现"支付锚点"同一思路：只在同一天内使用）。
     */
    var anchorTime: Long? = null
        private set

    /** 最近一次成功页的支付工具（详情页缺失时可补） */
    var successInstrument: PaymentInstrument? = null
        private set

    /** 已生成过候选的"页面实例"指纹：同一张结果页的重复事件不再生成第二条候选 */
    private val emittedPages = LinkedHashSet<String>()

    /** 本流程已生成候选的次序（0 = 本分钟内第一笔，见采集中心的去重键分配） */
    var emitSeq: Int = 0
        private set

    internal var updatedAt: Long = startedAt

    val hasConfirmContext: Boolean
        get() = confirmAmountCents != null || confirmCounterparty != null || confirmInstrument != null

    /**
     * 记录"支付确认页/收银台页"：开启时间锚点。
     *
     * **再次进入收银台 = 用户又付了一笔**：若上一笔已经成功，这里先清空流程上下文再记录，
     * 保证上一笔的金额/商户/支付方式不会污染这一笔（也保证同额的第二笔能重新生成候选）。
     */
    fun noteConfirmPage(
        amountCents: Long?,
        counterparty: String?,
        instrument: PaymentInstrument?,
        atMillis: Long,
    ) {
        if (tradeState == TradeState.SUCCESS || tradeState == TradeState.REFUND) resetForNewFlow()
        tradeState = TradeState.PENDING
        scene = PageScene.PAYMENT_CONFIRM
        if (amountCents != null && amountCents > 0) confirmAmountCents = amountCents
        if (!counterparty.isNullOrBlank()) confirmCounterparty = counterparty
        if (instrument != null) confirmInstrument = instrument
        anchorTime = atMillis
    }

    /**
     * 新交易开始：清掉上一笔的累积信息与"已生成"标记。
     * 触发的典型时点：再次出现收银台/确认页（用户重新付了一笔）、上一笔已完成后又出现成功页。
     */
    fun resetForNewFlow() {
        tradeState = TradeState.UNKNOWN
        scene = PageScene.UNKNOWN
        confirmAmountCents = null
        confirmCounterparty = null
        confirmInstrument = null
        anchorTime = null
        successInstrument = null
        emittedPages.clear()
    }

    fun noteScene(scene: PageScene, state: TradeState) {
        this.scene = scene
        this.tradeState = state
    }

    fun noteSuccess(instrument: PaymentInstrument?) {
        tradeState = TradeState.SUCCESS
        if (instrument != null) successInstrument = instrument
    }

    /** 该页面实例是否已生成过候选 */
    fun hasEmitted(pageKey: String): Boolean = emittedPages.contains(pageKey)

    /**
     * 登记一次候选生成。返回本次生成的次序（0 = 本流程第一笔）。
     * 同一页面实例重复调用返回 -1（调用方据此跳过）。
     */
    fun markEmitted(pageKey: String): Int {
        if (!emittedPages.add(pageKey)) return -1
        val seq = emitSeq
        emitSeq += 1
        return seq
    }

    val pageKey: String get() = "$packageName|$startedAt"
}

/**
 * 支付流程上下文管理器：按包名维护一个短期 [PageFlow]，超时回收。
 *
 * 刻意做得很轻：只有"当前流程"一个槽位，随包名隔离，超时（默认 3 分钟）自动丢弃——
 * 不做全局常驻状态机，也不需要持久化（跨进程重启后重新开始即可）。
 */
class PageFlowTracker(
    private val ttlMs: Long = FLOW_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val flows = HashMap<String, PageFlow>()
    private val lock = Any()

    /** 取当前流程（不存在/已超时则新建） */
    fun flow(packageName: String): PageFlow = synchronized(lock) {
        val now = clock()
        val existing = flows[packageName]
        if (existing != null && now - existing.updatedAt <= ttlMs) {
            existing.updatedAt = now
            return existing
        }
        val fresh = PageFlow(packageName, now)
        flows[packageName] = fresh
        fresh
    }

    /** 只读查询（不新建）：给"当前是否处于某笔支付流程中"这类判断用 */
    fun peek(packageName: String): PageFlow? = synchronized(lock) {
        val now = clock()
        flows[packageName]?.takeIf { now - it.updatedAt <= ttlMs }
    }

    /** 开启新流程（用户重新走了一遍支付） */
    fun restart(packageName: String): PageFlow = synchronized(lock) {
        val now = clock()
        val fresh = PageFlow(packageName, now)
        flows[packageName] = fresh
        fresh
    }

    fun clear() = synchronized(lock) { flows.clear() }

    /** 回收超时流程（内存有界；由采集中心在处理每个快照时顺带调用） */
    fun evictExpired() = synchronized(lock) {
        val now = clock()
        flows.entries.removeAll { now - it.value.updatedAt > ttlMs }
    }

    companion object {
        /** 流程上下文存活时长：超过它视为上一笔已经结束（用户不再可能补采到同笔的其它页面） */
        const val FLOW_TTL_MS = 180_000L

        /** 进程内共享实例（采集中心与解析器共用一个） */
        val shared = PageFlowTracker()
    }
}
