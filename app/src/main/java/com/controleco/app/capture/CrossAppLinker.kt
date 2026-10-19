package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 跨应用支付链路关联（纯逻辑，便于单测）。
 *
 * 真实付款经常横跨两个应用：电商/外卖页面负责订单与商户，微信/支付宝负责扣款，
 * 银行短信/推送负责卡侧动账。三者的包名不同，不能只靠"同包名"融合。
 *
 * 判定分两档：
 *
 * 1. **明确线索（强证据，窗口 130 秒）**——页面明确给出可核对的支付渠道/银行卡线索：
 *    - 页面支付方式含"微信/零钱/分付" ⇄ 微信通知；
 *    - 页面支付方式含"支付宝/花呗/余额宝" ⇄ 支付宝通知；
 *    - 页面支付方式含对应银行名（可带卡尾号）⇄ 银行短信/银行 App 推送；
 *    - 订单号相同（双方都能解析出时）是最高优先级证据。
 * 2. **跨链路短时同额（用户规则 2026-10-19，窗口 60 秒）**——页面没有/读不出支付方式时，
 *    同一笔付款仍会同时被"电商/外卖页面"和"支付应用（微信/支付宝）"抓到。
 *    两条链路在 60 秒内捕获到**完全相同**的金额、方向、退款标记即视为同一笔。
 *    这是有意的规则调整：旧实现的"无渠道线索一律不合并"会让每一笔用微信/支付宝支付的
 *    外卖订单都记成两笔（真实样本：美团 ¥19.20 + 微信 ¥19.20）。
 *    真实链路方向是固定的：微信/支付宝先捕获，用户返回商家应用后商户结果页再捕获。
 *
 * 同额档的安全闸门（缺一不可）：
 * - 必须**恰好一侧**是电商/外卖页面，另一侧是微信/支付宝（两个电商页面之间、两个支付通知之间永不合并）；
 * - 金额分、方向、退款标记全等，时间差 ≤ [SAME_AMOUNT_WINDOW_MS]；
 * - 双方都有商户名时至少一方包含另一方（"美团" ⊂ "美团外卖" 可合并，"肯德基"/"麦当劳" 不合并）；
 * - 页面明确写出的支付渠道与对侧来源冲突时不合并（页面写支付宝 ⇄ 微信通知）；
 * - 充值/提现/转账/还款等资金操作不参与（它们与消费不是同一业务事件）。
 *
 * 银行侧不适用同额档：短信/推送只有金额与卡尾号，仍要求页面明确写出银行名/卡尾号（见下）。
 *
 * 关联条件不足或候选不唯一时保持独立记录。
 */
internal object CrossAppLinker {

    /** 页面与通知/银行侧的强证据关联窗口：银行侧到达延迟通常大于支付通知 */
    const val WINDOW_MS = 130_000L

    /**
     * 跨链路同额判重窗口（2026-10-19 用户规则）。
     *
     * 只用于"没有明确渠道线索、且一侧是电商/外卖页面、另一侧是支付应用"的同一笔付款：
     * 用户从支付应用返回商家结果页，或两边的页面/通知先后到达，通常都在几秒内。
     * 刻意收窄到 60 秒——金额相同是弱证据，窗口越宽，把两笔同额真实交易并成一笔的风险越高。
     * （银行侧不适用：短信/推送只有金额与卡尾号，仍要求页面明确写出银行名/卡尾号。）
     */
    const val SAME_AMOUNT_WINDOW_MS = 60_000L

    /** 命中原因：写日志/溯源用，说明这笔是靠哪条证据合并的 */
    enum class LinkReason {
        /** 订单号相同 */
        ORDER_ID,

        /** 页面写入的支付渠道与支付应用来源一致（微信/支付宝） */
        PAYMENT_CHANNEL,

        /** 页面写入的银行名/卡尾号与银行侧一致 */
        BANK_CHANNEL,

        /** 无渠道线索，但两条链路在 [SAME_AMOUNT_WINDOW_MS] 内捕获到完全相同金额 */
        SAME_AMOUNT,
    }

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
    fun isSamePurchase(page: Evidence, other: Evidence): Boolean = linkReason(page, other) != null

    /**
     * 同额判定并返回命中原因（null = 不是同一笔）。
     *
     * 先按"明确线索 + 130 秒"判，再退到"跨链路短时同额 + 60 秒"。
     * 两条路径共用同一组硬闸门（金额/方向/退款/资金操作/商户冲突），避免弱证据绕过安全条件。
     */
    fun linkReason(page: Evidence, other: Evidence): LinkReason? {
        if (!page.isCheckoutPage) return null
        if (page.sourcePackage == other.sourcePackage) return null
        if (page.amountCents != other.amountCents) return null
        if (page.type != other.type) return null
        if (page.isRefund != other.isRefund) return null
        if (isMoneyMovement(page) || isMoneyMovement(other)) return null
        // 两侧都有商户名时至少一方包含另一方；商户明显冲突就不是同一笔
        if (merchantConflicts(page, other)) return null

        val delta = abs(page.time - other.time)
        val channel = CrossChannelDedup.channelClass(other.sourcePackage)

        if (delta <= WINDOW_MS) {
            if (page.orderId.isNotBlank() && other.orderId.isNotBlank() && page.orderId == other.orderId) {
                return LinkReason.ORDER_ID
            }
            when (channel) {
                CrossChannelDedup.ChannelClass.PAY ->
                    if (paymentChannelMatches(other.sourcePackage, page)) return LinkReason.PAYMENT_CHANNEL
                CrossChannelDedup.ChannelClass.BANK ->
                    if (bankChannelMatches(other, page)) return LinkReason.BANK_CHANNEL
                CrossChannelDedup.ChannelClass.UNKNOWN -> Unit
            }
        }

        // 跨链路短时同额：页面没有写出支付方式（或写出的与对侧不冲突）时，
        // 60 秒内完全相同金额的"电商/外卖页面 ⇄ 支付应用"视为同一笔。
        // 银行侧不适用：银行短信/推送只有金额与卡尾号，无法与"同额的另一笔"区分，
        // 仍要求页面明确写出银行名/卡尾号（见 CrossChannelDedup.hasBankEvidence）。
        if (delta <= SAME_AMOUNT_WINDOW_MS &&
            channel == CrossChannelDedup.ChannelClass.PAY &&
            !channelConflicts(page, other)
        ) {
            return LinkReason.SAME_AMOUNT
        }
        return null
    }

    /** 双方都有商户名且互不包含 → 明显不是同一笔 */
    fun merchantConflicts(page: Evidence, other: Evidence): Boolean {
        val a = normalizeMerchant(page.counterparty)
        val b = normalizeMerchant(other.counterparty)
        return a.isNotEmpty() && b.isNotEmpty() && !a.contains(b) && !b.contains(a)
    }

    /**
     * 页面明确写出的渠道与对侧来源冲突时禁止"同额即同笔"：
     * 页面写"支付宝/花呗/余额宝"，对侧却是微信通知（两笔不同渠道的付款）。
     *
     * 页面没写支付方式、或只写了与对侧一致的渠道，不构成冲突。
     * （银行侧不走同额档，卡号冲突由 [bankChannelMatches] 在强证据档处理。）
     */
    fun channelConflicts(page: Evidence, other: Evidence): Boolean {
        val pageChannel = explicitPaymentChannel(page) ?: return false
        if (CrossChannelDedup.channelClass(other.sourcePackage) != CrossChannelDedup.ChannelClass.PAY) {
            return false
        }
        return pageChannel != other.sourcePackage
    }

    /** 页面文本明确写出的支付渠道（只认微信/支付宝；其余返回 null） */
    private fun explicitPaymentChannel(page: Evidence): String? {
        val text = (page.instrument + " " + page.note + " " + page.ruleName + " " + page.counterparty).trim()
        if (text.isBlank()) return null
        val alipay = text.contains("支付宝") || text.contains("花呗") || text.contains("余额宝")
        val wechat = text.contains("微信支付") || text.contains("微信") || text.contains("零钱") || text.contains("分付")
        // 两者同时出现（页面同时展示多种可选支付方式）→ 无法据以否定，交给同额档
        return when {
            alipay && !wechat -> NotificationRules.ALIPAY
            wechat && !alipay -> NotificationRules.WECHAT
            else -> null
        }
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
