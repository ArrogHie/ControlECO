package com.controleco.app.csv

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import com.controleco.app.data.model.BillSource
import com.controleco.app.data.model.ImportedBillRow
import com.controleco.app.data.model.RefundPair

/**
 * 账单导入时的「退款收入 → 被退款的原支出」配对。
 *
 * 为什么要配对：统计页的分类预算按 **支出 − 退款** 算，必须先知道这笔退款退的是哪一类支出。
 * 导入文件里大多数情况**已经写明了**，不需要猜：
 *
 * 1. **支付宝有确定键**：退款行的交易订单号形如 `原交易订单号_退款单号`（2026 版实测），
 *    取 `_` 前面那段就能精确指回原支出。优先用这条，命中即止。
 * 2. **微信没有共享单号**，但账单本身把**原支出行**标成了「已全额退款」/「已退款(¥8.54)」——
 *    这是最有力的证据：候选只接受这些被账单点名退款的支出，再按
 *    金额（全额比原额、部分退款比状态里写明的退款额）+ 商户 + 时间先后选最近的一笔。
 *
 * 配不上、或候选有歧义的一律不关联，交给「待确认账单」让用户点选——宁可让用户点一下，
 * 也不要把退款扣到错的分类上。
 */
object RefundPairing {

    /** 退款可能比原支出晚到多久（超出认为不是同一笔） */
    private const val MAX_GAP_DAYS = 90L
    private const val DAY_MS = 24L * 3_600_000

    /**
     * 对整份账单做配对。返回的下标都指向 [rows]；
     * 一笔支出最多被一笔退款指名，一笔退款最多配一笔支出。
     */
    fun pair(rows: List<ImportedBillRow>, source: BillSource): List<RefundPair> {
        val usedExpenses = mutableSetOf<Int>()
        val pairs = mutableListOf<RefundPair>()
        rows.forEachIndexed { refundIndex, refund ->
            if (refund.refundMark != RefundMark.REFUND_INCOME || refund.amountCents <= 0) {
                return@forEachIndexed
            }
            val expenseIndex = exactMatch(rows, refundIndex, refund, source, usedExpenses)
                ?: evidenceMatch(rows, refundIndex, refund, usedExpenses)
                ?: return@forEachIndexed
            usedExpenses += expenseIndex
            pairs += RefundPair(refundIndex, expenseIndex)
        }
        return pairs
    }

    // ---------- 1. 确定键（支付宝单号） ----------

    /**
     * 支付宝退款：交易订单号 = `原交易订单号_退款单号`。只看单号，不看金额与商户。
     * 旧版/其它格式没有这个结构时返回 null，落到证据匹配。
     */
    private fun exactMatch(
        rows: List<ImportedBillRow>,
        refundIndex: Int,
        refund: ImportedBillRow,
        source: BillSource,
        used: Set<Int>,
    ): Int? {
        if (source != BillSource.ALIPAY) return null
        val id = refund.externalId ?: return null
        val separator = id.indexOf('_')
        if (separator <= 0 || separator == id.lastIndex) return null
        val originalId = id.substring(0, separator)
        return rows.indices.firstOrNull { index ->
            index != refundIndex && index !in used &&
                rows[index].type == TxType.EXPENSE &&
                rows[index].externalId == originalId
        }
    }

    // ---------- 2. 证据匹配（微信；支付宝兜底） ----------

    private data class Candidate(val index: Int, val sameMerchant: Boolean, val gap: Long)

    /**
     * 候选 = 账单里被点名「已退款」的支出：
     * - 全额退款：`refundMark == REFUNDED_EXPENSE` 且金额与退款行相同；
     * - 部分退款：状态写了 `已退款(¥X)`（[ImportedBillRow.refundedCents]）且与退款行金额相同。
     *
     * 退款必须发生在支出之后（最多 90 天）；同商户的优先，其次时间最近。
     * 最优候选出现平局（同商户且时间距离完全相同）时判为有歧义，不自动关联。
     */
    private fun evidenceMatch(
        rows: List<ImportedBillRow>,
        refundIndex: Int,
        refund: ImportedBillRow,
        used: Set<Int>,
    ): Int? {
        val earliest = refund.timestamp - MAX_GAP_DAYS * DAY_MS

        val candidates = rows.indices.mapNotNull { index ->
            if (index == refundIndex || index in used) return@mapNotNull null
            val expense = rows[index]
            if (expense.type != TxType.EXPENSE) return@mapNotNull null
            if (expense.timestamp > refund.timestamp || expense.timestamp < earliest) return@mapNotNull null
            val sameAmount = when {
                expense.refundMark == RefundMark.REFUNDED_EXPENSE -> expense.amountCents == refund.amountCents
                expense.refundedCents != null -> expense.refundedCents == refund.amountCents
                else -> false
            }
            if (!sameAmount) return@mapNotNull null
            Candidate(
                index = index,
                sameMerchant = merchantsRelated(expense.counterparty, refund.counterparty),
                gap = refund.timestamp - expense.timestamp,
            )
        }
        if (candidates.isEmpty()) return null
        val best = candidates.sortedWith(
            compareByDescending<Candidate> { it.sameMerchant }.thenBy { it.gap }.thenBy { it.index },
        )
        val top = best.first()
        val runnerUp = best.getOrNull(1)
        // 同商户 + 时间距离完全相同 = 无法判断是哪一笔，交给用户
        if (runnerUp != null && runnerUp.sameMerchant == top.sameMerchant && runnerUp.gap == top.gap) {
            return null
        }
        return top.index
    }

    /**
     * 商户名是否指向同一个收款方：账单里同一笔的写法可能一个是全称、一个是平台简称
     * （如「拼多多平台商户」与「拼多多」），所以除相等外也接受包含关系。
     * 空值 / 微信转账里的 "/" 不算相关。
     */
    fun merchantsRelated(a: String, b: String): Boolean {
        val x = normalizeMerchant(a)
        val y = normalizeMerchant(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        return shorter.length >= 2 && longer.contains(shorter)
    }

    private fun normalizeMerchant(raw: String): String =
        raw.trim()
            .lowercase()
            .replace(" ", "")
            .replace("/", "")
            .replace("（", "(")
            .replace("）", ")")
            .trim('-', '.', ',', '·')
}
