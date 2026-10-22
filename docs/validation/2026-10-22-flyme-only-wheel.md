# 仅保留 Flyme Auto EAS 方控：验证记录

基准提交：`5ff6a879`。目标为吉利星瑞 2026 款昆仑，Flyme Auto / Android 9。

## 清理范围

- 删除 `GeelyWheelKeys` 输入服务绑定及私有按键订阅、`GeelyWheelKeyCodes`、两份 `com.geely.lib.oneosapi.input` AIDL，以及仅用于这些接口的 shared AIDL 构建开关。
- 删除会话 attach/detach 中旧方控的启动和停止；保留 `FlymeWheelIntegration` 及 `integrations/flyme-eas` 的原有实现。
- 保留 EAS 共用的媒体和 Siri 转发接口，移除默认的 geely-wheel 来源及对应废弃分支；标准 Android 媒体事件继续执行 EAS 归属与去重检查。
- 删除旧服务专属的 4 项测试，将通用测试的语音键引用改为现有 Flyme 常量，诊断来源改为 flyme-eas。
- 更新项目准则与方控说明，禁止恢复并行的旧吉利／领克输入服务通路。

独立的 `GeelyHeadUnit` 麦克风音源选择仍保留，未修改其行为；它不绑定方控服务。Flyme 的语音候选码 200231 继续使用，不能因与旧私有码数值相同而删除。

## 全量验证

| 模块 | 测试总数 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| shared | 1157 | 0 | 1 |
| common | 1279 | 100 | 0 |
| flyme-eas | 10 | 0 | 0 |
| home | 8 | 0 | 0 |

common 的 99 项失败与基准中的类名、方法名完全匹配；另有未改动的 `ReportShareServerTest.theTypedAddressKeepsItsPortFromOneOpeningToTheNext` 端口复用断言失败（期望 49499，实际 49500）。全量退出码 1，不可表述为全部测试通过。

为确认额外失败，单独复测了 7 个相关测试类：音频焦点转发 20、Flyme 媒体发布 3、Flyme 语音输入 3、媒体诊断 1、导航音量按键 7、方控日志 10、报告分享服务 27，共 71 项，全部通过。端口失败在本次单独复测中未复现；保留全量失败记录，不将其算作已修复问题。

mobile、home、maphost 的 Debug APK 构建均完成；Lint 错误均为 0，警告分别 3/14/7。三个 APK 的 DEX 均无 `GeelyWheelKeys`、`GeelyWheelKeyCodes`、旧 AIDL 包或 `com.geely.inputservice` 标识；主 APK 仍含 `FlymeWheelBridge`。

独立代码复核未发现问题。未完成目标车辆实测，仍需确认 Siri、切歌、焦点、通话期间处理及断开后的释放。

## 命令与环境

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-25.jdk/Contents/Home \
  ANDROID_HOME=/Users/jzhp/Library/Android/sdk \
  ./gradlew --init-script /tmp/diplay-byd-use-installed-ndk.gradle \
  :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
  :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --continue --console=plain
```

因仓库指定的本机 NDK 安装不完整，验证用临时 init script 选择已安装的 `28.0.13004108`，仓库 NDK 配置未变。

补充复测在同一环境运行 `:common:testDebugUnitTest`，以 `--tests` 选择 `*ReportShareServerTest`、`*MediaKeyReportTest`、`*WheelKeyLogTest`、`*NavigationWheelKeyPolicyTest`、`*FlymeVoiceInputTest`、`*FlymeMediaPublicationTest`、`*CarPlayMediaFocusForwardingTest`。

全量日志：`/tmp/diplay-flyme-only-wheel-validation.log`；补充日志：`/tmp/diplay-flyme-only-wheel-focused.log`。同目录 JSON 保存全量失败详情、复测统计及产物扫描结果。
