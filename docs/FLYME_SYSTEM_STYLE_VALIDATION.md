# Flyme Auto 系统风格适配与验收

日期：2026-10-19。依据本地 update.zip 的系统资源实施，SHA-256 为 `1e60fdd818882eae9cb9b529e40f63429088835de37b938bd86a8077b945fba9`，对应 Android 9 / IHU623G / build 537。设计与执行步骤见 [设计](superpowers/specs/2026-10-19-flyme-system-style-design.md) 和 [计划](superpowers/plans/2026-10-19-flyme-system-style.md)。资源引用链、OTA 镜像校验和提取结果位于工作区 `../analysis_flyme_style_20261019/`。

## 适配结果

| 角色 | 日间 | 夜间 | 使用方式 |
| --- | --- | --- | --- |
| 系统品牌填充 | #1368FB | #1368FB | 主按钮白字、开关和滑杆 |
| 页面底色 | #D3DBE5 | #20272E | 应用页面与系统栏 |
| 卡片表层 | #E5F6FBFF | #66495460 | 保留首页/概览功能卡，按实际底色合成 |
| 普通按钮 | #F2F7FC | #282C31 | 设置入口与辅助操作 |
| 弹窗表层 | #DAE2EB | #24282E | 独立不透明窗口，避免透明卡片色透底 |
| 一级/次级文字 | 90%/60% 黑 | 90%/60% 白 | 采用系统一级与 fix L2 角色 |
| 正文强调 | #064DBD | #66B2FF | 从品牌蓝派生，保证文字对比度 |

完整/紧凑窗口使用成对字号与行高，按钮为 24/34sp 与 18/26sp；正文为 22/30sp 与 18/26sp。平台 sans-serif/medium 继承设备字体映射，不打包 OEM 字体。尺寸使用 dp/sp；普通按钮圆角为高度的 0.375、最大 24dp，弹窗操作最小高度 80dp。旧固定高度的设置/辅助按钮转为最小高度，允许大字体和长标签增高。

按钮通过 StateListAnimator 提供按压缩放、释放回弹，保留原点击与触摸监听，覆盖取消、禁用和移出窗口的恢复。平台 Overshoot 插值是弹性观感近似，不声称与厂商物理弹簧参数相同。列表保留轻量按压覆盖及方控焦点圈。

设置分类正文改为开放分组表单，宽屏侧栏移除外层卡片，导航行使用共享尺寸并独立滚动；首页和概览保留功能卡。诊断页、UID/MFi/方控页面、画面调整、CarPlay 侧栏、Compose 及 home/maphost 样例接入相同语义色或字阶。画面调整面板整块滚动，首次打开即应用当前配色。媒体画布、设置归属、搜索接口、偏好保存和业务回调保持原职责。

## 分项验证

- 配色：24 项聚焦检查通过，含透明表层合成后的文字对比度和日夜品牌填充；提交 `b78873c9`。
- 组件：common 40、home 8、maphost 1 项通过，覆盖真实触摸取消/禁用/移出窗口、按钮和弹窗自然高度、字体/行高、开关状态及截图；提交 `23f16769`。
- 页面：common 33、home 8、maphost 1 项聚焦检查通过，含开放分组、无外卡导航、辅助按钮换行、短面板底部操作滚动可达及弹窗表层。
- 最终校正：17 项定向回归通过；精确字阶测试先复现说明文字误用正文（期望 16sp、实测 18sp），再修复共享阈值；同步白色滑块、语义文字色与行高预期，保留业务断言。各项独立只读复审未发现新增 P1/P2。

完整页面矩阵为 4 种窗口 × 日夜 × 首页/连接/音频，共 24 张，字号缩放 1.5：400×800dp、1400×900dp、900×400dp、高密度 400×800dp RTL（800×1600px）。测试固定界面缩放 100%，避免 AUTO 把宽屏重新缩小；另外保留自动布局和系统配置兼容测试。应用文案固定简体中文，RTL 用阿拉伯语资源配置并明确启用镜像方向，检查几何布局，不代表新增阿拉伯语翻译。

截图路径为 `common/build/reports/flyme-system/`；人工检查了日间完整连接页、夜间紧凑连接页、短窗口音频页、高密度 RTL 夜间音频页及完整夜间首页，未见操作文字裁切。新测试也检查所有已布局按钮的文字高度，以及短面板滚到底后的底部操作可见性。组件/弹窗截图位于对应测试生成的 build/reports 目录。

## 完整 CI

已执行 AGENTS 指定的测试、Lint 和构建，另加 Flyme EAS 与 maphost 单元测试，使用 `--continue` 完成所有检查：

```sh
./gradlew :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest \
  :common:testDebugUnitTest :home:testDebugUnitTest :maphost:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --continue
```

本机使用 JDK 25、已安装 Android SDK 与 NDK 28.0.13004108，通过临时 init script 选择已安装 NDK；仓库声明未修改。

| 模块 | 测试数 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| shared | 1553 | 13 | 1 |
| common | 1433 | 136 | 0 |
| home | 8 | 0 | 0 |
| maphost | 1 | 0 | 0 |
| Flyme EAS | 10 | 0 | 0 |

全量命令未全绿。按 classname + testcase name 与上一轮系统审计 full-ci XML 逐项比较，shared 的 13 个失败名称完全相同；common 的 136 个失败均已存在于上一轮的 142 个失败中，新增失败集合为空。此前授权 fixture 修复的六项 CarPlayHostDisplaySizeTest 在此次完整运行中通过。其余历史失败未纳入本次风格适配修复，不能仅凭名称相同认定其都是测试问题。

首轮完整运行曾发现五项受新样式影响的旧断言，其中精确字阶检查还揭示说明误用正文角色；修复并经 17 项定向回归后，重新执行了上面的完整命令。上表是最后一次全量实测结果，不由定向结果推算。

mobile、home、maphost 的 `lintDebug` 全部 0 error，warnings 分别为 17、14、7；三个 `assembleDebug` 全部成功。全部日志、XML、Lint、失败名称集合对比、聚焦验证与截图已归档至 `build/reports/flyme-system-validation/`（构建产物，不纳入 Git）。

## 提交与实机边界

按配色（`b78873c9`）、组件（`23f16769`）、说明字阶修正（`22fedc30`）及页面适配分别提交，提交正文记录动机、行为和验证；页面与本记录同一提交，未推送远端。未导入 OEM APK、车型背景、厂商 UI 库或字体文件。当前证据来自静态固件资源、Android 原生渲染的 Robolectric 截图、单元测试、Lint 和构建；目标车机资源覆盖、实际字体映射、方控焦点和动画观感仍需在设备验证，不能由本地模拟测试结果推断已完全一致。
