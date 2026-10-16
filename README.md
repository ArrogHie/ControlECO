# 我ECO（ ControlECO）

安卓记账应用，目标是逐步实现"自动记账"：自动捕获微信/支付宝/云闪付通知、电商收银台、银行动账短信与银行 App 推送、
支付页文字（无障碍）等交易事件；底部悬浮确认卡与待确认队列双通道确认；商户归类越用越准。
数据全部保存在本机；唯一联网用途是更新检查与更新包下载（只访问 GitHub，不上传任何数据）。

## 开发环境

- JDK 17：`D:\Java\jdk-17.0.20.1+1`（Temurin，未写入系统环境变量，构建时临时注入）
- Android SDK：`D:\AndroidSDK`（cmdline-tools + platform-tools + platforms;android-35 + build-tools;35.0.0）
- Gradle 8.10.2：`D:\gradle\gradle-8.10.2`（项目自带 wrapper，依赖走阿里云镜像，见 `settings.gradle.kts`）

## 构建

```bash
# Git Bash 下
export JAVA_HOME='D:\Java\jdk-17.0.20.1+1'
export PATH="/d/Java/jdk-17.0.20.1+1/bin:$PATH"
"D:/gradle/gradle-8.10.2/bin/gradle.bat" :app:assembleDebug
# 或使用 wrapper（首次会下载 Gradle 发行版，distributionUrl 已指向腾讯镜像）
./gradlew :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

```bash
# 发布包（beta 分发用 debug 密钥签名，可与侧载的 debug 包互相覆盖安装、不清数据）
./gradlew :app:assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

## 真机调试（USB 实时日志）

1. **手机打开开发者选项**：设置 → 关于手机 → 连点"版本号"7 次；华为/荣耀通常是 设置 → 系统和更新 → 开发人员选项（或先在关于手机连点版本号开启）。
2. **打开 USB 调试**：开发者选项里开启"USB 调试"；华为/荣耀若还有"仅充电模式下允许 ADB 调试"，一并开启。
3. **连接**：数据线连接电脑，手机弹窗"允许 USB 调试？"勾选"一律允许"再确定。
4. **电脑端（Git Bash）**：

```bash
ADB="D:/AndroidSDK/platform-tools/adb.exe"
"$ADB" devices                 # 确认设备已连接（状态 device；显示 unauthorized 要回手机重新授权）
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk   # 装机
"$ADB" logcat -c               # 清空旧日志
"$ADB" logcat -v time -s ControlECO          # 实时日志：本应用全部（[Ntf] 通知 / [Page] 页面 / [Sms] 短信 / [Overlay] 悬浮卡 / [learn] 归类学习）
"$ADB" logcat -v time -s ControlECO | grep Page    # 只看页面通道（页面快照/规则判定/悬浮窗原因）
"$ADB" logcat -v time -s ControlECO > controleco.log  # 边看边存文件，方便把样本发给开发会话
```

5. **采样流程**（页面解析器调试）：`logcat -c` 清屏 → 手机上完整走一遍支付 → 终端里 `snapshot pkg=... texts=[...]`
   与 `page matched / page scene` 就是页面文本快照与解析判定，据此在 `capture/page/` 下对应应用的专属解析器里加/改规则
   （流程与日志对照表见 `docs/通知规则维护指南.md` 第五节）。

