package com.controleco.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 设计稿 v5 的 24x24 线性图标集（stroke 1.7，圆头圆角）。
 *
 * 说明：分类图标仍使用项目现有的 [CategoryGlyph]（用户要求优先复用项目资产）；
 * 此文件只承担导航、操作、状态等界面图标。路径由原型 ICONS 对象生成。
 */
object EcoIcons {
    /** home */
    val Home: ImageVector by lazy {
        ecoIcon(
            "home",
            listOf(
            "M3 10.2 12 3l9 7.2V20a1 1 0 0 1-1 1h-5.4v-6.5H9.4V21H4a1 1 0 0 1-1-1Z",
            ),
        )
    }

    /** list */
    val List: ImageVector by lazy {
        ecoIcon(
            "list",
            listOf(
            "M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01",
            ),
        )
    }

    /** chart */
    val Chart: ImageVector by lazy {
        ecoIcon(
            "chart",
            listOf(
            "M3 20V9M9 20V4M15 20v-8M21 20V7M2.5 20.5h19",
            ),
        )
    }

    /** user */
    val User: ImageVector by lazy {
        ecoIcon(
            "user",
            listOf(
            "M8.4 7.6A3.6 3.6 0 1 0 15.6 7.6A3.6 3.6 0 1 0 8.4 7.6Z",
            "M4.5 20.4v-1.5a7.5 7.5 0 0 1 15 0v1.5",
            ),
        )
    }

    /** plus */
    val Plus: ImageVector by lazy {
        ecoIcon(
            "plus",
            listOf(
            "M12 4.5v15M4.5 12h15",
            ),
        )
    }

    /** back */
    val Back: ImageVector by lazy {
        ecoIcon(
            "back",
            listOf(
            "m15 4.8-7.2 7.2L15 19.2",
            ),
        )
    }

    /** next */
    val Next: ImageVector by lazy {
        ecoIcon(
            "next",
            listOf(
            "m9 4.8 7.2 7.2L9 19.2",
            ),
        )
    }

    /** chev */
    val Chev: ImageVector by lazy {
        ecoIcon(
            "chev",
            listOf(
            "m9 5 7 7-7 7",
            ),
        )
    }

    /** chevDown */
    val ChevDown: ImageVector by lazy {
        ecoIcon(
            "chevDown",
            listOf(
            "m5 9 7 7 7-7",
            ),
        )
    }

    /** close */
    val Close: ImageVector by lazy {
        ecoIcon(
            "close",
            listOf(
            "M5.5 5.5l13 13M18.5 5.5l-13 13",
            ),
        )
    }

    /** settings（Tabler Icons: settings） */
    val Gear: ImageVector by lazy {
        ecoIcon(
            "gear",
            listOf(
            "M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.37 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.94 1.543 -.826 3.31 -2.37 2.37a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 -.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37c1 .608 2.296 .07 2.572 -1.065",
            "M9 12a3 3 0 1 0 6 0a3 3 0 0 0 -6 0",
            ),
        )
    }

    /** bell */
    val Bell: ImageVector by lazy {
        ecoIcon(
            "bell",
            listOf(
            "M18 8.5a6 6 0 0 0-12 0V14l-2 3h16l-2-3Z",
            "M9.7 20.2h4.6",
            ),
        )
    }

    /** clock */
    val Clock: ImageVector by lazy {
        ecoIcon(
            "clock",
            listOf(
            "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12Z",
            "M12 6.6V12l3.6 2.1",
            ),
        )
    }

    /** calendar */
    val Calendar: ImageVector by lazy {
        ecoIcon(
            "calendar",
            listOf(
            "M5.6 5H18.4A2.4 2.4 0 0 1 20.8 7.4V18.6A2.4 2.4 0 0 1 18.4 21H5.6A2.4 2.4 0 0 1 3.2 18.6V7.4A2.4 2.4 0 0 1 5.6 5Z",
            "M3.2 10h17.6M8 2.8v4.4M16 2.8v4.4",
            ),
        )
    }

    /** image */
    val Image: ImageVector by lazy {
        ecoIcon(
            "image",
            listOf(
            "M6 3.5H18A3 3 0 0 1 21 6.5V17.5A3 3 0 0 1 18 20.5H6A3 3 0 0 1 3 17.5V6.5A3 3 0 0 1 6 3.5Z",
            "M6.6 8.4A1.7 1.7 0 1 0 10 8.4A1.7 1.7 0 1 0 6.6 8.4Z",
            "m4.2 17 5-5 3.4 3 2.5-2.1 5.5 5.4",
            ),
        )
    }

    /** wallet */
    val Wallet: ImageVector by lazy {
        ecoIcon(
            "wallet",
            listOf(
            "M5.7 6H18.3A2.7 2.7 0 0 1 21 8.7V17.8A2.7 2.7 0 0 1 18.3 20.5H5.7A2.7 2.7 0 0 1 3 17.8V8.7A2.7 2.7 0 0 1 5.7 6Z",
            "M3 10h18M16 15.2h2.4",
            "M6.5 6V4.6a1.4 1.4 0 0 1 1.8-1.35L19 5.3",
            ),
        )
    }

    /** bowl */
    val Bowl: ImageVector by lazy {
        ecoIcon(
            "bowl",
            listOf(
            "M3.2 11.2h17.6c0 4.6-3.8 8.3-8.8 8.3s-8.8-3.7-8.8-8.3Z",
            "M8 7.6c-.9-1.4.9-2.4 0-3.8M12 7.6c-.9-1.4.9-2.4 0-3.8M16 7.6c-.9-1.4.9-2.4 0-3.8M4.5 21h15",
            ),
        )
    }

    /** bag */
    val Bag: ImageVector by lazy {
        ecoIcon(
            "bag",
            listOf(
            "M5 8.4h14l1 12.2H4Z",
            "M8.5 9V6.4a3.5 3.5 0 0 1 7 0V9",
            ),
        )
    }

    /** train */
    val Train: ImageVector by lazy {
        ecoIcon(
            "train",
            listOf(
            "M8 3H16A3 3 0 0 1 19 6V15A3 3 0 0 1 16 18H8A3 3 0 0 1 5 15V6A3 3 0 0 1 8 3Z",
            "M5 10h14M8.5 21l2-3M15.5 21l-2-3M8.5 7h.01M15.5 7h.01",
            ),
        )
    }

    /** shop */
    val Shop: ImageVector by lazy {
        ecoIcon(
            "shop",
            listOf(
            "M3.2 9.6h17.6l-1.3-6.1H4.5Z",
            "M4.5 9.6V21h15V9.6M9 21v-6.3h6V21",
            ),
        )
    }

    /** spark */
    val Spark: ImageVector by lazy {
        ecoIcon(
            "spark",
            listOf(
            "M12 2.8c.6 4.9 2.3 6.6 7.2 7.2-4.9.6-6.6 2.3-7.2 7.2-.6-4.9-2.3-6.6-7.2-7.2 4.9-.6 6.6-2.3 7.2-7.2Z",
            "M19 16.4c.25 2 .95 2.7 2.9 2.95-1.95.25-2.65.95-2.9 2.95-.25-2-.95-2.7-2.9-2.95 1.95-.25 2.65-.95 2.9-2.95Z",
            ),
        )
    }

    /** pie */
    val Pie: ImageVector by lazy {
        ecoIcon(
            "pie",
            listOf(
            "M12 3a9 9 0 1 0 9 9h-9Z",
            "M15 3.5V9h5.5A9 9 0 0 0 15 3.5Z",
            ),
        )
    }

    /** tag */
    val Tag: ImageVector by lazy {
        ecoIcon(
            "tag",
            listOf(
            "M3.2 12.2V4.5a1.3 1.3 0 0 1 1.3-1.3h7.7L21 12l-8.7 8.7Z",
            "M6.6 8A1.4 1.4 0 1 0 9.4 8A1.4 1.4 0 1 0 6.6 8Z",
            ),
        )
    }

    /** rotate */
    val Rotate: ImageVector by lazy {
        ecoIcon(
            "rotate",
            listOf(
            "M20.2 11.2a8.2 8.2 0 1 1-2.6-5.9",
            "M20.2 3.4v5.6h-5.6",
            ),
        )
    }

    /** backspace */
    val Backspace: ImageVector by lazy {
        ecoIcon(
            "backspace",
            listOf(
            "M9 5h11.2a1.8 1.8 0 0 1 1.8 1.8v10.4a1.8 1.8 0 0 1-1.8 1.8H9L2.8 12Z",
            "m12 9 6 6M18 9l-6 6",
            ),
        )
    }

    /** gift */
    val Gift: ImageVector by lazy {
        ecoIcon(
            "gift",
            listOf(
            "M4.6 8.2H19.4A1 1 0 0 1 20.4 9.2V11.4A1 1 0 0 1 19.4 12.4H4.6A1 1 0 0 1 3.6 11.4V9.2A1 1 0 0 1 4.6 8.2Z",
            "M5.2 12.4V21h13.6v-8.6M12 8.2V21",
            "M12 8.2C9.2 8.2 6.4 7.6 6.4 5.3A2.1 2.1 0 0 1 8.5 3.2c2.1 0 3.5 2.5 3.5 5ZM12 8.2c2.8 0 5.6-.6 5.6-2.9a2.1 2.1 0 0 0-2.1-2.1c-2.1 0-3.5 2.5-3.5 5Z",
            ),
        )
    }

    /** leaf */
    val Leaf: ImageVector by lazy {
        ecoIcon(
            "leaf",
            listOf(
            "M4.8 19.2C4.8 10.8 11 5 20 5c-1 8.8-7 14-14.6 14.2Z",
            "M5.8 19.2C8.4 14.3 12.5 10.8 18 8.6",
            ),
        )
    }

    /** check */
    val Check: ImageVector by lazy {
        ecoIcon(
            "check",
            listOf(
            "m4.5 12.4 4.6 4.6L19.6 6.6",
            ),
        )
    }

    /** info */
    val Info: ImageVector by lazy {
        ecoIcon(
            "info",
            listOf(
            "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12Z",
            "M12 10.8V17M12 7.4h.01",
            ),
        )
    }

    /** pencil（Tabler Icons: pencil） */
    val Edit: ImageVector by lazy {
        ecoIcon(
            "edit",
            listOf(
            "M4 20h4l10.5 -10.5a2.828 2.828 0 1 0 -4 -4l-10.5 10.5v4",
            "M13.5 6.5l4 4",
            ),
        )
    }

    /** trash */
    val Trash: ImageVector by lazy {
        ecoIcon(
            "trash",
            listOf(
            "M4 7h16M9.5 7V4.6h5V7M6.4 7l1 13.4h9.2L18 7M10 11v5.5M14 11v5.5",
            ),
        )
    }

    /** more */
    val More: ImageVector by lazy {
        ecoIcon(
            "more",
            listOf(
            "M10.7 5A1.3 1.3 0 1 0 13.3 5A1.3 1.3 0 1 0 10.7 5Z",
            "M10.7 12A1.3 1.3 0 1 0 13.3 12A1.3 1.3 0 1 0 10.7 12Z",
            "M10.7 19A1.3 1.3 0 1 0 13.3 19A1.3 1.3 0 1 0 10.7 19Z",
            ),
        )
    }

    /** shield */
    val Shield: ImageVector by lazy {
        ecoIcon(
            "shield",
            listOf(
            "M12 2.7 20 6v5.8c0 4.6-3.2 7.8-8 9.5-4.8-1.7-8-4.9-8-9.5V6Z",
            "m8.6 12 2.4 2.4 4.5-5",
            ),
        )
    }

    /** a11y */
    val A11y: ImageVector by lazy {
        ecoIcon(
            "a11y",
            listOf(
            "M10.2 4.6A1.8 1.8 0 1 0 13.8 4.6A1.8 1.8 0 1 0 10.2 4.6Z",
            "M4.5 8.5c5 1.3 10 1.3 15 0M12 8.5v5.2M12 13.7 8.2 21M12 13.7l3.8 7.3",
            ),
        )
    }

    /** overlay */
    val Overlay: ImageVector by lazy {
        ecoIcon(
            "overlay",
            listOf(
            "M5.5 4H18.5A2.5 2.5 0 0 1 21 6.5V17.5A2.5 2.5 0 0 1 18.5 20H5.5A2.5 2.5 0 0 1 3 17.5V6.5A2.5 2.5 0 0 1 5.5 4Z",
            "M8.5 10.5H15.5A1.5 1.5 0 0 1 17 12V15A1.5 1.5 0 0 1 15.5 16.5H8.5A1.5 1.5 0 0 1 7 15V12A1.5 1.5 0 0 1 8.5 10.5Z",
            ),
        )
    }

    /** sms */
    val Sms: ImageVector by lazy {
        ecoIcon(
            "sms",
            listOf(
            "M4 4.5h16v12.6H9.2L4 21Z",
            "M8 9h8M8 12.4h5.5",
            ),
        )
    }

    /** app */
    val App: ImageVector by lazy {
        ecoIcon(
            "app",
            listOf(
            "M7 2.8H17A3 3 0 0 1 20 5.8V18.2A3 3 0 0 1 17 21.2H7A3 3 0 0 1 4 18.2V5.8A3 3 0 0 1 7 2.8Z",
            "M9 5.4h6M10.5 18.2h3",
            ),
        )
    }

    /** key */
    val Key: ImageVector by lazy {
        ecoIcon(
            "key",
            listOf(
            "M5 12A3.2 3.2 0 1 0 11.4 12A3.2 3.2 0 1 0 5 12Z",
            "M11.4 12H21M17.2 12v3.2M14.4 12v2.2",
            ),
        )
    }

    /** file */
    val File: ImageVector by lazy {
        ecoIcon(
            "file",
            listOf(
            "M6 2.8h7.2L19 8.6V21H6Z",
            "M13 2.8v6h6M9 13h6M9 16.5h4.5",
            ),
        )
    }

    /** link */
    val Link: ImageVector by lazy {
        ecoIcon(
            "link",
            listOf(
            "M9.5 14.5 14.5 9.5M8.2 17.8l-1.2 1.2a3.5 3.5 0 0 1-4.9-4.9l3.1-3.1a3.5 3.5 0 0 1 4.9 0M15.8 6.2l1.2-1.2a3.5 3.5 0 0 1 4.9 4.9l-3.1 3.1a3.5 3.5 0 0 1-4.9 0",
            ),
        )
    }

    /** undo */
    val Undo: ImageVector by lazy {
        ecoIcon(
            "undo",
            listOf(
            "M8.5 5.5 4 10l4.5 4.5M4.5 10h9.2a5.3 5.3 0 0 1 0 10.6H9",
            ),
        )
    }

    /** external */
    val External: ImageVector by lazy {
        ecoIcon(
            "external",
            listOf(
            "M7 17 17 7M9.5 7H17v7.5",
            ),
        )
    }

    /** phone */
    val Phone: ImageVector by lazy {
        ecoIcon(
            "phone",
            listOf(
            "M9 2.5H15A2.5 2.5 0 0 1 17.5 5V19A2.5 2.5 0 0 1 15 21.5H9A2.5 2.5 0 0 1 6.5 19V5A2.5 2.5 0 0 1 9 2.5Z",
            "M10 5h4M11 18.6h2",
            ),
        )
    }

    /** battery */
    val Battery: ImageVector by lazy {
        ecoIcon(
            "battery",
            listOf(
            "M4.5 7H17.5A2 2 0 0 1 19.5 9V15A2 2 0 0 1 17.5 17H4.5A2 2 0 0 1 2.5 15V9A2 2 0 0 1 4.5 7Z",
            "M22 10v4M6 10v4M10 10v4",
            ),
        )
    }

    /** refresh */
    val Refresh: ImageVector by lazy {
        ecoIcon(
            "refresh",
            listOf(
            "M20 11a8 8 0 0 0-14.7-4M4 5v6h6M4 13a8 8 0 0 0 14.7 4M20 19v-6h-6",
            ),
        )
    }

    /** copy */
    val Copy: ImageVector by lazy {
        ecoIcon(
            "copy",
            listOf(
            "M10 8H18A2 2 0 0 1 20 10V18A2 2 0 0 1 18 20H10A2 2 0 0 1 8 18V10A2 2 0 0 1 10 8Z",
            "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3",
            ),
        )
    }

    /** warning */
    val Warning: ImageVector by lazy {
        ecoIcon(
            "warning",
            listOf(
            "M12 3 22 20H2Z",
            "M12 9v5.4M12 17.4h.01",
            ),
        )
    }

    /** play */
    val Play: ImageVector by lazy {
        ecoIcon(
            "play",
            listOf(
            "m8 5 11 7-11 7Z",
            ),
        )
    }

    /** sun */
    val Sun: ImageVector by lazy {
        ecoIcon(
            "sun",
            listOf(
            "M8 12A4 4 0 1 0 16 12A4 4 0 1 0 8 12Z",
            "M12 2v2M12 20v2M4.5 4.5 6 6M18 18l1.5 1.5M2 12h2M20 12h2M4.5 19.5 6 18M18 6l1.5-1.5",
            ),
        )
    }

    /** moon */
    val Moon: ImageVector by lazy {
        ecoIcon(
            "moon",
            listOf(
            "M20.5 14.2A8.5 8.5 0 0 1 9.8 3.5 8.5 8.5 0 1 0 20.5 14.2Z",
            ),
        )
    }

    /** brush（Tabler Icons: brush） */
    val Brush: ImageVector by lazy {
        ecoIcon(
            "brush",
            listOf(
            "M3 21v-4a4 4 0 1 1 4 4h-4",
            "M21 3a16 16 0 0 0 -12.8 10.2",
            "M21 3a16 16 0 0 1 -10.2 12.8",
            "M10.6 9a9 9 0 0 1 4.4 4.4",
            ),
        )
    }

    /** coffee */
    val Coffee: ImageVector by lazy {
        ecoIcon(
            "coffee",
            listOf(
            "M4 8h13v6.5a4.5 4.5 0 0 1-4.5 4.5H8.5A4.5 4.5 0 0 1 4 14.5Z",
            "M17 9.5h1.8a2.5 2.5 0 0 1 0 5H17M7 3.5c-.7 1 .7 1.5 0 2.5M11 3.5c-.7 1 .7 1.5 0 2.5",
            ),
        )
    }

    /** house */
    val House: ImageVector by lazy {
        ecoIcon(
            "house",
            listOf(
            "M3 10.5 12 3l9 7.5V21H3Z",
            "M9.5 21v-7h5v7",
            ),
        )
    }

    /** medical */
    val Medical: ImageVector by lazy {
        ecoIcon(
            "medical",
            listOf(
            "M6 5H18A3 3 0 0 1 21 8V18A3 3 0 0 1 18 21H6A3 3 0 0 1 3 18V8A3 3 0 0 1 6 5Z",
            "M8 3v4M16 3v4M12 9.5v7M8.5 13h7",
            ),
        )
    }

    /** book */
    val Book: ImageVector by lazy {
        ecoIcon(
            "book",
            listOf(
            "M3.5 5.2A3 3 0 0 1 6.5 2.8H12v18H6.5a3 3 0 0 0-3 2.4ZM20.5 5.2a3 3 0 0 0-3-2.4H12v18h5.5a3 3 0 0 1 3 2.4Z",
            ),
        )
    }

    /** pet */
    val Pet: ImageVector by lazy {
        ecoIcon(
            "pet",
            listOf(
            "M5 8A2 2 0 1 0 9 8A2 2 0 1 0 5 8Z",
            "M10 6A2 2 0 1 0 14 6A2 2 0 1 0 10 6Z",
            "M15 8A2 2 0 1 0 19 8A2 2 0 1 0 15 8Z",
            "M12.5 11.5c3 0 5.5 2.7 5.5 5 0 2-1.5 3.2-3.4 2.6-1.3-.4-2.7-.4-4 0-1.9.6-3.4-.6-3.4-2.6 0-2.3 2.5-5 5.3-5Z",
            ),
        )
    }

    /** plane */
    val Plane: ImageVector by lazy {
        ecoIcon(
            "plane",
            listOf(
            "M3 12.5 21 4l-4.6 16-4.2-6.2ZM12.2 13.8 21 4",
            ),
        )
    }

    /** receipt */
    val Receipt: ImageVector by lazy {
        ecoIcon(
            "receipt",
            listOf(
            "M6 3h12v18l-3-2-3 2-3-2-3 2Z",
            "M9 7h6M9 10.5h6M9 14h4",
            ),
        )
    }

    /** download */
    val Download: ImageVector by lazy {
        ecoIcon(
            "download",
            listOf(
            "M12 3v12M7.5 10.5 12 15l4.5-4.5M4.5 20h15",
            ),
        )
    }

    /** flag */
    val Flag: ImageVector by lazy {
        ecoIcon(
            "flag",
            listOf(
            "M5 21V3M5 4h11l-1.5 3.5L16 11H5Z",
            "M5 7.5h11M8.6 4v7M12.3 4v7",
            ),
        )
    }

    /** search（放大镜）：账单页右上角搜索入口、搜索页输入框 */
    val Search: ImageVector by lazy {
        ecoIcon(
            "search",
            listOf(
            "M4 10.5A6.5 6.5 0 1 0 17 10.5A6.5 6.5 0 1 0 4 10.5Z",
            "M15.2 15.2 20.5 20.5",
            ),
        )
    }

    /** filter（漏斗）：账单页工具条的「按分类筛选」 */
    val Filter: ImageVector by lazy {
        ecoIcon(
            "filter",
            listOf(
            "M4 5h16l-6.4 7.4v7.1l-3.2-1.9v-5.2Z",
            ),
        )
    }

    /** sort（上下双箭头）：账单页/搜索页的「排序方式」 */
    val Sort: ImageVector by lazy {
        ecoIcon(
            "sort",
            listOf(
            "M3 9l4-4 4 4M7 5v14",
            "M21 15l-4 4-4-4M17 19V5",
            ),
        )
    }
}

private fun ecoIcon(name: String, paths: List<String>): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    paths.forEach { d ->
        builder.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}
