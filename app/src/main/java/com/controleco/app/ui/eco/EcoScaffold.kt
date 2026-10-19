package com.controleco.app.ui.eco

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import kotlinx.coroutines.delay

// ---------- 顶栏 ----------

@Composable
fun EcoTopBar(
    micro: String,
    title: String,
    modifier: Modifier = Modifier,
    low: String? = null,
    onBack: (() -> Unit)? = null,
    action: @Composable RowScope.() -> Unit = {},
) {
    val c = MaterialTheme.eco
    val main = onBack == null
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(if (main) 72.dp else 64.dp)
            .padding(start = if (main) 22.dp else 16.dp, end = if (main) 22.dp else 18.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!main) {
            EcoBackButton(onClick = onBack)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            EcoMicro(micro)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    title,
                    style = if (main) EcoType.TopTitle else EcoType.SubTitle,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (low != null) {
                    Text(
                        low,
                        style = if (main) EcoType.TopLow else EcoType.SubTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        color = c.muted,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

/** 首页右上角的"设置"小胶囊 */
@Composable
fun EcoSettingsPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.eco
    Row(
        modifier
            .width(72.dp)
            .height(36.dp)
            .shadow(7.dp, RoundedCornerShape(19.dp, 9.dp, 19.dp, 9.dp), ambientColor = c.ink.copy(alpha = .10f), spotColor = c.ink.copy(alpha = .14f))
            .clip(RoundedCornerShape(19.dp, 9.dp, 19.dp, 9.dp))
            .background(c.surface.copy(alpha = 0.93f))
            .border(1.dp, c.jade.copy(alpha = 0.18f), RoundedCornerShape(19.dp, 9.dp, 19.dp, 9.dp))
            .ecoPressable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(EcoIcons.Gear, contentDescription = null, tint = c.blueDeep, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("设置", style = EcoType.Pill.copy(fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.blueDeep)
    }
}

// ---------- 底部导航 ----------

data class EcoNavItem(val label: String, val icon: ImageVector)

@Composable
fun EcoBottomNav(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.eco
    val items = remember {
        listOf(
            EcoNavItem("首页", EcoIcons.Home),
            EcoNavItem("账单", EcoIcons.List),
            EcoNavItem("统计", EcoIcons.Chart),
            EcoNavItem("我的", EcoIcons.User),
        )
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, c.ruleStrong.copy(alpha = 0.6f), Color.Transparent),
                    ),
                ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(78.dp)
                .padding(start = 9.dp, end = 9.dp, top = 7.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val on = index == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(29.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (on) c.blueSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = if (on) c.blue else c.quiet,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, style = EcoType.Nav, color = if (on) c.ink else c.quiet)
                }
            }
        }
    }
}

/** 主标签页内容底部留白：底栏 78 + 系统手势条 + 呼吸 */
@Composable
fun ecoBottomContentPadding(extra: Dp = 18.dp): Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return 78.dp + navBottom + extra
}

@Composable
fun ecoFabBottomOffset(): Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return 78.dp + navBottom + 14.dp
}

// ---------- FAB ----------

@Composable
fun EcoFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "fab-breath")
    val breath by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(4100), RepeatMode.Reverse),
        label = "fab-breath-scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.34f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(4100), RepeatMode.Reverse),
        label = "fab-breath-alpha",
    )
    Box(modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(54.dp)
                .graphicsLayer { scaleX = breath; scaleY = breath; alpha = ringAlpha }
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
        )
        Box(
            Modifier
                .size(54.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x55103539),
                    spotColor = Color(0x66103539),
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF356F88), Color(0xFF25556C), Color(0xFF1F475B)),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(140f, 140f),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.34f), CircleShape)
                .ecoPressable(scaleTo = 0.93f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(EcoIcons.Plus, contentDescription = "记一笔", tint = Color(0xFFF8FAF4), modifier = Modifier.size(20.dp))
        }
    }
}

/** 账户/分类页的方形次级 FAB（50x50） */
@Composable
fun EcoSquareFab(onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = EcoIcons.Plus) {
    Box(
        modifier
            .size(50.dp)
            .shadow(12.dp, RoundedCornerShape(17.dp), ambientColor = Color(0x55103539), spotColor = Color(0x66103539))
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF269B8C), Color(0xFF16677C))))
            .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(17.dp))
            .ecoPressable(scaleTo = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

// ---------- Sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcoSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.eco
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = modifier,
        containerColor = c.surface,
        contentColor = c.ink,
        scrimColor = Color.Black.copy(alpha = if (c.isDark) 0.58f else 0.32f),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp),
        dragHandle = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { EcoHandle() }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
        ) {
            if (title != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = EcoType.SheetTitle, color = c.ink)
                        if (subtitle != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(subtitle, style = EcoType.SheetSub, color = c.quiet)
                        }
                    }
                    EcoIconButton(
                        icon = EcoIcons.Close,
                        contentDescription = "关闭",
                        onClick = onDismiss,
                        size = 28.dp,
                        container = c.surface2,
                        border = false,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            if (scrollable) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

// ---------- Dialog ----------

@Composable
fun EcoDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
                    .shadow(26.dp, RoundedCornerShape(23.dp), ambientColor = Color(0x66103539), spotColor = Color(0x66103539))
                    .clip(RoundedCornerShape(23.dp))
                    .background(MaterialTheme.eco.surface)
                    .border(1.dp, MaterialTheme.eco.rule, RoundedCornerShape(23.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .padding(22.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
fun EcoConfirm(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "取消",
    danger: Boolean = false,
) {
    val c = MaterialTheme.eco
    EcoDialog(onDismiss = onDismiss) {
        Column {
            Text(title, style = EcoType.ModalTitle, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Text(body, style = EcoType.ModalBody, color = c.muted)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EcoGhostButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f), height = 40.dp)
                if (danger) {
                    Box(Modifier.weight(1f)) {
                        EcoDangerButton(text = confirmText, onClick = onConfirm, height = 40.dp)
                    }
                } else {
                    Box(Modifier.weight(1f)) {
                        EcoPrimaryButton(text = confirmText, onClick = onConfirm, height = 40.dp)
                    }
                }
            }
        }
    }
}

// ---------- Toast ----------

data class EcoToastData(val id: Long, val text: String, val tone: EcoPillTone)

object EcoToast {
    var current by mutableStateOf<EcoToastData?>(null)
        private set

    fun show(text: String, tone: EcoPillTone = EcoPillTone.Good) {
        current = EcoToastData(System.nanoTime(), text, tone)
    }

    fun clear() {
        current = null
    }

    fun clearIf(id: Long) {
        if (current?.id == id) current = null
    }
}

@Composable
fun EcoToastHost(modifier: Modifier = Modifier) {
    val data = EcoToast.current
    LaunchedEffect(data?.id) {
        val shown = data ?: return@LaunchedEffect
        delay(2400)
        EcoToast.clearIf(shown.id)
    }
    AnimatedVisibility(
        visible = data != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(140)),
    ) {
        val c = MaterialTheme.eco
        val tone = data?.tone ?: EcoPillTone.Good
        val accent = when (tone) {
            EcoPillTone.Bad -> c.clay
            EcoPillTone.Warn -> c.amber
            EcoPillTone.Blue -> c.blue
            else -> c.jade
        }
        Row(
            Modifier
                .padding(horizontal = 22.dp)
                .shadow(16.dp, RoundedCornerShape(13.dp), ambientColor = Color(0x66103539), spotColor = Color(0x66103539))
                .clip(RoundedCornerShape(13.dp))
                .background(c.ink)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(EcoIcons.Check, contentDescription = null, tint = c.paper, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(data?.text.orEmpty(), style = EcoType.RowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.paper, maxLines = 2)
        }
    }
}
