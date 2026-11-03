package com.controleco.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryGuesserTest {

    @Test
    fun `expense guesses`() {
        assertEquals("餐饮", CategoryGuesser.guess("美团外卖-黄焖鸡"))
        assertEquals("购物", CategoryGuesser.guess("京东商城-京东超市"))
        assertEquals("日用", CategoryGuesser.guess("某某超市"))
        assertEquals("居住", CategoryGuesser.guess("电费缴费"))
        assertEquals("娱乐", CategoryGuesser.guess("腾讯视频会员"))
        assertEquals("医疗", CategoryGuesser.guess("康泰药房"))
    }

    @Test
    fun `transport merchants`() {
        // 用户指定：带"出行/打车/地铁/公交"的商户归交通
        assertEquals("交通", CategoryGuesser.guess("滴滴出行"))
        assertEquals("交通", CategoryGuesser.guess("滴滴打车"))
        assertEquals("交通", CategoryGuesser.guess("高德打车"))
        assertEquals("交通", CategoryGuesser.guess("地铁乘车码代扣"))
        assertEquals("交通", CategoryGuesser.guess("公交一卡通充值"))
    }

    @Test
    fun `medical merchants`() {
        // 用户指定：带"医院/诊所/医务室"的商户归医疗
        assertEquals("医疗", CategoryGuesser.guess("市第一人民医院"))
        assertEquals("医疗", CategoryGuesser.guess("某某口腔诊所"))
        assertEquals("医疗", CategoryGuesser.guess("校医务室"))
    }

    @Test
    fun `income guesses`() {
        assertEquals("工资", CategoryGuesser.guess("工资代发"))
        assertEquals("理财", CategoryGuesser.guess("余额宝-收益发放"))
        assertEquals("红包", CategoryGuesser.guess("微信红包"))
    }

    @Test
    fun `fallback to other`() {
        assertEquals("其他", CategoryGuesser.guess("某个完全不认识的商户"))
        assertEquals("其他", CategoryGuesser.guess(""))
    }

    @Test
    fun `latin merchants ignore case`() {
        assertEquals("餐饮", CategoryGuesser.guess("LUCKIN COFFEE"))
        assertEquals("娱乐", CategoryGuesser.guess("星光 ktv"))
    }
}
