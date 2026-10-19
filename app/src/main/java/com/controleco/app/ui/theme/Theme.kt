package com.controleco.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.data.budget.Budget
import kotlin.math.pow

/**
 * ECO v5 设计系统。
 *
 * 纸面/墨色/青蓝/玉绿/琥珀/陶土六组语义色，深浅两套；强调色有 6 套可切换。
 * 业务代码只通过 [MaterialTheme.eco]（纸面、轨、强调色族等）与 [MaterialTheme.accBook]
 * （旧语义：收入/警告/危险）取色，禁止散落硬编码。金额统一 tabular-nums。
 */

// ---------- 主题色 ----------

enum class EcoAccent(
    val id: String,
    val label: String,
    val note: String,
    val light: BlueFamily,
    val dark: BlueFamily,
    val heroA: Color,
    val heroB: Color,
    val heroC: Color,
) {
    Tide(
        "default", "潮汐", "青蓝",
        BlueFamily(0xFF24788A, 0xFF145B70, 0xFF419DAB, 0xFFA0DFDE, 0xFFE0F3F2, 0xFFECF7F4),
        BlueFamily(0xFF88D5DF, 0xFFB8EDF0, 0xFF62B8C6, 0xFFC0F4E9, 0xFF264E54, 0xFF20434A),
        Color(0xFF103945), Color(0xFF165A60), Color(0xFF1B766F),
    ),
    Meadow(
        "meadow", "松林", "玉绿",
        BlueFamily(0xFF2E8069, 0xFF22624E, 0xFF479C7B, 0xFFA3D9BE, 0xFFE6F4EA, 0xFFEEF8F2),
        BlueFamily(0xFF94D8B5, 0xFFC5F0D8, 0xFF78BD9B, 0xFFB7E7CB, 0xFF29443B, 0xFF213A32),
        Color(0xFF173B34), Color(0xFF216956), Color(0xFF339175),
    ),
    Iris(
        "iris", "鸢尾", "暮紫",
        BlueFamily(0xFF7964B1, 0xFF55438A, 0xFF9281C1, 0xFFCBBFEC, 0xFFEFEAF9, 0xFFF8F4FD),
        BlueFamily(0xFFC5B1E8, 0xFFE4D9FA, 0xFFAC94DA, 0xFFDCCDF4, 0xFF383147, 0xFF2D2939),
        Color(0xFF292840), Color(0xFF59507C), Color(0xFF8470A6),
    ),
    Coral(
        "coral", "珊瑚", "陶红",
        BlueFamily(0xFFB66678, 0xFF924A5E, 0xFFCA8B94, 0xFFF0BFC2, 0xFFFAE9EB, 0xFFFFF4F1),
        BlueFamily(0xFFF0ADBB, 0xFFFFD5DB, 0xFFD78EA5, 0xFFF6C6D1, 0xFF47333B, 0xFF382A32),
        Color(0xFF43303F), Color(0xFF874C65), Color(0xFFBB7180),
    ),
    Amber(
        "amber", "暖金", "琥珀",
        BlueFamily(0xFFB77E42, 0xFF885A2A, 0xFFC29461, 0xFFEFD1A0, 0xFFF8EDDB, 0xFFFFF8EB),
        BlueFamily(0xFFEAC38E, 0xFFFFE3AF, 0xFFCEA66A, 0xFFF4DBAB, 0xFF43392B, 0xFF352F27),
        Color(0xFF443627), Color(0xFF8A5B3B), Color(0xFFBA8A51),
    ),
    Sky(
        "sky", "晴海", "天青",
        BlueFamily(0xFF317FAB, 0xFF235E89, 0xFF579EC3, 0xFFA7D9EC, 0xFFE4F2F9, 0xFFF0F8FD),
        BlueFamily(0xFFA4D8F3, 0xFFD0EEFC, 0xFF84B8D9, 0xFFC1E3F5, 0xFF294353, 0xFF233947),
        Color(0xFF183548), Color(0xFF245E81), Color(0xFF368DB4),
    ),
    ;

    companion object {
        fun fromId(id: String?): EcoAccent = entries.firstOrNull { it.id == id } ?: Tide
    }
}

@Immutable
data class BlueFamily(
    val blue: Long,
    val deep: Long,
    val mid: Long,
    val hi: Long,
    val soft: Long,
    val wash: Long,
)

// ---------- 扩展色 ----------

@Immutable
data class EcoColors(
    val isDark: Boolean,
    val accent: EcoAccent,
    val paper: Color,
    val paperDeep: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val surfaceInset: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val quiet: Color,
    val rule: Color,
    val ruleStrong: Color,
    val track: Color,
    val blue: Color,
    val blueDeep: Color,
    val blue2: Color,
    val blueHi: Color,
    val blueSoft: Color,
    val blueWash: Color,
    val jade: Color,
    val jadeHi: Color,
    val jadeSoft: Color,
    val amber: Color,
    val amberHi: Color,
    val amberSoft: Color,
    val clay: Color,
    val clayHi: Color,
    val claySoft: Color,
    val violet: Color,
    val violetSoft: Color,
    val heroA: Color,
    val heroB: Color,
    val heroC: Color,
)

val LocalEcoColors = staticCompositionLocalOf {
    ecoColors(false, EcoAccent.Tide)
}

val MaterialTheme.eco: EcoColors
    @Composable get() = LocalEcoColors.current

fun ecoColors(dark: Boolean, accent: EcoAccent): EcoColors {
    val b = if (dark) accent.dark else accent.light
    return if (dark) {
        EcoColors(
            isDark = true,
            accent = accent,
            paper = Color(0xFF10272D),
            paperDeep = Color(0xFF142F35),
            surface = Color(0xFF1B363C),
            surface2 = Color(0xFF224047),
            surface3 = Color(0xFF2D4B51),
            surfaceInset = Color(0xFF1A373C),
            ink = Color(0xFFF2F8F2),
            ink2 = Color(0xFFD9E6DF),
            muted = Color(0xFFB2C8C2),
            quiet = Color(0xFF8CA7A5),
            rule = Color(0xFF35555A),
            ruleStrong = Color(0xFF496A6D),
            track = Color(0xFF355358),
            blue = Color(b.blue),
            blueDeep = Color(b.deep),
            blue2 = Color(b.mid),
            blueHi = Color(b.hi),
            blueSoft = Color(b.soft),
            blueWash = Color(b.wash),
            jade = Color(0xFF88DBB0),
            jadeHi = Color(0xFFBBE9C9),
            jadeSoft = Color(0xFF255044),
            amber = Color(0xFFF0C278),
            amberHi = Color(0xFFFFE1A0),
            amberSoft = Color(0xFF4A4130),
            clay = Color(0xFFF39F8E),
            clayHi = Color(0xFFFFC4AE),
            claySoft = Color(0xFF503C3B),
            violet = Color(0xFFC4B3DF),
            violetSoft = Color(0xFF3C354B),
            heroA = accent.heroA,
            heroB = accent.heroB,
            heroC = accent.heroC,
        )
    } else {
        EcoColors(
            isDark = false,
            accent = accent,
            paper = Color(0xFFF7F8F3),
            paperDeep = Color(0xFFEAF2EC),
            surface = Color(0xFFFFFEFA),
            surface2 = Color(0xFFF0F6F2),
            surface3 = Color(0xFFE4EFE9),
            surfaceInset = Color(0xFFEAF3EE),
            ink = Color(0xFF142F37),
            ink2 = Color(0xFF38545A),
            muted = Color(0xFF607B7D),
            quiet = Color(0xFF899E9C),
            rule = Color(0xFFDCE9E1),
            ruleStrong = Color(0xFFC6DAD0),
            track = Color(0xFFDFE9E1),
            blue = Color(b.blue),
            blueDeep = Color(b.deep),
            blue2 = Color(b.mid),
            blueHi = Color(b.hi),
            blueSoft = Color(b.soft),
            blueWash = Color(b.wash),
            jade = Color(0xFF268E70),
            jadeHi = Color(0xFF98DDB1),
            jadeSoft = Color(0xFFE4F5EB),
            amber = Color(0xFFB98937),
            amberHi = Color(0xFFF4D58D),
            amberSoft = Color(0xFFFAF0DA),
            clay = Color(0xFFCC6854),
            clayHi = Color(0xFFF1A28F),
            claySoft = Color(0xFFFCEAE4),
            violet = Color(0xFF907BA8),
            violetSoft = Color(0xFFF1EAF5),
            heroA = accent.heroA,
            heroB = accent.heroB,
            heroC = accent.heroC,
        )
    }
}

// ---------- 旧语义色（保留给尚未迁移的组件与业务逻辑） ----------

@Immutable
data class AccBookColors(
    val income: Color,
    val warning: Color,
    val critical: Color,
    val criticalAlert: Color,
    val primaryStrong: Color,
    val hairline: Color,
    val ringTrack: Color,
    val festiveRed: Color,
    val overlayInk: Color,
    val cardOnLight: CardTones,
    val cardOnDark: CardTones,
    val categoryPalette: List<Color>,
)

internal fun lightAccBookColors() = AccBookColors(
    income = Color(0xFF268E70),
    warning = Color(0xFFB98937),
    critical = Color(0xFFCC6854),
    criticalAlert = Color(0xFFB44A38),
    primaryStrong = Color(0xFF145B70),
    hairline = Color(0xFFDCE9E1),
    ringTrack = Color(0xFFDFE9E1),
    festiveRed = Color(0xFFCC6854),
    overlayInk = Color(0xFFFFFFFF),
    cardOnLight = CardTones(
        ink = Color(0xFF142F37),
        sub = Color(0xFF38545A),
        income = Color(0xFF268E70),
        expense = Color(0xFFB44A38),
        scrim = Color(0xFFFFFFFF),
        pillContainer = Color(0x2624788A),
        pillSelected = Color(0xFF145B70),
        pillSelectedContent = Color(0xFFFFFFFF),
    ),
    cardOnDark = CardTones(
        ink = Color(0xFFF2F8F2),
        sub = Color(0xCCF2F8F2),
        income = Color(0xFF98DDB1),
        expense = Color(0xFFF1A28F),
        scrim = Color(0xFF000000),
        pillContainer = Color(0x33FFFFFF),
        pillSelected = Color(0xFFFFFFFF),
        pillSelectedContent = Color(0xFF142F37),
    ),
    categoryPalette = listOf(
        Color(0xFF24788A), // 青蓝
        Color(0xFF268E70), // 玉绿
        Color(0xFFB98937), // 琥珀
        Color(0xFFCC6854), // 陶土
        Color(0xFF907BA8), // 鸢尾
        Color(0xFF317FAB), // 晴海
        Color(0xFFB66678), // 珊瑚
        Color(0xFF2E8069), // 松林
    ),
)

internal fun darkAccBookColors() = AccBookColors(
    income = Color(0xFF88DBB0),
    warning = Color(0xFFF0C278),
    critical = Color(0xFFF39F8E),
    criticalAlert = Color(0xFFFF8A73),
    primaryStrong = Color(0xFFB8EDF0),
    hairline = Color(0xFF35555A),
    ringTrack = Color(0xFF355358),
    festiveRed = Color(0xFFF39F8E),
    overlayInk = Color(0xFFFFFFFF),
    cardOnLight = CardTones(
        ink = Color(0xFF142F37),
        sub = Color(0xFF38545A),
        income = Color(0xFF268E70),
        expense = Color(0xFFB44A38),
        scrim = Color(0xFFFFFFFF),
        pillContainer = Color(0x2624788A),
        pillSelected = Color(0xFF145B70),
        pillSelectedContent = Color(0xFFFFFFFF),
    ),
    cardOnDark = CardTones(
        ink = Color(0xFFF2F8F2),
        sub = Color(0xCCF2F8F2),
        income = Color(0xFF98DDB1),
        expense = Color(0xFFF1A28F),
        scrim = Color(0xFF000000),
        pillContainer = Color(0x33FFFFFF),
        pillSelected = Color(0xFFFFFFFF),
        pillSelectedContent = Color(0xFF142F37),
    ),
    categoryPalette = listOf(
        Color(0xFF88D5DF),
        Color(0xFF88DBB0),
        Color(0xFFF0C278),
        Color(0xFFF39F8E),
        Color(0xFFC4B3DF),
        Color(0xFFA4D8F3),
        Color(0xFFF0ADBB),
        Color(0xFF94D8B5),
    ),
)

val LocalAccBookColors = staticCompositionLocalOf { lightAccBookColors() }

val MaterialTheme.accBook: AccBookColors
    @Composable get() = LocalAccBookColors.current

@Immutable
data class CardTones(
    val ink: Color,
    val sub: Color,
    val income: Color,
    val expense: Color,
    val scrim: Color,
    val pillContainer: Color,
    val pillSelected: Color,
    val pillSelectedContent: Color,
)

fun categoryColor(palette: List<Color>, categoryName: String): Color {
    if (palette.isEmpty()) return Color.Unspecified
    return palette[preferredSlot(palette.size, categoryName)]
}

fun ringColors(palette: List<Color>, fixed: Map<String, Color>, names: List<String>): Map<String, Color> {
    val out = LinkedHashMap<String, Color>(fixed)
    if (names.isEmpty()) return out
    if (palette.isEmpty()) return out
    val used = ArrayList<Color>(fixed.values)
    names.filter { it.isNotEmpty() }.distinct().sorted().forEach { name ->
        if (out.containsKey(name)) return@forEach
        val slot = palette.indices.maxByOrNull { candidate ->
            if (used.isEmpty()) 0f else used.minOf { colorDistance(palette[candidate], it) }
        } ?: 0
        out[name] = palette[slot]
        used += palette[slot]
    }
    return out
}

internal const val RING_MIN_DELTA = 26f

internal fun colorDistance(a: Color, b: Color): Float {
    val la = toLab(a)
    val lb = toLab(b)
    val dl = la[0] - lb[0]
    val da = la[1] - lb[1]
    val db = la[2] - lb[2]
    return kotlin.math.sqrt(dl * dl + da * da + db * db)
}

private fun toLab(color: Color): FloatArray {
    val r = linearize(color.red)
    val g = linearize(color.green)
    val b = linearize(color.blue)
    val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
    val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
    val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
    val fx = labF(x)
    val fy = labF(y)
    val fz = labF(z)
    return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
}

private fun linearize(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

private fun labF(t: Float): Float =
    if (t > 0.008856f) t.pow(1f / 3f) else 7.787f * t + 16f / 116f

internal fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val rawHue = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf(if (rawHue < 0f) rawHue + 360f else rawHue, if (max == 0f) 0f else d / max, max)
}

internal fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
    val c = v * s
    val hp = (((h % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val r: Float
    val g: Float
    val b: Float
    when (hp.toInt()) {
        0 -> { r = c; g = x; b = 0f }
        1 -> { r = x; g = c; b = 0f }
        2 -> { r = 0f; g = c; b = x }
        3 -> { r = 0f; g = x; b = c }
        4 -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    val m = v - c
    return floatArrayOf(r + m, g + m, b + m)
}

private fun preferredSlot(size: Int, name: String): Int {
    if (size <= 0) return 0
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) and 0x7FFFFFFF
    return h % size
}

fun toneColor(tone: Budget.Tone, colors: AccBookColors): Color = when (tone) {
    Budget.Tone.OK -> colors.income
    Budget.Tone.MID -> colors.warning
    Budget.Tone.LOW -> colors.critical
}

/** 新设计：档位色直接取纸面语义色 */
fun toneColor(tone: Budget.Tone, colors: EcoColors): Color = when (tone) {
    Budget.Tone.OK -> colors.jade
    Budget.Tone.MID -> colors.amber
    Budget.Tone.LOW -> colors.clay
}

// ---------- Material 配色 ----------

private fun lightScheme(eco: EcoColors) = lightColorScheme(
    background = eco.paper,
    onBackground = eco.ink,
    surface = eco.surface,
    onSurface = eco.ink,
    surfaceVariant = eco.surface2,
    onSurfaceVariant = eco.muted,
    surfaceDim = eco.paperDeep,
    surfaceBright = eco.surface,
    surfaceContainerLowest = eco.surface,
    surfaceContainerLow = eco.surface2,
    surfaceContainer = eco.surface2,
    surfaceContainerHigh = eco.surface3,
    surfaceContainerHighest = eco.surface3,
    surfaceTint = eco.blue,
    primary = eco.blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = eco.blueSoft,
    onPrimaryContainer = eco.blueDeep,
    secondary = eco.jade,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = eco.jadeSoft,
    onSecondaryContainer = eco.ink,
    tertiary = eco.amber,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = eco.amberSoft,
    onTertiaryContainer = eco.ink,
    error = eco.clay,
    onError = Color(0xFFFFFFFF),
    errorContainer = eco.claySoft,
    onErrorContainer = eco.ink,
    outline = eco.ruleStrong,
    outlineVariant = eco.rule,
    inverseSurface = eco.ink,
    inverseOnSurface = eco.paper,
    inversePrimary = eco.blueHi,
)

private fun darkScheme(eco: EcoColors) = darkColorScheme(
    background = eco.paper,
    onBackground = eco.ink,
    surface = eco.surface,
    onSurface = eco.ink,
    surfaceVariant = eco.surface2,
    onSurfaceVariant = eco.muted,
    surfaceDim = eco.paperDeep,
    surfaceBright = eco.surface3,
    surfaceContainerLowest = eco.paperDeep,
    surfaceContainerLow = eco.surface,
    surfaceContainer = eco.surface,
    surfaceContainerHigh = eco.surface2,
    surfaceContainerHighest = eco.surface3,
    surfaceTint = eco.blue,
    primary = eco.blue,
    onPrimary = eco.paper,
    primaryContainer = eco.blueSoft,
    onPrimaryContainer = eco.blueDeep,
    secondary = eco.jade,
    onSecondary = eco.paper,
    secondaryContainer = eco.jadeSoft,
    onSecondaryContainer = eco.jade,
    tertiary = eco.amber,
    onTertiary = eco.paper,
    tertiaryContainer = eco.amberSoft,
    onTertiaryContainer = eco.amber,
    error = eco.clay,
    onError = eco.paper,
    errorContainer = eco.claySoft,
    onErrorContainer = eco.clay,
    outline = eco.ruleStrong,
    outlineVariant = eco.rule,
    inverseSurface = eco.ink,
    inverseOnSurface = eco.paper,
    inversePrimary = eco.blueDeep,
)

// ---------- 字体 ----------

private fun tracking(size: Float, em: Float) = (size * em).sp

private fun ecoText(
    size: Float,
    weight: Int,
    em: Float = 0f,
    lineHeight: Float = size * 1.25f,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking(size, em),
    lineHeight = lineHeight.sp,
    fontFeatureSettings = "tnum",
)

/** 命名版式：新代码直接引用，避免每处猜字号/字距 */
object EcoType {
    val TopTitle = ecoText(28f, 800, -0.064f, 32f)
    val SubTitle = ecoText(23f, 800, -0.064f, 28f)
    val TopLow = ecoText(28f, 500, -0.064f, 32f)
    val Micro = ecoText(9f, 800, 0.145f, 12f)
    val MicroQuiet = ecoText(9f, 700, 0.17f, 12f)
    val SectionNo = ecoText(8f, 800, 0f, 10f)
    val SectionTitle = ecoText(13f, 800, -0.015f, 17f)
    val SectionAside = ecoText(8.5f, 500, 0f, 11f)
    val RowTitle = ecoText(13f, 700, -0.014f, 17f)
    val RowSub = ecoText(9.5f, 400, 0f, 15f)
    val RowTrail = ecoText(10.5f, 500, 0f, 14f)
    val PageLead = ecoText(10f, 400, 0f, 17.5f)
    val PageNote = ecoText(9f, 400, 0f, 15f)
    val HeroAmount = ecoText(48f, 800, -0.078f, 48f)
    val MeterMoney = ecoText(39f, 700, -0.065f, 46f)
    val TodayAmount = ecoText(47f, 700, -0.065f, 51f)
    val CatTotal = ecoText(38f, 800, -0.065f, 39f)
    val AssetAmount = ecoText(48f, 800, -0.07f, 53f)
    val CoverMoney = ecoText(32f, 800, -0.07f, 32f)
    val ConclusionAmount = ecoText(29f, 700, -0.065f, 35f)
    val PendingAmount = ecoText(16f, 800, -0.05f, 20f)
    val BillAmount = ecoText(13.5f, 800, -0.025f, 18f)
    val CatAmount = ecoText(16.5f, 800, -0.045f, 21f)
    val KeyAmount = ecoText(53f, 800, -0.075f, 53f)
    val EditAmount = ecoText(33f, 800, -0.06f, 40f)
    val ConfirmAmount = ecoText(29f, 800, -0.06f, 35f)
    val PendingCount = ecoText(35f, 800, -0.07f, 37f)
    val DailyValue = ecoText(31f, 800, -0.065f, 35f)
    val Seg = ecoText(10.5f, 700, 0f, 14f)
    val SegSmall = ecoText(10f, 700, 0f, 13f)
    val Chip = ecoText(10.5f, 700, 0f, 14f)
    val Pill = ecoText(9f, 700, 0.02f, 12f)
    val Btn = ecoText(12f, 800, 0f, 16f)
    val FieldLabel = ecoText(10f, 700, 0.04f, 13f)
    val Input = ecoText(12f, 500, 0f, 16f)
    val AssetLabel = ecoText(11f, 700, 0.16f, 14f)
    val MeterLabel = ecoText(11f, 700, 0.06f, 14f)
    val DatePill = ecoText(9.5f, 700, 0.02f, 12f)
    val SheetTitle = ecoText(20f, 800, -0.05f, 24f)
    val SheetSub = ecoText(8.5f, 400, 0f, 13f)
    val ModalTitle = ecoText(18f, 800, -0.04f, 22f)
    val ModalBody = ecoText(10f, 400, 0f, 17f)
    val EmptyTitle = ecoText(13f, 700, 0f, 17f)
    val EmptyBody = ecoText(12f, 400, 0f, 21f)
    val LogChannel = ecoText(7.5f, 700, 0.04f, 9f)
    val TraceTime = ecoText(8f, 400, 0f, 11f)
    val Nav = ecoText(10f, 600, 0f, 12f)
    val SheetTitleSmall = ecoText(17f, 800, -0.04f, 21f)
}

private fun appTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = EcoType.HeroAmount,
        displayMedium = EcoType.MeterMoney,
        displaySmall = EcoType.ConclusionAmount,
        headlineLarge = EcoType.TopTitle,
        headlineMedium = EcoType.SubTitle,
        headlineSmall = EcoType.SheetTitle,
        titleLarge = EcoType.ModalTitle,
        titleMedium = EcoType.CatAmount,
        titleSmall = EcoType.RowTitle,
        bodyLarge = ecoText(13f, 500, 0f, 19f),
        bodyMedium = ecoText(11f, 400, 0f, 16f),
        bodySmall = EcoType.RowSub,
        labelLarge = EcoType.Btn,
        labelMedium = EcoType.Chip,
        labelSmall = EcoType.Pill,
    )
}

private val appShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(19.dp),
    extraLarge = RoundedCornerShape(25.dp),
)

@Composable
fun AccBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: EcoAccent = EcoAccent.Tide,
    content: @Composable () -> Unit,
) {
    val eco = ecoColors(darkTheme, accent)
    val colorScheme = if (darkTheme) darkScheme(eco) else lightScheme(eco)
    val extended = if (darkTheme) darkAccBookColors() else lightAccBookColors()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalEcoColors provides eco,
        LocalAccBookColors provides extended,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(),
            shapes = appShapes,
            content = content,
        )
    }
}
