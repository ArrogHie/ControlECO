package com.controleco.app.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 章鱼猫轮廓，来自 `GitHub_Invertocat_Black_Clearspace.svg`（128×128 画布）。
 *
 * 注意：这条 path 描的是「**圆盘减去猫形**」——它同时包含外接圆和猫的轮廓，
 * 直接填色会得到「白圆盘 + 猫是个洞」（猫看着是黑的）。
 * 要显示白色猫，必须把它当遮罩**挖掉**，只留下猫形。
 */
private const val OCTOCAT_PATH_DATA =
    "M56.7937,84.9688C44.4187,83.4688 35.7,74.5625 35.7,63.0313C35.7,58.3438 37.3875,53.2813 " +
        "40.2,49.9063C38.9812,46.8125 39.1687,40.25 40.575,37.5313C44.325,37.0625 49.3875,39.0313 " +
        "52.3875,41.75C55.95,40.625 59.7,40.0625 64.2937,40.0625C68.8875,40.0625 72.6375,40.625 " +
        "76.0125,41.6563C78.9187,39.0313 84.075,37.0625 87.825,37.5313C89.1375,40.0625 89.325,46.625 " +
        "88.1062,49.8125C91.1062,53.375 92.7,58.1563 92.7,63.0313C92.7,74.5625 83.9812,83.2813 " +
        "71.4187,84.875C74.6062,86.9375 76.7625,91.4375 76.7625,96.5938L76.7625,106.344C76.7625,109.156 " +
        "79.1062,110.75 81.9187,109.625C98.8875,103.156 112.2,86.1875 112.2,65.1875C112.2,38.6563 " +
        "90.6375,17 64.1062,17C37.575,17 16.2,38.6562 16.2,65.1875C16.2,86 29.4187,103.25 " +
        "47.2312,109.719C49.7625,110.656 52.2,108.969 52.2,106.438L52.2,98.9375C50.8875,99.5 " +
        "49.2,99.875 47.7,99.875C41.5125,99.875 37.8562,96.5 35.2312,90.2188C34.2,87.6875 " +
        "33.075,86.1875 30.9187,85.9063C29.7937,85.8125 29.4187,85.3438 29.4187,84.7813C29.4187,83.6563 " +
        "31.2937,82.8125 33.1687,82.8125C35.8875,82.8125 38.2312,84.5 40.6687,87.9688C42.5437,90.6875 " +
        "44.5125,91.9063 46.8562,91.9063C49.2,91.9063 50.7,91.0625 52.8562,88.9063C54.45,87.3125 " +
        "55.6687,85.9063 56.7937,84.9688Z"

/** logo 外接圆在 128 画布里的位置与直径（由 path 上三点反推：圆心 64.2,65.0、半径 48） */
private const val OCTOCAT_DISC_LEFT = 16.2f
private const val OCTOCAT_DISC_TOP = 17f
private const val OCTOCAT_DISC_SIZE = 96f

/**
 * 白色章鱼猫图标（个人页 GitHub 圆钮用）：**猫白、猫外是按钮的深墨底**。
 *
 * 做法：
 * 1. 画布就是 logo 的**外接圆**（96/128），并 `clip(CircleShape)`——所以绘制范围天然不会超出 logo；
 * 2. 先铺满纯白，再用 logo 的 path 以 `BlendMode.DstOut` 把「圆盘 − 猫形」整块挖掉；
 * 3. 挖完只剩猫形是白的，其余（圆盘与留白）变透明，露出按钮底色。
 *
 * `graphicsLayer(CompositingStrategy.Offscreen)` 必须有：`DstOut` 只能作用于本图层，
 * 否则会把按钮底色一起挖穿。
 *
 * 猫形约占外接圆的 0.66（宽）× 0.78（高）；默认 48dp 时白猫约 31.5×37.5dp，
 * 在 63dp 的圆钮里宽度正好一半、高度约 60%。
 */
@Composable
fun GitHubOctocat(size: Dp = 48.dp) {
    val path = remember { PathParser().parsePathString(OCTOCAT_PATH_DATA).toPath() }
    Canvas(
        Modifier
            .size(size)
            // 换成 Canvas 后没有 Icon 自带的语义，这里补回来
            .semantics { contentDescription = "GitHub 仓库" }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .clip(CircleShape),
    ) {
        val s = this.size.minDimension / OCTOCAT_DISC_SIZE
        scale(s, s, pivot = Offset.Zero) {
            translate(-OCTOCAT_DISC_LEFT, -OCTOCAT_DISC_TOP) {
                // 纯白底：范围就是 logo 的外接圆，绝不会比 logo 更大
                drawRect(Color.White, size = Size(OCTOCAT_DISC_SIZE, OCTOCAT_DISC_SIZE))
                // 用「圆盘 − 猫形」把白底挖穿，只留下白色猫形
                drawPath(path, Color.White, blendMode = BlendMode.DstOut)
            }
        }
    }
}
