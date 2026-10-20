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
    currentCents: Long,
    onSave: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(currentCents) {
        mutableStateOf(if (currentCents > 0) Money.formatCents(currentCents) else "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    EcoSheet(
        onDismiss = onDismiss,
        title = if (dailyMode) "日预算" else "月预算",
    ) {
        EcoMoneyField(
            label = if (dailyMode) "每日预算" else "每月预算",
            value = text,
            onValueChange = { text = it; error = null },
            placeholder = "0.00",
            supporting = error,
            isError = error != null,
        )
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
    }
}
