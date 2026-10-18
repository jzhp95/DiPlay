# UID 与 Flyme Auto 样式验证记录

## 完成内容

- 测试工具新增 UID 检测页，区分读取结果、system 身份和安装身份一致性，支持刷新。
- system appId 判断覆盖 Android 多用户，普通应用、root、shell 不会误判为 system。
- 三页导航保持独立滚动、选择恢复和方控焦点释放，保留 MFi 取消逻辑。
- common、home、maphost 共用 ui-style 里的现有 Flyme Auto 配色、按钮、卡片和弹窗源码。
- 统一测试页标题与留白，裁剪页操作栏；home/maphost 支持日夜配色、窄屏、大字体。
- 修复短横屏 home 内容不可见和 Activity 重建时待添加组件 ID 丢失。
- 浅色强调蓝微调以通过 4.5:1 对比度要求，原有风格保持。

## 最终通过的检查

最终 Gradle 命令 BUILD SUCCESSFUL，耗时 59 秒：

- common：29 项相关测试全部通过（UID、测试页 Activity、方控布局、三页截图、配色对比度、Flyme 控件、通用 UI 截图、弹窗截图、MFi controller）。
- home：8 项测试全部通过，包括编辑/返回、重建时待添加组件 ID、日夜和大字体布局。
- maphost：1 项布局与截图矩阵测试通过。
- mobile / home / maphost：lint 全部 0 error，分别为 17 / 14 / 7 warnings。
- mobile / home / maphost：debug APK 全部构建成功。
- git diff --check：通过。
- 独立源码审查：两项生命周期/布局问题已修复，最终复审未发现 P1/P2 问题。

截图矩阵：测试工具包含 400/900dp、160/320dpi、1.5 字体、日夜与 RTL；home 包含 400x800、800x360、1100x700、日夜、1.5 字体；maphost 包含 400x800、1100x700、日夜、1.5 字体。已人工查看最终 UID、home 和 maphost 的代表性截图。

截图在各模块 build/reports/diagnostics-test-ui、flyme-home、flyme-maphost，以及 common/build/reports/flyme-app、car-dialogs（以测试输出为准）。截图来自 Robolectric，不代表车机硬件实测。

## 完整 CI 的限制

已按 AGENTS.md 和 .github/workflows/android.yml 执行全部单元测试、lint 和构建。首次完整运行：shared 1534 项、13 失败、1 跳过；common 1406 项、140 失败；home 7 项通过；flyme-eas 10 项通过。完整检查未全绿。

随后在 `/tmp/diplay-ui-baseline` 导出未修改 HEAD，使用同一 JDK / NDK 复测：AdaptiveSettingsUiTest 65 项中 22 项失败，AirPlayTcpAcceptTest 2 项中 1 项失败，复现了文案/搜索与本机网络监听的同类问题。没有逐项复测所有完整套件失败，不能宣称其全部已证明为历史问题。

本次范围内失效的旧测试已对齐现有设计：简体中文资源策略、返回入口位于测试工具左侧、Flyme 中性配色。其余已有失败没有通过禁用测试或修改生产逻辑来掩盖。第一次全量报告保存在 `build/reports/uid-flyme-validation/`，最后的定向通过报告位于各模块默认测试报告目录。

## 构建环境与产物

JDK 25；本机 NDK 28.2 安装不完整，使用临时 init script `/tmp/diplay-ui-installed-ndk.gradle` 选择完整安装的 NDK 28.0.13004108。项目 NDK 声明未改。

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `samples/home/build/outputs/apk/debug/home-debug.apk`
- `samples/maphost/build/outputs/apk/debug/maphost-debug.apk`

未连接真实车机，实际 UID 和 OEM 系统接口权限以安装后的 UID 检测页和具体功能实测为准。
