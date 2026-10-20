package com.controleco.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.controleco.app.keepalive.Brand
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoPageLead
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPill
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSwitch
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 设置体系的共用视觉件（设置首页 / 权限 / 监听 App / 商户归类 / 开发者 / 关于 / 账户 / 导入 / 保活）。
 *
 * 与原型 ECO v5 的 `top--sub` + `sectionHead` + `.pane` 结构一一对应：
 * - 页面：EcoTopBar（micro + 标题 + 返回）+ 22dp 横向留白、32dp 收尾的可滚动内容；
 * - 分组：编号方块 + 标题 + 发丝线 + 右侧 micro（如 `NOTIFICATION ACCESS`）；
 * - 卡片：EcoPane 陶瓷面 / 19dp 圆角 / 发丝描边，行间 16dp 缩进分隔线；
 * - 行：13/700 标题 + 9.5 副标 + 10.5 取值 / 胶囊 / chevron，最小高度 48–54dp。
 *
 * 只做视觉外壳；所有状态、权限与数据读写仍留在各页面。
 */

/** 设置页统一的左右留白（原型 22px） */
val SettingsPagePadding: Dp = 22.dp

/** 设置页内容底部留白（原型 32px） */
val SettingsPageBottom: Dp = 32.dp

// ---------- 页面骨架 ----------

/**
 * 设置子页：返回按钮顶栏 + 可滚动内容列。
 * 页面底色由全局 EcoBackdrop 提供，这里不再铺 background，避免出现第二条色带。
 */
@Composable
fun SettingsPage(
    micro: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        EcoTopBar(micro = micro, title = title, onBack = onBack, action = action)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsPagePadding)
                .padding(top = 2.dp, bottom = SettingsPageBottom),
            content = content,
        )
    }
}

/**
 * 列表型设置页（商户归类）：整页只有**一个** LazyColumn 承担滚动。
 * 用来替换「verticalScroll 里再嵌 LazyColumn」的写法——两者嵌套会在列表非空时
 * 因无限高度约束直接崩（IllegalStateException: Vertically scrollable component was
 * measured with an infinity maximum height constraints）。
 */
@Composable
fun SettingsLazyPage(
    micro: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        EcoTopBar(micro = micro, title = title, onBack = onBack, action = action)
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = SettingsPagePadding,
                end = SettingsPagePadding,
                top = 2.dp,
                bottom = SettingsPageBottom,
            ),
            content = content,
        )
    }
}

// ---------- 分组 ----------

/**
 * 分组头（原型 `sectionHead`）：01 蓝色小方块 + 标题 + 发丝线 + 右侧 micro。
 * [aside] 就是设计稿的小号英文标签（如 `CAPTURE / 监听`、`SYSTEM / 系统`）。
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    aside: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = EcoType.SectionTitle,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .padding(horizontal = 10.dp)
                .weight(1f)
                .height(1.dp)
                .background(c.rule),
        )
        if (aside != null) {
            Text(
                aside,
                style = EcoType.SectionAside,
                color = c.quiet,
                maxLines = 1,
            )
        }
        trailing?.invoke(this)
    }
}

/** 分组卡片：EcoPane 陶瓷面 + 纵向内容列（行间用 [SettingsDivider]） */
@Composable
fun SettingsPane(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(19.dp),
    elevation: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    EcoPane(modifier.fillMaxWidth(), shape = shape, elevation = elevation) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** 行分隔线（与 EcoRowDivider 一致，左右缩进 16dp） */
@Composable
fun SettingsDivider(inset: Dp = 16.dp) {
    EcoRowDivider(inset = inset)
}

/** 页首说明（原型 `.page-lead`） */
@Composable
fun SettingsLead(text: String, modifier: Modifier = Modifier) {
    EcoPageLead(text, modifier)
}

/** 页尾说明（原型 `.page-note`） */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    EcoPageNote(text, modifier)
}

// ---------- 行 ----------

/**
 * 通用设置行：图标瓦片 + 标题/副标 + 取值 / 胶囊 / 自定义尾部 + chevron。
 * 整行可点（[onClick]），不可点时不带箭头；[danger] 只染标题。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMaxLines: Int = 2,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.eco.blue,
    trail: String? = null,
    trailColor: Color = MaterialTheme.eco.muted,
    pillText: String? = null,
    pillTone: EcoPillTone = EcoPillTone.Neutral,
    showChevron: Boolean = false,
    danger: Boolean = false,
    minHeight: Dp = 54.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (subtitle.isNullOrBlank()) 12.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            EcoIconTile(
                icon = icon,
                tint = iconTint,
                size = 34.dp,
                radius = 11.dp,
                iconSize = 17.dp,
                bare = true,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = EcoType.RowTitle,
                color = if (danger) c.clay else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = EcoType.RowSub,
                    color = c.muted,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (pillText != null) {
            Spacer(Modifier.width(8.dp))
            EcoPill(pillText, tone = pillTone)
        }
        if (trail != null) {
            Spacer(Modifier.width(8.dp))
            Text(trail, style = EcoType.RowTrail, color = trailColor, maxLines = 1)
        }
        trailing?.invoke(this)
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                EcoIcons.Chev,
                contentDescription = null,
                tint = c.quiet,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 导航行：标题 + 可选副标 + 右侧取值 + chevron */
@Composable
fun SettingsChoiceRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.eco.blue,
    value: String? = null,
) {
    SettingsRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        trail = value,
        showChevron = true,
        onClick = onClick,
    )
}

/** 开关行：标题 + 副标 + 右侧开关（可在开关前插一个行内动作） */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = EcoType.RowTitle,
                color = c.ink.copy(alpha = if (enabled) 1f else 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = EcoType.RowSub,
                    color = c.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        Spacer(Modifier.width(14.dp))
        EcoSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 取值行：左侧说明 + 右侧取值（原型 `.version-line`） */
@Composable
fun SettingsValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.eco.ink,
    emphasize: Boolean = true,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = EcoType.RowSub, color = c.muted, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = if (emphasize) EcoType.RowTrail.copy(fontWeight = FontWeight.W700) else EcoType.RowTrail,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** 危险动作行：文字说明 + 右侧陶土色动作词（删除 / 清空等） */
@Composable
fun SettingsDangerRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String = "删除",
    enabled: Boolean = true,
) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = EcoType.RowTitle,
                color = c.ink.copy(alpha = if (enabled) 1f else 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = EcoType.RowSub, color = c.muted, maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            actionText,
            style = EcoType.RowTrail.copy(fontWeight = FontWeight.W700),
            color = c.clay.copy(alpha = if (enabled) 1f else 0.35f),
            maxLines = 1,
        )
    }
}

/**
 * 状态行：标题 + 说明 + 右侧 pill；未授权时 pill 后跟「去开启 / 去授权」文字动作。
 * 权限页的每个通道都以它开头。
 */
@Composable
fun SettingsStatusRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    grantedText: String,
    missingText: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = EcoType.RowTitle,
                color = MaterialTheme.eco.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = EcoType.RowSub, color = MaterialTheme.eco.muted, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        EcoPill(
            text = if (granted) grantedText else missingText,
            tone = if (granted) EcoPillTone.Good else EcoPillTone.Neutral,
        )
        if (!granted) {
            Spacer(Modifier.width(4.dp))
            Text(
                actionText,
                style = EcoType.Pill.copy(fontWeight = FontWeight.W800),
                color = MaterialTheme.eco.blueDeep,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
    }
}

/** 折叠式教程：默认收起，展开后按序号列步骤（权限引导页共用） */
@Composable
fun SettingsTutorial(
    title: String,
    steps: List<String>,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val c = MaterialTheme.eco
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                EcoIcons.Chev,
                contentDescription = null,
                tint = c.muted,
                modifier = Modifier
                    .size(12.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = EcoType.RowTitle.copy(fontSize = EcoType.RowTitle.fontSize * 0.92f),
                color = c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "收起" else "展开",
                style = EcoType.Pill,
                color = c.quiet,
                maxLines = 1,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            ) {
                steps.forEachIndexed { index, step ->
                    if (index > 0) Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "${index + 1}.",
                            style = EcoType.Pill.copy(fontWeight = FontWeight.W800),
                            color = c.blue,
                            modifier = Modifier.width(16.dp),
                        )
                        Text(
                            step,
                            style = EcoType.RowSub,
                            color = c.muted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 小号软按钮（33 高、圆角 10）：保活页「申请忽略」这类行内动作 */
@Composable
fun SettingsSoftAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Box(
        modifier
            .height(33.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.blueSoft)
            .border(1.dp, c.blue.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = EcoType.Pill.copy(fontWeight = FontWeight.W800),
            color = c.blueDeep,
            maxLines = 1,
        )
    }
}

// ---------- 分品牌的系统设置路径 ----------

/** 分品牌的系统无障碍设置路径 */
fun a11yPathFor(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "或：设置 → 更多设置 → 无障碍 → 已下载的应用 → 我ECO 支付页面采集"
    Brand.HUAWEI, Brand.HONOR -> "或：设置 → 辅助功能 → 无障碍 → 已下载的服务 → 我ECO 支付页面采集"
    Brand.OPPO, Brand.ONEPLUS, Brand.REALME ->
        "或：设置 → 其他设置 → 无障碍 → 已下载的应用（版本间路径有差异，可在设置中搜索「无障碍」）"
    else -> "或：系统设置中搜索「无障碍」→ 已下载的服务/应用 → 我ECO 支付页面采集"
}

/** 分品牌的悬浮窗权限路径（悬浮确认卡的前提） */
fun overlayPathFor(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "或：设置 → 应用设置 → 应用管理 → 我ECO → 权限管理 → 「显示悬浮窗」允许"
    Brand.HUAWEI, Brand.HONOR -> "或：设置 → 应用和服务 → 应用管理 → 我ECO → 权限 → 「悬浮窗」允许"
    Brand.OPPO, Brand.ONEPLUS, Brand.REALME -> "或：手机管家 → 权限与隐私 → 悬浮窗管理，允许 我ECO"
    else -> "或：系统设置 → 应用 → 我ECO → 权限 → 「显示在其他应用上层」允许"
}
