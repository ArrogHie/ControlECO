package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money

/**
 * 银行动账文本解析（短信与银行 App 推送共用）。
 *
 * 为什么共用：银行动账短信与银行 App 的动账推送是**同一种文本形态**
 * （`【工商银行】您尾号1234的储蓄卡10月8日12:30消费人民币50.00元，余额5000.00元`），
 * 拆成两套规则只会在改版时漏改一处。所以解析核心在这里，短信侧只多一道发送号码闸门。
 *
 * 规则来源：银行短信/推送的通用形态（【银行名】签名 + 尾号 + 时间 + 方向词 + 金额 + 余额），
 * 自测中逐条发现并修正的坑（机制见 docs/自动记账捕获机制.md；早期草稿见未合并分支
 * feat/capture-refs-integration）：
 * 1. 发送号码曾完全没用上——这里把它当第一道闸门（[isBankSender]）；
 * 2. 不支持千分位：`消费人民币1,234.56元` 会被截断成 `234.56`（真实错值）——统一金额组；
 * 3. "取第一个金额"，余额在前时会取错——这里按**上下文**区分交易金额与余额/额度；
 * 4. "两个方向词都不命中就丢弃"予以保留（防验证码/营销短信误入库）；
 * 5. 短信接收器不用 goAsync()、自建 scope 从不取消，进程被杀即丢单——
 *    接收器侧改为 goAsync + IO（见 [SmsCaptureReceiver]）。
 *
 * 诚实声明（见开发日志）：两类通道都**没有真机样本**（仓库内只有微信/支付宝日志），
 * 规则按通用形态推演 + 合成单测保护；首条真实样本到手后按维护指南扩规则。
 */
object BankLedgerRules {

    // ---------- 银行识别 ----------

    /**
     * 银行别名 → 标准全称。长名在前，避免短别名抢先命中。
     * （"中国工商银行" 不含 "中国银行" 子串，"中信银行" 不含 "中行"，故各家互不串名。）
     */
    private val bankNames: List<Pair<String, String>> = listOf(
        "中国工商银行" to "工商银行", "工商银行" to "工商银行", "工行" to "工商银行",
        "中国建设银行" to "建设银行", "建设银行" to "建设银行", "建行" to "建设银行",
        "中国农业银行" to "农业银行", "农业银行" to "农业银行", "农行" to "农业银行",
        "中国邮政储蓄银行" to "邮储银行", "邮政储蓄银行" to "邮储银行",
        "邮储银行" to "邮储银行", "邮储" to "邮储银行",
        "中国银行" to "中国银行", "中行" to "中国银行",
        "招商银行" to "招商银行", "招行" to "招商银行",
        "交通银行" to "交通银行", "交行" to "交通银行",
        "中信银行" to "中信银行", "中信" to "中信银行",
        "上海浦东发展银行" to "浦发银行", "浦发银行" to "浦发银行",
        "中国民生银行" to "民生银行", "民生银行" to "民生银行",
        "光大银行" to "光大银行", "平安银行" to "平安银行", "广发银行" to "广发银行",
        "兴业银行" to "兴业银行", "华夏银行" to "华夏银行", "浙商银行" to "浙商银行",
        "渤海银行" to "渤海银行", "恒丰银行" to "恒丰银行",
        "北京银行" to "北京银行", "上海银行" to "上海银行", "江苏银行" to "江苏银行",
        "宁波银行" to "宁波银行", "南京银行" to "南京银行", "杭州银行" to "杭州银行",
        "云闪付" to "云闪付", "银联" to "云闪付",
    )

    /** 短信签名：【工商银行】 ——中文银行短信与 App 推送的通用签名形态 */
    private val signatureRegex = Regex("""[【\[［]\s*([^】\]］]{2,20}?)\s*[】\]］]""")

    /**
     * 银行 App 包名 → 银行名。**只在正文没有【银行名】签名时兜底**——
     * 银行 App 的推送常省略签名（"您尾号1234的账户消费50.00元"），此时包名是唯一的银行线索。
     * 这份短名单不是闸门（正文签名才是），列错或漏列只会少抓几条推送，不会误记。
     */
    private val bankAppPackages: Map<String, String> = mapOf(
        "com.icbc" to "工商银行",
        "com.chinamworld.main" to "建设银行",
        "com.android.bankabc" to "农业银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "cmb.pb" to "招商银行",
        "com.bankcomm.Bankcomm" to "交通银行",
        "com.yitong.mbank.psbc" to "邮储银行",
        "com.ecitic.bank.mobile" to "中信银行",
        "com.cebbank.mobile.cemb" to "光大银行",
        "com.cmbc.cc.mbank" to "民生银行",
        "com.spdbccc.app" to "浦发银行",
        "com.pingan.pabank.activity" to "平安银行",
        "com.cgbchina.xpt" to "广发银行",
        "com.cib.bank" to "兴业银行",
        "com.huaxia.mobilebank" to "华夏银行",
    )

    fun bankNameOfPackage(packageName: String): String? = bankAppPackages[packageName]

    /** 已知银行短信号码段（前 5 位）。命中只是"加分项"，正文银行签名仍必须命中 */
    private val bankSenderPrefixes = listOf(
        "95588", "95533", "95566", "95599", "95555", "95559", "95580", "95558",
        "95595", "95568", "95528", "95511", "95508", "95561", "95577",
        "95313", "95308", "95312", "95516", "95188", "95594", "95337",
    )

    fun isBankSender(sender: String): Boolean {
        val digits = sender.filter { it.isDigit() }
        if (digits.length < 5) return false
        if (bankSenderPrefixes.any { digits.startsWith(it) }) return true
        // 带国家码的号码（+86 95588 / 8695588）
        return digits.startsWith("86") && bankSenderPrefixes.any { digits.substring(2).startsWith(it) }
    }

    /** 从文本里识别银行名：优先取【】签名内的，再退化到全文扫描 */
    fun detectBankName(text: String): String? {
        for (m in signatureRegex.findAll(text)) {
            val inner = m.groupValues[1]
            bankNames.firstOrNull { inner.contains(it.first) }?.let { return it.second }
        }
        return bankNames.firstOrNull { text.contains(it.first) }?.second
    }

    /**
     * 某个标准银行名的全部写法（标准名 + 别名，如 招商银行 ⇄ 招行）：账户名推测用——
     * 用户账户常起名"招行卡""工行"，只比对标准名会匹配不上。
     */
    fun aliasesOf(bankName: String): List<String> =
        (listOf(bankName) + bankNames.filter { it.second == bankName }.map { it.first }).distinct()

    // ---------- 噪声（银行营销/通知类短信占绝对多数，必须挡掉） ----------

    /**
     * 硬噪声：这些词出现时**一定不是动账**（安全提示、额度营销、账单提醒、办理邀约），无一票否决之外的例外。
     */
    private val hardNoise = listOf(
        "验证码", "校验码", "动态码", "密码", "登录", "注册", "开通", "激活",
        "可用额度", "信用额度", "额度", "授信", "提额", "分期", "手续费率", "年费",
        "利率", "理财产品", "基金", "保险", "账单日", "还款日", "到期", "逾期",
        "网点", "营业时间", "客服热线", "退订", "回T", "投诉", "挂失",
    )

    /**
     * 促销噪声：营销文案常用词。**只在没有交易确认短语时**才否决——
     * 真实动账短信偶尔带"，享优惠/送积分"的尾巴，不能连交易一起丢
     * （"消费满100元可参与抽奖"这类纯营销则没有确认短语，会被挡下）。
     */
    private val promoNoise = listOf(
        "抽奖", "中奖", "优惠", "满减", "立减", "好礼", "推荐", "回馈", "问卷", "评价",
        "活动", "领取", "点击", "详情", "积分", "扫码", "关注", "下载", "邀请",
    )

    /** 同 NotificationRules：不含裸的"已到账"（"优惠券已到账"类营销短信会借此绕过促销词闸门） */
    private val confirmPhrases = listOf(
        "已支付", "支付成功", "交易成功", "已扣款", "扣款成功", "已入账", "入账成功",
        "收款成功", "退款成功", "已退款",
    )

    fun isNoise(text: String): Boolean =
        hardNoise.any { text.contains(it) } ||
            (promoNoise.any { text.contains(it) } && confirmPhrases.none { text.contains(it) })

    // ---------- 方向 ----------

    private val incomeWords = listOf(
        "收入", "存入", "转入", "到账", "入账", "收款", "汇入", "代发", "工资", "薪资",
        "退款", "返款", "返现", "利息", "结息", "分红", "存现", "现存",
    )
    private val expenseWords = listOf(
        "支出", "消费", "支取", "取款", "取现", "转出", "付款", "扣款", "扣费", "扣除",
        "代扣", "缴费", "还款", "透支", "支付", "汇出", "提现",
    )

    /** 方向判定：退款优先（"退款到账"同时命中收入词时不能按普通收款处理）；双向歧义即放弃 */
    fun detectDirection(text: String): Pair<TxType, Boolean>? {
        val refund = text.contains("退款") || text.contains("退回") || text.contains("冲正")
        val income = incomeWords.any { text.contains(it) }
        val expense = expenseWords.any { text.contains(it) }
        return when {
            refund -> TxType.INCOME to true
            income && !expense -> TxType.INCOME to false
            expense && !income -> TxType.EXPENSE to false
            else -> null
        }
    }

    // ---------- 金额 ----------

    private val amountToken = Regex("""(?:人民币|RMB|¥|￥)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:元|圆|人民币|RMB)?""")

    /** 这些词出现在金额**之前**时，说明它讲的是余额/额度而不是本次交易金额 */
    private val balanceContexts = listOf(
        "余额", "可用", "剩余", "额度", "限额", "欠款", "当前", "账户余额", "存款",
    )

    /** 这些词出现在金额之前时，说明它就是本次交易金额 */
    private val ledgerContexts = incomeWords + expenseWords + listOf("交易金额", "发生额", "金额", "人民币")

    /**
     * 提取交易金额（分）。三件事必须同时成立才算候选：
     * ① 有货币标记（¥/人民币/RMB/元/圆）或带小数点——把 `10月8日 12:30` 这类日期时间排除掉；
     * ② 前面的上下文不是"余额/可用/额度"——把 `余额5000.00元` 排除掉；
     * ③ 金额在合理范围内（0 < x ≤ ¥1000 万）。
     * 优先返回带"消费/入账"等标签的那个；没有标签时退而取第一个非余额金额。
     */
    fun extractAmountCents(text: String): Long? {
        var labeled: Long? = null
        var plain: Long? = null
        for (m in amountToken.findAll(text)) {
            val raw = m.groupValues[1]
            val matched = m.value
            val before = text.substring(maxOf(0, m.range.first - 10), m.range.first)
            // 币种标记必须从**整个匹配**判断：正则把 `人民币`/`¥`/`元` 都含在匹配区间内，
            // 只看区间外会漏掉"人民币50元""¥50元"这类整数写法（余额/日期仍按 before 排除）
            val hasCurrency = matched.contains("¥") || matched.contains("￥") ||
                matched.contains("人民币") || matched.uppercase().contains("RMB") ||
                matched.contains("元") || matched.contains("圆")
            // 带小数点的裸数字也认为是金额（银行动账短信里日期不会是 10.8 这种写法）
            if (!hasCurrency && !raw.contains('.')) continue
            val cents = Money.parseCents(raw) ?: continue
            if (cents <= 0 || cents > NotificationRules.MAX_AMOUNT_CENTS) continue
            if (balanceContexts.any { before.contains(it) }) continue
            if (ledgerContexts.any { before.contains(it) }) {
                if (labeled == null) labeled = cents
            } else if (plain == null) {
                plain = cents
            }
        }
        return labeled ?: plain
    }

    /** 卡尾号：尾号1234 / 末四位1234 / ****1234 */
    private val cardTailRegex = Regex("""(?:尾号|末四位|末4位|后四位|卡号后四位)\s*[*xX]{0,6}\s*(\d{4})|[*xX]{3,}\s*(\d{4})""")

    fun extractCardTail(text: String): String? {
        val m = cardTailRegex.find(text) ?: return null
        return (m.groupValues[1].ifBlank { m.groupValues[2] }).takeIf { it.length == 4 }
    }

    private val merchantPatterns = listOf(
        Regex("""(?:商户名称|特约商户|商户)[：:]\s*([^\s，,。;；]{2,20})"""),
        Regex("""在\s*([^\s，,。;；]{2,20}?)\s*(?:消费|刷卡|支付)"""),
        Regex("""(?:向|付给|转账给)\s*([^\s，,。;；]{2,20}?)\s*(?:付款|转账|支付)"""),
        Regex("""(?:支出|消费|收入|存入)\s*[(（]([^)）]{1,20})[)）]"""),
        Regex("""(?:渠道|支付方式)[：:]\s*([^\s，,。;；]{2,20})"""),
    )

    /** 商户/渠道："支出(消费)"这类文本会提取出"消费"这种动词，这里显式挡掉 */
    fun extractMerchant(text: String): String {
        for (p in merchantPatterns) {
            val v = p.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (v.isEmpty()) continue
            if (v in incomeWords || v in expenseWords) continue
            if (v.all { it.isDigit() }) continue
            return v.take(30)
        }
        return ""
    }

    // ---------- 统一产出 ----------

    data class BankLedger(
        val bankName: String,
        val type: TxType,
        val amountCents: Long,
        val isRefund: Boolean,
        val cardTail: String?,
        val merchant: String,
        /** 账户名："工商银行(1234)"，无尾号时为银行名 */
        val accountLabel: String,
        /** 去重键的来源段：短信 = `SMS:工商银行`，银行 App = 包名 */
        val sourceKey: String,
        /** 展示名："工商银行短信" / "工商银行通知" */
        val displayLabel: String,
        val note: String,
        val ruleName: String,
        val dedupKey: String,
    )

    /**
     * 银行动账文本解析主入口（短信与银行 App 推送共用）。
     * [sourceKey] 用于去重键前缀与来源记录，[channelSuffix] 是通道后缀（"短信"/"通知"）。
     * [fallbackBankName] 是正文没有银行签名时的兜底（银行 App 推送可用包名映射）。
     * 返回 null = 不是一笔可记账的动账（验证码/营销/提醒/方向不明/无金额）。
     */
    fun parse(
        text: String,
        sourceKey: String,
        channelSuffix: String,
        eventTime: Long,
        fallbackBankName: String? = null,
    ): BankLedger? {
        val normalized = NotificationRules.normalize(text)
        if (normalized.isBlank()) return null
        // 尾号必须在遮罩**之前**取——遮罩会把作为金额干扰源的四位数字抹掉，之后就取不到了
        val cardTail = extractCardTail(normalized)
        val masked = NotificationRules.maskCardTails(normalized)

        val bankName = detectBankName(masked) ?: fallbackBankName ?: run {
            AppLog.d("Sms", "skip (no bank signature) [$masked]")
            return null
        }
        if (isNoise(masked) || NotificationRules.isNoise(masked)) {
            AppLog.d("Sms", "skip (noise) bank=$bankName [$masked]")
            return null
        }
        val (type, isRefund) = detectDirection(masked) ?: run {
            AppLog.d("Sms", "skip (direction unclear) bank=$bankName [$masked]")
            return null
        }
        val amountCents = extractAmountCents(masked) ?: run {
            AppLog.d("Sms", "skip (no amount) bank=$bankName [$masked]")
            return null
        }
        val merchant = extractMerchant(masked)
        val accountLabel = if (cardTail != null) "$bankName($cardTail)" else bankName
        val kind = when {
            isRefund -> "退款"
            type == TxType.INCOME -> "入账"
            else -> "支出"
        }
        return BankLedger(
            bankName = bankName,
            type = type,
            amountCents = amountCents,
            isRefund = isRefund,
            cardTail = cardTail,
            merchant = merchant,
            accountLabel = accountLabel,
            sourceKey = sourceKey,
            displayLabel = "$bankName$channelSuffix",
            note = masked.take(200),
            ruleName = "$bankName$channelSuffix·$kind",
            dedupKey = NotificationRules.dedupKey(sourceKey, type, isRefund, amountCents, eventTime),
        )
    }
}
