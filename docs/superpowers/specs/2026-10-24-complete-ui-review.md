# DiPlay 完整 UI 审阅版

状态：用户已确认进入代码实施；2026-10-24 已接入 Android 生产界面。本文下方“预览验证”保留当时记录，最终代码验收见 docs/validation/2026-10-24-complete-ui.md。

## 覆盖范围

包含 27 个审阅界面，89 项主设置控件与入口，已覆盖 DiPlayActivity 的全部 15 个 filteredSection 分组。各页按现有源码及资源文案盘点，保留独立首页。

| 分组 | 界面 | 来源 |
| --- | --- | --- |
| 首页 | 独立首页 | CarPlayHomeLayout.kt; DiPlayActivity.kt:721-788 |
| 设置 | 设置总览 | DiPlayActivity.kt:898-999 |
| 设置 | 连接 | DiPlayActivity.kt:1362-1372,1413-1483,1712-1720; CarHotspotSettingsUi.kt |
| 设置 | 显示 | DiPlayActivity.kt:1484-1520 |
| 设置 | 音频 | DiPlayActivity.kt:1626-1654 |
| 设置 | 导航 | DiPlayActivity.kt:1659-1665 |
| 设置 | 车辆 | DiPlayActivity.kt:1373-1387,2403-2558 |
| 设置 | 诊断 | DiPlayActivity.kt:1388-1412 |
| 设置 | 高级 | DiPlayActivity.kt:1521-1625,1655-1711 |
| 设置 | 关于 | DiPlayActivity.kt:1721-1733 |
| 投屏 | CarPlay 连接准备 | CarPlayHostActivity.kt:1517-1610 |
| 投屏 | CarPlay 投屏与侧边面板 | CarPlayHostActivity.kt:1715-1915 |
| 投屏 | 投屏内设置面板 | CarPlayHostActivity.kt:2382-2967 |
| 投屏 | 画面调整 | CarPlayPicturePanel.kt |
| 投屏 | 安全区域编辑 | CarPlayHostActivity.kt:3630-3792 |
| 投屏 | 视频播放 | CarPlayVideoActivity.kt |
| 测试 | 方控测试 | WheelTestLayout.kt; DiagnosticsTestLayout.kt |
| 测试 | MFi 测试 | MfiTestLayout.kt |
| 测试 | UID 测试 | UidTestLayout.kt |
| 测试 | 底层 I2C 自检 | MainActivity.kt:47-125 |
| 报告 | 车机报告分享 | DiPlayActivity.kt:1739-1815 |
| 报告 | 手机输入验证码 | assets/report-code.html |
| 报告 | 手机下载报告 | assets/report-share.html |
| 辅助界面 | 图片裁剪 | ImageCropActivity.kt |
| 辅助界面 | 桌面导航小组件 | res/layout/widget_navigation.xml |
| 辅助界面 | 中控地图卡与仪表音乐 | CenterMapOverlay.kt; DiPlayActivity.kt:2202-2230 |
| 辅助界面 | 弹窗与异常状态 | DiPlayActivity.kt:1864-1880,2090-2194,2281-2402,3051-3120; CarHotspotSettingsUi.kt:293-330 |

## 审阅方式

顶部“界面”可直接跳到每个界面；“状态”可切换就绪、首次使用、连接中、已连接、权限未授予、无设备/无网络、失败、忙碌和不支持；“展示条件项”用于查看开关后才显示的设置。宽屏、窄屏、日夜及1.5倍字体可以切换。主应用设置保留原分类归属，首页不并入设置。

热点名称与密码采用整行编辑入口，弹窗含清空、密码眼睛、取消与保存。诊断以现有调试日志、问题标记、报告保存、另存位置、手机查看和FPS控制为准，替换前期简化的诊断示意项。已保留此前的热点主次操作按钮方向。

## 交互及显示边界

所有连接、授权、诊断、导出、播放和硬件数据均为本地模拟，不发送请求、不修改设备设置。SSID、密码和输入内容只保留在预览内存，不写入会话状态。系统权限、文件选择等外部页面显示明确跳转说明；iPhone自己的应用内容、地图流及视频流显示标注的占位区域。二维码不可扫码。

源码中未接到现有可达入口的旧UI辅助方法不擅自新增为产品功能；已声明的内部I2C诊断Activity保留在审阅目录，并标注为内部诊断。Android9目标不新增Android10以上专属控件。布局是Flyme Auto视觉方案，不能声称原生页面或实车像素级一致。

## 预览验证（实施前记录）

JavaScript语法通过，资源文案引用检查通过，主设置分组覆盖15/15；本机Chrome逐页打开27/27界面并观察到关键控件。检查了跨分类搜索、连接方式分支、高级条件项、投屏设置、编辑保存和焦点返回、长音频选项列表、权限缺失示例，以及420px窄屏/1.5倍字体弹窗。测试分类在窄屏下改用分类选择入口，避免文字拥挤。

未运行Android编译或Gradle回归：本轮未修改生产代码。实车运行与最终排版仍需在方案审阅通过、实现之后验收。

## 文件

预览源：artifacts/diplay-complete-ui-20261023/diplay-complete-ui-preview.html。覆盖明细：同目录coverage.json；构建来源：complete-ui.js、complete-ui.css、build-preview.py。旧预览保留用于对照。
