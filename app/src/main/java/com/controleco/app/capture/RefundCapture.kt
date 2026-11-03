package com.controleco.app.capture

import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType

/** 退款必须由用户选中原支出，所有采集通道共用这道入账闸门。 */
internal fun canDirectlyEnterCapture(isRefund: Boolean, amountCents: Long): Boolean =
    !isRefund && amountCents > 0

internal fun canFillRefundAmount(existing: PendingTransactionEntity, isRefund: Boolean, amountCents: Long): Boolean =
    isRefund && amountCents > 0 && existing.status == PendingStatus.PENDING &&
        existing.type == TxType.INCOME && existing.refundMark == RefundMark.REFUND_INCOME &&
        existing.amountCents == 0L

internal fun refundNoticePending(
    packageName: String,
    rawId: Long,
    timestamp: Long,
    title: String,
    text: String,
    bigText: String,
    notificationKey: String?,
    eventStamp: Long,
): PendingTransactionEntity {
    val identity = notificationInstanceId(notificationKey, eventStamp, TxType.INCOME, true)
    // 无实例键时保留原始时间和文本摘要，避免同一分钟的两条退款提醒互相覆盖。
    val fallback = stableDigest("$eventStamp|$title|$text|$bigText")
    return PendingTransactionEntity(
        type = TxType.INCOME, amountCents = 0L, timestamp = timestamp,
        sourcePackage = packageName, ruleName = "退款·待处理",
        note = listOf(title, text, bigText).filter { it.isNotBlank() }.distinct().joinToString(" · ").take(500),
        dedupKey = dedupKeyWithIdentity("$packageName|INCOME|R|0|${timestamp / 60_000}", identity ?: "n:$fallback"),
        rawNotificationId = rawId, refundMark = RefundMark.REFUND_INCOME,
        status = PendingStatus.PENDING, notificationInstanceId = identity.orEmpty(),
    )
}
