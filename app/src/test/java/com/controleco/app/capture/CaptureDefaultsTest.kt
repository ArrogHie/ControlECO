package com.controleco.app.capture

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自动入账/待确认弹窗共用的默认分类与账户推测。 */
class CaptureDefaultsTest {

    private fun cat(id: Long, name: String, type: TxType) = CategoryEntity(id = id, name = name, type = type)
    private fun acc(id: Long, name: String) = AccountEntity(id = id, name = name)

    private val categories = listOf(
        cat(1, "餐饮", TxType.EXPENSE),
        cat(2, "购物", TxType.EXPENSE),
        cat(3, "其他", TxType.EXPENSE),
        cat(4, "工资", TxType.INCOME),
        cat(5, "其他", TxType.INCOME),
        cat(6, "交通", TxType.EXPENSE),
        cat(7, "医疗", TxType.EXPENSE),
    )

    private val accounts = listOf(
        acc(1, "现金"),
        acc(2, "微信零钱"),
        acc(3, "支付宝"),
        acc(4, "中国银行储蓄卡"),
    )

    @Test
    fun `分类优先取该类型的其他`() {
        assertEquals(3L, CaptureDefaults.categoryId(categories, TxType.EXPENSE))
        assertEquals(5L, CaptureDefaults.categoryId(categories, TxType.INCOME))
    }

    @Test
    fun `没有其他分类时取该类型第一个_并区分方向`() {
        val only = listOf(cat(1, "餐饮", TxType.EXPENSE), cat(2, "工资", TxType.INCOME))
        assertEquals(1L, CaptureDefaults.categoryId(only, TxType.EXPENSE))
        assertEquals(2L, CaptureDefaults.categoryId(only, TxType.INCOME))
    }

    @Test
    fun `没有可用分类返回空`() {
        assertNull(CaptureDefaults.categoryId(emptyList(), TxType.EXPENSE))
        assertNull(CaptureDefaults.categoryId(listOf(cat(1, "工资", TxType.INCOME)), TxType.EXPENSE))
    }

    @Test
    fun `商户关键词命中优先于其他`() {
        // 捕获的商户/备注能按关键词猜分类（用户指定：出行打车类→交通、医院类→医疗）
        assertEquals(6L, CaptureDefaults.categoryId(categories, TxType.EXPENSE, "滴滴出行"))
        assertEquals(6L, CaptureDefaults.categoryId(categories, TxType.EXPENSE, "高德打车"))
        assertEquals(7L, CaptureDefaults.categoryId(categories, TxType.EXPENSE, "市第一人民医院"))
        assertEquals(7L, CaptureDefaults.categoryId(categories, TxType.EXPENSE, "校医务室"))
        // 备注兜底：通知通道没有商户，用通知原文猜
        assertEquals(6L, CaptureDefaults.categoryId(categories, TxType.EXPENSE, "你在滴滴出行有一笔14.90元的支出"))
    }

    @Test
    fun `猜中分类与方向不匹配时回退其他`() {
        // 工资/理财是收入侧分类，支出方向命中也不采用
        assertEquals(3L, CaptureDefaults.categoryId(categories, TxType.EXPENSE, "余额宝-收益发放"))
        // 关键词没命中 → 其他
        assertEquals(3L, CaptureDefaults.categoryId(categories, TxType.EXPENSE, "某个完全不认识的商户"))
        assertEquals(3L, CaptureDefaults.categoryId(categories, TxType.EXPENSE, ""))
    }

    @Test
    fun `账户按来源包名推测`() {
        assertEquals(2L, CaptureDefaults.accountId(accounts, NotificationRules.WECHAT))
        assertEquals(3L, CaptureDefaults.accountId(accounts, NotificationRules.ALIPAY))
        // 云闪付等：走"银行"关键字
        assertEquals(4L, CaptureDefaults.accountId(accounts, NotificationRules.UNIONPAY))
    }

    @Test
    fun `推测不到关键字时取第一个账户`() {
        val plain = listOf(acc(1, "现金"), acc(2, "备用金"))
        assertEquals(1L, CaptureDefaults.accountId(plain, NotificationRules.ALIPAY))
        assertNull(CaptureDefaults.accountId(emptyList(), NotificationRules.ALIPAY))
    }

    @Test
    fun `银行来源按银行名匹配账户`() {
        assertEquals(4L, CaptureDefaults.accountId(accounts, "SMS:中国银行", "中国银行"))
        // 简称匹配："招商银行" 对账户名"招行卡"
        val withShort = accounts + acc(9, "招行卡")
        assertEquals(9L, CaptureDefaults.accountId(withShort, "SMS:招商银行", "招商银行"))
        // 没有对应银行名的账户 → 退化到含"银行"的账户
        assertEquals(4L, CaptureDefaults.accountId(accounts, "SMS:工商银行", "工商银行"))
    }

    @Test
    fun `从来源键与规则名取回银行名`() {
        assertEquals("工商银行", CaptureDefaults.bankNameOf("SMS:工商银行"))
        // 号码兜底：来源键是 SMS:95588，银行名要从规则名里找
        assertEquals("工商银行", CaptureDefaults.bankNameOf("SMS:95588", "工商银行短信·支出"))
        assertNull(CaptureDefaults.bankNameOf("SMS:95588", ""))
        assertEquals("工商银行", CaptureDefaults.bankNameOf("com.icbc"))
        assertEquals("建设银行", CaptureDefaults.bankNameOf("com.unknown.app", "建设银行通知·支出"))
    }

    @Test
    fun `学到的商户规则优先于关键词猜测`() {
        val rules = listOf(
            com.controleco.app.data.db.MerchantRuleEntity(
                id = 0, matchKey = "瑞幸咖啡", type = TxType.EXPENSE, categoryId = 2, accountId = 3,
            ),
        )
        // "瑞幸咖啡"没有关键词规则会落到"其他"(3)；学到的规则指向"购物"(2) 且优先
        assertEquals(
            2L,
            CaptureDefaults.categoryId(
                categories, TxType.EXPENSE, "瑞幸咖啡",
                learnedRules = rules, counterparty = "瑞幸咖啡", note = "",
            ),
        )
        // 学到的账户同样优先
        assertEquals(
            3L,
            CaptureDefaults.accountId(
                accounts, NotificationRules.WECHAT, null,
                learnedAccountId = com.controleco.app.capture.MerchantRules.accountIdFrom(
                    rules, accounts, TxType.EXPENSE, "瑞幸咖啡", "",
                ),
            ),
        )
        // 没有规则时退回关键词猜测（滴滴→交通）
        assertEquals(
            6L,
            CaptureDefaults.categoryId(categories, TxType.EXPENSE, "滴滴出行"),
        )
    }

    @Test
    fun `银行来源标记与支付来源标记`() {
        assertEquals(TxSource.BANK, CaptureDefaults.txSource("SMS:工商银行"))
        assertEquals(TxSource.BANK, CaptureDefaults.txSource(NotificationRules.UNIONPAY))
        assertEquals(TxSource.BANK, CaptureDefaults.txSource("com.icbc"))
        assertEquals(TxSource.NOTIFICATION, CaptureDefaults.txSource(NotificationRules.WECHAT))
        assertEquals(TxSource.NOTIFICATION, CaptureDefaults.txSource("com.example.unknown"))
    }

    @Test
    fun `可信账户匹配_银行卡按尾号或银行名_不回落渠道账户`() {
        val withCard = accounts + acc(9, "招商银行(1234)")
        assertEquals(9L, CaptureDefaults.trustedAccountId(withCard, NotificationRules.WECHAT, "招商银行(1234)"))
        // 尾号对不上：不能因为"从微信付的"就改记到微信零钱
        assertNull(CaptureDefaults.trustedAccountId(withCard, NotificationRules.WECHAT, "招商银行(9999)"))
        // 银行名唯一命中（账户名含银行名）可以匹配
        assertEquals(4L, CaptureDefaults.trustedAccountId(accounts, "SMS:中国银行", "中国银行储蓄卡"))
        // 渠道钱包按渠道匹配
        assertEquals(2L, CaptureDefaults.trustedAccountId(accounts, NotificationRules.WECHAT, "零钱"))
        // 电商页面只写了"微信支付"：按支付渠道本身匹配微信账户，不能落到"银行"账户
        assertEquals(2L, CaptureDefaults.trustedAccountId(accounts, NotificationRules.MEITUAN_TAKEOUT, "微信支付"))
        // 完全匹配不到 → null，由调用方保持原账户
        assertNull(CaptureDefaults.trustedAccountId(accounts, NotificationRules.WECHAT, "某个不存在的卡"))
    }
}
