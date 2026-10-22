package com.controleco.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「次要色」与「近白纸面」两条口径的护栏。
 *
 * 背景以前是固定的青灰纸面（`#F7F8F7` 一类），所以换任何主题底色都泛青；
 * 装饰性卡片又直接借用了语义色（绿/金/陶红），于是「换主题只变一点点」。
 *
 * 现在：
 * 1. 每套主题有**主色 + 次要色**两个色相，卡片可以靠它们做区分，但都来自主题；
 * 2. 纸面是「近乎白色 + 一点主题色」，大面积区域接近纯白。
 *
 * 这两个不变式都在这里守住。
 */
class ThemeSecondColorTest {

    private val all = EcoAccent.entries
    private val tinted = all.filter { it.neutral != Neutral.WHITE }

    private fun hue(c: Color): Float = rgbToHsv(c.red, c.green, c.blue)[0]
    private fun sat(c: Color): Float = rgbToHsv(c.red, c.green, c.blue)[1]

    /** 色相环上的最短夹角（0..180） */
    private fun hueDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    // ---------- 次要色 ----------

    @Test
    fun `次要色与主色是两个不同色相`() {
        tinted.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                val d = hueDiff(hue(c.blue), hue(c.second))
                assertTrue(
                    "${accent.label}(dark=$dark): 次要色与主色色相只差 $d 度，起不到区分作用",
                    d >= 30f,
                )
            }
        }
    }

    @Test
    fun `次要色与主色不是同一个颜色`() {
        all.forEach { accent ->
            val c = ecoColors(false, accent)
            assertNotEquals("${accent.label}: 次要色不应等于主色", c.blue, c.second)
            assertNotEquals(accent.label, c.blueSoft, c.secondSoft)
        }
    }

    @Test
    fun `纯白主题的次要色仍是中性灰`() {
        // 白主题是黑白调性，次要色不能突然冒出一个彩色
        val c = ecoColors(false, EcoAccent.White)
        assertTrue("白主题次要色应保持中性（饱和度 ${sat(c.second)}）", sat(c.second) < 0.15f)
    }

    @Test
    fun `次要色族的明暗方向与主色一致`() {
        // deep = 远离背景的那一端，soft = 贴近背景的那一端。
        // 浅色模式背景是白的，所以 deep 更深、soft 更浅；深色模式正好相反。
        all.forEach { accent ->
            val light = ecoColors(false, accent)
            assertTrue(
                "${accent.label}(浅色): secondDeep 应比 second 深",
                relativeLuminance(light.secondDeep) < relativeLuminance(light.second),
            )
            assertTrue(
                "${accent.label}(浅色): secondSoft 应比 second 浅",
                relativeLuminance(light.secondSoft) > relativeLuminance(light.second),
            )

            val dark = ecoColors(true, accent)
            assertTrue(
                "${accent.label}(深色): secondDeep 应比 second 亮（压深底要更亮）",
                relativeLuminance(dark.secondDeep) > relativeLuminance(dark.second),
            )
            assertTrue(
                "${accent.label}(深色): secondSoft 应比 second 暗（贴背景）",
                relativeLuminance(dark.secondSoft) < relativeLuminance(dark.second),
            )
        }
    }

    @Test
    fun `压在次要色上的前景可读`() {
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                val cr = contrastRatio(c.onSecond, c.secondDeep)
                assertTrue(
                    "${accent.label}(dark=$dark): onSecond/secondDeep 对比度不足 $cr",
                    cr >= 3f,
                )
            }
        }
    }

    @Test
    fun `每套主题的主色和次要色组合互不重复`() {
        assertEquals(
            "主色/次要色组合不应重复",
            all.size,
            all.map { it.light.blue to ecoColors(false, it).second }.toSet().size,
        )
    }

    // ---------- 近白纸面 ----------

    @Test
    fun `浅色纸面接近白色`() {
        tinted.forEach { accent ->
            val c = ecoColors(false, accent)
            val l = relativeLuminance(c.paper)
            assertTrue("${accent.label}: 纸面不够白（亮度 $l）", l >= 0.90f)
            // 「一点主题色」= 极低彩度，不能又变成一块有色背景
            assertTrue("${accent.label}: 纸面彩度太高（${sat(c.paper)}）", sat(c.paper) <= 0.05f)
        }
    }

    @Test
    fun `浅色纸面带的是当前主题的色相`() {
        // 这是「背景不再是固定青绿」的核心断言：纸面色相要跟着主色走
        tinted.forEach { accent ->
            val c = ecoColors(false, accent)
            val d = hueDiff(hue(c.paper), hue(c.blue))
            assertTrue(
                "${accent.label}: 纸面色相偏离主色 $d 度（纸面 ${hue(c.paper)} / 主色 ${hue(c.blue)}）",
                d <= 30f,
            )
        }
    }

    @Test
    fun `不同主题的纸面确实不一样`() {
        val papers = tinted.map { ecoColors(false, it).paper }
        assertEquals("各主题纸面不应相同", tinted.size, papers.toSet().size)
    }

    @Test
    fun `卡片面比纸面更白`() {
        tinted.forEach { accent ->
            val c = ecoColors(false, accent)
            assertTrue(
                "${accent.label}: 卡片面应比纸面更接近纯白",
                relativeLuminance(c.surface) >= relativeLuminance(c.paper),
            )
        }
    }

    @Test
    fun `白主题仍是大面积纯白`() {
        val c = ecoColors(false, EcoAccent.White)
        assertEquals(Color(0xFFFFFFFF), c.paper)
        assertEquals(Color(0xFFFFFFFF), c.surface)
    }

    @Test
    fun `深色纸面依然是深色且随主题偏色`() {
        val darks = all.map { ecoColors(true, it) }
        darks.forEach { c ->
            val l = relativeLuminance(c.paper)
            assertTrue("${c.accent.label}: 深色纸面太亮（$l）", l < 0.06f)
        }
        // 深色模式也不再是一片深青
        assertEquals("深色纸面不应相同", all.size, darks.map { it.paper }.toSet().size)
    }

    // ---------- 语义色没有被次要色挤掉 ----------

    @Test
    fun `次要色不会和语义色撞在一起`() {
        // 次要色是装饰色，不能看起来像「收入绿 / 警告金 / 危险红 / 辅助紫」，
        // 否则用户会把装饰当成状态提示。
        all.forEach { accent ->
            val c = ecoColors(false, accent)
            listOf(
                "收入绿 jade" to c.jade,
                "警告金 amber" to c.amber,
                "危险红 clay" to c.clay,
                "辅助紫 violet" to c.violet,
            ).forEach { (name, semantic) ->
                val d = colorDistance(c.second, semantic)
                assertTrue(
                    "${accent.label}: 次要色与「$name」太接近（ΔE=$d）",
                    d > 20f,
                )
            }
        }
    }

    // ---------- 双色相封面 ----------

    @Test
    fun `封面收尾档用的是次要色相`() {
        // 用 ΔE 而不是色相差：珊瑚的 hero 自己就偏过色相，色相差会偏小，
        // 但只要两档看起来是两种颜色，渐变就有层次。
        tinted.forEach { accent ->
            val c = ecoColors(false, accent)
            val d = colorDistance(c.heroSecond, c.heroA)
            assertTrue(
                "${accent.label}: heroSecond 与主色封面太接近（ΔE=$d），渐变会退化成单色",
                d > 20f,
            )
        }
    }

    @Test
    fun `封面收尾档与主色档同亮度`() {
        // 同亮度才能保证白字在整条渐变上对比一致
        all.forEach { accent ->
            val c = ecoColors(false, accent)
            val d = abs(relativeLuminance(c.heroSecond) - relativeLuminance(c.heroC))
            assertTrue("${accent.label}: heroSecond 亮度应贴近 heroC（差 $d）", d < 0.02f)
        }
    }

    @Test
    fun `双色相封面主色占多`() {
        all.forEach { accent ->
            val c = ecoColors(false, accent)
            val g = c.heroDuoGradient
            if (accent.neutral == Neutral.WHITE) {
                // 无彩色主题没有第二个色相可转，应退回单色渐变（而不是塞一个假色相）
                assertEquals("${accent.label}: 无彩色主题应退回单色渐变", c.heroGradient, g)
                return@forEach
            }
            assertEquals("${accent.label}: 双色相封面应是 4 个色标", 4, g.size)
            // 前 3 个色标都是主色相，只有最后一个才换色相
            g.take(3).forEach {
                assertTrue(
                    "${accent.label}: 前段应为主色相",
                    colorDistance(it, c.heroA) < 20f,
                )
            }
            assertTrue(
                "${accent.label}: 末段应为次要色相",
                colorDistance(g.last(), c.heroA) > 20f,
            )
        }
    }

    @Test
    fun `双色相封面上白字仍可读`() {
        all.forEach { accent ->
            val c = ecoColors(false, accent)
            c.heroDuoGradient.forEach { stop ->
                val cr = contrastRatio(c.onHero, stop)
                assertTrue("${accent.label}: 双色相封面某端对比度不足 $cr", cr >= 4.5f)
            }
        }
    }
}
