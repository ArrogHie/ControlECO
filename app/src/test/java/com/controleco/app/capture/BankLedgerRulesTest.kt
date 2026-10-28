package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 银行动账解析测试（短信与银行 App 推送共用 BankLedgerRules）。
 *
 * 样本按真实银行短信/推送的通用形态构造，每个用例对应一类**会错账**的输入：
 * 日期被当金额、余额被当交易金额、千分位截断、营销短信误入库、方向不明。
 * 改动规则时这些断言就是护栏（见 docs/自动记账捕获机制.md）。
 */
class BankLedgerRulesTest {

    private val smsTime = 1_700_000_000_000L

    private fun parseSms(body: String, sender: String = "95588") =
        BankLedgerRules.parse(body, "SMS:工商银行", "短信", smsTime)

    private fun parseApp(pkg: String, body: String) =
        BankLedgerRules.parse(body, pkg, "通知", smsTime)

    // ---------- 号码闸门 ----------

    @Test
    fun `known bank sender numbers are recognized`() {
        assertTrue(BankLedgerRules.isBankSender("95588"))
        assertTrue(BankLedgerRules.isBankSender("95533"))
        assertTrue(BankLedgerRules.isBankSender("+86 95555"))
        assertFalse(BankLedgerRules.isBankSender("10690329012345"))
        assertFalse(BankLedgerRules.isBankSender("13800138000"))
    }

    // ---------- 支出 ----------

    @Test
    fun `icbc expense sms with balance after`() {
        val r = parseSms("【工商银行】您尾号1234的储蓄卡10月8日12:30消费人民币50.00元，余额5000.00元")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(5000L, r.amountCents)
        assertEquals("工商银行", r.bankName)
        assertEquals("1234", r.cardTail)
        assertEquals("工商银行(1234)", r.accountLabel)
        assertEquals("工商银行短信·支出", r.ruleName)
        assertFalse(r.isRefund)
    }

    @Test
    fun `balance before the amount does not win`() {
        // "取第一个金额"的实现会在这里取到余额 10000.00；必须按上下文区分
        val r = parseSms("【建设银行】余额10000.00元，您尾号5678账户消费123.45元")!!
        assertEquals(12_345L, r.amountCents)
        assertEquals("建设银行", r.bankName)
    }

    @Test
    fun `real cmb quick payment notice carrying wechat channel hint`() {
        // 真实样本（2026-10-21 用户实测，与微信转账 ¥200 被记成两笔就是这一条）：
        // 招行动账通知没有"尾号"，渠道写在【财付通-微信支付-微信转账】里——
        // 这条线索是「支付渠道 ⇄ 银行」去重的依据（见 CrossChannelDedup.bankTextMentionsPayChannel）
        val body = "招商银行 您账户2532于10月21日16:54在【财付通-微信支付-微信转账】发生快捷支付扣款，人民币200.00"
        val r = BankLedgerRules.parse(body, "cmb.pb", "通知", smsTime, "招商银行")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(20_000L, r.amountCents)
        assertEquals("招商银行", r.bankName)
        assertEquals("招商银行通知·支出", r.ruleName)
        assertFalse(r.isRefund)
        // 卡尾号缺失（样本里是"您账户2532"，不是"尾号2532"）；渠道线索必须留在正文里
        assertNull(r.cardTail)
        assertTrue(r.note.contains("微信支付"))
        assertTrue(CrossChannelDedup.bankTextMentionsPayChannel(r.note, NotificationRules.WECHAT))
    }

    @Test
    fun `thousand separator is parsed correctly`() {
        // 不支持千分位的实现会在这里解析成 234.56（真实错值）
        val r = parseSms("【招商银行】您尾号1234卡10月8日消费人民币1,234.56元")!!
        assertEquals(123_456L, r.amountCents)
    }

    @Test
    fun `integer amounts with currency markers are parsed`() {
        // CR-006：币种前缀/后缀曾包含在正则完整匹配里，导致整数金额被当成"没有货币标记"丢掉
        val cases = listOf(
            "【工商银行】您尾号1234卡消费人民币50元" to 5000L,
            "【工商银行】您尾号1234卡消费¥50元" to 5000L,
            "【工商银行】您尾号1234卡消费50元" to 5000L,
            "【工商银行】您尾号1234卡消费人民币1,234元" to 123_400L,
            "【工商银行】您尾号1234卡消费￥1,234元" to 123_400L,
        )
        for ((body, expected) in cases) {
            assertEquals(body, expected, parseSms(body)?.amountCents)
        }
    }

    @Test
    fun `integer balance and date never win over the transaction amount`() {
        // 修复整数金额时不能把余额/日期当交易金额
        val r = parseSms("【建设银行】余额10000元，您尾号5678账户10月8日12:30消费123元")!!
        assertEquals(12_300L, r.amountCents)
        val r2 = parseSms("【招商银行】您尾号1234卡10月8日消费人民币50元，余额5000元")!!
        assertEquals(5000L, r2.amountCents)
    }

    @Test
    fun `balance-only integer message is not recorded`() {
        assertNull(parseSms("【工商银行】您尾号1234卡余额人民币5,000元，请登录手机银行查看"))
    }

    @Test
    fun `date and time are not taken as amount`() {
        val r = parseSms("【中国银行】您尾号1234卡2026年10月8日12:30消费88.00元")!!
        assertEquals(8800L, r.amountCents)
    }

    @Test
    fun `expense with merchant`() {
        val r = parseSms("【工商银行】您尾号1234卡10月8日在星巴克消费35.00元")!!
        assertEquals(3500L, r.amountCents)
        assertEquals("星巴克", r.merchant)
    }

    @Test
    fun `expense parenthesized category is not treated as merchant`() {
        val r = parseSms("【工商银行】您尾号1234卡10月8日支出(消费)50.00元")!!
        assertEquals(5000L, r.amountCents)
        assertEquals("", r.merchant)
    }

    // ---------- 收入 / 退款 ----------

    @Test
    fun `salary income with thousand separators`() {
        val r = parseSms("【工商银行】您尾号1234账户10月8日收入(工资)人民币8,000.00元，余额12,345.67元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(800_000L, r.amountCents)
        assertFalse(r.isRefund)
        assertEquals("", r.merchant)
    }

    @Test
    fun `refund is income with refund flag`() {
        val r = parseSms("【中国银行】您尾号1234卡支付宝退款50.00元")!!
        assertEquals(TxType.INCOME, r.type)
        assertTrue(r.isRefund)
        assertEquals(5000L, r.amountCents)
    }

    @Test
    fun `card tail with asterisks`() {
        val r = parseSms("【招商银行】您尾号****8888信用卡消费20.00元")!!
        assertEquals("8888", r.cardTail)
    }

    // ---------- 噪声（银行营销/通知类短信占绝对多数） ----------

    @Test
    fun `verification code is filtered`() {
        assertNull(parseSms("【工商银行】验证码123456，请勿泄露给他人"))
    }

    @Test
    fun `credit limit marketing is filtered`() {
        assertNull(parseSms("【兴业银行】您尾号1234信用卡可用额度提升至50000元，点击申请提额"))
    }

    @Test
    fun `installment marketing is filtered`() {
        assertNull(parseSms("【中信银行】您尾号1234信用卡可办理账单分期，手续费率低至0.3%"))
    }

    @Test
    fun `consumption lottery marketing is filtered`() {
        // 含"消费"与"100元"，若无噪声闸门会被记成 ¥100 支出
        assertNull(parseSms("【工商银行】您尾号1234卡消费满100元可参与抽奖"))
    }

    @Test
    fun `bill reminder is filtered`() {
        assertNull(parseSms("【招商银行】您尾号1234信用卡账单已出，还款日10月25日，应还金额5000元"))
    }

    @Test
    fun `real expense with promo tail is still recorded`() {
        // 有交易确认短语时促销词不否决（真实短信偶尔带"享优惠"尾巴）
        val r = parseSms("【工商银行】您尾号1234卡已支付50.00元，享优惠")!!
        assertEquals(5000L, r.amountCents)
    }

    // ---------- 方向不明 / 无金额 ----------

    @Test
    fun `ambiguous direction is dropped`() {
        assertNull(parseSms("【工商银行】您尾号1234卡10月8日收入50.00元，同日支出30.00元"))
    }

    @Test
    fun `no bank signature is dropped`() {
        assertNull(parseSms("您尾号1234卡消费50.00元", sender = "10690329012345"))
    }

    @Test
    fun `no amount is dropped`() {
        assertNull(parseSms("【工商银行】您尾号1234卡有一笔动账，请登录手机银行查看"))
    }

    @Test
    fun `transfer notification without direction is dropped`() {
        assertNull(parseSms("【工商银行】您尾号1234卡发生一笔交易"))
    }

    // ---------- 银行 App 推送（与短信共用解析） ----------

    @Test
    fun `bank app push notification is parsed with its own source`() {
        val r = parseApp("com.icbc", "【工商银行】您尾号1234的账户于10月8日向商户支出50.00元")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(5000L, r.amountCents)
        assertEquals("com.icbc", r.sourceKey)
        assertEquals("工商银行通知·支出", r.ruleName)
        assertEquals("工商银行通知", r.displayLabel)
    }

    @Test
    fun `bank app push uses its own dedup key prefix`() {
        val sms = parseSms("【工商银行】您尾号1234卡消费50.00元")!!
        val app = parseApp("com.icbc", "【工商银行】您尾号1234卡消费50.00元")!!
        assertTrue(sms.dedupKey.startsWith("SMS:"))
        assertTrue(app.dedupKey.startsWith("com.icbc|"))
    }

    @Test
    fun `bank app push without signature falls back to package mapping`() {
        // 银行 App 推送常省略【银行名】签名，此时包名是唯一的银行线索
        val r = BankLedgerRules.parse(
            "您尾号1234的账户于10月04日17:33成功入账100.00元，可用余额200.00元",
            "com.yitong.mbank.psbc", "通知", smsTime,
            fallbackBankName = BankLedgerRules.bankNameOfPackage("com.yitong.mbank.psbc"),
        )!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(10_000L, r.amountCents)
        assertEquals("邮储银行", r.bankName)
    }

    @Test
    fun `unknown package without signature is not parsed`() {
        // 既无签名、包名也不在银行短名单里：不能仅凭"尾号+金额"就记账（可能是任意应用的通知）
        assertNull(
            BankLedgerRules.parse(
                "您尾号1234的账户消费50.00元", "com.example.app", "通知", smsTime,
                fallbackBankName = BankLedgerRules.bankNameOfPackage("com.example.app"),
            ),
        )
    }

    // ---------- 真实样本（2026-10-27 真机捕获日志：同一笔 ¥36.40 被记了两次） ----------

    /** 真实短信正文（工行，盒马消费）：注意「尾号卡」后面没有四位数字 */
    private val realIcbcSms =
        "尾号卡10月27日09:35支出(消费支付宝-上海盒马网络科技有限公司)36.40元，余额7.62元。【工商银行】"

    /** 同一条短信在系统短信 App 通知里的文本（title + text + bigText 拼接后的形态） */
    private val realMmsNotification =
        "工商银行 $realIcbcSms 工商银行: $realIcbcSms $realIcbcSms"

    @Test
    fun `真实样本_工行盒马支出3640`() {
        val r = parseSms(realIcbcSms)!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(3640L, r.amountCents)          // 余额 7.62 不抢金额
        assertEquals("工商银行", r.bankName)
        assertEquals("工商银行短信·支出", r.ruleName)
        assertEquals("消费支付宝-上海盒马网络科技有限公司", r.merchant)
        // 「尾号卡」后面没有四位数字 → 取不到尾号（已知边界：尾号为 null）
        assertNull(r.cardTail)
        assertEquals("工商银行", r.accountLabel)
    }

    @Test
    fun `真实样本_短信App通知的文本同样可解析_但包名不在银行白名单`() {
        // 解析层面一致（这就是当初被记成第二笔的原因）
        val r = BankLedgerRules.parse(realMmsNotification, "com.android.mms", "通知", smsTime)!!
        assertEquals(3640L, r.amountCents)
        assertEquals("工商银行", r.bankName)
        // 但 com.android.mms 不是银行 App 包名 → 2026-10-27 起该通知不再进入银行通道
        assertNull(BankLedgerRules.bankNameOfPackage("com.android.mms"))
    }

    @Test
    fun `真实样本_两条通道的去重键因来源键不同而不同`() {
        // 旧行为：两条都能落库（唯一索引不冲突）→ 就是用户看到的 transaction 26 / 27
        val sms = parseSms(realIcbcSms)!!
        val mms = BankLedgerRules.parse(realMmsNotification, "com.android.mms", "通知", smsTime)!!
        assertTrue(sms.dedupKey.startsWith("SMS:工商银行|EXPENSE|3640|"))
        assertTrue(mms.dedupKey.startsWith("com.android.mms|EXPENSE|3640|"))
        assertNotEquals(sms.dedupKey, mms.dedupKey)
    }
}
