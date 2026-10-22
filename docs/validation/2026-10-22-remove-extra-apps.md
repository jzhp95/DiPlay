# 删除 Automotive、Home、MapHost 独立应用

基准提交：`c8e35986`。本分支仅保留面向星瑞 2026 款昆仑 Flyme Auto / Android 9 的 `mobile` 主应用及共享模块。

## 改动与边界

删除 `automotive/`、`samples/home/`、`samples/maphost/` 的源码、配置、资源、专属测试及本地产物；移除 Gradle 注册、专属依赖别名、仅供示例使用的 `FlymeSampleStyle`。同步 CI 任务和报告路径、项目准则、资源语言测试模块列表及当前构建/安装/桌面集成说明。历史验证记录、发行说明和上游资料保留。

主应用的地图流、地图嵌入服务、浮窗、导航及 Flyme EAS 实现未修改。此次删除的是三个独立应用，不是主应用内的地图能力。

## 验证

Gradle `projects` 实测仅剩 `common`、`flyme-eas`、`mobile`、`shared`。三个目录已不存在；当前构建和 CI 不再引用已删除应用。独立代码复核无问题。

| 模块 | 测试总数 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| shared | 1157 | 0 | 1 |
| common | 1279 | 99 | 0 |
| flyme-eas | 10 | 0 | 0 |

common 的 99 项失败与既有历史失败按类名、方法名逐项匹配，没有新增失败用例。本轮报告分享测试没有失败。调整后的资源语言测试通过。全量命令仍因上述历史失败退出 1，不可视为全量通过。

`mobile:assembleDebug` 完成；Lint 0 错误、3 警告。APK 中已无 `FlymeSampleStyle`，仍含 `FlymeWheelIntegration`、`MapEmbedService`、`CenterMapOverlay`。未进行目标车辆实测。

## 执行环境与命令

使用 JDK 25、本机 Android SDK。仓库指定 NDK 安装不完整，本机验证通过临时 init script 选用已安装 NDK `28.0.13004108`，仓库 NDK 配置未变。

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-25.jdk/Contents/Home \
  ANDROID_HOME=/Users/jzhp/Library/Android/sdk \
  ./gradlew --init-script /tmp/diplay-byd-use-installed-ndk.gradle projects \
  :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
```

日志：`/tmp/diplay-remove-extra-apps-validation.log`。统计与检查结果见同目录 JSON。提交前检查 `git diff --cached --check`。
