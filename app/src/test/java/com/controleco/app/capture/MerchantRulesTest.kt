package com.controleco.app.capture

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.MerchantRuleEntity
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 商户归类学习（"越用越准"）：学习键归一化、命中判定、分类/账户应用（含分类被删后失效）。
 */
class MerchantRulesTest {

    private fun rule(
        key: String,
        type: TxType = TxType.EXPENSE,
        categoryId: Long = 1,
        accountId: Long? = null,
        hits: Int = 1,
    ) = MerchantRuleEntity(id = 0, matchKey = key, type = type, categoryId = categoryId, accountId = accountId, hits = hits)

    private fun cat(id: Long, name: String, type: TxType = TxType.EXPENSE) = CategoryEntity(id = id, name = name, type = type)

    private fun acc(id: Long, name: String) = AccountEntity(id = id, name = name)

    // ---------- 学习键 ----------

    @Test
    fun `键取商户并归一化`() {
        assertEquals("瑞幸咖啡", MerchantRules.keyOf("瑞幸咖啡", ""))
        assertEquals("星巴克", MerchantRules.keyOf(" 星巴克 ", "备注"))
        // 标点与空白去掉、大小写统一
        assertEquals("luckincoffee", MerchantRules.keyOf("Luckin Coffee", ""))
        assertEquals("麦当劳北京南站店", MerchantRules.keyOf("麦当劳·北京南站（店）", ""))
        // 商户为空时退回备注
        assertEquals("楼下超市", MerchantRules.keyOf("", "楼下超市"))
    }

    @Test
    fun `通用词与平台名不做键`() {
        assertNull(MerchantRules.keyOf("微信支付", ""))
        assertNull(MerchantRules.keyOf("拼多多", ""))
        assertNull(MerchantRules.keyOf("转账", ""))
        assertNull(MerchantRules.keyOf("", "收款"))
        // 纯数字/过短
        assertNull(MerchantRules.keyOf("123456", ""))
        assertNull(MerchantRules.keyOf("A", ""))
        assertNull(MerchantRules.keyOf("", ""))
    }

    @Test
    fun `像通知原文的备注不做键`() {
        // 一次性长文本（含金额）学下来没有复用价值
        assertNull(MerchantRules.keyOf("", "已支付¥12.80，用于测试"))
        assertNull(MerchantRules.keyOf("", "你有一笔14.90元的支出"))
    }

    @Test
    fun `键截断到二十字`() {
        val long = "某某某某某某某某某某某某某某某某某某某某某某某某某商户"
        assertEquals(20, MerchantRules.keyOf(long, "")!!.length)
    }

    // ---------- 命中与应用 ----------

    @Test
    fun `同键同方向命中并按方向隔离`() {
        val rules = listOf(rule("瑞幸咖啡"), rule("工资", type = TxType.INCOME, categoryId = 9))
        assertEquals(1L, MerchantRules.categoryIdFrom(rules, listOf(cat(1, "餐饮")), TxType.EXPENSE, "瑞幸咖啡", ""))
        // 支出侧的规则不会用到收入上
        assertNull(MerchantRules.categoryIdFrom(rules, listOf(cat(1, "餐饮")), TxType.INCOME, "瑞幸咖啡", ""))
        assertEquals(9L, MerchantRules.categoryIdFrom(rules, listOf(cat(9, "工资", TxType.INCOME)), TxType.INCOME, "工资", ""))
    }

    @Test
    fun `分类被删后规则失效`() {
        val rules = listOf(rule("瑞幸咖啡", categoryId = 42))
        // 分类表里没有 42 → 规则不生效（回退猜测链）
        assertNull(MerchantRules.categoryIdFrom(rules, listOf(cat(1, "餐饮")), TxType.EXPENSE, "瑞幸咖啡", ""))
    }

    @Test
    fun `账户规则同样校验存在性`() {
        val rules = listOf(rule("瑞幸咖啡", accountId = 7))
        val accounts = listOf(acc(7, "微信零钱"))
        assertEquals(7L, MerchantRules.accountIdFrom(rules, accounts, TxType.EXPENSE, "瑞幸咖啡", ""))
        assertNull(MerchantRules.accountIdFrom(rules, listOf(acc(8, "现金")), TxType.EXPENSE, "瑞幸咖啡", ""))
        // 规则没学账户 → 不命中
        assertNull(MerchantRules.accountIdFrom(listOf(rule("瑞幸咖啡")), accounts, TxType.EXPENSE, "瑞幸咖啡", ""))
    }

    @Test
    fun `没有规则时不命中`() {
        assertNull(MerchantRules.categoryIdFrom(emptyList(), listOf(cat(1, "餐饮")), TxType.EXPENSE, "瑞幸咖啡", ""))
        assertTrue(MerchantRules.match(emptyList(), TxType.EXPENSE, "瑞幸咖啡", "") == null)
    }

    @Test
    fun `跨渠道标签全角和平台前缀归一到同一商户`() {
        assertEquals("luckincoffee", MerchantRules.keyOf("商户名称：Ｌｕｃｋｉｎ　Ｃｏｆｆｅｅ", ""))
        assertEquals("瑞幸咖啡", MerchantRules.keyOf("微信支付-瑞幸咖啡", ""))
        val rules = listOf(rule("瑞幸咖啡"))
        assertEquals(1L, MerchantRules.match(rules, TxType.EXPENSE, "收款方：瑞幸咖啡", "")?.categoryId)
    }

    @Test
    fun `通用商户只从备注明确商户字段取键`() {
        assertEquals("小鹿书店", MerchantRules.keyOf("微信支付", "支付成功\n商户：小鹿书店\n金额：15.00元"))
        assertEquals("小鹿书店", MerchantRules.keyOf("", "商户：小鹿书店 支付金额：15.00元"))
        assertNull(MerchantRules.keyOf("微信支付", "楼下超市"))
        assertNull(MerchantRules.keyOf("", "您的账户收到一条很长很长的付款结果通知，金额99.00元"))
    }

    @Test
    fun `相似商户不模糊归并且历史退款规则不应用于普通收入`() {
        assertNull(MerchantRules.match(listOf(rule("小鹿书店")), TxType.EXPENSE, "小鹿书店东门店", ""))
        assertNull(MerchantRules.categoryIdFrom(
            listOf(rule("小鹿书店", TxType.INCOME, 9)),
            listOf(cat(9, "退款", TxType.INCOME)), TxType.INCOME, "小鹿书店", "",
        ))
    }
}
