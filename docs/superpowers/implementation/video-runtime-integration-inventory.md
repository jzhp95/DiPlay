# 视频运行管线接入面、复用白名单与现有工件快照

- 日期：2026-11-01
- 任务编号：N00（[实施 Tasks](../plans/2026-11-01-carplay-video-runtime-pipeline-tasks.md)）
- 依据规范：[Spec v1.2](../specs/2026-11-01-carplay-video-runtime-pipeline-spec.md) 第 31 节、[AGENTS.md](../../../AGENTS.md)

---

## 1. 源码与工作区现状快照

- **Git HEAD**: `7a95356badae2c2ff5a561e455f4b1940a91b118`
- **应用版本约束**: `versionName=0.2.26`, `versionCode=45`, `NDK=28.2.13676358`
- **现有未提交（Dirty）文件清单（严格保持原样，禁止重置、清理或擅自提交）**:
  - `M AGENTS.md`
  - `M common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsService.kt`
  - `M common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsWorkerService.kt`
  - `M common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
  - `M common/src/main/java/com/shilapi/xcertplay/VideoReportPublisher.kt`
  - `M common/src/test/java/com/shilapi/xcertplay/BinaryReportDownloadsTest.kt`
  - `M common/src/test/java/com/shilapi/xcertplay/VideoAdaptationOverlayTest.kt`
  - `M docs/superpowers/specs/2026-10-31-carplay-video-color-latency-remediation-spec.md`
  - `M shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt`
  - `M shared/src/main/java/com/shilapi/xcertplay/media/SoftwareYuvRenderer.kt`
  - `M shared/src/main/java/com/shilapi/xcertplay/media/VideoOutputFreshness.kt`
  - `M shared/src/test/java/com/shilapi/xcertplay/media/VideoOutputFreshnessTest.kt`
  - `?? docs/superpowers/plans/2026-10-30-illustrated-manuals.md`
  - `?? docs/superpowers/plans/2026-11-01-carplay-video-runtime-pipeline-acceptance.md`
  - `?? docs/superpowers/plans/2026-11-01-carplay-video-runtime-pipeline-tasks.md`
  - `?? docs/superpowers/specs/2026-10-31-carplay-grayscale-clipping-evidence.md`
  - `?? docs/superpowers/specs/2026-10-31-guided-video-diagnostics-spec.md`
  - `?? docs/superpowers/specs/2026-11-01-carplay-video-runtime-pipeline-spec.md`
  - `?? Apple-CarPlay-master-icons/`
  - `?? scripts/__pycache__/`
  - `?? scripts/tests/__pycache__/`
- **历史正常软件直出基线状态**: `baseline_unavailable`（当前工作区暂未检出旧版正常直出的独立 APK 或提交哈希，后续如需回归比对需独立从 Git 历史排查提取，不伪造历史基线）。

---

## 2. AndroidMediaSink 视频接入面完整盘点与角色路由

依据 [`AndroidMediaSink.kt`](../../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt) 当前源码逐项梳理所有视频及诊断接口：

| 方法 / 属性 | 调用者与异步语义 | 主屏新实现 (`independent`) 处置 | 旧管线 (`legacy`) 处置 | 副屏 / Mirror 处置 |
| :--- | :--- | :--- | :--- | :--- |
| `onVideoCodec(type, codec)` | 协议流接收线程 | 转换为新契约 `VideoStreamConfig`，向协调器发送 `Config` 事件 | 路由至旧 `AndroidVideoPipeline.onVideoCodec` | 仅支持 legacy |
| `onVideoConfig(type, codecData)` | 协议流接收线程 | 交付新入口解析 CSD 并更新 `configGeneration` | 路由至旧 `AndroidVideoPipeline.onVideoConfig` | 仅支持 legacy |
| `onVideoFrame(type, naluBytes, ...)` | 协议流接收线程（已认证解密、已 Annex-B） | 封装为 `EncodedAccessUnit` 并调用新协调器 `tryOfferAccessUnit`，受有界队列反压 | 路由至旧 `AndroidVideoPipeline.onVideoFrame` | 仅支持 legacy |
| `onScreenStreamActive(type, active)` | 协议流线程 | 记录流活动状态，主屏通过新协调器处理 | 路由至旧 `AndroidVideoPipeline.onScreenStreamActive` | 独立路由 |
| `setSurface(type, surface)` | UI 主线程 / SurfaceHolder 回调 | 构造新 `OutputTarget`，分派给新协调器 `attachOutput` | 路由至旧 `AndroidVideoPipeline.setSurface` | 路由至旧 mirror |
| `clearSurface(type, surface)` | UI 主线程 / SurfaceHolder 回调 | 调用新协调器 `detachOutput`，获取 `ReleaseTicket` 并等待/监听退役 | 路由至旧 `AndroidVideoPipeline.clearSurface` | 路由至旧 mirror |
| `beginSurfaceDetach(surface, parkMain)` | UI 切换 Surface | 统一封装为后端中立的 `ReleaseTicket` 语义；不再仅强转旧类型 | 委托旧 `AndroidVideoPipeline.beginSurfaceDetach` | 不影响主屏 |
| `awaitVideoReleased(timeoutMillis)` | 会话关闭 / 重构线程 | 等待新协调器及所有后端资源进入 `RELEASED` 状态；超时返回 `BLOCKED` | 委托旧 `AndroidVideoPipeline.awaitVideoReleased` | 统一等待 |
| `whenVideoReleased(onReleased)` | 会话异步释放监听 | 注册到新协调器的释放监听队列 | 委托旧 `AndroidVideoPipeline.whenVideoReleased` | 统一监听 |
| `setMirrorSurface(...)` | 外部 Mirror 请求 | 不接管，显式限制为旧实现独立端点 | 委托旧 `AndroidVideoPipeline.setMirrorSurface` | 仅 legacy 拥有 |
| `detachMirrorAndAwait(...)` | 外部 Mirror 释放 | 不接管 | 委托旧 `AndroidVideoPipeline.detachMirrorAndAwait` | 仅 legacy 拥有 |
| `resourceOwnershipCounts()` | 诊断与心跳轮询 | 合并新协调器/缓冲池/后端持有的真实资源计数 + 音频计数 | 委托旧 `AndroidVideoPipeline.resourceCounts` | 独立合并 |
| `videoOutputObservation(type)` | 诊断快照读取 | 由新模块内部 `RuntimeSnapshot` 转换适配为兼容输出观察对象 | 委托旧 `AndroidVideoPipeline.videoOutputObservation` | 独立返回 |
| `videoColorOutput(type)` | 诊断与测试读取 | 返回新像素契约 `ResolvedPixelContract` 对应的实际输出描述 | 委托旧 `AndroidVideoPipeline.videoColorOutput` | 仅 legacy |
| `videoColorEvidence(type)` | 诊断导出 | 导出同帧采样 `SampleResult` 与真实颜色元数据 | 委托旧 `AndroidVideoPipeline.videoColorEvidence` | 仅 legacy |
| `videoCadenceSnapshot(type)` | 性能观测 | 返回新有界指标 `VideoRuntimeMetrics.snapshot()` 中的节奏数据 | 委托旧 `AndroidVideoPipeline.videoCadenceSnapshot` | 独立返回 |
| `recentBacklogEpisodes(type)` | 诊断读取 | 返回新输入队列有界过载/恢复 episode 列表 | 委托旧 `AndroidVideoPipeline.recentBacklogEpisodes` | 独立返回 |
| `latestRawYuvSamples(type)` | 诊断抽样 | 返回新取证模块在租约有效期内捕获的有界同帧不可变样点 | 委托旧 `AndroidVideoPipeline.latestRawYuvSamples` | 仅 legacy |
| `configureDiagnosticSampling(...)` | 诊断开始前配置 | 配置新同帧侵入采样器的 patch 点坐标与关联 frameId | 委托旧 `AndroidVideoPipeline.configureDiagnosticSampling` | 仅 legacy |
| `refreshPicture(type)` | UI 恢复请求 | 触发新协调器重新请求关键帧或重刷最新帧槽 | 委托旧 `AndroidVideoPipeline.refreshPicture` | 独立返回 |
| `suspendVideoForDiagnostics(...)` | 诊断模式互斥占用 | 挂起新协调器输入接纳与调度；超时保持真实状态 | 委托旧 `AndroidVideoPipeline.suspendVideoForDiagnostics` | 仅 legacy |
| `resumeVideoAfterDiagnostics(...)` | 诊断恢复 | 恢复新协调器输入接纳，重置静止与积压基线 | 委托旧 `AndroidVideoPipeline.resumeVideoAfterDiagnostics` | 仅 legacy |
| `diagnosticLeaseReleased(...)` | 诊断租约释放 | 通知新协调器释放诊断相关租约 | 委托旧 `AndroidVideoPipeline.diagnosticLeaseReleased` | 仅 legacy |
| `diagnosticsVideoSuspended()` | 诊断状态查询 | 查询新协调器是否处于挂起状态 | 委托旧 `AndroidVideoPipeline.diagnosticsVideoSuspended` | 仅 legacy |
| `requestDiagnosticKeyFrame()` | 诊断关键帧请求 | 触发独立恢复控制通道请求关键帧 | 委托旧 `AndroidVideoPipeline.requestDiagnosticKeyFrame` | 仅 legacy |
| `finishInputForDiagnostics()` | 诊断结束输入 | 关闭新输入队列接纳并排空 | 委托旧 `AndroidVideoPipeline.finishInputForDiagnostics` | 仅 legacy |
| `freezeFirstOutputForDiagnostics()` | 诊断首帧定格 | 通知新调度策略保持首个有效解码输出，不被后续覆盖 | 委托旧 `AndroidVideoPipeline.freezeFirstOutputForDiagnostics` | 仅 legacy |
| `setVideoRecoveryHandler(...)` | 会话控制注册 | 注册新协调器的 R1/R2 分级恢复回调 | 委托旧 `AndroidVideoPipeline.setVideoRecoveryHandler` | 独立注册 |
| `setVideoDiagnosticHandler(...)` | 诊断日志注册 | 订阅新运行管线有界元数据事件 | 委托旧 `AndroidVideoPipeline.setVideoDiagnosticHandler` | 独立注册 |

---

## 3. 复用白名单与严格禁止依赖清单

### 3.1 允许复用的底层与公共组件白名单
1. **网络与协议安全层**: `AirPlaySession`, `CarPlayController`, `ScreenStream`（仅消费其解密并转为 Annex-B 后的数据流，不重写握手与计数）。
2. **音频与车辆集成**: `AndroidAudioPipeline`, `AudioChannelMapper`, 车辆按键、方控、麦克风与通话处理。
3. **输入与控制写出**: `InputCommandQueue`, `TouchTransform`, HID 报文编码与 Socket 写出机制。
4. **诊断导出与文件系统**: `DiagnosticExportStore`, `VideoReportPublisher`, `FileDiagnosticStorage`（用于将采样结果与元数据回读校验落盘）。
5. **无状态标准解析纯工具**: H.264/HEVC NAL Header 格式解析、SPS 尺寸计算纯数学函数（须通过无状态纯函数单元测试后方可引用）。

### 3.2 严格禁止在新主屏管线（`media/video/*`）中引用的旧实现黑名单
新主屏管线（`implementationId=independent`）严禁 `import` 或间接反射调用以下旧视频模块：
1. `com.shilapi.xcertplay.media.AndroidVideoPipeline`
2. `com.shilapi.xcertplay.media.SoftwareYuvRenderer`
3. `com.shilapi.xcertplay.media.Yuv420Frame`（包含其内部静态结构）
4. `com.shilapi.xcertplay.media.SoftwareYuvColor`
5. `com.shilapi.xcertplay.media.VideoOutputFreshness`
6. `com.shilapi.xcertplay.media.VideoPlanResolver`
7. `com.shilapi.xcertplay.media.VideoPipelinePlan`（严禁作为新模块内部状态或配置契约）
8. `com.shilapi.xcertplay.media.VideoOutputObservation`（严禁作为新模块内部状态，仅在门面层输出兼容快照）
