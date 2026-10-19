# UI 重构方案：eco v5 一比一复刻

> 目标：把现有 Compose UI 全面对齐 `eco_ui_v5_standalone.html`（390×844 原型），
> 同时保留项目内所有既有功能与路由契约。本文记录设计系统、页面映射、保留/新增功能与验收口径。

## 一、范围与硬约束

- **一比一复刻**：颜色、字号、间距、圆角、层级、浅色/深色两套、动效节奏均以原型最终级联值为准。
- **不丢功能**：19 个路由、`LedgerViewModel` 契约、通知/悬浮卡契约、`capture_alert` 通道、深链、
  既有单测全部保留。
- **图标**：优先用项目现成的分类图标体系（`CategoryLogo` / `CategoryGlyph` / 26 个字形 + 自定义
  emoji/图片）；通用线性图标用按原型 ICONS 生成的 `EcoIcons`（60 个，24×24、1.7 描边、圆头）。
- **字体**：系统 `FontFamily.SansSerif`，金额用 tabular-nums；不引入外部字体/网络。
- **深色模式**：浅色 + 深色同时实现，跟随系统。
- **个人页「每月首日」特例**：保留项目原有的滚轮选取（点击弹层 + 长按行内拖动），
  不改成普通字段或底部 sheet。
- **通知中心**：不新建页面，只调整系统通知标题/正文文案。
- **打赏**：使用用户提供的微信/支付宝收款码图片（`drawable-nodpi/reward_wechat.png`、
  `reward_alipay.jpg`）做弹窗。
- **不改行为**：只改 UI 层；数据/VM/解析/捕获逻辑不动。

## 二、设计系统落地

### 2.1 Token（`ui/theme/Theme.kt`）

- `EcoColors`：paper / paperDeep / surface / surface2 / surface3 / ink / ink2 / muted / quiet /
  rule / ruleStrong / track / blue / blueDeep / blue2 / blueHi / blueSoft / blueWash / jade /
  amber / clay / violet / heroA/B/C / isDark 等，浅色与深色两套。
- 兼容旧字段：保留 `AccBookColors`（income/critical/primaryStrong/hairline…），旧页面迁移期间不崩。
- `EcoType`：`TopTitle` 28/W800/-0.064em、`SubTitle` 23/W800、`Micro` 9/W800/.145em、
  `SheetTitle` 20/W800、`HeroAmount`、`MeterMoney`、`TodayAmount`、`AssetAmount`、`CoverMoney`、
  `EditAmount`、`CatTotal`、`CatAmount`、`RowTitle`、`Pill`、`PageLead/PageNote`、`Nav` 等。
- 6 套强调色：`EcoAccent.Tide / Meadow / Iris / Coral / Amber / Sky`（各带 light/dark 蓝族与
  heroA/B/C），由 `AccentThemeStore` 持久化到 `eco_ui/accent_theme`，启动时恢复。
- 分类调色板改为 8 色可区分设计色（任意两色 ΔE ≥ 30，保留 `CategoryColorsTest`）；`ringColors/colorDistance` 等旧逻辑保留。

### 2.2 组件

- `ui/eco/EcoFoundation.kt`：`EcoBackdrop`（纸面 + 两团光晕 + 淡圆环）、`EcoPane`（阴影/描边/顶部玉色
  发丝线）、`EcoHairline`、`EcoRowDivider`、`EcoPressable`、`EcoAutoMoney`（按宽度三档缩字）、
  `EcoSectionHead`、`EcoPageLead/Note`、`EcoEmpty`、`EcoHeroCard`、`EcoHandle`、`EcoProgressBar`、`EcoMicro`。
- `ui/eco/EcoControls.kt`：`EcoIconButton`、`EcoBackButton`、`EcoTextAction`、`EcoPrimaryButton`
  （`#269B8C → #16677C`）、`EcoGhostButton`、`EcoDangerButton`、`EcoChip`、`EcoPill`、`EcoSegmented`、
  `EcoFilterStrip`、`EcoSwitch`、`EcoIconTile`、`EcoCategoryTile`、`EcoRow`、`EcoSwitchRow`、
  `EcoField`、`EcoMoneyField`、`EcoRowValue`、`EcoDot`、`EcoSignedMoney`。
- `ui/eco/EcoScaffold.kt`：`EcoTopBar`（主 72 / 子 64 + 状态栏）、`EcoSettingsPill`、`EcoBottomNav`
  （78 + 手势条）、`EcoFab`（54，呼吸圈）、`EcoSquareFab`、`EcoSheet`（27 顶圆角）、`EcoDialog`、
  `EcoConfirm`、`EcoToast` / `EcoToastHost`。
- `ui/eco/EcoBudgetSheet.kt`：月/日预算底部卡，保存 0 = 清除，保持旧语义。
- `ui/common/EcoIcons.kt`：按原型 ICONS 生成的 60 个 `ImageVector`。

### 2.3 主题壳

- `MainActivity`：`enableEdgeToEdge`（透明状态栏/导航栏），`AccBookTheme(accent = AccentThemeStore.current)`，
  根 `Box` 承载内容。
- `AppNav`：根 `Box` 先铺 `EcoBackdrop`，`NavHost` 透明；底部导航与 FAB 以 overlay 方式压在最上层
  （纸面一直铺到屏幕底），`EcoToastHost` 固定在底部导航上方。
- Tab 状态用 `SaveableStateHolder` 保留；再次点击当前 Tab 仍触发 `reenterTick` 回顶。

## 三、页面映射

| 路由 | 新实现 | 关键点 |
|---|---|---|
| main / 首页 | `ui/home/HomeScreen.kt` | 三视图翻转（预算环 / 分类剩余 / 资产）；150ms 两段式 rotationY；45° 阈值 + 甩动；三圆点；真实 VM 数据；资产趋势由 `homePeriodRows` 按日聚合 |
| 账单 | `ui/transactions/TransactionListScreen.kt` | 结余封面 150（夜色/薄雾/陶土/相册图 + 裁剪）、月/年、筛选、日期分组、跳日期、分页、多选批删、范围删除 |
| 统计 | `ui/stats/StatsScreen.kt` + `StatsCharts.kt` | 结论卡、可交互柱状图、双环分类、预算对比、期间切换/选择 |
| 个人 | `ui/profile/ProfileScreen.kt` | 预算 hero、月/日预算、待确认胶囊、分类卡、常驻「每月首日」滚轮、主题色、打赏二维码、导入/设置/关于 |
| 快速记账 / 编辑 | `ui/transactions/QuickAddScreen.kt` / `EditTransactionScreen.kt` | 自定义键盘、分类/账户/日期/商户/备注、编辑删除 |
| 待确认 / 导入 / 退款 | `ui/pending` / `ui/imports` / `ui/refund` | 全部流程与状态保留 |
| 设置体系 | `ui/settings/*` + `ui/common/SettingsKit.kt` | 设置、权限、监听 App、商户规则、开发者、关于、保活、账户/分类入口、导入设置 |
| 账户 / 分类 | `ui/accounts/*` | 账户 CRUD；分类编辑器在同一滚动 sheet 内加颜色/Emoji/图片/预算 |
| 捕获日志 / 溯源 | `ui/debug/*` | 保留 debug 生命线 |
| 悬浮卡 / 权限提醒 | `capture/overlay/*`、`PermissionReminderDialog.kt` | 底部卡样式；保持无焦点、拖动、60s 超时；修空白点击误关与编辑中超时 |

## 四、保留 / 新增功能清单

**保留（原型没有但项目已有）**
- 分类自定义颜色、Emoji、相册图片与 26 个字形；分类预算金额/百分比口径。
- 封面图平移/缩放/旋转裁剪。
- 快速记账商户/备注/账户/日期；数字键盘规则。
- 批量选择/删除、删本年/全部、日期跳转、分页。
- 货币符号选择、GitHub 链接、版本信息、更新下载/安装门禁。
- 全部设置开关/子页/对话框、通知/无障碍/保活引导。

**新增（原型有）**
- 6 套强调色主题（真实可切换、持久化、全局生效）。
- 结余封面 3 套预设底色 + 相册图。
- 打赏弹窗（微信/支付宝收款码）。
- 首页资产视图（总存款 + 本月已花趋势）。
- 账户总资产卡（若原型要求）。
- 通知中心（按用户确认：**不做**）。
- 演示支付页 / 假更新（不做）。

## 五、验收

- `:app:compileDebugKotlin` 通过。
- `:app:testDebugUnitTest` 全过（现有 577 项；保留 `buildCategoryRings`、`billRowText`、分页等纯函数 API）。
- `:app:assembleDebug` 通过。
- 浅色/深色静态走查：无旧 M3 卡片/Scaffold 视觉残留；无硬编码旧蓝。
- 真机 `UWW4RCGUKVJFDYWC`（1280×2772 / density 520）已走查：首页三视图、账单、统计、
  个人、设置、每月首日滚轮、底部导航、FAB、路由转场；截图 `tmp/device/*.png`（gitignore）。

### 5.1 动效验收口径（真机回归补充）

- 切 Tab / 进子页：新页 alpha 0→1、下移 24px→0，380ms；旧页立即移除，不保留可点击层。
- FAB：只有外圈缩放 .95→1.09、alpha .20→.50，4.2s 呼吸；本体不位移、不缩放。
- 底部导航：选中胶囊渐变 + 上浮 2dp + 3dp 玉色点，220ms。
- 金额：940ms easeOutQuart 数字滚动；进度条 900ms；统计柱 820ms 依次生长。
- Hero 柔光 7s、今日星 3.5s、待确认摇摆 4.5s、Dialog 280ms modalIn。
- 每月首日：拖拽滚轮不得被 sheet/外层滚动抢手势，松手吸附后落库。

## 六、提交记录

- `63ee6da` feat(ui): eco v5 design system + home + bills
- `9b80bd0` feat(ui): 全量对齐 eco v5 原型（其余页面 + 设置 + 悬浮卡 + 文档）
- `6313b95` style(ui): 自定义颜色弹窗改用 eco 对话框与字段
- 本次真机回归（动效/滚轮/账单细节，见 `docs/开发日志.md` 2026-10-19 条目）
