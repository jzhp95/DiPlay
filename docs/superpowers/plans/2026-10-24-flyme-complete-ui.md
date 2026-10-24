# Flyme Auto 完整界面实施计划

> **For agentic workers:** Execute task-by-task in this session; request independent code review before completion. Steps use checkbox syntax for tracking.

**Goal:** 将已审阅的完整 UI 方案落实到现有 Android App，保留独立首页、全部功能与现有持久化/重连生命周期。

**Architecture:** 沿用 native View 和 ui-style 共享展示层，不引入网页 UI，也不重写传输、权限和会话。统一字阶、色彩、按压、分组、设置行及编辑组件，再在现有页面接入，最后验证全部应用自有界面。

**Tech Stack:** Kotlin、Android API28+、native View/AlertDialog、AndroidX SpringAnimation、Robolectric、Gradle、现有手机报告 HTML。

## Global Constraints

- 目标：吉利星瑞2026昆仑，Flyme Auto / Android9 API28。仅 mobile 及共享模块；不恢复删除的独立应用、BYD/DiLink或其他吉利私有方控通道。
- 保留偏好键、Flyme EAS 归属、点击/触摸处理、权限确认、报告安全机制及保存/重连约束。
- 独立首页保持启动入口；设置分类不复制业务设置；保留总览/搜索/条件项与辅助界面。
- 所有尺寸使用共享命名 dp 常量；文字用 sp；同一行控件等高，圆角依高度；中文文案、RTL、窄屏及1.5字体可用。
- 修改基于本轮开始时的未提交可见性修复；不覆盖其他文件/用户修改。原预览的示例状态不得进入生产业务。

用户补充：先一次性完成全部代码修改，最后统一测试/构建/截图验收；不再分段运行测试。已执行的旧实现聚焦检查已结束，后续验证合并到Task4。

### Task 1: 统一展示契约

**Files:** ui-style/.../FlymeAutoStyle.kt, DiPlayPalette.kt, FlymePressFeedback.kt; common/build.gradle.kts; tests FlymeAutoStyleTest.kt, FlymePressFeedbackTest.kt。

- [x] 用实际控件测量验证文字、填充和可见触控区域；新增按压中途释放保持连续的回归，先跑失败。
- [x] 分离 L1/L2/L3 和输入表层；按 H2/B1/B2/C1 对齐文本大小/行高/字重；共享普通、主要、文字操作样式。
- [x] 用 SpringAnimation(250,.89,.002,.9→1)替换固定时长近似；通过 StateListAnimator 适配保留业务触摸所有权、取消、禁用及 detach 恢复。
- [x] 运行现有字体/控件/按压测试，修复回归。

### Task 2: 设置行与热点编辑

**Files:** DiPlayActivity.kt, CarHotspotSettingsUi.kt, 新增 SettingsTextEditor.kt，相关 values/drawable 资源；tests CarHotspotSettingsUiTest.kt, SettingsControlVisibilityTest.kt, 新增编辑交互测试。

- [x] 回归：取消编辑不改变草稿、弹窗保存仅更新草稿、最终保存后写入配置；密码默认隐藏，空字段有明确入口。
- [x] 新增共享编辑组件：输入、清空、眼睛、取消、保存；不以外观变化替换验证/生命周期回调。
- [x] 将热点字段改成整行入口，保留频段/信道依赖及已连接时启动热点确认；保存主操作、开启热点次操作，窄屏等高自适应。
- [x] 设置选择行按“标题 · 值”语义保留 getText/search，用转换后的两行显示和箭头增强层次；测量随文字换行增长。
- [x] 对照所有15个设置分组，保留独立首页及当前信息架构；开放分组与实心控件配套落地，不产生相邻不可辨认区域。

### Task 3: 所有自有页面接入

**Files:** SettingsWidgets.kt/SettingsTheme.kt、CarDialogStyle.kt、CarPlayHomeLayout.kt、CarPlayHostActivity.kt、CarPlayPicturePanel.kt、各 TestLayout、ImageCropActivity.kt、MainActivity.kt、report-share.html/report-code.html。

- [x] 主设置诊断操作用整行入口；保留原有详细日志/问题标记/导出/手机报告/FPS功能。
- [x] 统一主页功能卡、分类导航、分组文字、输入与弹窗列表；测试页面、投屏内设置、画面和安全区域沿用共享组件。
- [x] 手机报告网页只改展示，保留验证码验证、剩余次数、下载URL和服务关闭语义。
- [x] 逐项核对 artifacts/diplay-complete-ui-20261023/coverage.json 的27个界面；系统页面和iPhone自有内容不伪造。

### Task 4: 验收和交付

- [x] 聚焦测试：编辑、信息架构、导航、按压、对话框、热点保存/权限、Flyme EAS、投屏显示状态。
- [x] 截图：API28主页、所有设置分类和编辑弹窗；API29测试页。日夜、完整/窄屏/短屏、1.5倍字体和RTL检查；投屏/媒体实际内容未作实车截图。
- [x] `:flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug`，使用已安装NDK的临时init脚本，不改仓库NDK版本。
- [x] 对全量历史失败按类/方法和本轮基线记录比对，不把失败说成通过。
- [x] 独立代码审阅，修复重要问题；复制APK及截图到独立artifact目录，记录哈希和验证边界。

验收结果：专项 59 项通过；全量保留 99 项历史失败（方法和错误信息完全一致），无新增失败。APK/Lint完成；未实车验证。详见 docs/validation/2026-10-24-complete-ui.md。
