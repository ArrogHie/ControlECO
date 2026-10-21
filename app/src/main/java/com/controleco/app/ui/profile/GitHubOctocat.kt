package com.controleco.app.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * GitHub 官方标志（github.com 页面内联的 16×16 mark，与 simple-icons 同源）。
 *
 * 这个标志是「**黑色圆盘挖掉章鱼猫**」：外圈是黑，猫是负空间。
 * 所以白猫 = 圆盘减去这条 path 的部分，而不是 path 本身。
 */
private const val GITHUB_MARK_PATH =
    "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49" +
        "-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82" +
        ".72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15" +
        "-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82" +
        ".44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2" +
        " 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z"

/**
 * 垫底白圆半径（16 画布单位）。
 *
 * 实测猫形最远点到圆心的距离是 7.873，所以 7.9 能把整只猫包住；
 * 又严格小于标志外圈的 8.0，白圆绝不会超出 logo——不会露白边。
 * 且这个半径处的「圆盘 − 猫形」是实心的，`DstOut` 会把它整圈挖掉，
 * 交界处也不会因为抗锯齿残留一圈白线。
 */
private const val OCTOCAT_DISC_RADIUS = 7.9f

/**
 * 白色章鱼猫图标（个人页 GitHub 圆钮用）：**猫白、猫外是按钮的深墨底**。
 *
 * 1. 先画一个纯白圆（半径见 [OCTOCAT_DISC_RADIUS]，正好包住猫、又不超出 logo）；
 * 2. 再用官方标志的 path 以 `BlendMode.DstOut` 挖掉「圆盘 − 猫形」；
 * 3. 剩下的就是完整的白色章鱼猫，圆盘和留白都变透明，露出按钮底色。
 *
 * 不需要 `clip`：白圆本身就比 logo 小，天然不会越界，也就没有裁切边缘的问题。
 * `graphicsLayer(CompositingStrategy.Offscreen)` 必须有——`DstOut` 只能作用于本图层，
 * 否则会把按钮底色一起挖穿。
 *
 * 默认 46dp：猫形占画布 0.68（宽）× 0.78（高），实际白猫约 31.5×36dp，
 * 在 63dp 的圆钮里宽度正好一半、高度约 57%。
 */
@Composable
fun GitHubOctocat(size: Dp = 46.dp) {
    val path = remember { PathParser().parsePathString(GITHUB_MARK_PATH).toPath() }
    Canvas(
        Modifier
            .size(size)
            .semantics { contentDescription = "GitHub 仓库" }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val s = this.size.minDimension / 16f
        scale(s, s, pivot = Offset.Zero) {
            drawCircle(Color.White, radius = OCTOCAT_DISC_RADIUS, center = Offset(8f, 8f))
            drawPath(path, Color.White, blendMode = BlendMode.DstOut)
        }
    }
}
