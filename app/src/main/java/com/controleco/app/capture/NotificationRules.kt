package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money

/**
 * 阶段二通知规则引擎：对感兴趣包名的通知做收款/动账/退款解析。
 * 规则刻意保持简单（关键词定方向 + 正则提金额），未匹配的通知不会丢——
 * 原始文本已进入捕获日志，拿真实样本对着日志扩规则即可（见 RealFileParseCheck 的用法）。
 *
 * 真实样本驱动的格式覆盖：
 * - 微信收款：text=微信支付收款0.01元（朋友到账）/ 收款入账：10.00元 / bigText=收款金额¥0.01
 * - 微信支出：text=已支付¥12.80（¥ 前缀、关键词后无数字，走兜底）
 * - 微信自动扣费：text=[11条]微信支付: 已扣费¥6.50（折叠计数前缀可变/可缺省；
 *   "扣费"是独立的支出动词，此前只认"扣款"导致整条通知被判「方向不明」直接漏记）
 * - 微信退款：text=[2条]微信支付: 退款到账通知（折叠汇总常无金额，明细行见捕获日志，无金额不入队）
 * - 支付宝支出：text=你有一笔14.90元的支出，点此查看详情。（金额在关键词前，走反向正则）
 * - 支付宝退款：text=你收到一笔14.90元退款，点此查看账单详情！（退款→收入，打退款标记）
 * - 云闪付：成功入账100.00元 / 消费50.00元
 *
 * 2026-10-13 加固（自测发现的错账样例逐条修正，见 docs/自动记账捕获机制.md）：
 * 1. 文本归一化：全角数字转半角、￥→¥、剥离折叠通知的「[N条]/共N条」计数前缀；
 * 2. 金额捕获组支持千分位——修复「已支付¥6,064.76」被兜底正则抓成 ¥6.00 的**静默错账**；
 * 3. 卡尾号遮罩：避免「尾号7619」「储蓄卡(8920)」里的四位数字被金额正则抓走；
 * 4. 金额上限：超过 ¥1000 万视为误抓（订单号/手机号），宁可漏记不记错账；
 * 5. 噪声黑名单：只在**没有任何交易确认短语**时一票否决（真实通知常带"使用优惠券省 3 元"的
 *    营销尾巴，不能连交易一起丢）。
 */
object NotificationRules {

    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val UNIONPAY = "com.unionpay"
    const val MEITUAN_TAKEOUT = "com.sankuai.meituan.takeoutnew"

    val knownPackages = setOf(WECHAT, ALIPAY, UNIONPAY)

    /**
     * 电商/外卖收银台渠道（京东、淘宝、抖音、美团…）。
     *
     * 为什么单独一组：这些都是真实付款渠道，但**营销与物流推送极其密集**，文案随版本变化大。
     * 所以它们比支付三包多一道**硬闸门**——必须命中明确的交易确认短语才解析
     * （"支付成功¥59.00"✓ / "您有一笔订单待支付¥59.00"✗），
     * 把"待支付/待付款/订单已提交"这类**未完成状态**挡在外面。
     * 包名清单只做"值得看一眼"的筛选，正文确认短语才是闸门。
     */
    val checkoutPackages: Map<String, String> = mapOf(
        "com.jingdong.app.mall" to "京东",
        "com.jd.jr.jdpay" to "京东支付",
        "com.jd.jrapp" to "京东金融",
        "com.taobao.taobao" to "淘宝",
        "com.tmall.wireless" to "天猫",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.ss.android.ugc.live" to "抖音",
        "com.sankuai.meituan" to "美团",
        // 自测修正：美团外卖的真实包名是 takeoutnew（此前误写成 takeaway——不存在的包名，
        // 等于这个渠道的通知/页面两条路都被静默排除，见开发日志同日条目）
        MEITUAN_TAKEOUT to "美团外卖",
        "me.ele" to "饿了么",
    )

    fun isCheckoutPackage(packageName: String): Boolean = packageName in checkoutPackages

    /** 是否为本引擎负责的来源：支付三包 + 电商/外卖收银台 */
    private fun isKnownSource(packageName: String): Boolean =
        packageName in knownPackages || packageName in checkoutPackages

    /** 短信来源前缀（见 CaptureCenter.onSmsReceived）：来源键形如 `SMS:工商银行` */
    const val SMS_SOURCE_PREFIX = "SMS:"

    fun isInterested(packageName: String): Boolean =
        packageName in knownPackages || packageName in checkoutPackages

    /** 全渠道统一金额捕获组：优先匹配带千分位的写法（6,064.76），否则普通写法（0.01 / 100） */
    private const val AMT = "([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)"

    /** 金额上限（分）：¥1000 万，超过视为误抓（订单号/卡号/手机号被当金额） */
    const val MAX_AMOUNT_CENTS = 1_000_000_000L

    /**
     * 无金额的退款到账提醒（微信折叠通知"退款到账通知"常不带金额，实测样本：
     * title=微信支付 text=退款到账通知）——无法入队，转"退款待处理"流程让用户选被退款的账单。
     * 带金额的退款在 parse() 里已正常入队，不会走到这里。
     */
    fun isAmountlessRefundNotice(packageName: String, title: String, text: String): Boolean =
        packageName in knownPackages &&
            ((title + text).contains("退款") || (title + text).contains("退回"))

    fun sourceLabel(packageName: String): String = when {
        packageName.startsWith(SMS_SOURCE_PREFIX) -> packageName.removePrefix(SMS_SOURCE_PREFIX) + "短信"
        packageName == WECHAT -> "微信"
        packageName == ALIPAY -> "支付宝"
        packageName == UNIONPAY -> "云闪付"
        checkoutPackages.containsKey(packageName) -> checkoutPackages.getValue(packageName)
        // 银行 App 推送（动账通知）走 BankLedgerRules 的签名闸门，包名 → 银行名
        BankLedgerRules.bankNameOfPackage(packageName) != null ->
            BankLedgerRules.bankNameOfPackage(packageName) + "通知"
        else -> packageName
    }

    /**
     * 来源显示名（列表/卡片用）：已知来源给品牌名；**未知来源退回规则名前缀**——
     * 未收录短名单的银行 App 推送只有包名（如 com.some.bank），直接显示包名对用户毫无意义，
     * 规则名（"工商银行通知·支出"）的前缀才是可读的来源。
     */
    fun sourceLabelOrRule(packageName: String, ruleName: String): String {
        val label = sourceLabel(packageName)
        if (label != packageName) return label
        return ruleName.substringBefore('·').ifBlank { label }
    }

    /** 去重键：来源包名 + 方向（退款加 R）+ 金额分 + 分钟级时间。通知/页面/短信三条路径共用同一构造 */
    fun dedupKey(packageName: String, type: TxType, isRefund: Boolean, amountCents: Long, timeMillis: Long): String =
        "$packageName|$type${if (isRefund) "|R" else ""}|$amountCents|${timeMillis / 60_000}"

    // ---------- 文本归一化与遮罩 ----------

    /** 折叠计数前缀 `[11条]` / `［1条］` / `（11条消息）`：数字与是否存在都不固定，一律按"计数标记"剥掉 */
    private val bracketedCount = Regex("""[\[［【(（]\s*\d+\s*条(?:消息|通知)?\s*[\]］】)）]""")
    private val countSuffix = Regex("""(?:共|新)\s*\d+\s*条(?:消息|通知)?""")
    private val appCountPrefix = Regex("""(微信支付|微信收款|微信|支付宝|云闪付)\s*[:：]?\s*\d+\s*条""")
    private val whitespace = Regex("""\s+""")

    /**
     * 归一化：全角数字→半角、￥→¥、全角句点→半角句点、全角空格→空格，
     * 并剥离折叠通知的计数前缀（`[2条]微信支付: …` → `微信支付: …`），最后折叠连续空白。
     *
     * 刻意**不**把全角标点「，。」转成半角：反向金额正则靠 `[^，。\d]` 限定关键词距离，
     * 转换会让它跨越逗号去够更远的关键词，反而抓到不相干的金额。
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when {
                    ch in '０'..'９' -> '0' + (ch - '０')
                    ch == '￥' -> '¥'
                    ch == '．' -> '.'
                    ch == '　' -> ' '
                    else -> ch
                },
            )
        }
        var s = bracketedCount.replace(sb, " ")
        s = countSuffix.replace(s, " ")
        s = appCountPrefix.replace(s, "\$1 ")
        return whitespace.replace(s.trim(), " ").trim()
    }

    private val cardTailWord = Regex("""尾号\s*\d{4,}""")
    private val cardTailBracket = Regex("""【([^】]*?)[(（]\s*\d{4}\s*[)）][^】]*】""")
    private val cardTailParen = Regex("""(储蓄卡|信用卡|借记卡|银行卡|账户|卡)\s*[(（]\s*\d{4}\s*[)）]""")

    /**
     * 卡尾号遮罩：卡号后四位不应参与金额解析。顺序 = 先剥【中国银行储蓄卡(8920)】的括号，
     * 再把「储蓄卡(8920)」变成「储蓄卡(****)」、把「尾号7619」变成「尾号****」。
     * 括号外的文字保留（支付方式识别仍需读"中国银行储蓄卡"）。
     */
    fun maskCardTails(text: String): String {
        var s = cardTailBracket.replace(text, "【\$1】")
        s = cardTailParen.replace(s, "\$1(****)")
        s = cardTailWord.replace(s, "尾号****")
        return s
    }

    // ---------- 噪声过滤 ----------

    /**
     * 交易确认短语：出现即认为文本确实在报告一笔已完成的交易，此时黑名单放行。
     *
     * 刻意**不含**裸的"已到账"：优惠券/红包类推送也写"已到账"
     * （"限时优惠券已到账，点击领取 20元券"会被解析成 ¥20 收入），
     * 把它当确认短语会放过一整类营销文案。
     */
    private val confirmPhrases = listOf(
        "已支付", "支付成功", "付款成功", "支付完成", "交易成功", "已付款",
        "扣款成功", "已扣款", "已扣费", "扣费成功", "消费成功", "收款成功", "已收款", "收款到账",
        "入账成功", "成功入账", "转账到账", "收到转账",
        "退款到账", "退款成功", "已退款",
        // 免密/自动扣款类（真实样本："你有一笔2.98元的免密/自动扣款支付，点击领取6个支付宝积分。"）——
        // 这句里的"点击领取"是营销词，但交易本身是真的，必须让确认短语放行
        "扣款支付", "免密支付", "自动扣款", "自动扣费",
    )

    /**
     * 噪声关键词：营销/额度/物流/安全提醒类。只在缺失确认短语时生效——
     * 真实通知常带"使用优惠券省 3 元"的尾巴，有确认短语就放行，避免把真交易一起丢掉。
     */
    private val noiseKeywords = listOf(
        "验证码", "动态码", "登录验证", "登录成功", "注册成功", "开通成功",
        "账单日", "最低还款", "还款提醒", "即将到期", "已逾期", "逾期提醒",
        "可用额度", "信用额度", "剩余额度", "提额", "额度已更新",
        "已发货", "待收货", "已签收", "物流", "配送中", "包裹",
        "领券", "优惠券", "消费券", "抵用券", "待领取", "点击领取", "限时领取", "立即领取",
        "积分到账", "积分即将", "会员权益", "开通会员", "立即申请", "点击查看详情",
        "提现提醒", "尚未处理", "正在处理中", "预约", "投票", "签到",
    )

    fun isNoise(text: String): Boolean =
        confirmPhrases.none { text.contains(it) } && noiseKeywords.any { text.contains(it) }

    /**
     * 微信红包类通知：不自动入账。
     *
     * 红包已改为“领取页弹卡由用户确认”，通知侧也不能再把它当普通收款/到账：
     * “红包到账5.20元”会命中收入金额正则，“你领取了…红包”会命中方向词，
     * 若不拦截就会绕过页面确认直接生成账目。只拦明确的红包语义，不影响普通转账/收款通知。
     */
    private val redPacketNoticeMarkers = listOf(
        "红包到账", "收到红包", "你领取了", "对方领取了", "红包已存入", "红包存入",
        "开红包", "红包封面", "红包收款", "领取红包",
    )

    fun isRedPacketNotice(text: String): Boolean =
        text.contains("红包") && redPacketNoticeMarkers.any { text.contains(it) }

    // ---------- 折叠摘要与「金额待补」（方向可辨、金额缺失） ----------

    private val groupedSummary = Regex(
        """[\[［【(（]\s*\d+\s*条\s*[\]］】)）]|共\s*\d+\s*条|\d+\s*条(?:新)?(?:消息|通知)?""",
    )

    private val ledgerVerbs = listOf(
        "已支付", "支付成功", "付款", "支出", "消费", "扣款", "扣费", "收款", "到账", "入账", "退款", "退回", "收入",
    )

    /**
     * "钱进账"的方向短语。
     *
     * `向你付款`/`付款给你`/`转账给你`/`收到一笔` 是收款码、转账入账的常见说法：
     * 真实样本（2026-10-18）`收钱码 倩蕾通过扫码向你付款2.00元` 只命中裸的"付款"，
     * 曾被判成支出。这些短语方向明确，必须优先于泛化的"付款"判定。
     */
    private val receivePhrases = listOf(
        "向你付款", "付款给你", "转账给你", "向你转账", "收到一笔",
    )

    private val incomePhrases = listOf(
        "收款成功", "已收款", "收款到账", "已到账", "入账成功", "成功入账", "转账到账", "收到转账",
    ) + receivePhrases
    private val expensePhrases = listOf(
        "已支付", "支付成功", "付款成功", "支付完成", "交易成功", "已付款", "扣款成功", "已扣款", "扣费成功",
        "已扣费", "消费成功",
    )

    /** 文本是否像折叠摘要（`[2条]`/`共3条`/`2条新消息`）；摘要自身常不含金额，需要子通知补全 */
    fun looksLikeGroupedSummary(text: String): Boolean = groupedSummary.containsMatchIn(text)

    /** 分组候选判定：像一条**账务明细**（同时有方向动词和可解析金额），用于从兄弟通知里挑出真正那笔 */
    fun looksLikeLedgerDetail(text: String): Boolean {
        if (text.isBlank() || isNoise(text)) return false
        val masked = maskCardTails(normalize(text))
        val hasAmount = reversedAmount.containsMatchIn(masked) ||
            currencyAmount.containsMatchIn(masked) ||
            incomeAmount.containsMatchIn(masked) ||
            expenseAmount.containsMatchIn(masked)
        return hasAmount && ledgerVerbs.any { masked.contains(it) }
    }

    /**
     * 方向可辨、金额缺失的通知 → 通知栏「输入金额」补录。
     * 判定刻意收紧：必须命中明确的**交易确认短语**，否则不打扰用户（服务通知、账单提醒一律不弹）。
     * 退款不走这里（金额常由原账单承载，用既有的"退款待处理"流程）。
     */
    data class DirectionHint(
        val type: TxType,
        val sourceLabel: String,
        val ruleName: String,
        val note: String,
    )

    fun parseDirectionOnly(
        packageName: String,
        title: String,
        text: String,
        bigText: String = "",
    ): DirectionHint? {
        if (!isKnownSource(packageName)) return null
        val raw = listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString(" ")
        // 折叠摘要自身没有明细，交给分组补全；补不出就不该让用户对着"[2条]"手输金额。
        // 注意要在归一化**之前**判断——归一化会把「[2条]」这个标记本身剥掉。
        if (looksLikeGroupedSummary(raw)) return null
        val body = normalize(raw)
        if (body.isBlank()) return null
        if (isNoise(body)) return null
        if (isRedPacketNotice(body)) {
            AppLog.d("Ntf", "skip red packet notice (user confirmation only) pkg=$packageName body=$body")
            return null
        }
        if (body.contains("退款") || body.contains("退回")) return null

        val income = incomePhrases.any { body.contains(it) }
        val expense = expensePhrases.any { body.contains(it) }
        val type = when {
            income && !expense -> TxType.INCOME
            expense && !income -> TxType.EXPENSE
            else -> return null
        }
        val label = sourceLabel(packageName)
        return DirectionHint(
            type = type,
            sourceLabel = label,
            ruleName = "$label·${if (type == TxType.INCOME) "待补收款" else "待补支出"}",
            note = body.take(200),
        )
    }

    // ---------- 金额与方向 ----------

    /**
     * 正向：关键词后跟金额且金额带"元"（收款0.01元 / 消费50.00元）。
     * 必须有"元"字收尾——关键词后的营销文案常带裸数字（真实样本：
     * "…自动扣款支付，点击领取6个支付宝积分"曾把 6 当成金额；"支付成功，领取6个积分"同理），
     * ¥ 前缀的金额（已支付¥12.80）交给 currencyAmount 兜底。
     */
    private val incomeAmount =
        Regex("""(?:收款|到账|入账|收入|向你付款|付款给你|转账给你|收到一笔)[^\d￥¥.]{0,8}${AMT}元""")
    private val expenseAmount = Regex("""(?:已支付|支付成功|支出|消费|付款|扣款|扣费)[^\d￥¥.]{0,8}${AMT}元""")
    private val refundAmount = Regex("""(?:退款|退回)[^\d￥¥.]{0,8}${AMT}元""")

    /**
     * 反向：金额在前（你有一笔14.90元的支出 / 你收到一笔14.90元退款 / 一笔100元的收入）。
     * 关键词前允许最多 8 个非标点非数字的修饰字（真实样本"有一笔2.98元的免密/自动扣款支付"
     * 的"的免密/自动"有 6 个字），并以标点（，。）为硬边界——营销文案在标点之后，
     * 不会被反向正则跨越（"支出，领1元红包"仍匹配到 0.01 而不是 1）。
     */
    private val reversedAmount =
        Regex("""$AMT\s*元[^，。\d]{0,8}的?\s*(?:支出|消费|付款|扣款|扣费|收款|收入|到账|入账|退款|退回)""")

    /** 兜底：文本里直接出现 ¥ 金额（已支付¥12.80 / 收款金额¥0.01） */
    private val currencyAmount = Regex("""[￥¥]\s*$AMT""")

    data class ParsedNotification(
        val type: TxType,
        val amountCents: Long,
        val sourceLabel: String,
        val ruleName: String,
        val note: String,
        val dedupKey: String,
        /** 退款到账（原支出已由支出通知/账单承载），入账时打 REFUND_INCOME 标记 */
        val isRefund: Boolean = false,
    )

    fun parse(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        postTime: Long,
    ): ParsedNotification? {
        if (!isKnownSource(packageName)) return null
        // 归一化 + 卡尾号遮罩后才做匹配：规则只面对干净输入
        val body = maskCardTails(
            normalize(sequenceOf(title, text, bigText).filter { it.isNotBlank() }.joinToString(" ")),
        ).trim()
        if (body.isBlank()) return null

        if (isNoise(body)) {
            AppLog.d("Ntf", "skip noise pkg=$packageName body=$body")
            return null
        }
        if (isRedPacketNotice(body)) {
            AppLog.d("Ntf", "skip red packet notice (user confirmation only) pkg=$packageName body=$body")
            return null
        }

        // 电商/外卖收银台：额外要求明确的交易确认短语，挡住"待支付/待付款/订单已提交"等未完成状态
        if (packageName in checkoutPackages && confirmPhrases.none { body.contains(it) }) {
            AppLog.d("Ntf", "checkout notice without confirmation phrase, skip pkg=$packageName body=$body")
            return null
        }

        val refundHit = body.contains("退款") || body.contains("退回")
        val receiveHit = receivePhrases.any { body.contains(it) }
        val incomeHit = body.contains("收款") || body.contains("到账") || body.contains("入账") || body.contains("收入")
        val expenseHit = body.contains("已支付") || body.contains("支付成功") || body.contains("支出") ||
            body.contains("消费") || body.contains("付款") || body.contains("扣款") ||
            body.contains("扣费") || body.contains("支付凭证")

        val type: TxType
        val isRefund: Boolean
        when {
            // 退款优先：退款到账是收入方向（"退款到账"同时命中"到账"不能误判为普通收款）
            refundHit -> { type = TxType.INCOME; isRefund = true }
            // "向你付款/付款给你"是明确的入账方向，即使同时命中泛化的"付款"也不能判成支出
            receiveHit -> { type = TxType.INCOME; isRefund = false }
            incomeHit && !expenseHit -> { type = TxType.INCOME; isRefund = false }
            expenseHit && !incomeHit -> { type = TxType.EXPENSE; isRefund = false }
            else -> {
                AppLog.d("Ntf", "rule ambiguous (both/none direction) pkg=$packageName body=$body")
                return null
            }
        }

        val primary = when {
            isRefund -> refundAmount
            type == TxType.INCOME -> incomeAmount
            else -> expenseAmount
        }
        val fallbackAllowed = refundHit || receiveHit || (type == TxType.INCOME && incomeHit) ||
            (type == TxType.EXPENSE && expenseHit)
        // 反向优先：金额在关键词前是主流格式（"你有一笔14.90元的支出"），且能避开
        // 关键词后跟营销文案的干扰（真实样本"你有一笔0.01元的支出，领1元生活缴费红包"
        // 曾被正向正则抓走红包的"1元"，识别成 ¥1.00）
        val amountText = reversedAmount.find(body)?.groupValues?.get(1)
            ?: primary.find(body)?.groupValues?.get(1)
            ?: (if (fallbackAllowed) currencyAmount.find(body)?.groupValues?.get(1) else null)
        if (amountText == null) {
            AppLog.d(
                "Ntf",
                "rule amount not found type=$type refund=$isRefund pkg=$packageName body=$body" +
                    if (isRefund) "（退款汇总通知常无金额，属预期，不入队）" else "",
            )
            return null
        }
        val amountCents = Money.parseCents(amountText) ?: return null
        if (amountCents <= 0 || amountCents > MAX_AMOUNT_CENTS) {
            AppLog.w("Ntf", "rule amount out of range [$amountText] -> $amountCents pkg=$packageName body=$body")
            return null
        }

        val label = sourceLabel(packageName)
        return ParsedNotification(
            type = type,
            amountCents = amountCents,
            sourceLabel = label,
            ruleName = label + when {
                isRefund -> "·退款"
                type == TxType.INCOME -> "·收款/入账"
                else -> "·支出/消费"
            },
            note = body.take(200),
            dedupKey = dedupKey(packageName, type, isRefund, amountCents, postTime),
            isRefund = isRefund,
        )
    }
}
