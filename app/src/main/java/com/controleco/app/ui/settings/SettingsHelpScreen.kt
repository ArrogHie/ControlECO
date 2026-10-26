package com.controleco.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.R
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.SettingsNote
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SettingsSection
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 设置 → 帮助：把 `docs/说明/软件说明.md` 重排成随主题变化的原生页面。
 *
 * 版式：不套卡片，正文直接排在页面纸面上；分组用 `SettingsSection` 分隔。
 * 原文的两种标记都做成**背景色标注**——
 * - 反引号包裹的词（界面名 / 状态词，如 `剩余预算`）→ 主色浅底 + 深色字；
 * - `**加粗**` 包裹的强调句 → 暖金浅底 + 加粗深字。
 *
 * 原文只做版式适配，不改口径：预算三处读数、自动记账的重复/遗漏处理、
 * 微信红包、悬浮窗与银行软件限制都按文档原文。
 */

/** 帮助正文：比常规行副标大一档，行高放宽，便于长段落阅读 */
private val HelpBody: TextStyle = EcoType.RowSub.copy(fontSize = 10.5.sp, lineHeight = 18.sp)

/** 条目标题：略大于行标题，和正文拉开层级 */
private val HelpTitle: TextStyle = EcoType.RowTitle.copy(fontSize = 14.sp, lineHeight = 19.sp)

/** 正文里的行内标记：`term` 与 **强调** */
private val INLINE_MARK = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

@Composable
fun SettingsHelpScreen(onBack: () -> Unit) {
    SettingsPage(micro = "HELP", title = "帮助", onBack = onBack) {
        SettingsSection(title = "预算", aside = "BUDGET")
        HelpEntryBlock(
            entry = HelpEntry(
                title = "首页",
                icon = EcoIcons.Home,
                paragraphs = listOf(
                    "内圆环表示本月剩余天数，外圆环表示本月剩余预算。" +
                        "当剩余预算超出剩余天数时显示绿色，低于时显示黄色，严重低于时显示红色预警。",
                ),
                showRing = true,
            ),
            tint = MaterialTheme.eco.blue,
        )
        HelpEntryBlock(
            entry = HelpEntry(
                title = "预算计算",
                icon = EcoIcons.Pie,
                paragraphs = listOf(
                    "首页的 `剩余预算` 和 `本月已花` 仅计入未被 `退款` 和未被标记为 `不计入预算` 的支出账单，" +
                        "对于 `AA` 账单，仅计入**自己所支付的金额**。",
                    "统计页的 `实际支出` **不会计入**被 `退款` 的账单，**会计入**被标记为 `不计入预算` 的支出账单，" +
                        "但是对于 `AA` 账单，只计入**自己所支付的金额**。",
                    "账单页的 `本月结余` 为所有支出收入的金额总和，不考虑 `退款`、`不计入金额` 和 `AA`。",
                ),
            ),
            tint = MaterialTheme.eco.blue,
        )
        HelpEntryBlock(
            entry = HelpEntry(
                title = "每月首日",
                icon = EcoIcons.Calendar,
                paragraphs = listOf(
                    "设置每月首日为第 x 日后，本月的 x 日起至次月的 x-1 日内的账单统计到本月内。" +
                        "对于次月没有第 x 日的，将次月末日前作为本月结束。",
                ),
            ),
            tint = MaterialTheme.eco.blue,
        )

        SettingsSection(title = "自动记账", aside = "CAPTURE")
        HelpCallout(
            "因支付场景较多，很难完全处理，因此推荐使用“悬浮窗入账”，而不是“自动入账”。" +
                "以及请打开“入账提醒”方便处理。",
        )
        HelpEntryBlock(
            entry = HelpEntry(
                title = "权限与保活",
                icon = EcoIcons.Shield,
                paragraphs = listOf(
                    "为了确保自动记账正常运行，请授予软件所需的权限，详细在 `设置`-`权限` 页。" +
                        "为了防止软件被杀后台后需要重新启动权限，请处理软件的保活与自启动相关权限，" +
                        "详细在 `设置`-`保活与自启动`。",
                    "当无法进行自动记账或者总是需要去重新打开无障碍功能时，请优先检查 `保活与自启动` 相关。",
                ),
            ),
            tint = MaterialTheme.eco.jade,
        )
        HelpEntryBlock(
            entry = HelpEntry(
                title = "重复记账",
                icon = EcoIcons.Copy,
                paragraphs = listOf(
                    "当出现重复记账时，如果不属于下面的两种情况之一，请在 `设置`-`开发者功能`-`溯源` 内" +
                        "找到重复记账账单的捕获信息，然后联系作者修复。",
                    "部分软件（例如微信），支付成功页没有时间与账单号，对于同一金额的支付成功页，" +
                        "无法判断是同一账单还是相同金额的不同账单。因此记账规则为：短时间内同一金额不进行多次抓取，" +
                        "但是超过时间阈值后会再次抓取。为了防止重复记账，结账后请及时退出支付成功页，" +
                        "长时间不退出支付成功页或者一段时间后再次打开支付成功页会导致重复记账。",
                    "对于部分电商平台，使用 微信/支付宝/云闪付 支付后，电商平台仍会弹出支付成功页。" +
                        "因为这两次支付成功页不属于同一软件，所以可能会被认为是不同订单。" +
                        "虽然针对这种情况已经补充了去重规则，但是仍可能有概率出现记重。" +
                        "最好的办法是只使用 微信/支付宝/云闪付 而不使用电商平台的方式进行支付" +
                        "（例如“美团支付”“多多支付”等）。",
                ),
            ),
            tint = MaterialTheme.eco.jade,
        )
        HelpEntryBlock(
            entry = HelpEntry(
                title = "遗漏记账",
                icon = EcoIcons.Warning,
                paragraphs = listOf(
                    "当出现遗漏记账时，如果不属于下面的两种情况之一，请打开 `设置`-`开发者功能`-`记录所有页面`，" +
                        "然后再次进行支付。支付后进入 `设置`-`开发者功能`-`捕获日志` 内找到支付相关的页面通知和短信信息，" +
                        "然后联系作者修复。如果找不到相关的信息，请联系作者说明支付的软件和机型。",
                    "对于部分电商平台，因缺乏易识别的支付成功页或者支付通知，所以无法进行自动识别记账（例如拼多多）。" +
                        "设置内的 `监听的APP` 只表示可以监听，但是对于电商平台的支付稳定性较差，" +
                        "建议使用 微信/支付宝/云闪付 支付。",
                    "当支付成功时，请在支付成功页短暂停留一秒，以防止软件未能及时捕获到订单。",
                    "对于支付宝，部分机型会保护支付宝的支付成功页，会有小概率不能正常捕获订单，" +
                        "该问题暂未找到解决方法，但是概率较小，影响不大。",
                ),
            ),
            tint = MaterialTheme.eco.amber,
        )
        HelpEntryBlock(
            entry = HelpEntry(
                title = "微信红包",
                icon = EcoIcons.Gift,
                paragraphs = listOf(
                    "因为微信红包没有信息可以识别是曾经被领取的红包还是现在被领取的红包，" +
                        "所以每次打开微信红包领取页时，都会弹出悬浮窗询问是否需要记录。",
                ),
            ),
            tint = MaterialTheme.eco.amber,
        )
        HelpEntryBlock(
            entry = HelpEntry(
                title = "悬浮窗相关",
                icon = EcoIcons.Overlay,
                paragraphs = listOf(
                    "悬浮窗被设定总是延迟弹出（延迟约 1-2s）。对于部分被保护的支付页面，手机会不允许弹出悬浮窗，" +
                        "此时如果正常捕获会发送通知。",
                ),
            ),
            tint = MaterialTheme.eco.amber,
        )

        SettingsSection(title = "兼容性", aside = "COMPATIBILITY")
        HelpEntryBlock(
            entry = HelpEntry(
                title = "银行软件无法使用",
                icon = EcoIcons.Wallet,
                paragraphs = listOf(
                    "因为该软件需要获取页面进行账单捕获，所以对于一些安全性较高的软件（例如招商银行 app）" +
                        "此时会无法使用。对于此种情况请在后台关掉该软件或者暂时关闭软件的无障碍权限。",
                ),
            ),
            tint = MaterialTheme.eco.blue,
        )

        SettingsNote(
            "遇到说明之外的问题，请在“设置 → 开发者功能”留存捕获日志后联系作者。",
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

// ---------- 数据 ----------

/** 一个说明条目：图标 + 标题 + 若干段正文（正文支持 `term` 与 **强调**） */
private data class HelpEntry(
    val title: String,
    val icon: ImageVector,
    val paragraphs: List<String>,
    /** 是否在正文前插入首页预算环示意图 */
    val showRing: Boolean = false,
)

// ---------- 条目 ----------

/**
 * 一个条目：图标 + 标题 + （可选）预算环图 + 多段正文。
 * 不套卡片——条目直接排在纸面上，靠 [ENTRY_GAP] 与分组头区分层次。
 */
@Composable
private fun HelpEntryBlock(entry: HelpEntry, tint: Color) {
    val c = MaterialTheme.eco
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                entry.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(entry.title, style = HelpTitle, color = c.ink, maxLines = 1)
        }
        if (entry.showRing) {
            Spacer(Modifier.height(12.dp))
            HelpRingImage()
        }
        entry.paragraphs.forEach { paragraph ->
            Spacer(Modifier.height(12.dp))
            HelpText(paragraph)
        }
    }
    Spacer(Modifier.height(ENTRY_GAP))
}

/** 条目之间的纵向间隔：正文段落 12dp，条目之间再放大一档，分段更明确 */
private val ENTRY_GAP = 24.dp

/** 首页预算环示意图（原图来自 docs/说明/image.png，白底截图，只做圆角裁切，不加卡片描边） */
@Composable
private fun HelpRingImage() {
    Image(
        painter = painterResource(R.drawable.help_budget_ring),
        contentDescription = "首页预算环：内圆环剩余天数，外圆环剩余预算",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
    )
}

/**
 * 正文：`term` → 主色浅底 + 深色字；**强调** → 暖金浅底 + 加粗深字。
 * 两种标记都不改变字号，只靠底色与字重区分，长段落里也能一眼扫到关键口径。
 */
@Composable
private fun HelpText(text: String) {
    val c = MaterialTheme.eco
    val annotated = remember(text, c.blueSoft, c.blueDeep, c.amberSoft, c.ink2) {
        buildAnnotatedString {
            var cursor = 0
            INLINE_MARK.findAll(text).forEach { match ->
                if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
                val term = match.groups[2]
                if (term != null) {
                    // 反引号：界面名 / 状态词
                    withStyle(
                        SpanStyle(background = c.blueSoft, color = c.blueDeep, fontWeight = FontWeight.W600),
                    ) { append(term.value) }
                } else {
                    // 星号：口径强调
                    withStyle(
                        SpanStyle(background = c.amberSoft, color = c.ink2, fontWeight = FontWeight.W700),
                    ) { append(match.groups[1]!!.value) }
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
    Text(annotated, style = HelpBody, color = c.muted)
}

/** 推荐口径提示：不加卡片，用暖金浅底整段标注（与「设置」卡固定暖金同源） */
@Composable
private fun HelpCallout(text: String) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = ENTRY_GAP),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            EcoIcons.Warning,
            contentDescription = null,
            tint = c.amber,
            modifier = Modifier
                .padding(top = 3.dp)
                .size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(background = c.amberSoft, color = c.ink2, fontWeight = FontWeight.W700)) {
                    append(text)
                }
            },
            style = HelpBody,
            color = c.ink2,
        )
    }
}
