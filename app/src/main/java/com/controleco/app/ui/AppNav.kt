package com.controleco.app.ui

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    // 转场：默认的 700ms 淡入淡出太慢，改成 180ms 淡入 + 轻微横向位移（层级更清楚、手感利落）
    Box(Modifier.fillMaxSize()) {
        EcoBackdrop()
        NavHost(
        navController = navController,
        startDestination = "main",
        modifier = Modifier.fillMaxSize(),
        // 原型 .page-enter：380ms 淡入 + 7px 下移回弹；返回用 pageInRight 的横向 14px
        enterTransition = {
            fadeIn(tween(380, easing = FastOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = tween(380, easing = FastOutSlowInEasing),
                    initialOffsetY = { (it * 0.0085f).roundToInt() },
                )
        },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = {
            fadeIn(tween(340, easing = FastOutSlowInEasing)) +
                slideInHorizontally(
                    animationSpec = tween(340, easing = FastOutSlowInEasing),
                    initialOffsetX = { it / 28 },
                )
        },
        popExitTransition = {
            fadeOut(tween(200)) +
                slideOutHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    targetOffsetX = { it / 28 },
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
    // 原型每次切页重放 .page-enter：新页面直接替换旧页面，从透明 + 7px 下移淡入。
    // 不用 AnimatedContent 叠放两页（会残留旧页的 pointerInput，挡掉新页点击）。
    val pageIn = remember { Animatable(1f) }
    LaunchedEffect(tab) {
        pageIn.snapTo(0f)
        pageIn.animateTo(
            targetValue = 1f,
            animationSpec = tween(380, easing = FastOutSlowInEasing),
        )
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = pageIn.value
                    translationY = (1f - pageIn.value) * 24f
                },
        ) {
            tabStates.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> HomeScreen(
                        vm = vm,
                        onOpenSettings = onOpenSettings,
                        onOpenCategories = onOpenCategories,
                    )
                    1 -> TransactionListScreen(
                        vm = vm,
                        onEditTransaction = onEditTransaction,
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
        }
        EcoBottomNav(
            selected = tab,
            onSelect = { index ->
                if (tab == index) reenterTick++ else tab = index
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (tab == 0 || tab == 1) {
            EcoFab(
                onClick = onQuickAdd,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 19.dp, bottom = ecoFabBottomOffset()),
            )
        }
    }
}
