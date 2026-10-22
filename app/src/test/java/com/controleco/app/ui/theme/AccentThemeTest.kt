package com.controleco.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 强调色系统：换主题必须真正改变全局观感，并且每套主题都要能读、能分。
 *
 * 这些测试守的是「主题色贯穿」这条产品口径：
 * 1. 7 套主题（含白主题）在深浅两套下都齐全；
 * 2. `blue*` / `hero*` / `accentGrad*` 三组互相独立、且都随主题变（不是换汤不换药）；
 * 3. 白主题的大面积面色确实是白/近白，且中性色**不再偏青绿**；
 * 4. 压在实心色块上的前景（onAccent / onHero*）对比度够；
 * 5. 分类色板不随主题换色导致分类混乱，但仍要去掉固定青绿槽位。
 */
class AccentThemeTest {

    private val all = EcoAccent.entries

    // ---------- 相对亮度 / 对比度（用生产同一套实现，避免测试自说自话） ----------

    private fun luminance(c: Color): Float = relativeLuminance(c)

    private fun contrast(a: Color, b: Color): Float = contrastRatio(a, b)

    /** 色相角（0..360）：用来判断「是不是还偏青绿」 */
    private fun hue(c: Color): Float {
        val hsv = rgbToHsv(c.red, c.green, c.blue)
        return hsv[0]
    }

    /** 饱和度（0..1）：接近 0 就是灰，白色/石墨应当很低 */
    private fun saturation(c: Color): Float = rgbToHsv(c.red, c.green, c.blue)[1]

    // ---------- 1. 主题齐全 ----------

    @Test
    fun `七套主题齐全且 id 唯一`() {
        assertEquals(7, all.size)
        assertEquals("主题 id 不能重复", all.size, all.map { it.id }.toSet().size)
        assertEquals("主题名不能重复", all.size, all.map { it.label }.toSet().size)
    }

    @Test
    fun `白主题存在且可从 id 还原`() {
        val white = EcoAccent.fromId("white")
        assertEquals(EcoAccent.White, white)
        assertEquals("素白", white.note)
        // 未知 id 回落到默认主题，不能崩
        assertEquals(EcoAccent.Tide, EcoAccent.fromId("nope"))
        assertEquals(EcoAccent.Tide, EcoAccent.fromId(null))
    }

    // ---------- 2. 强调色族随主题变化 ----------

    @Test
    fun `每套主题的 blue 主色互不相同`() {
        val light = all.map { Color(it.light.blue) }
        assertEquals("浅色下 7 套主题的主色不应相同", all.size, light.toSet().size)
        val dark = all.map { Color(it.dark.blue) }
        assertEquals("深色下 7 套主题的主色不应相同", all.size, dark.toSet().size)
    }

    @Test
    fun `每套主题的 hero 渐变与主按钮渐变都不同`() {
        assertEquals("hero 渐变不应重复", all.size, all.map { it.heroA to it.heroC }.toSet().size)
        assertEquals(
            "主按钮渐变不应重复",
            all.size,
            all.map { it.accentGradA to it.accentGradC }.toSet().size,
        )
    }

    @Test
    fun `主按钮渐变比 hero 渐变亮`() {
        // hero 是「深色封面」，主操作按钮要比它亮，否则按钮看起来像封面
        all.forEach { accent ->
            val hero = luminance(accent.heroB)
            val button = luminance(accent.accentGradB)
            assertTrue(
                "${accent.label}: 主按钮渐变应比 hero 亮（hero=$hero, button=$button）",
                button > hero,
            )
        }
    }

    @Test
    fun `换主题时渐变_描边_选中态全部跟着变`() {
        // 这是本次重构的核心承诺：同一套组件在不同主题下必须拿到不同的色值
        val tide = ecoColors(false, EcoAccent.Tide)
        val iris = ecoColors(false, EcoAccent.Iris)

        assertNotEquals("主色应变化", tide.blue, iris.blue)
        assertNotEquals("深端应变化", tide.blueDeep, iris.blueDeep)
        assertNotEquals("浅底应变化", tide.blueSoft, iris.blueSoft)
        assertNotEquals("hero 渐变应变化", tide.heroA, iris.heroA)
        assertNotEquals("主按钮渐变应变化", tide.accentGradA, iris.accentGradA)
        // 描边用的是 blue 的 alpha，自然跟着变
        assertNotEquals("描边色应变化", tide.accentBorder, iris.accentBorder)
        // 渐变列表整体也要不同
        assertNotEquals("heroGradient 应变化", tide.heroGradient, iris.heroGradient)
        assertNotEquals("accentGradient 应变化", tide.accentGradient, iris.accentGradient)
    }

    @Test
    fun `语义色不随主题变_只随深浅变`() {
        // 收入/警告/危险是语义，换主题必须保持不变，否则用户会误读颜色含义
        val a = ecoColors(false, EcoAccent.Tide)
        val b = ecoColors(false, EcoAccent.Iris)
        val c = ecoColors(false, EcoAccent.White)
        listOf(a, b, c).forEach {
            assertEquals(a.jade, it.jade)
            assertEquals(a.amber, it.amber)
            assertEquals(a.clay, it.clay)
            assertEquals(a.violet, it.violet)
        }
        // 深浅之间必须不同
        val dark = ecoColors(true, EcoAccent.Tide)
        assertNotEquals(a.jade, dark.jade)
        assertNotEquals(a.clay, dark.clay)
    }

    // ---------- 3. 白主题：大面积白色 + 中性不偏青 ----------

    @Test
    fun `白主题浅色下背景与卡片是纯白`() {
        val white = ecoColors(false, EcoAccent.White)
        assertEquals("纸面应为纯白", Color(0xFFFFFFFF), white.paper)
        assertEquals("卡片面应为纯白", Color(0xFFFFFFFF), white.surface)
        // 次级面只能是极浅灰，不能有彩度
        listOf(white.paperDeep, white.surface2, white.surface3, white.surfaceInset).forEach {
            assertTrue("次级面应接近白（实际亮度 ${luminance(it)}）", luminance(it) > 0.85f)
            assertTrue("次级面不应有彩度（饱和度 ${saturation(it)}）", saturation(it) < 0.05f)
        }
    }

    @Test
    fun `白主题的中性色不是青绿`() {
        val white = ecoColors(false, EcoAccent.White)
        // 分隔线/轨道/正文这些大面积的neutral，饱和度都要低
        listOf(white.rule, white.ruleStrong, white.track, white.muted, white.quiet).forEach {
            assertTrue("中性色不应有明显彩度（饱和度 ${saturation(it)}）", saturation(it) < 0.12f)
        }
    }

    @Test
    fun `默认主题的纸面比白主题暗且带一点冷调`() {
        val cool = ecoColors(false, EcoAccent.Tide)
        val white = ecoColors(false, EcoAccent.White)
        assertTrue("默认纸面应比纯白暗", luminance(cool.paper) < luminance(white.paper))
        // 默认中性面保留冷调，但彩度必须很低（原来的青灰饱和度偏高）
        assertTrue("默认中性面彩度应克制", saturation(cool.paper) < 0.12f)
    }

    @Test
    fun `白主题深色是石墨而不是纯黑`() {
        val dark = ecoColors(true, EcoAccent.White)
        val l = luminance(dark.paper)
        assertTrue("石墨底不能纯黑（亮度 $l）", l > 0.002f)
        assertTrue("石墨底也不能太亮（亮度 $l）", l < 0.05f)
        assertTrue("石墨底不应有彩度", saturation(dark.paper) < 0.10f)
    }

    // ---------- 4. 对比度 ----------

    @Test
    fun `正文与纸面_卡片对比度达标`() {
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                val onPaper = contrast(c.ink, c.paper)
                val onSurface = contrast(c.ink, c.surface)
                assertTrue(
                    "${accent.label}(dark=$dark): ink/paper 对比度不足 $onPaper",
                    onPaper >= 7f,
                )
                assertTrue(
                    "${accent.label}(dark=$dark): ink/surface 对比度不足 $onSurface",
                    onSurface >= 7f,
                )
            }
        }
    }

    @Test
    fun `次文字在卡片上仍然可读`() {
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                assertTrue(
                    "${accent.label}(dark=$dark): muted 对比度不足 ${contrast(c.muted, c.surface)}",
                    contrast(c.muted, c.surface) >= 4.5f,
                )
            }
        }
    }

    @Test
    fun `实心强调块上的前景有足够对比度`() {
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                // 选中 chip / 主按钮：onAccent 压在 blueDeep 上
                assertTrue(
                    "${accent.label}(dark=$dark): onAccent/blueDeep 对比度不足 " +
                        contrast(c.onAccent, c.blueDeep),
                    contrast(c.onAccent, c.blueDeep) >= 3f,
                )
            }
        }
    }

    @Test
    fun `hero 前景在 hero 渐变上可读`() {
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                val worst = c.heroGradient.minOf { contrast(c.onHero, it) }
                assertTrue(
                    "${accent.label}(dark=$dark): hero 前景在最差渐变端对比度不足 $worst",
                    worst >= 4.5f,
                )
                // 次级前景略淡，但仍要能看清
                val mutedWorst = c.heroGradient.minOf { contrast(c.onHeroMuted, it) }
                assertTrue(
                    "${accent.label}(dark=$dark): hero 次级前景对比度不足 $mutedWorst",
                    mutedWorst >= 3f,
                )
            }
        }
    }

    @Test
    fun `主按钮文字压在按钮渐变两端都可读`() {
        all.forEach { accent ->
            val c = ecoColors(false, accent)
            val worst = listOf(c.accentGradA, c.accentGradC).minOf { contrastRatio(c.onAccentGrad, it) }
            assertTrue(
                "${accent.label}: 按钮文字对比度不足 $worst",
                worst >= 3f,
            )
        }
    }

    @Test
    fun `深浅两套下强调色本身对纸面有区分度`() {
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                val d = colorDistance(c.blue, c.paper)
                assertTrue(
                    "${accent.label}(dark=$dark): 强调色与纸面太接近 ΔE=$d",
                    d >= 18f,
                )
            }
        }
    }

    // ---------- 5. 分类色板 ----------

    @Test
    fun `分类色板仍是 8 色且两两可分`() {
        all.forEach { accent ->
            listOf(lightAccBookColors(accent), darkAccBookColors(accent)).forEach { colors ->
                val p = colors.categoryPalette
                assertEquals(8, p.size)
                assertEquals("调色板内不应有重复", 8, p.toSet().size)
                var min = Float.MAX_VALUE
                for (i in p.indices) {
                    for (j in i + 1 until p.size) min = minOf(min, colorDistance(p[i], p[j]))
                }
                assertTrue("${accent.label}: 分类调色板最小色差 $min 过小", min >= 26f)
            }
        }
    }

    @Test
    fun `分类色板不再含固定青绿槽位`() {
        // 原来的「浅青 #7EC8C8 / #5FB6C8」是写死的，任何主题下都有一块青绿
        val legacyTealLight = Color(0xFF7EC8C8)
        val legacyTealDark = Color(0xFF5FB6C8)
        all.forEach { accent ->
            assertTrue(
                "${accent.label}: 浅色调色板不应再含固定浅青",
                legacyTealLight !in lightAccBookColors(accent).categoryPalette,
            )
            assertTrue(
                "${accent.label}: 深色调色板不应再含固定浅青",
                legacyTealDark !in darkAccBookColors(accent).categoryPalette,
            )
        }
    }

    @Test
    fun `主题槽位随强调色变化`() {
        // 调色板里留了一个「跟主题走」的槽位：换主题时它要变，分类才不会全一样
        val tide = lightAccBookColors(EcoAccent.Tide).categoryPalette
        val coral = lightAccBookColors(EcoAccent.Coral).categoryPalette
        assertNotEquals("主题槽位应随主题变化", tide.toSet(), coral.toSet())
    }

    // ---------- 6. 派生的 hero 辅助色 ----------

    @Test
    fun `hero 辅助色都由 hero 推导且不比 hero 暗`() {
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                // onHero 系列是在 hero 中段色上向白提亮，必须比 heroB 亮
                assertTrue(
                    "${accent.label}: onHero 应比 heroB 亮",
                    luminance(c.onHero) > luminance(c.heroB),
                )
                assertTrue(
                    "${accent.label}: onHeroMuted 应比 onHero 暗",
                    luminance(c.onHeroMuted) < luminance(c.onHero),
                )
                // 进度条渐变两端都要比 hero 底亮
                c.heroProgress.forEach {
                    assertTrue(
                        "${accent.label}: hero 进度色应比 heroB 亮",
                        luminance(it) > luminance(c.heroB),
                    )
                }
                // 底盘是 hero 深端再压深
                assertTrue(
                    "${accent.label}: hero 底盘应比 heroA 暗",
                    luminance(c.heroPlate) < luminance(c.heroA),
                )
            }
        }
    }

    @Test
    fun `softGradient 不会产生脏色`() {
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                // 软渐变是 soft 与 surface 之间的插值：两端亮度必须落在两者之间
                val top = lerp(c.blueSoft, c.surface, 0.62f)
                val lo = minOf(luminance(c.blueSoft), luminance(c.surface))
                val hi = maxOf(luminance(c.blueSoft), luminance(c.surface))
                val l = luminance(top)
                assertTrue(
                    "${accent.label}(dark=$dark): 软渐变顶色越界 $l 不在 [$lo, $hi]",
                    l >= lo - 0.005f && l <= hi + 0.005f,
                )
            }
        }
    }

    // ---------- 7. 白主题下强调色仍然是「墨黑」而不是青 ----------

    @Test
    fun `白主题强调色是中性墨色`() {
        val light = ecoColors(false, EcoAccent.White)
        assertTrue("白主题主色应接近中性灰", saturation(light.blue) < 0.12f)
        val dark = ecoColors(true, EcoAccent.White)
        assertTrue("白主题深色主色也应中性", saturation(dark.blue) < 0.18f)
    }

    @Test
    fun `实心前景总是自动选对比更高的一侧`() {
        // 前景取白还是深墨由亮度决定：同底色下应当不劣于「另一个选择」
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                val fg = c.onAccent
                val bg = c.blueDeep
                val other = if (luminance(fg) > 0.5f) Color(0xFF14181B) else Color(0xFFFFFFFF)
                assertTrue(
                    "${accent.label}(dark=$dark): onAccent 应选对比更高的一侧",
                    contrast(fg, bg) >= contrast(other, bg) - 0.001f,
                )
            }
        }
    }

    @Test
    fun `主按钮前景在整条渐变上都可读`() {
        // 按钮文字要同时压得住渐变两端（起点最亮、终点最暗）
        all.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val c = ecoColors(dark, accent)
                c.accentGradient.forEach { stop ->
                    assertTrue(
                        "${accent.label}(dark=$dark): 按钮文字在渐变某端不可读 " +
                            contrast(c.onAccentGrad, stop),
                        contrast(c.onAccentGrad, stop) >= 3f,
                    )
                }
            }
        }
    }
}
