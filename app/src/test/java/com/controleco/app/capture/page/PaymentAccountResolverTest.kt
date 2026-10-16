package com.controleco.app.capture.page

import com.controleco.app.capture.page.PaymentAccountResolver.MatchReason
import com.controleco.app.data.db.AccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付工具 → 记账账户的匹配：精确名 → 卡尾号 → 银行名 → 渠道兜底的命中顺序，
 * 以及"匹配不到就返回空、绝不猜账户"的边界。
 *
 * 匹配只依据结构化后的支付工具（[PaymentInstrument]），不把备注文本当已匹配账户。
 */
class PaymentAccountResolverTest {

    private val accounts = listOf(
        AccountEntity(id = 1, name = "现金"),
        AccountEntity(id = 2, name = "微信零钱"),
        AccountEntity(id = 3, name = "支付宝"),
        AccountEntity(id = 4, name = "招商银行(1234)"),
    )

    private fun instrument(raw: String, channel: String = ""): PaymentInstrument {
        val parsed = PaymentInstrumentParser.parse(raw, channel)
        assertNotNull("应能解析为支付工具：$raw", parsed)
        return parsed!!
    }

    // ---------- 银行卡 ----------

    @Test
    fun `银行卡支付匹配到账户名同形的账户`() {
        val match = PaymentAccountResolver.resolve(accounts, instrument("招商银行(1234)"))
        assertNotNull(match)
        assertEquals(4L, match!!.accountId)
        assertEquals("招商银行(1234)", match.accountName)
        // 卡尾号阶段优先于精确名阶段：同名银行的多张卡只有尾号能区分，尾号唯一即命中
        assertEquals(MatchReason.CARD_TAIL, match.reason)
    }

    @Test
    fun `列表之外的银行卡不误配_给渠道标签时才按渠道兜底`() {
        val icbc = instrument("工商银行(5678)")
        // 银行名与尾号都命中不了任何账户：不猜账户
        assertNull(PaymentAccountResolver.resolve(accounts, icbc))
        // 明确给了渠道标签才落到渠道兜底
        val viaChannel = PaymentAccountResolver.resolve(accounts, icbc, "微信")
        assertNotNull(viaChannel)
        assertEquals("微信零钱", viaChannel!!.accountName)
        assertEquals(MatchReason.CHANNEL, viaChannel.reason)
        assertNotEquals("招商银行(1234)", viaChannel.accountName)
    }

    // ---------- 渠道钱包 ----------

    @Test
    fun `渠道钱包按渠道加产品组合键匹配账户`() {
        val wallet = PaymentAccountResolver.resolve(accounts, instrument("零钱", channel = "微信"))
        assertNotNull(wallet)
        assertEquals("微信零钱", wallet!!.accountName)
        assertEquals(2L, wallet.accountId)
        // "微信零钱"是渠道 + 产品名的组合匹配键，同样走精确名阶段
        assertEquals(MatchReason.EXACT_NAME, wallet.reason)

        val yuEbao = PaymentAccountResolver.resolve(accounts, instrument("余额宝", channel = "支付宝"))
        assertNotNull(yuEbao)
        assertEquals("支付宝", yuEbao!!.accountName)
        assertEquals(3L, yuEbao.accountId)
    }

    // ---------- 同名账户与尾号 ----------

    @Test
    fun `同银行的简称账户与带尾号账户_按尾号命中带尾号的那个`() {
        val duplicated = listOf(
            AccountEntity(id = 1, name = "招行卡"),
            AccountEntity(id = 2, name = "招商银行(1234)"),
        )
        val match = PaymentAccountResolver.resolve(duplicated, instrument("招商银行(1234)"))
        assertNotNull(match)
        // 卡尾号阶段排在精确名阶段之前："1234"只命中带尾号的账户，不会被"招行卡"抢先
        assertEquals("招商银行(1234)", match!!.accountName)
        assertEquals(MatchReason.CARD_TAIL, match.reason)

        // 工具没有尾号时（如"招商银行储蓄卡"）才回落到同银行的账户，取列表里在前的那个
        val noTail = PaymentAccountResolver.resolve(duplicated, instrument("招商银行"))
        assertNotNull(noTail)
        assertEquals("招行卡", noTail!!.accountName)
        assertEquals(MatchReason.EXACT_NAME, noTail.reason)
    }

    @Test
    fun `账户名只有卡种与尾号时按尾号唯一命中`() {
        val cardOnly = listOf(
            AccountEntity(id = 1, name = "现金"),
            AccountEntity(id = 2, name = "储蓄卡(1234)"),
        )
        val match = PaymentAccountResolver.resolve(cardOnly, instrument("招商银行(1234)"))
        assertNotNull(match)
        assertEquals("储蓄卡(1234)", match!!.accountName)
        assertEquals(2L, match.accountId)
        assertEquals(MatchReason.CARD_TAIL, match.reason)
    }

    // ---------- 兜底与边界 ----------

    @Test
    fun `空账户列表返回空_工具缺失时按渠道标签兜底`() {
        assertNull(PaymentAccountResolver.resolve(emptyList(), instrument("零钱", channel = "微信")))
        assertNull(PaymentAccountResolver.resolve(emptyList(), null, "微信"))

        val byChannel = PaymentAccountResolver.resolve(accounts, null, "微信")
        assertNotNull(byChannel)
        assertEquals("微信零钱", byChannel!!.accountName)
        assertEquals(MatchReason.CHANNEL, byChannel.reason)

        // 没有渠道标签、或渠道没有对应账户时都不猜
        assertNull(PaymentAccountResolver.resolve(accounts, null, ""))
        assertNull(PaymentAccountResolver.resolve(accounts, null, "不存在的渠道"))
    }

    // ---------- 名称标准化与尾号提取 ----------

    @Test
    fun `normalizeAccountName 剥离尾号并归一简称`() {
        assertEquals("招商银行", PaymentAccountResolver.normalizeAccountName("招商银行(1234)"))
        assertTrue(PaymentAccountResolver.normalizeAccountName("招行卡").contains("招商银行"))
        assertEquals("招商银行", PaymentAccountResolver.normalizeAccountName("招行 1234"))
        // 没有可剥离内容的名字保持原样
        assertEquals("微信零钱", PaymentAccountResolver.normalizeAccountName("微信零钱"))
    }

    @Test
    fun `accountTails 提取账户名里的四位尾号`() {
        assertEquals(setOf("1234"), PaymentAccountResolver.accountTails("招商银行(1234)"))
        assertEquals(setOf("7619"), PaymentAccountResolver.accountTails("尾号7619"))
        assertTrue(PaymentAccountResolver.accountTails("现金").isEmpty())
    }
}
