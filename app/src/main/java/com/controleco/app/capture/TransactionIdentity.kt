package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import java.security.MessageDigest

/**
 * 交易身份与去重键的工具（纯函数，便于单测）。
 *
 * 三个概念必须分开：
 * - **去重键**（数据库唯一索引）：同一条捕获不应重复落库；
 * - **页面流程标识**（`p:<uuid>`）：同一次支付流程的确认页/结果页/详情页共享；
 * - **通知实例标识**（`n:<摘要>`）/银行事件标识（`b:<摘要>`）：同一条系统通知/短信的更新共享。
 *
 * 既有实现让第一笔页面交易使用"无后缀"的去重键，导致进程重启或缓存清空后无法从持久化记录
 * 还原它属于哪个流程。这里改为：页面/通知/银行候选**首次生成就带身份后缀**，
 * 同时把身份独立存到条目列（flowId / notificationInstanceId），不再依赖内存分配器。
 */

internal const val PAGE_ID_PREFIX = "p:"
internal const val NOTIFICATION_ID_PREFIX = "n:"
internal const val BANK_ID_PREFIX = "b:"

/** 去重键 + 身份后缀：同一身份重复生成得到同一个键；不同身份即使同分钟同额也不会互相覆盖。 */
internal fun dedupKeyWithIdentity(baseKey: String, identity: String?): String =
    if (identity.isNullOrEmpty()) baseKey else "$baseKey@$identity"

/**
 * 通知实例标识。
 *
 * 同一 `StatusBarNotification`（key 相同）的折叠更新、重复回调会得到同一个 `when` 与同方向，
 * 因此标识稳定；另一笔真实交易通常换了通知实例或换了发布时间，标识不同。
 * **刻意不包含金额**：同一通知从"金额待补"更新为完整金额时仍是同一条通知实例，
 * 两次捕获必须能对上；不同金额的真实交易由金额段与严格同额合并规则区分。
 * 只保存摘要，不把通知原文写进身份。
 */
internal fun notificationInstanceId(
    notificationKey: String?,
    rawPostTime: Long,
    type: TxType,
    isRefund: Boolean,
): String? {
    if (notificationKey.isNullOrBlank()) return null
    val material = "ntf|$notificationKey|$rawPostTime|$type|$isRefund"
    return NOTIFICATION_ID_PREFIX + stableDigest(material)
}

/**
 * 银行事件标识：同一条短信/推送重复投递得到同一个值；
 * 同一分钟内同额的两笔真实动账因事件时间/卡尾号不同而分开。
 */
internal fun bankInstanceId(
    sourceKey: String,
    eventTime: Long,
    type: TxType,
    amountCents: Long,
    isRefund: Boolean,
    cardTail: String?,
): String {
    val material = "bank|$sourceKey|$eventTime|$type|$amountCents|$isRefund|${cardTail.orEmpty()}"
    return BANK_ID_PREFIX + stableDigest(material)
}

/** 稳定摘要（SHA-256 十六进制）：不用短哈希承担交易身份。 */
internal fun stableDigest(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val out = CharArray(bytes.size * 2)
    val digits = "0123456789abcdef"
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        out[index * 2] = digits[value ushr 4]
        out[index * 2 + 1] = digits[value and 0x0F]
    }
    return String(out)
}

/** 交易已插入、但关联的 AUTO 待确认条目写入失败（唯一键冲突或返回非正 id） */
internal class EntryAssociationException : Exception("pending association not created")

/**
 * 正式交易与 AUTO 关联条目必须成对落库（CR-008）：
 * 关联插入返回 -1（唯一键冲突被 IGNORE）时抛异常，让外层 Room 事务回滚交易插入，
 * 而不是留下一条没有关联记录的"半成品"账单。
 */
internal fun requireEntryAssociation(pendingId: Long) {
    if (pendingId <= 0) throw EntryAssociationException()
}
