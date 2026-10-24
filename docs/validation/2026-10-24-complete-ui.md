# DiPlay 完整 UI 代码与验收

日期：2026-10-24。代码保留独立首页，将已确认方案接入 Android native View；安装包为 debug 测试包。

- 安装包：[DiPlay-FlymeAuto-debug.apk](../../../artifacts/diplay-complete-ui-20261024/DiPlay-FlymeAuto-debug.apk)
- 截图入口：[index.html](../../../artifacts/diplay-complete-ui-20261024/index.html)，共 107 张。主界面/热点编辑为 API28，测试工具为 API29，均为 Robolectric native graphics 渲染及测试数据，并非实车截图。
- 专项验收：59 项通过，含 Android9/10 按压、编辑保存/取消、密码眼睛和清空、热点待保存值、首页等高、分类导航及大字体布局。
- 全量：common 1309 项中 99 项失败；shared 1157 项、0 失败（1 跳过）；Flyme EAS 10 项通过。
- 这 99 项失败与旧记录的类、方法、错误信息逐项一致，无新增失败。完整 CI 因历史失败仍返回非零，不能视为全量通过；明细见 [validation-results.json](2026-10-24-complete-ui-results.json)。
- Lint：0 错误，3 警告；APK 构建成功，v2 签名校验通过，minSdk28。
- SHA256：`0be6dd28c0f652687e7cddba6e2338a4d0b0039fff35c1b4e5d0de1c8d5abfce`。

共享样式覆盖文字、普通/主要/文字按钮、开关、选择行、编辑输入和弹窗；独立首页及全部设置分类保留。热点弹窗只更新草稿，最终保存才写入配置；未修改的保存不会阻挡启动。诊断、投屏内设置、画面、测试、内部诊断、报告、裁剪、小组件、视频控件及地图卡同步接入展示样式，保留会话、权限、媒体与通信逻辑。

截图涵盖主界面十个页面的宽屏/窄屏/短屏/RTL、日夜、1.5倍文字，以及热点操作与编辑、测试工具。视频流、地图流和系统页面未伪造为实车内容。尚未完成 Android9 实车运行验证；内部 I2C、硬件、投屏与网络能力不能由 UI 渲染结果代替验证。

Git 提交状态以仓库记录为准。

## 完整命令

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-25.jdk/Contents/Home ANDROID_HOME=/Users/jzhp/Library/Android/sdk ./gradlew --init-script /tmp/diplay-settings-cards-use-installed-ndk.gradle :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
```

构建日志：`/tmp/diplay-complete-ui-release-check.log`。NDK 通过临时 init 脚本选择本机已安装版本，未改项目配置。

## 验收修复

独立审阅发现并修正：文字转换重入、StateListAnimator 清理与 spring 归属、首次热点读取与编辑弹窗的竞态。Android9 文字变换保留字符长度以保持 TextView 布局偏移；动画测试隔离 AndroidX 缓存的 Choreographer 并逐帧推进，保留物理中间位置、长按、松开、取消、禁用及 detach 检查。窄屏首页按钮按实际换行高度等高测量，选择箭头按日夜着色。
