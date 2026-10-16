package com.controleco.app.capture.page

import com.controleco.app.data.db.AccountEntity

/**
 * 支付工具 → 记账账户的匹配（纯函数，便于单测）。
 *
 * 解决的问题：以前只知道"这笔从微信付的"，于是所有微信支付都记到"微信"账户；
 * 实际上微信支付可能走某张银行卡（`招商银行(1234)`），记到"微信"会让账户余额与对账全错。
 *
 * 匹配顺序（命中即停，**匹配不到就返回 null，绝不猜一个不存在的账户**）：
 * 1. 账户名与支付工具名精确相等；
 * 2. 卡尾号唯一命中（账户名里含四位尾号）；
 * 3. 银行名 + 尾号同时命中；
 * 4. 银行名/产品名（标准化后）包含匹配；
 * 5. 渠道名匹配（零钱 → 名字含"微信"的账户；余额宝/花呗 → 名字含"支付宝"的账户）。
 *
 * 匹配依据只有"结构化后的支付工具"，绝不把备注里的支付方式文本当成已匹配账户。
 */
object PaymentAccountResolver {

    /** 匹配原因（写入日志/溯源，便于"为什么这笔记到了这个账户"） */
    enum class MatchReason(val label: String) {
        EXACT_NAME("账户名精确"),
        CARD_TAIL("卡尾号"),
        BANK_AND_TAIL("银行+尾号"),
        BANK_NAME("银行名"),
        PRODUCT_NAME("产品名"),
        CHANNEL("渠道"),
        NONE("未匹配"),
    }

    data class Match(val accountId: Long, val accountName: String, val reason: MatchReason)

    /** 渠道名 → 账户名里可能出现的关键词 */
    private val channelAccountKeywords = mapOf(
        "微信" to listOf("微信", "零钱"),
        "支付宝" to listOf("支付宝", "余额宝", "花呗"),
        "云闪付" to listOf("云闪付", "银联"),
        "京东" to listOf("京东"),
        "美团" to listOf("美团"),
        "抖音" to listOf("抖音"),
        "淘宝" to listOf("淘宝", "支付宝"),
    )

    /**
     * 在用户账户列表里为支付工具找账户。
     * [accounts] 为空时返回 null（调用方自行决定回落策略）。
     */
    fun resolve(
        accounts: List<AccountEntity>,
        instrument: PaymentInstrument?,
        channelLabel: String = "",
    ): Match? {
        if (accounts.isEmpty()) return null
        if (instrument == null) return resolveByChannel(accounts, channelLabel)

        val candidates = instrument.matchKeys + instrument.brand + instrument.raw
        val compactNames = candidates.map { normalizeAccountName(it) }.filter { it.length >= 2 }

        // 1) 卡尾号唯一命中：比"账户名精确"更精确——同名银行账户有多张卡时，
        //    尾号是唯一能区分"哪张卡"的信息（"招商银行(1234)" vs "招商银行(5678)"）
        if (instrument.tail.isNotEmpty()) {
            val tailHits = accounts.filter { accountTails(it.name).contains(instrument.tail) }
            if (tailHits.size == 1) {
                return Match(tailHits.first().id, tailHits.first().name, MatchReason.CARD_TAIL)
            }
            // 2) 银行名 + 尾号（账户名里同时写了银行名与尾号）
            if (instrument.brand.isNotEmpty()) {
                val both = tailHits.filter { normalizeAccountName(it.name).contains(normalizeAccountName(instrument.brand)) }
                if (both.size == 1) return Match(both.first().id, both.first().name, MatchReason.BANK_AND_TAIL)
            }
        }

        // 3) 账户名精确相等
        accounts.firstOrNull { account -> compactNames.any { normalizeAccountName(account.name) == it } }
            ?.let { return Match(it.id, it.name, MatchReason.EXACT_NAME) }

        // 4) 银行名/产品名包含匹配（唯一命中才算，多命中说明账户命名有歧义，交给渠道兜底）
        val brand = normalizeAccountName(instrument.brand)
        if (brand.length >= 2) {
            val hits = accounts.filter { normalizeAccountName(it.name).contains(brand) }
            if (hits.size == 1) {
                val reason = if (instrument.kind == InstrumentKind.BANK_CARD) MatchReason.BANK_NAME else MatchReason.PRODUCT_NAME
                return Match(hits.first().id, hits.first().name, reason)
            }
        }

        // 5) 渠道兜底（零钱 → 微信账户）
        return resolveByChannel(accounts, channelLabel.ifBlank { instrument.channel })
    }

    /** 按渠道名找账户（零钱/余额宝这类渠道自有钱包，以及无法精确匹配的银行卡场景） */
    fun resolveByChannel(accounts: List<AccountEntity>, channelLabel: String): Match? {
        if (channelLabel.isBlank()) return null
        val keywords = channelAccountKeywords[channelLabel] ?: listOf(channelLabel)
        for (keyword in keywords) {
            val hit = accounts.firstOrNull { it.name.contains(keyword, ignoreCase = true) } ?: continue
            return Match(hit.id, hit.name, MatchReason.CHANNEL)
        }
        return null
    }

    /** 账户名里出现的四位卡尾号（"招商银行(1234)" / "招行 1234" / "尾号1234"） */
    internal fun accountTails(name: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (m in Regex("""\d{4}""").findAll(name)) out.add(m.value)
        return out
    }

    /** 账户名标准化：去空格、去括号内卡号以外的噪声、常见简称归一 */
    internal fun normalizeAccountName(raw: String): String {
        var s = raw.trim().replace(" ", "").replace("　", "")
        s = s.replace(Regex("""[(（]?\d{3,}[(）)]?"""), "")
        s = s.replace(Regex("""尾号\d+"""), "")
        s = s.replace(Regex("""(储蓄卡|借记卡|信用卡|贷记卡|银行卡|卡号|尾号|卡)"""), "")
        val aliases = listOf(
            "招行" to "招商银行", "工行" to "工商银行", "建行" to "建设银行", "农行" to "农业银行",
            "中行" to "中国银行", "交行" to "交通银行", "邮储" to "邮储银行",
        )
        for ((short, full) in aliases) {
            if (s == short || s.startsWith(short)) s = s.replaceFirst(short, full)
        }
        return s
    }
}
