# Android 9 兼容下限清理验证

基准提交：`f5b6a07b`。目标：吉利星瑞 2026 款昆仑 Flyme Auto，Android 9 / API 28。

## 改动范围

主应用、common、shared、Flyme EAS 的 minSdk 统一为 28，自编译原生模块 APP_PLATFORM 同步为 android-28。home、maphost 依赖 API 30，保留原有下限。

删除 Android 7 USB 完成线程、Android 8 USB 预设队列上限、旧音频焦点接口、自制 Base64、旧通知及窗口等兼容分支。保留 Android 9 所需的 USB 队列失败重试、授权探测、热点、存储权限和前台服务处理；Flyme 方控机制不变。通用测试覆盖迁移到 API 28，增加音频焦点请求归属与 UTC 日期边界验证。

## 验证结果

| 模块 | 测试总数 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| shared | 1157 | 0 | 1 |
| common | 1283 | 99 | 0 |
| flyme-eas | 10 | 0 | 0 |
| home | 8 | 0 | 0 |

common 的 99 项失败均与基准记录中的历史失败用例同名，未发现新的失败用例。按类名和方法名对比并移除末尾 SDK 参数，因为部分覆盖从 API 25/27 迁移到了 28；这不表示已修复这些历史失败。移除旧版本专属测试会减少测试总数。另行运行的音频焦点转发测试 20 项全部通过。

三个 Debug APK 构建成功。mobile/home/maphost 的 Lint 错误均为 0，警告分别为 3/14/7。全量命令退出码为 1，原因为上述 common 历史测试失败，不可视为全量通过。

APK 实测最低 API：mobile 28，home 30，maphost 30。主 APK 内三个项目自编译库 `liblocal_hotspot_radio.so`、`libspeex_echo.so`、`libxcertplay_i2c.so` 的 Android ABI note 均为 API 28。第三方预编译库不在此次重编译范围。

## 执行命令与环境

使用 JDK 25 和本机 Android SDK。仓库指定的 NDK 安装不完整，验证临时使用已安装的 NDK `28.0.13004108`；通过 `/tmp/diplay-byd-use-installed-ndk.gradle` 覆盖 shared 的 NDK 版本，未修改仓库 NDK 配置。

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-25.jdk/Contents/Home \
  ANDROID_HOME=/Users/jzhp/Library/Android/sdk \
  ./gradlew --init-script /tmp/diplay-byd-use-installed-ndk.gradle \
  :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
  :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --continue --console=plain
```

完整日志：`/tmp/diplay-api28-validation-final.log`。音频焦点补充验证日志：`/tmp/diplay-api28-focus-proof.log`。逐项失败记录与构建产物检查结果保存在同目录 JSON 文件中。

已完成代码复核。未进行 Android 9 实车验证；USB 接入、热点、声音及 Flyme 方控仍需在目标车辆上确认。
