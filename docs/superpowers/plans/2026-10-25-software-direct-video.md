# Software Direct Video Implementation Plan

**Goal:** 将用户实测较好的软件解码＋直接输出路径变成可保存的日常兼容选项。
**Architecture:** AirPlayPersistence保存独立模式开关；CarPlayVideoOutput统一计算有效输出；Host固定本次输出及主屏软件策略；AndroidMediaSink只对主屏应用严格软件要求。
**Tech Stack:** Kotlin、Android API28、MediaCodec、SurfaceView、Robolectric。

## Global Constraints

- minSdk/API28；默认关闭；版本不变；不改其他车型/方控/音频；不导入第三方APK代码。
- 先完成所有代码及回归用例，再统一执行验证（遵循AGENTS.md）。
- 当前请求已授权实现；在当前线程完成，不额外创建提交。

## Tasks

- [x] 在SoftwareDirectVideoOutputTest、MainScreenSoftwareDecoderTest和AdaptiveSettingsUiTest先写用例：偏好恢复、Host实际软件设置、诊断隔离、主屏范围、保存不掉线、软件不可用和三种窗口截图。
- [x] AirPlayPersistence增加load/saveSoftwareDirectVideoOutput；CarPlayVideoOutput计算有效smooth=false/direct=true/software=true而不覆写已有偏好。
- [x] CarPlayHostActivity使用有效设置、传mainScreenSoftwareDecoder、关闭本会话硬件低延迟调优；切换后复用前校验后台sink，并重建不匹配的Host输出。
- [x] AndroidMediaSink把mainScreenSoftwareDecoder限制在type110且statsLabel=null；保留forceSoftwareVideoDecoder现有诊断行为与无硬件回退规则。
- [x] DiPlayActivity高级影音卡增加模式开关，检查当前codec软件能力，保存后markReconnectNeeded；模式生效时禁用被覆盖的输出/硬件优化控件。
- [x] 统一执行 :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug，检查API28大字体窄/宽/RTL截图并记录结果。

验证完成：详细结果见 docs/validation/2026-10-25-software-direct-video.md。完整CI有99项既有失败，无新增；最终8项专项通过。
