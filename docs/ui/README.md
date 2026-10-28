# UI 页面开发文档

这套文档的目标：**改一个页面时，只读这一页的文档 + 共享组件文档，就能动手**，不必通读全部 Compose 代码。

适用对象是接手的 agent 或开发者。每页文档都按同一模板写：

1. 路由与入口
2. 文件地图（页面 + 私有 composable + 依赖）
3. 从上到下的页面骨架
4. 关键尺寸 / 字号 / 颜色
5. 状态与数据流
6. 交互与跳转
7. 文案规则
8. 「想改 X 看 Y」
9. 坑与验收清单

## 先读哪两篇

任何 UI 改动前，先读：

- [共享设计系统与组件](00-共享设计系统与组件.md)：颜色、字号、间距、通用组件、字体缩放、动画约定。
- [应用外壳与导航](01-应用外壳与导航.md)：路由、四个 tab、底部栏、FAB、页面转场、全局弹窗/Toast。

然后按下面的索引进入具体页面。页面文档里引用的组件，必要时再跳回 `00`。

## 路由 / 文件索引

路由定义在 `app/src/main/java/com/controleco/app/ui/AppNav.kt` 的 `NavHost`。路由名同时被通知跳转、快捷方式、桌面磁贴使用，**不要重命名**。

| 页面 | 路由 | 主文件 | 文档 |
| --- | --- | --- | --- |
| 应用外壳 / 四 tab | `main` | `ui/AppNav.kt`、`MainActivity.kt` | [01-应用外壳与导航](01-应用外壳与导航.md) |
| 首页 | `main` tab 0 | `ui/home/HomeScreen.kt` | [02-首页](02-首页.md) |
| 账单 | `main` tab 1 | `ui/transactions/TransactionListScreen.kt`、`BalanceCard.kt` | [03-账单](03-账单.md) |
| 统计 | `main` tab 2 | `ui/stats/StatsScreen.kt` | [04-统计](04-统计.md) |
| 个人 | `main` tab 3 | `ui/profile/ProfileScreen.kt` | [05-个人](05-个人.md) |
| 快速记账 | `quickAdd` | `ui/transactions/QuickAddScreen.kt` | [06-快速记账](06-快速记账.md) |
| 编辑交易 | `edit/{txId}` | `ui/transactions/EditTransactionScreen.kt`、`TxFormKit.kt` | [07-编辑交易](07-编辑交易.md) |
| 账户管理 | `accounts` | `ui/accounts/AccountsScreen.kt` | [08-账户管理](08-账户管理.md) |
| 分类管理 | `categories` | `ui/accounts/CategoriesScreen.kt` | [09-分类管理](09-分类管理.md) |
| 导入账单 | `import` | `ui/imports/ImportScreen.kt` | [10-导入账单](10-导入账单.md) |
| 待确认账单 | `pending` | `ui/pending/PendingScreen.kt` | [11-待确认](11-待确认.md) |
| 退款关联 | `refundLink` | `ui/refund/RefundLinkScreen.kt` | [12-退款关联](12-退款关联.md) |
| 捕获日志 | `captureLog` | `ui/debug/CaptureLogScreen.kt` | [13-捕获日志](13-捕获日志.md) |
| 记账溯源 | `captureTrace` | `ui/debug/CaptureTraceScreen.kt` | [14-捕获溯源](14-捕获溯源.md) |
| 设置首页 | `settings` | `ui/settings/SettingsScreen.kt` | [15-设置首页](15-设置首页.md) |
| 设置 → 账户入口 | `settings/account` | `ui/settings/SettingsAccountScreen.kt` | [16-设置-账户入口](16-设置-账户入口.md) |
| 设置 → 账单导入 | `settings/import` | `ui/settings/SettingsImportScreen.kt` | [17-设置-账单导入](17-设置-账单导入.md) |
| 设置 → 权限 | `settings/permissions` | `ui/settings/SettingsPermissionScreen.kt` | [18-设置-权限](18-设置-权限.md) |
| 设置 → 监听的 App | `settings/watchedApps` | `ui/settings/SettingsWatchedAppsScreen.kt` | [19-设置-监听App](19-设置-监听App.md) |
| 设置 → 商户归类 | `settings/merchantRules` | `ui/settings/SettingsMerchantRulesScreen.kt` | [20-设置-商户归类](20-设置-商户归类.md) |
| 设置 → 开发者功能 | `settings/developer` | `ui/settings/SettingsDeveloperScreen.kt` | [21-设置-开发者功能](21-设置-开发者功能.md) |
| 设置 → 关于 | `settings/about` | `ui/settings/SettingsAboutScreen.kt` | [22-设置-关于](22-设置-关于.md) |
| 保活与自启动 | `keepAlive` | `ui/keepalive/KeepAliveScreen.kt` | [23-保活引导](23-保活引导.md) |
| 打赏 | `reward` | `ui/profile/RewardScreen.kt`、`util/AlbumSaver.kt` | [24-打赏](24-打赏.md) |
| 设置 → 帮助 | `settings/help` | `ui/settings/SettingsHelpScreen.kt` | [25-设置-帮助](25-设置-帮助.md) |
| 搜索账单 | `search` | `ui/transactions/TransactionSearchScreen.kt` | [26-搜索](26-搜索.md) |

补充入口：

- 通知点击：`CaptureCenter.ROUTE_PENDING` → `pending`；`ROUTE_REFUND_LINK` → `refundLink`；`ROUTE_EDIT_PREFIX` → `edit/{id}`；`ROUTE_OVERLAY_TEST` 直接弹悬浮确认卡；`ROUTE_IMPORT_FILE_PREFIX` 预解析后进 `import`。
- 快捷方式 / 磁贴：`quickAdd`。
- 冷启动缺权限：`PermissionReminderDialog`，按钮进 `settings/permissions`。
- 个人页仍是「账本 / 账户 / 导入」的业务入口；设置页不再重复这些入口。

## 目录地图

```
ui/
  AppNav.kt                 路由、四 tab 外壳、页面转场、全局 Toast
  eco/                      ECO v5 组件层（页面只应依赖这里 + SettingsKit）
    EcoFoundation.kt        背景、Pane、分隔线、金额、章节头、空态、hero
    EcoControls.kt          按钮、字段、chip、segmented、row、switch、tile
    EcoScaffold.kt          顶栏、底部导航、FAB、Sheet、Dialog、Confirm、Toast
  common/
    SettingsKit.kt          设置类页面骨架 + 设置行
    PeriodControls.kt       周期标签、月/年切换、周期选择 sheet、滚轮
    WheelPicker.kt          月起始日滚轮
    CategoryLogo.kt         分类图标渲染（emoji / 图片 / 内置 glyph）
    CategoryGlyph.kt        glyph 目录与绘制
    Components.kt           旧通用组件（新 UI 尽量不用）
    ColorWheel.kt           分类颜色选择
  theme/Theme.kt            颜色 token、EcoType 字号、MaterialTheme.eco
  home/ transactions/ stats/ profile/
  accounts/ imports/ pending/ refund/ settings/ debug/ keepalive/
```

## 不可破坏的全局规则

1. **390dp 设计栅格**：页面内容左右各 22dp；设置页 `SettingsPagePadding = 22.dp`；底部必须为悬浮底栏留出空间。
2. **edge-to-edge**：Activity 已 `enableEdgeToEdge()`；页面顶栏自己 `statusBarsPadding()`，底部用 `navigationBarsPadding()` 或 `ecoBottomContentPadding()`。不要在页面里再套一层 `Scaffold`。
3. **字体最大 1.25**：`MainActivity` 用 `LocalDensity` 把系统 `fontScale` 限制在 1.25。设计按 1.25 验收；新增固定高度容器必须检查 1.25 下不裁字。
4. **只用 ECO 组件和 token**：颜色取 `MaterialTheme.eco.*`，字号取 `EcoType.*`；不要硬编码十六进制色，不要用原生 M3 `Card` / `Scaffold` / 默认按钮。
5. **四个 tab 的内容互斥替换**：`MainScaffold` 同一时刻只渲染 `tab` 这一页；切 tab 时新页整体上移 10dp / 220ms（`translationY`，单层位移）。不要两页同屏平移或整页 alpha 交叉淡化——每帧重绘两整页会明显掉帧，这是实测帧率最差的写法。
6. **再次点当前 tab 回到顶部**：通过 `reenterTick` → 页面的 `scrollToTopTick` 参数实现；页面要记 `lastReenterTick`、只在 tick 增大时回顶，否则从别的 tab 切回来会复位滚动位置。新增滚动页照此接入。
7. **底部栏与 FAB 是 overlay**：内容在它们下面滚动。滚动内容底部 padding 用 `ecoBottomContentPadding(extra)`；FAB 位置用 `ecoFabBottomOffset()`，不要自己算导航栏高度。
8. **动效克制**：tab 切换 220ms 单页上移、路由转场串行（旧页先淡出、新页再淡入，避免两页同时半透明叠加）、冷启动开屏 logo 淡入淡出（期间预热首屏）；sheet/dialog 自带入场；金额用 `EcoAutoMoney` / `EcoCountUpMoney` 自适应；**金额/柱状图/进度条/首页环每次进入页面重播**（产品口径），页面切换不叠影、不留空白；尊重 `rememberReducedMotion()`（首页翻转）与设置里的「显示动画」开关（`LocalAnimationsEnabled`，关闭后一切入场动效直接定格终值）。
9. **文案短、状态准**：标题/按钮尽量 2–4 字；动态状态（待补、待确认、已退款、未开启）不能省；**真正不可恢复的删除（记录/账户）**保留「删除后不可恢复」，分类删除则说明流水归入「其他」；**页面不预置规则/边界说明**，只在用户触发时提示；导入步骤、权限教程属于功能文案，不要“精简”掉；**任何文字前不加 `·`**，也不用 `·` 分隔字段（用空格、`/` 或中文标点）。
10. **数据未就绪先不画**：首页/统计/个人页在关键 Flow 为 `null` 时直接 `return`，避免先显示 0 再跳数。新增汇总卡沿用该约定。

## 改动工作流（建议 agent 直接照做）

1. 用路由表定位主文件，读对应页面文档。
2. 读 `00` 中列出的、该页用到的共享组件源码（只读用到的）。
3. 用 `grep` 找目标 composable / 常量的所有调用点，确认没有别的页面复用。
4. 改代码；优先改页面私有 composable 和常量，不动共享组件的行为。
5. 构建：
   ```powershell
   $env:JAVA_HOME='D:\Java\jdk-17.0.20.1+1'
   $env:Path="D:\Java\jdk-17.0.20.1+1\bin;$env:Path"
   & 'D:\gradle\gradle-8.10.2\bin\gradle.bat' :app:assembleDebug --console=plain -q
   ```
6. 单测：`:app:testDebugUnitTest`（当前基线 686 passed / 61 suites）。
7. 真机截图核对：设备 `UWW4RCGUKVJFDYWC`，1280×2772 / density 520 / font_scale 1.25；截图放 `tmp/device/`（已 gitignore）。
8. 至少检查：1.25 字体、深色模式、空态、长文案、底部栏遮挡、返回栈。
9. 更新对应页面文档和 `docs/开发日志.md`，再提交。

## 文档维护约定

- 尺寸/字号以代码为准；文档写**常量名和 composable 名**，不写会漂移的行号。
- 页面结构、入口、交互、文案规则变了，必须同步改该页文档；只改颜色值不必逐页改，改 `00` 的 token 表即可。
- 发现文档与代码不一致：以代码为准，并在同一次提交里修正文档；若疑似 bug，单独记录，不要静默“按文档改坏代码”。
- 新增页面：在 `docs/ui/` 增加一篇，套用同一模板，并更新本页路由表。
