# 设置控件可见性恢复

基准提交 92ad14d2。上一版仅恢复外卡，内部控件仍出现辨识度和输入布局问题。

原因：普通选择按钮未聚焦时没有边框，浅色填充与卡片接近；热点标签、输入内容及密码显示按钮挤在同一行，400dp/mdpi/1.5 倍字体下密码编辑区只有66px；normalizeSpacing 清除了开关行内边距，且把隐藏错误提示当成可见说明，压缩后续控件间距。

修复：标签移到输入区域上方；输入内容左对齐并使用主文字色，编辑框按自然高度布局；设置控件恢复旧版蓝灰填充、常态边框与焦点边框；统一卡片内边距与开关行留白，只按可见内容计算间距。保留设置分类、保存流程和业务回调。设置控件单独使用 settingsButton/settingsControlFill，不改首页及投影菜单的普通按钮填充。

修改前4项可见性场景均失败；修改后5项全部通过，包括宽窄屏、日夜和空输入框。窄屏密码区扩展到222px，热点名称区300px。像素检查覆盖未聚焦边框与填充区分度。单独检查了21项UI测试（可见性5、热点布局矩阵1、系统布局5、返回对齐4、布局策略6），全部通过。热点原有功能用例有5项失败，按名称均有历史记录。

完整检查：Flyme EAS10项零失败；shared1157项零失败、跳过1；common1289项中99项失败；mobile Lint0错误3警告，Debug APK构建成功并通过v2签名验证。全量退出码1；99项失败签名均有历史记录，没有新增失败签名。本轮没有另跑未修改HEAD的完整基线，不能用名称一致推断所有失败原因一致。

[机器记录](2026-10-23-settings-controls-results.json)；[交付目录](../../../artifacts/diplay-settings-controls-20261023) 中含APK、before/after截图和日志。截图使用测试资料，为API28模拟渲染；另检查了现有大字体、高密度、RTL布局矩阵。未进行实车验证。

本次恢复的是可用性及旧版控件层次，不能据此称为完成Flyme Auto原生风格复刻；当前差异见 [Flyme风格差异](2026-10-23-flyme-style-gaps.md)。

验证使用本机JDK25、ANDROID_HOME与临时NDK28.0.13004108覆盖脚本；仓库NDK声明未改。执行AGENTS要求的 :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug，并以 --continue 完成全部任务。
