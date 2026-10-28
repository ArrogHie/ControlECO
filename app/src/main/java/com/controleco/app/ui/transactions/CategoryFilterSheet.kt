package com.controleco.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 分类多选集合的 `rememberSaveable` 存取：`Set<Long>` 本身不能进 Bundle，用 LongArray 中转。
 * 账单页与搜索页的分类筛选都用它，保证「进详情页再返回」「切 tab」后筛选还在。
 */
internal val CategoryIdSetSaver: Saver<Set<Long>, LongArray> = Saver(
    save = { it.toLongArray() },
    restore = { it.toSet() },
)

/**
 * 分类筛选 sheet（账单页筛选按钮 / 搜索页共用）：按支出/收入分组多选。
 *
 * 勾选先在 sheet 内暂存，「应用筛选」才回写；直接关掉 = 放弃本次改动
 * （sheet 铺满屏幕时看不到背后的列表，即时生效反而让人不知道点了什么）。
 */
@Composable
internal fun CategoryFilterSheet(
    categories: List<CategoryEntity>,
    selected: Set<Long>,
    onApply: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    var picked by remember(selected) { mutableStateOf(selected) }
    EcoSheet(
        onDismiss = onDismiss,
        title = "按分类筛选",
        subtitle = if (picked.isEmpty()) "可多选" else "已选 ${picked.size} 个分类",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (categories.isEmpty()) {
                Text("还没有分类，先到「分类管理」里添加。", style = EcoType.PageNote, color = c.quiet)
            } else {
                CategoryGroup("支出", categories.filter { it.type == TxType.EXPENSE }, picked) { id ->
                    picked = picked.toggle(id)
                }
                CategoryGroup("收入", categories.filter { it.type == TxType.INCOME }, picked) { id ->
                    picked = picked.toggle(id)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    EcoGhostButton(text = "重置", onClick = { picked = emptySet() }, height = 42.dp)
                }
                Box(Modifier.weight(1f)) {
                    EcoPrimaryButton(text = "应用筛选", onClick = { onApply(picked) }, height = 42.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryGroup(
    label: String,
    items: List<CategoryEntity>,
    picked: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    if (items.isEmpty()) return
    val c = MaterialTheme.eco
    Column {
        Text(label, style = EcoType.FieldLabel, color = c.muted)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { cat ->
                EcoChip(text = cat.name, selected = cat.id in picked, onClick = { onToggle(cat.id) })
            }
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id
