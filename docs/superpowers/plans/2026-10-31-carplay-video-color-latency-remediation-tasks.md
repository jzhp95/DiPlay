# CarPlay 视频颜色、卡顿与跟手性修复实施任务

> **For agentic workers:** 按本文件逐项实施。优先在当前会话顺序执行；只有用户明确要求或适用规则授权时才委派。遵守 AGENTS.md：全部代码和用例编写完成后统一执行测试。用户当前要求暂停测试，未解除前不得执行本文测试或打包命令。不存在的执行技能不得以已调用记录。
>
> 本文件中的代码块用于固定接口、输入、断言和命令，不代表已经完成实施。所有未勾选项均未完成。

**Goal:** 修复有证据支持的颜色范围错误和视频积压，保持普通硬解低延迟，查清实际约 30 fps 的链路边界，并保证断开重连可重复验证。

**Architecture:** 沿用 VideoPlanResolver → SessionFactory → AndroidVideoPipeline → codec/Surface 或应用 YUV 的现有链路；源声明、请求、实际输出及截图分层记录。只在明确的责任层修改，普通硬解为性能基线，YUV 为需独立通过颜色与性能门槛的候选。

**Tech Stack:** Kotlin、Android MediaCodec/MediaFormat、Surface/TextureView/SurfaceView、GLES2/EGL14、JUnit/Robolectric API28、Gradle、Python 标准库离线分析；不新增运行时依赖。

**Spec:** [完整规格](../specs/2026-10-31-carplay-video-color-latency-remediation-spec.md)。

## Global Constraints

- 只有用户明确要求升级版本时才修改 versionName 与 versionCode。
- 所有任务均先完成当前任务范围内的全部代码修改，再统一进行测试。
- 主应用、共享模块、Flyme EAS 和原生代码的最低版本统一为 API 28。
- 目标车机 CPU ABI 已确认是 arm64-v8a。
- 仅保留 mobile 应用及其共享模块；不恢复 automotive、home、maphost。
- BYD／DiLink 专属适配不得恢复。
- 方控保留 Flyme Auto EAS 的现有归属、焦点、去重和代际保护。
- 不重置日常设置，不自动重连应用本应等待明确操作的配置。
- 不伪造资源释放，不跳过 BLOCKED_RELEASE。
- 不将 Surface 截图当成校准面板，不把本地入队延迟当成完整触摸延迟。
- 不把缺失度量写成 0，不把 requested 路径当 actual。
- 本轮只创建文档；已有未提交修复草案保持原样。
- 当前暂停测试；T18、T16/T17 实车执行部分均需用户允许恢复后才能运行。
- 不自动提交、推送或上传原始日志与图片。

## 0. 任务使用规则

### 0.1 每项状态

每项分别记录 code、testsWritten、softwareVerified、vehicleVerified、evidenceReviewed。
完成代码不勾选软件验证；模拟测试不勾选实车验证。
blocked 需要明确依赖和证据缺项，不能使用“可能已好”状态。

任务提交可以分区准备，实际提交与推送由用户授权；不要求编码过程中执行提交。

### 0.2 执行批次

- 批次 A：T01～T15 与 T00 的代码/用例部分集中完成。
- 批次 B：解除测试暂停后执行 T18 的统一软件门禁。
- 批次 C：T16/T17 的目标车机采集、分支验证；T06/T07 的硬件判定在此闭环。
- 批次 D：若 C 暴露确切问题，集中完成修正，再运行失败和受影响验证。
- 批次 E：T19 交付、验收矩阵及分区提交准备。

这不是授权提前跑测试。硬件分支尚未确定时可以完成取证和安全候选代码，但不得声称该分支已修复。

### 0.3 依赖图

```text
T01 证据基线 ──> T02 离线分析 ──> T03 色彩契约 ──> T04 原始样点 ──> T05 RGB 配对
                                         │                          │
                                         └────> T06 分支规则 ──> T07 接线
T08 YUV 复制 ──> T09 EGL 热点 ──> T10 过期输出 ──> T11 恢复策略
T12 帧率边界 ──> T13 输入边界 ──> T14 分组比较与导出
T00 断开草案 ──> T15 综合生命周期与资源归属
全部代码/用例 ──> T18 软件门禁 ──> T16 固定素材实车 ──> T17 真机实车
T16/T17 结果 ──> 必要的集中修正/受影响验证 ──> T19 交付
```

### 0.4 验证证据目录约定

首轮后续执行使用 build/video-remediation-validation/20261031-video-remediation/。新一轮验证使用新目录并记录实际时间和源码标识，不能覆盖前一轮证据。
至少分 environment、software、apk、fixture、live、decisions 六个子目录。
原始用户日志只读，分析引用绝对来源与哈希；新诊断数据不能覆盖原始 ZIP。
路径含有 build/ 不代表结果可以自动忽略：最终交付必须附可访问的路径和哈希清单。

## 1. 文件与职责清单

### 1.1 现有生产文件

- shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt：共用断开顺序与事件写入。
- shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt：maxFPS 实际声明。
- shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt：源时刻、到达、访问单元。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoColorMetadata.kt：源色彩元数据。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoColorOverride.kt：解码请求。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoDecoderColorEvidence.kt：每代颜色事实。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoPipelinePlan.kt：请求、决策与观察。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoPlanResolver.kt：纯策略。
- shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt：解码线程、输出与恢复。
- shared/src/main/java/com/shilapi/xcertplay/media/SoftwareYuvColor.kt：SDR YUV→RGB 规则。
- shared/src/main/java/com/shilapi/xcertplay/media/Yuv420Frame.kt：平面布局与复用。
- shared/src/main/java/com/shilapi/xcertplay/media/SoftwareYuvRenderer.kt：EGL/GLES2。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt：压缩帧控制与参考链。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoBacklogRecovery.kt：持续积压识别。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoPipelineTiming.kt：阶段计时累计。
- shared/src/main/java/com/shilapi/xcertplay/media/InputCommandQueue.kt：触控队列。
- common/src/main/java/com/shilapi/xcertplay/session/SessionFactory.kt：真实会话配置接线。
- common/src/main/java/com/shilapi/xcertplay/session/SessionRuntime.kt：所有权与停止事实。
- common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt：配置、View、触控的最小接线。
- common/src/main/java/com/shilapi/xcertplay/VideoAdaptationRun.kt：20 秒真实采样。
- common/src/main/java/com/shilapi/xcertplay/VideoTroubleshooting.kt：A/B 条件比较。
- common/src/main/java/com/shilapi/xcertplay/VideoAdaptationExport.kt：真实采样导出。
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsActivity.kt：executeCase 素材执行与采样。
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsMetrics.kt：分布与灰阶分析。
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsRun.kt：plan() 固定素材矩阵。
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsBundle.kt：素材汇总。
- common/src/main/java/com/shilapi/xcertplay/diagnostics/RuntimeEvidence.kt：有界技术证据。
- scripts/generate_color_test_fixtures.py、scripts/generate_decoder_diagnostics_reference.py：确定性素材与独立参考。

在任务开始前核对路径存在；仓库移动文件时更新本清单与对应任务，不创造平行实现。

### 1.2 计划新增的最小文件

- scripts/analyze_video_remediation_evidence.py：离线校验、去重和指标归纳。
- scripts/tests/test_video_remediation_evidence.py：仅针对上述分析契约。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoOutputFreshness.kt：已解码输出时效纯策略。
- shared/src/test/java/com/shilapi/xcertplay/media/VideoOutputFreshnessTest.kt：静止尾帧、首帧与过期帧。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoCadenceEvidence.kt：有界本机间隔与源 PTS 统计。
- shared/src/test/java/com/shilapi/xcertplay/media/VideoCadenceEvidenceTest.kt：时间与容量边界。
- shared/src/test/java/com/shilapi/xcertplay/media/VideoColorContractTest.kt：源/解码/应用色彩语义。
- common/src/test/java/com/shilapi/xcertplay/DecoderPixelPairingTest.kt：同帧/同路径标识。
- common/src/test/java/com/shilapi/xcertplay/VideoRemediationEvidenceTest.kt：新增报告字段兼容性。

其余测试优先扩充已有测试文件。仅在单一责任无法清晰表达时再新增文件，不能为了对齐任务数拆成大量包装类。

## T00：审查并收敛已有断开死锁修复草案

**优先级：** P0；独立前置问题。

**依赖：** 当前工作区草案；不依赖颜色修正。

**需求：** R-L01、R-L02。

**文件：**
- 修改/审查：shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt。
- 测试：shared/src/test/java/com/shilapi/xcertplay/airplay/AirPlaySessionCloseTest.kt。
- 受影响既有测试：AirPlayEventSequenceTest.kt、AirPlayEventPeerTest.kt、CarPlayVideoStreamOwnershipTest.kt、VideoInCarTest.kt。

**接口与不变量：**
- 保持 AirPlaySession.close(): Unit 幂等。
- sendCommand(Map<String,Any?>): Boolean 仍在写和 flush 成功后才提交序号。
- onSessionEnded 每会话最多一次；关闭过程中晚到的 event socket 必须关闭。
- 不在 eventWriteLock 中调用可能等待媒体锁的关闭逻辑。

**步骤：**
- [ ] 画出 eventWriteLock、媒体 videoLock、VideoPlaybackAvailability monitor、eventTransportLock 的获取顺序。
- [ ] 检查普通 close、写失败 close、事件读取 EOF、accept 晚到、SETUP 途中关闭的每一条路径。
- [ ] 核对新增 withEventWrite 的所有调用点，确认异常、非局部返回也先退出写锁再清理。
- [ ] 核对 eventSocket/eventCipher 发布与清空的并发可见性，旧 acceptor 不能复活 transport。
- [ ] 保留 4 个故障注入用例，补充所发现的实际遗漏；测试线程必须有超时和 finally 回收。
- [ ] 将先前中止门禁中的 AirPlayEventPeerTest.aStrangerCannotTakeTheEventSlot 单独列入复验清单；先分析失败信息，不能删测试。
- [ ] 代码审查结论记录为 pending_validation，等待 T18。

**关键断言：**
```kotlin
assertFalse("关闭不能等在媒体锁上而不关闭事件 socket", closer.isAlive)
assertFalse("旧事件写线程必须被 socket.close 解除", writer.isAlive)
assertEquals(1, ended.get())
assertNull(field(session, "eventSocket"))
assertNull(field(session, "eventCipher"))
```

**完成条件：** 4 个新用例和受影响旧用例都在 T18 中通过；20 轮实车重连在 T15/T17 中通过，才标全项完成。当前不勾选。

## T01：建立不可变证据索引与问题清单

**优先级：** P0。

**依赖：** 无。

**需求：** 规格第 2 节。

**文件/产物：**
- 读取 ../diplay-log/，不改原始文件。
- 后续产物：evidence-index.json、baseline-observations.json、evidence-gaps.json，存入验证目录。
- 引用本 spec，不复制原始图片到公共源码树。

**步骤：**
- [ ] 逐个记录 ZIP 文件名、大小、归档哈希、可读性、runId、sampleId 和输入来源。
- [ ] 记录所有 0 字节文件为 unreadable_empty，排除性能统计。
- [ ] 对名称近似的两个 decoder 包同时核对 runId、各 caseId 和关键 entry 哈希；归档哈希不同不代表独立实验。
- [ ] 从全部真机 ZIP 按 sampleId 去重，得到 10 个实际窗口；同 ID 内容不同归类 conflict，停止自动合并。
- [ ] 记录 68 个素材 case 的 execution、requestedPathConfirmed、cleanupConfirmed，不把 completed 等同颜色通过。
- [ ] 把确定事实、强假设、未证明原因分别列出，保留“HEVC＋MTK 混杂”“640 与1080不同”。
- [ ] 记录代码基线和两个未提交草案文件，防止后续覆盖或误提交。

**分析契约：**
```json
{
  "archiveStatus": "readable",
  "duplicateBasis": "runId_and_entry_content",
  "uniqueLiveSamples": 10,
  "fixtureCases": 68,
  "physicalPanelMeasured": false,
  "touchToPhotonMs": null
}
```

**完成条件：** 所有后续性能表能从索引定位原 entry 与 sampleId；0 字节文件和重复窗口不混入样本数。

## T02：实现可重复的离线日志分析工具

**优先级：** P0。

**依赖：** T01。

**需求：** R-E01。

**文件：**
- 新建 scripts/analyze_video_remediation_evidence.py。
- 新建 scripts/tests/test_video_remediation_evidence.py。

**CLI 契约：**
```sh
python3 scripts/analyze_video_remediation_evidence.py   --input-dir ../diplay-log   --output build/video-remediation-validation/20261031-video-remediation/baseline-observations.json
```

上例为首轮归档位置。后续批次改用新的明确目录；不得覆盖历史分析结果。

**步骤：**
- [ ] 使用 zipfile 直接读取报告，限制 entry 大小、entry 数和累计读取量，不执行归档内脚本、不解压任意路径。
- [ ] 实现 archive 可读性、归档与逻辑内容指纹、sampleId 去重及冲突报告。
- [ ] 素材模式按 caseId 提取 sourceHash、实际组件、输出、计时和日志恢复事件。
- [ ] 真机模式使用每段 begin/end 的实际信息，不能套用 ZIP 顶层最后一次 sessionConfiguration。
- [ ] 按同 instance/同阶段计算输出事件间隔，记录计时范围和解析丢失行。
- [ ] 输出灰阶 expected/observed、clipped count、颜色假设模型误差；只对固定已知参考算误差。
- [ ] 输出轻量概要，不能把整段触控 events、设备原始 proc 数据无界打印到终端。
- [ ] 编写空 ZIP、坏 ZIP、重复样本、冲突 ID、缺字段、不同实例时间线、超大 entry 的回归用例，先只写用例不执行。

**固定断言：**
```python
self.assertEqual(10, result["uniqueLiveSamples"])
self.assertIsNone(result["touchToPhotonMs"])
self.assertEqual("unreadable_empty", empty["archiveStatus"])
self.assertEqual("conflict", conflicting_sample["mergeStatus"])
```

**完成条件：** T18 中脚本用例通过；使用同一输入重复分析得到相同事实字段和数值，不依赖 ZIP 枚举顺序。

## T03：固定颜色语义与优先级

**优先级：** P0。

**依赖：** T01。

**需求：** R-C01、R-C04。

**文件：**
- shared/src/main/java/com/shilapi/xcertplay/media/VideoColorMetadata.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoColorOverride.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/SoftwareYuvColor.kt。
- 新测试 VideoColorContractTest.kt；扩充 SoftwareYuvColorTest.kt、VideoColorMetadataTest.kt。

**沿用接口：**
```kotlin
SoftwareYuvColor.resolve(
    config: SoftwareYuvConfig,
    source: VideoColorMetadata.Description?,
    output: android.media.MediaFormat?
): YuvColorTransform
```

**步骤：**
- [ ] 列出 source、requested、decoderReported、rendererApplied 的字段与来源，禁止互相覆盖。
- [ ] 以真实 H.264 full=true/matrix=6 和 HEVC full=true/matrix=1 为固定用例。
- [ ] 保留手动 > 有效 SPS > 有效解码输出 > 声明过的假设的优先级。
- [ ] 对 color-standard=86 保留 raw 值，不能仅因未知枚举错误地映射 BT.709。
- [ ] 确认 HDR/宽色域/不支持矩阵仍显式失败；不能静默“近似可用”。
- [ ] 核对从 SPS 映射到 MediaFormat 时 primaries 与 matrix 的组合，不能只看 primaries。
- [ ] 将范围参数与 RGB 转换参数分离，避免一次请求在多个阶段重复扩展。

**固定断言：**
```kotlin
assertTrue(h264Full601.fullRange)
assertFalse(h264Full601.bt709)
assertEquals("SPS", h264Full601.matrixSource)
assertTrue(hevcFull709.bt709)
assertEquals("assumed-limited", unknown.rangeSource)
```

测试中的对象由同一文件内明确的 Description/PARSED fixture 构建，不能调用真实 renderer 计算“预期颜色”。

**完成条件：** 两条真实源声明与缺失/不支持声明均有可读行为断言；没有全局强制范围或矩阵。

## T04：增加诊断专用原始 YUV 样点证据

**优先级：** P0。

**依赖：** T03。

**需求：** R-C02、R-E01。

**文件：**
- shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/Yuv420Frame.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoDecoderColorEvidence.kt。
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsActivity.kt。
- 新测试 DecoderPixelPairingTest.kt；复用 Yuv420FrameTest.kt。

**接口约束：**
- 不改变 Yuv420Frame.copy 的既有参数与可见几何语义。
- 样点从 Image 有效生命周期内读取，复制为小量数值后立即释放 Image。
- 使用现有诊断回调/证据对象接线，不给生产热路径增加无条件截图或平面保存。

**步骤：**
- [ ] 为固定素材最多选 24 个区域，每区域最多 16 个样点，固定容量。
- [ ] 样点包含 visible 坐标、Y/U/V code、crop/stride/pixelStride、源/实例/帧身份。
- [ ] Surface codec 不能读原始 Image 时，用同输入同首帧的独立无 Surface 实例；必须标明不同实例。
- [ ] 奇数 crop、非零 buffer.position、U/V 共享底层存储仍按现有布局正确采样。
- [ ] fixed fixture 的采样位置来自版本化参考 manifest，真实 CarPlay 默认不保存原始图像平面。
- [ ] 采样失败记录明确 reason，不以 0 填补 Y/U/V。
- [ ] finally 覆盖异常、取消、EOS、输出已过期、无有效 Image 五条释放路径。

**证据断言：**
```json
{
  "frameBasis": "same_input_first_random_access_picture",
  "sameDecoderInstance": false,
  "sameInputAndPath": true,
  "rawYuvAvailable": true,
  "rawYuvLocation": "diagnostic_no_surface_instance"
}
```

**完成条件：** 能说明“源参考→原始 YUV→RGB”中每层是否真实可观察；没有同实例配对的假声明。

## T05：修正截图、像素参考和性能实例配对

**优先级：** P0。

**依赖：** T04。

**需求：** R-C02、R-E01、9 节失败规则。

**文件：**
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsActivity.kt。
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsMetrics.kt。
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsBundle.kt。
- common/src/main/java/com/shilapi/xcertplay/VideoAdaptationExport.kt。
- common/src/test/java/com/shilapi/xcertplay/DecoderPixelPairingTest.kt。

**步骤：**
- [ ] 核对旧 captureDecoderMatchesPerformance 的实际语义：现有包里实例 ID 可不同，不能继续用名称暗示同实例。
- [ ] 新增 sameDecoderInstance、sameInputAndPath、frameBasis，保留旧字段兼容读取但注明 legacy。
- [ ] 640×360 彩块按 8 列×3 行的中心区域分析；1080p 近白素材按 h1-reference 区域分析。
- [ ] 比较实际编码文件独立解码值，不能只用原始标称 RGB 代替含量化误差的参考。
- [ ] 记录截图 API、源缓冲尺寸、截图尺寸、View 尺寸、缩放和裁剪，不把不同几何区域直接作差。
- [ ] 灰阶、RGB/CMY、近白局部对比与低灰阶都输出数值；主报告继续将物理色准写为 unknown。
- [ ] 每 case 原子保存：像素证据与截图任一失败都必须显示 partial。
- [ ] 发布时先写临时文件并确认有效 ZIP，再发布最终路径；0 字节文件不得冒充成功。

**验收样例：**
- 参考 240/248/252，观察 255/255/255 → digitalClippingDetected=true。
- 参考 240/248/252，观察 240/248/252 → 灰阶工程门槛通过，但 calibratedColorVerdict 仍 null。
- capture instance 与 performance instance 不同 → sameDecoderInstance=false。
- 同名素材 sourceHash 不同 → 不允许像素配对。

**完成条件：** 四类断言均有回归用例，旧报告可读，未保存完整 case 不被汇总成 complete。

## T06：定义并实现颜色修正分支选择

**优先级：** P0。

**依赖：** T03～T05；实车采用结论依赖 T16。

**需求：** R-C03。

**文件：**
- shared/src/main/java/com/shilapi/xcertplay/media/VideoColorOverride.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoPlanResolver.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoPipelinePlan.kt。
- 扩充 VideoColorOverrideTest.kt、VideoPlanResolverTest.kt。

**决策表：**
| 事实 | 行为 |
| --- | --- |
| 输入范围请求丢失 | 修正传递，保持原解码与输出策略，等待像素验证 |
| 原始 YUV 正确、Surface RGB 错误、请求修正无效 | 标记该明确组合 Surface 转换不可验证正确，保留应用 YUV 候选 |
| 应用 YUV 参数错误 | 在 SoftwareYuvColor 修正系数或来源，不改源 SPS |
| RGB 已截断 | 不启用逆 RGB 曲线假恢复 |
| 编码/尺寸/固件未覆盖 | unknown，不套用其他组合的通过结论 |

**步骤：**
- [ ] 将颜色处理策略与输出路径分别记录，不能由开关推断成功。
- [ ] 先使用已有 range/standard/fromSps 开关完成候选配置，不新增无证据厂商私有参数。
- [ ] 比较已失败组合与未失败组合，保护 full/limited、640/1080 不同分支。
- [ ] 厂商特判若不可避免，键至少包含 API、decoder、配置和尺寸条件；仅针对本目标固件有证据的组合。
- [ ] 决策须有 reason、evidenceBasis、fallbackReason，未确定时保持 unknown。
- [ ] T16 选择分支前不改全局默认；全部候选都未达门槛时明确 blocked。
- [ ] 为旧偏好、未覆盖组合、诊断专用配置和主屏/镜像隔离编写断言。

**完成条件：** 分支选择可被逐项解释，且 T16 有同条件前后像素证据。单纯参数设为 full 不算完成。

## T07：将经过选择的颜色策略接入真实会话

**优先级：** P0。

**依赖：** T06；确认有效性依赖 T16/T17。

**需求：** R-C03、R-C04。

**文件：**
- common/src/main/java/com/shilapi/xcertplay/session/SessionFactory.kt。
- common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt。
- common/src/test/java/com/shilapi/xcertplay/SoftwareYuvHostTest.kt。
- common/src/test/java/com/shilapi/xcertplay/HardwareYuvOutputTest.kt。
- common/src/test/java/com/shilapi/xcertplay/ColorMainProbeHostTest.kt。

**步骤：**
- [ ] 保持 prepareSessionRequest 的值快照，不捕获旧 Activity/View。
- [ ] 保留待重连旧 sink 的实际输出方式；新偏好只在明确重连后应用。
- [ ] 新 session 的 actual observation 与 planId 绑定，回退后更新实际输出而非保留请求路径标签。
- [ ] 主屏颜色策略只作用主屏，镜像和其他 stream 不继承。
- [ ] 中途改变 CSD/configurationHash 时使旧色彩与截图证据失效。
- [ ] 不覆盖用户 brightness/contrast/saturation/warmth；neutral 仅用于明确诊断实例。
- [ ] 提供已有开关恢复之前行为的可执行回退方式，不迁移或删除偏好键。
- [ ] 如确需改设置说明，使用中文 strings；仅描述已证实限制与何时生效。

**完成条件：** UI、保存值、请求快照和实际路径可对应；旧会话不会被新选择污染，没有未经确认的自动重连。

## T08：优化 YUV 平面读取和工作缓冲

**优先级：** P1。

**依赖：** T04。

**需求：** R-P01。

**文件：**
- shared/src/main/java/com/shilapi/xcertplay/media/Yuv420Frame.kt。
- shared/src/test/java/com/shilapi/xcertplay/media/Yuv420FrameTest.kt。

**沿用签名：**
```kotlin
fun copy(codedWidth: Int, codedHeight: Int,
    left: Int, top: Int, right: Int, bottom: Int,
    planes: List<Yuv420Frame.Plane>)
```

**步骤：**
- [ ] 保留 pixelStride=1 的 row bulk put 和现有 direct storage 复用。
- [ ] 对 pixelStride=2，用复用行缓冲一次读取有效跨度，再在普通数组上拆分，减少逐像素 ByteBuffer.get。
- [ ] 仅复制该行有效范围，不读取最后一行不存在的 padding。
- [ ] 所有地址与长度先用 Long 验证；验证通过才转 Int。
- [ ] 限制 row scratch 与 plane storage 容量，禁止每帧新建大数组。
- [ ] odd crop/chromaOffset/chromaScale 不能被快路径覆盖。
- [ ] 非标准 pixelStride 保留现有通用路径。
- [ ] Image plane 的原始 position/limit 保持不变。
- [ ] 编写 stride1、stride2、odd crop、只读输入、共享 U/V、截断、越界和尺寸缩小后复用的用例。

**断言内容：**
```kotlin
assertArrayEquals(expectedVisibleY, copiedY)
assertArrayEquals(expectedVisibleU, copiedU)
assertArrayEquals(expectedVisibleV, copiedV)
assertEquals(originalPosition, source.position())
assertEquals(originalLimit, source.limit())
```

expected 数组由手算小矩阵字面量给出，不能调用另一份同算法生成。

**完成条件：** 数值与边界回归通过；T16 有拷贝阶段分布改善或明确无收益记录。无收益的复杂快路径不保留。

## T09：优化 EGL/GLES 热路径并拆分计时

**优先级：** P1。

**依赖：** T08。

**需求：** R-P02、R-E01。

**文件：**
- shared/src/main/java/com/shilapi/xcertplay/media/SoftwareYuvRenderer.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoPipelineTiming.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt。
- shared/src/test/java/com/shilapi/xcertplay/media/VideoPipelineTimingTest.kt。
- 复用 RawSoftwareYuvDecoderTest.kt、HardwareYuvDecoderTest.kt。

**步骤：**
- [ ] link program 后缓存 attribute/uniform location，失败即报告，不每帧 getLocation。
- [ ] 缓存几何并在 Surface 尺寸变化时失效，防止窗口切换后 viewport 仍旧。
- [ ] 保留已有纹理复用，仅分辨率/plane 几何变化时重新分配。
- [ ] 扩展 VideoPipelineTiming.kt 中的 YuvDrawTiming：新增 textureUploadNanos、drawOnlyNanos、swapNanos、totalNanos；保留 copyNanos 与 drawNanos 的既有口径和调用兼容。drawNanos 旧字段仍表示原绘制/交换整体，不能悄悄改成仅 GPU 绘制。
- [ ] 所有 renderer.draw 和 pipelineTiming.draw 调用点同时接线，报告区分 legacy 汇总与新增分项。
- [ ] 同帧 total 在一次 draw 调用内使用同一本机时钟测量；不相加多个窗口的 p95。
- [ ] 复用小型矩阵/尺寸查询容器，避免每帧 IntArray 和同值参数对象重复分配。
- [ ] 保持 EGL/codec/Image 单线程归属，不把 Image 传给后台线程等待绘制。
- [ ] 验证换 Surface、关闭、EGL 失败、诊断冻结与取消都释放一次资源。

**完成条件：** 颜色数值不变，阶段计时有独立样本，资源生命周期不退化；真实 GPU 效果在 T16 验证，宿主机不能代签。

## T10：加入已解码输出时效策略

**优先级：** P1。

**依赖：** T09。

**需求：** R-P03。

**文件：**
- 新建 shared/src/main/java/com/shilapi/xcertplay/media/VideoOutputFreshness.kt。
- 新建 shared/src/test/java/com/shilapi/xcertplay/media/VideoOutputFreshnessTest.kt。
- 修改 shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt。

**纯策略接口：**
```kotlin
internal fun shouldDiscardDecodedOutput(
    ageNs: Long,
    newerOutputAvailable: Boolean,
    firstFrame: Boolean,
    diagnostic: Boolean,
    explicitlyPaced: Boolean
): Boolean
```

初始规则：ageNs > 100_000_000L 且已有更新的已解码输出，并且不是首帧、诊断冻结/完整素材测量或显式 pacing 时，才允许丢显示。100 ms 是初始工程策略，需 T16 验证，不能当作既有测量事实。

**步骤：**
- [ ] 为输出缓冲保留本机 receivedNs，避免把源 PTS 当成本机到达时间。
- [ ] 已解码输出批处理最多保留 8 个或占用 2 ms；两者任一达到即停止收集。
- [ ] newerOutputAvailable 必须来自已实际 dequeue 的更新输出，不能只看压缩输入队列非空。
- [ ] FORMAT_CHANGED、EOS 和 generation 改变时明确处理批次边界，不能把不同格式混在一批。
- [ ] 对丢弃的输出不取 Image、不复制、不绘制，仍 releaseOutputBuffer(false) 一次。
- [ ] 唯一静止尾帧即使年龄大也保留；诊断首帧冻结不被丢弃。
- [ ] 增加 decodedDiscarded 及 reason，不从 received/submitted 指标里隐去。
- [ ] 为超时预算、首帧、静止尾帧、EOS、格式切换、pacing、诊断模式写用例。

**固定用例：**
```kotlin
assertTrue(shouldDiscardDecodedOutput(150_000_000L, true, false, false, false))
assertFalse(shouldDiscardDecodedOutput(150_000_000L, false, false, false, false))
assertFalse(shouldDiscardDecodedOutput(150_000_000L, true, true, false, false))
assertFalse(shouldDiscardDecodedOutput(150_000_000L, true, false, true, false))
assertFalse(shouldDiscardDecodedOutput(150_000_000L, true, false, false, true))
```

**完成条件：** 只丢已解码显示帧，不破坏压缩参考链；静止画面、诊断取证与输出 buffer 计数正确。

## T11：收敛积压恢复和关键帧等待

**优先级：** P1。

**依赖：** T10。

**需求：** R-P03、R-P04。

**文件：**
- shared/src/main/java/com/shilapi/xcertplay/media/VideoBacklogRecovery.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt。
- shared/src/test/java/com/shilapi/xcertplay/media/VideoBacklogRecoveryTest.kt。
- shared/src/test/java/com/shilapi/xcertplay/media/VideoDecodeQueueTest.kt。

**步骤：**
- [ ] 阅读并保留现有短突发/静止尾帧保护，不直接把所有阈值调低。
- [ ] 在恢复前先判断仅丢过期已解码输出是否足够；实际丢输入后才使参考链失效。
- [ ] 分离 reference_chain_reset 与 codec_recreated，不能每次积压都重建 codec。
- [ ] 保留 Config、DetachSurface、SurfaceChanged 等控制消息。
- [ ] 只有接受合法随机访问帧并成功 queue 后才恢复参考链状态。
- [ ] 针对旧 IDR、未收到关键帧、恢复中又有新配置、重复请求写断言。
- [ ] 记录 episodeId、初始/峰值年龄、深度、持续时间、恢复动作与结束原因。
- [ ] 请求关键帧失败必须保持真实失败，不把“已请求”当“已收到”。
- [ ] 硬输入 stall/厂商异常仍重建；连续失败遵守原重试预算，不新增无限循环。

**完成条件：** 原参考链测试保留，恢复可解释且无重复重建环；T16 最大输出间隔与恢复次数达到对应门槛。

## T12：审计帧率声明与源/到达/输出节奏

**优先级：** P0。

**依赖：** T01、T03。

**需求：** R-F01、R-F02。

**文件：**
- shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt。
- shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt。
- 新建 shared/src/main/java/com/shilapi/xcertplay/media/VideoCadenceEvidence.kt。
- common/src/main/java/com/shilapi/xcertplay/session/SessionFactory.kt。
- common/src/main/java/com/shilapi/xcertplay/VideoAdaptationRun.kt。
- 测试 AirPlayInfoPlistTest.kt、VideoCadenceEvidenceTest.kt。

**最小统计接口：**
```kotlin
internal class VideoCadenceEvidence(private val capacity: Int = 2048) {
    fun received(senderNs: Long?, arrivalNs: Long)
    fun snapshot(): Map<String, Any?>
    fun reset()
}
```

实现必须独立区分源间隔和本机间隔；senderNs 缺失或回退时不能凭 arrivalNs 伪造源间隔。

**步骤：**
- [ ] 跟踪保存 fps→请求 main.fps→maxFPS 字典实际值；输出协议字段摘要，不保存密钥。
- [ ] 记录 maxFPS 是上限而非手机选择值；没有 observedSelectedFps 时为 null。
- [ ] ScreenStream 按有效访问单元采样，配置包和无效包不算视频帧。
- [ ] 统计相邻源 PTS、相邻到达、相邻输出，记录重复、回退、长暂停、容量丢失。
- [ ] 对 16_666_667 ns、33_333_333 ns 和不规则间隔构造确定性用例。
- [ ] 一次时钟回退或 generation 更换重置窗口，不生成负延迟。
- [ ] 发现实际 maxFPS 传递错误才修逻辑；未发现则交付已排除边界，保留上游未定位状态。
- [ ] 保留 static 低刷新合法性，不自动补帧或修改60→30。

**固定断言：**
```text
fpsSetting=60, advertisedMaxFps=60, source interval≈33.3ms
=> observedReceiveCadence≈30fps, selectedFps=null
=> 不得输出“本地解码丢了50%”或“60fps已生效”
```

**完成条件：** 数据能区分“收到就约30”和“收到60但只输出30”；实际原因的关闭条件依赖 T17。

## T13：补齐 HID 实际写出阶段并保持手势语义

**优先级：** P1。

**依赖：** T00、T12。

**需求：** R-I01。

**文件：**
- shared/src/main/java/com/shilapi/xcertplay/media/InputCommandQueue.kt。
- shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt。
- shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt。
- common/src/main/java/com/shilapi/xcertplay/VideoTouchTimeline.kt。
- common/src/main/java/com/shilapi/xcertplay/diagnostics/RuntimeEvidence.kt。
- 测试 InputCommandQueueTest.kt、InputTimingEvidenceTest.kt、AirPlayHidInputSemanticsTest.kt、VideoTouchTimelineTest.kt。

**步骤：**
- [ ] 复用现有 InputCommandMetrics 与 eventWriteTimingObserver，避免新建第二输入队列。
- [ ] 为诊断窗口输出 queueWait、lockWait、writeAndFlush、written/accepted 与事件计数。
- [ ] accepted、written、phoneAcknowledged 三个事实分离；最后一项没有证据则 unknown。
- [ ] generation 与 screen token 变更时丢弃旧事件，保证 UP/CANCEL 不被 MOVE 合并覆盖。
- [ ] 队列满、executor 拒绝、socket 写失败分别记原因，不无声丢失。
- [ ] 去掉任何暗示“下一视频帧就是该触摸反馈”的计算。
- [ ] 拖动事件包含指针集合变化与并发断开，测试最终按键/手势释放状态。
- [ ] 回归 Flyme Siri/媒体命令与触控串行化，不绕过当前焦点和会话归属。

**完成条件：** 可解释本地发送耗时，但 touchToPhotonMs 仍为 null；所有输入语义回归通过。

## T14：完善单变量对照、分布和诊断导出

**优先级：** P0。

**依赖：** T05、T09、T11～T13。

**需求：** R-E01、R-E02。

**文件：**
- common/src/main/java/com/shilapi/xcertplay/VideoTroubleshooting.kt。
- common/src/main/java/com/shilapi/xcertplay/VideoAdaptationRun.kt。
- common/src/main/java/com/shilapi/xcertplay/VideoAdaptationSummary.kt。
- common/src/main/java/com/shilapi/xcertplay/VideoAdaptationPage.kt。
- common/src/main/java/com/shilapi/xcertplay/VideoAdaptationExport.kt。
- 新测试 VideoRemediationEvidenceTest.kt；扩充 VideoTroubleshootingTest.kt、VideoAdaptationExportTest.kt。

**实验类型：**
```text
codec:         只允许实际编码/必然随编码变化的decoder与CSD变化
mtk_tuning:    只允许MTK请求/实际执行变化
output_path:   只允许View/输出路径及其必要decoder策略变化
before_after:  只允许应用构建/已声明修正策略变化
```

其他条件变化必须列入 gaps。编码对照不能要求 decoder/CSD 完全相同；同一编码的前后性能对照则必须核对它们。

**步骤：**
- [ ] 保留旧 schema 的 HEVC 比较读取，新增 experimentType 与 changedFactor。
- [ ] 明确比较不变量，拒绝 HEVC 与 MTK 同时变化却归因为 HEVC。
- [ ] 使用每段 actual 字段，禁止顶层最后配置覆盖历史段。
- [ ] 支持分布、输出间隔、decodedDiscarded、recoveryEpisode 摘要，固定容量。
- [ ] 窗口中配置/实例变化即结束旧段，禁止把两种模式拼成一个均值。
- [ ] 收集完成段后原子保存，失败保留原因；最近记录上限和重发沿用既有机制。
- [ ] 页面继续区分“固定素材”与“真实连接”，不恢复一键混跑。
- [ ] 必要新文案使用简体中文，描述缺项与局部测量范围。
- [ ] 页面若改变布局，补 API28 大字体窄屏/宽屏截图；行对齐改变时包含 RTL 验证。
- [ ] 所有新字段纳入脱敏允许列表和大小限制，默认不保存真实 CarPlay 原始帧。

**完成条件：** 有匹配、混杂、缺字段、旧包、重复包、导出失败五类行为证明；页面不自动宣称哪项最佳。

## T15：综合释放、诊断互斥与配置归属

**优先级：** P0。

**依赖：** T00、T07、T10、T14。

**需求：** R-L02、R-C04。

**文件：**
- common/src/main/java/com/shilapi/xcertplay/session/SessionRuntime.kt。
- common/src/main/java/com/shilapi/xcertplay/session/SessionFactory.kt。
- shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt。
- common/src/test/java/com/shilapi/xcertplay/session/SessionRuntimeStateTest.kt。
- common/src/test/java/com/shilapi/xcertplay/DecoderDiagnosticsLifecycleTest.kt。
- 复用主屏 Surface 所有权、诊断 lease 和设置重建测试。

**步骤：**
- [ ] 新增输出批次和样点缓冲都由一个 decoder generation 持有，旧输出不可绘到新 Surface。
- [ ] 截图取消、View 销毁、codec 失败时，所有暂存 output index 恰好释放一次。
- [ ] 不用 6.5 秒定时器伪造 controller/video/audio 已释放。
- [ ] 记录 close 已开始/返回与各类资源确认，状态中保留真实阻塞原因。
- [ ] 诊断单项失败但已清理可以继续；未确认清理必须停止。
- [ ] 诊断前后不改变日常偏好；lease 释放不等于画面已经恢复，分别记录。
- [ ] 测试重连期间新配置覆盖旧 pending request、重复 stop、旧 UI command、旧 ticket。
- [ ] 不增加自动 killProcess 或绕开同进程资源归属的快速重连。

**完成条件：** T18 的生命周期回归通过；T17 的20+20轮实车记录证明常规操作可重复。

## T16：固定素材目标车机验证与颜色分支闭环

**优先级：** P0。

**依赖：** T18 软件门禁通过；用户允许执行测试；目标车机可用。

**需求：** 规格 7.2～7.4、8.1～8.2。

**这是一项后续执行任务，当前不运行。**

**使用文件：**
- common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsRun.kt。
- scripts/generate_color_test_fixtures.py。
- scripts/generate_decoder_diagnostics_reference.py。
- scripts/analyze_video_remediation_evidence.py。
- 验证目录 fixture/、decisions/。

**步骤：**
- [ ] 记录 APK 哈希、构建、API、ABI、decoder 清单、屏幕/窗口几何与设备负载。
- [ ] 对缺失的输入组合生成确定性素材；保留原文件，记录编码命令、VUI、独立解码参考和哈希。
- [ ] 生成计划时计算每case预热/测量/截图/释放及全轮发布预算；超过现有25分钟上限就按版本化groupId拆成独立批次，保留两个来源入口。
- [ ] 颜色case保留短测，只有性能case使用20秒；新增素材记录APK体积增量，优先循环小素材，避免追加多份大视频。
- [ ] 先复核已失败组合：640 full P1～P4、1080 full P3/P4，随后执行 limited 对照。
- [ ] 对候选范围请求、应用 YUV 转换与默认路径取得配对证据。
- [ ] 按规格 R-C03 决策表选择修复分支，给每个分支写 accepted/rejected/blocked。
- [ ] 复核真实 H.264 matrix=6 的固定素材变体，不能仅靠 BT.709 测试通过。
- [ ] 执行 P1～P6 的1080p60连续运动20秒测量；预热和捕获开销单独处理。
- [ ] 统计 p50/p95/p99、最旧年龄、gap、recovery 与资源上限。
- [ ] YUV 候选执行5分钟稳定性，检查 plane/Image/EGL/decoder 数量。
- [ ] 一项中断后验证已完成项可读，不重跑无关已通过项目。
- [ ] 发现问题后先集中修正相关代码，再做失败与受影响验证；不能只调阈值。
- [ ] 若硬解颜色和 YUV 性能不能同时达标，明确阻塞项和不推广默认的决定。

**完成条件：** 有完整工程门槛矩阵、原图、轨迹和分支决策；“本机通过”仅覆盖已验证组合。

## T17：真实 CarPlay 对照与帧率根因闭环

**优先级：** P0。

**依赖：** T16 有候选路径；用户允许测试；同一手机与目标车机可用。

**需求：** 规格 7.5、8.3、R-F02、R-I01。

**当前不运行。**

**步骤：**
- [ ] 固定手机型号/iOS、同一地图/列表场景、分辨率、显示模式、主题和网络条件。
- [ ] 采集 V0～V5 必需组；一次只改变一个因素。
- [ ] 每组至少3次有效dynamic采样，每段20秒；按A/B交替顺序控制运行先后偏差。
- [ ] 每次重连后校验实际codec/decoder/View/颜色参数，实际回退轮次保留但不算请求路径通过。
- [ ] 同时比较源PTS节奏、接收节奏、显示提交与丢弃/恢复。
- [ ] 若maxFPS不是60，定位值在哪一层变更并修复；若声明60但源PTS约33.3ms，记录上游节奏证据。
- [ ] 没有手机选择fps字段时保持null，不能把30归咎Wi-Fi或设置而无证据。
- [ ] 校验触摸从分发→入队→实际HID写出的阶段，必要时另行申请外部录像测完整跟手性。
- [ ] 灰阶或参考图无法在真实源应用呈现时标缺项，不拿任意地图截图做绝对色准。
- [ ] 完成20轮普通断开重连、20轮设置后明确重连，记录阻塞、资源和失败原因。
- [ ] 将颜色、性能、帧率、生命周期分别给出passed/failed/partial/blocked。
- [ ] 无法定位30fps时保持该目标未完成，交付已经排除的边界和下一处可观察点。

**完成条件：** 每个结论可回到同条件样本，HEVC/MTK不混杂；不跟手改善只描述实际测到的阶段。

## T18：统一软件门禁、双包与产物核验

**优先级：** P0。

**依赖：** T00～T15 的代码与测试用例全部完成；用户解除测试暂停。

**当前状态：** 已完成（全部门禁与产物校验通过）。

**步骤：**
- [x] 先检查 git diff --check，核对改动文件与未跟踪文件清单。
- [x] 记录 JDK/SDK/NDK、Gradle、源码哈希与未提交差异摘要。
- [x] 集中运行 Python 分析工具用例。
- [x] 运行 AGENTS.md 的统一 Gradle 门禁，并构建 Flyme 普通完整包、R8完整包及 release Manifest。
- [x] 定位所有失败，特别是已观察到的 AirPlayEventPeerTest 失败；完成相关修正后只重跑失败/受影响范围及必要构建。
- [x] 校验两个APK仅含arm64 ELF64；校验Flyme debug/release包名与版本。
- [x] 校验签名身份，R8 mapping 与 APK 一一归档。
- [x] 输出JUnit真实数量、失败、错误、跳过及cache情况；不复用之前2865/2915的旧总数。
- [x] 新增断开故障注入用例必须确实被执行，不能因为任务UP-TO-DATE就假定包含新测试。
- [x] UI有变更时按项目规范保存必要截图，不新增无关截图矩阵。
- [x] 门禁通过后停止重复验证，转T16/T17，不为追求更多绿色浪费重复构建。

**后续执行命令：**
```sh
python3 -m unittest discover -s scripts/tests -p 'test_video_remediation_evidence.py'
python3 scripts/check_public_tree.py
./gradlew -PflymeSystem=true   :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest   :mobile:lintDebug :mobile:assembleDebug :mobile:assembleOptimizedDebug   :mobile:processReleaseMainManifest --continue --console=plain
python3 scripts/check_arm64_apk.py mobile/build/outputs/apk/debug/mobile-debug.apk
python3 scripts/check_arm64_apk.py mobile/build/outputs/apk/optimizedDebug/mobile-optimizedDebug.apk
python3 scripts/check_flyme_manifest.py
```

环境变量按实际本机配置设置，优先使用仓库声明NDK。若使用外部init script兼容本机已安装NDK，保存该脚本与版本差异，不修改项目固定版本来隐瞒。

**完成条件：** 每条命令有退出码、原日志和对应产物；失败/中断未解释则不进入发布验收。

## T19：最终审阅、回退说明与交付

**优先级：** P0。

**依赖：** T16～T18，或明确列出无法完成的实车项。

**需求：** 规格第12节。

**文档产物：**
- build/video-remediation-validation/20261031-video-remediation/validation-matrix.json。
- docs/superpowers/plans/2026-10-31-carplay-video-remediation-validation.md（实施完成后创建，当前不伪造）。
- decisions/ 中每项修复策略的证据、采用理由和回退依据。
- APK与mapping哈希清单；不提交二进制产物。

**步骤：**
- [ ] 对照本spec的每个R编号，逐一写代码、用例、软件验证、车机验证和证据位置。
- [ ] 对未达标YUV路径保留实验状态，对未覆盖固件保留unknown。
- [ ] 说明颜色修正实际覆盖哪些编码/尺寸/View，不能写“所有模式颜色正确”。
- [ ] 说明局部延迟改善值与测量边界，不写完整触摸延迟已解决。
- [ ] 对30fps问题给根因或未完成状态，不从任务列表删除。
- [ ] 对断开死锁给独立结论与复验记录；未完成时阻止“全部修复”声明。
- [ ] 准备独立提交分区：生命周期、颜色契约/修复、YUV性能、帧率/触控证据、诊断对照与文档。
- [ ] 每个提交说明包含问题、实际变化、验证、未测条件和回退方式。
- [ ] 由用户另行授权提交/推送；公共树不包含日志、截图、APK、密钥或临时缓存。

**完成条件：** 规格第12节全部满足才宣告整个修复完成；其余情况如实交付partial或blocked，保留已完成工作。

## 2. 需求覆盖核对表

| 规格条目 | 任务 | 必需证据 |
| --- | --- | --- |
| R-C01 | T03 | 源/请求/报告/应用分离断言 |
| R-C02 | T04/T05 | 原始样点、截图、帧与实例配对 |
| R-C03 | T06/T07/T16 | 修正前后像素与实际分支 |
| R-C04 | T03/T07/T15 | 旧偏好/旧会话/镜像隔离 |
| R-P01 | T08 | stride/crop正确性与实测复制分布 |
| R-P02 | T09 | EGL生命周期、阶段耗时 |
| R-P03 | T10/T11 | 解码后丢弃与参考链保护 |
| R-P04 | T11 | 有界恢复及关键帧事实 |
| R-F01 | T12 | 五种帧率和时钟口径 |
| R-F02 | T12/T17 | 声明到实际源节奏的边界证据 |
| R-I01 | T13 | 入队/锁/写出与输入语义 |
| R-E01 | T01/T02/T04/T05/T14 | 去重、版本、缺项和可复现分析 |
| R-E02 | T14/T17 | 单变量实验及阻止混杂 |
| R-L01 | T00/T18 | 故障注入、旧失败项复验 |
| R-L02 | T15/T17 | 所有权与20+20轮真实重连 |
| 全部工程门槛 | T16/T17/T19 | 分层判定矩阵 |

## 3. 本轮文档交付检查

- [x] spec与本任务的相对链接能定位到真实文件。
- [x] 证据哈希、样本去重与68项计数已核对。
- [x] 未将640与1080差异隐藏，未将HEVC与MTK混杂写成因果。
- [x] 每项任务具有文件、步骤、断言或交付物、依赖和完成条件。
- [x] 旧未提交修复未被覆盖，原始日志未被修改。
- [x] 未执行测试、未自动提交/推送。

这一节只用于文档自查；勾选不改变T00～T19的实施状态。
