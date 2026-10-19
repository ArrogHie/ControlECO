package com.controleco.app.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigDecimal

/**
 * 金额工具：内部一律以“分”（Long）存储，避免浮点误差。
 *
 * 货币符号可在个人页「本月预算」里调整（见 [symbol] / [CURRENCIES]），
 * 只改符号、不做换算；所有带符号的格式化都从这里取，别处不要再写死「¥」。
 */
object Money {

    /** 可选货币（符号 → 名称），保持常见、够用，不做汇率换算 */
    val CURRENCIES: List<Pair<String, String>> = listOf(
        "¥" to "人民币",
        "$" to "美元",
        "€" to "欧元",
        "JP¥" to "日元",
        "£" to "英镑",
        "₩" to "韩元",
        "₹" to "印度卢比",
        "HK$" to "港币",
        "NT$" to "新台币",
    )

    const val DEFAULT_SYMBOL = "¥"

    private var currentSymbol by mutableStateOf(DEFAULT_SYMBOL)

    /**
     * 当前货币符号。用 Compose 快照状态存：改符号后**所有显示金额的地方都会自动重画**，
     * 不用逐页刷新，也不会把符号变更限制在某一个页面里。
     */
    var symbol: String
        get() = currentSymbol
        set(value) {
            currentSymbol = value.ifBlank { DEFAULT_SYMBOL }
        }

    private val pattern = Regex("""^-?\d+(\.\d{1,2})?$""")

    /**
     * 解析金额文本为分。支持：¥/￥ 前缀、千分位逗号、正负号、前后空白。
     * 仅接受最多两位小数；非法输入返回 null。
     * 注意：逗号只在符合千分位格式（如 1,234.56）时移除，"1,2.3" 这类脏输入返回 null。
     */
    fun parseCents(text: String): Long? {
        var cleaned = text.replace("¥", "").replace("￥", "").replace(" ", "").trim()
        // 当前货币符号（$ / € / JP¥ …）也一并去掉，粘进来也能解析
        if (currentSymbol != DEFAULT_SYMBOL) cleaned = cleaned.replace(currentSymbol, "")
        if (cleaned.contains(',')) {
            val thousandSeparated = Regex("""^-?\d{1,3}(,\d{3})+(\.\d{1,2})?$""")
            cleaned = if (thousandSeparated.matches(cleaned)) cleaned.replace(",", "") else cleaned
        }
        if (cleaned.isEmpty() || !pattern.matches(cleaned)) return null
        return BigDecimal(cleaned).movePointRight(2).longValueExact()
    }

    /** 分 → "1234.56"（负数带 -，不含货币符号） */
    fun formatCents(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val whole = abs / 100
        val frac = (abs % 100).toString().padStart(2, '0')
        return (if (negative) "-" else "") + whole + "." + frac
    }

    /** 分 → "¥1234.56"（负数形如 -¥12.34） */
    fun formatYuan(cents: Long): String {
        val negative = cents < 0
        return (if (negative) "-" else "") + symbol + formatCents(if (negative) -cents else cents)
    }

    /**
     * 分 → "¥2,884"（整数金额不带小数、千分位分组；有小数时保留两位，如 ¥32.50）。
     * 首页/汇总等大金额展示用，短且易读。
     */
    fun formatYuanGrouped(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val frac = abs % 100
        val body = if (frac == 0L) grouped(abs) else grouped(abs) + "." + frac.toString().padStart(2, '0')
        return (if (negative) "-" else "") + symbol + body
    }

    /**
     * 分 → 紧凑金额（首页圆环等窄空间用）：进位规则统一为
     * **小数点前超过 4 位（不含 4 位）才升一档单位**，K（千）→ M（百万）→ B（十亿）。
     * 例：¥1,234.12 原样保留；¥32,312.32 → ¥32.31K；¥1,234,567.89 → ¥1234.57K；
     * ¥12,345,678.9 → ¥12.35M；¥12,345,678,901 → ¥12.35B。每档保留两位小数并去尾零。
     */
    fun formatYuanCompact(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val sign = if (negative) "-$symbol" else symbol
        var value = abs / 100.0
        if (value < 10_000.0) return formatYuanGrouped(cents)
        for (i in COMPACT_UNITS.indices) {
            value /= 1_000.0
            // 按最终显示文本判断：小数点前仍超过 4 位（含四舍五入进位，如 9,999,999.99→10000K）才继续升档
            val text = trimNumber(value)
            if (text.substringBefore('.').length <= 4 || i == COMPACT_UNITS.lastIndex) {
                return sign + text + COMPACT_UNITS[i]
            }
        }
        return formatYuanGrouped(cents)
    }

    /** 紧凑金额单位档位，逐级进位 */
    private val COMPACT_UNITS = listOf("K", "M", "B")

    /**
     * 分 → "¥3,480.00"（千分位分组 + 固定两位小数；账单页汇总大字/日期小计用）。
     * 与 [formatYuanGrouped] 的区别：整数金额也保留 ".00"，更像正式账单的读数。
     */
    fun formatYuanGroupedFixed(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val body = grouped(abs) + "." + (abs % 100).toString().padStart(2, '0')
        return (if (negative) "-" else "") + symbol + body
    }

    /**
     * 分 → "+12,000.00" / "-32.00"（显式正负号 + 千分位 + 固定两位小数，**不带**货币符号）。
     * 账单列表金额列用：页头已声明单位「人民币 · 元」，行内只留数字更清爽。
     */
    fun formatSignedPlain(cents: Long): String {
        val sign = if (cents < 0) "-" else "+"
        val abs = if (cents < 0) -cents else cents
        return sign + grouped(abs) + "." + (abs % 100).toString().padStart(2, '0')
    }

    /** 同 [formatSignedPlain]，但带上当前货币符号（账单行/日期小计，如 "-¥23.90"）。 */
    fun formatSignedCurrency(cents: Long): String {
        val sign = if (cents < 0) "-" else "+"
        val abs = if (cents < 0) -cents else cents
        return sign + symbol + grouped(abs) + "." + (abs % 100).toString().padStart(2, '0')
    }

    /**
     * 整数元部分的千分位分组（手写，入参为非负分）。
     *
     * 原先每次调用都 new 一个 `DecimalFormat("#,##0")`：列表每行每帧都要格式化金额（账单行/日期头/统计），
     * 构造并解析模式的开销在滚动时是实打实的卡顿来源；手写后只分配结果字符串，输出与 DecimalFormat 一致。
     */
    private fun grouped(absCents: Long): String {
        val yuan = (absCents / 100).toString()
        if (yuan.length <= 3) return yuan
        val sb = StringBuilder(yuan.length + (yuan.length - 1) / 3)
        val head = yuan.length % 3
        if (head > 0) sb.append(yuan, 0, head)
        var i = head
        while (i < yuan.length) {
            if (i > 0) sb.append(',')
            sb.append(yuan, i, i + 3)
            i += 3
        }
        return sb.toString()
    }

    /** 保留两位小数（四舍五入）并去除尾零：32.31232 → "32.31"，10.0 → "10" */
    private fun trimNumber(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
