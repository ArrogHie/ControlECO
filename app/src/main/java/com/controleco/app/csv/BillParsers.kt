package com.controleco.app.csv

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import com.controleco.app.data.model.BillSource
import com.controleco.app.data.model.ImportedBillRow
import com.controleco.app.data.model.ParsedBill
import com.controleco.app.util.AppLog
import com.controleco.app.util.CategoryGuesser
import com.controleco.app.util.Money
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * 微信/支付宝账单解析。
 * - 微信：2026 版起导出 xlsx（交易时间为 Excel 日期序列号）；旧版 CSV 文本时间同样兼容。
 * - 支付宝：2026 版 CSV 表头为 交易时间/交易分类/…/交易订单号（GBK 编码、单号带尾部制表符）；旧版（交易创建时间/交易号）兼容。
 * 列按名称映射而非固定列位，列顺序变化不影响；表头行按特征自动识别。
 */
object BillParsers {

    private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Excel 日期序列号纪元偏移：1899-12-30 距 1970-01-01 的天数 */
    private const val EXCEL_EPOCH_OFFSET = 25569L

    /** 解析账单字节流：xlsx/CSV → 行列 → 识别来源 → 分发。无法识别返回 null。 */
    fun parse(bytes: ByteArray): ParsedBill? {
        val rows = if (XlsxReader.isXlsx(bytes)) {
            AppLog.i("import", "input detected as xlsx")
            XlsxReader.parse(bytes)
        } else {
            CsvReader.parse(CsvReader.decode(bytes))
        }
        val source = detect(rows) ?: return null
        AppLog.i("import", "detected bill source=$source, totalLines=${rows.size}")
        return when (source) {
            BillSource.WECHAT -> parseWechat(rows)
            BillSource.ALIPAY -> parseAlipay(rows)
        }
    }

    fun detect(rows: List<List<String>>): BillSource? {
        val head = rows.take(25)
        fun has(keyword: String) = head.any { row -> row.any { it.contains(keyword) } }
        return when {
            has("微信支付账单明细") || has("交易单号") -> BillSource.WECHAT
            has("交易订单号") || has("商家订单号") || has("交易号") -> BillSource.ALIPAY
            else -> null
        }
    }

    // ---------- 微信 ----------

    fun parseWechat(rows: List<List<String>>): ParsedBill {
        val headerIdx = rows.indexOfFirst { row ->
            row.any { normalize(it) == "交易时间" } && row.any { normalize(it) == "交易单号" }
        }
        require(headerIdx >= 0) { "未找到微信账单表头（交易时间/交易单号）" }
        val header = rows[headerIdx].map { normalize(it) }
        fun col(prefix: String) = header.indexOfFirst { it.startsWith(prefix) }

        val iTime = col("交易时间")
        val iCounterparty = col("交易对方")
        val iGoods = col("商品")
        val iInOut = col("收/支")
        val iAmount = col("金额")
        val iPayMethod = col("支付方式")
        val iStatus = col("当前状态")
        val iTxnId = col("交易单号")
        val iNote = col("备注")

        val parsed = mutableListOf<ImportedBillRow>()
        var skipType = 0
        var skipStatus = 0
        var refundCount = 0

        for (raw in rows.subList(headerIdx + 1, rows.size)) {
            fun cell(idx: Int) = if (idx in raw.indices) raw[idx].trim() else ""
            if (raw.all { it.isBlank() }) continue

            val type = when (cell(iInOut)) {
                "收入" -> TxType.INCOME
                "支出" -> TxType.EXPENSE
                else -> { skipType++; continue }   // "/"：不计收支（充值、提现、理财等）
            }
            val amount = Money.parseCents(cell(iAmount))
            if (amount == null) {
                AppLog.w("import", "微信账单金额无法解析: '${cell(iAmount)}'")
                continue
            }

            // 退款结构（真实账单为成对记录）：
            // - 退款到账行：收/支=收入、交易类型"XXX-退款"、状态"已全额退款" → 标记退款收入
            // - 原支出行：状态"已全额退款" → 标记已退款；状态"已退款(¥8.54)"为部分退款 → 保持普通支出
            var mark = RefundMark.NONE
            val status = cell(iStatus)
            if (status.contains("退款")) {
                refundCount++
                mark = when {
                    type == TxType.INCOME -> RefundMark.REFUND_INCOME
                    extractRefundAmount(status)?.let { it < amount } == true -> RefundMark.NONE
                    else -> RefundMark.REFUNDED_EXPENSE
                }
            }

            val ts = parseWechatTime(cell(iTime))
            if (ts == null) {
                AppLog.w("import", "微信账单时间无法解析: '${cell(iTime)}'")
                continue
            }
            val counterparty = cell(iCounterparty)
            val goods = cell(iGoods)
            val note = cell(iNote).ifEmpty { goods }
            parsed += ImportedBillRow(
                type = type,
                amountCents = amount,
                timestamp = ts,
                counterparty = counterparty,
                goods = goods,
                note = note,
                payMethod = cell(iPayMethod),
                externalId = cell(iTxnId).ifEmpty { null },
                guessedCategoryName = CategoryGuesser.guess("$counterparty $goods"),
                refundMark = mark,
            )
        }
        return ParsedBill(BillSource.WECHAT, parsed, skipType, skipStatus, refundCount)
    }

    /**
     * 微信交易时间：xlsx 为 Excel 日期序列号（如 46299.627...，文件注明为 UTC+8 墙上时间），
     * 旧 CSV 为 "yyyy-MM-dd HH:mm:ss" 文本。统一按本地时区墙上时间转毫秒。
     */
    private fun parseWechatTime(text: String): Long? {
        val serial = text.toDoubleOrNull()
        if (serial != null && serial > 20000) {
            val days = serial.toLong()
            val frac = serial - days
            val date = LocalDate.ofEpochDay(days - EXCEL_EPOCH_OFFSET)
            return date.atStartOfDay(ZoneId.systemDefault())
                .plusSeconds((frac * 86400.0).roundToLong())
                .toInstant().toEpochMilli()
        }
        return parseTime(text)
    }

    // ---------- 支付宝 ----------

    fun parseAlipay(rows: List<List<String>>): ParsedBill {
        val headerIdx = rows.indexOfFirst { row ->
            val cells = row.map { normalize(it) }
            cells.any { it == "交易时间" || it == "交易创建时间" } &&
                cells.any { it == "交易订单号" || it == "交易号" }
        }
        require(headerIdx >= 0) { "未找到支付宝账单表头（交易时间/交易订单号）" }
        val header = rows[headerIdx].map { normalize(it) }
        fun col(prefix: String) = header.indexOfFirst { it.startsWith(prefix) }
        fun colExact(name: String) = header.indexOfFirst { it == name }

        val iTime = colExact("交易时间").takeIf { it >= 0 } ?: col("交易创建时间")
        val iPaid = col("付款时间")                    // 旧版才有，优先于创建时间
        val iTradeNo = colExact("交易订单号").takeIf { it >= 0 } ?: colExact("交易号")
        val iCategory = colExact("交易分类")           // 2026 新版才有
        val iCounterparty = col("交易对方")
        val iGoods = col("商品")
        val iAmount = col("金额")
        val iInOut = col("收/支")
        val iStatus = col("交易状态")
        val iPayMethod = col("收/付款").takeIf { it >= 0 } ?: col("支付方式")
        val iNote = col("备注")

        val parsed = mutableListOf<ImportedBillRow>()
        var skipType = 0
        var skipStatus = 0
        var refundCount = 0

        for (raw in rows.subList(headerIdx + 1, rows.size)) {
            fun cell(idx: Int) = if (idx >= 0 && idx in raw.indices) raw[idx].trim() else ""
            if (raw.all { it.isBlank() }) continue

            val inOut = cell(iInOut)
            val status = cell(iStatus)

            val type = when {
                inOut == "收入" -> TxType.INCOME
                inOut == "支出" -> TxType.EXPENSE
                // 不计收支中的退款到账行（状态"退款成功"）单独收录，其余（充值/提现/理财）跳过
                status.contains("退款") -> TxType.INCOME
                else -> { skipType++; continue }
            }

            // 退款标记（真实账单为成对记录）：
            // - 退款到账行：不计收支 + 状态"退款成功" → 标记退款收入
            // - 原支出行：状态"交易关闭"（已退款关闭）→ 标记已退款；部分退款时原行仍"交易成功"，保持普通支出
            var mark = RefundMark.NONE
            when {
                status.contains("退款") && type == TxType.INCOME -> { mark = RefundMark.REFUND_INCOME; refundCount++ }
                type == TxType.EXPENSE && status == "交易关闭" -> { mark = RefundMark.REFUNDED_EXPENSE; refundCount++ }
                !(status.contains("成功") || status.contains("已到账")) -> { skipStatus++; continue }
                else -> {}
            }

            val amount = Money.parseCents(cell(iAmount))
            if (amount == null) {
                AppLog.w("import", "支付宝账单金额无法解析: '${cell(iAmount)}'")
                continue
            }
            val ts = parseTime(cell(iPaid)) ?: parseTime(cell(iTime))
            if (ts == null) {
                AppLog.w("import", "支付宝账单时间无法解析: '${cell(iPaid)}'/'${cell(iTime)}'")
                continue
            }
            val counterparty = cell(iCounterparty)
            val goods = cell(iGoods)
            val note = cell(iNote).ifEmpty { goods }
            val categoryText = if (iCategory >= 0) cell(iCategory) else ""
            val guessed = CategoryGuesser.fromAlipayCategory(categoryText)
                ?: CategoryGuesser.guess("$counterparty $goods")
            parsed += ImportedBillRow(
                type = type,
                amountCents = amount,
                timestamp = ts,
                counterparty = counterparty,
                goods = goods,
                note = note,
                payMethod = cell(iPayMethod),
                externalId = cell(iTradeNo).ifEmpty { null },
                guessedCategoryName = guessed,
                refundMark = mark,
            )
        }
        return ParsedBill(BillSource.ALIPAY, parsed, skipType, skipStatus, refundCount)
    }

    // ---------- 公共 ----------

    /** 微信部分退款状态中的金额：兼容"已退款(¥8.54)"与"已退款¥8.54" */
    private val REFUND_AMOUNT = Regex("""已退款[(]?[¥￥]?\s*(\d+(?:\.\d{1,2})?)""")

    private fun extractRefundAmount(status: String): Long? =
        REFUND_AMOUNT.find(status)?.groupValues?.get(1)?.let { Money.parseCents(it) }

    private fun normalize(s: String): String =
        s.replace("（", "(").replace("）", ")").replace(" ", "").trim()

    private fun parseTime(text: String): Long? = try {
        LocalDateTime.parse(text, DATETIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
