# 设置页返回区与分类栏对齐

基准提交：`3a16f8a5`。目标环境：Flyme Auto / Android 9（API 28）。

## 原因与修复

宽屏外层壳的左右留白为 32dp，但分类栏自身额外添加了 16dp 横向内边距，导致返回按钮外框与下方分类项外框不共用页面起始边界。修复仅删除 `settingsRail()` 的这层额外内边距；分类栏宽度、右侧内容、内部文字/图标样式和返回处理逻辑沿用现有实现。窄屏没有该分类栏，保持原有布局。

## 回归证明

新增 `SettingsHeaderAlignmentTest`，实际创建 Activity、测量布局并比较返回按钮与下方分类项/内容组的页面起始边界。API 28、1.5 倍字体，覆盖普通/RTL 宽屏和高密度窄屏。

修复前 4 项测试中 2 项失败：1400dp 宽屏起始位置为 32 对 48；RTL 为 1368 对 1352，两者都偏移 16dp。窄屏两项通过。修复后同一组 4 项全部通过；另加现有系统布局与策略测试，定向共 14 项全部通过。

已查看实际大字体截图：

| 布局 | 修复前 | 修复后 |
| --- | --- | --- |
| 宽屏 | [截图](../screenshots/settings-header-alignment/before-full-large-font.png) | [截图](../screenshots/settings-header-alignment/full-large-font.png) |
| 宽屏 RTL | [截图](../screenshots/settings-header-alignment/before-full-rtl-large-font.png) | [截图](../screenshots/settings-header-alignment/full-rtl-large-font.png) |
| 高密度窄屏 | — | [截图](../screenshots/settings-header-alignment/compact-large-font.png) |
| 高密度窄屏 RTL | — | [截图](../screenshots/settings-header-alignment/compact-rtl-large-font.png) |

RTL 用例采用阿拉伯系统配置，并显式镜像视图几何；产品文案仍固定为简体中文。截图为模拟渲染，不代表已完成实车验收。

## 全量验证

shared 1157 项零失败（跳过 1），Flyme EAS 10 项通过；common 1283 项中 99 项与既有历史失败按类名和方法名匹配，没有新增失败。新增对齐用例在全量中也全部通过。全量退出码 1，不能宣称全量测试通过。

主 APK 构建完成，Lint 0 错误、3 警告。独立代码复核无问题，提交前执行差异空白检查。尚未进行 Android 9 实车验证。

## 执行环境与命令

使用 JDK 25、本机 Android SDK；指定 NDK 安装不完整，通过临时 init script 选择已安装 NDK `28.0.13004108`，仓库配置未改。

```sh
./gradlew --init-script /tmp/diplay-byd-use-installed-ndk.gradle \
  :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
```

红灯日志：`/tmp/diplay-settings-header-red.log`；定向日志：`/tmp/diplay-settings-header-focused.log`；全量日志：`/tmp/diplay-settings-header-validation.log`。详细统计与截图散列见同目录 JSON。
