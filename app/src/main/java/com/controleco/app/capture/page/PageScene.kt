package com.controleco.app.capture.page

import com.controleco.app.data.db.TxType

/**
 * 页面场景：一张页面在支付流程里扮演什么角色。
 *
 * 引入它的原因：只判断"含不含支付成功"会把订单待支付页、订单列表、进度条一起记成账。
 * 专属解析器先定场景，再按场景决定字段提取方式与是否允许生成交易候选。
 */
enum class PageScene(val label: String) {
    UNKNOWN("未知页"),
    /** 收银台/支付确认页（还没付）：可作为流程锚点，绝不生成候选 */
    PAYMENT_CONFIRM("支付确认页"),
    /** 支付成功页（付款结果页） */
    PAYMENT_SUCCESS("支付成功页"),
    /** 支付失败页 */
    PAYMENT_FAILED("支付失败页"),
    /** 支付取消/关闭/超时页 */
    PAYMENT_CANCELLED("支付已取消页"),
    /** 交易/账单详情页（已完成的交易，含单号、支付方式、时间） */
    TRANSACTION_DETAIL("账单详情页"),
    /** 收款到账页（转账收款/收款码收款） */
    RECEIVE_SUCCESS("收款到账页"),
    /** 转账成功页（含转出/充值/提现） */
    TRANSFER_SUCCESS("转账成功页"),
    /** 红包收发页 */
    RED_PACKET("红包页"),
    /** 退款成功页 */
    REFUND_SUCCESS("退款成功页"),
    /** 自动扣款/免密扣款页 */
    AUTO_DEBIT("自动扣款页"),
    /** 订单待支付页（下单未付） */
    ORDER_PENDING("订单待支付页"),
    /** 订单取消/关闭页 */
    ORDER_CANCELLED("订单已取消页"),
    /** 交易列表/明细列表（不是单笔结果页） */
    LIST("列表页"),
}

/**
 * 交易状态：候选交易是否已成立。
 *
 * 只有 [SUCCESS] 与 [REFUND] 允许生成/补全账目；[PENDING] 只用于流程上下文（收银台信息带给结果页），
 * 绝不因为"页面出现了金额"就入账。
 */
enum class TradeState {
    UNKNOWN,
    /** 交易进行中：收银台/密码键盘/处理中（含"银行处理中"这类未落账状态） */
    PENDING,
    /** 交易已成功 */
    SUCCESS,
    /** 交易失败 */
    FAILED,
    /** 交易被取消/关闭/超时 */
    CANCELLED,
    /** 退款（独立资金流） */
    REFUND,
}

/** 解析置信度：页面结构命中越确定越高；低置信度仍可入账但会进日志便于回溯 */
enum class ParseConfidence { HIGH, MEDIUM, LOW }

/**
 * 页面交易候选：页面通道交给采集中心的统一结果（与通知/短信候选同构）。
 *
 * [instrument] 是结构化后的支付工具（零钱/余额宝/招商银行(1234)…），
 * 供账户匹配使用；[paymentMethod] 保留原始文本用于展示与备注。
 */
data class PageCandidate(
    val type: TxType,
    val amountCents: Long,
    /** 收款方/商户（空 = 页面没有可用的商户信息） */
    val counterparty: String = "",
    /** 页面上的支付方式原文（"零钱" / "招商银行(1234)" / "美团月付"） */
    val paymentMethod: String? = null,
    /** 结构化支付工具（账户匹配用；解析不出为 null） */
    val instrument: PaymentInstrument? = null,
    /** 页面上的交易时间（解析不出为 null，调用方用捕获时刻兜底） */
    val txTime: Long? = null,
    val scene: PageScene,
    val tradeState: TradeState,
    val confidence: ParseConfidence,
    val sourceLabel: String,
    /** 规则名（"微信·支付成功页"），写入条目与日志 */
    val ruleName: String,
    /** 备注：支付方式等页面独有信息 */
    val note: String = "",
    /** 交易单号/订单号（可获得时用于跨渠道关联，缺失为 null） */
    val orderId: String? = null,
    /**
     * 是否退款到账（收入方向 + 退款标记）。退款是独立资金流：
     * 与同额消费支出绝不互相覆盖，跨渠道归并时也必须方向一致才可能同笔。
     */
    val isRefund: Boolean = false,
    /**
     * 本次候选所属的"交易流程"标识（同一笔支付在不同页面上保持同一个值）。
     * 用于区分"同一笔的重复事件"与"同金额的两笔独立交易"：采集中心据此给去重键分配序号，
     * 流程标识不同 = 独立交易，不会被唯一索引吞掉第二笔。
     */
    val flowKey: String = "",
    /** 基础去重键（不含 flowSeq 序号；序号由采集中心按已用键分配） */
    val dedupKey: String,
)
