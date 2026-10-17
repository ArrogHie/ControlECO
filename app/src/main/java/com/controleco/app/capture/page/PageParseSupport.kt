package com.controleco.app.capture.page

import com.controleco.app.util.Money
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 页面解析公共工具（纯函数，便于单测）。
 *
 * 三个专属解析器与兜底解析器共用这一套"字段提取原语"，保证各应用的口径一致：
 * 金额上下文分类（实付/订单总额/优惠/余额/运费/退款）、"标签→值"提取、商户兜底、
 * 交易时间解析、页面形状闸门（主界面/消息流/列表页不是结果页）。
 *
 * 这里刻意不放任何"某个应用独有"的判断——应用专属的场景识别写在各自的解析器里。
 */
object PageParseSupport {

    // ---------- 通用词表 ----------

    /** 收银台/支付确认页关键词：出现即说明这笔还没付完 */
    val cashierKeywords = listOf(
        "确认付款", "确认支付", "立即支付", "提交订单", "使用密码", "请输入支付密码", "请验证",
        "正在付款", "支付中", "处理中", "付款中", "安全支付中", "计算中", "银行处理中",
    )

    /** 失败短语：命中即拒绝生成候选 */
    val failureKeywords = listOf(
        "支付失败", "付款失败", "交易失败", "扣款失败", "付款未完成", "支付未完成", "扣款未成功",
    )

    /** 取消/关闭短语：命中即拒绝生成候选 */
    val cancelKeywords = listOf(
        "交易已取消", "交易已关闭", "订单已取消", "订单已关闭", "已撤销", "支付已取消",
        "订单已失效", "付款已取消", "交易关闭", "订单关闭", "超时未支付", "已过期",
    )

    /** 退款短语（退款是独立资金流，必须与消费支出分开处理） */
    val refundKeywords = listOf("退款成功", "退款到账", "已退款", "退款金额", "全额退款", "部分退款", "发起退款")

    /** 状态短语：提取"标签→值"时用于排除把状态行当商户/支付方式 */
    val statusPhrases = listOf(
        "对方已收钱", "已收钱", "已存入", "支付成功", "付款成功", "支付完成",
        "退款成功", "交易成功", "已支付", "已领取", "待收钱", "已退款", "已全额退款", "已完成",
        "等待付款", "待付款", "待支付", "未支付", "已取消", "已关闭",
    )

    /** 商户标签（具体在前，"对方"最宽泛放最后） */
    val counterpartyLabels = listOf(
        "商户全称", "商户名称", "收单商户", "商家全称", "收款方全称", "收款方", "收款人",
        "付款方", "商家", "商户", "对方",
    )

    /** 支付方式标签 */
    val paymentMethodLabels = listOf("支付方式", "付款方式", "交易方式", "扣款方式", "付款信息", "支付工具")

    /** 时间标签 */
    val timeLabels = listOf("交易时间", "付款时间", "支付时间", "创建时间", "订单时间", "转账时间", "扣款时间", "收款时间")

    /** 单号标签 */
    val orderIdLabels = listOf("交易单号", "订单编号", "商户订单号", "交易流水号", "流水号", "付款单号", "转账单号", "订单号")

    /** 消息中心用语：出现即说明这不是交易结果页 */
    private val messageStreamMarkers = listOf("最近消息", "条新消息", "消息盒子", "消息中心", "点击查看全部消息")

    /** 应用主界面入口词：同一页出现 ≥3 个即视为首页宫格 */
    private val appHomeMarkers = listOf(
        "扫一扫", "收付款", "卡包", "出行", "市民中心", "余额宝", "花呗", "蚂蚁森林", "我的快递", "转账",
    )

    /** 消息预览的相对时间戳：锚点附近出现这类字样 → 是消息流里的历史预览 */
    private val relativeTimeMarkers = listOf("刚刚", "秒前", "分钟前", "小时前", "昨天", "天前")

    /** 交易列表页特征：命中任一组合即视为列表页（不是单笔结果页） */
    private val listPageCombos = listOf(
        listOf("全部", "待付款", "待评价"), listOf("全部", "待收货", "退款"), listOf("筛选", "待付款", "已完成"),
        listOf("全部订单", "待评价", "退款"), listOf("全部账单", "收支统计"), listOf("账单", "收支统计", "查找交易"),
        listOf("交易状态", "收款方"), listOf("交易状态", "对方已收款"), listOf("筛选", "全部", "本月"),
    )

    /** 订单待支付页特征：不是已完成的交易 */
    private val orderPendingCombos = listOf(
        listOf("待付款", "取消订单"), listOf("等待付款", "取消订单"), listOf("去支付", "订单金额"),
        listOf("待支付", "支付剩余时间"), listOf("付款倒计时"),
    )

    val stepperNode = Regex("""^x\d+$""")

    // ---------- 金额原语 ----------

    /**
     * 纯金额节点（可带 ¥ 前缀/负号/千分位/小数/尾随"元"）：绝不能当商户名或标签值。
     * 真实样本：支付成功页把"￥"与"20.06"渲染成两个独立节点，"20.06" 被兜底扫描当成商户记进了账。
     */
    private val amountLikeNode = Regex("""^[-−－+]?\s*[￥¥]?\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?\s*元?$""")

    /** 行内的任意 ¥ 金额（含千分位） */
    private val anyAmount = Regex("""[￥¥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")

    /** 整数元标签（"共3件"/"6个积分"这类不是金额） */
    private val bareNumberWithYuan = Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元""")

    internal fun isAmountLikeNode(text: String): Boolean = amountLikeNode.matches(text.trim())

    /** 纯数字/单号样文本（标签值里要排除的信息） */
    fun isNumberLike(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.all { it.isDigit() || it == '.' || it == ',' || it == '-' || it == ' ' }
    }

    fun amountValuesIn(line: String): List<String> = anyAmount.findAll(line).map { it.groupValues[1] }.toList()

    /** 金额上下文类型 */
    enum class AmountKind { PAID, ORDER_TOTAL, DISCOUNT, BALANCE, FEE, REFUND, UNKNOWN }

    private data class AmountContext(val positive: List<String>, val negative: List<String>)

    private val paidWords = listOf(
        "实付款", "实付", "实际支付", "实际付款", "付款金额", "支付金额", "交易金额", "支出金额",
        "消费金额", "已支付", "已付", "实收金额", "收款金额", "收入金额", "转账金额", "入账金额",
        "订单实付", "需付款", "付款额",
    )
    private val orderTotalWords = listOf("订单金额", "商品总额", "总金额", "总额", "合计", "订单总额", "原价", "商品金额")
    private val discountWords = listOf("优惠", "立减", "折扣", "满减", "减免", "抵扣", "返现", "红包", "抵扣券", "省", "已减", "共减", "优惠券")
    private val balanceWords = listOf("余额", "可用", "剩余", "额度", "零钱通余额", "账户余额", "余额宝余额", "钱包余额")
    private val feeWords = listOf("运费", "配送费", "服务费", "手续费", "打包费", "包装费", "小费")
    private val refundWords = listOf("退款", "退回", "退还")

    /**
     * 扫出页面上所有金额节点并按上下文分类。
     * 判据顺序：明确标签词 → 负向上下文（优惠/余额/运费/退款）→ 未知。
     * 负向词优先于"金额"这类弱标签：真机上"账户余额¥1,234.56"与"实付¥12.34"同页很常见。
     */
    fun collectAmounts(lines: List<String>): List<AmountHit> {
        val hits = ArrayList<AmountHit>()
        for ((index, line) in lines.withIndex()) {
            val values = amountValuesIn(line)
            if (values.isEmpty()) continue
            for (value in values) {
                hits.add(AmountHit(value, classifyAmount(line), index))
            }
        }
        return hits
    }

    /** 单行金额的上下文分类（行内词表判定；"减/优惠"等负向词优先） */
    fun classifyAmount(line: String): AmountKind {
        val ctx = line.trim()
        if (discountWords.any { ctx.contains(it) } && !paidWords.any { ctx.contains(it) }) return AmountKind.DISCOUNT
        if (balanceWords.any { ctx.contains(it) }) return AmountKind.BALANCE
        if (feeWords.any { ctx.contains(it) } && !paidWords.any { ctx.contains(it) }) return AmountKind.FEE
        if (refundWords.any { ctx.contains(it) } && !paidWords.any { ctx.contains(it) }) return AmountKind.REFUND
        if (paidWords.any { ctx.contains(it) }) return AmountKind.PAID
        if (orderTotalWords.any { ctx.contains(it) }) return AmountKind.ORDER_TOTAL
        // 负号前缀（"-¥0.01"）是优惠项的写法
        val m = anyAmount.find(ctx)
        if (m != null && m.range.first > 0) {
            val prev = ctx[m.range.first - 1]
            if (prev == '-' || prev == '－' || prev == '−' || prev == '减' || prev == '惠') return AmountKind.DISCOUNT
        }
        return AmountKind.UNKNOWN
    }

    /** 带来源行号与上下文类型的金额 */
    data class AmountHit(val value: String, val kind: AmountKind, val lineIndex: Int)

    /**
     * 取"实付金额"：优先级 = 锚点节点内金额 → PAID 上下文 → 独立 ¥ 节点 → ORDER_TOTAL → UNKNOWN。
     * 页面同时出现订单原价与实付时永远取实付（这是"商品原价 vs 实付"误记的根因）。
     */
    fun pickPaidAmount(snapshot: PageSnapshot, anchorIndex: Int = -1): String? {
        if (anchorIndex in snapshot.lines.indices) {
            amountInAnchor(snapshot.lines[anchorIndex])?.let { return it }
        }
        val hits = collectAmounts(snapshot.lines)
        pickWithPriority(hits, AmountKind.PAID)?.let { return it.value }
        standaloneAmountNode(snapshot.lines)?.let { return it }
        pickWithPriority(hits, AmountKind.ORDER_TOTAL)?.let { return it.value }
        pickWithPriority(hits, AmountKind.UNKNOWN)?.let { return it.value }
        return null
    }

    /** 退款金额：只认明确标注退款的节点（退款页专用，不与实付混用） */
    fun pickRefundAmount(snapshot: PageSnapshot): String? {
        val hits = collectAmounts(snapshot.lines)
        return hits.firstOrNull { it.kind == AmountKind.REFUND }?.value
            ?: hits.firstOrNull { it.kind == AmountKind.UNKNOWN }?.value
    }

    private fun pickWithPriority(hits: List<AmountHit>, kind: AmountKind): AmountHit? =
        hits.firstOrNull { it.kind == kind && Money.parseCents(it.value)?.let { v -> v > 0 } == true }

    /** 独立金额节点（整行只有一个 ¥ 金额，成功页的大字金额） */
    private fun standaloneAmountNode(lines: List<String>): String? =
        lines.firstNotNullOfOrNull { line ->
            val t = line.trim()
            if (t.startsWith("¥") || t.startsWith("￥")) {
                val kind = classifyAmount(t)
                if (kind == AmountKind.PAID || kind == AmountKind.UNKNOWN) {
                    amountValuesIn(t).firstOrNull()
                } else null
            } else null
        }

    /** 锚点节点内的实付金额：跳过优惠项（"-￥0.01"/"减￥1"这类写法） */
    fun amountInAnchor(node: String): String? =
        anyAmount.findAll(node).firstOrNull { m ->
            val prev = if (m.range.first > 0) node[m.range.first - 1] else '　'
            prev != '-' && prev != '－' && prev != '−' && prev != '减' && prev != '惠'
        }?.groupValues?.get(1)

    /** 行内第一个"元"结尾的金额（无 ¥ 前缀的写法："支付成功 12.80元"） */
    fun yuanSuffixedAmount(line: String): String? = bareNumberWithYuan.find(line)?.groupValues?.get(1)

    // ---------- 时间 ----------

    private val timePattern = Regex("""(\d{4})[-/.年]\s*(\d{1,2})[-/.月]\s*(\d{1,2})日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /** 交易时间解析：2026-10-06 12:34[:56] / 2026.10.6 12:34 / 2026年10月6日 12:34 */
    fun timeFromLine(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val m = timePattern.find(text) ?: return null
        return runCatching {
            LocalDateTime.of(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                m.groupValues[6].ifEmpty { "0" }.toInt(),
            ).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** 页面上的交易时间：先按时间标签找，再全页兜底 */
    fun timeFromPage(snapshot: PageSnapshot): Long? {
        valueAfterLabel(snapshot.lines, timeLabels)?.let { timeFromLine(it)?.let { v -> return v } }
        for (line in snapshot.lines) {
            timeFromLine(line)?.let { return it }
        }
        return null
    }

    /** 账单详情页的可记账新鲜度：超过它说明用户在翻旧账，不能记成新账 */
    const val MAX_DETAIL_AGE_MS = 24 * 3_600_000L

    /**
     * 旧账单详情页判定（详情类的页面专用）：页面上的交易时间早于现在超过 [MAX_DETAIL_AGE_MS]
     * → 用户是在翻历史账单，不是刚发生的交易。**结果页（支付成功页）不需要这道闸门**：
     * 它的交易时间就是刚刚。时间缺失（null）时不判旧（宁可让后续的重复预检与去重键兜底）。
     */
    fun isStaleDetail(snapshot: PageSnapshot, txTime: Long?): Boolean =
        txTime != null && snapshot.captureTime - txTime > MAX_DETAIL_AGE_MS

    // ---------- 标签→值 / 商户 ----------

    private val fallbackExcluded = counterpartyLabels + paymentMethodLabels + timeLabels + orderIdLabels +
        listOf(
            "完成", "确认", "取消", "查看", "收起", "展开", "复制", "返回", "同意", "关闭", "继续", "知道了",
            "账单详情", "交易详情", "优惠", "红包", "金额", "实付", "首页", "客服", "更多", "帮助",
            "申请退款", "联系商家", "评价", "再来一单", "查看订单", "联系客服", "投诉",
        )

    /**
     * "标签→值"提取：同行值必须紧跟分隔符（冒号或空格）——
     * "点击给商家对账"这类标签嵌在长句里的节点不产生值；
     * 标签独占一个节点时取其后的第一个有效值节点。
     */
    fun valueAfterLabel(lines: List<String>, labels: List<String>): String? {
        for ((index, node) in lines.withIndex()) {
            for (label in labels) {
                val idx = node.indexOf(label)
                if (idx < 0) continue
                val after = node.substring(idx + label.length)
                val rest = when {
                    after.startsWith("：") || after.startsWith(":") -> after.substring(1).trim()
                    after.startsWith(" ") || after.startsWith("　") -> after.trimStart(' ', '　').trim()
                    else -> null
                }
                if (rest != null && isUsableValue(rest, labels)) return rest.take(40)
                if (node.trim() == label) {
                    for (j in index + 1 until lines.size) {
                        val next = lines[j]
                        if (isUsableValue(next, labels)) return next.take(40)
                    }
                }
            }
        }
        return null
    }

    fun isUsableValue(text: String, labels: List<String>): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (statusPhrases.any { t.contains(it) }) return false
        if (labels.any { t == it }) return false
        if (isNumberLike(t)) return false
        if (isAmountLikeNode(t)) return false
        return true
    }

    /**
     * "金额标签 → 金额值"提取：标签同行/下一行的金额节点或行内金额。
     * 与 [valueAfterLabel] 分开是必要的——金额本身会被 [isUsableValue] 当作"非法文本值"排除，
     * 用文本标签提取器取金额会顺延到"收款方"这类下一行文本上（自测发现的误取值）。
     */
    fun amountAfterLabel(lines: List<String>, labels: List<String>): String? {
        for ((index, node) in lines.withIndex()) {
            for (label in labels) {
                val idx = node.indexOf(label)
                if (idx < 0) continue
                // 同行：标签后直接跟金额（"交易金额：¥58.00" / "金额 ¥58.00"）
                amountValuesIn(node).firstOrNull()?.let { return it }
                yuanSuffixedAmount(node)?.let { return it }
                if (node.trim() != label) continue
                // 标签独占一行：向后最多看 2 行（金额常紧跟，偶尔隔一个装饰节点）
                for (j in index + 1 until minOf(lines.size, index + 3)) {
                    val next = lines[j]
                    amountValuesIn(next).firstOrNull()?.let { return it }
                    if (isAmountLikeNode(next)) {
                        val cleaned = next.trim().trimStart('¥', '￥', '+', '-', ' ').trimEnd('元').trim()
                        if (Money.parseCents(cleaned) != null) return cleaned
                    }
                }
            }
        }
        return null
    }

    /**
     * 结构化版本的"标签 → 金额"：除了文本相邻，还利用控件层级找**同一父节点下的后续兄弟**。
     *
     * 真实页面里标签与金额经常是同一容器的两个子节点（`付款金额` + `¥12.80`），
     * 中间可能夹着图标/说明节点；只按扁平文本的下一行找，会在多一列布局时取错。
     * 找不到时回落到文本版本，不改变既有行为。
     */
    fun amountAfterLabel(snapshot: PageSnapshot, labels: List<String>): String? {
        val nodes = snapshot.nodes
        for (i in nodes.indices) {
            val node = nodes[i]
            val display = node.display
            if (display.isEmpty()) continue
            val label = labels.firstOrNull { display.contains(it) } ?: continue
            val pos = display.indexOf(label)
            val same = display.substring(pos + label.length).trim('：', ':', ' ', '\u00A0')
            extractAmountToken(same)?.let { return it }
            val parent = node.parentIndex
            if (parent >= 0) {
                var checked = 0
                for (j in i + 1 until nodes.size) {
                    val sibling = nodes[j]
                    if (sibling.parentIndex != parent) continue
                    checked++
                    val text = sibling.display
                    if (text.isEmpty()) continue
                    if (labels.any { text.contains(it) }) break
                    extractAmountToken(text)?.let { return it }
                    if (checked >= 3) break
                }
            }
        }
        return amountAfterLabel(snapshot.lines, labels)
    }

    /** 从一段文本里取金额数值文本（去掉 ¥/元，保持与文本版一致） */
    private fun extractAmountToken(text: String): String? {
        if (text.isBlank()) return null
        amountValuesIn(text).firstOrNull()?.let { return it }
        yuanSuffixedAmount(text)?.let { return it }
        val cleaned = text.trim().trimStart('¥', '￥', '+', '-', ' ').trimEnd('元').trim()
        return cleaned.takeIf { isAmountLikeNode(it) }?.trimStart('¥', '￥')
    }

    /**
     * 无标签商户兜底：从锚点节点向后扫最多 5 个节点，取第一个"像名称"的文本。
     * 排除金额/状态/按钮/标签/其他标签的值节点。
     */
    fun guessCounterparty(lines: List<String>, anchorCandidates: List<String>): String? {
        val anchorIdx = lines.indexOfFirst { line -> anchorCandidates.any { line.contains(it) } }
        if (anchorIdx < 0) return null
        val allLabels = counterpartyLabels + paymentMethodLabels + timeLabels
        val end = minOf(lines.size, anchorIdx + 6)
        for (i in anchorIdx + 1 until end) {
            val t = lines[i]
            if (t.isEmpty()) continue
            if (t.startsWith("￥") || t.startsWith("¥")) continue
            if (isAmountLikeNode(t)) continue
            if (isNumberLike(t)) continue
            if (stepperNode.matches(t)) continue
            if (statusPhrases.any { t.contains(it) }) continue
            if (fallbackExcluded.any { t.contains(it) }) continue
            if (i > 0 && allLabels.any { lines[i - 1] == it }) continue
            return t.take(30)
        }
        return null
    }

    /** 商户是否"像名称"：非空、非金额、非状态、非导航词 */
    fun looksLikeMerchant(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 40) return false
        if (isAmountLikeNode(t) || isNumberLike(t)) return false
        if (statusPhrases.any { t.contains(it) }) return false
        if (fallbackExcluded.any { t == it }) return false
        return true
    }

    // ---------- 支付方式 ----------

    private val paymentMethodNoise = listOf("更改", "按钮", "单指双击即可执行", "已选择", "点击", "＞", ">")

    /**
     * 支付方式清洗：剥掉"付款方式，已选择 零钱 更改"这类无障碍合并文本里的噪声，
     * 得到"零钱"/"招商银行(1234)"这样的工具名。
     */
    fun cleanPaymentMethod(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        for (noise in paymentMethodNoise) s = s.replace(noise, "")
        for (label in paymentMethodLabels) s = s.replace(label, "")
        s = s.trim().trim('：', ':', '，', ',', ' ', '·').trim()
        return s.ifEmpty { null }?.take(30)
    }

    /** 订单/交易单号：连续 6 位以上的字母数字组合 */
    private val orderIdPattern = Regex("""([A-Za-z0-9]{6,40})""")

    fun orderIdFrom(lines: List<String>): String? {
        for ((index, line) in lines.withIndex()) {
            for (label in orderIdLabels) {
                if (!line.contains(label)) continue
                // 同行值
                val after = line.substringAfter(label, "").trim('：', ':', ' ', '，')
                if (after.length >= 6) orderIdPattern.find(after)?.groupValues?.get(1)?.let { return it }
                // 下一行值
                val next = lines.getOrNull(index + 1)?.trim().orEmpty()
                if (next.length in 6..40) orderIdPattern.find(next)?.groupValues?.get(1)?.let { return it }
            }
        }
        return null
    }

    // ---------- 页面形状闸门 ----------

    /**
     * 页面形状闸门：拒掉"应用主界面 / 消息流 / 列表页"里的伪锚点（含历史账单预览）。
     * 返回 null = 形状像真正的交易结果页；否则返回拒绝原因（进日志）。
     */
    fun nonResultPageReason(snapshot: PageSnapshot, anchorIndex: Int): String? {
        val nodes = snapshot.lines
        val joined = snapshot.joined
        if (messageStreamMarkers.any { joined.contains(it) }) return "message center surface"
        if (appHomeMarkers.count { joined.contains(it) } >= 3) return "app home surface"
        if (listPageCombos.any { combo -> combo.all { joined.contains(it) } }) return "transaction list surface"
        if (anchorIndex >= 0) {
            val lo = maxOf(0, anchorIndex - 2)
            val hi = minOf(nodes.size, anchorIndex + 3)
            if ((lo until hi).any { i -> relativeTimeMarkers.any { nodes[i].contains(it) } }) {
                return "anchor looks like message preview"
            }
            if (nodes.size >= 30 && anchorIndex > 8) return "rich page with buried anchor"
        }
        return null
    }

    /**
     * 锚点行是否**本身就是**状态标题（允许尾随 ¥ 金额与标点）：
     * 结果页的"支付成功"是大标题（"支付成功"/"支付成功¥19.38"）；
     * 订单进度条的"提交订单 支付成功 商家接单"是长句，去掉金额仍是长句。
     */
    fun isStatusTitleNode(line: String, keywords: Collection<String>): Boolean {
        val stripped = anyAmount.replace(line, "").trim().trimEnd('，', '。', '！', '!', '、', '：', ':', ' ')
        return keywords.any { stripped == it }
    }

    /** 待支付页面（未完成的订单）判据 */
    fun looksLikeOrderPendingPage(snapshot: PageSnapshot): Boolean =
        orderPendingCombos.any { combo -> combo.all { snapshot.joined.contains(it) } } ||
            (snapshot.joined.contains("待付款") && snapshot.joined.contains("取消订单"))

    /** 是否像交易列表/明细列表（不是单笔结果页） */
    fun looksLikeListPage(snapshot: PageSnapshot): Boolean =
        listPageCombos.any { combo -> combo.all { snapshot.joined.contains(it) } }
}
