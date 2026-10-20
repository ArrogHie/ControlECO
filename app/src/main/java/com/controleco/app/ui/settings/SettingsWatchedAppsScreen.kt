package com.controleco.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.capture.AppGroup
import com.controleco.app.capture.CaptureAppCatalog
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.SettingsDivider
import com.controleco.app.ui.common.SettingsLead
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SettingsPane
import com.controleco.app.ui.common.SettingsSection
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoSwitch
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.accBook
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.eco

/**
 * 设置 → 监听的 App：按应用开关采集范围（支付 / 银行 / 电商外卖三组）。
 *
 * 语义（与 CaptureAppCatalog 一致）：本页只覆盖"包名可枚举"的通知/页面通道；
 * 目录外的来源（银行短信、未知银行 App 的动账推送）由各自通道的签名闸门决定，不受本页影响。
 * 写盘即时生效（SharedPreferences），tick 只用来刷新本页快照。
 */
@Composable
fun SettingsWatchedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val watched = remember(tick) { CaptureAppCatalog.watched(context) }
    var query by rememberSaveable { mutableStateOf("") }

    val apps = remember { CaptureAppCatalog.apps }
    val filtered = remember(apps, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.contains(keyword, ignoreCase = true) || it.packageName.contains(keyword, ignoreCase = true)
            }
        }
    }
    val watchedInCatalog = remember(watched, apps) { apps.count { it.packageName in watched } }

    fun setAll(on: Boolean) {
        AppGroup.entries.forEach { group -> CaptureAppCatalog.setGroupWatched(context, group, on) }
        tick++
    }

    SettingsPage(micro = "WATCHED APPS", title = "监听的 App", onBack = onBack) {
        SearchBox(value = query, onValueChange = { query = it })
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$watchedInCatalog / ${apps.size} 已监听",
                style = EcoType.SectionAside,
                color = MaterialTheme.eco.muted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            InlineAction("全部开启", onClick = { setAll(true) })
            Spacer(Modifier.width(6.dp))
            InlineAction("全部关闭", onClick = { setAll(false) }, color = MaterialTheme.eco.muted)
        }

        if (filtered.isEmpty()) {
            SettingsPane {
                EcoEmpty(
                    title = if (query.isBlank()) "目录里还没有应用" else "没有匹配的应用",
                    body = if (query.isBlank()) "更新应用后目录会自动补齐" else "换个应用名或包名试试",
                )
            }
        } else {
            var sectionIndex = 0
            AppGroup.entries.forEach { group ->
                val groupApps = filtered.filter { it.group == group }
                if (groupApps.isEmpty()) return@forEach
                sectionIndex += 1
                val onCount = groupApps.count { it.packageName in watched }
                val allOn = onCount == groupApps.size

                SettingsSection(
                    number = "%02d".format(sectionIndex),
                    title = group.title,
                    aside = "$onCount / ${groupApps.size}",
                )
                SettingsPane {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "整组",
                                    style = EcoType.RowTitle.copy(fontSize = 11.5.sp),
                                    color = MaterialTheme.eco.ink,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "$onCount / ${groupApps.size} 已监听",
                                    style = EcoType.SectionAside,
                                    color = MaterialTheme.eco.muted,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.height(1.dp))
                            Text(
                                groupHint(group),
                                style = EcoType.RowSub.copy(fontSize = 8.5.sp),
                                color = MaterialTheme.eco.quiet,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        InlineAction(
                            text = if (allOn) "全部关闭" else "全部开启",
                            onClick = {
                                CaptureAppCatalog.setGroupWatched(context, group, !allOn)
                                tick++
                            },
                        )
                    }
                    groupApps.forEach { app ->
                        SettingsDivider()
                        AppRow(
                            label = app.label,
                            checked = app.packageName in watched,
                            onCheckedChange = { on ->
                                CaptureAppCatalog.setWatched(context, app.packageName, on)
                                tick++
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 应用行：首字色块（按名称取色）+ 名称 + 小号开关 */
@Composable
private fun AppRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = MaterialTheme.eco
    val tint = categoryColor(MaterialTheme.accBook.categoryPalette, label)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = if (c.isDark) 0.22f else 0.12f))
                .border(1.dp, tint.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.take(1),
                style = EcoType.RowTitle.copy(fontSize = 12.sp, fontWeight = FontWeight.W700),
                color = tint,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            label,
            style = EcoType.RowTitle.copy(fontSize = 11.5.sp),
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        EcoSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 搜索框：40 高、surface2 底、发丝描边；有内容时右侧出现清除按钮 */
@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.surface2)
            .border(1.dp, c.rule, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = EcoType.Input.copy(color = c.ink),
            cursorBrush = SolidColor(c.blue),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "搜索应用名或包名",
                            style = EcoType.Input,
                            color = c.quiet,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                EcoIcons.Close,
                contentDescription = "清除搜索",
                tint = c.quiet,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}

/** 行内文字动作（10/800 蓝色） */
@Composable
private fun InlineAction(
    text: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.eco.blueDeep,
) {
    Text(
        text,
        style = EcoType.Pill.copy(fontWeight = FontWeight.W800),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}

private fun groupHint(group: AppGroup): String = when (group) {
    AppGroup.PAY -> "支付三包的通知与页面"
    AppGroup.BANK -> "动账推送；短信通道单独控制"
    AppGroup.SHOP -> "收银台与订单页面的捕获"
}
