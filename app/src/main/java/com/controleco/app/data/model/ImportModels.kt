package com.controleco.app.data.model

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType

/** 账单来源（阶段一：CSV 导出文件；后续阶段扩展通知、短信等） */
enum class BillSource(val displayName: String) {
    WECHAT("微信支付"),
    ALIPAY("支付宝"),
}

/** 解析后待确认的单条账单 */
data class ImportedBillRow(
    val type: TxType,
    val amountCents: Long,
    val timestamp: Long,
    val counterparty: String,
    val goods: String,
    val note: String,
    val payMethod: String,
    val externalId: String?,
    val guessedCategoryName: String,
    val refundMark: RefundMark = RefundMark.NONE,
    /**
     * 原支出行状态里写明的退款金额（如微信 `已退款(¥8.54)`）。
     * 只用于把退款收入配回这笔支出；全额退款为 null（金额与原支出相同）。
     */
    val refundedCents: Long? = null,
)

/**
 * 退款收入 → 原支出的配对结果（下标指向 [ParsedBill.rows]）。
 * 账单里成对的退款记录在导入时据此自动关联，配不上的退款进「待确认账单」等用户选。
 */
data class RefundPair(val refundIndex: Int, val expenseIndex: Int)

/** 一个账单文件的解析结果 */
data class ParsedBill(
    val source: BillSource,
    val rows: List<ImportedBillRow>,
    /** 不计收支（如零钱充值、提现、理财申购）跳过的条数 */
    val skippedOtherType: Int,
    /** 状态未完成（如等待付款）而跳过的条数 */
    val skippedStatus: Int,
    /** 退款相关记录数（始终入库） */
    val refundCount: Int,
    /** 账单里能确定配对上的「退款收入 → 原支出」 */
    val refundPairs: List<RefundPair> = emptyList(),
)

data class ImportCommitResult(
    val inserted: Int,
    val duplicated: Int,
    /** 本次导入顺带关联上的退款条数（配对成功且原支出能定位到） */
    val refundLinked: Int = 0,
)
