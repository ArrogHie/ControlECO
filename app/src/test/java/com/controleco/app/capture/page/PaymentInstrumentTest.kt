package com.controleco.app.capture.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付方式文本 → 结构化支付工具：类型判定（渠道钱包/银行卡/信用付产品）、
 * 品牌与银行简称归一、卡尾号提取，以及"判不出来就返回空"的保守边界。
 */
class PaymentInstrumentTest {

    private fun parse(raw: String, channel: String = ""): PaymentInstrument {
        val instrument = PaymentInstrumentParser.parse(raw, channel)
        assertNotNull("应能解析为支付工具：$raw", instrument)
        return instrument!!
    }

    // ---------- 渠道自有钱包 ----------

    @Test
    fun `渠道自有钱包_零钱与零钱通`() {
        val wallet = parse("零钱", channel = "微信")
        assertEquals(InstrumentKind.CHANNEL_WALLET, wallet.kind)
        assertEquals("零钱", wallet.brand)
        assertEquals("微信", wallet.channel)
        // 渠道 + 产品名同时进匹配键，供账户名匹配使用
        assertTrue(wallet.matchKeys.contains("微信零钱"))

        val walletPlus = parse("零钱通", channel = "微信")
        assertEquals(InstrumentKind.CHANNEL_WALLET, walletPlus.kind)
        assertEquals("零钱通", walletPlus.brand)
    }

    @Test
    fun `渠道自有钱包_余额宝与账户余额`() {
        val yuEbao = parse("余额宝", channel = "支付宝")
        assertEquals(InstrumentKind.CHANNEL_WALLET, yuEbao.kind)
        assertEquals("余额宝", yuEbao.brand)

        val accountBalance = parse("账户余额", channel = "支付宝")
        assertEquals(InstrumentKind.CHANNEL_WALLET, accountBalance.kind)
        // "账户余额"是别名，规范名归一为"余额"
        assertEquals("余额", accountBalance.brand)
    }

    // ---------- 信用付产品 ----------

    @Test
    fun `信用付产品_花呗`() {
        val huabei = parse("花呗", channel = "支付宝")
        assertEquals(InstrumentKind.CREDIT_PRODUCT, huabei.kind)
        assertEquals("花呗", huabei.brand)
        assertEquals("支付宝", huabei.channel)
    }

    @Test
    fun `信用付产品_白条与月付`() {
        val baitiao = parse("京东白条", channel = "京东")
        assertEquals(InstrumentKind.CREDIT_PRODUCT, baitiao.kind)
        assertEquals("京东白条", baitiao.brand)

        val monthPay = parse("美团月付", channel = "美团")
        assertEquals(InstrumentKind.CREDIT_PRODUCT, monthPay.kind)
        assertEquals("美团月付", monthPay.brand)
    }

    // ---------- 银行卡 ----------

    @Test
    fun `银行卡_银行名加括号尾号`() {
        val cmb = parse("招商银行(1234)")
        assertEquals(InstrumentKind.BANK_CARD, cmb.kind)
        assertEquals("招商银行", cmb.brand)
        assertEquals("1234", cmb.tail)

        val boc = parse("中国银行储蓄卡(8920)", channel = "支付宝")
        assertEquals(InstrumentKind.BANK_CARD, boc.kind)
        assertEquals("中国银行", boc.brand)
        assertEquals("8920", boc.tail)
    }

    @Test
    fun `银行卡_简称归一为全称且可以没有尾号`() {
        val zhaohang = parse("招行信用卡")
        assertEquals(InstrumentKind.BANK_CARD, zhaohang.kind)
        assertEquals("招商银行", zhaohang.brand)
        assertEquals("", zhaohang.tail)
    }

    @Test
    fun `银行卡_前缀词不影响银行与尾号识别`() {
        val icbc = parse("付款卡 工商银行储蓄卡(1234)")
        assertEquals(InstrumentKind.BANK_CARD, icbc.kind)
        assertEquals("工商银行", icbc.brand)
        assertEquals("1234", icbc.tail)
    }

    @Test
    fun `只有卡种与尾号时仍按银行卡处理`() {
        val card = parse("储蓄卡(8920)")
        assertEquals(InstrumentKind.BANK_CARD, card.kind)
        assertEquals("", card.brand)
        assertEquals("8920", card.tail)
    }

    // ---------- 保守边界 ----------

    @Test
    fun `明显不是支付工具的文本返回空`() {
        assertNull(PaymentInstrumentParser.parse("更改"))
        assertNull(PaymentInstrumentParser.parse("完成"))
        assertNull(PaymentInstrumentParser.parse(""))
        assertNull(PaymentInstrumentParser.parse("   "))

        val longSentence = "本次交易已通过安全校验并完成扣款，如需查看完整说明请前往账单详情页面查看详情"
        assertTrue("样例应超过长度上限", longSentence.length > 30)
        assertNull(PaymentInstrumentParser.parse(longSentence))
    }

    // ---------- 尾号提取 ----------

    @Test
    fun `tailOf 解析尾号写法`() {
        assertEquals("7619", PaymentInstrumentParser.tailOf("尾号7619"))
        assertEquals("1234", PaymentInstrumentParser.tailOf("**** 1234"))
        assertEquals("1234", PaymentInstrumentParser.tailOf("招商银行(1234)"))
        assertEquals("", PaymentInstrumentParser.tailOf("abc"))
    }

    @Test
    fun `channel 原样保留在解析结果里`() {
        assertEquals("微信", parse("零钱", channel = "微信").channel)
        assertEquals("", parse("零钱").channel)
    }
}
