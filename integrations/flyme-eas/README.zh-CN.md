# DiPlay 的 Flyme EAS 方控接入

## 适用范围与验证状态

本模块以 `carplay-wheel-replica-demo` 中实车成功的 **system UID + Flyme EAS** 路径为基线，默认媒体源 **36**。用户已确认基线 demo 能接管焦点、收到上一曲/下一曲，且车机原音乐不响应。这里是该协议的 DiPlay 集成，**集成版本尚未实车验证**，不能把 demo 的测试结果当作本 APK 的测试结果。

本分支默认输出普通包便于日常调试；通过 `-PflymeSystem=true` 构建 Flyme 系统包（正式包名 `com.shihab.diplay.flyme`，测试包名 `com.shihab.diplay.flyme.test`，声明 system UID 并启用 EAS）。声明系统身份不等于实际获得 system UID：还需要与车机平台相符的签名、成功加入共享 UID、OEM 媒体权限和对应 MediaCenter 服务。只在 Manifest 写 `android.uid.system` 不足以通过安装和运行授权。

本次不添加 UI 设置，不移植 MediaBridge 的自动拉起或持续抢焦点策略，不改 iAP2/AirPlay 协议实现。支持播放、暂停、上一曲、下一曲；音量仍归系统，Siri 沿用 DiPlay 原有路径。demo 中仅显示的快进/快退/退出等事件没有映射为不存在的 iPhone 指令，也不会伪装成处理成功。

## 独立维护边界

```
Flyme MediaCenter
  ↕ Binder / IMusicClient
:flyme-eas（integrations/flyme-eas）
  ↕ Host.ready / Host.playback / Host.mediaButton
common/FlymeWheelIntegration.kt
  → CarPlayMediaKeys.onExternalMediaKey(..., "flyme-eas")
  → CarPlayController.sendMediaButton
  → AirPlaySession.sendMedia → iPhone
```

`:flyme-eas` 只依赖 Android SDK，没有引用 DiPlay 类，没有厂商 SDK/DEX，没有独立服务，没有私钥或认证资产。集成层不会再注册一套 Android MediaSession 或 AudioFocus；复用 DiPlay 原来的媒体会话及音频焦点机制。

| 文件 | 责任 |
| --- | --- |
| `EasAdapter.java` | 已验证的 Binder 描述符、注册/配置/焦点/注销事务、回调及超时 |
| `EasProtocol.java` | OTA 回调号 → 媒体语义；保持 demo 的映射 |
| `FlymeWheelBridge.java` | 运行资格检查、会话就绪、有限焦点申请、真实元数据、诊断 |
| `SessionGate.java` | 生命周期、已取得焦点后不自动重抢、命令资格 |
| `KeyPairs.java` | 焦点未知时对 150ms 内的 EAS/硬件重复事件逐个配对 |
| `PlaybackSnapshot.java` | 与业务代码无关的歌曲/进度/播放状态 |
| `common/.../FlymeWheelIntegration.kt` | 唯一知道 DiPlay Controller、蓝牙音频设置及后台会话的适配器 |
| `mobile/src/main/AndroidManifest.xml` | Flyme 应用固定声明 system UID 与 EAS 元数据；保留 debug/release overlays |

将来合并上游时，优先保留整个 `integrations/flyme-eas` 目录和新增的 `FlymeWheelIntegration.kt`。现有文件的改动集中在以下接点：

1. `settings.gradle.kts`：声明 `:flyme-eas` 并指向独立目录。
2. `common/build.gradle.kts`：增加一条模块依赖。
3. `mobile/build.gradle.kts`：固定 Flyme 应用 ID，debug 追加 `.test`；`mobile/src/main/AndroidManifest.xml` 固定声明 system UID／EAS，不再用生成脚本或构建开关切换普通包。
4. `CarPlayMediaKeys.attach/detach/onNowPlayingChanged`：分别挂接会话、释放、元数据更新；不抢占已有 listener。
5. `CarPlayMediaKeys.send/onExternalMediaKey`：给 EAS 来源命名，对其他硬件入口做去重；明确的 MediaController 播放/暂停/切歌指令继续工作。
6. `CarPlayHostActivity` 的两处前台媒体按键，以及 `WheelKeyService.sendMedia`：复用同一硬件去重入口。
7. `DiPlayActivity` 既有诊断报告中追加 `FlymeWheelIntegration.report()`。
8. CI 执行 `:flyme-eas:testDebugUnitTest`，并合并 debug／release Manifest；`scripts/check_flyme_manifest.py` 验证两个包名、共享 UID、EAS 元数据和 Provider authority。

不要将模块内部协议搬回 Activity，不要在 shared 层硬编码 Flyme，系统 UID 仅放在 mobile 应用 Manifest，不放入通用 common／shared 库 Manifest。上游若重构媒体生命周期，只需要重新挂接第 4～7 项。升级厂商 OTA 时，优先修改/验证 `EasAdapter` 的 ABI。

## 生命周期与焦点

- 每个 CarPlayController 对应一个 Bridge，重复 attach 同一个 Controller 不重新注册。替换 Controller 时先使旧 Bridge 失效并排队注销。
- 等待 `CarPlayBackgroundSession.active`、后台会话中的 Controller 与本 Controller 一致、Controller 未关闭，才注册 EAS。仅打开应用、不连接 iPhone，不抢媒体源。
- 蓝牙直出音频模式跳过 EAS，避免与车机蓝牙 AVRCP 争抢按键。
- 资格检查要求 EAS 元数据启用、appId 1000、与 `android` 包签名匹配、OEM 媒体权限及 MediaCenter 包存在。失败时不绑定、不抢焦点；原因写入诊断。
- 按 demo ABI 注册客户端，声明源列表及当前源 36，发布功能 `[0,2,3]`、真实 iPhone 元数据，发起 requestPlay。
- 最多申请四次。每次 worker 请求结束后才安排下一次，间隔 800ms；执行前以及执行前的焦点查询结束后均再次检查资格。不会积压多个同时等待的申请。
- requestPlay 返回 true 只代表受理。通过事务 42 查询当前焦点包。首次查询确认本包后，后续申请全部停止；用户切换其他源后，不自动抢回。空焦点当作未知。
- EAS 回调在焦点查询未知时仍可转发；两路重复按键在 150ms 内按语义逐个配对。同一路连续按键不会被 debounce。150ms 是软件策略，须实车确认；相隔很近的不同来源主动操作可能被视为一对。
- 已查询确认 EAS 拥有焦点时，其他硬件媒体路径被抑制；Android 通知栏等明确的媒体控制仍然转发。
- iPhone 暂停只更新播放状态，不注销媒体源；下一曲/播放仍可接收。不会将暂停伪装成正在播放。
- Activity 进入后台、但 CarPlay 会话还活着时保留注册。会话断开、Controller 关闭或 detach 后，立即拒绝旧事件/重试，随后解绑、事务 4 注销。
- Binder 死亡或超时不自动重连。重新建立 CarPlay Controller 后才重建接入，避免退出后又抢回焦点。

同步 Binder 调用无法由 Android 客户端强制取消。4 秒 watchdog 会使连接代际失效并解绑；如果远端在关闭后才返回注册 token，会对那个晚到 token 单独注销。如果远端永久卡死，远端注销完成不能保证；本地不会再发送方控或申请焦点。诊断中的 `unregister returned=true` 才是远端完成注销的证据。

## 协议与 iPhone 命令

| IMusicClient 回调 | 语义 | DiPlay/iPhone 媒体索引 |
| --- | --- | --- |
| 1 / 30 | 播放 / 列表播放 | 1 |
| 2 / 31 | 暂停 / 列表暂停 | 2 |
| 3 | 下一曲 | 4 |
| 4 | 上一曲 | 5 |

回调确认表示命令被本地接收排队，不表示 iPhone 已执行。最终以 `WheelKeyLog` 中 `sent=true`、iPhone 实际切歌和 NowPlaying 回传相互核对；`sent=true` 本身也只是 Controller 接受发送。

厂商事务保持基线：Framework 1/4 获取 mediacenter 主 Binder；支持服务事务 1 兼容回退；MediaCenter 19 注册（明确返回 null 才回退 1）、7 源列表、8 当前源、30 功能声明、6 元数据、10 进度、5 requestPlay、42 焦点查询、4 注销。`IEASFrameworkSuppportService` 中三个 p 是真实 ABI，不能纠正拼写。

`IMusicClient #36` 仍按该 OTA 的 Bundle presence 处理，不当作 MediaBridge 的媒体列表，不反序列化未知 Parcelable。源 6、其他固件 ABI、厂商包白名单及 iPhone 快进/快退扩展不属于本次已验证路径。

## 构建及签名

本分支默认输出普通包，支持通过 `-PflymeSystem=true` 切换 Flyme 车机系统包。当前基本版本为 0.2.26／45；debug 显示为 0.2.26。

| 类型 | applicationId | 说明 |
| --- | --- | --- |
| release | `com.shihab.diplay.flyme` | 使用与目标平台匹配的 release 签名 |
| debug | `com.shihab.diplay.flyme.test` | Gradle 调试签名产物须经平台重签名后安装 |

两个类型都声明 `android.uid.system` 和 `com.shilapi.flymeeas.ENABLED=true`；这不替代车机上的签名、实际 UID 与 OEM 权限检查。

macOS／Linux 在仓库根目录执行：

```sh
./integrations/flyme-eas/build-apks.sh
```

Windows 10/11（PowerShell 5.1 或 7）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\integrations\flyme-eas\build-apks.ps1
```

脚本只构建并签名一个 Flyme 测试包。输出到已忽略的 `build/car-test-apks/`：

- `Apple-CarPlay-flyme-test-YYYYMMDD-HHMMSS.apk`
- `SHA256SUMS-YYYYMMDD-HHMMSS.txt`
- 本轮签名、badging 与 Manifest 校验文本。

历史 APK 不删除。脚本保留 16KB ZIP 对齐、平台证书指纹与包名／Manifest 检查。

环境要求：JDK 25、Android SDK Platform 37、Build Tools 36.1.0、完整 NDK 28.2.13676358。Windows 脚本优先读取 ANDROID_HOME，其次 ANDROID_SDK_ROOT，最后 LOCALAPPDATA/Android/Sdk。

平台签名目录默认为仓库根 `AOSP-platform-signing/`，可用 `DIPLAY_PLATFORM_SIGNING_DIR` 覆盖；读取 `platform.pk8` 和 `platform.x509.pem`。这些文件不入库、不进入 APK。

认证资产优先使用 `DIPLAY_AUTH_ASSETS_DIR` 指定的资源根，其次仓库根 `offline-mfi/`。只暂存 `offline-mfi/identity.pk8` 和 `offline-mfi/certificate.p7b`；存在本地输入时执行 assembleStandaloneDebug，无输入时生成无法独立完成认证的源码测试包。认证材料与平台签名是两套不同用途的输入。

直接构建与检查：

```sh
./gradlew :mobile:assembleStandaloneDebug
./gradlew :mobile:processDebugMainManifest :mobile:processReleaseMainManifest
python3 scripts/check_flyme_manifest.py
```

第一条需本地认证资产；没有认证资产的源码构建使用 :mobile:assembleDebug。默认 Gradle debug APK 仍需目标平台签名，使用上面的打包脚本处理。

### 从旧测试包迁移

旧包 `com.shihab.diplay.flyme.hudtest` 与新包 `com.shihab.diplay.flyme.test` 是两个 Android 应用身份，不是覆盖更新。不会自动继承旧包私有设置；先保留旧安装和报告，再在新包按需设置。不会自动卸载旧应用或删除其数据，正式包名不变。

### 安装后的身份与行为检查

```sh
adb -s SERIAL shell dumpsys package com.shihab.diplay.flyme.test
adb -s SERIAL logcat -d -s FlymeWheel CarPlayMediaKeys
```

检查 appId=1000、platform=true、OEM permission granted。然后集中验证：仅打开应用不抢焦点；连接后媒体信息和方控有效；暂停不伪装播放；后台保留方控；切到原音乐不抢回；断开后旧回调无权操作；蓝牙音频直出模式仍跳过 EAS。详细步骤见 [车机连接与方控调试](DEBUGGING.zh-CN.md)。

构建检查不能替代目标车机验收。Windows 脚本须在实际 PowerShell／Windows 环境验证，不能用 shell 结果冒充。

## 媒体卡片兼容修复（2026-10-17，待实车验证）

车测确认焦点和方控可用，但连接 iPhone 播放音乐时，车机没有显示歌曲信息。
这版只修复媒体数据发布路径，保留源类型 36、现有注册方式、有限焦点申请与停止注销流程。

- 每次发布使用固定的数据快照，避免车机异步读取时把两首歌的数据混在一起。
- 提供随曲目变化的 UUID、应用打开入口、完整的类型正确的可选字段。
- 将匹配当前歌曲的 iPhone 封面经只读 Provider 交给厂商进程读取。限制原始数据 4 MiB、尺寸 8192，缩小至最长边不超过 1024，仅保留 8 张 PNG。Provider 只开放固定格式的图片文件名，不开放其他应用文件和写入。
- 确认 EAS 当前焦点属于本包后，立即补发元数据，并在 500/1500ms 后再次补发；拥有焦点期间每 5 秒刷新保留信息。只更新信息，不增加 requestPlay，不在用户切换其他媒体源后抢回。
- 封面异步解码完成后，沿用既有监听接点触发一次 Flyme 信息更新。
- 停止后，旧回调返回空歌曲信息、暂停状态和空图片/入口，再沿用原有注销逻辑。没有在注销前新增同步远程清卡片事务，避免拖延已验证的释放路径。远端缓存是否清除仍由系统处理。
- 报告新增 `eas_metadata` 状态和每次 `metadata update accepted=...` 的原因、标题、状态、时长、进度；发布失败记录异常。`accepted=true` 只证明接口受理，部分固件会异步处理，不代表桌面已展示；`false` 也可能是重复信息。

### 车测步骤

1. 用 `build-apks.sh` / Windows 脚本构建并安装 **Flyme 测试版**，确认目标车机实际 UID 与权限。
2. 在设置的诊断页开启详细日志，然后连接 iPhone，打开手机音乐 App 播放一首有歌名和封面的歌曲。
3. 检查车机原有媒体区域是否出现 Apple CarPlay、歌名、歌手与封面。切换一首歌，检查内容是否随之更新；暂停时应保留歌名并反映暂停状态。
4. 验证方向盘上一曲、下一曲、播放暂停仍然有效；主动切到原车音乐后，不应被周期刷新抢回。
5. 断开 CarPlay 后检查焦点及方控释放。无论媒体信息是否显示，都导出诊断报告用于对照。若 `eas_metadata` 的 title 始终为空，需继续检查 iPhone 元数据；有标题且受理仍不显示，则进一步检查厂商媒体源过滤与界面展示规则。

不要把修复后的局部协议测试结果当作车机媒体展示已通过。当前没有修改 Siri 映射。

## Flyme 语音键 → Siri（尝试适配，待实车验证）

- 标准语音键 `231` 和参考包中的厂商候选 `200231` 接到现有 `requestSiri()`。前台收到完整按下/松开后请求一次；重复按下和没有对应按下的松开不会重复请求。未连接或通话时不发 Siri 请求。
- 系统 UID 版的有效 EAS 会话会额外尝试反射调用原包所用的 `com.ecarx.xui.adaptapi.input.Input.create()`、`requestKeysInterception()`，请求语音键，不拦截音量和切歌。`KEYCODE_R_VOICE_ASSIST` 优先使用固件常量，缺失时回退到 `200231`。
- 同时兼容 `onKeyPressed/Released` 和 `onKeyShortPressed/Released`、`onKeyLongPressed/Released` 回调。一次按住只处理一次；Siri 请求在主线程再次检查会话和通话状态，并沿用现有跨来源语音键去重。
- 没有引入厂商 SDK/DEX；独立模块里的 `FlymeVoiceInput.java` 负责这条可选接入，断开时使旧回调立即失效并异步调用 `abandonKeysInterception()`。接口不存在或权限不足会记录原因，不阻断媒体方控。
- **厂商接口返回注册结果不代表一定派发按键。** 已分析的部分 OTA 中该接口可能不产生实际回调；原车助手是否同时响应，仍需实测。
- 独立方控测试页收到厂商语音回调时，只显示“语音键”和真实键码，不调用 Siri。正式 CarPlay 会话才会发送唤醒请求。
- 诊断日志中查找 `voice interception`（注册/释放）、`voice callback`（真实派发）、`flyme-siri`（去重及发送结果）。`sent=true` 表示请求已入发送队列，不是 iPhone 的成功确认。

车测时先连接 iPhone，在 CarPlay 画面按一次方向盘语音键，再测试长按、切到车机首页但保留连接、以及断开后的原车语音功能。若 Siri 未出现，导出报告区分：前台是否收到键码、厂商接口是否存在、是否实际回调、Siri 请求是否入队。

本次同时移除“方向盘按键”菜单中的“方向盘播放／暂停按钮”（含其选择按键入口）和“外接控制器（实验性）”。Siri、自定义 Siri 按键和其他车辆控制选项保留。
