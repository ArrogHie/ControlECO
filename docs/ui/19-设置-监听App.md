# 19 · 设置 · 监听的 App

按分组开关“哪些 App 的通知/页面通道要被采集”。搜索框 + 全局全开/全关 + 分组卡（整组开关 + 每应用开关）。

源码：`app/src/main/java/com/controleco/app/ui/settings/SettingsWatchedAppsScreen.kt`（约 299 行）
数据：`app/src/main/java/com/controleco/app/capture/CaptureAppCatalog.kt`

---

## 1. 路由与入口

- 路由：`settings/watchedApps`。
- 进入：设置首页 → `01 自动记账` →「监听的 App」。
- 离开：返回。
- 本页不申请系统权限；只写监听范围（SharedPreferences），即时生效。

---

## 2. 文件地图

| 符号 | 作用 |
| --- | --- |
| `SettingsWatchedAppsScreen` | 搜索、分组渲染、全开/全关 |
| `AppRow` | 应用行（首字色块 + 名称 + `EcoSwitch`） |
| `SearchBox` | 40dp 搜索框 + 清除 |
| `InlineAction` | 行内文字动作 |
| `groupHint` | 每组说明 |
| `CaptureAppCatalog` | 目录、`watched`、`setWatched`、`setGroupWatched` |
| `AppGroup` | `PAY 支付` / `BANK 银行` / `SHOP 电商 / 外卖` |

---

## 3. 页面骨架

```
SettingsPage(micro="WATCHED APPS", title="监听的 App", onBack)
  SearchBox("搜索应用名或包名")
  Spacer 8
  Row("N / M 已监听" + "全部开启" + "全部关闭")
  if (filtered.isEmpty())
    SettingsPane { EcoEmpty(...) }
  else
    for (group in AppGroup.entries) {
      if (groupApps.isEmpty()) skip
      SettingsSection("%02d", group.title, "$onCount / ${groupApps.size}")
      SettingsPane
        Row("整组" + "$onCount/$size 已监听" + groupHint + InlineAction("全部关闭/全部开启"))
        for app: SettingsDivider + AppRow(label, checked, onCheckedChange)
    }
```

---

## 4. 关键尺寸 / 字号

| 元素 | 规格 |
| --- | --- |
| 搜索框 | 高 **40dp**，圆角 11dp，`surface2` + `rule` 描边，左右 12dp |
| 搜索文字 | `EcoType.Input`，光标 `blue`；清除 14dp |
| 计数/动作行 | `SectionAside`；InlineAction 10/800 `blueDeep`（全部关闭用 `muted`） |
| 分组章节 | `SettingsSection("%02d", group.title, "on/size")`，编号按**非空组**重新编 |
| 整组行 | 左右 16/10，上下 10；标题 11.5sp；说明 8.5sp |
| 应用行 | `heightIn(min=48dp)`，左右 16、上下 10 |
| 首字色块 | 32dp，圆角 10dp，底色 `tint` 12%/22%，首字 12sp/700 |
| 应用名 | 11.5sp，单行省略 |
| 开关 | `EcoSwitch` |
| 分隔线 | `SettingsDivider`（16dp 缩进） |

---

## 5. 状态与数据流

### 状态

- `tick`：本地自增，用来重新读取 `CaptureAppCatalog.watched(context)`。
- `watched: Set<String>`：已监听包名。
- `query`：`rememberSaveable`，保存搜索词。
- `apps = CaptureAppCatalog.apps`：静态目录。
- `filtered`：按 `label` 或 `packageName` 模糊匹配（忽略大小写）。
- `watchedInCatalog = apps.count { packageName in watched }`。

### 写回

- 单应用：`CaptureAppCatalog.setWatched(context, packageName, on)` → `tick++`。
- 整组：`setGroupWatched(context, group, !allOn)` → `tick++`。
- 全部：遍历 `AppGroup.entries` 调 `setGroupWatched` → `tick++`。
- 写盘即时生效，`tick` 只刷新本页快照。

### 分组

- 只渲染 `filtered` 中非空的组；章节编号按渲染顺序 `%02d` 重新生成。
- `allOn = onCount == groupApps.size`；整组动作文案随之为 `全部关闭` / `全部开启`。

---

## 6. 交互

| 操作 | 结果 |
| --- | --- |
| 输入搜索 | 过滤应用；分组/章节随之变化 |
| 清除搜索 | 恢复全部 |
| 全部开启 / 全部关闭 | 作用于**整个目录**（不受搜索影响） |
| 整组开关 | 只作用于该组当前显示的应用 |
| 单应用开关 | 写回并刷新 |
| 返回 | 设置首页 |

---

## 7. 文案

- 顶栏：`WATCHED APPS` / `监听的 App`。
- 搜索：`搜索应用名或包名`、`清除搜索`。
- 计数：`N / M 已监听`；组内 `N / M 已监听`。
- 动作：`全部开启`、`全部关闭`、整组 `全部开启`/`全部关闭`。
- 分组：`支付`、`银行`、`电商 / 外卖`。
- 说明：`支付三包的通知与页面`、`动账推送；短信通道单独控制`、`收银台与订单页面的捕获`。
- 空态：`目录里还没有应用` / `更新应用后目录会自动补齐`；`没有匹配的应用` / `换个应用名或包名试试`。

---

## 8. 想改 X 看 Y

| 想改 | 去哪里 |
| --- | --- |
| 搜索框 | `SearchBox` |
| 全局动作 | 顶部 `InlineAction` + `setAll` |
| 分组/顺序 | `AppGroup.entries` |
| 组说明 | `groupHint` |
| 应用行 | `AppRow` |
| 目录/默认开关 | `CaptureAppCatalog.apps`（`CatalogApp.defaultOn`） |
| 写回 | `setWatched` / `setGroupWatched` |

---

## 9. 坑与验收

**坑**

1. 顶部「全部开启/关闭」作用于**整个目录**，不是当前搜索结果；文案没有“筛选内”的歧义，但实现要清楚。
2. 整组开关只作用于 `groupApps`（当前过滤结果），搜索时会只改可见应用。
3. 章节编号按**非空组**重新数，搜索后可能从 `01` 变 `02`；不要写死组编号。
4. `tick` 只刷新快照；不要用它触发数据层重载。
5. 本页只管“包名可枚举”的通知/页面通道；银行短信、未知银行 App 的推送由各自签名闸门决定，不受本页影响。
6. 目录外来源不在此页；不要把它描述成“所有捕获开关”。
7. `rememberSaveable` 的搜索词在进程重建后保留；不要改成普通 `remember`。
8. 首字色块颜色按名称稳定取色（`categoryColor`），不是随机。

**验收**

- [ ] 搜索按名称/包名过滤，清除恢复。
- [ ] 分组只显示有结果的组，编号连续。
- [ ] 单应用/整组/全局开关都即时写回，重进页面保持。
- [ ] 全开/全关后计数与每行开关一致。
- [ ] 两种空态文案正确。
- [ ] 1.25 字体下应用名、组说明、动作不挤。
