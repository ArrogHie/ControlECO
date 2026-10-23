package com.controleco.app.ui.profile

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.controleco.app.R
import com.controleco.app.ui.common.EcoIcons
import com.controleco.app.ui.eco.EcoPageLead
import com.controleco.app.ui.eco.EcoPageNote
import com.controleco.app.ui.eco.EcoPane
import com.controleco.app.ui.eco.EcoPillTone
import com.controleco.app.ui.eco.EcoPrimaryButton
import com.controleco.app.ui.eco.EcoToast
import com.controleco.app.ui.eco.EcoTopBar
import com.controleco.app.ui.eco.ecoPressable
import com.controleco.app.ui.theme.EcoType
import com.controleco.app.ui.theme.eco
import com.controleco.app.util.AlbumSaver
import com.controleco.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一张收款码的定义。
 *
 * [tint] 是品牌色（微信绿 / 支付宝蓝），按产品口径**固定**、不随主题变；
 * 暗色下会向白色抬一点保证对比度，浅色下保持原色。
 * [extension] 必须与 drawable 里的真实格式一致——保存走原始字节，MIME 错了相册会认不出。
 */
private data class RewardCode(
    val key: String,
    val title: String,
    val latin: String,
    val resId: Int,
    val fileBase: String,
    val extension: String,
    val tint: Color,
    val icon: ImageVector,
)

private val REWARD_CODES = listOf(
    RewardCode(
        key = "wechat",
        title = "微信",
        latin = "WECHAT PAY",
        resId = R.drawable.reward_wechat,
        fileBase = "易控钱包-微信收款码",
        extension = "png",
        tint = Color(0xFF2E9E5B),
        icon = EcoIcons.Gift,
    ),
    RewardCode(
        key = "alipay",
        title = "支付宝",
        latin = "ALIPAY",
        resId = R.drawable.reward_alipay,
        fileBase = "易控钱包-支付宝收款码",
        extension = "jpg",
        tint = Color(0xFF2E6FBF),
        icon = EcoIcons.Wallet,
    ),
)

/**
 * 打赏页（路由 `reward`，入口在个人页底排的陶红「打赏」圆钮）。
 *
 * 原来是个人页里的 `RewardDialog` 弹窗；改独立页后：
 * - 不再被对话框 28dp 边距压小，扫码图能放到接近整宽；
 * - 图片可点开全屏预览，预览页与卡片上都能「保存到相册」——
 *   存的是原始资源字节，可直接在微信/支付宝「扫一扫 → 相册」里识别；
 * - 保存失败/权限被拒统一走 [EcoToast]，不弹二级对话框。
 */
@Composable
fun RewardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewing by remember { mutableStateOf<RewardCode?>(null) }
    // 正在保存的那张（key）：按钮显示「保存中…」并禁用重复点击
    var savingKey by remember { mutableStateOf<String?>(null) }
    // Android 8/9 需要存储权限：先记下待保存的图，授权回调里再继续写
    var pendingKey by remember { mutableStateOf<String?>(null) }

    fun performSave(code: RewardCode) {
        if (savingKey != null) return
        savingKey = code.key
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AlbumSaver.saveRawResource(context, code.resId, code.fileBase, code.extension)
                }
            }
            savingKey = null
            // 预览是独立窗口，全局 Toast 画在它后面。保存完先收起预览，用户才看得到结果；
            // 失败也收起——重试重新点开即可，比闷在黑底里没反馈好。
            if (previewing?.key == code.key) previewing = null
            result.onSuccess { name ->
                AppLog.i("Reward", "收款码已保存到相册：${AlbumSaver.albumRelativePath}/$name")
                EcoToast.show("已保存到相册", EcoPillTone.Good)
            }.onFailure { error ->
                AppLog.w("Reward", "保存收款码失败：${error.message}", error)
                EcoToast.show("保存失败，请检查系统相册权限", EcoPillTone.Warn)
            }
        }
    }

    // 只有 Android 8/9 需要写外部存储的运行时权限；10+ 直接写 MediaStore
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val code = pendingKey?.let { key -> REWARD_CODES.firstOrNull { it.key == key } }
        pendingKey = null
        if (granted && code != null) {
            performSave(code)
        } else if (!granted) {
            EcoToast.show("未授予相册权限，无法保存", EcoPillTone.Warn)
        }
    }

    fun requestSave(code: RewardCode) {
        if (AlbumSaver.needsLegacyPermission() && !AlbumSaver.hasLegacyPermission(context)) {
            pendingKey = code.key
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            performSave(code)
        }
    }

    Column(Modifier.fillMaxSize()) {
        EcoTopBar(micro = "THANK YOU", title = "支持作者", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 2.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EcoPageLead(
                "易控钱包完全本地运行、没有广告，也没有云端账号。" +
                    "如果它帮你省下了对账的时间，可以请作者喝一杯咖啡。",
            )
            REWARD_CODES.forEach { code ->
                RewardCodeCard(
                    code = code,
                    saving = savingKey == code.key,
                    onPreview = { previewing = code },
                    onSave = { requestSave(code) },
                )
            }
            EcoPageNote(
                "打赏纯属自愿，不影响任何功能。保存到相册后，在微信 / 支付宝「扫一扫」里" +
                    "从相册选这张图即可识别。",
            )
        }
    }

    previewing?.let { code ->
        RewardPreview(
            code = code,
            saving = savingKey == code.key,
            onSave = { requestSave(code) },
            onDismiss = { previewing = null },
        )
    }
}

// ---------- 卡片 ----------

/** 单张收款码卡片：品牌行（标题 + 保存动作）+ 整宽码图 + 预览提示。 */
@Composable
private fun RewardCodeCard(
    code: RewardCode,
    saving: Boolean,
    onPreview: () -> Unit,
    onSave: () -> Unit,
) {
    val c = MaterialTheme.eco
    EcoPane(contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(c.surface2)
                        .border(1.dp, c.rule, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        code.icon,
                        contentDescription = null,
                        // 品牌色在暗色下抬向白色，避免深底上绿/蓝发闷
                        tint = if (c.isDark) lerp(code.tint, Color.White, 0.34f) else code.tint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        code.title,
                        style = EcoType.RowTitle.copy(fontSize = 13.5.sp, fontWeight = FontWeight.W800),
                        color = c.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        code.latin,
                        style = EcoType.MicroQuiet.copy(fontSize = 8.sp, letterSpacing = 1.1.sp),
                        color = c.quiet,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                SaveAction(
                    text = if (saving) "保存中…" else "保存",
                    enabled = !saving,
                    onClick = onSave,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 码图容器固定白底 + 内边距：二维码的静区不能被卡片底色吃掉
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White)
                    .border(1.dp, c.rule, RoundedCornerShape(15.dp))
                    .ecoPressable(scaleTo = 0.985f, onClick = onPreview)
                    .padding(7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(code.resId),
                    contentDescription = "${code.title}收款码，点击全屏预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    EcoIcons.Image,
                    contentDescription = null,
                    tint = c.quiet,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "点击图片全屏预览，可保存到相册",
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
                    color = c.quiet,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 小号「保存」动作：主题色，不走品牌色（这是控件，不是品牌标识）。 */
@Composable
private fun SaveAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.eco
    val tint = if (enabled) c.blueDeep else c.quiet
    Row(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .border(1.dp, c.rule, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            EcoIcons.Download,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = EcoType.Pill.copy(fontSize = 10.sp, fontWeight = FontWeight.W700),
            color = tint,
            maxLines = 1,
        )
    }
}

// ---------- 全屏预览 ----------

/**
 * 全屏预览：黑底、码图按短边撑满，底部「保存到相册」。
 *
 * 按产品口径**不做缩放**：收款码在 390dp 宽的机器上已经铺到接近整宽，
 * 全屏只是把点阵放大到可扫/可核对，再加手势会和「点空白关闭」抢事件。
 */
@Composable
private fun RewardPreview(
    code: RewardCode,
    saving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            code.title,
                            style = EcoType.RowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.W800),
                            color = Color.White,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${code.latin} · 收款码",
                            style = EcoType.MicroQuiet.copy(fontSize = 8.5.sp, letterSpacing = 1.1.sp),
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 1,
                        )
                    }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            EcoIcons.Close,
                            contentDescription = "关闭预览",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    // 正方形码图按可用短边取尺寸，横竖屏都不会被裁
                    val side = minOf(maxWidth, maxHeight)
                    Box(
                        Modifier
                            .size(side)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White)
                            .padding(14.dp)
                            // 吞掉码图上的点击：只有点空白处（含图片外的黑底）才关闭
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {},
                    ) {
                        Image(
                            painter = painterResource(code.resId),
                            contentDescription = "${code.title}收款码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    "点击空白处关闭",
                    style = EcoType.PageNote.copy(fontSize = 9.sp),
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(12.dp))
                EcoPrimaryButton(
                    text = if (saving) "保存中…" else "保存到相册",
                    onClick = onSave,
                    enabled = !saving,
                    leading = EcoIcons.Download,
                    height = 46.dp,
                )
            }
        }
    }
}
