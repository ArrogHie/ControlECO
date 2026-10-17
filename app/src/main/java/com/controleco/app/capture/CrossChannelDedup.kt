package com.controleco.app.capture

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 跨通道归并判定（纯函数，便于单测）。
 *
 * 场景：同一笔消费常被两个通道同时捕获——微信/支付宝的支付通知（支付类），
 * 外加银行卡的动账短信或银行 App 推送（银行类）。不归并就会变成两条待确认。
 *
 * 判定条件（必须全部命中才算同一笔）：
 * ① 金额分完全相同；② 收支方向相同；③ 退款标记相同；④ 时间差 ≤ [WINDOW_MS]；
 * ⑤ **通道类别相异且一边支付类、一边银行类**。
 *
 * 关于 ⑤：不做"同类别内归并"是刻意的保守选择——云闪付通知与银行短信虽然大概率是
 * 同一笔（同卡两通知），但在不读卡号的前提下，无法与"两张卡各刷一笔同额"区分开；
 * 宁可留两条待确认由用户丢弃，也不冒把两笔真实交易并成一笔的风险（丢记录）。
 *
 * 130 秒窗口比常规的 60 秒预检略宽，因为银行短信的到达延迟通常大于推送。
 */
internal object CrossChannelDedup {

    /** 归并窗口：银行短信/推送相对支付通知的到达延迟 */
    const val WINDOW_MS = 130_000L

    /** 通道类别：支付类（微信/支付宝）与银行类（短信 / 银行 App 推送 / 云闪付） */
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
        val a = channelClass(existing.source)
        val b = channelClass(incoming.source)
        val crossClass = (a == ChannelClass.PAY && b == ChannelClass.BANK) ||
            (a == ChannelClass.BANK && b == ChannelClass.PAY)
        return crossClass &&
            existing.amountCents == incoming.amountCents &&
            existing.type == incoming.type &&
            existing.isRefund == incoming.isRefund &&
            abs(existing.time - incoming.time) <= WINDOW_MS
    }

    /**
     * 支付类条目是否带有可核对的银行线索（银行名/卡尾号）。
     *
     * [isSameTransaction] 只回答"两笔在金额/方向/时间上是否吻合"；能否合并到银行侧
     * 还必须有这条线索——只凭金额与时间接近合并，可能把两笔真实交易并成一笔。
     */
    fun hasBankEvidence(entryText: String, bankName: String, cardTail: String?): Boolean {
        if (entryText.isBlank()) return false
        val bankHit = BankLedgerRules.aliasesOf(bankName).any { alias ->
            alias.length >= 2 && entryText.contains(alias)
        }
        val tailHit = !cardTail.isNullOrEmpty() && entryText.contains(cardTail)
        return bankHit || tailHit
    }
}
