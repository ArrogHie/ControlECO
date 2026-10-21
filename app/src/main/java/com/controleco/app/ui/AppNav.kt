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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.conflate
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
import com.controleco.app.ui.eco.LocalContentAnimationsEnabled
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

/** 切 tab：左右平移，旧页滑出、新页滑入，两页拼满屏幕（无空白、无叠加） */
private const val TAB_SLIDE_MS = 260

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
    // 底栏高亮用 tab（点击即变）；renderedTab 是当前页，outgoingTab 是正在滑出的旧页
    var renderedTab by rememberSaveable { mutableIntStateOf(0) }
    var outgoingTab by remember { mutableStateOf<Int?>(null) }
    var direction by remember { mutableIntStateOf(1) }
    // 页面是否已完全切换到位：滑动期间为 false，内容动画只定格终值、不播放；
    // 滑完置 true，页面里的金额/环/柱状图等再统一重播，避免两页同时动画掉帧。
    var settled by remember { mutableStateOf(true) }
    // 再次点击当前标签 → 计数 +1，由页面自己"回到顶部"（列表/滚动页通用）
    var reenterTick by rememberSaveable { mutableIntStateOf(0) }
    val tabStates = rememberSaveableStateHolder()
    // 0 → 1 的滑动进度：0 时旧页在屏幕内、新页在屏幕外，1 时反过来
    val slide = remember { Animatable(1f) }

    // 顺序处理 tab 变化，conflate 只保留最新目标：滑动中再次点击会等当前滑完再切，
    // 避免三页同屏，也避免同一个 tab 的 SaveableStateProvider 在两个槽位间来回搬家丢状态。
    LaunchedEffect(Unit) {
        snapshotFlow { tab }.conflate().collect { target ->
            if (target == renderedTab) return@collect
            val from = renderedTab
            direction = if (target > from) 1 else -1
            // 先关动画，再换页：保证新页第一帧就是“定格终值”，不会先播一帧动画
            settled = false
            outgoingTab = from
            renderedTab = target
            slide.snapTo(0f)
            // 新页此刻停在屏外：先放过一帧，让它完成组合/布局/首绘，再开始滑动，
            // 避免滑动第一帧就撞上整页构建导致卡顿
            withFrameNanos { }
            slide.animateTo(1f, tween(TAB_SLIDE_MS, easing = FastOutSlowInEasing))
            outgoingTab = null
            settled = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageWidth = constraints.maxWidth.toFloat()
            // 每个 tab 固定 key 占位：旧页从“当前页”变成“滑出页”时不会重建，状态原地保留
            for (index in 0..3) {
                val isCurrent = index == renderedTab
                val isOutgoing = index == outgoingTab
                if (!isCurrent && !isOutgoing) continue
                key(index) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            // 两页同时平移：旧页 -W·p，新页 +W·(1-p)，边缘始终相接，没有空白也没有叠影
                            .graphicsLayer {
                                translationX = if (isCurrent) {
                                    direction * pageWidth * (1f - slide.value)
                                } else {
                                    -direction * pageWidth * slide.value
                                }
                            }
                            // 滑出中的旧页不再接收触摸，避免点左侧区域误触上一个 tab
                            .then(if (isOutgoing) Modifier.blockPointerInput() else Modifier),
                    ) {
                        tabStates.SaveableStateProvider(index) {
                            // 滑动期间 settled=false：页面内容动画只定格终值；滑完再重播
                            CompositionLocalProvider(LocalContentAnimationsEnabled provides settled) {
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
        }
        EcoBottomNav(
            selected = tab,
            onSelect = { index ->
                if (tab == index) {
                    reenterTick++
                } else {
                    tab = index
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // FAB 只属于首页/账单：跟实际渲染的页面走，避免页面滑动时先消失
        AnimatedVisibility(
            visible = renderedTab == 0 || renderedTab == 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
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

/** 当前页与滑动中的旧页共用同一份渲染，保证两页内容一致 */
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
 * 只给“滑出中的旧页”用：它看得见但不该再响应点击/滑动。
 */
private fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}