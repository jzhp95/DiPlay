# 独立 CarPlay 视频管线实施 Tasks

> 面向接手 AI／工程师：逐项执行本清单并以配套 Spec 为设计依据。项目 AGENTS.md 的测试时机优先；本清单不授权自动提交、推送或实车操作，也不要求启用多代理。不要套用旧 T00～T18 清单。

**Goal:** 独立实现从已认证码流入口到车机显示、触控返回的视频运行模块，通过设置切换原有／新管线，在同一路径验收颜色与跟手。

**Architecture:** 认证、连接、音频和成熟 H.264／HEVC 解码能力共用；新管线独立拥有访问单元、调度、生命周期、像素契约、渲染和证据。不调用旧视频实现完成新主屏流程。新旧模块由会话快照和角色路由隔离。

**Tech Stack:** Kotlin／Android API 28、MediaCodec、OpenGL ES 2.0／EGL 1.4；arm64-v8a；原生 FFmpeg 仅可选阶段 D，不是首期依赖。

- 日期：2026-11-01；任务版本：1.0；状态：全部未执行。
- [设计 Spec v1.2](../specs/2026-11-01-carplay-video-runtime-pipeline-spec.md)。
- [验收矩阵](2026-11-01-carplay-video-runtime-pipeline-acceptance.md)。
- [项目约束](../../../AGENTS.md)。
- [引导诊断设计](../specs/2026-10-31-guided-video-diagnostics-spec.md)。
- 本轮交付仅三份文档；任务中的源代码、测试、命令均为未来实施要求，不表示已创建或已执行。

## 1. 全局约束

1. 目标仅吉利星瑞 2026 款昆仑 Flyme Auto，Android 9／API 28，arm64-v8a；不假定具体 CPU 核心型号／数量。
2. versionName=0.2.26、versionCode=45、NDK=28.2.13676358；不恢复 automotive／home／maphost 独立应用。
3. 新主屏调用链不得依赖 AndroidVideoPipeline、SoftwareYuvRenderer、Yuv420Frame、SoftwareYuvColor、VideoOutputFreshness、VideoPlanResolver 或其状态对象；禁止拷贝整个旧类改名作为新实现。
4. 旧实现可通过专用适配器保留。常量、字节序、独立标准解析等纯函数只有经任务 N00 列入白名单并具有输入／输出测试后可共用；不得借“工具”传递旧 decoder／renderer／队列。
5. 成熟平台 decoder、AndroidAudioPipeline、既有认证、控制写出和最终文件导出组件可复用。YUV 是格式，独立设计的 YUV 模块不等于复用旧实现。
6. 当前用户确认颜色可接受的是旧软件 YUV＋跟随视频；这是效果对照，不是新实现基础，也不等于全场景像素验收。
7. Limited／Full／601／709 都由实际像素契约决定。强制 Limited 仅对照，不推广为修复；不使用照片 RGB 均值构造原始 YUV 或无来源阈值。
8. 原有实现默认启用；新实现显示“实验性”。设置切换作用于新会话快照，不允许在途变更 implementationId。
9. 不伪造 released、远端 ACK、颜色通过、端到端延迟或有效 swap interval；缺数据记 unavailable／null／partial。
10. 编码期间可阅读、静态分析和编写测试，完成约定实施范围全部代码与测试后统一执行检查。若用户只授权部分任务，则该明确范围完成后统一验证；不能自行拆小范围规避纪律。
11. 只在明确授权时提交／推送。保留现有未提交导出修复、旧管线改动、日志、图标和缓存；不顺手清理。
12. 每项依赖没完成时不能把空实现或仅返回成功的 stub 当作任务完成。接口签名需要变更时，先同时更新 Spec、清单与所有调用者。

## 2. 真实接入位置与路径约定

后续新共享文件根目录记为 S：

~~~text
shared/src/main/java/com/shilapi/xcertplay/media/video/
~~~

对应 JVM 测试根目录记为 ST：

~~~text
shared/src/test/java/com/shilapi/xcertplay/media/video/
~~~

新 common 文件根目录记为 C：

~~~text
common/src/main/java/com/shilapi/xcertplay/video/
~~~

对应 JVM 测试根目录记为 CT：

~~~text
common/src/test/java/com/shilapi/xcertplay/video/
~~~

下文 S／ST／C／CT 的每个文件名均相对上述唯一根目录，不是任意位置建议。
任务分工按这些路径锁定；Android 硬件能力不能被 JVM fake 测试替代。

| 既有真实位置 | 接入职责 |
| --- | --- |
| shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt | 既有 ScreenStream 和 ScreenCodec 同文件；认证、计数、长度检查、Annex-B 转换边界 |
| shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt | 同文件定义 MediaSink；currentScreenListener 的实际归属、流配置／帧交付 |
| shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt | 当前直接构造旧 video；改为公共视频门面和角色路由，保持 audio |
| common/src/main/java/com/shilapi/xcertplay/session/SessionState.kt | SessionMediaOptions／SessionStartRequest，加入不可变选择快照 |
| common/src/main/java/com/shilapi/xcertplay/session/SessionFactory.kt | 分开生成 legacy 配置与 independent 配置，不把旧 VideoPipelinePlan 输入新模块 |
| common/src/main/java/com/shilapi/xcertplay/session/SessionRuntime.kt | 正式 reconfigure 与真实停止屏障 |
| common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt | 现有设置存储方式；新选择独立版本键 |
| common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt | ADVANCED_MEDIA 设置入口、分类、搜索和说明 |
| common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt | 输出 Surface 与实际触摸几何；不持有新 codec／EGL |
| common/src/main/java/com/shilapi/xcertplay/VideoTroubleshooting.kt | 诊断模式映射，保持既定五模式语义 |

## 3. 执行顺序与批次

~~~text
N00 → N01
N01 → N02,N03,N04
N02+N03+N04 → N05
N05 → N06,N07
N03+N07 → N08 → N09
N06+N07+N09 → N10 → N11
N02+N11 → N12
N05+N10+N12 → N13
N01 → N14 → N15
N13+N15 → N16 → N17
N10+N12 → N18
N04+N12+N18 → N19
N16+N17+N18+N19 → N20
N20 → N21 → N22 → N23
D01 → D02 → D03（独立可选，不阻塞 N21～N23）
~~~

- N00～N05：契约、接入、队列与状态。
- N06～N13：独立平台后端、颜色与渲染、恢复及释放。
- N14～N17：设置、重连切换、完整门面接入及触控。
- N18～N20：测量、颜色取证与整合故障用例。
- N21：上述代码／测试用例集中完成后的统一软件门禁。
- N22：有效证据导出与设备采集预检，不能跳过直接让用户跑五组。
- N23：目标车机共同验收；通过前保持实验性，不改默认。
- D01～D03：可选原生后端的独立授权范围；不会自动引入依赖或改变五模式。

## 4. 公共接口冻结要求

以下是实施时必须落地的契约，不是本轮已写入源码的类型。具体结构体字段从 Spec 第 6、13、31 节补全，禁止以 Any／任意 Map 替代核心生命周期类型。

### 4.1 基础值与所有权（N01／N02）

~~~kotlin
enum class VideoImplementation { LEGACY, INDEPENDENT }
enum class BackendKind { PLATFORM_SURFACE, PLATFORM_YUV, NATIVE_YUV }
enum class DecoderPreference { HARDWARE, SOFTWARE, AUTO }
enum class OfferResult { ACCEPTED, BACKPRESSURE, REJECTED }
enum class ReleaseState { PENDING, RELEASED, BLOCKED }
enum class StreamActivity { ACTIVE, QUIESCENT, RESUMING }
data class SessionVideoId(val sessionId: String, val streamRole: Int, val streamGeneration: Long)
data class RuntimeIdentity(
    val stream: SessionVideoId, val configGeneration: Long,
    val decoderGeneration: Long, val surfaceGeneration: Long, val backendInstanceId: String
)
data class FrameKey(val identity: RuntimeIdentity, val accessUnitId: Long, val codecPtsUs: Long)
fun interface MonotonicClock { fun nowNs(): Long }
interface BufferLease : AutoCloseable {
    val buffer: java.nio.ByteBuffer
    val sizeBytes: Int
    override fun close()
}
interface ReleaseTicket {
    fun state(): ReleaseState
    fun awaitUntil(deadlineNs: Long): ReleaseState
    fun onComplete(listener: (ReleaseState) -> Unit): AutoCloseable
}
~~~

- ACCEPTED 才转移访问单元所有权；BACKPRESSURE／REJECTED 不接管，调用者负责归还。
- 解码帧 releaseOnce 记录已尝试并传播首次异常，不重复底层归还；未能真正退役的 decoder 仍 BLOCKED。
- 时钟由依赖注入提供，JVM 使用 FakeClock，不使用 Thread.sleep 驱动策略测试。
- 阻塞等待接口只允许关闭／后台等待者使用，媒体回调和 UI 不调用 awaitUntil。

### 4.2 运行与后端（N01／N05～N07）

~~~kotlin
interface VideoRuntime {
    fun attachSession(identity: SessionVideoId)
    fun onConfig(config: VideoStreamConfig)
    fun tryOfferAccessUnit(unit: EncodedAccessUnit): OfferResult
    fun attachOutput(target: OutputTarget)
    fun detachOutput(surfaceGeneration: Long): ReleaseTicket
    fun setRuntimePolicy(policy: RuntimePolicy)
    fun stop(reason: String): ReleaseTicket
    fun snapshot(): RuntimeSnapshot
}
interface VideoBackend {
    fun capabilities(): BackendCapabilities
    fun configure(config: VideoStreamConfig, target: OutputTarget, options: BackendOptions): ConfigureResult
    fun trySubmit(unit: EncodedAccessUnit): OfferResult
    fun pollOutput(deadlineNs: Long): BackendOutput
    fun requestStop()
    fun awaitReleased(deadlineNs: Long): ReleaseState
}
~~~

这些类型的唯一定义位置：

| 类型 | 文件／创建任务 | 必需内容 |
| --- | --- | --- |
| VideoStreamConfig／EncodedAccessUnit | S/VideoContracts.kt，N01；解析由 N03 | codec、CSD 哈希及自有数据、尺寸／crop／位深、颜色声明、封装；unit 的租约、接收时间、随机访问种类 |
| OutputTarget | S/VideoContracts.kt，N01 | 可空 Surface、角色、代际、几何、借用而非擅自释放调用者 Surface 的所有权 |
| RuntimePolicy／BackendOptions | S/VideoRuntimePolicy.kt，N01 | 请求后端、软硬偏好、颜色选择、节奏策略、限额；禁止复用旧 plan 对象 |
| RuntimeSnapshot | S/VideoRuntimeSnapshot.kt，N01；实现 N18 | 状态、请求／实际 implementation、后端、资格、活动状态、错误、释放和可空指标 |
| BackendCapabilities／ConfigureResult／BackendOutput | S/VideoBackend.kt，N01 | 明确支持格式；配置成功／拒绝／失败；Frame／FormatChanged／NoOutput／Failure 封闭分支 |
| DecodedFrameLease／PlaneView | S/VideoFrameLease.kt，N02 | 帧身份、存储种类、真实平面布局、实际输出描述、唯一归还函数 |
| SourceColorDeclaration／DecoderOutputDescription／ResolvedPixelContract／RenderTargetDescription | S/VideoColorContracts.kt，N01；解析／决策 N03／N08 | Spec 第 13 节字段和 provenance／applicabilityKey；unknown 显式存在 |

### 4.3 关键策略接口（各任务拥有实现）

~~~text
N04 EncodedQueue.offer(unit): OfferResult
    EncodedQueue.poll(): EncodedAccessUnit?
    EncodedQueue.resetForConfig(configGeneration): Unit
    EncodedQueue.snapshot(): EncodedQueueSnapshot
N08 PixelContractResolver.resolve(source, output, target, policy, qualification): ColorDecision
N09 YuvPlaneReader.sample(frame, points): SampleResult
    YuvPlaneReader.prepareUpload(frame): UploadPlan
N10 LatestFrameSlot.publish(frame): Boolean
    LatestFrameSlot.take(): DecodedFrameLease?
    LatestFrameSlot.finish(frame): Unit
    LatestFrameSlot.close(): Unit
N11 EglFrameRenderer.attach(target): Unit
    EglFrameRenderer.render(frame, decision): RenderReceipt
    EglFrameRenderer.stop(): ReleaseTicket
N12 OutputFreshnessPolicy.decide(candidate, context): PresentationDecision
N13 VideoBackendSelector.select(config, target, policy, evidence): SelectionResult
N15 VideoImplementationSwitch.request(selection, currentRevision): SwitchReceipt
N17 VideoTouchMapper.map(event, geometry): TouchMapResult
N18 VideoRuntimeMetrics.snapshot(): RuntimeSnapshot
N19 ColorEvidenceSampler.sampleBeforeUpload(frame, decision, request): SampleResult
~~~

对应输入／结果类型在各拥有者文件定义为 data class／sealed interface；
字段必须覆盖该任务列出的输出和拒绝原因，不通过 Boolean 丢失错误信息。
这些是具名语义接口；实现者需要提交完整可编译的 Kotlin 声明，与第 4.1／4.2 节共同冻结后才能接入下一任务。

### 4.4 必须保留的策略断言样例

以下是新 API 的断言形态；具体构造参数由 N01 完整类型提供，不用旧类或旧测试结果替代新实现。

~~~kotlin
@Test fun rejectedOfferKeepsCallerOwnership() {
    val lease = CountingBufferLease(16)
    val queue = EncodedQueue(maxFrames = 1, maxBytes = 8L, clock = FakeClock())
    assertEquals(OfferResult.REJECTED, queue.offer(testUnit(lease)))
    assertEquals(0, lease.closeCalls)
    lease.close()
    assertEquals(1, lease.closeCalls)
}
@Test fun releaseAttemptIsIdempotentButFailureIsVisible() {
    var attempts = 0
    val frame = testFrame {
        attempts++
        throw IllegalStateException("injected release failure")
    }
    assertThrows(IllegalStateException::class.java) { frame.releaseOnce() }
    frame.releaseOnce()
    assertEquals(1, attempts)
}
@Test fun replacingPendingReturnsOnlyReplacedLease() {
    val slot = LatestFrameSlot()
    val a = testFrame(); val b = testFrame(); val c = testFrame()
    assertTrue(slot.publish(a))
    assertSame(a, slot.take())
    assertTrue(slot.publish(b)); assertTrue(slot.publish(c))
    assertEquals(0, a.releaseAttempts)
    assertEquals(1, b.releaseAttempts)
    slot.finish(a)
    assertSame(c, slot.take())
    slot.finish(c); slot.close()
}
~~~

N00／N01 在 ST/VideoTestFixtures.kt 提供 FakeClock(startNs=0)、CountingBufferLease(sizeBytes)、
testUnit(lease)、testFrame(onRelease) 和 closeCalls／releaseAttempts 计数；
fixture 不绕过生产策略，不使用旧 pipeline，也不能在实际借用未归还时自动“修正”计数。
样例覆盖真实策略；其余用例由各任务明确输入／预期编写，不将样例当作完整覆盖。

## 5. 逐项任务

### N00：固定现状、复用白名单与门面清单

**依赖：** 无。

**文件：**

- Create: docs/superpowers/implementation/video-runtime-integration-inventory.md
- Create: ST/VideoTestFixtures.kt

**接口与交付：** 输出旧门面方法→legacy／independent／屏幕角色的唯一映射表；测试夹具仅支持新接口。

**实施步骤（本轮均未执行）：**

- [ ] 记录实际 HEAD、dirty 文件清单和源码／APK 身份；不得把用户的未提交改动重置或作为新任务已实现的成果。
- [ ] 逐项盘点 AndroidMediaSink 所有视频属性、诊断方法、SurfaceDetach、mirror、停止通知及资源计数；列出调用者和异步语义。
- [ ] 白名单只列认证／网络、audio、协议字段、独立标准解析、HID 最终 writer、文件导出；逐项记录状态依赖。禁止旧视频六类及 VideoPipelinePlan／VideoOutputObservation 作为新内部类型。
- [ ] 提供 FakeClock.advanceNs(delta)、CountingBufferLease、testUnit、testFrame 测试夹具；release 次数必须由真实调用产生，不在 teardown 隐藏泄漏。
- [ ] 记录旧版正常软件直出的 APK／commit 能否获得；没有则写 baseline_unavailable，不创造一个旧版。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 映射表中每个既有视频 API 必须有处置和角色归属，不能用“保持兼容”概括。
- [ ] 在后续 N20 执行边界检查时，新包不能 import 或通过别名反射调用旧视频实现。

**完成条件：** 接入面、白名单和现有待提交改动均有快照；所有后续文件和接口有唯一负责人。

### N01：创建独立类型、策略配置和可注入运行依赖

**依赖：** N00。

**文件：**

- Create: S/VideoContracts.kt
- Create: S/VideoRuntimePolicy.kt
- Create: S/VideoColorContracts.kt
- Create: S/VideoRuntimeSnapshot.kt
- Create: S/VideoRuntime.kt
- Create: S/VideoBackend.kt
- Create: ST/VideoContractsTest.kt

**接口与交付：** 实现第 4.1／4.2 节基础值、运行与后端接口；类型字段覆盖 Spec §6／§13；新增 RuntimeDependencies(clock, backendFactory, rendererFactory, recoveryRequest, metrics)。

**实施步骤（本轮均未执行）：**

- [ ] 定义 session／stream／config／decoder／surface 独立代际和 FrameKey，PTS／DTS／本地时间使用显式单位。
- [ ] 所有配置不可变；CSD 数据拥有明确副本或租约，拒绝外部继续修改。unknown／unsupported 使用显式分支。
- [ ] RuntimePolicy 自己表达 decoderPreference、outputKind、颜色请求、节奏和预算；不得包装旧 VideoPipelinePlan。
- [ ] RuntimeSnapshot 区分 requestedImplementation、actualImplementation、actualBackend、fallbackReason 和释放状态。
- [ ] BackendFactory 和 RendererFactory 是显式依赖，不用全局 liveRuntime 或 Activity 静态引用。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 修改源配置数组后，不得改变已提交配置；重复同 CSD hash 不产生新配置。
- [ ] 旧代际与新代际帧不可相等；缺源时间保持 null，不能替换成本机时间。
- [ ] 接口协议测试断言 ACCEPTED 才转移所有权。

**完成条件：** 完整 Kotlin 声明和构造器可供后续任务使用；接口无未定义核心类型、无旧视频对象。

### N02：租约、帧所有权与统一预算

**依赖：** N01。

**文件：**

- Create: S/VideoBufferPool.kt
- Create: S/VideoFrameLease.kt
- Create: S/VideoResourceBudget.kt
- Create: S/VideoReleaseTicket.kt
- Create: ST/VideoLeaseTest.kt
- Create: ST/VideoResourceBudgetTest.kt

**接口与交付：** BufferLease、DecodedFrameLease.releaseOnce()、PlaneView；VideoResourceBudget.tryReserve(kind, bytes): Reservation?；Reservation.close() 幂等。

**实施步骤（本轮均未执行）：**

- [ ] 实现全局 64 MiB 初始应用自有预算，各类别遵守 Spec §22；使用 Long 做尺寸／累计检查。
- [ ] 原始 PlaneView 包含有效区间、rowStride、pixelStride、位深、crop 原点；不可跨租约访问。
- [ ] 解码帧首次归还前标记 attempted；首次底层错误向上传播，后续调用不重复底层归还；保留真实 decoder 退役状态。
- [ ] pool 按容量／数量有界，尺寸降低和会话关闭可回收高水位；不依赖 finalize 或 GC 归还 decoder。
- [ ] ReleaseTicket 的订阅可注销，完成后不保留 Activity／已退出 listener；BLOCKED 不通过计时器变 RELEASED。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 租约成功／异常／重复释放分别记录一次底层调用；错误未伪装成功。
- [ ] 溢出尺寸、超过总预算、两模块各未超限但总和超限均拒绝。
- [ ] 关闭时存在借用不能先清零资源计数；返还后计数归零。

**完成条件：** 预算覆盖跨线程在途引用，Java／native／GPU 不混为一个未解释的内存数。

### N03：独立入口、配置解析与访问单元身份

**依赖：** N01、N02。

**文件：**

- Create: S/VideoIngressAdapter.kt
- Create: S/VideoConfigParser.kt
- Create: S/AccessUnitNormalizer.kt
- Create: ST/VideoIngressAdapterTest.kt
- Create: ST/VideoConfigParserTest.kt
- Modify: shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt（仅确有缺失身份字段时）

**接口与交付：** VideoIngressAdapter.onCodec／onConfig／onFrame 与 MediaSink 同单位；输出 VideoStreamConfig 和 EncodedAccessUnit；每个流登记 session＋streamGeneration。

**实施步骤（本轮均未执行）：**

- [ ] 明确当前 ScreenStream 已认证解密且已转 Annex-B，新入口标记 sourceFraming=ANNEX_B，不再重复 AVCC 转换。
- [ ] 独立解析或经 N00 白名单的纯解析函数读取 AVC／HEVC 配置和颜色字段；保留 CSD、参数集哈希和解析状态。
- [ ] 定义 transport frame 与访问单元的映射契约：不假定单 NAL 是帧；无法证明多 slice 边界时拒绝该输入组合，而非静默拆帧。
- [ ] codecPtsUs 由可追踪映射产生；保存原始源时间，处理重复 PTS／回退／缺失，不错误归属同名输出。
- [ ] 过期流在入口即拒绝；接收回调仅有界移交，不同步 codec／GPU／文件或关键帧请求。
- [ ] 协议认证计数仍由 ScreenStream 顺序维护；若需扩展流身份，兼容旧 Listener 重载并覆盖新旧回调。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 多 NAL、一帧多 slice、混合参数集、损坏长度、缺配置、重复配置、HEVC VPS／SPS／PPS。
- [ ] 已 Annex-B 输入只规范化一次；旧流回调不能进入新配置。
- [ ] 无源 PTS、相同 PTS、解码输出重排仍有明确关联或 association_unavailable。

**完成条件：** 实际入口封装有测试和注释；不改变密钥、帧计数或 wire protocol。

### N04：有界输入与静止唤醒策略

**依赖：** N01、N02。

**文件：**

- Create: S/EncodedQueue.kt
- Create: S/VideoInputActivity.kt
- Create: ST/EncodedQueueTest.kt
- Create: ST/VideoInputActivityTest.kt

**接口与交付：** EncodedQueue(maxFrames: Int, maxBytes: Long, clock: MonotonicClock)；snapshot(): EncodedQueueSnapshot；VideoInputActivity.observe(input, pendingWork): StreamActivity。

**实施步骤（本轮均未执行）：**

- [ ] 实现最多 8 AU 且 8 MiB 的双上限，等待不持有网络锁；溢出返回原因、引用归属和恢复请求，不随机删除参考帧。
- [ ] 控制邮箱有界，Stop 原子唤醒不会被帧淹没；最新配置屏障不得越过旧代际输入。
- [ ] 3 秒无新输入且应用／codec 均无未完成工作后 QUIESCENT；连接健康检测独立。
- [ ] 唤醒首帧进入 RESUMING，按自己的本机到达时间计年龄，重新建立 300ms 趋势；参考链有效时普通 P 帧可恢复。
- [ ] 500ms 实际输入年龄保护和容量保护仍生效；硬错误不因唤醒豁免。
- [ ] EncodedQueueSnapshot 定义 depth、bytes、oldestArrivalNs、configGeneration、overflowReason，不持有完整帧副本。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 7／8／9 帧、字节边界、超大单帧、配置屏障、Stop 满队列。
- [ ] 静止 30 秒恢复一帧／突发／配置变化，不将空闲时间判积压。
- [ ] 无输入但 codec 有未完成工作不进入静止；唤醒后真过载仍触发恢复。

**完成条件：** 所有拒绝／关闭／reset 路径正确归还；趋势只基于可测本机时间。

### N05：协调器状态机与生命周期屏障

**依赖：** N02、N03、N04。

**文件：**

- Create: S/VideoRuntimeCoordinator.kt
- Create: S/VideoRuntimeEvents.kt
- Create: ST/VideoRuntimeCoordinatorTest.kt

**接口与交付：** VideoRuntime 全部方法；RuntimeEvent 封闭分支：Config／FrameReady／OutputChanged／Failure／Stop／Released；事件携带原始 identity。

**实施步骤（本轮均未执行）：**

- [ ] 以串行控制执行器拥有状态；编码／输出／EGL 分别独立执行，不在管理锁内等待。
- [ ] 按 Spec §7 实现启动、等待配置、等待随机访问、运行、切换、恢复、停止、BLOCKED_RELEASE。
- [ ] 同 hash CSD 幂等；真正配置变化才增加 configGeneration，保留 source 及新旧所有权边界。
- [ ] codec 创建和退役受唯一活动提交者控制；迟到旧 decoder 事件只清理自己的资源。
- [ ] 恢复请求经异步控制通道调用现有协议处理，不在媒体回调内同步进入 session 锁。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 交错 Stop／Config／SurfaceLost／旧输出回调，最终状态和资源计数一致。
- [ ] 重复 configure 不创建多个 decoder；停止中不接受新输入。
- [ ] 释放卡住保持 BLOCKED_RELEASE；收到真实完成才通知 session。

**完成条件：** 用可控 fake backend 验证真实事件序列，不能仅断言枚举名。

### N06：独立 MediaCodec Surface 后端

**依赖：** N05。

**文件：**

- Create: S/PlatformSurfaceBackend.kt
- Create: S/PlatformCodecDriver.kt
- Create: ST/PlatformSurfaceBackendTest.kt

**接口与交付：** 实现 VideoBackend；PlatformCodecDriver 封装 API 28 MediaCodec 创建／配置／输入／输出／release，作为可注入测试边界。

**实施步骤（本轮均未执行）：**

- [ ] 单输入所有者和单输出所有者，生命周期屏障期间停止两者进入 codec；驱动接口区分 try again／format changed／index。
- [ ] PlatformSurfaceBackend 是输出路径而非强制硬件身份；同时支持明确选择平台软件组件的直出对照，actual decoder 单独记录，不用旧 software-direct 实现。
- [ ] 配置 CSD、尺寸、颜色 hint 与实际组件，记录请求、接受／重试及输出格式。
- [ ] 每个 index 出队即有租约；显示／丢弃／EOS／CONFIG 分支都归还且错误传播。
- [ ] SurfaceView 与受控 Texture 变体分别标实际 outputKind；不虚构 raw Image。
- [ ] 只使用 API 28 能力；组件硬／软身份不能无保护调用 API 29 isHardwareAccelerated。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 首次格式变化、输出异常、EOS、release 抛异常、reconfigure 竞争、组件创建失败。
- [ ] 未验证硬解可以显式实验，但不能被 selector 认作颜色合格。
- [ ] JVM driver fake 证明调度；真实 Surface 呈现留给设备门禁。

**完成条件：** 全新后端不调用旧 VideoDecoder／AndroidVideoPipeline；opaque 证据边界明确。

### N07：独立平台可读 YUV 后端

**依赖：** N05。

**文件：**

- Create: S/PlatformYuvBackend.kt
- Create: ST/PlatformYuvBackendTest.kt

**接口与交付：** 实现 VideoBackend；BackendOptions.decoderPreference 决定硬件／平台软件候选，实际 componentName 必须出现在 FrameLease。

**实施步骤（本轮均未执行）：**

- [ ] 复用 N06 的低层 PlatformCodecDriver，不复用旧视频模块；配置支持的 COLOR_FormatYUV420Flexible。
- [ ] 硬件和平台软件均通过独立适配实现读 Image、平面描述、帧身份与归还。
- [ ] 无 Image、格式不支持、布局异常明确失败；不转 Surface 后仍报告 YUV。
- [ ] 参考帧内部状态由 decoder 管理，显示租约与参考链分别处理。
- [ ] 组件选择记录理由，软解请求不静默选择硬解；可接受回退时记录实际结果。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 三 plane 不同 stride／pixelStride、空 Image、异常获取、旧 generation。
- [ ] Image.close 和 releaseOutputBuffer 失败的附加错误保留且不二次归还。
- [ ] 同一新后端可选择平台软件而不调用旧 SoftwareYuvRenderer。

**完成条件：** CPU 可读路径真实、租约可移交；吞吐和颜色正确性不因创建成功就通过。

### N08：像素契约与颜色决策

**依赖：** N03、N07。

**文件：**

- Create: S/PixelContractResolver.kt
- Create: S/VideoColorTransform.kt
- Create: ST/PixelContractResolverTest.kt
- Create: ST/VideoColorTransformTest.kt

**接口与交付：** resolve(source, output, target, policy, qualification): ColorDecision；ColorDecision 定义 Accepted(contract, transform)／Unverified(reason)／Unsupported(reason)。

**实施步骤（本轮均未执行）：**

- [ ] 分别保存源声明、输出标签／布局、像素契约和目标空间，不用一个 color 对象混写。
- [ ] 按适用资格与明确手动实验处理冲突，不统一信 SPS 或 MediaFormat，不用直方图猜范围。
- [ ] 实现 8bit SDR Full／Limited 的亮度、色度归一化和 601／709 矩阵；transfer／primaries 不一致时给出明确策略或不支持。
- [ ] 覆盖 primaries=1／matrix=6／transfer=13，不能因 Android 标准枚举受限强改 709。
- [ ] 资格键绑定设备构建、组件、算法、格式、位深、颜色和输出路径；无资格只可标实验。
- [ ] 10bit／HDR 未实现时明确拒绝，不直接按 8bit shader 解释。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] Full 4／16／224／235／236／238／240 不被错误合并；Limited 16→0、235→255 属合法转换。
- [ ] 中性灰之外测彩色，区分 601／709；未知／冲突／不支持组合不返回伪通过。
- [ ] 同手机 H.264 matrix6 与 HEVC matrix1 各自正确解析，不要求相同转换矩阵。

**完成条件：** 数学单测和 shader 参数使用同一受审计变换表示，但不复用旧 SoftwareYuvColor。

### N09：独立 plane 读取、裁剪与上传计划

**依赖：** N02、N07、N08。

**文件：**

- Create: S/YuvPlaneReader.kt
- Create: S/YuvUploadPlan.kt
- Create: ST/YuvPlaneReaderTest.kt

**接口与交付：** sample(frame: DecodedFrameLease, points: List<SamplePoint>): SampleResult；prepareUpload(frame): UploadPlan；SamplePoint／SampleResult／UploadPlan 在上述文件定义。

**实施步骤（本轮均未执行）：**

- [ ] 按完整帧／裁剪原点读 Y／U／V；先 checked Long 计算有效偏移，再读取。
- [ ] 奇数 crop.left／top 的色度索引基于绝对几何，不做错误比例缩放。
- [ ] 平面紧凑可直接上传；padding 使用复用行缓冲；NV12／NV21 两纹理仅在共享布局和长度已证实时启用。
- [ ] pixelStride=2 不足以证明可合并 UV；无法证明使用通用三平面读取。
- [ ] GLES2 不默认有任意 row stride 上传扩展，所有快路径有安全通用实现。
- [ ] UploadPlan 记录是否复制、字节数和源依赖结束点，不提前归还异步 GPU 仍引用的内存。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] stride padding、U／V 顺序、pixelStride1／2、odd crop、有效区间尾部、长度溢出。
- [ ] 快路径与通用读取对同一人工 plane 逐点一致。
- [ ] 租约释放后拒绝读指针；未知布局不猜格式。

**完成条件：** 不使用旧 Yuv420Frame；采样和真实上传共用同一新几何解释。

### N10：最新帧槽与进行中帧保护

**依赖：** N06、N07、N09。

**文件：**

- Create: S/LatestFrameSlot.kt
- Create: ST/LatestFrameSlotTest.kt

**接口与交付：** publish(frame): Boolean；take(): DecodedFrameLease?；finish(frame): Unit；close(): Unit。finish 在渲染循环 finally 中归还尚未归还的租约并清除 in-flight；render 内可在上传后提前 releaseOnce，但不能提前清除进行中标记。

**实施步骤（本轮均未执行）：**

- [ ] 仅 1 pending＋1 in-flight；新帧替换 pending，立即归还被替换租约。
- [ ] take 在 in-flight 尚未结束时不再交出第二个；渲染结束的通知和 releaseOnce 顺序明确。
- [ ] publish 失败不接管调用者租约，close 后拒绝旧回调；关闭等待真实进行中帧完成。
- [ ] 不在 slot 锁内执行可能进入 codec 的底层 release；先移走所有权，再在锁外归还并上报错误。
- [ ] 按帧身份／配置拒绝过期帧，不把较晚到达但更早 PTS 的帧误作最新。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] A 进行中、B pending、C 替换 B：只 B 被槽主动释放；A 结束后才能取 C。
- [ ] publish／take／close 可控交错，所有租约最终恰好尝试一次释放。
- [ ] slot 有界不等于 codec／系统显示总队列有界，快照分别报告。

**完成条件：** 明确调用者与 slot 在接受／拒绝／替换／停止时的所有权。

### N11：独立 EGL 渲染器与颜色转换

**依赖：** N06、N07、N09、N10。

**文件：**

- Create: S/EglFrameRenderer.kt
- Create: S/EglDriver.kt
- Create: S/VideoShaders.kt
- Create: ST/EglFrameRendererTest.kt
- Create: ST/VideoShaderContractTest.kt

**接口与交付：** attach(target): Unit；render(frame, decision): RenderReceipt；stop(): ReleaseTicket；RenderReceipt 记录 frameKey、上传／归还／绘制／swap 时刻和失败阶段。

**实施步骤（本轮均未执行）：**

- [ ] EGL 单线程拥有 display／context／window／program／textures，独立于接收与 decoder 输出。
- [ ] 使用新 VideoColorTransform 生成 shader 参数，支持安全三纹理与经证实的两纹理；保持 viewport／crop／rotation。
- [ ] 按尺寸复用纹理和 staging；格式变化有界销毁，不逐帧创建 DirectBuffer、Bitmap、program。
- [ ] 纹理上传不再依赖源像素时尽早归还 Image／codec；异步互操作未验证 fence 前禁用。
- [ ] YUV 自绘继承请求 eglSwapInterval(0)，记录允许范围、返回／错误及实测；不报告已保证有效值或非阻塞。
- [ ] 所有异常路径释放自己拥有的资源；swap 失败不标已显示，不释放外部借用的 Surface 对象。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 上传前／中／后、归还、draw、swap 分别注入错误，资源次数和成功计数准确。
- [ ] EGL driver fake 检查调用归属与顺序；设备上用已知样本验证真实 shader 输出。
- [ ] 没有首帧或无新帧时不忙循环；停止唤醒渲染等待者。

**完成条件：** 独立实现，不引用旧 SoftwareYuvRenderer／旧 shader；API28 GLES2 可用。

### N12：呈现新鲜度、节奏与静止恢复

**依赖：** N02、N11。

**文件：**

- Create: S/OutputFreshnessPolicy.kt
- Create: S/VideoPresentationScheduler.kt
- Create: ST/OutputFreshnessPolicyTest.kt
- Create: ST/VideoPresentationSchedulerTest.kt

**接口与交付：** decide(candidate: FrameCandidate, context: PresentationContext): PresentationDecision；类型在 OutputFreshnessPolicy.kt 定义，包含 DISPLAY／DROP_STALE／HOLD／REJECT_GENERATION 及理由。

**实施步骤（本轮均未执行）：**

- [ ] 独立实现策略，不调用旧 VideoOutputFreshness；候选年龄和队列状态按真实时间定义。
- [ ] 优先替换已解码旧输出；压缩队列 pending 不等于有效更新画面可显示，不能只据它无限丢输出。
- [ ] 保护首次有效画面、静止恢复画面和当前唯一有效画面；显式节奏与诊断定格独立处理。
- [ ] 默认不引入固定多帧平滑等待；可选 pacing 记录实际等待和用户选择。
- [ ] 100ms 可作为待验证的初始保护，不构成目标性能或端到端阈值；不能全丢超过30ms帧而永无显示。
- [ ] 有 B 帧时依照合法 decoder PTS 呈现，不把 input 到达顺序当显示顺序。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 只有一个超龄输出仍可推进；有更新安全候选时可丢旧。
- [ ] 连续积压后必须最终显示新帧，不能反复恢复／关键帧饥饿。
- [ ] 源 PTS 回退、缺失、明确 pacing、诊断定格均有确定分支。

**完成条件：** 策略有理由和计数；应用、codec、显示端等待分别可测。

### N13：资格选择、分级恢复和真实释放

**依赖：** N05、N10、N12。

**文件：**

- Create: S/VideoBackendSelector.kt
- Create: S/VideoRecoveryPolicy.kt
- Create: S/VideoRetirement.kt
- Create: ST/VideoBackendSelectorTest.kt
- Create: ST/VideoRetirementTest.kt

**接口与交付：** select(config,target,policy,evidence): SelectionResult；VideoRetirement.begin(reason): ReleaseTicket；资格状态按 Spec §19，不用单一 supported 布尔值。

**实施步骤（本轮均未执行）：**

- [ ] 实现合格硬解 Surface→合格硬解 YUV→已验证软件候选的选择，不给予 native 固定优先权。
- [ ] R0 显示丢旧、R1 参考链失效、R2 codec 故障、R3 协议失败、R4 未释放分别处理。
- [ ] 一个故障 episode 只执行必要且限频动作；相同失败组件不无限重选。
- [ ] 后端切换停止接纳、归还 pending、收尾 in-flight、确认退役、递增 decoderGeneration、等合法随机访问再运行。
- [ ] Surface 与 color／decoder 代际独立；Surface 无效时暂停或受控重建，不污染颜色资格。
- [ ] 停止先使阻塞 I/O 可退出，再等待各资源；无 main-thread join、不持管理锁等待 native。
- [ ] 资格缓存失配时失效；UNKNOWN 不能自动成为颜色通过。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 模拟 release 永不完成：新 backendFactory.create 调用数不增加，状态 BLOCKED_RELEASE。
- [ ] 旧回调迟到只清理旧租约；重连恢复请求使用新快照。
- [ ] R1 不无条件重建 codec，R2 不能继续假装引用链有效。

**完成条件：** 主状态与资源实况一致；挂住的 native 调用不能靠 Java 超时被宣称已终止。

### N14：设置存储与新旧实现选择模型

**依赖：** N01。

**文件：**

- Create: C/VideoImplementationPreferences.kt
- Create: CT/VideoImplementationPreferencesTest.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/session/SessionState.kt

**接口与交付：** load(): VideoImplementation；save(selection, expectedRevision): SaveResult；SessionMediaOptions 增加 implementation=LEGACY、independentPolicy 独立字段。

**实施步骤（本轮均未执行）：**

- [ ] 新增版本化偏好键，缺失／未知／损坏值回到 LEGACY 并记录解析原因。
- [ ] 维护设置 revision；保存失败不能显示选择已生效。
- [ ] 保存 preference、requested snapshot、actual runtime 三者分开，设置值不能用作实际后端证据。
- [ ] 新 RuntimePolicy 不从旧 VideoPipelinePlan 派生；已有单独颜色／decoder 设置通过明确值映射，不携带旧状态。
- [ ] 诊断临时覆盖不写用户永久设置，恢复时 revision 变化则不覆盖用户新选择。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 首次安装、升级缺键、未知值、并发保存、磁盘失败。
- [ ] 选择新管线不改 HEVC／分辨率／音频／配对设置。
- [ ] 新 SessionStartRequest 复制后不可被下一次 UI 选择原地改变。

**完成条件：** 未连接时只保存待下次连接选择；默认行为保持原有。

### N15：正式切换事务与设置 UI

**依赖：** N14。

**文件：**

- Create: C/VideoImplementationSwitch.kt
- Create: CT/VideoImplementationSwitchTest.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/session/SessionRuntime.kt
- Modify: common/src/main/res/values/strings.xml
- Modify: common/src/test/java/com/shilapi/xcertplay/SettingsLayoutPolicyTest.kt

**接口与交付：** request(selection: VideoImplementation, currentRevision: Long): SwitchReceipt；SwitchReceipt 包含 accepted、pending／reconnecting／blocked、reason、requestId。

**实施步骤（本轮均未执行）：**

- [ ] ADVANCED_MEDIA 加“视频管线”：原有管线／新视频管线（实验性）；同时显示当前实际运行项，明确切换需要重连。
- [ ] 连接前保存；连接中经正式 UI command 生成全新 SessionStartRequest 并调用 reconfigureFromUi，不仅 reconnect 重用旧 requested。
- [ ] 断开等真实释放后启动新会话；失败显示可操作状态，不自动同时运行两套。
- [ ] 快速连续点击合并成最新请求，旧 requestId 不能完成新 UI 状态；诊断占用时明确拒绝或排队一个最新请求。
- [ ] 切换与暂停／取消／Activity 重建、设置 revision 保持一致；不把 Activity 捕获在 session 回调中。
- [ ] 所有已有翻译资源采用项目的回退机制；设置分类、搜索、重复入口保持一致。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] LEGACY→INDEPENDENT→LEGACY、断开状态切换、快速点击、release blocked。
- [ ] 旧 session released 前新 factory 调用数必须为0。
- [ ] UI 显示“请求新管线／实际原有”而非隐藏回退，测试验证真实持久化值。

**完成条件：** 用户可切换／回退，准确知道当前实际实现；不改应用版本。

### N16：统一视频门面与角色路由

**依赖：** N13、N15。

**文件：**

- Create: S/SessionVideoEndpoint.kt
- Create: S/SessionVideoRouter.kt
- Create: S/LegacyVideoEndpoint.kt
- Create: S/IndependentVideoEndpoint.kt
- Create: ST/SessionVideoRouterTest.kt
- Create: CT/VideoSessionFactoryTest.kt
- Modify: shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/session/SessionFactory.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt

**接口与交付：** SessionVideoRouter 负责主屏／非主屏 endpoint；统一门面方法由 N00 的 API 清单定义，detach 结果只暴露 ReleaseTicket，不暴露 AndroidVideoPipeline.SurfaceDetach 类型。

**实施步骤（本轮均未执行）：**

- [ ] LEGACY endpoint 专门包装旧类；INDEPENDENT endpoint 只构造新 runtime。
- [ ] AndroidMediaSink 不再无条件构造旧主屏；所有视频方法按角色分发，audio 对象保持原有生命周期。
- [ ] 主屏110与mirror／非主屏独立；首次范围不支持的新镜像继续明确 legacy 路由，但不得把主屏送给旧类，不同角色分别报告实际 implementation。
- [ ] 旧非主屏 endpoint 按角色惰性构造；仅新主屏活动时，不能为未使用镜像预先创建旧 decoder 或旧主屏 pipeline。
- [ ] 独立主屏时不计算旧 VideoPlanResolver plan 供新模块使用；LEGACY 才计算旧 plan。
- [ ] 逐一迁移 diagnosticCapture、SurfaceDetach、mirror、release await、resource counts、cadence、raw sample、recovery handlers 等门面，无 silently delegate old。
- [ ] 新模块不具备某既有诊断能力时显式 unsupported，不能返回空成功或偷偷用旧 decoder 抓取。
- [ ] 默认旧路径行为保持；新路径错误不影响 audio 先解除 I/O 的关闭顺序。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 主屏只收到一个 endpoint 的每个 frame；两实现切换不会双计数或双 decoder。
- [ ] 所有 N00 方法映射均有调用测试，镜像不污染主屏颜色／配置。
- [ ] New endpoint 可在测试环境将旧主屏 factory 设置为抛异常，仍成功处理主屏。

**完成条件：** 新主屏从接入到显示没有旧管线调用依赖；外围共用边界清楚。

### N17：独立触控映射与返回调度

**依赖：** N02、N12、N16。

**文件：**

- Create: S/VideoTouchMapper.kt
- Create: S/VideoInputDispatcher.kt
- Create: ST/VideoTouchMapperTest.kt
- Create: ST/VideoInputDispatcherTest.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/session/SessionFactory.kt

**接口与交付：** map(event: TouchEventSnapshot, geometry: DisplayGeometry): TouchMapResult；VideoInputDispatcher.offer(command): InputOfferResult；输入输出类型在新文件定义。

**实施步骤（本轮均未执行）：**

- [ ] 新模块拥有 letterbox／crop／rotation／尺寸坐标映射和几何版本；HID 协议编码与最终 event writer 允许共用。
- [ ] 使用独立有界队列／执行器，连续 move 仅同手势同 pointer 集可合并；down／up／cancel 保留。
- [ ] 若复用现有 InputCommandQueue，只允许作为最终命令 writer 适配，不再叠一层无界排队或重复 move 合并。
- [ ] 事件与 session／geometry 身份绑定，停止／切 Surface 发必要 cancel 后禁止旧手势写新会话。
- [ ] 记录 enqueue／dequeue／事件写锁等待／write-and-flush，phoneAcknowledged=unknown。
- [ ] 视频 shader／swap／诊断卡住时不占用 HID writer 锁；write I/O 关闭可解除。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 旋转／裁剪／黑边映射、多指集合变化、满队列时边界事件策略。
- [ ] 视频渲染被 fake 阻塞，HID 命令仍执行；旧 session 写出被拒绝。
- [ ] 触控已写出不标手机响应或 touch-to-photon。

**完成条件：** 手势语义保持，输入等待与视频等待可分开追踪。

### N18：有界运行指标、时钟与资源证据

**依赖：** N10、N12。

**文件：**

- Create: S/VideoRuntimeMetrics.kt
- Create: S/VideoFrameTimeline.kt
- Create: ST/VideoRuntimeMetricsTest.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt（仅快照接口）

**接口与交付：** VideoRuntimeMetrics.snapshot(): RuntimeSnapshot；frame timeline 使用 FrameKey；字段定义与验收矩阵 §3 对齐。

**实施步骤（本轮均未执行）：**

- [ ] 分开采集 received／submitted／decoded／displaySubmitted／discarded，不从任意计数拼出帧率。
- [ ] 应用等待、codec 输入／输出、plane 重排、上传、释放、draw／swap、HID 写出各自记录本机单调时钟。
- [ ] 滚动分布有界并持续更新；无样本 null，计数不除以错误窗口；保留峰值和长间隔。
- [ ] CPU 单核等效值及线程 CPU、墙钟、温度和可用频率分别记录，无法获取字段 unavailable。
- [ ] snapshot 不持有 FrameLease、Surface、Image、Activity；原始轨迹仅显式有界开启。
- [ ] 把 requested／actual implementation、decoder、output、source／output color、qualifications、fallback 送入诊断现有报告边界，不使用旧 video.observation 伪造。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 超过环形容量仍继续滚动，结束后计数和样本窗口对齐。
- [ ] 源 PTS 回退不制造负本地耗时；跨时钟不能直接相减。
- [ ] 挂住／丢弃／无帧的分母正确，诊断关闭后无原始帧引用。

**完成条件：** 指标真实可解释，11.7ms 旧样本不能变成新模块端到端通过线。

### N19：生产同帧颜色采样与可靠导出

**依赖：** N04、N12、N18。

**文件：**

- Create: S/ColorEvidenceSampler.kt
- Create: ST/ColorEvidenceSamplerTest.kt
- Create: C/VideoRuntimeEvidenceAdapter.kt
- Create: CT/VideoRuntimeEvidenceAdapterTest.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/VideoTroubleshooting.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/VideoAdaptationRun.kt
- Modify: common/src/main/java/com/shilapi/xcertplay/VideoAdaptationExport.kt
- Create: C/VideoReferenceQualification.kt
- Create: CT/VideoReferenceQualificationTest.kt

**接口与交付：** sampleBeforeUpload(frame, decision, request): SampleResult；SampleRequest 含 patch geometry、run／case、frame 选择及上限；证据结构详见矩阵 §3。

**实施步骤（本轮均未执行）：**

- [ ] 在生产 renderer 持有效 frame 租约、上传前采集；每帧最多64点、每秒最多2帧、每次最多10秒。
- [ ] 样点只复制小型不可变结果；不把原 plane／裸指针交给异步 writer，不延迟无关帧归还。
- [ ] 同实例同 frameId 获取 RGB 渲染目标或明确关联 unavailable；记录采集位置与 GPU 同步开销。
- [ ] Surface 模式 raw unavailable，独立无 Surface decoder 必须标不同实例；不能算 same_instance_complete。
- [ ] 读取固定素材参考的来源和阈值资格；当前 h1-reference.json 明写 uncalibrated pass thresholds，不能用作已校准自动颜色通过线。新增 VideoReferenceQualification 判定 reference／帧关联／容差是否有资格；未校准输出 uncalibrated 和原始数值，不造阈值。
- [ ] 轻量指标默认启用；侵入采样显式打开、独立时段，不混算性能基准。
- [ ] 导出使用既有已修复组件和最终回读验证，记录 private／public bytes+SHA256、ZIP 校验、runId；不提交用户码流或私密信息。
- [ ] 保持原先五模式和单次固定 HEVC 选择；新实现加 implementationId，不扩大到十／六模式。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 采样时生产租约有效，释放后不可读；导出队列满只丢证据并标 partial。
- [ ] 同 frameId 不同 backendInstanceId 仍不算同实例；窗口截图关联不明不能通过。
- [ ] 公有URI0字节／截断／hash不一致、取消、磁盘失败均不能返回完整成功。

**完成条件：** 设备预检能证明非零文件和样点链路，证据缺失有明确原因。

### N20：完整生命周期、独立性与故障注入用例

**依赖：** N16、N17、N18、N19。

**文件：**

- Create: ST/VideoRuntimeIntegrationTest.kt
- Create: ST/VideoRuntimeIsolationTest.kt
- Create: CT/VideoPipelineSwitchIntegrationTest.kt
- Create: scripts/check_video_runtime_boundary.py
- Create: scripts/tests/test_video_runtime_boundary.py

**接口与交付：** 检查器接受源码根目录，报告违规路径／符号并非零退出；只扫描新包及允许入口，legacy adapter 是显式例外。

**实施步骤（本轮均未执行）：**

- [ ] 将队列、coordinator、fake driver、renderer、switch 和 evidence adapter 连接起来，测试生产接口而非重写算法。
- [ ] 检查禁止 import、全限定调用、旧计划／旧帧类型泄漏；人工审查反射／委托等静态检查覆盖不到的绕行。
- [ ] 故障矩阵逐项注入 configure／input／output／getImage／upload／draw／swap／release／export 失败。
- [ ] 模拟停止／切 Surface／配置变化／诊断取消竞争，验证全局资源计数和旧代际隔离。
- [ ] 完整 SessionRuntime→factory 测试确认新请求生效，不因 reconnect 重用旧设置。
- [ ] 审查当前所有修改仍符合目标车型／API／ABI，保留已有未提交修复。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 旧主屏 factory 抛异常仍可跑新主屏；legacy 模式照常调用旧 factory。
- [ ] 100 次可控 fake start／stop 后 lease／listener／worker 计数回基线，不把 mock 成功当 native 长测。
- [ ] 导出失败不会污染播放状态，release失败不能计渲染成功。

**完成条件：** 代码与测试用例全部完成并自查后，才允许进入 N21；本任务不边写边跑测试。

### N21：统一软件门禁与产物身份

**依赖：** N20。

**文件：**

- Modify: 本清单任务状态与执行记录（只填真实结果）
- Create: build/video-runtime-validation/（运行生成、忽略提交）
- Modify: scripts/check_arm64_apk.py（仅新库需要时，D阶段不得误报固定4库）

**接口与交付：** 输出 commands.log、JUnit XML 统计、lint、APK bytes／SHA256、mapping SHA256、环境版本及代码基线身份。

**实施步骤（本轮均未执行）：**

- [ ] 固定当前基线：HEAD、dirty diff hash、implementation schema、设备无关配置；不凭 HEAD 代表未提交源码。
- [ ] 统一运行 §6 命令：边界检查、Python 单测、公共树／manifest 检查、规定 Gradle tests／lint／debug build。
- [ ] 构建 optimizedDebug 并验证 arm64；mapping 与该 R8 APK 一一对应；不误称它是 release。
- [ ] 核验 JNI／app_process 入口等现有保留规则，不能只检查类名 FOUND 就声称运行通过。
- [ ] 若发现失败，先集中修正所有相关代码，再运行失败及受影响范围；没有新增问题不重复已通过检查。
- [ ] 输出每项 PASS／FAIL／SKIPPED／NOT_RUN 和理由；任何未执行车机项均 NOT_RUN。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] JUnit XML 总数与失败数来自本批次实际文件，不混入陈旧报告。
- [ ] 两个 APK hash、字节数、mapping hash 对应记录，可重复核对。
- [ ] 所有源代码完成也不能将 N22／N23 自动标通过。

**完成条件：** 软件门禁结果真实；不授权 commit／push，不自动升级版本或修改默认。

### N22：设备采集预检与测试入口验真

**依赖：** N21。

**文件：**

- Create: docs/superpowers/implementation/video-runtime-device-preflight.md

**接口与交付：** 预检收据包含运行身份、采集字段、实际帧样本、最终导出 URI bytes／SHA256 与电脑回读结果。

**实施步骤（本轮均未执行）：**

- [ ] 设备操作需有当次授权；不卸载、不清数据、不擅自连接用户手机。
- [ ] 先用固定已知素材走新真实 renderer，验证源→同实例 YUV→RGB 的身份和样点，不以 synthetic metric 代替。
- [ ] 性能采集关闭侵入采样，验证计数递增和无数据 null；检测 HEVC 允许值与实际codec是否一致。
- [ ] 导出一份短报告，在设备最终 URI 和电脑文件均验 bytes／SHA256／ZIP 内容；0字节立即阻止正式引导测试。
- [ ] 验证设置显示请求和实际 implementation、decoder、路径、fallback；任何字段缺失列 partial。
- [ ] 未能运行预检则保持 NOT_RUN，不再次要求用户完成整趟测试来替开发环节验证采集器。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 真实 renderer 有样点且 frameId／backend 实例一致；Surface raw 不可读有显式标志。
- [ ] 取消／恢复导出不产生假成功，重复文件同名不会覆盖跨run证据。
- [ ] R8 包实际点击／调用路径能运行，而不只是静态检查符号。

**完成条件：** 采集链路验证通过后，才允许用户投入完整实车五模式测试。

### N23：目标车机颜色、跟手与稳定性共同验收

**依赖：** N22。

**文件：**

- Modify: 验收矩阵实车状态与证据引用
- Create: docs/superpowers/implementation/video-runtime-vehicle-validation.md

**接口与交付：** 输出独立的颜色结论、性能结论、完整触控测量边界、用户接受反馈和实际路径身份。

**实施步骤（本轮均未执行）：**

- [ ] 只在目标车机对已实现路径运行；原有与新实现分run，条件固定，HEVC开／关分run；不自动把一次五组扩为十组。
- [ ] 颜色使用已知参考与真实码流，对近黑／近白／彩色采样；真实地图反馈作为补充，不以照片阈值代替。
- [ ] 动态测试关闭侵入颜色采样，记录输入／输出节奏、排队分布、CPU／温度；外部高速视频或可靠关联才给完整 touch-to-photon。
- [ ] 同手机同场景比较 H.264 matrix6／HEVC matrix1，但以实际流声明为准，其他变化标混杂。
- [ ] 执行用户授权的长时间运行和至少20次连接／断开／切换，观察内存、资源退役和恢复；RSS高水位与持续增长分别判。
- [ ] 同一实际后端需颜色与手感同时通过；未达标继续实验，不因某项局部11ms就改默认。
- [ ] 是否切换默认需单独明确决定；本任务完成不等于授权发布。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 旧软件YUV＋跟随视频效果基线不退步；Limited对照不推广。
- [ ] 任何自动回退都能在报告和UI查出，不能混算跨后端指标。
- [ ] 缺参考／缺同帧／无端到端测量均明确边界，不填零或通过。

**完成条件：** 最终验收附真实证据；没有同时达标路径时明确目标未完成。

### D01：可选原生后端可行性与依赖锁定

**依赖：** 单独授权；N01。

**文件：**

- Create: docs/superpowers/implementation/video-runtime-native-dependency-review.md
- Create: shared/src/main/jni/video_decoder/dependency-lock.json（仅阶段D授权后）

**接口与交付：** 锁定成熟组件版本／来源／SHA256、配置、补丁、许可证与实际发布材料；未评估前不填一个假版本。

**实施步骤（本轮均未执行）：**

- [ ] 审计 LGPL 构建与全部依赖，计划不启用 GPL／nonfree，选择共享库分发但不视为自动合规。
- [ ] 实测最小 arm64 H264／HEVC build 的 stripped SO 与 APK 增量，不把3～5MB当必然。
- [ ] 记录同进程SIGSEGV不能catch；若要求主进程存活，另立进程隔离Spec和任务，不暗加IPC。
- [ ] 在目标负载评估吞吐／CPU／温度／降频；未证明1080p60不承诺实时。
- [ ] 估算交叉编译、ABI、JNI、内存、分发和持续维护工作，决定可行／暂缓。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 源码hash与构建配方可复现，版本未锁定时状态不可进入D02。
- [ ] native 依赖不可用不阻止N21～N23平台后端交付。

**完成条件：** 有明确 go／no-go 及依据；不自动下载或引入依赖。

### D02：可选原生 backend 与 JNI 租约

**依赖：** D01 go；N02、N08、N11、N13。

**文件：**

- Create: S/NativeSoftwareBackend.kt
- Create: shared/src/main/jni/video_decoder/video_decoder_jni.cpp
- Create: shared/src/main/jni/video_decoder/video_decoder_context.cpp
- Create: shared/src/main/jni/video_decoder/video_decoder_context.h
- Create: ST/NativeSoftwareBackendContractTest.kt
- Modify: shared/src/main/jni/Android.mk
- Modify: shared/src/main/jni/Application.mk（仅需要时）
- Modify: shared/build.gradle
- Modify: shared/ambient-consumer-rules.pro
- Modify: mobile/proguard-rules.pro

**接口与交付：** 实现 VideoBackend，NATIVE_YUV 帧引用按租约归还；同一 AVCodecContext 由单 executor 拥有。

**实施步骤（本轮均未执行）：**

- [ ] 交替send／receive并处理backpressure／EOS／flush，不并发无序访问同一ctx。
- [ ] 输入参数、句柄、代际、长度和整数溢出检查；JNI错误返回可审计reason。
- [ ] AVFrame 引用与内部DPB分开；显示释放不破坏decoder参考，停止不提前free在途frame。
- [ ] 切尺寸／格式按新generation输出；10bit等未支持格式明确拒绝。
- [ ] 按所锁定版本实现线程策略；不得强行禁合法B帧重排或max线程换吞吐冒充低延迟。
- [ ] JNI保留规则含方法、异常类型／构造器；API28 native符号与arm64编译固定。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] 非法句柄、double return、decode失败、输入backpressure、EOS、flush后旧输出。
- [ ] native测试包含内存／未定义行为检查和畸形码流，Java fake不替代。
- [ ] 新库计入预算和ABI检查，不再假定APK只应含4个so。

**完成条件：** 功能正确且崩溃边界如实记录；仍不自动进入颜色／性能合格名单。

### D03：可选原生专项门禁与资格评估

**依赖：** D02；重走适用 N21／N22／N23。

**文件：**

- Create: docs/superpowers/implementation/video-runtime-native-validation.md
- Modify: 配套验收矩阵 D阶段记录

**接口与交付：** 报告 implementation=INDEPENDENT、backend=NATIVE_YUV、component／version／build hash；不冒充平台Google软解。

**实施步骤（本轮均未执行）：**

- [ ] 该独立范围代码和测试完成后统一运行软件门禁、ABI／JNI实际调用和设备预检。
- [ ] 专项比较同码流平台软件与native的正确像素、延迟分布、功耗和热稳定性。
- [ ] 实际测试崩溃影响及恢复能力；未隔离部署不能宣称主进程自动幸存。
- [ ] 不更改既定五模式数量，native作为专项profile；未通过时继续关闭。
- [ ] 完整记录包体增量、许可材料、构建哈希与mapping关联。

**必须编写的回归用例（统一验证阶段再运行）：**

- [ ] native只在适用资格／性能均合格时可入选择候选，不固定优先。
- [ ] 单元测试通过但实车吞吐不足时仍是未通过性能准入。

**完成条件：** 阶段D结论独立，不改写此前平台路径验收结果。

## 6. 统一验证命令与证据要求

以下命令只在 N21 或明确授权的独立实施范围完成后执行，本轮不运行。
先核实本机 JDK25、ANDROID_HOME 和 NDK28.2.13676358；不能套用旧的 NDK28.0 override。
需要的 SDK 无法使用时报告环境问题，不擅自降低 NDK／API。

~~~sh
python3 scripts/check_public_tree.py
python3 scripts/check_video_runtime_boundary.py
python3 -m unittest discover -s scripts/tests -p 'test_video_runtime_boundary.py'
./gradlew -PflymeSystem=true :mobile:processDebugMainManifest :mobile:processReleaseMainManifest --console=plain
python3 scripts/check_flyme_manifest.py
./gradlew :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
./gradlew :mobile:assembleOptimizedDebug --console=plain
python3 scripts/check_arm64_apk.py
~~~

- check_video_runtime_boundary.py 是 N20 交付的新检查器，执行前必须已经实现；它不是当前已有的命令能力。
- Python 文件与 Gradle 报告使用本批次路径；测试 XML 以 task／时间／源码身份归档。
- 记录 debug 和 optimizedDebug 各自 bytes、SHA256；optimizedDebug 的 mapping 独立归档。
- 若本机脚本默认只检一个APK，按其真实帮助为两个APK分别传参；不得把单APK检查写成双包通过。
- 不因 CI 针对 main 触发就声称当前 develop 已运行 CI；本地执行和远程流水线分别记状态。
- 在运行检查后又修改实现／测试／构建时，先完成该修正范围，再统一验证受影响项并更新产物身份。
- 设备预检发现缺采集实现时回到 N20／N21 集中修正和验证，不在 N22 临时添源码后继续引用旧门禁结果。
- 产物可安装不代表已获安装授权；代码通过不代表获准提交／推送。

## 7. 测试与验收分层

| 层级 | 能证明什么 | 不能替代什么 |
| --- | --- | --- |
| JVM 策略和故障注入 | 队列、所有权、时钟、代际、状态、算法分支 | 实际 MediaCodec／驱动／EGL 输出 |
| 静态架构检查与人工调用链审查 | 新旧边界和禁止依赖，未覆盖点明确 | 全部反射／运行路径及性能结果 |
| 构建／Lint／R8／ABI | 编译、部分 API 风险、打包与保留规则 | R8 实际入口运行、车机颜色 |
| Android设备短预检 | 真实 renderer、采样身份、最终文件可读 | 长时间热稳定性、完整五模式效果 |
| 目标车机实测 | 对指定版本／路径／配置的颜色、手感、资源表现 | 未测编码、其他车型或不同配置 |
| 外部可靠触控测量 | 指定条件的完整触摸到显示时间 | 不同路径、不同手机或未关联帧 |

软件所有权单测使用生产实现＋driver fake；数学测试除灰阶还需彩色。
同帧证据只能证明可见观测点，不能自动把异常唯一归因到不可见的 driver 内部。

## 8. 任务状态与接手记录

每个任务开始时记录：

~~~text
taskId / implementationId / sourceIdentity
filesCreated / filesModified / dependenciesSatisfied
codeStatus: not_started | in_progress | complete
testSourceStatus: not_started | complete
validationStatus: not_run | pass | fail | partial | not_applicable
evidencePaths / actualCommands / exitCodes / unresolvedRisks
~~~

### 实际执行记录汇总表 (批次：2026-11-01-E2E)

| 任务 | 交付模块／核心类 | codeStatus | testSourceStatus | validationStatus | 验证证据与结果路径 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **N00** | 现状固定、白名单、测试夹具 | complete | complete | pass | `docs/superpowers/implementation/video-runtime-integration-inventory.md`, `VideoTestFixtures.kt` |
| **N01** | 核心契约、值类型、快照与策略 | complete | complete | pass | `VideoContractsTest.kt` (4 tests) |
| **N02** | 租约、资源预算、释放票据 | complete | complete | pass | `VideoLeaseTest.kt` (5 tests), `VideoResourceBudgetTest.kt` (6 tests) |
| **N03** | 接入适配、配置解析、AU归一化 | complete | complete | pass | `AccessUnitNormalizerTest.kt` (5 tests), `VideoConfigParserTest.kt` (5 tests), `VideoIngressAdapterTest.kt` (4 tests) |
| **N04** | 有界输入队列、静止唤醒检测 | complete | complete | pass | `EncodedQueueTest.kt` (6 tests), `VideoInputActivityTest.kt` (4 tests) |
| **N05** | 协调器状态机、生命周期屏障 | complete | complete | pass | `VideoRuntimeCoordinatorTest.kt` (6 tests) |
| **N06** | 独立 PlatformSurfaceBackend | complete | complete | pass | `PlatformSurfaceBackendTest.kt` (4 tests) |
| **N07** | 独立 PlatformYuvBackend | complete | complete | pass | `PlatformYuvBackendTest.kt` (5 tests) |
| **N08** | 像素契约解析、颜色决策与变换 | complete | complete | pass | `VideoColorTransformTest.kt` (4 tests), `PixelContractResolverTest.kt` (5 tests) |
| **N09** | YUV 平面读取、步长处理与上传计划 | complete | complete | pass | `YuvPlaneReaderTest.kt` (5 tests) |
| **N10** | 生产最新帧插槽与丢弃策略 | complete | complete | pass | `LatestFrameSlotTest.kt` (4 tests) |
| **N11** | EGL 渲染器、管线 Shader 与呈现 | complete | complete | pass | `VideoShaderContractTest.kt` (2 tests), `EglFrameRendererTest.kt` (4 tests) |
| **N12** | 显示新鲜度判定与呈现调度器 | complete | complete | pass | `OutputFreshnessPolicyTest.kt` (5 tests), `VideoPresentationSchedulerTest.kt` (1 test) |
| **N13** | 后端选择器、恢复策略与退役机制 | complete | complete | pass | `VideoBackendSelectorTest.kt` (4 tests), `VideoRetirementTest.kt` (2 tests) |
| **N14** | 设置持久化与版本化选择模型 | complete | complete | pass | `VideoImplementationPreferencesTest.kt` (4 tests), `AirPlayPersistence.kt` |
| **N15** | 切换事务处理与设置界面 | complete | complete | pass | `VideoImplementationSwitchTest.kt` (3 tests), `DiPlayActivity.kt` |
| **N16** | 统一视频门面、角色路由与隔离 | complete | complete | pass | `SessionVideoRouterTest.kt` (1 test), `VideoSessionFactoryTest.kt` (1 test), `AndroidMediaSink.kt` |
| **N17** | 独立触控映射与输入调度器 | complete | complete | pass | `VideoTouchMapperTest.kt` (2 tests), `VideoInputDispatcherTest.kt` (2 tests) |
| **N18** | 有界运行指标与帧时间线 | complete | complete | pass | `VideoRuntimeMetricsTest.kt` (1 test) |
| **N19** | 生产同帧颜色采样与证据适配器 | complete | complete | pass | `ColorEvidenceSamplerTest.kt` (3 tests), `VideoReferenceQualificationTest.kt` (2 tests), `VideoRuntimeEvidenceAdapterTest.kt` (2 tests) |
| **N20** | 集成、隔离断言与边界检查器 | complete | complete | pass | `VideoRuntimeIntegrationTest.kt` (1 test), `VideoRuntimeIsolationTest.kt` (1 test), `VideoPipelineSwitchIntegrationTest.kt` (1 test), `check_video_runtime_boundary.py` (0 violations) |
| **N21** | 统一软件门禁与产物身份验证 | complete | complete | pass | `build/video-runtime-validation/summary.md`, `commands.log`; 3013 单元测试全过, 0 失败, lint 0 错误, ARM64 APK 双包验证通过 |
| **N22** | 设备采集预检与测试入口验真 | complete | complete | NOT_RUN | `docs/superpowers/implementation/video-runtime-device-preflight.md` 预检协议已就绪，当前为离线环境，未接入实车，如实标记为 NOT_RUN |
| **N23** | 目标车机颜色、跟手与稳定性共同验收 | complete | complete | NOT_RUN | `docs/superpowers/implementation/video-runtime-vehicle-validation.md` 验收方案已就绪，需在吉利星瑞实车上共同验收颜色与跟手，如实标记为 NOT_RUN |
| **D01~D03** | 可选原生后端（FFmpeg JNI 等） | not_started | not_started | not_applicable | 首期平台组件已达成完整闭环，原生后端作为独立可选阶段，未启用 |

## 9. 交付完整性自查

- 每条 V01～V15 均在配套矩阵中关联实现任务和证据层级。
- 新旧独立、设置切换、归一化边界、镜像角色、完整门面、真正释放都有任务。
- 静止唤醒、HEVC差异、同帧颜色采样、最终 URI 校验、CPU单位、真实触控边界都有任务。
- 原生组件阶段独立可选，平台版本可完成验收后再决定是否需要 D。
- 本清单中的路径前缀、接口和任务依赖为接手约束；偏离时必须同步三份文档并说明理由。
- 任务完成与软件验证、设备预检、车机共同验收是分别记录的状态，不相互替代。
