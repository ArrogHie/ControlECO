package com.controleco.app.capture.page

/**
 * 页面解析器注册表：按包名分派到应用专属解析器。
 *
 * 调用链只有这一条入口：
 * `AccBookAccessibilityService`（采集快照）→ `PageParserRegistry.parse`（按包名分派）→
 * `CaptureCenter`（候选融合与入账）。旧版"所有应用共用一套关键词规则"的入口已删除，
 * 不存在第二个页面解析入口。
 *
 * 分派顺序：专属解析器（[dedicated]）→ 兜底解析器（[GenericPageParser]，仅对监听目录内
 * 或银行 App 的包名生效）→ 不支持（返回 null，采集层据此决定是否留样本）。
 */
object PageParserRegistry {

    /** 应用专属解析器（每个应用/同系列应用一个实例，见各解析器文件头部的覆盖说明） */
    private val dedicated: List<AppPageParser> = listOf(
        WechatPageParser,
        AlipayPageParser,
        UnionPayPageParser,
        JdPageParser,
        TaobaoPageParser,
        PinduoduoPageParser,
        DouyinPageParser,
        MeituanPageParser,
        ElemePageParser,
    )

    private val byPackage: Map<String, AppPageParser> =
        dedicated.flatMap { parser -> parser.packages.map { it to parser } }.toMap()

    /** 包名 → 解析器（专属优先，其次兜底；都不支持返回 null） */
    fun parserFor(packageName: String): AppPageParser? =
        byPackage[packageName] ?: if (GenericPageParser.accepts(packageName)) GenericPageParser else null

    /** 是否有专属（逐应用校准过的）解析器 */
    fun hasDedicatedParser(packageName: String): Boolean = byPackage.containsKey(packageName)

    /** 专属解析器覆盖的包名（设置页/文档的"已适配应用"以此为准，不把"已注册包名"当"已支持"） */
    fun dedicatedPackages(): Set<String> = byPackage.keys

    /**
     * 解析一张页面快照。异常一律被吞掉并降级为"与交易无关"——
     * 单个应用的解析器出问题绝不能让整条采集链路（乃至无障碍服务）不可用。
     */
    fun parse(snapshot: PageSnapshot, flow: PageFlow): PageParseResult {
        val parser = parserFor(snapshot.packageName) ?: return PageParseResult.NotATradePage
        return try {
            parser.parse(snapshot, flow)
        } catch (e: Exception) {
            SafeParseResult.of(snapshot.packageName, parser.label, e)
        }
    }

    /** 兜底解析器是否覆盖该包名（用于采集层的"是否留样本"判断） */
    fun fallbackAccepts(packageName: String): Boolean = GenericPageParser.accepts(packageName)
}

/**
 * 解析器异常的降级结果：记录一次并返回"与交易无关"。
 * 单独抽出来便于在测试里断言"任何解析器抛异常都不会传播到采集层"。
 */
internal object SafeParseResult {
    fun of(packageName: String, parserLabel: String, error: Throwable): PageParseResult {
        com.controleco.app.util.AppLog.w(
            "Page",
            "parser failed pkg=$packageName parser=$parserLabel: ${error.javaClass.simpleName}: ${error.message}",
        )
        return PageParseResult.SceneOnly(PageScene.UNKNOWN, TradeState.UNKNOWN, "解析器异常已隔离")
    }
}
