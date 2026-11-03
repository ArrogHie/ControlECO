package com.controleco.app.capture.overlay

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.controleco.app.ui.theme.AccBookTheme

/**
 * 悬浮窗里的 Compose 宿主。
 *
 * ComposeView 需要 LifecycleOwner（WindowRecomposer 依赖它调度重组）与 ViewModelStore /
 * SavedStateRegistry 三类宿主；TYPE_APPLICATION_OVERLAY 窗口没有 Activity，这三类在这里手工补齐。
 *
 * 状态机刻意简化：attach 后直接 RESUMED（窗口已可见，没有 Activity 的 STARTED/STOPPED 语义），
 * destroy 时置 DESTROYED 并清空 ViewModelStore——生命周期只走一趟，避免乱序回调打乱状态。
 */
internal class OverlayComposeHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        // addView 会同步触发首次组合；在挂载前恢复，避免 rememberSaveable 读取未恢复的注册表。
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** ComposeView 挂载后调用：恢复空 SavedState + 进入 RESUMED（触发首次组合） */
    fun attach() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** 窗口移除后调用：停止重组、释放 ViewModelStore */
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
}

/** 组装带宿主绑定的 ComposeView（三类 ViewTree Owner 都要在 setContent 之前挂上）；主题跟随应用（暗色模式自动生效） */
internal fun buildOverlayComposeView(
    context: Context,
    host: OverlayComposeHost,
    content: @Composable () -> Unit,
): ComposeView = ComposeView(context).apply {
    setViewTreeLifecycleOwner(host)
    setViewTreeViewModelStoreOwner(host)
    setViewTreeSavedStateRegistryOwner(host)
    setContent { AccBookTheme { content() } }
}

/** View 上是否已挂宿主（调试用：确认窗口内容不会因缺宿主而抛异常） */
internal fun View.hasComposeOwners(): Boolean =
    findViewTreeLifecycleOwner() != null &&
        findViewTreeViewModelStoreOwner() != null &&
        findViewTreeSavedStateRegistryOwner() != null
