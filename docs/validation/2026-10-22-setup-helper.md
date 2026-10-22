# 旧初始化向导辅助代码清理

基准：`3a681b43`。旧 `setup/DiLinkGeneration.kt`、`setup/SetupGuide.kt` 和专属测试已在 `f5b6a07b` 删除。

本次删除唯一检出的、无源码调用的 `AirPlayPersistence.applySetupNavigationAudioChannelDefault()`。当前连接设置、导航音频通道的读取/保存/旧值回退、本地认证初始化均保留。旧页面状态 `setup` 跳首页的升级处理继续保留。

独立代码复核无问题，源码检索未发现上述三个符号的引用；`git diff --check` 通过。

## 统一验证

运行与前次相同的 CI 全量任务，附加 `:flyme-eas:testDebugUnitTest`，使用 `--continue --console=plain`：

```sh
./gradlew --init-script /tmp/diplay-byd-use-installed-ndk.gradle \
  :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
  :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --continue --console=plain
```

使用 JDK 25 与本机 Android SDK。仓库指定 NDK 安装不完整，临时 init script 选择已安装 NDK `28.0.13004108`，仓库 NDK 配置不变。

- shared：1157 项，0 失败、1 跳过；Flyme EAS：10 项通过；home：8 项通过。
- common：1279 项、100 失败；99 项与既有历史失败逐项同名；另有未改动的报告分享测试 `afterFiveWrongCodesNoCodeIsAcceptedNotEvenTheRightOne` 失败。
- 单独复测报告分享类：27 项、1 失败。全量失败用例此次通过，但 `aBusyPortDoesNotKeepThePageFromOpening` 出现 `SocketException: Connection reset`。
- 三个 Debug APK 构建完成；mobile/home/maphost 的 Lint 错误均为 0，警告分别 3/14/7。

全量与单独复测退出码均为 1，不能宣称全量通过。报告分享模块没有改动，保留其两轮失败证据，本次不扩大到该模块修复。未进行实车验证。

日志：`/tmp/diplay-setup-helper-validation.log`、`/tmp/diplay-setup-helper-reportshare.log`。完整统计及额外失败详情见同目录 JSON。
