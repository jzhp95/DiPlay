# 设置页卡片背景恢复

原因：aaeb7b82 为了对齐 Flyme 开放分组样式，把非概览设置页的共用 section 容器改成 column()，移除了背景填色和圆角。这与用户要求的卡片呈现不符。

修复只将该分支恢复为既有 card() 构建器，沿用当前日夜 surface 配色、圆角和分组间距，保留现有上下内边距。设置项、偏好保存和点击行为未修改。

- [生产改动](../../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt)：section()。
- [回归测试](../../common/src/test/java/com/shilapi/xcertplay/FlymeSystemLayoutTest.kt)：实际 Activity 检查与各分类日夜背景检查；改正旧测试中要求背景为空的断言。
- 修复前 2 项回归测试均因卡片背景缺失失败；修复后 15 项定向检查全部通过。
- API 28、1.5 倍字体截图覆盖宽屏、窄屏、短窗口、RTL、高密度及日夜模式；已查看连接页与音频页的宽屏/窄屏日夜截图。
- 截图为 Robolectric 模拟渲染，未进行 Android 9 实车验证。

[交付目录](../../../artifacts/diplay-settings-cards-20261023) 内为修复 APK 和 16 张设置页截图。包名 com.shihab.diplay.hudtest，版本 0.2.20-hud-test，最低 API 28，v2 签名验证通过。用户提供的本地认证资源按现有流程打包。

## 完整检查

按 AGENTS.md 执行 Flyme EAS、shared、common 单元测试、mobile Lint 和 Debug APK 构建；使用 --continue 完成其他任务。本机配置的 NDK 安装不完整，通过临时 init script 使用已安装 NDK，未修改仓库配置。

| 检查 | 结果 |
|---|---|
| Flyme EAS | 10 项，0 失败 |
| shared | 1157 项，0 失败，跳过 1 |
| common | 1284 项，99 失败 |
| 卡片/布局定向检查 | 15 项，0 失败 |
| mobile Lint | 0 错误、3 警告 |
| mobile Debug APK | 构建成功 |

全量退出码为 1，不能称全量通过。99 项失败按测试类名及方法名对照，均有仓库历史验证记录，没有新增失败签名。本轮未另外运行未修改 HEAD 的完整测试基线；历史对照范围仅限已有记录。

完整日志随交付文件保存为 validation.log，逐项失败和截图散列见 [机器记录](2026-10-23-settings-card-background-results.json)。

执行命令：JDK 25 和本机 ANDROID_HOME，./gradlew --init-script /tmp/diplay-settings-cards-use-installed-ndk.gradle :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --continue --console=plain。
