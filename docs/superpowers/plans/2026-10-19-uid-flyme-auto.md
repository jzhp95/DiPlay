# UID 检测与 Flyme Auto 风格统一实施计划

**Goal:** 为测试工具增加 UID 检测，并以现有 FlymeAutoStyle / DiPlayPalette 统一各模块。
**Architecture:** UID 检测只读进程、包管理器身份；不申请提权、不修改 sharedUserId。共用样式源码供主程序与两个独立示例模块编译，避免引入 CarPlay 运行时依赖。
**Tech Stack:** Kotlin、Android Views、Robolectric、Gradle。

## 已批准设计与约束
- 显示进程 UID、安装 UID、包名、读取状态及 system 身份；读取普通 UID 不等于取得系统身份。
- system 判断采用 Android appId（UID % 100000），覆盖多用户；shell/root 不视为 system。
- 页面切换停止方控测试，恢复选择且不启动新会话。
- 所有文案使用简体中文，现有首页按钮尺寸与设置布局策略保持。
- 媒体画布、仪表导航采用用途所需的专用色，不覆盖为普通设置背景。

## 任务
- [x] 在 WheelTestActivityTest 增加 UID 菜单、身份显示、切页释放和恢复测试；运行并观察缺失菜单导致失败。
- [x] 新增 UidDiagnostic / UidTestLayout，扩展 DiagnosticsTestLayout 与 Activity 生命周期。
- [x] 复用样式源码；统一测试页标题和留白、裁剪页边栏、home/maphost 控件及日夜色。
- [x] 扩展现有 diagnostics 截图测试到三页，验证 400/900dp、1.5 字体、160/320dpi、日夜和 RTL。
- [x] 执行 AGENTS.md 全部 CI 检查；查看截图和 diff，记录验证结果。

最终验证结果见 `docs/UID_FLYME_AUTO_VALIDATION.md`。完整 CI 已执行，但存在与本次任务无关的失败，不能表述为全量通过。
