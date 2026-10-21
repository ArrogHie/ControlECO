package com.controleco.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.capture.CapturePermission
import com.controleco.app.capture.CapturePermissions
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.accounts.AccountsScreen
import com.controleco.app.ui.accounts.CategoriesScreen
import com.controleco.app.ui.common.PermissionReminderDialog
import com.controleco.app.ui.debug.CaptureLogScreen
import com.controleco.app.ui.debug.CaptureTraceScreen
import com.controleco.app.ui.home.HomeScreen
import com.controleco.app.ui.imports.ImportScreen
import com.controleco.app.ui.keepalive.KeepAliveScreen
import com.controleco.app.ui.pending.PendingScreen
import com.controleco.app.ui.profile.ProfileScreen
import com.controleco.app.ui.refund.RefundLinkScreen
import com.controleco.app.ui.settings.SettingsAboutScreen
import com.controleco.app.ui.settings.SettingsAccountScreen
import com.controleco.app.ui.settings.SettingsDeveloperScreen
import com.controleco.app.ui.settings.SettingsImportScreen
import com.controleco.app.ui.settings.SettingsPermissionScreen
import com.controleco.app.ui.settings.SettingsMerchantRulesScreen
import com.controleco.app.ui.settings.SettingsScreen
import com.controleco.app.ui.settings.SettingsWatchedAppsScreen
import com.controleco.app.ui.eco.EcoBackdrop
import com.controleco.app.ui.eco.EcoBottomNav
import com.controleco.app.ui.eco.EcoFab
import com.controleco.app.ui.eco.EcoToastHost
import com.controleco.app.ui.eco.ecoFabBottomOffset
import com.controleco.app.ui.stats.StatsScreen
import com.controleco.app.ui.transactions.EditTransactionScreen
import com.controleco.app.ui.transactions.QuickAddScreen
import com.controleco.app.ui.transactions.TransactionListScreen

@Composable
fun AccBookNav(
    vm: LedgerViewModel,
    navEvent: Pair<Long, String?> = 0L to null,
    /** 本次冷启动是否做权限自检（MainActivity 传入；每次启动只提醒一次） */
    promptPermissions: Boolean = false,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // 打开 App 时权限自检：只列"功能开着但没授权"的项；从捕获通知直接进入时不打断处理动线
    var permissionMissing by remember {
        mutableStateOf(
            if (promptPermissions && navEvent.second == null) CapturePermissions.missing(context) else emptyList(),
        )
    }
    // 去系统设置授权后返回：重查一次，补齐即自动收起
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionMissing.isNotEmpty()) {
                permissionMissing = CapturePermissions.missing(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统设置页直达（权限页 / 保活页共用）
    val openNotificationAccess: () -> Unit = {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        Unit
    }
    val openAccessibilitySettings: () -> Unit = {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Unit
    }
    val openOverlayPermission: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        Unit
    }

    // 系统通知点击跳转（MainActivity 传入 route 事件；既有路由名是捕获层的对外契约，勿改）
    LaunchedEffect(navEvent.first) {
        val route = navEvent.second
        when {
            route == null -> Unit
            route == CaptureCenter.ROUTE_PENDING -> navController.navigate("pending")
            route == CaptureCenter.ROUTE_REFUND_LINK -> navController.navigate("refundLink")
            // 「已记入一笔账」通知：直达该笔记录的编辑页（追加路由：edit/{txId}）
            route.startsWith(CaptureCenter.ROUTE_EDIT_PREFIX) -> navController.navigate(route)
            // 快捷方式/磁贴：直接进快速记账
            route == "quickAdd" -> navController.navigate("quickAdd")
            // 设置子页直达（调试/客服引导用：开发者功能、权限页）
            route == "settings/developer" || route == "settings/permissions" ->
                navController.navigate(route)
            // 悬浮确认卡样例（等价开发者功能里的「悬浮确认卡（立即）」；调试路由，不落库）
            route == CaptureCenter.ROUTE_OVERLAY_TEST -> CaptureCenter.showOverlayTest(0L) { }
            // 分享导入：文件已在缓存里，跳到导入页并预解析
            route.startsWith(CaptureCenter.ROUTE_IMPORT_FILE_PREFIX) -> {
                val fileUri = route.removePrefix(CaptureCenter.ROUTE_IMPORT_FILE_PREFIX)
                vm.parseBillFile(fileUri)
                navController.navigate("import")
            }
        }
    }
    // 转场：旧页保持不透明到新页淡入过半后再退场（200ms 就退场会露出纸面，看起来“闪一下”）
    Box(Modifier.fillMaxSize()) {
        EcoBackdrop()
        NavHost(
        navController = navController,
        startDestination = "main",
        modifier = Modifier.fillMaxSize(),
        // 原型 .page-enter：淡入 + 8px 下移；返回走横向位移
        enterTransition = {
            fadeIn(tween(300, easing = FastOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    initialOffsetY = { (it * 0.0095f).roundToInt() },
                )
        },
        // 延迟退场：新页先淡入覆盖，旧页再退，避免中间露出背景纸面
        exitTransition = { fadeOut(tween(180, delayMillis = 170)) },
        popEnterTransition = {
            fadeIn(tween(280, easing = FastOutSlowInEasing)) +
                slideInHorizontally(
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                    initialOffsetX = { it / 32 },
                )
        },
        popExitTransition = {
            fadeOut(tween(180, delayMillis = 150)) +
                slideOutHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    targetOffsetX = { it / 32 },
                )
        },
    ) {
        composable("main") {
            MainScaffold(
                vm = vm,
                onQuickAdd = { navController.navigate("quickAdd") },
                onEditTransaction = { id -> navController.navigate("edit/$id") },
                onOpenPending = { navController.navigate("pending") },
                onOpenAccount = { navController.navigate("settings/account") },
                onOpenImport = { navController.navigate("settings/import") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenCategories = { navController.navigate("categories") },
            )
        }
        composable("quickAdd") { QuickAddScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("edit/{txId}") { entry ->
            val txId = entry.arguments?.getString("txId")?.toLongOrNull() ?: -1L
            EditTransactionScreen(vm = vm, txId = txId, onBack = { navController.popBackStack() })
        }
        composable("accounts") { AccountsScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("categories") { CategoriesScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("import") { ImportScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("pending") { PendingScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("refundLink") { RefundLinkScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("captureLog") { CaptureLogScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("captureTrace") {
            CaptureTraceScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenTransaction = { id -> navController.navigate("edit/$id") },
            )
        }

        // ---- 设置体系：设置 →（权限 / 开发者功能 / 关于）；账户/账单导入在个人页 ----
        composable("settings") {
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenPermissions = { navController.navigate("settings/permissions") },
                onOpenWatchedApps = { navController.navigate("settings/watchedApps") },
                onOpenMerchantRules = { navController.navigate("settings/merchantRules") },
                onOpenDeveloper = { navController.navigate("settings/developer") },
                onOpenAbout = { navController.navigate("settings/about") },
            )
        }
        composable("settings/account") {
            SettingsAccountScreen(
                onBack = { navController.popBackStack() },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenCategories = { navController.navigate("categories") },
            )
        }
        composable("settings/import") {
            SettingsImportScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onImport = { navController.navigate("import") },
            )
        }
        composable("settings/permissions") {
            SettingsPermissionScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenNotificationAccess = openNotificationAccess,
                onOpenAccessibilitySettings = openAccessibilitySettings,
                onOpenOverlayPermission = openOverlayPermission,
            )
        }
        composable("settings/watchedApps") {
            SettingsWatchedAppsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings/merchantRules") {
            SettingsMerchantRulesScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable("settings/developer") {
            SettingsDeveloperScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenPending = { navController.navigate("pending") },
                onOpenCaptureLog = { navController.navigate("captureLog") },
                onOpenCaptureTrace = { navController.navigate("captureTrace") },
                onOpenKeepAlive = { navController.navigate("keepAlive") },
            )
        }
        composable("settings/about") {
            SettingsAboutScreen(onBack = { navController.popBackStack() })
        }

        composable("keepAlive") {
            KeepAliveScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenNotificationAccess = openNotificationAccess,
                onOpenAccessibilitySettings = openAccessibilitySettings,
            )
        }
        }
        EcoToastHost(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 112.dp),
        )
    }

    // 冷启动权限提醒（与 NavHost 并列；调用方 Surface 内容即 Box，弹窗自带独立系统窗口）
    if (permissionMissing.isNotEmpty()) {
        PermissionReminderDialog(
            missing = permissionMissing,
            onOpenPermissions = {
                permissionMissing = emptyList()
                navController.navigate("settings/permissions")
            },
            onDismiss = { permissionMissing = emptyList() },
        )
    }
}

/** tab 切换的交叉淡入时长：短到不拖沓，长到足以盖住新页首帧布局 */
private const val TAB_SWITCH_MS = 260

@Composable
private fun MainScaffold(
    vm: LedgerViewModel,
    onQuickAdd: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenPending: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 再次点击当前标签 → 计数 +1，由页面自己"回到顶部"（列表/滚动页通用）
    var reenterTick by rememberSaveable { mutableIntStateOf(0) }
    val tabStates = rememberSaveableStateHolder()

    // 单页直接替换时旧页瞬间消失、新页又从 alpha 0 淡入，中间几帧只剩纸面 —— 观感就是“闪一下”。
    // 这里把旧页保留为过渡底图（动画结束立刻释放），新页在它上面淡入，任何一帧都有完整内容。
    var previousTab by remember { mutableStateOf<Int?>(null) }
    // 旧页被替换时它自身可能还在淡入；记下当前 alpha，避免瞬间跳回 1（快速连点会闪亮）。
    var previousAlpha by remember { mutableFloatStateOf(1f) }
    var hasSwitched by remember { mutableStateOf(false) }
    // 首帧就用目标值创建：旧写法 remember { Animatable(1f) } + 事后 snapTo(0f) 会让新页
    // 先以全不透明画一帧再突然归零，正是“闪一下”的来源。remember(tab) 保证首帧即目标值。
    val pageIn = remember(tab) { Animatable(if (hasSwitched) 0f else 1f) }
    LaunchedEffect(tab) {
        if (pageIn.value < 1f) {
            pageIn.animateTo(1f, tween(TAB_SWITCH_MS, easing = FastOutSlowInEasing))
        }
        // 新页已完全不透明，撤掉旧底图不会产生任何视觉变化
        previousTab = null
    }

    Box(Modifier.fillMaxSize()) {
        // 每个 tab 用固定 key 占位：切走后它仍在树上（只是退到底层），
        // SaveableStateProvider 只有一份，滚动位置/rememberSaveable 原地保留，不重建、不重播入场动画。
        for (index in 0..3) {
            val isCurrent = index == tab
            val isPrevious = index == previousTab
            if (isCurrent || isPrevious) {
                key(index) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            // 当前页必须在最上层；旧页在下层并吞掉全部触摸，
                            // 否则旧页 pointerInput 会挡住新页点击（AnimatedContent 时代的坑）。
                            .zIndex(if (isCurrent) 1f else 0f)
                            // alpha 在 draw 阶段读取，动画每帧只失效图层，不重组整页
                            .graphicsLayer {
                                this.alpha = if (isCurrent) pageIn.value else previousAlpha
                                if (isCurrent) translationY = (1f - pageIn.value) * 8.dp.toPx()
                            }
                            .then(if (isCurrent) Modifier else Modifier.blockPointerInput()),
                    ) {
                        tabStates.SaveableStateProvider(index) {
                            TabPage(
                                tab = index,
                                vm = vm,
                                reenterTick = reenterTick,
                                onEditTransaction = onEditTransaction,
                                onOpenAccount = onOpenAccount,
                                onOpenImport = onOpenImport,
                                onOpenPending = onOpenPending,
                                onOpenSettings = onOpenSettings,
                                onOpenCategories = onOpenCategories,
                            )
                        }
                    }
                }
            }
        }
        EcoBottomNav(
            selected = tab,
            onSelect = { index ->
                if (tab == index) {
                    reenterTick++
                } else {
                    hasSwitched = true
                    previousTab = tab
                    previousAlpha = pageIn.value
                    tab = index
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 页面当前层是 zIndex 1，底栏必须更高才能压在上面并优先接收点击
                .zIndex(2f),
        )
        // FAB 只属于首页/账单：切换时淡出+缩小，不要突然消失（比页面本身更扎眼）
        AnimatedVisibility(
            visible = tab == 0 || tab == 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .zIndex(2f)
                .padding(end = 19.dp, bottom = ecoFabBottomOffset()),
            enter = fadeIn(tween(180)) +
                scaleIn(initialScale = 0.86f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(140)) +
                scaleOut(targetScale = 0.86f, animationSpec = tween(160, easing = FastOutSlowInEasing)),
        ) {
            EcoFab(onClick = onQuickAdd)
        }
    }
}

/** 当前 tab 的真实内容；主页面与过渡底图共用，保证两处渲染完全一致 */
@Composable
private fun TabPage(
    tab: Int,
    vm: LedgerViewModel,
    reenterTick: Int,
    onEditTransaction: (Long) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    when (tab) {
        0 -> HomeScreen(
            vm = vm,
            onOpenSettings = onOpenSettings,
            onOpenCategories = onOpenCategories,
        )
        1 -> TransactionListScreen(
            vm = vm,
            onEditTransaction = onEditTransaction,
            onOpenSettings = onOpenSettings,
            scrollToTopTick = reenterTick,
        )
        2 -> StatsScreen(vm = vm, scrollToTopTick = reenterTick)
        3 -> ProfileScreen(
            vm = vm,
            onOpenAccount = onOpenAccount,
            onOpenImport = onOpenImport,
            onOpenPending = onOpenPending,
            onOpenSettings = onOpenSettings,
            onOpenCategories = onOpenCategories,
        )
    }
}

/**
 * 消费子树内的全部指针事件（Initial 阶段，先于子节点）。
 * 只给“过渡中的旧页”用：它看得见但不该再响应任何点击/滑动。
 */
private fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
