# 车机视频适配采集 Implementation Plan
**Goal:** 独立测试页完成硬件清单、真实CarPlay静止/动态采样和可导出证据。
**Architecture:** 扩展已有LiveVideoCounters的累计视频负载字节；common内窗口模型计算差值并拒绝会话变化；设备后台采集、真实主屏覆盖控件、结果存储与导出各独立负责。
**Tech Stack:** Kotlin / Android API28 / JSONObject / Robolectric / EGL14 / PixelCopy / 现有 FileProvider。
## Global Constraints
全部代码和用例先写完再统一测试；版本不变；不自动提交；不引入依赖/车型私有服务；不改变已提交YUV和实验音频行为。
- [x] 写窗口计算/会话隔离/令牌过期/缺项/导出与界面生命周期用例，不提前执行。
- [x] shared LiveVideoCounters增加receivedBytes默认0，实际submit累计，沿用现有FPS计数。
- [x] VideoAdaptationRun实现20秒窗口、CPU/码率统计、解码实例和视口隔离；Store持久化最近8组，Registry只接受进程内有效令牌。
- [x] VideoAdaptationDevice后台收集屏幕模式、解码能力、GPU与API28安全降级。
- [x] VideoAdaptationPage实现设备采集、当前主屏入口、可选iPhone信息、结果/缺项、颜色测试链接与ZIP保存分享。
- [x] VideoAdaptationOverlay与Host挂接、清理、完整截图；新增DiagnosticsTestLayout VIDEO页并保存/恢复选择。
- [x] 全部代码写完后统一CI、独立审查、核对99项历史失败、查看窄屏/宽屏/RTL大字体截图并交付APK。
