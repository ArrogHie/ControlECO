package com.controleco.app.capture.page

/**
 * 支付工具类型：决定"这笔钱从哪出"，是账户匹配的输入。
 *
 * 与"来源应用"是两回事：微信支付既可能用零钱，也可能走某张银行卡——
 * 只按包名把来源判成"微信"会让账户推断在持卡支付场景下系统性出错。
 */
enum class InstrumentKind {
    /** 渠道自有钱包（微信零钱/零钱通、支付宝余额/余额宝） */
    CHANNEL_WALLET,

    /** 银行卡（储蓄卡/信用卡，可能带尾号） */
    BANK_CARD,

    /** 信用付产品（花呗/京东白条/美团月付/抖音月付…），记账上按独立账户处理 */
    CREDIT_PRODUCT,

    /** 其他支付工具（数字人民币钱包、Apple Pay…） */
    OTHER,
}

/**
 * 结构化支付工具。
 *
 * [channel] 是所属渠道（微信/支付宝/云闪付/京东…），[brand] 是品牌（银行名或产品名），
 * [tail] 是卡号后四位（**只留后四位**，不保存完整卡号）。
 */
data class PaymentInstrument(
    val raw: String,
    val kind: InstrumentKind,
    val channel: String = "",
    val brand: String = "",
    val tail: String = "",
    /** 可用于账户名匹配的候选关键词（品牌、产品名、渠道+产品） */
    val matchKeys: List<String> = emptyList(),
) {
    /** 展示名（备注/日志）：原始文本已足够可读，缺失时用拼装结果 */
    val label: String get() = raw.ifBlank { listOf(channel, brand, tail).filter { it.isNotBlank() }.joinToString("") }
}

/**
 * 支付方式文本 → 结构化支付工具（纯函数，便于单测）。
 *
 * 覆盖真实样本里的写法：
 * - 微信：`零钱` / `零钱通` / `招商银行(1234)` / `中国银行储蓄卡(8920)` / `工商银行信用卡`
 * - 支付宝：`余额宝` / `花呗` / `账户余额` / `中国银行储蓄卡(8920)` / `亲情卡`
 * - 云闪付：`付款卡 工商银行储蓄卡(1234)` / `卡号 尾号1234`
 * - 电商：`京东白条` / `美团月付` / `抖音月付` / `多多钱包`
 *
 * 判不出来返回 null（宁可不匹配账户，也不猜一个不存在的账户）。
 */
object PaymentInstrumentParser {

    /** 余额类文本 → 规范名（渠道自有钱包） */
    private val walletAliases: List<Pair<String, String>> = listOf(
        "零钱通" to "零钱通",
        "零钱" to "零钱",
        "微信钱包" to "零钱",
        "余额宝" to "余额宝",
        "账户余额" to "余额",
        "支付宝余额" to "余额",
        "余额" to "余额",
    )

    /** 信用付产品（含历史别名） */
    private val creditProducts: List<Pair<String, String>> = listOf(
        "花呗" to "花呗",
        "京东白条" to "京东白条",
        "白条" to "京东白条",
        "美团月付" to "美团月付",
        "月付" to "美团月付",
        "抖音月付" to "抖音月付",
        "信用购" to "信用购",
        "分付" to "微信分付",
    )

    /** 数字人民币/其他 */
    private val otherTools = listOf("数字人民币", "云闪付", "Apple Pay", "华为支付", "Huawei Pay")

    /** 银行名（识别"哪家银行"用；不含"银行"二字也能匹配，如"招行"） */
    private val bankNames = listOf(
        "工商银行", "建设银行", "农业银行", "中国银行", "交通银行", "邮储银行", "邮政储蓄",
        "招商银行", "中信银行", "光大银行", "民生银行", "浦发银行", "平安银行", "广发银行",
        "兴业银行", "华夏银行", "北京银行", "上海银行", "宁波银行", "江苏银行", "南京银行",
        "浙商银行", "恒丰银行", "渤海银行", "徽商银行", "汉口银行", "长沙银行", "成都银行",
        "东莞银行", "贵阳银行", "西安银行", "重庆银行", "农商银行", "农村商业银行", "村镇银行",
    )

    private val bankShortNames = mapOf(
        "工行" to "工商银行", "建行" to "建设银行", "农行" to "农业银行", "中行" to "中国银行",
        "交行" to "交通银行", "邮储" to "邮储银行", "招行" to "招商银行", "中信" to "中信银行",
        "光大" to "光大银行", "民生" to "民生银行", "浦发" to "浦发银行", "平安" to "平安银行",
        "广发" to "广发银行", "兴业" to "兴业银行", "华夏" to "华夏银行",
    )

    private val cardTailPattern = Regex("""(?:尾号|尾数|\*{2,})\s*(\d{3,4})""")
    private val parenTailPattern = Regex("""[(（]\s*(\d{3,4})\s*[)）]""")
    private val fourDigitsTail = Regex("""(?:^|\D)(\d{4})(?:\D|$)""")

    private val creditCardWords = listOf("信用卡", "贷记卡", "信用购")
    private val debitCardWords = listOf("储蓄卡", "借记卡", "银行卡", "银联卡", "卡")

    /**
     * 解析一段支付方式文本。[channel] = 渠道展示名（微信/支付宝/云闪付/京东…），
     * 用于给渠道自有钱包补全渠道含义（"零钱" → 微信零钱）。
     * 输入已经过调用方清洗（去标签、去"更改"按钮等）。
     */
    fun parse(raw: String, channel: String = ""): PaymentInstrument? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 明显不是支付工具的文本（"完成"按钮/一句提示）直接放弃
        if (text.length > 30) return null
        if (text in notInstrumentWords) return null

        val compact = text.replace(" ", "")

        // 1) 渠道自有钱包
        for ((alias, canonical) in walletAliases) {
            if (compact.contains(alias)) {
                return PaymentInstrument(
                    raw = text,
                    kind = InstrumentKind.CHANNEL_WALLET,
                    channel = channel,
                    brand = canonical,
                    matchKeys = listOfNotNull(canonical, if (channel.isNotBlank()) channel else null, channel + canonical).distinct(),
                )
            }
        }
        // 2) 信用付产品
        for ((alias, canonical) in creditProducts) {
            if (compact.contains(alias)) {
                return PaymentInstrument(
                    raw = text,
                    kind = InstrumentKind.CREDIT_PRODUCT,
                    channel = channel,
                    brand = canonical,
                    matchKeys = listOf(canonical, channel + canonical).filter { it.isNotBlank() }.distinct(),
                )
            }
        }
        // 3) 银行卡（银行名 + 可选卡种 + 可选尾号）
        val bank = bankNames.firstOrNull { compact.contains(it) } ?: bankShortNames.entries.firstOrNull { compact.contains(it.key) }?.value
        if (bank != null) {
            return PaymentInstrument(
                raw = text,
                kind = InstrumentKind.BANK_CARD,
                channel = channel,
                brand = bank,
                tail = tailOf(text),
                matchKeys = listOf(bank, if (channel.isNotBlank()) channel + bank else "").filter { it.isNotBlank() }.distinct(),
            )
        }
        // 4) 其他工具
        val other = otherTools.firstOrNull { compact.contains(it) }
        if (other != null) {
            return PaymentInstrument(
                raw = text,
                kind = InstrumentKind.OTHER,
                channel = channel,
                brand = other,
                matchKeys = listOf(other, channel).filter { it.isNotBlank() }.distinct(),
            )
        }
        // 5) 只有卡种/尾号（如 "储蓄卡(8920)"）：仍可用于账户尾号匹配
        val cardWord = (creditCardWords + debitCardWords).firstOrNull { compact.contains(it) }
        val tail = tailOf(text)
        if (cardWord != null || tail.isNotEmpty()) {
            return PaymentInstrument(
                raw = text,
                kind = InstrumentKind.BANK_CARD,
                channel = channel,
                brand = "",
                tail = tail,
                matchKeys = listOfNotNull(if (channel.isNotBlank()) channel else null).distinct(),
            )
        }
        return null
    }

    /** 卡号后四位（只取四位，不保存完整卡号）：优先"尾号1234"，其次括号数字，最后任意四位数 */
    fun tailOf(text: String): String {
        cardTailPattern.find(text)?.groupValues?.get(1)?.let { if (it.length == 4) return it }
        parenTailPattern.find(text)?.groupValues?.get(1)?.let { if (it.length == 4) return it }
        fourDigitsTail.find(text)?.groupValues?.get(1)?.takeIf { it.length == 4 }?.let { return it }
        return ""
    }

    /** 明显不是支付工具的文本（按钮/状态词），避免把导航节点当支付方式 */
    private val notInstrumentWords = setOf(
        "更改", "完成", "确定", "确认", "更多", "帮助", "详情", "查看", "展开", "收起",
        "选择支付方式", "改为", "切换", "免密支付", "查看规则", "关闭",
    )
}
