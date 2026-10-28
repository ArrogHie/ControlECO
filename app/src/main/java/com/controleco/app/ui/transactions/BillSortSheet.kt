package com.controleco.app.ui.transactions

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoSheet
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco

/**
 * 排序方式 sheet（账单页 / 搜索页共用）：单选，点一行即生效并关掉。
 *
 * 与分类筛选 sheet 不同，这里没有“应用”按钮——排序是单选项、结果立即可见，
 * 多点一次确认反而累赘。
 */
@Composable
internal fun BillSortSheet(
    current: BillSort,
    onSelect: (BillSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    EcoSheet(
        onDismiss = onDismiss,
        title = "排序方式",
        subtitle = if (current == BillSort.TIME_DESC) null else "当前：${current.title}",
    ) {
        Column(Modifier.fillMaxWidth()) {
            BillSort.entries.forEach { option ->
                val on = option == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(option) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.title,
                            style = EcoType.RowTitle,
                            color = if (on) c.blueDeep else c.ink,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            option.sub,
                            style = EcoType.PageNote.copy(fontSize = 9.sp),
                            color = c.quiet,
                        )
                    }
                    if (on) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            EcoIcons.Check,
                            contentDescription = "当前排序",
                            tint = c.blueDeep,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
