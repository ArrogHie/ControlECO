# 阶段四交接文档：UI 美化（给下一个开发会话/Agent）

> **历史资料**：保留原路径供追溯。下文的“当前”“定稿”和待办均指记录当时，可能已被后续实现替代；现行行为请从[文档索引](README.md)进入，自动记账与金额计算以当前专题为准。

> **✅ 本阶段已于 2026-10-07 完成**（v0.4.0，commits 3287d00…dbc0f3f）。本文档保留为设计决策与现状记录；完成情况见文末「八、完成情况」，后续开发以 `docs/开发日志.md` 最新条目为准。
>
> 本文档是阶段四的开工说明。接手前先读：`docs/开发方案.md`（总体方案）、`docs/开发日志.md`（全部踩坑记录）。
> **阶段范围变更**：原阶段四（银行短信通道）因样本少难以测试**暂时跳过**，UI 美化提前；短信通道顺延（见第五节）。

---

## 一、项目现状快照（截至 2026-10-06，commit bd2ead5 之后）

**功能已全部可用（v0.3.0）**：
- 阶段一：手动记账内核 + 微信(xlsx)/支付宝(CSV)账单导入
- 阶段二：通知监听通道全链路（捕获→解析→待确认→入账），真实样本全部识别
- 阶段三：支付宝无障碍页面捕获 + 悬浮确认窗（与横幅通知双通道提醒、双向冲突处理）+ 无金额退款关联流程 + 权限引导
- 保活引导（分品牌）、捕获日志（通知/页面筛选）、debug 工具（全量记录、20s 延迟弹窗/通知测试）

**测试**：64 个单元测试全过（`gradlew :app:testDebugUnitTest`），UI 层无测试覆盖。

---

## 二、UI 现状盘点（接手必读）

**架构**：单 Activity（MainActivity，singleTask）+ Compose + Navigation；底部导航三 Tab（流水/统计/设置）+ NavHost 二级路由。数据一律经 `LedgerViewModel`（全局单例 VM）。

**主题**：`ui/theme/Theme.kt` 仅 10 行——Material 3 默认 light/dark 配色，**无任何自定义 Color/Typography/Shapes**。全项目设计 token 零定制，这是本阶段的核心工作起点。

**屏幕清单**（`app/src/main/java/com/accbook/app/ui/`）：

| 文件 | 职责 | 当前样式备注 |
|---|---|---|
| `AppNav.kt` | 路由 + 底部导航 + 悬浮卡/通知跳转事件（LaunchedEffect navEvent） | 路由名勿改（通知/悬浮卡用字符串路由） |
| `transactions/TransactionListScreen.kt` | 月度流水，按日分组，金额红绿区分收支 | 收入色硬编码 `Color(0xFF2E7D32)` |
| `transactions/EditTransactionScreen.kt` | 记一笔/编辑（分类选择、日期、账户） | 表单式，最常用页面 |
| `stats/StatsScreen.kt` | 月度收支汇总 + 分类占比 | 无图表库，纯文本/行 |
| `accounts/AccountsScreen.kt`、`accounts/CategoriesScreen.kt` | 账户/分类 CRUD | 简单列表 + 对话框 |
| `imports/ImportScreen.kt` | 微信/支付宝账单导入（解析预览→确认） | 收入色同上硬编码 |
| `pending/PendingScreen.kt` | 待确认队列（确认对话框：分类 FilterChip + 账户下拉） | 核心交互页，交互逻辑勿动 |
| `refund/RefundLinkScreen.kt` | 退款关联（选原支出→金额可改→确认） | 新页面 |
| `debug/CaptureLogScreen.kt` | 捕获日志（全部/通知/页面筛选 chips，点击复制） | debug 属性页 |
| `settings/SettingsScreen.kt` | 设置（大量行/开关/可展开教程/权限按钮） | 行数最多的页面 |
| `keepalive/KeepAliveScreen.kt` | 保活引导（状态卡+电池白名单+分品牌步骤） | 文案密集 |
| `common/Components.kt` | 共享组件：MonthHeader/SummaryRow/DropdownSelector/DatePickerButton + formatTimestamp | 美化的杠杆点：改这里全局生效 |
| **悬浮确认窗**（`capture/FloatingConfirmWindow.kt`） | **经典 View 实现**（非 Compose），深色圆角卡+循环选择器 | 支付保护拦截分析后的产物，样式独立于 Compose 主题；美化可选、优先级低，动它需谨慎（无焦点窗口约束见文件头注释） |

**已知硬编码样式**：收入绿色 `Color(0xFF2E7D32)` 出现在 3 处（流水/待确认/导入）——应收敛为语义色 token（如 `IncomeColor`）；金额符号 `-`/`+` 前缀逻辑分散在各 Row。

**图标**：启动器图标仍是 AndroidStudio 默认 mipmap（无品牌设计）。

---

## 三、设计目标与硬约束

**目标**（按优先级）：
1. 设计 token 体系：`Theme.kt` 建立语义色（收入/支出/退款、各屏统一）、Typography、Shapes、间距规范；消费掉全部硬编码色。
2. 核心动线优先：流水列表 → 记一笔/编辑 → 待确认确认对话框——这三个是日常使用频率最高的界面。
3. 信息密度与层级：金额是第一视觉主体（记账 App 惯例：等宽数字感、收支色彩语义、按日分组卡片化）。
4. 统一组件化：设置页/保活页的行、开关、教程折叠等重复模式抽组件。

**硬约束（违反即返工）**：
- **不改行为**：所有路由名、点击流、状态流（VM 接口）、通知/悬浮卡/待确认的交互契约保持不变；VM 只读不改。
- **不新增权限**、不引入网络图片/字体下载（本应用无网络权限是隐私承诺，字体用系统/内置资源）。
- **不砍 debug 功能**：捕获日志、20s 延迟测试、教程折叠等是排查生命线。
- **深色模式必须同时可用**（当前 M3 默认深色正常，自定义后两套都要验）。
- **不依赖新库做图表**：如需统计图，优先自绘 Canvas；引入第三方库需在交接回复中说明理由（minSdk 26 / Compose BOM 2024.12.01 约束内）。
- 改动后 `gradlew :app:testDebugUnitTest` 全过 + `:app:assembleDebug` 可装机。
- 隐私红线不动：真实账单/样本不入库（test/、docs/book 已 gitignore）。

---

## 四、已知 UI 相关踩坑（来自开发日志）

- `stickyHeader` 在 foundation 1.7+ 是 LazyListScope 成员函数，**不要 import**。
- Kotlin 2.0 不允许 `?: run { continue }`——lambda 内跳转用 `if (x == null) { ...; continue }`。
- 大量 M3 API 带 `@ExperimentalMaterial3Api`（TopAppBar、FilterChip 等），函数上 `@OptIn`。
- `Icons.Filled.ArrowBack` 已废弃 → 用 `Icons.AutoMirrored.Filled.ArrowBack`（现存代码尚未迁移，可顺手做）。
- Toast/对话框等 UI 调用必须在主线程（阶段三踩过 IO 线程 Toast 闪退）。
- 通知渠道 id=`capture_alert`（横幅），`CaptureCenter.CHANNEL_CAPTURE` 公开常量，设置页"横幅设置"按钮直达。

---

## 五、建议实施步骤

1. **主题先行**：`Theme.kt` 定义完整 colorScheme（含语义扩展色：`ColorScheme` 可用 `MaterialTheme.colorScheme.copy()` + 自定义 CompositionLocal，或扩展字段）+ Typography + Shapes；全局替换 3 处硬编码收入色；ArrowBack 迁移 AutoMirrored。
2. **核心动线**：流水列表（日分组卡片、金额排版）→ 记一笔/编辑 → 待确认确认对话框。
3. **统计页**：分类占比可视化（自绘条形即可）。
4. **次级页**：设置/保活/导入/日志的组件化与留白。
5. **悬浮确认窗微调**（可选）：对齐主题色（经典 View 手工配色，注意深浅色可读性——它浮在任意应用上，深色底+白字是安全解）。
6. **启动器图标**：可选，需自适应图标（anydpi-v26 + 传统 fallback）。
7. 每完成一个单元：装机自验（浅色/深色）→ 提交（`feat: ui ...`）→ 更新开发日志。

**验收标准**：
1. 无硬编码颜色残留（`grep "Color(0x" ui/` 仅剩主题文件）；
2. 深色模式全屏走查无对比度问题；
3. 全部既有交互可用（重点回归：记一笔→保存、待确认→确认/丢弃、退款关联全流程、导入全流程）；
4. 64 个单测全过、装机无崩溃；
5. 各屏提交独立、开发日志有记录。

## 六、构建环境（新会话必读）

- JDK 17：`D:\Java\jdk-17.0.20.1+1`；Gradle：`D:\gradle\gradle-8.10.2\bin\gradle.bat`；SDK：`D:\AndroidSDK`
- Git Bash：PATH 用 `/d/Java/...`，JAVA_HOME 给 .bat 用 Windows 风格
- 常用命令：
  ```bash
  export JAVA_HOME='D:\Java\jdk-17.0.20.1+1'
  export PATH="/d/Java/jdk-17.0.20.1+1/bin:$PATH"
  "D:/gradle/gradle-8.10.2/bin/gradle.bat" :app:assembleDebug :app:testDebugUnitTest
  "D:/AndroidSDK/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
  "D:/AndroidSDK/platform-tools/adb.exe" logcat -s AccBook
  ```
- 依赖走阿里云镜像；真机调试见 README「真机调试」节
- Git 规范：feat/fix/ui/docs 前缀 + 中文正文；**真实账单/隐私样本绝不入库**

---

## 七、顺延事项：银行短信通道（原阶段四）

- 现状：样本极少（0 条真实短信），规则引擎无法测试，刻意跳过。
- 接口已预留：`TxSource` 枚举、CaptureCenter 双通道先例（`onPageCaptured` 模式可直接复制为 `onSmsCaptured`）、捕获日志 source 列。
- 届时开工条件：积累 10+ 条真实银行动账短信样本（各家格式差异大）→ 按通知规则同一套方法论建 `SmsRules`。

---

## 八、完成情况（2026-10-07 补记）

**设计方向升级**：用户提供的设计稿将本应用定位为"极简预算控制 App"（首页=剩余可用预算的 HUD 视图），因此本阶段不止是"给现有页换色"，还新增了预算概念与快速记账页（`quickAdd` 路由、`Budget` 数据层、VM additive 扩展）。

**已完成**（对照第三节验收标准）：
1. ✅ 设计 token 体系：`Theme.kt` 语义色/Typography/Shapes；`grep "Color(0x" ui/` 仅剩主题文件；收入绿等硬编码色全部收敛。
2. ✅ 核心动线：首页预算 HUD（金币 Canvas+剩余金额计数动画+三态）→ 快速记账（金额优先+数字键盘）→ 账单页/编辑页。
3. ✅ 信息层级：金额第一视觉主体（tnum 等宽字形、千分位分组）；统计页按"结论>趋势>分类细节"重构（自绘柱状图，未引库）。
4. ✅ 组件化：BudgetEditDialog/CategoryBadge/SettingRow(subtitle)；设置/保活等次级屏统一弱化文字 token。
5. ✅ 70 单测全过（64 既有 + BudgetTest 6 新增）、`assembleDebug` 出包成功（v0.4.0/versionCode 3）。

**遗留（下阶段/装机时处理）**：
- 真机深浅色走查（本会话无设备连接，见开发日志 2026-10-07 条目清单）。
- 悬浮确认窗（经典 View）未对齐新主题色——可选项，风险>收益，明确搁置。
- 启动器图标仍为默认 mipmap——可选项未做。
- 预算状态跨天/跨月不主动刷新（重开应用对齐）；如需精确可加日界 ticker。
