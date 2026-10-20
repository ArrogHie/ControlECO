package com.controleco.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.capture.CapturePermissions
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.AccBookNav
import com.controleco.app.ui.theme.AccBookTheme
import com.controleco.app.ui.theme.AccentThemeStore
import com.controleco.app.update.AppUpdate

class MainActivity : ComponentActivity() {

    /** 通知点击跳转：route + 自增序号（同路由重复点击也能触发导航） */
    private var navEvent by mutableStateOf(0L to null as String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 纸面要铺到状态栏/手势条后面；系统栏图标颜色由 auto 按系统深浅色决定
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        consumeRoute(intent)
        // 权限自检只在"冷启动"提醒一次（重建/旋转不重弹），且从捕获通知进入时不打扰
        val coldStart = savedInstanceState == null
        val fromCaptureRoute = intent?.getStringExtra(CaptureCenter.EXTRA_ROUTE) != null
        val promptPermissions = coldStart && CapturePermissions.claimLaunchPrompt()
        // 自动更新检查：同样只认冷启动、从捕获通知进入时不打扰（结果就绪后弹对话框）
        if (coldStart && !fromCaptureRoute) AppUpdate.maybeCheck(this)
        setContent {
            // eco v5 是 390dp 固定栅格设计：字体跟随系统，但上限锁在 1.25，
            // 避免超大字体把 hero / 底排卡片撑开；dp 尺寸与系统显示大小仍照常生效。
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = base.density,
                    fontScale = base.fontScale.coerceAtMost(1.25f),
                ),
            ) {
                AccBookTheme(accent = AccentThemeStore.current) {
                    val vm: LedgerViewModel = viewModel()
                    Box(Modifier.fillMaxSize()) {
                        AccBookNav(vm, navEvent, promptPermissions)
                        UpdateAvailableDialog()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeRoute(intent)
    }

    // 前台状态上报：悬浮确认卡在本应用前台时立即收起（应用内用待确认列表，卡片悬在本应用上会挡操作）
    override fun onResume() {
        super.onResume()
        CaptureCenter.setAppForeground(true)
    }

    override fun onPause() {
        CaptureCenter.setAppForeground(false)
        super.onPause()
    }

    private fun consumeRoute(intent: Intent?) {
        val route = intent?.getStringExtra(CaptureCenter.EXTRA_ROUTE) ?: return
        CaptureCenter.onRouteOpened(route)
        navEvent = (navEvent.first + 1) to route
    }
}

/**
 * 发现新版本的应用内对话框：确认「立即更新」后由应用自己下载 APK（直连失败自动走镜像，
 * 弹窗内显示进度）并拉起系统安装器，用户只需在系统安装确认页再点一次确认。
 * 下载中不允许关弹窗；下载失败回到弹窗可重试或「下次再说」。
 */
@Composable
private fun UpdateAvailableDialog() {
    val context = LocalContext.current
    val info = AppUpdate.updateAvailable.collectAsStateWithLifecycle().value ?: return

    // null = 未在下载；-1 = 总大小未知（进度条转圈）；0..100 = 百分比
    var progress by remember(info.tagName) { mutableStateOf<Int?>(null) }

    fun toast(text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun startUpdate() {
        if (progress != null) return
        // 先过"安装未知应用"授权闸（缺了就跳授权页，用户授权回来再点一次即可）
        val gate = AppUpdate.installGate(context)
        if (gate != null) {
            toast(gate)
            return
        }
        progress = -1
        AppUpdate.downloadApk(
            context,
            info,
            onProgress = { progress = it },
            onDone = { result ->
                result.fold(
                    onSuccess = { file ->
                        progress = null
                        AppUpdate.consumeUpdate()
                        AppUpdate.startInstaller(context, file)?.let { toast(it) }
                    },
                    onFailure = { e ->
                        progress = null
                        toast(e.message ?: "下载失败，请稍后再试")
                    },
                )
            },
        )
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) AppUpdate.consumeUpdate() },
        title = { Text("发现新版本 ${info.tagName}") },
        text = {
            Column {
                Text("当前 v${BuildConfig.VERSION_NAME}")
                val pct = progress
                if (pct != null) {
                    Spacer(Modifier.height(16.dp))
                    if (pct >= 0) {
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("正在下载更新… ${pct}%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("正在下载更新…")
                    }
                } else if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.notes.trimEnd())
                }
            }
        },
        confirmButton = {
            if (progress == null) {
                TextButton(onClick = ::startUpdate) { Text("立即更新") }
            }
        },
        dismissButton = {
            if (progress == null) {
                TextButton(onClick = AppUpdate::consumeUpdate) { Text("下次再说") }
            }
        },
    )
}
