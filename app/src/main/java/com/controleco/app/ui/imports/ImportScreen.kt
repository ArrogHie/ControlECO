package com.controleco.app.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import com.controleco.app.data.model.ImportCommitResult
import com.controleco.app.data.model.ImportedBillRow
import com.controleco.app.data.model.ParsedBill
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.eco.EcoChip
import com.controleco.app.ui.eco.EcoEmpty
import com.controleco.app.ui.eco.EcoGhostButton
import com.controleco.app.ui.eco.EcoIconTile
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoRowDivider
import com.controleco.app.ui.eco.EcoSectionHead
import com.controleco.app.ui.eco.EcoTextAction
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.Money

/**
 * 账单导入：选择文件 → 解析 → 预览 → 导入完成，四个阶段同页。
 * 解析器只认微信/支付宝导出的账单；导入按"来源 + 时间 + 金额 + 方向"去重。
 */
@Composable
fun ImportScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val bill by vm.parsedBill.collectAsStateWithLifecycle()
    val error by vm.importError.collectAsStateWithLifecycle()
    val done by vm.importDone.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()

    var accountId by remember { mutableStateOf<Long?>(null) }
    var parsing by remember { mutableStateOf(false) }

    LaunchedEffect(accounts) {
        if (accountId == null && accounts.isNotEmpty()) accountId = accounts.first().id
    }

    // 解析结束（成功或失败）后收起进度态；进度态本身只覆盖"文件已选、结果未到"这段
    LaunchedEffect(bill, error) {
        if (bill != null || error != null) parsing = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            parsing = true
            vm.parseBillFile(uri.toString())
        }
    }
    val launchPicker: () -> Unit = { picker.launch(arrayOf("*/*")) }

    DisposableEffect(Unit) {
        onDispose { vm.clearImportState() }
    }

    val reset: () -> Unit = {
        parsing = false
        vm.clearImportState()
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(
            micro = "IMPORT",
            title = "导入账单",
            onBack = onBack,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 32.dp),
        ) {
            val b = bill
            val d = done
            when {
                d != null -> ImportDoneStage(
                    result = d,
                    accountName = accounts.firstOrNull { it.id == accountId }?.name,
                    onContinue = reset,
                    onBack = onBack,
                )
                b != null -> ImportPreviewStage(
                    bill = b,
                    accounts = accounts,
                    accountId = accountId,
                    showRefunds = showRefunds,
                    error = error,
                    onSelectAccount = { accountId = it },
                    onCommit = { accountId?.let { vm.commitImport(b, it) } },
                    onReset = reset,
                )
                parsing && error == null -> ImportParsingStage()
                else -> ImportIdleStage(
                    error = error,
                    onPick = launchPicker,
                )
            }
        }
    }
}

// ---------- 阶段 1：选择文件 ----------

@Composable
private fun ImportIdleStage(
    error: String?,
    onPick: () -> Unit,
) {
    val c = MaterialTheme.eco
    ImportStageBar(current = 0)

    error?.let {
        Spacer(Modifier.height(10.dp))
        ImportErrorCard(message = it, onRetry = onPick)
    }

    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(c.blue.copy(alpha = if (c.isDark) 0.09f else 0.06f), Color.Transparent),
                        center = Offset(size.width * 0.76f, size.height * 0.18f),
                        radius = size.width * 0.72f,
                    ),
                )
            }
            .ecoDashedBorder(color = c.ruleStrong, radius = 20.dp)
            .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.blueSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(EcoIcons.File, contentDescription = null, tint = c.blue, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "从本机选择账单文件",
            style = EcoType.RowTitle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = c.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "微信 xlsx / 支付宝 CSV",
            style = EcoType.PageNote,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.width(190.dp)) {
            EcoPrimaryButton(text = "选择账单文件", onClick = onPick)
        }
    }

    EcoPageNote(
        "文件只在本机解析；导入会跳过已存在的记录（按来源 + 时间 + 金额 + 方向去重）。",
    )

    EcoSectionHead(number = "01", title = "导出方法", aside = "HOW TO")
    EcoPane {
        Column {
            ExportGuideRow(
                icon = EcoIcons.App,
                tint = c.jade,
                title = "微信",
                body = "我 → 服务 → 钱包 → 账单 → 右上角常见问题 → 下载账单 → 用于个人对账" +
                    "（xlsx 发送到邮箱）。",
            )
            EcoRowDivider(inset = 14.dp)
            ExportGuideRow(
                icon = EcoIcons.Wallet,
                tint = c.blue,
                title = "支付宝",
                body = "我的 → 账单 → 右上角「…」 → 开具交易流水证明 → 用于个人对账" +
                    "（CSV 发送到邮箱）。",
            )
        }
    }
}

@Composable
private fun ExportGuideRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EcoIconTile(icon = icon, tint = tint, size = 38.dp, radius = 12.dp, iconSize = 19.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = EcoType.RowTitle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                color = c.ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                body,
                style = EcoType.PageNote.copy(lineHeight = 15.sp),
                color = c.muted,
            )
        }
    }
}

// ---------- 阶段 2：解析中 ----------

@Composable
private fun ImportParsingStage() {
    val c = MaterialTheme.eco
    val transition = rememberInfiniteTransition(label = "parse")
    val ringAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "parse-ring",
    )
    val barSlide by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "parse-bar",
    )

    ImportStageBar(current = 1)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 46.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(58.dp)) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = c.rule,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            rotate(ringAngle) {
                drawArc(
                    color = c.blue,
                    startAngle = -90f,
                    sweepAngle = 140f,
                    useCenter = false,
                    style = stroke,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "正在解析账单…",
            style = EcoType.RowTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            color = c.ink,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "读取行、识别来源、生成去重键",
            style = EcoType.PageNote,
            color = c.muted,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .width(200.dp)
                .height(5.dp)
                .clip(CircleShape)
                .background(c.track),
        ) {
            Box(
                Modifier
                    .offset(x = 124.dp * barSlide)
                    .width(76.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(c.blue.copy(alpha = 0.55f), c.blue),
                        ),
                    ),
            )
        }
    }
}

// ---------- 阶段 3：预览 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPreviewStage(
    bill: ParsedBill,
    accounts: List<AccountEntity>,
    accountId: Long?,
    showRefunds: Boolean,
    error: String?,
    onSelectAccount: (Long) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
) {
    val c = MaterialTheme.eco
    ImportStageBar(current = 2)
    val previewRows = bill.rows.take(8)

    error?.let {
        Spacer(Modifier.height(10.dp))
        ImportErrorCard(message = it)
    }

    Spacer(Modifier.height(12.dp))
    EcoPane {
        Column {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "识别为：${bill.source.displayName} 账单",
                    style = EcoType.RowTitle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    color = c.ink,
                )
                Spacer(Modifier.height(4.dp))
                val incomeCount = bill.rows.count { it.type == TxType.INCOME }
                val expenseCount = bill.rows.count { it.type == TxType.EXPENSE }
                Text(
                    "共 ${bill.rows.size} 条（支出 $expenseCount，收入 $incomeCount）；" +
                        "已跳过不计收支 ${bill.skippedOtherType} 条、未完成 ${bill.skippedStatus} 条。",
                    style = EcoType.PageNote,
                    color = c.muted,
                )
                if (bill.refundCount > 0) {
                    val refundState = if (showRefunds) {
                        "已开启显示"
                    } else {
                        "未开启显示，不计入流水与汇总"
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "其中退款相关 ${bill.refundCount} 条（已入库；当前$refundState，可在设置中切换）",
                        style = EcoType.PageNote,
                        color = c.amber,
                    )
                }
            }

            EcoRowDivider(inset = 14.dp)

            Column(Modifier.padding(14.dp)) {
                Text("记入账户", style = EcoType.FieldLabel, color = c.muted)
                Spacer(Modifier.height(7.dp))
                if (accounts.isEmpty()) {
                    Text(
                        "暂无账户，请先在账户管理中添加",
                        style = EcoType.PageNote,
                        color = c.amber,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        accounts.forEach { acc ->
                            EcoChip(
                                text = acc.name,
                                selected = accountId == acc.id,
                                onClick = { onSelectAccount(acc.id) },
                            )
                        }
                    }
                }
            }

            if (bill.rows.isEmpty()) {
                EcoRowDivider(inset = 14.dp)
                EcoEmpty(
                    title = "没有可导入的记录",
                    body = "文件里没有可入账的收支记录，换一个文件试试。",
                    icon = EcoIcons.File,
                )
            } else {
                EcoRowDivider(inset = 14.dp)
                Column {
                    previewRows.forEachIndexed { index, row ->
                        ImportPreviewRow(row = row)
                        if (index != previewRows.lastIndex) EcoRowDivider(inset = 14.dp)
                    }
                }
            }

            EcoRowDivider(inset = 14.dp)

            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EcoPrimaryButton(
                    text = "确认导入 ${bill.rows.size} 条",
                    onClick = onCommit,
                    enabled = accountId != null && bill.rows.isNotEmpty(),
                )
                EcoGhostButton(text = "换一个文件", onClick = onReset)
            }
        }
    }

    EcoPageNote(
        "预览只显示前 8 条；导入会跳过已存在的记录（按来源 + 时间 + 金额 + 方向去重）。",
    )
}

@Composable
private fun ImportPreviewRow(row: ImportedBillRow) {
    val c = MaterialTheme.eco
    val detail = buildString {
        append(row.note.ifBlank { row.goods }.ifBlank { "-" })
        append(" → ")
        append(row.guessedCategoryName)
        if (row.refundMark != RefundMark.NONE) {
            append(" ｜")
            append(if (row.refundMark == RefundMark.REFUND_INCOME) "退款收入" else "已退款")
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${formatTimestamp(row.timestamp)}  ${row.counterparty.ifBlank { "(无对方)" }}",
                style = EcoType.RowTitle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = EcoType.PageNote.copy(fontSize = 8.5.sp),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (row.type == TxType.EXPENSE) "-" else "+") + Money.formatYuan(row.amountCents),
            style = EcoType.BillAmount.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = if (row.type == TxType.INCOME) c.jade else c.ink,
            maxLines = 1,
        )
    }
}

// ---------- 阶段 4：完成 ----------

@Composable
private fun ImportDoneStage(
    result: ImportCommitResult,
    accountName: String?,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MaterialTheme.eco
    ImportStageBar(current = 3)

    Spacer(Modifier.height(18.dp))
    EcoPane {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(c.jadeSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EcoIcons.Check, contentDescription = null, tint = c.jade, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "导入完成",
                style = EcoType.RowTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = c.ink,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "成功导入 ${result.inserted} 条，跳过重复 ${result.duplicated} 条。",
                style = EcoType.PageNote.copy(textAlign = TextAlign.Center),
                color = c.muted,
                textAlign = TextAlign.Center,
            )
            if (accountName != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "已记入「$accountName」。",
                    style = EcoType.PageNote.copy(textAlign = TextAlign.Center),
                    color = c.muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    EcoPrimaryButton(text = "继续导入", onClick = onContinue)
    Spacer(Modifier.height(8.dp))
    EcoGhostButton(text = "返回", onClick = onBack)

    EcoPageNote("导入的记录可在账单页查看；重复记录按来源 + 时间 + 金额 + 方向自动跳过。")
}

// ---------- 阶段条 / 错误条 ----------

@Composable
private fun ImportStageBar(current: Int) {
    val c = MaterialTheme.eco
    val steps = listOf("选择文件", "预览", "导入", "完成")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        steps.forEachIndexed { index, label ->
            val active = index == current
            val done = index < current
            val fg = when {
                active -> c.blueDeep
                done -> c.jade
                else -> c.quiet
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        when {
                            active -> c.blueSoft
                            done -> c.jadeSoft
                            else -> Color.Transparent
                        },
                    )
                    .padding(horizontal = 3.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (index + 1).toString().padStart(2, '0'),
                    style = EcoType.Micro.copy(fontSize = 7.5.sp),
                    color = fg,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    label,
                    style = EcoType.SegSmall.copy(fontSize = 9.sp),
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ImportErrorCard(message: String, onRetry: (() -> Unit)? = null) {
    val c = MaterialTheme.eco
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.claySoft)
            .border(1.dp, c.clay.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(EcoIcons.Warning, contentDescription = null, tint = c.clay, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "导入失败",
                style = EcoType.RowTitle.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                color = c.clay,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                message,
                style = EcoType.PageNote,
                color = c.clay,
            )
        }
        if (onRetry != null) {
            Spacer(Modifier.width(6.dp))
            EcoTextAction(text = "重试", onClick = onRetry, color = c.clay)
        }
    }
}

// ---------- 工具 ----------

private fun Modifier.ecoDashedBorder(
    color: Color,
    radius: Dp,
    width: Dp = 1.dp,
): Modifier = drawBehind {
    val inset = width.toPx() / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius((radius.toPx() - inset).coerceAtLeast(0f)),
        style = Stroke(
            width = width.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()), 0f),
        ),
    )
}
