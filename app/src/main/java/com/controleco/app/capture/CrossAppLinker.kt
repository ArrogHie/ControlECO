package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 跨应用支付链路关联（纯逻辑，便于单测）。
 *
 * 真实付款经常横跨两个应用：电商/外卖页面负责订单与商户，微信/支付宝负责扣款，
 * 银行短信/推送负责卡侧动账。三者的包名不同，不能只靠"同包名"融合。
 *
 * 但"时间接近 + 金额相同"本身**不是**同一笔的证据（同额两笔、同分钟两笔都可能误并）。
 * 只有页面明确给出了可核对的支付渠道/银行卡线索时，才允许跨应用关联：
 *
 * - 页面支付方式含"微信/零钱/分付" ⇄ 微信通知；
 * - 页面支付方式含"支付宝/花呗/余额宝" ⇄ 支付宝通知；
 * - 页面支付方式含对应银行名（可带卡尾号）⇄ 银行短信/银行 App 推送；
 * - 订单号相同（双方都能解析出时）是最高优先级证据。
 *
 * 充值/提现/转账/还款等资金操作不参与跨应用关联（它们与消费不是同一业务事件）。
 * 关联条件不足时保持独立记录——宁可多一条待确认，也不把两笔真实交易并成一笔。
 */
internal object CrossAppLinker {

    /** 页面与通知/银行侧的关联窗口：银行侧到达延迟通常大于支付通知 */
    const val WINDOW_MS = 130_000L

    /** 统一证据视图（页面候选或既有条目都能映射进来） */
    data class Evidence(
        val sourcePackage: String,
        val amountCents: Long,
        val type: TxType,
        val isRefund: Boolean,
        val time: Long,
        val counterparty: String = "",
        val instrument: String = "",
        val note: String = "",
        val ruleName: String = "",
        val orderId: String = "",
    ) {
        val isCheckoutPage: Boolean
            get() = sourcePackage in NotificationRules.checkoutPackages
    }

    /**
     * 页面侧与另一侧（支付通知/银行动账）是否描述同一笔消费。
     * [page] 必须是电商/外卖页面；[other] 是支付类或银行类来源。
     */
    fun isSamePurchase(page: Evidence, other: Evidence): Boolean {
        if (!page.isCheckoutPage) return false
        if (page.sourcePackage == other.sourcePackage) return false
        if (page.amountCents != other.amountCents) return false
        if (page.type != other.type) return false
        if (page.isRefund != other.isRefund) return false
        if (abs(page.time - other.time) > WINDOW_MS) return false
        if (isMoneyMovement(page) || isMoneyMovement(other)) return false

        val orderLink = page.orderId.isNotBlank() && other.orderId.isNotBlank() &&
            page.orderId == other.orderId
        val channel = CrossChannelDedup.channelClass(other.sourcePackage)
        val channelLink = when (channel) {
            CrossChannelDedup.ChannelClass.PAY -> paymentChannelMatches(other.sourcePackage, page)
            CrossChannelDedup.ChannelClass.BANK -> bankChannelMatches(other, page)
            CrossChannelDedup.ChannelClass.UNKNOWN -> false
        }
        if (!orderLink && !channelLink) return false

        // 两侧都有商户名时至少一方包含另一方（收单名/门店名可能是包含关系）
        val a = normalizeMerchant(page.counterparty)
        val b = normalizeMerchant(other.counterparty)
        if (a.isNotEmpty() && b.isNotEmpty() && !a.contains(b) && !b.contains(a)) return false
        return true
    }

    /** 页面披露的支付方式是否指向该支付渠道（微信/支付宝） */
    fun paymentChannelMatches(sourcePackage: String, page: Evidence): Boolean {
        val text = (page.instrument + " " + page.note + " " + page.ruleName).trim()
        if (text.isBlank()) return false
        return when (sourcePackage) {
            NotificationRules.WECHAT ->
                text.contains("微信") || text.contains("零钱") || text.contains("分付")
            NotificationRules.ALIPAY ->
                text.contains("支付宝") || text.contains("花呗") || text.contains("余额宝")
            else -> false
        }
    }

    /**
     * 银行侧与页面支付方式是否指向同一张卡/同一家银行：
     * 页面能给出银行名时按银行名匹配；双方都有卡尾号时尾号必须一致（不一致直接否定）。
     */
    fun bankChannelMatches(bankSide: Evidence, page: Evidence): Boolean {
        val bankName = bankNameOf(bankSide)
        val pageMoney = PaymentInstrumentSnapshot.of(page.instrument, page.note)
        if (bankName != null && pageMoney.bankName != null && bankName != pageMoney.bankName) return false
        val bankTail = bankSide.instrument.tailOfMaybe()
        if (bankTail != null && pageMoney.tail != null && bankTail != pageMoney.tail) return false
        val nameMatch = bankName != null && pageMoney.bankName == bankName
        val tailMatch = bankTail != null && pageMoney.tail == bankTail
        val unionPayMatch = bankName == "云闪付" && page.instrument.contains("云闪付")
        return nameMatch || tailMatch || unionPayMatch
    }

    fun bankNameOf(evidence: Evidence): String? {
        val source = evidence.sourcePackage
        if (source.startsWith(NotificationRules.SMS_SOURCE_PREFIX)) {
            val fromKey = source.removePrefix(NotificationRules.SMS_SOURCE_PREFIX)
            if (fromKey.any { !it.isDigit() }) return fromKey
        }
        BankLedgerRules.bankNameOfPackage(source)?.let { return it }
        BankLedgerRules.detectBankName(evidence.ruleName)?.let { return it }
        BankLedgerRules.detectBankName(evidence.instrument)?.let { return it }
        return null
    }

    /** 充值/提现/转账/还款不是消费，不参与跨应用关联 */
    private fun isMoneyMovement(evidence: Evidence): Boolean {
        val text = evidence.ruleName + " " + evidence.note
        return listOf("充值", "提现", "转账", "还款", "转出", "转入", "零钱通", "余额宝转").any {
            text.contains(it)
        }
    }

    private fun normalizeMerchant(text: String): String =
        text.trim().replace(" ", "").replace("（", "(").replace("）", ")").take(30)

    /** 从支付方式文本里取银行名与尾号（不保存完整卡号） */
    private data class PaymentInstrumentSnapshot(val bankName: String?, val tail: String?) {
        companion object {
            fun of(raw: String, note: String): PaymentInstrumentSnapshot {
                val text = "$raw $note"
                val brands = listOf(
                    "工商银行", "建设银行", "农业银行", "中国银行", "交通银行", "邮储银行",
                    "招商银行", "中信银行", "光大银行", "民生银行", "浦发银行", "平安银行",
                    "广发银行", "兴业银行", "华夏银行", "北京银行", "上海银行", "宁波银行",
                    "江苏银行", "南京银行", "浙商银行", "恒丰银行", "渤海银行",
                )
                val bankName = brands.firstOrNull { text.contains(it) }
                val tail = Regex("""(?:尾号|末四位|末4位|\(|\*{2,})\s*(\d{4})""")
                    .find(text)?.groupValues?.get(1)
                return PaymentInstrumentSnapshot(bankName, tail)
            }
        }
    }

    private fun String.tailOfMaybe(): String? {
        val m = Regex("""(?:尾号|末四位|末4位|\(（?|[*xX]{2,})\s*(\d{4})""").find(this) ?: return null
        return m.groupValues.getOrNull(1)?.takeIf { it.length == 4 }
    }
}
