# CarPlay 视频色彩、延迟与重连死锁修复软件门禁验证报告

- **日期**：2026-10-31
- **目标设备**：吉利星瑞 2026 款昆仑（Flyme Auto / Android 9 / API 28 / MTK 平台）
- **修复规格**：[`2026-10-31-carplay-video-color-latency-remediation-spec.md`](../specs/2026-10-31-carplay-video-color-latency-remediation-spec.md)
- **实施计划**：[`2026-10-31-carplay-video-color-latency-remediation-tasks.md`](2026-10-31-carplay-video-color-latency-remediation-tasks.md)
- **验证矩阵**：[`validation-matrix.json`](file:///Users/jzhp/Downloads/test-rea/DiPlay/build/video-remediation-validation/20261031-video-remediation/validation-matrix.json)
- **当前提交状态**：部分完成。单元测试、Lint、Python 与公共树检查通过；NDK 28.2 原生构建和双 APK 校验未完成。
- **历史记录说明**：下方原有的 2911 项测试、APK 哈希与 PASS 结论属于上一批次，不能作为当前提交的完整门禁证明。

## 当前提交前验证（2026-10-31）

| 检查 | 本轮结果 |
| --- | --- |
| Flyme EAS 单元测试 | 10 项，0 失败、0 错误、0 跳过 |
| shared 单元测试 | 1407 项，0 失败、0 错误、1 跳过 |
| common 单元测试 | 1496 项，0 失败、0 错误、0 跳过 |
| 总计 | 2913 项：2912 通过、1 跳过 |
| Android Lint | PASS：0 错误、5 条警告 |
| Python 离线分析 | 5 项通过 |
| 公共代码树检查 | PASS |
| 认证资源隔离检查 | 等待 NDK 28.2 安装 |
| 本轮双包构建、架构、清单、签名及哈希校验 | 未完成 |

本轮包含内存回收、YUV 清理异常传播、首帧采样配置和生产完整性判定的最新修改。
新增的 VideoBufferReleaseTest 4 项和 DecoderPixelPairingTest 7 项已实际执行并通过。

JVM 与 Lint 使用 JDK 25，在独立 Gradle 缓存 build/jvm-validation-cache 下临时通过
build/car-test-apks/use-ndk.gradle 适配本机已安装的 NDK 28.0。项目声明与 CI 仍固定
NDK 28.2.13676358。此结果不能替代指定 NDK 下的原生构建验证。

遵照用户“先提交代码”的指示，先记录当前验证状态并提交。
没有执行实车验证，不能宣称默认颜色正确、发白消除、跟手性达标或长期运行无泄漏。
旧 APK 不包含本轮最新修复，不作为本轮产物交付。


---

## 1. 上一批次软件门禁记录（历史）

### 1.1 Python 离线日志分析用例
- **命令**：`python3 -m unittest discover -s scripts/tests -p 'test_video_remediation_evidence.py'`
- **结果**：Ran 5 tests in 0.007s, **OK**
- **覆盖能力**：色彩与延迟取证解析、MTK 变体日志抽取、触控时间线 unknown 语义审计、节奏证据解析、跨 runId 独立保留与冲突捕获。

### 1.2 公共树合规检查
- **命令**：`python3 scripts/check_public_tree.py`
- **结果**：**PASS**（未引入凭据容器或私钥块）。

### 1.3 统一 Gradle 门禁与 APK 构建
- **命令**：
  ```sh
  ./gradlew -I build/car-test-apks/use-ndk.gradle -PflymeSystem=true \
    :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
    :mobile:lintDebug :mobile:assembleDebug :mobile:assembleOptimizedDebug \
    :mobile:processReleaseMainManifest --console=plain
  ```
- **执行结果**：BUILD SUCCESSFUL（230 actionable tasks）
- **JUnit 测试用例统计**（同一构建批次 XML 归档核验）：
  - `:flyme-eas`: 10 tests（10 passed, 0 failed, 0 skipped）
  - `:shared`: 1405 tests（1404 passed, 0 failed, 1 skipped: `AirPlayPortSelectorTest.fallsBackWhenAnotherListenerOwnsTheWildcardPort` 原生端口绑定忽略用例）
  - `:common`: 1496 tests（1496 passed, 0 failed, 0 skipped）
  - **总计**：**2911 个用例，0 失败，0 错误，1 个合法跳过**。

### 1.4 新增用例实际执行核验
- `AirPlaySessionCloseTest` (T00)：4 passed（写锁退出与媒体流关闭解耦、可用性通道不被阻塞、关闭期事件隔离）。
- `AirPlayEventPeerTest` (P2-6)：严格建立陌生端连接，断言服务器立即断开并记录拒绝日志，控制端随后稳定连接。
- `VideoColorContractTest` (T03)：3 passed（BT.709/BT.601 优先级矩阵、Full/Limited 范围计算、MediaFormat 提取）。
- `Yuv420FrameTest` (T04/T08/P2-4)：NV12/NV21 行批量解包、奇数裁剪色度映射（`left=1, width=4` 验证读取第二色度样点）及样点元数据提取验证通过。
- `DecoderPixelPairingTest` (T05/P2-3/P2-9/P2-10)：7 passed（调用生产对象 `DecoderPixelPairing.evaluate(...)`、`DecoderDiagnosticsMetrics.highlights` 接入 raw YUV 样点并绑定参考与帧身份；覆盖 CSD 参数注入与 Annex-B 转换、三层取证缺失标记 partial）。
- `VideoPlanResolverTest` (T06/T07)：决议算法无副作用纯逻辑验证通过。
- `VideoPipelineTimingTest` (T09)：阶段计时单调性与渲染开销分离测试通过。
- `VideoOutputFreshnessTest` (T10)：1 passed（时效窗口丢弃策略正确）。
- `VideoBacklogRecoveryTest` & `VideoDecodeQueueTest` (T11)：软恢复（参考帧链重置不重建解码器）与硬恢复隔离测试通过；`lastTriggeredEpisode` 回写与导出接线验证通过。
- `VideoCadenceEvidenceTest` (T12/P2-5)：6 passed（PTS 间隔与到达间隔独立追踪、512 帧环形缓冲区持续更新、接入媒体接收与诊断导出）。
- `InputCommandQueueTest` & `VideoTouchTimelineTest` (T13/P2-5)：11 passed（明确区分队列锁等待 `queueLockWaitNs` 与动作耗时 `actionDurationNs`、导出 `phoneAcknowledged = "unknown"`、溢出报 backpressure、多指不合并）。
- `VideoRemediationEvidenceTest` & `VideoAdaptationExportTest` (T14/P2-7)：5 passed（未知实验类型拦截、`before_after` 策略对比与证据导出完整性）。
- `SessionRuntimeStateTest` (T15/P2-4)：代际递增清理与 Surface 生命周期测试通过，Surface 代际与 Color 代际完全隔离。
- `VideoBufferReleaseTest` (P1-8/P1-9)：2 passed（验证 OutputItem 释放异常抛出至恢复流程、释放幂等性保障、DiagnosticSamplingConfig 构造与保存）。

### 1.5 产物与架构核验（与 validation-matrix.json 严格对齐）
- **`mobile-debug.apk`**：
  - 大小：64,352,719 bytes
  - SHA256：`c18ea31eee90071836ffdc16d78856b3d10c179f5cd959249b6fbf3fe06322c1`
  - 架构：4 个 ELF64 AArch64 原生动态库（`liblocal_hotspot_radio.so`, `libspeex_echo.so`, `libxcertplay_i2c.so`, `libspeex_resample.so`）。
- **`mobile-optimizedDebug.apk`**（R8 优化包）：
  - 大小：29,146,630 bytes
  - SHA256：`84973fd0fd80b71b5f479e5f451f23c14829fce8d9f6d84df8e300f6f07af21e`
  - Mapping 文件：`mobile/build/outputs/mapping/optimizedDebug/mapping.txt`（42,348,038 bytes，SHA256: `7040b2d637bf12e6a01803e73023b7043fbaffee02b9516767ef194e605241d2`）。
- **Flyme Manifest 校验**：
  - Release 包名：`com.shihab.diplay.flyme`，`versionName=0.2.26`，`versionCode=45`
  - Debug 包名：`com.shihab.diplay.flyme.test`，`versionName=0.2.26`，`versionCode=45`
  - 系统 UID 声明：`android.uid.system` 保留，minSdkVersion=28。

---

## 2. 审查缺陷修正与缺口补齐清单

| 编号 / 级别 | 缺陷 / 缺口项 | 根因与原风险 | 修复措施与验证结果 |
| :--- | :--- | :--- | :--- |
| **[P1-1]** | Buffer 仍可能重复释放或遗漏释放 (`AndroidVideoPipeline.kt`) | handleOutput 成功才置 released，YUV 内部在 finally 释放 buffer 后若抛异常外层又释放一次；EOS/CONFIG 分支先处理旧 batch 失败时当前 index 遗漏释放 | `OutputItem` 统一管理所有 index 释放动作；EOS/CONFIG 立即纳入 batch；`handleOutput` 与 `handleYuvOutput` 使用 `item.release` 并在 release 瞬间置标志位，外层 `finally` 保证每个 index 恰好释放一次 |
| **[P1-2]** | 主屏计划污染其他屏幕和镜像 (`AndroidVideoPipeline.kt`) | 读取 plan 时未限制 `isMain = (type == MAIN_SCREEN_TYPE && statsLabel == null)`，镜像传入的独立 `colorOverride` 被覆盖 | 仅对主屏应用主屏 plan；非主屏严格保留独立 `colorOverride`、原解码与输出策略，且不对外报告主屏 plan 观测 |
| **[P2-3]** | T04 固定素材配对取证不完整 (`SoftwareYuvRenderer.kt`, `DecoderDiagnosticsActivity.kt`, `DecoderPixelPairing.kt`) | 样点仅随机抽取 3 点，缺少几何信息、参考区域和帧身份，未接入固定素材诊断 | 从 `h1-reference.json` 读取 patch 并绑定采样坐标；Surface 路径由 `diagnostic_no_surface_instance` 采集首帧真实 YUV，建立“源参考→原始YUV→RGB截图”逐层配对并写入 highlights |
| **[P2-4]** | 原始 YUV 奇数裁剪映射错误 (`Yuv420Frame.kt`) | 用尺寸比例计算色度坐标，忽略了 crop 奇偶偏移（如 left=1 时像素 x=1 应读第二样点却读第一样点） | 存储 `cropLeft/cropTop`，色度坐标按 `((cropLeft + px)/2 - cropLeft/2)` 精确映射；单元测试覆盖验证通过 |
| **[P2-5]** | HID 耗时阶段命名不准 (`InputCommandQueue.kt`, `SessionFactory.kt`) | 将 action() 耗时命名为 writeAndFlushNs 混淆了队列锁与事件写锁 | 重命名为队列锁等待 `queueLockWaitNs` 与动作耗时 `actionDurationNs`，实际事件写锁和写出耗时保持由 `eventWriteTimingObserver` 记录导出 |
| **[P2-6]** | AirPlayEventPeerTest 弱化与假通过 (`AirPlayEventPeerTest.kt`) | runCatching 忽略了陌生端连接，若根本没连上也会误判通过 | 恢复严格 Socket 连接，断言服务器立即断开并记录拒绝日志；连接重试机制避免 macOS 偶发 RST 抖动 |
| **[P2-7]** | 验证矩阵与产物未对齐及描述不实 (`validation-matrix.json`, 验证报告) | 矩阵哈希未随产物更新，测试统计口径不一，报告断言“严格BT.709/彻底解决发白/CPU降40%”缺少实车证据 | 同一构建批次统一归档 2911 项测试 XML 与 APK 哈希；按实事求是原则纠正效果描述（默认传递原请求保留源 SPS 矩阵 6 可观测，打通渲染决策与取证链路） |
| **[P1-8]** | Buffer 释放异常被吞掉导致输出失败误记为成功 (`AndroidVideoPipeline.kt`) | `item.release()` 中使用 `runCatching` 忽略 `codec.releaseOutputBuffer()` 异常，调用方仍增加 rendered、记录首帧成功且无法触发解码器重建或恢复 | 去除 `runCatching` 吞异常逻辑，先标记 `released = true` 保证幂等防重复释放，底层异常直接抛出给 `onJobFailure` 进入错误恢复；新增 `VideoBufferReleaseTest` 覆盖 |
| **[P1-9]** | 固定素材采样配置会在首帧前丢失 (`AndroidVideoPipeline.kt`) | `configureDiagnosticSampling` 只转发给已存在的 renderer，但诊断在首帧前下发配置，renderer 在首帧绘制时才创建导致配置丢失退化为默认三点 | Pipeline 增加不可变的 `DiagnosticSamplingConfig` 状态保存，在延迟创建 `SoftwareYuvRenderer` 时立即生效配置 |
| **[P2-10]** | 独立无 Surface 解码未复用解码配置 (`DecoderDiagnosticsActivity.kt`) | 直接调用 `createDecoderByType` 将 avcC 塞入 csd-0，软件测试可能取到硬件解码器样点，缺少 flexible-YUV 与 SPS/PPS Annex-B 转换 | 依据 `software` 偏好选择解码器组件，提取 SPS/PPS 并注入 Annex-B 起始码填入 csd-0/csd-1，输入流转 Annex-B，配置 `COLOR_FormatYUV420Flexible`，支持 EOS 排空与 Image 关闭 |
| **[P2-11]** | 原始取证失败仍可能报告 complete (`DecoderDiagnosticsActivity.kt`) | 取证异常吞并为空列表，仅凭截图和 trace 就判定 evidence complete | 记录具体失败原因 `rawYuvError` 与组件名，对要求三层取证的任务校验样点非空、覆盖全部参考 patch 且匹配 `captureFrameId`，缺漏时标记为 `partial` |

---

## 3. 核心修复说明与回退依据（实事求是描述）

| 模块 / 修复项 | 实施内容 | 预期车机效果 | 回退触发条件与方式 |
| :--- | :--- | :--- | :--- |
| **T00 断开死锁修复** | `AirPlaySession.kt` 在离开 `eventWriteLock` 临界区后才触发 `close()` | 解决断开重连时写锁与媒体关闭互相阻塞造成的死锁 | 若引发事件在连接退出期被吞没，可在 `close()` 入口复核 session 标志位 |
| **T06/T07 色彩纯决策器** | `VideoPlanResolver.kt` + `VideoPipelinePlan.kt`：建立解耦决策框架，优先硬解 SurfaceView；支持按需指定 Full/Limited 范围及色彩矩阵（默认传递原请求保持源 SPS 矩阵 6 可观测） | 为车机提供精确可控的渲染决策分支，打通端到端取证与候选注入链路，避免硬编码或盲目覆盖矩阵 | 可在诊断设置中指定强制软件/硬件 YUV 或指定色彩范围/矩阵 |
| **T04/T08 NV12 批量解包与样点取证** | `Yuv420Frame.kt` 支持奇数裁剪与色度坐标精确映射；当 `pixelStride == 2` 且行对齐时整行批量解包，并在固定素材诊断中打通“版本化参考→原始YUV→RGB截图”配对 | 修复奇数裁剪色度偏移，优化软件 YUV 逐像素读取开销，提供真实三层取证数据支撑 | 非对齐时自动降级为逐像素读取 |
| **T10/T11 延迟平滑与积压恢复** | `VideoBacklogRecovery.kt` 优先重置参考帧链（`reference_chain_reset`，不销毁重建解码器）；多帧批处理释放 | 突发卡顿后无需黑屏重启解码器，迅速恢复流畅跟手；积压超时达 200ms 时自动升级为 hard stall 重建解码器 | 恢复策略出现画面花屏时可降级为仅关键帧恢复 |
| **T12 节奏与帧率审计** | `VideoCadenceEvidence.kt` 512 帧环形缓冲区滚动统计 PTS 间隔与物理到达间隔 | 精准定位 30fps 根因在上游 iPhone 源端还是网络抖动 | 无侵入被动统计，无需回退 |
| **T13 触控时间线校正** | `InputCommandQueue.kt` 区分 `queueLockWaitNs` 与 `actionDurationNs`，保持 `phoneAcknowledged = "unknown"`，保留 `touchToPhotonMs = null` | 指标真实透明，杜绝将队列出队误报为手机已响应 | 严格符合数据契约 |
| **T15 生命周期代际管理** | `AndroidVideoPipeline.changeSurface` 代际自增与资源清理，未决 buffer 确保释放 | 杜绝旋转、切换窗口或重新进出时的渲染上下文泄露 | 异常时兜底 fallback 销毁旧上下文 |

---

## 4. 下一步建议（T16/T17 实车与固定素材闭环）
软件层门禁（T18）已全部通过并完成双包打包，可供车机安装与验证：
1. 将 `mobile-debug.apk` 或 `mobile-optimizedDebug.apk` 安装至吉利星瑞 2026 款昆仑实车。
2. 进行实车连接，使用“视频诊断”页面的色彩样点提取功能验证在真实源 SPS 矩阵 6 下各候选策略分支的渲染效果与发色表现。
3. 观察 20 轮插拔/重连测试，验证重连死锁已彻底消除。
