# Flyme Auto System Style Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将现有应用表层适配到已解析的 Flyme Auto 系统设计语言。

**Architecture:** ui-style负责语义颜色、控件尺寸/字阶/反馈；common与样例继续共编译该源码。Native窗口资源和Compose映射到相同规则；业务层只在已有控件工厂接入，不改偏好与协议。

**Tech Stack:** Kotlin、Android Views/Drawables/Animator、Compose、Robolectric；无新厂商依赖。

## Global Constraints

- 参考2026-10-19-flyme-system-style-design.md与SHA-256指定OTA。
- 保留小窗、大字体、方控焦点、设置分类及业务回调；尺寸使用dp/sp，命名间距常量，同排等高。
- 不导入OEM APK、车型背景或字体文件；不改变媒体画布。
- 分项提交，提交正文详细记录；当前未跟踪icons目录不加入。

---

### Task 1: 系统语义配色

**Files:** ui-style/.../DiPlayPalette.kt；common/res/values/{colors,themes}.xml；相关等待页/Compose/设置主题；DiPlayPaletteTest.kt与FlymeAutoStyleTest.kt。

**Interfaces:** produces `DiPlayPalette.accentText: Int`、`dialogSurface: Int`；保留accent作为品牌填充。现有surface可含alpha，窗口与弹窗使用不透明专属角色。

- [x] RED：palette与真实button检查日夜品牌填充均为Color.rgb(19,104,251)，onAccent均WHITE；旧中性层断言替换为系统蓝灰来源断言。实际渲染/合成后文字对比验证，不能测试Color.red(alphaColor)代替可见颜色。
- [x] 运行 :common:testDebugUnitTest --tests '*DiPlayPaletteTest' --tests '*FlymeAutoStyleTest'，确认旧配色导致断言失败。
- [x] GREEN：在共享palette定义设计指定值，Native资源同步，等待页继续委托palette；正文强调使用accentText，图形/主按钮继续accent。调用 `setTextColor(palette.accentText)`，弹窗window背景调用palette.dialogSurface。
- [x] 运行相关配色/主题与现有布局快照，独立审查后单独详细提交。

### Task 2: 统一组件字阶、形状和反馈

**Files:** FlymeAutoStyle.kt；CarDialogStyle.kt；新FlymePressFeedback.kt；DiPlayActivity.kt按钮工厂；settings/SettingsWidgets.kt、诊断布局、Compose Type.kt；相关组件和弹窗测试。

**Interfaces:** `FlymeAutoStyle.text(view: TextView, role: TextRole, compact: Boolean)`应用字号/行高；`FlymePressFeedback.install(view: View)`只添加状态动画，不替换点击/触摸监听。

- [x] RED：真实Button press/release/cancel/disable/detach检查scale恢复、原click回调只调用一次；紧凑和完整字号/行高与高度派生圆角检查；弹窗在大字体窄屏不裁字。
- [x] 运行聚焦测试确认当前Ripple/字号/圆角不满足。
- [x] GREEN：按钮圆角 `min(24dp,heightPx*.375f)`；完整按钮24/34sp、紧凑18/26sp；TextRole实现设计字阶。Animator只使用平台API，状态监听通过Drawable状态，不接管业务事件。对话框复用同一控件工厂并覆盖80dp高度与独立表层，处理disabled和focus。
- [x] 删除设置按钮重复padding/background/focus覆写；Switch轨道使用accentTrack/trackOff。
- [x] 运行按钮、开关、弹窗、诊断和快照矩阵；独立审查并单独详细提交。

### Task 3: 页面层次与整体校验

**Files:** DiPlayActivity.kt设置section/rail；样例Home/WidgetBoard；settings布局测试和全页截图；最终验证记录。

**Interfaces:** 复用Task 1/2 palette和FlymeAutoStyle；已有section(title,build)、分类搜索、偏好保存接口保持。

- [x] RED：真实分类布局验证分组表单背景与导航独立滚动；原回调、搜索和返回仍可用；截图检查窄屏/宽屏/短屏及大字体不裁切。
- [x] GREEN：分类表单移除通用外卡、保留标题和统一分组间距；概览功能卡不变。侧栏去外卡，改用共享导航行尺寸、圆角和按压覆盖。样例控件使用共享字号/状态，地图媒体布局职责不变。
- [x] 运行现有相关设置、诊断、home、maphost、弹窗截图，人工查看日夜紧凑/完整/RTL代表图。
- [x] 执行AGENTS完整测试/Lint/构建，保存失败名称并与上一轮full-ci比较；报告新增失败、既有失败、构建结果和实机边界。
- [x] 独立复审后提交页面适配和验证记录。

最终完整验收记录：docs/FLYME_SYSTEM_STYLE_VALIDATION.md。全量无新增失败；既有失败保留并明确报告。说明字阶阈值修正另行独立提交，先完成16sp/18sp失败复现再修复。
