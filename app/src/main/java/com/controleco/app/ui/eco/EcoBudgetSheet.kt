package com.controleco.app.ui.eco

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money

/**
 * 预算设置底部卡（首页/个人页共用）：月预算与日预算两种口径。
 * 语义与旧 BudgetEditDialog 一致：保存 0 = 清除预算。
 */
@Composable
fun EcoBudgetSheet(
    dailyMode: Boolean,
    daysInPeriod: Int,
    currentCents: Long,
    onSave: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.eco
    var text by remember(currentCents) {
        mutableStateOf(if (currentCents > 0) Money.formatCents(currentCents) else "")
    }
    var error by remember { mutableStateOf<String?>(null) }
    val typed = Money.parseCents(text.trim())

    EcoSheet(
        onDismiss = onDismiss,
        title = if (dailyMode) "设置日预算" else "设置月预算",
        subtitle = if (dailyMode) "每天可花的额度，跨月自动沿用" else "本月可花的总额度，按会计月计算",
    ) {
        EcoMoneyField(
            label = if (dailyMode) "每日预算" else "每月预算",
            value = text,
            onValueChange = { text = it; error = null },
            placeholder = "0.00",
            supporting = error,
            isError = error != null,
        )
        if (dailyMode && typed != null && typed > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "≈ ${Money.formatYuanGrouped(typed * daysInPeriod.coerceAtLeast(1))} / 月（按本期 $daysInPeriod 天）",
                style = EcoType.PageNote,
                color = c.muted,
            )
        }
        Spacer(Modifier.height(20.dp))
        EcoPrimaryButton(
            text = "保存",
            onClick = {
                val cents = Money.parseCents(text.trim())
                if (cents == null || cents <= 0) {
                    error = "请输入有效金额"
                } else {
                    onSave(cents)
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EcoGhostButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            if (currentCents > 0) {
                EcoDangerButton(
                    text = "清除预算",
                    onClick = { onSave(0) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "预算只保存在本机；清除后首页回到未设置引导。",
            style = EcoType.PageNote,
            color = c.quiet,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
