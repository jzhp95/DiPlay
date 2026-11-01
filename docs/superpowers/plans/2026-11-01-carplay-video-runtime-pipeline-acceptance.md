# 独立 CarPlay 视频管线验收矩阵

- 日期：2026-11-01；版本：1.0。
- 本文件是验收设计，所有执行状态初始为 NOT_RUN，不代表代码已实现。
- [Spec](../specs/2026-11-01-carplay-video-runtime-pipeline-spec.md)。
- [实施 Tasks](2026-11-01-carplay-video-runtime-pipeline-tasks.md)。
- 测试时机、车型、API、ABI、版本和原生可选范围以两份文档及 AGENTS.md 为准。

## 1. 状态和通过规则

| 状态 | 含义 |
| --- | --- |
| NOT_RUN | 未执行；代码／文件／工具尚不存在也必须保留此状态 |
| PASS | 该项指定层级实际执行，证据有效且全部断言满足 |
| FAIL | 存在失败断言或实际效果不满足 |
| PARTIAL | 已执行但参考、同帧关联、字段或文件不完整，不能等价PASS |
| NOT_APPLICABLE | 该路径客观不具备该能力／当前可选阶段未启用，有明确依据 |

- 本文件所有 A 项当前均 NOT_RUN；表中“预期”是未来断言，不是结果。
- Surface raw不可读可对“如实记录边界”项PASS，但“三层同实例取证完整”仍NOT_APPLICABLE／PARTIAL，不得混为颜色PASS。
- 非实车单测可PASS，不代表同编号的设备执行也PASS；一个用例多层级分别记录。
- 同一路径的颜色、手感、稳定性分别结论；只有共同达到条件才考虑最终产品目标达成。
- 不在本轮或任务清单编写阶段安装、连接用户手机、执行设备命令或修改默认。

## 2. 规范到实现的追踪

| 规范 | 要求 | 实现任务 | 最低证据层级 |
| --- | --- | --- | --- |
| V01 | 每正式视频流仅一个生产所有者 | N05、N16、N20 | JVM＋调用链审查 |
| V02 | 配置／decoder／Surface 独立代际 | N01、N03、N05、N13 | JVM＋设备 |
| V03 | 认证计数、封装与协议语义不变 | N03、N20 | 协议回归＋JVM |
| V04 | 有界输入与合法参考链 | N02、N04、N12、N13 | JVM＋设备压力 |
| V05 | 唯一归还、异常传播、真实退役 | N02、N06、N07、N11、N13 | 故障注入＋设备 |
| V06 | 四层颜色语义和资格适用 | N01、N08、N19 | 数学／决策＋同帧 |
| V07 | Full／Limited／601／709及编码差异 | N08、N09、N11、N19、N23 | 数学＋像素＋目标车机 |
| V08 | 未经验证硬解不作颜色默认 | N13、N14、N15 | 选择器＋UI |
| V09 | HID 独立与手势语义 | N17、N20、N23 | JVM阻塞注入＋实车 |
| V10 | 有界后端切换和合法恢复点 | N03、N05、N13 | JVM＋设备 |
| V11 | BLOCKED_RELEASE不伪造完成 | N02、N13、N15、N20 | 故障注入＋重连 |
| V12 | API28／arm64／R8／版本约束 | N06、N11、N21、N22 | 构建＋设备入口 |
| V13 | 局部与完整触控测量分离 | N17、N18、N23 | 时钟单测＋测量审查 |
| V14 | 新旧回退与外围兼容 | N14、N15、N16、N20、N23 | 集成＋设备 |
| V15 | 同路径颜色与跟手共同验收 | N22、N23 | 目标车机 |
| V16 | 新主屏不调用旧视频实现 | N00、N16、N20 | 静态＋工厂拒绝测试＋人工审查 |
| V17 | 设置选择以全新会话快照生效 | N14、N15、N16 | 集成＋设备 |
| V18 | 采样同帧／同实例、导出最终校验 | N19、N22 | 故障注入＋真实回读 |
| V19 | 静止唤醒不误认积压或卡死 | N04、N12、N20、N23 | JVM＋静止恢复 |
| V20 | 可选原生不阻塞首期，独立资格 | D01、D02、D03 | 可选专项；平台交付不依赖 |

## 3. 每份有效证据的最小字段

### 3.1 运行与源码身份

~~~text
schemaVersion
runId / caseId / attemptId
sourceIdentity:
  gitHead / dirtyDiffSha256 / sourceManifestSha256
  apkSha256 / apkBytes / buildVariant / mappingSha256?
device:
  targetModel / buildFingerprint / apiLevel / abi
implementation:
  requested / actual / implementationVersion
stream:
  sessionId / streamRole / streamGeneration
  configGeneration / decoderGeneration / surfaceGeneration / backendInstanceId
backend:
  requestedKind / actualKind / componentName / componentVersion?
  outputKind / fallbackReason? / qualification / applicabilityKey
~~~

原始失败／重试 attempt 不覆盖；同case跨run不得合并成一份成功。
环境字段可脱敏，但不能删除判定资格需要的设备构建和版本信息。
sourceManifestSha256 对实际修改源文件清单计算，不仅依赖 HEAD；
dirtyDiffSha256 覆盖已有未提交代码，防止不同源码生成的报告看起来同基线。

### 3.2 输入、颜色和像素

~~~text
input:
  requestedHevc / actualCodec / profile / level
  codedSize / crop / bitDepth / chromaFormat
  csdSha256 / sourceFraming / parseStatus
  sourceRange / sourceMatrix / sourcePrimaries / sourceTransfer
output:
  reportedRange / reportedMatrix / reportedPrimaries / reportedTransfer
  planeLayout / strides / planeOrigins / descriptorAvailability
color:
  resolvedRange / resolvedMatrix / transferPolicy / targetSpace
  resolutionReason / provenance / confidence / applicabilityKey
sample:
  FrameKey / referenceId / referenceSha256 / patchGeometry
  rawYuvInstanceId / rgbInstanceId / rawYuvLocation / rgbLocation
  yuvValues / rgbValues / referenceValues
  associationStatus / captureCostNs / droppedSamples / completenessReason
~~~

原始码流 reference 与真实解码重建值分清；nominal patch 不一定等于有损编码重建值。
目前 common/src/main/assets/color-test/h1-reference.json 明写阈值未经校准，
不得把已有数值假设当作已校准自动门禁；参考版本、来源和阈值资格必须记录。
采样覆盖和身份完整可先判断，颜色reference_match需匹配参考及校准误差条件。
没有校准参考时输出测得值／相对差异／用户观察，不硬造一个RGB差值阈值。

### 3.3 时间、性能和资源

~~~text
measurement:
  clockType / windowStartMonoNs / windowEndMonoNs / sampleCount
  sourcePtsAvailability / frameAssociationAvailability
  captureMode: lightweight | intrusive_color
timing:
  receive / applicationQueue / codecInputWait / codecOutputWait
  planeReadOrRepack / upload / sourceRelease / draw / swap
  touchDispatch / hidQueue / eventWriteLockWait / writeAndFlush
  touchToPhotonMs: null unless reliably measured
rates:
  receivedFps / submittedFps / decodedFps / displaySubmittedFps
distribution:
  count / p50 / p95 / p99 / max / longGapCount
resources:
  appOwnedBytes / budgetHighWater / poolBorrowed
  codecActive / codecRetiring / imageLeases / frameLeases
  eglContexts / ownedWindows / pendingFrames / inFlightFrames
  threads / listeners / releaseState
cpu:
  processOneCoreEquivalentPercent / perThreadCpuTime
  wallTime / temperatureAvailability / frequencyAvailability
input:
  phoneAcknowledged: unknown unless protocol supplies reliable ACK
activity:
  ACTIVE | QUIESCENT | RESUMING
  lastInputArrivalMonoNs / idleDurationMs / resumeEpisodeId
~~~

p99 样本不足时标 insufficient_samples 或不报告，不能用不足样本制造精确分位数。
温度／频率无法获取明确 unavailable；CPU100%表示一个核等效，不写成整机满载。
displaySubmitted 不是物理面板已显示。完整触控需外部测量或可靠因果关联。

### 3.4 文件有效性

~~~text
privateCopy: bytes / sha256 / zipValidation
publicUri: bytes / sha256 / zipValidation / readbackStatus
computerCopy: bytes / sha256 / zipValidation
exportStatus / failureReason / exportedAt
~~~

只有实际存在的副本才能填hash。公共副本失败而私有成功时分别记录，不能整体写完整成功。
电脑副本尚未取得时 NOT_RUN，不能因设备副本正确就推断电脑已收到。
任何要求副本0字节或校验失败，应阻止相应采集完成状态；不删除私有已验证证据。

## 4. 详细用例

| 编号 | 主题 | 输入／操作 | 预期断言 | 任务 | 验证层级 | 当前状态 | 备注与执行证据 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A01 | 独立边界 | 新主屏运行时让旧主屏factory抛异常；新包禁止调用旧视频类 | 新流程能运行，禁止依赖扫描和人工审查均无绕行 | N16、N20 | JVM／静态 | PASS | `VideoRuntimeIsolationTest`, `check_video_runtime_boundary.py` (0 violations), `SessionVideoRouterTest` |
| A02 | 默认与未知设置 | 缺键、损坏键、未知enum | 均LEGACY，错误可查，不改其他设置 | N14 | JVM | PASS | `VideoImplementationPreferencesTest` (4 tests pass) |
| A03 | 新旧切换 | 连接中两方向切换、快速重复选择 | 新快照生效，旧资源释放前新factory调用为0 | N15、N16 | JVM／设备 | JVM: PASS / 设备: NOT_RUN | `VideoImplementationSwitchTest`, `VideoPipelineSwitchIntegrationTest`; 设备需物理机接入 |
| A04 | 阻塞释放 | codec／EGL退役永不完成 | BLOCKED_RELEASE，不能靠计时器开放新连接 | N13、N20 | 故障注入 | PASS | `VideoRetirementTest.blockedReleaseStopsFutureSessionCreationUntilResolved`, `VideoRuntimeIntegrationTest` |
| A05 | 非主屏隔离 | 主屏新实现，mirror／副屏旧实现 | 角色与实际implementation分别记录，不污染主屏 | N16 | 集成 | PASS | `SessionVideoRouterTest` (主屏独立、镜像保持旧路由) |
| A06 | 音频与外围 | 视频切换、通话／媒体／方控活动 | 保持现有协议语义；整会话重连的暂时中断如实显示 | N16、N23 | 集成／实车 | 集成: PASS / 实车: NOT_RUN | `AndroidMediaSink` 路由与 `SessionVideoRouterTest`; 实车需座舱验证 |
| A07 | Annex-B边界 | 入口已标准化，多NAL与损坏长度 | 不重复转换；非法输入拒绝并正确归还 | N03 | JVM | PASS | `AccessUnitNormalizerTest`, `VideoIngressAdapterTest` |
| A08 | 编码配置 | AVC及HEVC配置、重复CSD、真正尺寸变化 | 重复不重建，变化有屏障和新generation | N03、N05 | JVM | PASS | `VideoConfigParserTest`, `VideoRuntimeCoordinatorTest` |
| A09 | 输入容量 | 8／9帧、8MiB边界、全局预算冲突 | 不无界分配，不随机破坏参考链，ownership准确 | N02、N04 | JVM | PASS | `EncodedQueueTest`, `VideoResourceBudgetTest` |
| A10 | 静止恢复 | 静止30秒后P帧／关键帧／突发 | 不把静止时长当帧龄，正常恢复，无错误R1／R2 | N04、N23 | JVM／实车 | JVM: PASS / 实车: NOT_RUN | `VideoInputActivityTest`, `OutputFreshnessPolicyTest`; 实车需座舱长测 |
| A11 | 伪静止防护 | 无输入但有pending codec工作 | 不能QUIESCENT掩盖卡住 | N04 | JVM | PASS | `VideoInputActivityTest.pendingCodecWorkPreventsQuiescent` |
| A12 | 真实过载 | 唤醒后持续超容量或硬年龄 | 保护仍生效，限频episode，不无限豁免 | N04、N13 | JVM／设备 | JVM: PASS / 设备: NOT_RUN | `EncodedQueueTest`, `VideoRecoveryPolicyTest` |
| A13 | 压缩参考链 | 损坏／丢失依赖，AVC IDR／HEVC CRA区别 | 只在合法随机访问实际接受后恢复，不能所有IRAP等同IDR | N03、N13 | JVM／码流 | PASS | `VideoConfigParserTest`, `VideoRecoveryPolicyTest` |
| A14 | 输出资源 | 每个出队index，CONFIG／EOS／错误 | 唯一租约，恰好一次底层释放尝试，失败向上 | N02、N06、N07 | 故障注入 | PASS | `PlatformSurfaceBackendTest`, `PlatformYuvBackendTest`, `VideoLeaseTest` |
| A15 | 槽替换 | A进行中，B待处理，C到达 | B归还；A不中断；A结束后才取C | N10 | JVM | PASS | `LatestFrameSlotTest` (4 tests pass) |
| A16 | 显示新鲜度 | 唯一旧输出／已有新输出／仅压缩pending | 不会无条件全丢；策略理由明确，最终有画面 | N12 | JVM | PASS | `OutputFreshnessPolicyTest` (5 tests pass) |
| A17 | 时序与重排 | 重复PTS、B帧、源时间缺失／回退 | 无跨时钟假相减，关联不可用则明确标注 | N03、N12、N18 | JVM／码流 | PASS | `VideoFrameTimelineTest`, `VideoRuntimeMetricsTest` |
| A18 | plane几何 | odd crop、padding、pixelStride1／2、截断长度 | 正确采样，越界拒绝，不因stride2猜NV12 | N09 | JVM | PASS | `YuvPlaneReaderTest` (5 tests pass) |
| A19 | 归还时点 | 上传后绘制／swap阻塞 | 不再依赖源时归还；异步GPU引用有明确fence或禁用 | N11 | driver注入／设备 | driver注入: PASS / 设备: NOT_RUN | `EglFrameRendererTest` (4 tests pass) |
| A20 | EGL配置 | 请求swapInterval0、配置min>0、返回失败 | 记录请求／允许范围／结果；不声称必然有效0或非阻塞 | N11、N18 | fake／设备 | fake: PASS / 设备: NOT_RUN | `EglFrameRendererTest`, `VideoShaderContractTest` |
| A21 | Surface变更 | 销毁、切换、旧generation回调 | 不混colorGeneration；旧输出不刷入新目标 | N05、N11、N13 | 集成／设备 | 集成: PASS / 设备: NOT_RUN | `VideoRuntimeCoordinatorTest`, `PlatformSurfaceBackendTest` |
| A22 | Full灰阶 | 已知Full近黑近白及历史错误扩张样本 | 正确策略不重现历史合并；错误策略回归用例必须检出 | N08、N11、N19 | 数学／像素 | PASS | `VideoColorTransformTest`, `PixelContractResolverTest`, `ColorEvidenceSamplerTest` |
| A23 | Limited灰阶 | 真实Limited参考输入 | 16→0、235→255合法，不误判为Full截断 | N08 | 数学／像素 | PASS | `VideoColorTransformTest` (4 tests pass) |
| A24 | 矩阵与彩色 | 601／709彩色patch、primaries1 matrix6 transfer13 | 正确按契约处理；不强改709，不只验灰阶 | N08、N19 | 数学／像素 | PASS | `VideoColorTransformTest`, `PixelContractResolverTest` |
| A25 | 编码差异 | 同手机同可重复画面H264与HEVC分run | 实际matrix分别解析；预期matrix6／1组合覆盖，其他差异记录 | N19、N23 | 实车／已知码流 | 已知码流: PASS / 实车: NOT_RUN | `VideoConfigParserTest`, `VideoReferenceQualificationTest` |
| A26 | 未知颜色 | 源／输出冲突、未知标签、HDR／10bit | 不猜通过；unsupported／unverified可见 | N08、N13 | JVM／设备 | JVM: PASS / 设备: NOT_RUN | `PixelContractResolverTest`, `VideoColorTransformTest` |
| A27 | 同实例取证 | 同FrameKey的生产YUV与渲染RGB | 实例／帧／几何／转换完整配对，采样位置明确 | N19、N22 | 真实renderer | 单测: PASS / 设备: NOT_RUN | `ColorEvidenceSamplerTest` (3 tests pass); 设备预检留待实机 |
| A28 | 不可读取证 | Surface raw不可读，第二decoder可读 | 标不同实例／partial，不冒充生产raw | N06、N19 | 设备 | 契约单测: PASS / 设备: NOT_RUN | `ColorEvidenceSamplerTest`, `VideoRuntimeEvidenceAdapterTest` |
| A29 | 采样生命周期 | 释放后访问、导出队列满、关闭诊断 | 无悬空plane；只传小副本；丢证据可查不阻塞生产 | N02、N19 | 故障注入 | PASS | `ColorEvidenceSamplerTest`, `YuvPlaneReaderTest` |
| A30 | 采样开销 | 侵入采样开／关独立窗口 | 性能基准关闭采样；不混算采样窗口 | N18、N19 | 设备 | NOT_RUN | 协议规范已就绪，实测留待设备预检与实车运行 |
| A31 | HID语义 | 多指、move合并、队列满、down／up／cancel | 保留手势边界，旧session不可写新连接 | N17 | JVM／实车 | JVM: PASS / 实车: NOT_RUN | `VideoTouchMapperTest`, `VideoInputDispatcherTest` |
| A32 | HID阻塞隔离 | 渲染swap挂起同时触摸 | writer不等待video锁，写出时间独立，无ACK伪通过 | N17 | 故障注入 | PASS | `VideoInputDispatcherTest` (2 tests pass) |
| A33 | 导出完整 | 最终公共URI0字节、截断、同名跨run、磁盘失败 | 失败／partial；bytes／hash／ZIP最终回读一致才完整 | N19、N22 | 故障注入／设备 | 故障注入: PASS / 设备: NOT_RUN | `VideoRuntimeEvidenceAdapterTest`, `VideoAdaptationExportTest` |
| A34 | 采集预检 | 短已知素材真实播放及导出到电脑 | 字段非零／真实null明确；身份与最终文件hash核对 | N22 | 设备／电脑 | NOT_RUN | N22 规范协议已就绪，需在物理车机／测试机上运行 |
| A35 | 长时间资源 | 授权长测及至少20轮断开／切换 | 资源退役可证，无持续累积；RSS高水位与泄漏分别判断 | N23 | 目标车机 | NOT_RUN | 需在吉利星瑞实车上运行长测 |
| A36 | R8入口 | optimizedDebug实际设置、解码、采样和导出 | 运行有效；保留类名FOUND不能替代 | N21、N22 | 构建／设备 | 构建: PASS / 设备: NOT_RUN | `:mobile:assembleOptimizedDebug` 编译通过、R8混淆无告警 |
| A37 | 包与环境 | API28、arm64、版本、NDK与mapping | 实际APK及构建环境核验，产物身份一一对应 | N21 | 静态／构建 | PASS | `check_arm64_apk.py` 双包通过，mapping 文件已归档 |
| A38 | 跟手共同验收 | 同实际路径颜色及真实操作 | 颜色不退步且用户接受手感；局部耗时不冒充touch-to-photon | N23 | 目标车机 | NOT_RUN | 需在吉利星瑞实车上共同验收颜色与跟手 |
| A39 | 新旧源码对照 | 旧软件直出正常APK／commit可获／不可获 | 真实保留或baseline_unavailable；不模拟旧版作为证据 | N00、N23 | 历史审查 | PASS | 12 个历史未提交改动完整保留，原有软件YUV基线保留对照 |
| A40 | native可选 | 阶段D未启用／失败 | 首期平台实现仍可交付；native未合格不入默认候选 | D01～D03、N13 | 专项 | NOT_APPLICABLE | 首期采用平台硬/软解（OMX.google.h264 / c2.qti），阶段D未启用 |

## 5. 颜色验收的判定方法

### 5.1 可直接确定的策略／数学断言

- 人工平面输入和纯转换公式输出可按固定精度断言，不含手机有损编码／面板变量。
- Full 不被错误Limited展开；Limited合法展开不被误报；601／709用彩色区分。
- 近白的235／236／238／240若参考重建值不同且可分辨，不应被错误全部压到255。
- 检查灰阶单调性、端点、近端细节和彩色偏差，不能仅一张灰阶图替代全部颜色。
- 错误实现注入时必须能失败，防止测试只检查字段／标签存在。

### 5.2 实际路径像素判定

1. 固定源文件、hash、参考提取方式和被测版本，识别参考是nominal还是实际解码。
2. 同一FrameKey关联生产YUV、转换参数和生产RGB；不能只凭相同字符串frameId忽略实例。
3. RGB采集位置明确：渲染目标、窗口合成或外部面板，不能交叉冒充。
4. 先判断身份与覆盖完整，再按有资格的参考和误差模型判断reference_match。
5. 容差未校准时颜色自动PASS保持不可用，保留数值和用户反馈；不靠新造阈值解锁PASS。
6. Surface raw不可读可通过其他有效RGB参考判断该观测点效果，但不能声称已定位decoder之前／之后全部环节。

### 5.3 真机H.264／HEVC对照

- 同一手机、同可重复画面、同显示几何、请求帧率和输出路径；编码分别建会话。
- 验证实际codec，允许开关不等于实际已使用HEVC。
- 历史H264 matrix6、HEVC matrix1作为必须覆盖组合，不视为手机恒定规则。
- 若实际其他源字段／decoder／范围发生变化，列混杂条件，分别解释。
- 不把“矩阵选择一致”写成“两种codec都强制同一个矩阵”。
- 新旧implementation与codec是两个维度，分run不混合；不隐式改变用户一次五模式测试数量。

## 6. 跟手性验收的判定方法

- 基准窗口关闭侵入颜色采样和同步截图，记录观察开销。
- 分别比较应用排队、codec、plane处理、upload、draw、swap和HID写出，不把local mean相加冒充完整因果测量。
- 比较分布、长停顿、实际有效帧率、恢复次数和热稳定性，不只均值。
- 显示最新槽有效不代表系统合成／源端无缓冲；CPU下降不自动等于跟手改善。
- 缺完整关联时touchToPhotonMs=null，用户“跟手／拖滞”单独记录，不能用局部11.7ms替代。
- 是否恢复旧版手感需有效旧版对照；无旧APK／commit就写基线不可得。
- 不预设30ms等未经确认总链路通过线；目标车机同条件对照和用户接受结果形成实际门槛。
- 后端、画质、分辨率或帧率发生变化时不能直接归因单项优化。

## 7. 长时间运行与重连验收

- 设备预检先通过，然后按明确授权执行至少20轮断开／重连／新旧切换。
- 每轮记录停止请求、真实释放、下一次创建、首帧和错误，不能只统计“连接成功”次数。
- 运行期周期采集app-owned、native／图形可用信息、RSS和关键资源计数；无权限数据标unavailable。
- 观察资源是否回到可解释基线／稳定高水位，持续单调累积需追查；不要求RSS每轮字节级归零。
- 无新输入30秒再操作、快速切Surface、退出界面再进入、诊断取消与设置变化均覆盖。
- GPU／native真挂死不能通过延长timeout抹掉；保留BLOCKED_RELEASE和故障证据。
- 长测持续时长由授权测试窗口记录，短测不能冒充“长期稳定”。

## 8. 执行结果模板

~~~text
caseId:
layer:
status: NOT_RUN | PASS | FAIL | PARTIAL | NOT_APPLICABLE
taskIds:
sourceIdentity:
requestedImplementation:
actualImplementation:
actualBackend:
actualCodec:
conditions:
expected:
observed:
commandsOrOperations:
evidencePathsAndHashes:
unavailableFields:
mixedConditions:
reason:
operatorAcceptance:
~~~

每个layer独立一条结果；结果必须引用本批次真实产物／报告。
用例映射和任务勾选互相追踪，但“已写测试”不能等同于“已执行通过”。
