package com.controleco.app.capture

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 跨通道归并判定（纯函数，便于单测）。
 *
 * 场景：同一笔消费常被两个通道同时捕获——微信/支付宝/云闪付的支付通知（支付渠道侧），
 * 外加银行卡的动账短信或银行 App 推送（银行侧）。不归并就会变成两条待确认，用户确认后
 * 就是两条账单（真实样本：微信转账 ¥200 + 招行「快捷支付扣款 200.00」，见 docs/开发日志.md）。
 *
 * **配对只认「支付渠道 ⇄ 银行」，且仅支出**（2026-10-22 用户规则）：
 * - 支付渠道侧 = 微信 / 支付宝 / 云闪付；
 * - 银行侧 = 银行动账短信（`SMS:` 前缀）/ 银行 App 推送（包名短名单）；
 * - 两个支付渠道之间（微信 ⇄ 支付宝 ⇄ 云闪付）、两家银行之间（工行短信 ⇄ 建行短信）
 *   **一律不归并**——同额两笔真实交易与"同一笔的两条线索"在不读卡号的前提下无法区分，
 *   宁可留两条待确认由用户丢弃，也不冒把两笔真实交易并成一笔的风险（丢记录）；
 * - 收入方向不归并：微信/支付宝收到的钱进的是零钱/余额，不会同时产生一条银行入账，
 *   "同额的两条收入"更可能是两笔独立的钱（工资 ⇄ 收款），合并即丢记录。
 *
 * 判定条件（必须全部命中才算同一笔）：
 * ① 方向都是支出、退款标记相同；② 金额分完全相同；③ 时间差 ≤ [WINDOW_MS]；
 * ④ 一条属支付渠道侧、另一条属银行侧（见 [isPayChannelSide] / [isBankLedgerSide]）。
 *
 * 130 秒窗口比常规的 60 秒预检略宽，因为银行短信的到达延迟通常大于推送。
 *
 * 光有 ①~④ 还不够——还必须有可核对线索才允许合并（见 [payBankLinked]），
 * **唯一例外是微信**：微信页面/通知都不披露所用银行卡（读不到银行名与卡尾号），
 * 按用户规则（2026-10-26）「微信 ⇄ 银行」只按金额 + 时间判定（见 [payBankLink]）。
 */
internal object CrossChannelDedup {

    /** 归并窗口：银行短信/推送相对支付通知的到达延迟 */
    const val WINDOW_MS = LogicalTransactions.CAPTURE_WINDOW_MS

    /**
     * 跨应用链路（`CrossAppLinker`）用的通道分类：支付类（微信/支付宝）与银行类（短信 / 银行 App 推送 / 云闪付）。
     *
     * ⚠️ 与银行动账去重（[isPayChannelSide] / [isBankLedgerSide]）**口径不同**，别混用：
     * 跨应用链路里云闪付是"能提供银行卡线索"的一侧，所以算银行类；
     * 而银行动账去重里云闪付是**支付渠道**（它扣的是绑定的银行卡），必须与微信/支付宝同侧，
     * 否则"云闪付 ⇄ 银行短信"永远配不上对。
     */
    enum class ChannelClass { PAY, BANK, UNKNOWN }

    /**
     * 来源 → 通道类别。刻意**不**用"不在支付白名单里即银行类"的写法（早前草稿的缺陷）：
     * 电商/外卖收银台、未来的新渠道都会被误判成银行侧，与支付通知错误归并。
     * 未知来源一律 UNKNOWN，不参与归并。
     */
    fun channelClass(source: String): ChannelClass = when {
        source.startsWith(NotificationRules.SMS_SOURCE_PREFIX) -> ChannelClass.BANK
        source == NotificationRules.UNIONPAY -> ChannelClass.BANK
        BankLedgerRules.bankNameOfPackage(source) != null -> ChannelClass.BANK
        source == NotificationRules.WECHAT || source == NotificationRules.ALIPAY -> ChannelClass.PAY
        else -> ChannelClass.UNKNOWN
    }

    fun isBankChannel(source: String): Boolean = channelClass(source) == ChannelClass.BANK

    /** 银行动账去重的"支付渠道"一侧：微信 / 支付宝 / 云闪付（详见 [channelClass] 的说明） */
    fun isPayChannelSide(source: String): Boolean =
        source == NotificationRules.WECHAT ||
            source == NotificationRules.ALIPAY ||
            source == NotificationRules.UNIONPAY

    /** 银行动账去重的"银行"一侧：银行动账短信（`SMS:银行名`）/ 银行 App 推送包名。云闪付不算 */
    fun isBankLedgerSide(source: String): Boolean =
        source.startsWith(NotificationRules.SMS_SOURCE_PREFIX) ||
            BankLedgerRules.bankNameOfPackage(source) != null

    /** 一条待归并记录的关键特征 */
    data class Signature(
        val amountCents: Long,
        val type: TxType,
        val isRefund: Boolean,
        val source: String,
        val time: Long,
    )

    /**
     * 从待确认/自动入账条目的字段构造签名（退款标记 → isRefund 的折算只在这一处，
     * 避免调用方各处口径不一致）。
     */
    fun signatureOf(
        amountCents: Long,
        type: TxType,
        refundMark: RefundMark,
        source: String,
        time: Long,
    ): Signature = Signature(
        amountCents = amountCents,
        type = type,
        isRefund = refundMark == RefundMark.REFUND_INCOME,
        source = source,
        time = time,
    )

    fun isSameTransaction(existing: Signature, incoming: Signature): Boolean {
        val payBankPair =
            (isPayChannelSide(existing.source) && isBankLedgerSide(incoming.source)) ||
                (isBankLedgerSide(existing.source) && isPayChannelSide(incoming.source))
        return payBankPair &&
            // 仅支出：收入方向（含退款到账）不参与归并，理由见类注释
            existing.type == TxType.EXPENSE &&
            incoming.type == TxType.EXPENSE &&
            existing.isRefund == incoming.isRefund &&
            existing.amountCents == incoming.amountCents &&
            abs(existing.time - incoming.time) <= WINDOW_MS
    }

    /**
     * 支付类条目是否带有可核对的银行线索（银行名/卡尾号）。
     *
     * [isSameTransaction] 只回答"两笔在金额/方向/时间上是否吻合"；能否合并还必须有这条线索——
     * 只凭金额与时间接近合并，可能把两笔真实交易并成一笔。
     */
    fun hasBankEvidence(entryText: String, bankName: String, cardTail: String?): Boolean {
        if (entryText.isBlank()) return false
        val bankHit = BankLedgerRules.aliasesOf(bankName).any { alias ->
            alias.length >= 2 && entryText.contains(alias)
        }
        val tailHit = !cardTail.isNullOrEmpty() && entryText.contains(cardTail)
        return bankHit || tailHit
    }

    /**
     * 支付渠道 → 银行动账文本里会出现的渠道线索。
     *
     * 银行侧的"特约商户/摘要"字段本身就是最强的渠道线索：招行的真实样本
     * `在【财付通-微信支付-微信转账】发生快捷支付扣款` 明确写着走的是微信支付。
     * 这条线索的方向与 [hasBankEvidence] 相反（银行文本里有渠道名，而不是支付文本里有银行名），
     * 两者任一成立都足以认定"这笔是同一笔卡支付"。
     */
    private val payChannelKeywords: Map<String, List<String>> = mapOf(
        NotificationRules.WECHAT to listOf("微信", "财付通"),
        NotificationRules.ALIPAY to listOf("支付宝"),
        NotificationRules.UNIONPAY to listOf("云闪付", "银联"),
    )

    /** 银行动账文本是否点名了该支付渠道（财付通/微信支付、支付宝、云闪付/银联） */
    fun bankTextMentionsPayChannel(bankText: String, paySource: String): Boolean {
        if (bankText.isBlank()) return false
        val keywords = payChannelKeywords[paySource] ?: return false
        return keywords.any { bankText.contains(it) }
    }

    /**
     * 两侧之间是否存在可核对线索：支付侧文本写了银行名/卡尾号，**或**银行侧文本点名了支付渠道。
     * 两个方向各缺一条线索时都算"证据不足"，保持独立记录（见 docs/通知规则维护指南.md 第六节）。
     *
     * ⚠️ 微信没有例外通道：微信读不到银行卡，本函数对它恒为 false。
     * 判定入口请用 [payBankLink]（它对微信走"仅金额 + 时间"档），不要直接调本函数。
     */
    fun payBankLinked(
        paymentText: String,
        bankText: String,
        bankName: String,
        cardTail: String?,
        paySource: String,
    ): Boolean =
        hasBankEvidence(paymentText, bankName, cardTail) ||
            bankTextMentionsPayChannel(bankText, paySource)

    /** 归并依据（调用方只用来打日志区分路径，判定以非空为准） */
    enum class LinkReason {
        /** 微信：读不到所用银行卡，按用户规则只认金额 + 时间（[WECHAT_AMOUNT_ONLY]） */
        WECHAT_AMOUNT_ONLY,

        /** 支付宝 / 云闪付：必须有可核对线索（[payBankLinked]） */
        EVIDENCE,
    }

    /**
     * 「支付渠道 ⇄ 银行」能否认作同一笔的**唯一入口**（金额/方向/时间由 [isSameTransaction] 判定）。
     *
     * - **微信**：微信的支付页面与支付通知都不披露所用银行卡（既没有银行名也没有卡尾号），
     *   线索闸门对它恒不成立，等于永远不归并——而"微信扣款 + 银行短信/推送到账"恰恰是最常见的一对。
     *   用户规则（2026-10-26）：**微信 ⇄ 银行仅按金额 + 时间 + 方向 + 退款标记判定**。
     *   代价：同一时段（[WINDOW_MS]）内两笔真实同额微信付款 + 一条同额银行动账，可能被并成一笔；
     *   用户已知并接受，且"同额候补不唯一"时仍保持独立（见 `CaptureCenter` 的 ambiguous 分支）。
     * - **支付宝 / 云闪付**：保持线索闸门（[payBankLinked]）——支付宝页面能读到"花呗/余额宝/银行卡(尾号)"，
     *   云闪付本身就是卡组织通道，线索可得，不必放宽。
     * - 不属于支付渠道侧（[isPayChannelSide]）的来源一律不配对。
     */
    fun payBankLink(
        paymentText: String,
        bankText: String,
        bankName: String,
        cardTail: String?,
        paySource: String,
    ): LinkReason? {
        if (!isPayChannelSide(paySource)) return null
        if (paySource == NotificationRules.WECHAT) return LinkReason.WECHAT_AMOUNT_ONLY
        return if (payBankLinked(paymentText, bankText, bankName, cardTail, paySource)) {
            LinkReason.EVIDENCE
        } else {
            null
        }
    }
}
