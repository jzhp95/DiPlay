# BYD／DiLink 专属适配清理验证（2026-10-22）

## 范围与保留边界

目标车型为吉利星瑞 2026 款昆仑，Flyme Auto／Android 9（API 28）。

已删除厂商 HUD、物理仪表投屏与任务路由、车辆字段探测、电池／胎压／车速读取、氛围灯、通话接管与弹窗、私有按键及默认映射、初始化向导、调试演示组件、厂商权限／包名查询、HUD 图标、对应配置入口、专属测试和当前使用指南。

将仍需使用的 iAP2 通话、导航、歌曲状态与后台 ADB shell 拆成独立通用实现；保留虚拟地图、音视频、定位、会话生命周期及现有 Flyme EAS 方控。第三方许可与历史发布／审计记录保留；旧配置的品牌过滤及旧键码负例不启用厂商功能。

## 环境

JDK 25、Android SDK 为本机已安装环境。项目指定的 NDK 28.2 安装缺少 `source.properties`，原始 CI 命令配置失败。验证使用临时 Gradle init script 指定完整的 NDK 28.0.13004108，未修改项目 NDK 配置：

```groovy
allprojects {
    afterEvaluate { p ->
        if (p.name == 'shared') p.android.ndkVersion = '28.0.13004108'
    }
}
```

```sh
./gradlew --init-script /tmp/diplay-byd-use-installed-ndk.gradle   :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest   :common:testDebugUnitTest :home:testDebugUnitTest   :mobile:lintDebug :home:lintDebug :maphost:lintDebug   :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug   --continue --console=plain
```

## 测试结果

| 模块 | 清理前用例／失败 | 清理后用例／失败／跳过 |
| --- | --- | --- |
| flyme-eas | 未单独记录 | 10／0／0 |
| shared | 1567／11 | 1219／0／1 |
| common | 1438／135 | 1300／101／0 |
| home | 8／0 | 8／0／0 |

完整命令退出码为 1，未达到全绿。common 的 100 个失败用例在清理前已失败；另有未修改的 `ReportShareServerTest.servesThePageOnlyUnderTheTokenPath` 出现 `Connection reset`。定向复跑 73 项时，46 项清理相关用例全部通过，报告分享用例出现一次 `stopClosesTheListener` 失败。随后仅复跑报告分享 27 项全部通过。两次不稳定结果均保留记录，不能据此宣称报告分享不存在问题。本次未修改其实现与测试。

定向覆盖：FlymeVoiceInputTest、FlymeMediaPublicationTest、WheelTestControllerTest、NavigationWheelKeyPolicyTest、HomeScreenMonitorTest、UsbAutoConfirmServiceTest。验证过程中发现的方控测试页记录与 Siri 去重回归已修复，移除的厂商行为断言已改为标准按键／标准设置页行为。

专属功能删除使测试数量减少；这不等同于修复历史失败。其余历史失败不在本次清理范围内。

## 构建、资源与界面

- mobile、home、maphost 的 Debug APK 构建通过；Lint 均为 0 错误，分别有 3、14、7 项警告。
- 最终删除未引用资源后，重新执行上述构建与 Lint，通过。
- 检查三个 APK：未发现 BYD／DiLink 命名资源，以及厂商 HUD 类、仪表布局类、StarterBridgeReceiver 或 StandaloneHudDemoActivity。
- 源码与 Manifest 中的厂商实现、权限和显式组件入口已移除，`git diff --check` 通过。
- home 的紧凑／完整窗口大字体截图已生成并检查，位于 `samples/home/build/reports/flyme-home/`；虚拟地图设置改名为“CarPlay 地图共享（实验性）”。
- 两轮只读代码复查未发现尚未处理的明确运行时回归。

尚未进行 Android 9 实车验证，模拟测试与构建结果不能替代实车连接、通话、导航和方向盘验证。
