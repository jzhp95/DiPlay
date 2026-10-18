# 车机热点管理 Implementation Plan

**Goal:** 默认车载热点，提供开机开启、系统设置跳转、系统热点配置弹窗、手动开启和手动凭据兜底；连接优先使用真实系统配置。

**Architecture:** shared/network 独立实现热点配置数据、反射接口与配置来源选择；common 提供设置组件、开机任务及 Activity/Host 接线。沿用当前 Flyme 弹窗、网络管理器和认证/方控逻辑，不修改 APK 版本与签名。

**Tech Stack:** Kotlin、Android WifiManager/SoftApConfiguration、ECarX Wifi6Ap 反射、SharedPreferences、Robolectric/JUnit。

## Global Constraints

- 保留现有 SDK 下限、中文界面、Flyme 深浅主题、普通/UID 构建、方控与认证流程。
- 默认连接方式 MANUAL，自动开热点默认 false；已有明确连接方式保留。
- 直接设置热点是用户明确要求的能力；不无条件关闭 STA，不把接口返回当成实际配置成功。
- 所有阻塞写入、轮询在后台执行；日志不得包含密码。
- 写入频段、加密、信道必须按实际接口能力处理，不能把忽略参数当成功。
- 连接来源优先系统可读配置，再使用已确认写入的配置，最后才读取原手动凭据；三类存储隔离。
- 完整 CI 运行 AGENTS.md 命令，若 wrapper 权限阻止则记录限制，不声称完成 APK/车机验证。

### Task 1: 配置模型与优先级

Files: 新增 shared/network/CarHotspotProfile.kt、CarHotspotProfiles.kt；新增对应测试。

- [x] 测试名称/密码/加密/频段/信道校验、系统来源优先且不调用手动 loader、所有系统来源失败后手动兜底。
- [x] 定义 `CarHotspotProfile(ssid, passphrase, security, band, channel)`，`CarHotspotProfiles.resolve(context, manual)`；管理配置与原手动配置分开保存。
- [x] 运行针对性 JUnit 验证有效配置与错误路径。

### Task 2: 系统热点控制

Files: 新增 shared/network/CarHotspotController.kt；新增接口适配测试。

- [x] 对照参考 APK 的 `com.ecarx.xui.adaptapi.wifiap.Wifi6Ap` 与 Android get/setSoftApConfiguration、旧 WifiConfiguration 路径。
- [x] 实现 `read()`、`apply(profile)`、`enable()`；优先完整 Android 配置接口，再尝试固件接口，读回核对。
- [x] 校验返回 false、权限异常、配置读回不匹配、WPA3/频段/信道不支持时不报告成功；成功后更新独立已验证配置存储。

### Task 3: 交互与生命周期

Files: 新增 common/CarHotspotSettingsUi.kt；修改 DiPlayActivity、BootReceiver、CarHotspotSetup、CarPlayHostActivity、manifest、中文资源及测试。

- [x] 热点卡片固定五项：开机自动开启（默认关）、系统热点设置、热点信息配置弹窗、开启本机热点、手动保存信息。
- [x] 复用 Flyme 弹窗；配置弹窗支持 SSID、密码、加密、频段、信道及回馈；后台写入并显示读回结果。
- [x] 单独开机后台任务不依赖是否自动打开 CarPlay；默认关时不启动热点。
- [x] Host 获取实际热点凭据并传递到已有 ManualHotspotManager；Activity 校验/状态与 Host 使用同一来源。
- [x] 连接方式保存后更新界面，保持当前会话，下次连接使用；取消不保存。

### Task 4: 验证与交付

- [x] 模型、优先级、反射适配、开机默认与回退、弹窗校验/取消/写入回馈测试。
- [x] 核对首页、手动输入和现有 Wi-Fi/Wi-Fi Direct 仍可使用，方控代码不变。
- [x] 紧凑/宽屏、大字体、深浅主题与 RTL 组件截图。
- [x] 运行完整 CI、语法/资源/敏感文件检查并记录构建环境限制。
- [x] 更新计划结果，说明未实车验证的固件权限和写入能力。不自动提交或推送。

## 实施结果与验证边界

- 设置位于「连接 → 热点设置」卡片，仅车载热点模式显示五个入口。新安装默认车载热点；已有连接方式不强制覆盖。
- 连接方式切换只保存配置、标记需要重连，不断开正在进行的 CarPlay；用户主动下次连接使用新配置。
- 「热点信息配置」保存与连接方式切换不同：用户明确保存时直接配置并开启 AP，可能中断当前热点连接。提供名称、密码、加密、频段、信道，空白信道表示自动。
- 配置来源依次为系统完整读回、此前确认过的配置、原手动信息。草稿不参与连接，系统配置不会覆盖原手动信息。历史确认配置是缓存，固件禁止读取后无法感知外部修改。
- 写入按 Android SoftApConfiguration、旧 WifiConfiguration、参考 APK Wifi6Ap 路径尝试。需要完整读回并核对后才报告成功。失败/取消会尝试恢复原配置及原开启状态，并记录恢复结果。
- 参考 Wifi6Ap 接口本身无法完整读回安全配置和密码，因此需要 Android 读回配合；该路径只支持 WPA2 与自动信道，不会静默忽略 WPA3 或指定信道。未知固件或权限不足时会提示失败，保留手动兜底。
- 开机开启是独立 JobService，默认关闭，不依赖应用开机自启动；连接需要时沿用已开启自动热点选项进行重试。
- 新热点组件、模型、事务及反射后端的 31 项针对性 JUnit/Robolectric 测试通过；Host 实际运行配置构造和校验方法的 2 项隔离测试、原手动凭据弹窗的 5 项隔离回归测试通过，共 38 项功能测试。隔离工具使用仓库真实代码，外围现有服务以测试替身提供，不等同整个工程编译通过。
- 紧凑/宽屏、深浅色及 RTL 的 1.5 倍字体组件截图已生成到 `build/reports/managed-hotspot-ui/`，检查了表单、保存按钮及热点卡片。
- 已执行 AGENTS.md 完整 CI 命令，但 Gradle wrapper 在读取用户目录锁文件时被环境拒绝（Operation not permitted），尚未开始工程编译。未产出 APK，未完成实车验证。
- Kotlin 语法、XML、`git diff --check` 和公开仓库敏感文件检查通过。没有修改方控模块、认证证书或签名脚本；未自动提交或推送。

## 装车验证顺序

1. 开启「诊断」中的详细日志，进入「连接」，确认默认车载热点、开机自动开启默认关闭。
2. 点击「打开系统热点设置」确认跳转；回到应用，填写热点配置，先使用 WPA2、5 GHz、自动信道保存。
3. 确认系统热点实际名称、密码、频段与应用输入一致，并用 iPhone 连接；不填写手动信息，验证 CarPlay 可连接。
4. 修改指定信道或加密方式，检查真实系统是否同步；不支持应提示失败，查看原配置及开启状态是否恢复。
5. 已连接时切换到其他连接方式，确认当前 CarPlay 不断开；主动断开后再次连接，确认采用新方式。
6. 打开开机自动开启，重启车机验证；关闭后重启确认应用不主动开启。系统自身的热点恢复行为由固件控制。
7. 若配置或连接失败，导出详细日志。记录普通版/UID 版、系统热点截图及失败操作。日志包含接口阶段、来源选择与恢复结果，不记录热点密码。
