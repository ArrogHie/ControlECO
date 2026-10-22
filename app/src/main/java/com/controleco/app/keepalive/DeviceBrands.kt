package com.controleco.app.keepalive

import android.os.Build

/** 设备品牌（保活引导按品牌给不同步骤） */
enum class Brand(val displayName: String) {
    XIAOMI("小米/红米"),
    HUAWEI("华为"),
    HONOR("荣耀"),
    OPPO("OPPO"),
    ONEPLUS("一加"),
    REALME("真我 realme"),
    VIVO("vivo/iQOO"),
    SAMSUNG("三星"),
    OTHER("其他品牌");

    companion object {
        /** 纯函数便于单测；荣耀先于华为判断（老荣耀机型厂商字段报 HUAWEI，走华为指引也基本一致） */
        fun from(manufacturer: String): Brand {
            val m = manufacturer.lowercase().trim()
            return when {
                m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> XIAOMI
                m.contains("honor") -> HONOR
                m.contains("huawei") -> HUAWEI
                m.contains("oppo") -> OPPO
                m.contains("oneplus") -> ONEPLUS
                m.contains("realme") -> REALME
                m.contains("vivo") || m.contains("iqoo") -> VIVO
                m.contains("samsung") -> SAMSUNG
                else -> OTHER
            }
        }

        fun current(): Brand = from(Build.MANUFACTURER ?: "")
    }
}

/** 引导步骤里的可跳转动作 */
enum class KeepAliveAction { AUTOSTART, APP_INFO }

/** 一条保活设置步骤：标题 + 操作说明 + 可选跳转按钮 */
data class KeepAliveStep(
    val title: String,
    val detail: String,
    val actions: List<KeepAliveAction> = emptyList(),
)

object DeviceBrands {

    fun stepsFor(brand: Brand): List<KeepAliveStep> = when (brand) {
        Brand.XIAOMI -> listOf(
            KeepAliveStep(
                "自启动",
                "安全中心 → 应用管理 → 自启动，找到 易控钱包 打开开关；或长按桌面图标 → 应用信息 → 自启动。",
                listOf(KeepAliveAction.AUTOSTART, KeepAliveAction.APP_INFO),
            ),
            KeepAliveStep(
                "省电策略设为「无限制」",
                "应用信息 → 省电策略 → 选择「无限制」（澎湃OS：应用信息 → 电量消耗 → 省电策略）。",
                listOf(KeepAliveAction.APP_INFO),
            ),
            KeepAliveStep(
                "锁定后台",
                "最近任务里下拉 易控钱包 卡片加锁，防止一键清理误杀。",
            ),
            KeepAliveStep(
                "小米专属提示",
                "开启无障碍/通知使用权时有约十秒倒计时确认（十秒盾）；系统或应用更新后如发现收不到通知，把通知使用权重新开关一次。",
            ),
            KeepAliveStep(
                "支付保险箱（页面采集/悬浮卡被拦截时）",
                "安全中心 → 支付保险箱会在支付页上盖一层保护界面：轻则 易控钱包 读不到支付页面（捕获日志里没有页面快照），" +
                    "重则悬浮确认卡弹不出来（回落横幅通知）。在支付保险箱设置里关闭对微信/支付宝的保护。",
            ),
        )

        Brand.HUAWEI -> listOf(
            KeepAliveStep(
                "应用启动管理（关键）",
                "设置 → 应用和服务 → 应用启动管理 → 易控钱包 → 关闭「自动管理」，手动打开：允许自启动、允许关联启动、允许后台活动。",
                listOf(KeepAliveAction.AUTOSTART, KeepAliveAction.APP_INFO),
            ),
            KeepAliveStep(
                "电池优化",
                "设置搜索「电池优化」→ 找到 易控钱包 → 设为「不允许」（上方白名单按钮可直接申请）。",
            ),
            KeepAliveStep(
                "锁定后台",
                "最近任务里下拉 易控钱包 卡片加锁。",
            ),
            KeepAliveStep(
                "华为专属提示",
                "支付保护中心会盖住支付页并拦截页面读取与悬浮卡弹出：在 支付保护中心 → 应用列表 中关闭对 易控钱包 的保护；" +
                    "系统更新后复查启动管理设置与受限设置开关。",
            ),
        )

        Brand.HONOR -> listOf(
            KeepAliveStep(
                "应用启动管理（关键）",
                "设置搜索「应用启动管理」→ 易控钱包 → 关闭「自动管理」，手动打开：允许自启动、允许关联启动、允许后台活动。",
                listOf(KeepAliveAction.AUTOSTART, KeepAliveAction.APP_INFO),
            ),
            KeepAliveStep(
                "电池优化",
                "设置搜索「电池优化」→ 找到 易控钱包 → 设为「不允许」（上方白名单按钮可直接申请）。",
            ),
            KeepAliveStep(
                "锁定后台",
                "最近任务里下拉 易控钱包 卡片加锁。",
            ),
            KeepAliveStep(
                "支付保护中心（页面采集/悬浮卡被拦截时）",
                "荣耀的支付保护中心会在支付页上盖保护层：易控钱包 可能读不到支付页面、悬浮确认卡也可能弹不出来（回落通知）。" +
                    "在支付保护中心的应用列表里关闭对 易控钱包 的保护。",
            ),
        )

        Brand.OPPO, Brand.ONEPLUS, Brand.REALME -> listOf(
            KeepAliveStep(
                "自启动",
                "设置 → 应用 → 自启动管理（或手机管家 → 权限隐私 → 自启动），允许 易控钱包。",
                listOf(KeepAliveAction.AUTOSTART),
            ),
            KeepAliveStep(
                "允许完全后台行为",
                "长按桌面图标 → 应用信息 → 电池 → 选择「允许完全后台行为」。",
                listOf(KeepAliveAction.APP_INFO),
            ),
            KeepAliveStep(
                "关闭应用速冻与睡眠待机优化",
                "设置 → 电池 → 更多设置：在「应用速冻」和「睡眠待机优化」中排除 易控钱包（否则夜间会被冻结，收不到动账通知）。",
            ),
            KeepAliveStep(
                "锁定后台",
                "最近任务里下拉 易控钱包 卡片加锁。",
            ),
            KeepAliveStep(
                "支付保护（页面采集/悬浮卡被拦截时）",
                "手机管家的「支付保护」类功能会盖住支付页、干扰页面读取与悬浮卡弹出：若付款后没有进待确认、" +
                    "悬浮确认卡也不出现，在支付保护/纯净模式设置里关闭对微信/支付宝的支付保护。",
            ),
        )

        Brand.VIVO -> listOf(
            KeepAliveStep(
                "自启动与后台弹出",
                "i管家 → 应用管理 → 权限 → 「自启动」和「后台弹出」均允许 易控钱包。",
                listOf(KeepAliveAction.AUTOSTART),
            ),
            KeepAliveStep(
                "关闭睡眠待机优化",
                "设置 → 电池 → 睡眠待机优化中排除 易控钱包。",
            ),
            KeepAliveStep(
                "锁定后台",
                "最近任务里下拉 易控钱包 卡片加锁。",
            ),
        )

        Brand.SAMSUNG -> listOf(
            KeepAliveStep(
                "电池 → 后台使用限制",
                "设置 → 电池 → 后台使用限制：把 易控钱包 从「深度睡眠」列表移除，或加入「未受限应用」。",
                listOf(KeepAliveAction.APP_INFO),
            ),
            KeepAliveStep(
                "电池优化",
                "设置搜索「电池优化」→ 找到 易控钱包 → 设为「不允许」。",
            ),
        )

        Brand.OTHER -> listOf(
            KeepAliveStep(
                "自启动/后台运行",
                "在系统管家/安全中心里查找「自启动」「后台运行」类权限并允许 易控钱包（不同品牌路径不同）。",
                listOf(KeepAliveAction.APP_INFO),
            ),
            KeepAliveStep(
                "电池优化",
                "设置搜索「电池优化」→ 找到 易控钱包 → 设为「不允许」（上方白名单按钮可直接申请）。",
            ),
            KeepAliveStep(
                "锁定后台",
                "最近任务里下拉 易控钱包 卡片加锁（如系统支持）。",
            ),
        )
    }
}
