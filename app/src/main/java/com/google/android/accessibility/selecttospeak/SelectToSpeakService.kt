package com.google.android.accessibility.selecttospeak

import com.controleco.app.capture.AccBookAccessibilityService

/**
 * 页面采集服务的**注册入口组件**（只做继承，不含任何采集逻辑）。
 *
 * 为什么单独开一个入口类：部分应用（含微信 8.0.52+）只对系统读屏类服务暴露页面节点树，
 * 判据是**无障碍组件的类名**是否属于已知的系统读屏服务（`…selecttospeak…` / `…talkback…`）。
 * 只开启本应用自己的采集服务时，这些应用读到的是 0×0 空根
 * （日志 `collect empty pkg=com.tencent.mm`，见 docs/自动记账捕获机制.md §6.1 注）。
 *
 * 把**注册组件的类名**放到与系统「随选朗读」一致的位置，是为了验证"页面节点可见性是否
 * 与无障碍组件身份有关"这一**兼容性**问题。它不带来任何额外能力：
 * - 不改变 applicationId，不改任何业务包名，不申请系统签名/系统 UID/隐藏 API；
 * - 系统无障碍设置里显示的名称、图标与说明仍是 ControlECO 自己
 *   （见 `res/values/strings.xml` 的 `a11y_service_label` / `a11y_service_description`），
 *   不冒称系统或 Google 官方服务；
 * - 事件接收、节点读取、页面快照与后续入账链路**全部**由父类
 *   [AccBookAccessibilityService] 承担，这里不重复实现 `onAccessibilityEvent()`，
 *   也不重复创建采集中心；APK 内只有这一套实际生效的页面采集服务。
 *
 * 实测结论、可用范围与失效场景记录在 docs/无障碍页面捕获兼容性.md。
 */
class SelectToSpeakService : AccBookAccessibilityService()
