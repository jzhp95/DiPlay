# DiPlay 的 Flyme EAS 方控接入

## 适用范围与验证状态

本模块以 `carplay-wheel-replica-demo` 中实车成功的 **system UID + Flyme EAS** 路径为基线，默认媒体源 **36**。用户已确认基线 demo 能接管焦点、收到上一曲/下一曲，且车机原音乐不响应。这里是该协议的 DiPlay 集成，**集成版本尚未实车验证**，不能把 demo 的测试结果当作本 APK 的测试结果。

普通 DiPlay 构建不会启用 EAS。开启系统构建也不等于获得 system UID：还需要与车机平台相符的签名、成功加入共享 UID、OEM 媒体权限和对应 MediaCenter 服务。只在 Manifest 写 `android.uid.system` 不足以通过安装和运行授权。

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
| `mobile.gradle.kts` | 显式生成带 system UID 的 main Manifest，保留原 debug/release overlays；与普通构建隔离 |

将来合并上游时，优先保留整个 `integrations/flyme-eas` 目录和新增的 `FlymeWheelIntegration.kt`。现有文件的改动集中在以下接点：

1. `settings.gradle.kts`：声明 `:flyme-eas` 并指向独立目录。
2. `common/build.gradle.kts`：增加一条模块依赖。
3. `mobile/build.gradle.kts`：最后 apply 独立 UID 构建脚本。
4. `CarPlayMediaKeys.attach/detach/onNowPlayingChanged`：分别挂接会话、释放、元数据更新；不抢占已有 listener。
5. `CarPlayMediaKeys.send/onExternalMediaKey`：给 EAS 来源命名，对其他硬件入口做去重；明确的 MediaController 播放/暂停/切歌指令继续工作。
6. `CarPlayHostActivity` 的两处前台媒体按键，以及 `WheelKeyService.sendMedia`：复用同一硬件去重入口。
7. `DiPlayActivity` 既有诊断报告中追加 `FlymeWheelIntegration.report()`。
8. CI 命令追加 `:flyme-eas:testDebugUnitTest`。

不要将模块内部协议搬回 Activity，不要在 shared 层硬编码 Flyme，也不要把 UID 写进通用主 Manifest。上游若重构媒体生命周期，只需要重新挂接第 4～7 项。升级厂商 OTA 时，优先修改/验证 `EasAdapter` 的 ABI。

## 生命周期与焦点

- 每个 CarPlayController 对应一个 Bridge，重复 attach 同一个 Controller 不重新注册。替换 Controller 时先使旧 Bridge 失效并排队注销。
- 等待 `CarPlayBackgroundSession.active`、后台会话中的 Controller 与本 Controller 一致、Controller 未关闭，才注册 EAS。仅打开应用、不连接 iPhone，不抢媒体源。
- 蓝牙直出音频模式跳过 EAS，避免与车机蓝牙 AVRCP 争抢按键。
- 资格检查要求显式构建开关、appId 1000、与 `android` 包签名匹配、OEM 媒体权限及 MediaCenter 包存在。失败时不绑定、不抢焦点；原因写入诊断。
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

普通版保持 `com.shihab.diplay`（debug 仍加 `.hudtest`）、普通 UID，不启用 EAS：

```sh
./gradlew :mobile:assembleDebug
```

系统 Flyme 调试版单独安装身份 `com.shihab.diplay.flyme.hudtest`：

```sh
./gradlew -PflymeSystem=true :mobile:assembleDebug
```

这个输出仍是 Gradle 的调试签名，**不能直接作为 system UID 包安装**。使用明确提供的平台密钥重新签名，输出到仓库外测试目录：

```sh
"/Users/jzhp/Library/Android/sdk/build-tools/36.0.0/apksigner" sign \
  --key "/Users/jzhp/Downloads/test-rea/AOSP-platform-signing/platform.pk8" \
  --cert "/Users/jzhp/Downloads/test-rea/AOSP-platform-signing/platform.x509.pem" \
  --out "/Users/jzhp/Downloads/test-rea/DiPlay-flyme-system-debug.apk" \
  "mobile/build/outputs/apk/debug/mobile-debug.apk"
"/Users/jzhp/Library/Android/sdk/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  "/Users/jzhp/Downloads/test-rea/DiPlay-flyme-system-debug.apk"
```

不要将密钥、keystore 或私有认证资产复制进模块。要测试独立 CarPlay 连接，仍需原项目现有的显式 `DIPLAY_AUTH_ASSETS_DIR` 输入和 `:mobile:assembleStandaloneDebug`；普通 source-only APK 没有凭据，不因本模块而自动获得认证材料。

安装后验证实际身份（把 SERIAL 换成当前车机 adb 地址）：

```sh
"/Users/jzhp/Library/Android/sdk/platform-tools/adb" -s SERIAL shell dumpsys package com.shihab.diplay.flyme.hudtest
"/Users/jzhp/Library/Android/sdk/platform-tools/adb" -s SERIAL logcat -d -s FlymeWheel CarPlayMediaKeys
```

核对 userId/appId=1000、OEM permission granted、诊断 identity 中 platform=true。平台证书若不匹配真实车机，重新签名也不能得到共享 UID，不要把安装失败当作方控协议失效。

## 本地检查与实车验收

```sh
./gradlew :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
  :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
./gradlew -PflymeSystem=true :mobile:processDebugMainManifest :mobile:assembleDebug
```

另有 `./integrations/flyme-eas/test-local.sh`，用本地缓存的 JUnit 4.13.2/Hamcrest 运行纯 Java 测试，覆盖有限申请、关闭后晚到焦点/命令、用户切换媒体源后不重抢、按键映射、自定义 Bundle 不误读、跨路径成对去重和连续按键。

2026-10-16 本轮：10 项纯 Java 测试通过；模块全部 Java 源文件使用 Android 37 SDK 的 android.jar 编译通过；UID 脚本的任务/API 代码对照缓存的 Gradle 9.5 / AGP 9.3 API 单独编译通过；手动 ManifestMerger2 样例验证普通/UID main XML 与模块及原 debug overlay 合并，HUD 组件保留；git diff --check 通过。这些局部验证不替代完整 Gradle 构建。完整 Gradle CI 命令已尝试，wrapper 默认缓存目录不可写；改用已缓存的 Gradle 和工作区缓存后，仍在 `FileLockContentionHandler` 启动阶段出现 `java.net.SocketException: Operation not permitted`。因此 **尚未验证全应用 Kotlin/资源/Manifest 合并及 APK 组装，未生成这次集成版 APK**。需在正常终端运行上面的普通版和 UID 版命令后再上车。

实车按以下顺序验收，导出应用原有诊断报告，并保留 `FlymeWheel` logcat：

1. 打开系统版但不连接 iPhone：原音乐仍可播放、可切歌；没有 EAS 注册。
2. 连接 CarPlay：诊断显示 token、源 36、焦点包等于本包；原音乐失去焦点。
3. 分别测试上一曲、下一曲、播放、暂停，快速连续按键；每次只执行一次，iPhone 标题/状态真实回传。
4. 测试连接后 iPhone 最初暂停、稍后播放的情况。demo 的固定 playing=1 已改为真实状态，此差异必须单独确认。
5. CarPlay 退后台：方控保持有效；播放暂停不影响后续按键接收。
6. 主动切换原音乐：确认不会被有限申请中的旧任务再次抢回；Android 通知栏控制也须单独测试。
7. 正常断开/退出：诊断有 session stopped 和 unregister 结果；等待至少 10 秒，原音乐能重新获取焦点并响应方控。
8. 再次连接 CarPlay：新的 Controller 正常注册，无旧 token 干扰。
9. 普通非 UID 版本、蓝牙音频直出模式：EAS 不抢占，原有按键路线不变。

若失败，提供应用诊断报告、上述 package dump 和覆盖“连接→按键→切源→断开后10秒”的 logcat；这些信息可以分清身份、授权、注册、焦点、转发和释放各层问题。
