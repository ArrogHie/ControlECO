package com.controleco.app.ui.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账单行文案：有商户 → 名称是商户、小行第一行「时间　类别」；无商户 → 名称是类别、小行第一行只留时间；
 * 备注(第二行)两种情况都原样带出。分隔用空白，不用「·」。
 */
class BillRowTextTest {

    @Test
    fun `有商户时名称用商户_小行第一行是时间加类别`() {
        val t = billRowText("星巴克", "餐饮", "09:30", "拿铁")
        assertEquals("星巴克", t.title)
        assertEquals("09:30  餐饮", t.subLine1)
        assertTrue("不应再用「·」间隔", !t.subLine1.contains('·'))
        assertEquals("拿铁", t.note)
    }

    @Test
    fun `无商户时名称用类别_小行第一行只有时间`() {
        val t = billRowText("", "餐饮", "09:30", "拿铁")
        assertEquals("餐饮", t.title)
        assertEquals("09:30", t.subLine1)
        assertEquals("拿铁", t.note)
    }

    @Test
    fun `备注为空时不产出备注行`() {
        assertEquals("", billRowText("", "交通", "08:05", "").note)
        assertEquals("", billRowText("滴滴", "交通", "08:05", "").note)
    }

    @Test
    fun `只填空格的商户视同没有商户`() {
        val t = billRowText("   ", "餐饮", "12:00", "")
        assertEquals("餐饮", t.title)
        assertEquals("12:00", t.subLine1)
    }

    @Test
    fun `商户名前后空格会被去掉`() {
        assertEquals("全家便利店", billRowText(" 全家便利店 ", "日用", "21:10", "").title)
    }

    @Test
    fun `平铺排序时小行前面补日期`() {
        val withMerchant = billRowText("星巴克", "餐饮", "09:30", "", datePrefix = "10月28日")
        assertEquals("10月28日 09:30  餐饮", withMerchant.subLine1)
        val withoutMerchant = billRowText("", "餐饮", "09:30", "", datePrefix = "10月28日")
        assertEquals("餐饮", withoutMerchant.title)
        assertEquals("10月28日 09:30", withoutMerchant.subLine1)
    }

    @Test
    fun `日期前缀为空视同按天分组_不产生多余空格`() {
        assertEquals("09:30  餐饮", billRowText("星巴克", "餐饮", "09:30", "", datePrefix = null).subLine1)
        assertEquals("09:30  餐饮", billRowText("星巴克", "餐饮", "09:30", "", datePrefix = "").subLine1)
    }
}
