package com.controleco.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「换主题后，界面上真正被用到的那些颜色」快照。
 *
 * [AccentThemeTest] 守的是调色板本身的正确性；这里守的是**组件实际取到的那几个 token**
 * （主按钮渐变、FAB、底栏选中胶囊、描边、hero、卡片软渐变），
 * 防止以后有人在组件里又写死一个 `Color(0xFF...)` 把主题绕过去。
 *
 * 没有真机/模拟器时的替代验证：把「同一组件在不同主题下取到的颜色」逐项断言为不同，
 * 并锁住白主题的面色确实是白。
 */
class ThemeSurfaceTest {

    private val coolThemes = listOf(
        EcoAccent.Tide, EcoAccent.Meadow, EcoAccent.Iris,
        EcoAccent.Coral, EcoAccent.Amber, EcoAccent.Sky,
    )

    // ---------- 主按钮 / FAB：同一份渐变 ----------

    @Test
    fun `主按钮与 FAB 共用同一条主题渐变`() {
        // 这两个原先是两套写死的青绿（EcoControls 用 269B8C/16677C，EcoFab 用 2AA79C/16858A/0F6472），
        // 现在必须都从 eco.accentGradient 来，保证「同一主操作只有一种颜色」。
        allThemes().forEach { (accent, dark) ->
            val c = ecoColors(dark, accent)
            assertEquals(
                "${accent.label}(dark=$dark): 主按钮渐变应等于 accentGradient",
                listOf(c.accentGradA, c.accentGradB, c.accentGradC),
                c.accentGradient,
            )
            assertEquals(3, c.accentGradient.size)
        }
    }

    @Test
    fun `六套冷调主题的主按钮渐变各不相同`() {
        val light = coolThemes.map { ecoColors(false, it).accentGradient }
        assertEquals("主按钮渐变不应重复", coolThemes.size, light.toSet().size)
    }

    // ---------- 底栏选中胶囊 ----------

    @Test
    fun `底栏选中胶囊取强调色软底`() {
        // 原来是 Color(0xFFD0F2E5) 起手的固定浅青渐变
        coolThemes.forEach { accent ->
            val c = ecoColors(false, accent)
            val pillTop = c.blueSoft
            // 不再等于那个固定的浅青
            assertNotEquals("${accent.label}: 选中胶囊不应回到固定浅青", Color(0xFFD0F2E5), pillTop)
            // 且与纸面有区分
            assertTrue(
                "${accent.label}: 选中胶囊应与纸面可区分",
                colorDistance(pillTop, c.paper) > 4f,
            )
        }
    }

    // ---------- 描边 ----------

    @Test
    fun `描边色随主题且带透明度`() {
        coolThemes.forEach { accent ->
            val c = ecoColors(false, accent)
            assertTrue("${accent.label}: 描边应保留透明度", c.accentBorder.alpha < 1f)
            assertTrue("${accent.label}: 描边应可辨识", c.accentBorder.alpha > 0.1f)
        }
        // 不同主题的描边色不同
        val borders = coolThemes.map { ecoColors(false, it).accentBorder }
        assertEquals("描边色不应重复", coolThemes.size, borders.toSet().size)
    }

    // ---------- hero ----------

    @Test
    fun `hero 渐变与前景随主题变化`() {
        coolThemes.forEach { accent ->
            val c = ecoColors(false, accent)
            assertEquals(3, c.heroGradient.size)
            // 前景必须比最亮的那一端更亮（白系字压在深卡上）
            val lightestHero = c.heroGradient.maxByOrNull { relativeLuminance(it) }!!
            assertTrue(
                "${accent.label}: hero 前景应比最亮端更亮",
                relativeLuminance(c.onHero) > relativeLuminance(lightestHero),
            )
        }
        val heros = coolThemes.map { ecoColors(false, it).heroGradient }
        assertEquals("hero 渐变不应重复", coolThemes.size, heros.toSet().size)
    }

    // ---------- 白主题面 ----------

    @Test
    fun `白主题把大面积面色换成白`() {
        val white = ecoColors(false, EcoAccent.White)
        assertEquals(Color(0xFFFFFFFF), white.paper)
        assertEquals(Color(0xFFFFFFFF), white.surface)
        // 与默认冷调主题相比，纸面明显更亮
        val tide = ecoColors(false, EcoAccent.Tide)
        assertTrue(
            "白主题纸面应比默认主题亮",
            relativeLuminance(white.paper) > relativeLuminance(tide.paper),
        )
    }

    @Test
    fun `只有白主题是纯白面_其它主题仍保留纸感`() {
        coolThemes.forEach { accent ->
            val c = ecoColors(false, accent)
            assertNotEquals(
                "${accent.label}: 冷调主题的纸面不应变成纯白（会丢失原有的纸感层次）",
                Color(0xFFFFFFFF),
                c.paper,
            )
        }
    }

    // ---------- 语义色 ----------

    @Test
    fun `收支红绿在所有主题下都可区分`() {
        allThemes().forEach { (accent, dark) ->
            val c = ecoColors(dark, accent)
            assertTrue(
                "${accent.label}(dark=$dark): 收入/支出色 ΔE 太小",
                colorDistance(c.jade, c.clay) > 30f,
            )
        }
    }

    private fun allThemes(): List<Pair<EcoAccent, Boolean>> =
        EcoAccent.entries.flatMap { listOf(it to false, it to true) }
}
