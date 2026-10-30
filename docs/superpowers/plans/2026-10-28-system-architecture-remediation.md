# DiPlay 系统架构改造设计与实施计划

> 执行说明：按本文任务依赖实施，先完成当前授权范围内全部代码与测试用例编写，再统一执行测试。本文是设计与任务清单，不表示代码已实施或验收通过。

- 日期：2026-10-28
- 本次增补：2026-10-29 根据上游 DiPlay v0.2.22 预发布版筛选通用修复，新增 M01—M05 移植任务；另在第 18 节记录本地 origin/main 对比、候选修复和证据差异；不整体合并上游分支。
- 状态（2026-10-30 更新）：T00—T22 与 M01—M05 已完成本地软件实施、复核和验证；完整 CI 命令退出码 0，Flyme 10／shared 1350／common 1501 项零失败，shared 1 项主机端口环境跳过。T24 本地交付归档完成；T23 目标车机实车验收未执行，整体验收尚未结项。最终证据见第 20 节。
- 项目：DiPlay；目标为吉利星瑞 2026 款昆仑，Flyme Auto，Android 9／API 28。
- 审查基线：HEAD 为 ea408540e41aec987f879b624c7c7ac63c32be8e，同时包含当前未提交的诊断改造、EAS 播放状态与五项通用改进。HEAD 单独不足以复现本次审查。
- 当前版本：0.2.26／versionCode 45；用户于 2026-10-28 明确要求首项任务同时升版。冻结前版本 0.2.25／44 单独保留。
- 后续用户指令（2026-10-28）：仅保留 Flyme 构建；正式包名 com.shihab.diplay.flyme，测试包名 com.shihab.diplay.flyme.test。下文待实施任务按此约束执行；此前普通／Flyme 双包验证仍保留为历史基线。
- 目标：在保留已验证功能的基础上，明确会话与资源归属，修补诊断监管盲区，统一视频配置及命令排队规则，恢复可信的回归保障。
- 技术栈：Kotlin／Java、Android Service／Handler／Executor、MediaCodec／Surface／EGL、现有 AirPlay／iAP2 实现、JUnit／Robolectric、Gradle。
- 阅读顺序：第 2 节看问题；第 4—9 节看设计；第 11 节按任务实施；第 12—14 节按场景验证与验收。

## 1. 范围、原则与明确不做的事

### 1.1 必须遵守的约束

1. 保持现有 mobile、common、shared、flyme-eas 模块，不新增独立应用；本轮不以增加 Gradle 模块作为拆分手段。
2. Android 9／API 28 为运行基准。高版本 API 必须保留 API 28 路径，不新增 Android 9 以下兼容分支。
3. 保留 CH341 已验证链路、MFi、USB／无线连接、原车 HFP、媒体信息与 EAS 播放状态上报。
4. 保留当前方控唯一 Flyme 入口、按键去重、通话门禁、焦点归属；不恢复其他厂商按键通路。
5. 不引入 HUD、25 款星瑞专属优化、BYD／DiLink 或其他车型专属功能。
6. 保留用户偏好、现有设置键和默认值。设置是否立即生效、下次连接生效、需要用户确认重连，必须沿用现有语义。
7. 先完成当前任务范围内全部代码修改，再统一测试；编码期间允许静态检查和编写用例，不边写边反复跑测试。统一测试失败后，集中修正，再验证失败和受影响范围。
8. 仅用户明确要求时升版本。提交、安装、发布按用户当次授权执行。
9. 本次架构工作不承诺直接修复颜色、模糊或物理触摸延迟；这些问题继续依靠同源样本与目标车机证据判定。
10. 报告不得包含 MFi 私钥、远端认证 token、Wi-Fi 密码等秘密；设备标识按现有脱敏规则处理。

### 1.2 改造策略选择

| 方案 | 优点 | 代价与风险 | 决策 |
|---|---|---|---|
| 继续在当前类里局部打补丁 | 短期改动小 | 所有权和状态分散继续扩大 | 只用于范围明确的缺陷修复 |
| 渐进收敛职责与运行边界 | 复用现有协议、渲染和资源保护，可逐项回退 | 需要迁移桥接和契约测试 | 本方案采用 |
| 一次性重写架构／全面更换技术栈 | 新结构整齐 | 已验证车机行为容易丢失，验证成本最高 | 不采用 |

不把“文件小于多少行”当作验收标准。目标是职责清楚、状态只有一个权威来源、资源释放可证明、失败可以收尾。

## 2. 问题清单与代码证据

以下位置为审查时的定位点，实施时以符号和调用关系为准；行号可能随代码变化。

| ID／优先级 | 已确认的结构事实 | 影响与证据边界 | 主要位置 |
|---|---|---|---|
| A01／高 | Activity 创建、重连、关闭控制器和媒体输出；Service 主要保活；全局对象保存 Activity owner 与停止闭包 | 页面与连接生命周期耦合；长期回调可保留旧页面。不是本次已复现的实车故障 | CarPlayHostActivity.startCarPlay／adoptBackgroundSession／shutdown；DiPlaySessionService；CarPlayBackgroundSession |
| A02／高 | 诊断主线程同步写状态、fd.sync、素材哈希；超时也在主 Handler | 慢盘可同时阻塞 UI 与 watchdog。源码可确认风险，未实测 ANR | DecoderDiagnosticsService.stage／nextCase／sealAndPublish；DecoderDiagnosticsRun.atomic |
| A03／高 | 较早完整 common 有 99 项历史失败；T00 冻结前全量有 100 项，含 1 项新增未定位 HTTP401 | 不能从“未新增失败”推导整体无回归；不等于 99 个产品 bug | 已归档 validation-results.json；android.yml |
| A04／中高 | 视频开关先由 CarPlayVideoOutput 归并，之后 Host、Sink 再各自选择或回退 | 请求路径、实际路径、回退结果分散，正式投屏与诊断可能逐步偏离 | CarPlayVideoOutput；CarPlayVideoSurface；AndroidMediaSink |
| A05／中 | 新自动入口走 Service，旧自动推进／恢复逻辑仍留在页面 | 两套状态和清理规则增加维护负担；未证明同时执行 | ColorTestPage.startAutomatic／startLegacyAutomatic／resumeAutomatic；AutomaticVideoSuite |
| A06／中 | 触摸逐条进入单线程队列，媒体独立队列最终仍共享事件写入锁 | 发送拥塞可能积累过期移动；共享加密锁是必要边界，不能直接删除 | CarPlayController.sendTouch；MediaCommandQueue；AirPlaySession.sendCommand |
| A07／中 | Host、设置 Activity、Controller、Sink 分别约 6765／4109／3283／3022 行，跨职责状态集中 | 一次修改需要理解多个子系统，扩大回归面；文件长本身不证明 bug | 四个对应类 |

源码入口：

- [会话创建与保留](../../../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt)：startCarPlay 约 5207 行；创建控制器约 5264 行；全局会话对象约 6679 行。
- [后台保活服务](../../../common/src/main/java/com/shilapi/xcertplay/DiPlaySessionService.kt)：onStartCommand、onTaskRemoved。
- [诊断监管服务](../../../common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsService.kt)：stage 约 100 行、素材哈希约 247 行、超时约 326 行。
- [诊断持久化](../../../common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsRun.kt)：atomic 约 24 行。
- [视频选项归并](../../../common/src/main/java/com/shilapi/xcertplay/CarPlayVideoOutput.kt)、[Surface 选择](../../../common/src/main/java/com/shilapi/xcertplay/CarPlayVideoSurface.kt)、[Android 媒体实现](../../../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt)。
- [协议到渲染边界](../../../shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt)：MediaSink 与流注册 owner。
- [命令入口](../../../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)：sendTouch 约 535 行。
- [事件发送锁](../../../shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt)：sendCommand／sendCommandLocked 约 290 行。
- [新旧自动诊断入口](../../../common/src/main/java/com/shilapi/xcertplay/ColorTestPage.kt)：约 641—728 行。
- [已有验证记录](../../../../artifacts/diplay-five-improvements-20261027/validation-results.json)。
- [CI](../../../.github/workflows/android.yml)、[项目准则](../../../AGENTS.md)。

## 3. 保留的架构与行为

| 已有能力 | 保留方式 |
|---|---|
| AirPlay／iAP2 协议、配对、认证、USB／无线传输 | 继续复用，职责迁移不改变协议字段、握手顺序和重试预算 |
| MediaSink 接口 | 保持协议向媒体后端交付数据的入口 |
| 音视频按会话与 registration 判断 owner | 原样保留，不以“当前 type 相同”替代身份校验 |
| Surface 分离、退休 decoder 跟踪、释放确认 | 移动所有者时保留屏障；超时不假装资源已释放 |
| 软件直出、软件 YUV、硬件 YUV、MTK 回退 | 保留现有配置与回退结果，第一轮不调画质参数 |
| 独立媒体队列与切歌续播 | 保留显式暂停意图、会话检查和真实元数据判断 |
| AirPlay CSeq | 保留成功 write／flush 后提交序号；异常关闭会话，不重用加密状态 |
| 返回键行为 | 保留本地浮层关闭优先、CarPlay 返回、双击回桌面的既定行为 |
| Flyme 适配 | 继续由 FlymeWheelIntegration 接应用，flyme-eas 管理 Binder／焦点／去重 |
| 诊断三层证据 | 保留标准输入、同源回放、正式主屏观测；不能互相冒充 |
| Download 交付 | 保留内部封存、ZIP 完整性检查、公共文件读回校验与重导出 |

## 4. 目标架构与资源归属

~~~mermaid
flowchart TB
    UI[设置页与投屏页] --> Client[SessionClient：命令与订阅]
    Client --> Service[DiPlaySessionService：系统生命周期]
    Service --> Runtime[SessionRuntime：唯一会话所有者]
    Runtime --> Controller[CarPlayController：连接和协议编排]
    Runtime --> Media[AndroidMediaSink：媒体门面]
    Media --> Video[AndroidVideoPipeline：解码和输出资源]
    Media --> Audio[AndroidAudioPipeline：音频和麦克风资源]
    UI --> Binding[ProjectionSurfaceBinding：页面 Surface 交接]
    Binding --> Runtime
    Runtime --> Flyme[现有媒体与 Flyme 适配入口]
    Plan[VideoPipelinePlan：请求与解析结果] --> Video
    Diag[DecoderDiagnosticsService：诊断监管] --> Lease[带会话身份的视频诊断租约]
    Lease --> Runtime
    Diag --> Store[串行诊断存储]
    Diag --> Worker[现有独立诊断进程]
    Worker --> Plan
    Worker --> Publish[封存与公开目录交付]
~~~

### 4.1 组件职责

| 组件／拟建位置 | 负责 | 不负责 |
|---|---|---|
| common/session/SessionRuntime.kt | 当前会话、代次、创建／重连／停止、资源终止结果、媒体适配绑定 | View、窗口、权限弹窗 |
| common/session/SessionState.kt | 不可变状态、命令结果、订阅快照 | Controller／Surface 等可变资源 |
| common/session/SessionClient.kt | Service 本地 Binder 连接、命令转交、订阅解除 | 自建第二条会话 |
| common/session/SessionFactory.kt | 使用 applicationContext 与配置快照构造现有 Controller／Sink／Engine | 持有 Activity、修改用户设置 |
| DiPlaySessionService | 前台通知、系统启动与结束、持有一个 SessionRuntime | 直接处理协议或 Surface |
| common/session/ProjectionSurfaceBinding.kt | 页面 Surface 生命周期、绑定身份、释放交接 | 拥有网络连接或媒体焦点 |
| shared/media/AndroidVideoPipeline.kt | 视频解码器、Surface、停车输出、镜像、退休集合和诊断租约 | 音频路由 |
| shared/media/AndroidAudioPipeline.kt | AudioRenderer、焦点协调、麦克风、HFP 策略与音频恢复 | 页面与视频选项 |
| shared/media/VideoPipelinePlan.kt | 视频方案的数据模型与纯解析逻辑 | 查询 View、读偏好、调用 codec |
| common/diagnostics/DiagnosticRunStore.kt | 异步文件操作、不可变检查点、错误结果 | 推进测试或写 UI |
| common/diagnostics/DiagnosticRunCoordinator.kt | 单次诊断的状态、身份、超时、恢复与结束 | 同步磁盘或 native 调用 |

上述路径均相对于各模块的 src/main/java/com/shilapi/xcertplay/。名称为拟新增设计，不代表代码已经存在。保持现有包的 internal 可见性边界，只有确需跨 common／shared 的模型设为 public。

### 4.2 唯一所有者规则

1. Service 持有 Runtime；Runtime 持有当前 Controller、Sink、媒体适配绑定和停止过程。
2. Activity 只持有订阅与 Surface 绑定；Activity 销毁不等于连接结束。
3. 一条运行会话有 sessionId 和递增 generation；每个异步回调必须携带创建时的身份。
4. 每个 UI 绑定另有 bindingId。旧页面销毁不能拆掉新页面 Surface。
5. 诊断有 runId、requestId 和基于会话代次的 leaseId。旧诊断结束不能恢复已经更换的生产会话。
6. UI 快照不包含 Controller、Sink、Activity、Bitmap 或 Surface。尺寸、主题、连接状态和视频方案以值对象发布。
7. 长期回调只捕获 applicationContext、会话身份和运行组件；页面回调仅存在于可显式解除的订阅内。

## 5. 会话管理与生命周期设计

### 5.1 状态模型

新增 RuntimePhase：IDLE、WAITING_PREREQUISITES、STARTING、RUNNING、RECONNECTING、STOPPING、BLOCKED_RELEASE、FAILED。

CarPlayStatus 保留为协议进度，不直接充当整个运行状态。协议连接、视频画面、音频播放、UI 可见性分别记录；不能把“收到一帧”或“Activity onResume”当成全部链路就绪。

| 事件 | 处理与状态 |
|---|---|
| 用户连接 | 权限、配置与窗口协商信息齐备后 STARTING；缺失则 WAITING_PREREQUISITES |
| 协议达到现有成功条件 | RUNNING，并发布现有 CarPlayStatus |
| Activity 离开／回来 | 解除／恢复输出绑定，运行会话保持；沿用现有 500ms 后台停车策略 |
| Activity 重建 | 新订阅一次性拿到会话、协议进度、显示信息、主题及实际视频方案 |
| 断线 | 沿用重试预算进入 RECONNECTING；同代次只接受一个重连请求 |
| 明确停止 | STOPPING，顺序解除适配、终止会话、确认媒体资源释放 |
| 停止等待超时 | BLOCKED_RELEASE，保留退休资源引用；拒绝争抢同一资源的新启动 |
| 旧资源后来确认退出 | 清理退休集合；若仍有当前有效启动意图，再继续 |
| 进程死亡后重新启动 | 不恢复 Controller／Surface 等对象；恢复配置与中断记录，按既有自动连接设置决定是否新建连接 |

### 5.2 接口合同

以下为拟新增合同，供任务间对接；不是已存在 API，也不是完整实现：

~~~kotlin
data class SessionIdentity(val id: String, val generation: Long)
data class CommandReceipt(val accepted: Boolean, val reason: String?)
data class SessionState(
    val identity: SessionIdentity?,
    val phase: RuntimePhase,
    val protocolStatus: CarPlayStatus?,
    val videoPlan: VideoPipelinePlan?,
    val output: VideoOutputObservation?,
    val failure: String?
)
interface SessionObserver { fun changed(state: SessionState) }
interface SessionSubscription : java.io.Closeable
~~~

SessionRuntime 的公开操作固定为：

| 方法 | 语义 |
|---|---|
| start(request: SessionStartRequest): CommandReceipt | 同配置重复请求幂等；运行中更换配置走明确重连流程 |
| reconnect(reason: String): CommandReceipt | 同代次合并重复请求，不重置既定重试预算 |
| stop(reason: String, completed: (StopResult) -> Unit) | completed 只报告本次停止是否真实释放，不能把请求受理当作完成 |
| subscribe(observer: SessionObserver): SessionSubscription | 在状态串行线程登记并投递完整快照，close 后不再收到事件 |
| snapshot(): SessionState | 返回已发布不可变快照，不读文件、不等待 native |
| bindOutput(binding: OutputBinding): CommandReceipt | binding 包含 bindingId、会话身份、Surface 和所有权类型 |
| unbindOutput(bindingId: String) | 只影响匹配绑定，不影响新页面的绑定 |

SessionStartRequest 含 CarPlayRuntimeConfig、AirPlayConfig、协商显示数据、视频请求方案；身份／配对存储由 SessionFactory 注入，不从 Activity 传入捕获页面的闭包。StopResult 含 released、reason、retainedResourceCount。OutputBinding 是运行内部对象，不放入可序列化 SessionState。

### 5.3 线程与停止规则

- 主 Handler 仅串行处理 Runtime 状态与 UI 订阅；阻塞的 USB、Binder、codec 和释放等待保持在各自 worker。
- 避免引入协程框架或把所有线程重写一遍；先明确现有线程的所有权与退出责任。
- stop 幂等，多个调用者等待同一个终止结果；重连和停止不能各自关同一资源后各自启动。
- 保留现有 Controller 4 秒等待预算、Sink 2 秒等待预算和 Surface 分离预算的语义；预算是等待上限，不是强行释放证明。
- SurfaceTexture 包装由应用负责；SurfaceHolder 的 Surface 属于框架。迁移必须保留现有 detach 确认，不能为了异步化提前释放 Surface。
- Service 主动 stopSelf 在 Runtime 收尾后执行；系统强制销毁时做尽力清理并记录中断，不声称已完成所有 native 释放。
- onTaskRemoved 沿用当前明确断开行为；按返回键回桌面不等同于划掉任务。
- 删除 Activity 内 teardownExecutor、airPlayCommandExecutor 前，先把其任务和结束责任完整迁给 Runtime／现有协议调度层。

### 5.4 迁移方法

先建立 Runtime 与工厂，再让 Activity 使用它；迁移期间只允许一个真正创建 Controller 的入口。CarPlayBackgroundSession 暂作兼容桥，随后逐个迁移 CarPlayMediaKeys、FlymeWheelIntegration、诊断、地图与设置消费者。最终删除它对 Activity owner、stopAction、Controller／Sink 快照的持有，不留下两套启动路径。

## 6. 视频与媒体职责设计

### 6.1 三份数据，不混用

- VideoPipelineRequest：用户偏好或诊断用例提出的请求。
- VideoPipelinePlan：结合能力与窗口条件解析出的方案。
- VideoOutputObservation：实际创建的 codec、实际输出、颜色格式、回退原因与时间。

VideoPipelinePlan 的必需字段：

| 字段 | 内容 |
|---|---|
| planId／schemaVersion | 本轮方案身份与格式版本 |
| codec | H.264 或 HEVC |
| decodePolicy | 系统默认、严格软件、硬件 YUV 等策略；不能把“默认”写成已证明硬解 |
| output | TEXTURE、SURFACE、APP_YUV |
| pacing | IMMEDIATE 或 TIMESTAMPED，附既有 delay 参数 |
| colorPolicy | 源 SPS／format 信息、显式覆盖及应用转换参数；分别保留来源 |
| size／fps | 协商尺寸与请求帧率 |
| vendorPolicy | 现有 Qualcomm／MTK 选项与支持依据 |
| fallbackPolicy | 哪些失败允许回退，回退目标是什么 |
| purpose | LIVE 或 DIAGNOSTIC；诊断严格性不得泄漏到日常配置 |

第一轮保持当前优先级：硬件 YUV 优先；软件直出／软件 YUV 强制直接输出并关闭平滑；普通模式维持当前 Surface 选择。完整迁移 truth table 后才允许改优先级。

### 6.2 同一解析入口

- 偏好读取在 common 的工厂／配置适配器进行。
- 解码能力与窗口条件形成只读输入；纯 resolver 不调用 MediaCodecList、View 或 SharedPreferences。
- 正式投屏、标准片、同源回放都调用 resolver；诊断显式声明严格路径和中性画面参数。
- 发生回退生成新的 Observation，保留请求和原因；不能修改原请求冒充成功。
- 例如请求硬件 YUV，实际回退系统 Surface：该用例应标 requested_path_not_executed／fallback，而不是“硬件 YUV 通过”。
- 不依据“吉利”或“MTK”字样自动套用其他车型参数；复用现有能力探测，不能以本轮重构扩大机型匹配范围。

### 6.3 Sink 拆分顺序

先将 VideoDecoder、ParkingOutput 和视频资源集合移入 AndroidVideoPipeline，再将 AudioRenderer、AudioFocusCoordinator、麦克风集合移入 AndroidAudioPipeline。AndroidMediaSink 保留 MediaSink 门面和必要桥接。

迁移不改锁顺序、回调线程、队列容量、帧调度阈值、音频增益与重试参数。每次资源由原对象转交后，原对象不再拥有独立 close 路径。

音频媒体状态回调、外部焦点转发、HFP 铃声策略、导航增益继续复用现有实现。视频诊断只冻结视频，不停止音乐、电话或更换媒体焦点。

## 7. 诊断监管、持久化与交付设计

### 7.1 修复主线程 I/O

新增 DiagnosticRunStore：单个串行 I/O 执行器，固定一个工作线程、有界队列；主线程只接收结果。以下全部移出主线程：

1. run／latest／case 状态读取和保存，包括页面刷新使用的 latest／active／description。
2. 素材 SHA-256、工作目录枚举、封存文件写入。
3. 中断恢复、旧记录读取和清理。

页面订阅内存快照；服务恢复期间显示“正在恢复记录”，恢复完成前拒绝第二次开始。不要把同步读盘从 Service 搬到页面轮询。

素材摘要每个 APK 内容版本只计算一次，诊断独立进程 inventory 阶段生成本轮输入索引；nextCase 直接读取已加载索引。缓存键包含素材内容身份，不只按文件名缓存。

### 7.2 写入、超时与迟到结果

- 存储请求携带 runId、revision、operationId；写入内容先序列化成不可变字节，禁止 I/O 线程与主线程共用可变 JSONObject。
- 检查点使用独立 revision 文件，临时文件完成后原子重命名；包封存明确引用获确认的 revision。
- 迟到写入可以留下旧 revision 文件，但不得修改当前内存状态、下一轮运行或已封存索引。
- 读取检查点时校验 runId、revision、结构与摘要；未完成临时文件不算有效记录。进程重启后，未封存的运行统一标记为 interrupted，不从磁盘状态自动续跑诊断；可恢复已有证据并重新导出。
- 初始设计预算：小状态 I/O 5 秒，读取／摘要准备 15 秒；保留现有 caseDeadlineMs、恢复及发布预算。预算为设计值，必须在目标车机校验后记录调整依据。
- 开始新的用例、恢复或封存前，相关检查点须异步确认成功；等待期间主线程继续处理取消与超时，不能同步等待 Future。采样指标可批量保存，不要求每帧同步落盘。
- 超时仅使操作失效，不声称 Thread.interrupt 能打断 fd.sync。
- 若存储线程仍卡住，不重复创建线程绕过；进入 storage_unavailable，停止新增用例，允许内存状态显示停止结果，待旧 I/O 返回或进程重启后恢复。
- 独立诊断 worker 可按现有边界回收；不能因为诊断失败杀掉生产进程。
- 存储彻底不可写时无法保证生成 ZIP。必须显示“报告未保存”及失败原因，保留可保留的证据；不能把内存中结束或私有副本当作公共目录交付成功。

### 7.3 唯一自动编排

新自动流程固定为：
预检查 → 设备清单 → 实时采集（条件满足时）→ 暂停生产视频输出 → 标准片／同源回放 → 恢复原视频 → 封存 → 发布 → 读回校验 → 结果页面。

各阶段失败进入受控收尾；缺少 iPhone 时，标准片可执行，实时项和回放项记录未执行原因。某条路径不支持不终止其他独立路径；生产视频未确认释放则禁止开始争抢该资源的测试。

自动状态只由 Coordinator 维护。旧 AutomaticVideoSuite 未完成记录迁移为 interrupted_legacy，可导出已有证据，不自动继续旧测试。保留手动色彩观察、主屏探针、手动导出，但统一通过诊断租约判断互斥。

### 7.4 报告格式与升级兼容

保留现有 decoder-diagnostics-v3 目录和旧报告可读性；新增 architectureSchemaVersion=1，不覆盖旧报告。

新增内容：

| 包内文件 | 用途 |
|---|---|
| runtime/session-events.jsonl | 代次、连接状态、重连、绑定、停止及释放事件 |
| runtime/resource-ownership.json | 运行／退休资源数量与归属，不序列化真实句柄 |
| video/request.json | 用户／诊断请求 |
| video/plan.json | resolver 结果 |
| video/output-events.jsonl | 实际 codec、输出格式、回退与切换 |
| diagnostics/storage-events.jsonl | I/O 队列、耗时、超时、失效结果；缺失时明确标记 |
| validation/evidence-gaps.json | 未执行、无法测量、缺少实机条件等缺口 |

继续保留原始样本、cases、参考素材索引、设备信息、unexecuted、summary、manifest 校验。附加证据仍受现有 256 MiB 总预算约束；非必要高频事件采用有界环形缓冲并记录 droppedCount，不能挤掉核心码流样本。receipt 在公共回读成功后单独登记，不为把 receipt 塞回 ZIP 而破坏已经计算的摘要。

## 8. 命令排队与拥塞设计

### 8.1 保留协议串行边界

保留 eventWriteLock、加密状态顺序和 CSeq 成功提交语义。媒体队列继续负责切歌和暂停意图，新的输入队列只解决触摸／旋钮入口的过期工作，不建立第二条事件 socket。

### 8.2 输入队列规则

新增 InputCommandQueue，接收时附 session token、gestureId、enqueueNs；执行前再次验证会话与主屏 token。

| 输入 | 规则 |
|---|---|
| DOWN／UP／CANCEL、指针增减 | 有序可靠事件，不能合并或静默丢弃 |
| MOVE | 仅相邻、同会话、同 gestureId、同指针集合且无中间控制事件时，保留最后一份坐标 |
| 旋钮按下／释放、Siri／电话控制 | 保留顺序，不按触摸移动规则合并 |
| 会话替换／断开 | 清除旧会话未发送任务，取消旧延迟动作 |
| 队列容量耗尽 | 先替换可合并 MOVE；仍无空间则报告 input_backpressure，拒绝新手势并使当前输入链路受控结束；不能单独丢 UP |
| socket 写入阻塞 | 记录等待与发送耗时；借助现有连接关闭／恢复解阻，不能并发绕过写入锁 |

初始容量为 64 个待发送输入任务。若可靠边界事件仍无法入队，执行受控会话故障处理，避免远端保持按下；这是明确行为变化，必须独立验证。正常负载不触发该路径。

指标包括 pending、oldestAgeMs、coalescedMoves、staleDropped、overflow、queueWaitNs、writeNs。明确这些是应用侧指标，不能冒充触摸到面板扫描的物理延迟。

## 9. UI、配置与大类收敛

1. Host 提取 ProjectionSurfaceBinding、ProjectionUiStateRenderer，保留 View／触摸坐标／浮层等页面职责。
2. 设置页复用已存在 SettingsInformationArchitecture、ConnectionSettingsSection、DisplaySettingsSection；将视频选项读写归并到一个适配器，不重做设置 UI。
3. AirPlayPersistence 继续保留现有键与默认值，本轮仅新增构造会话快照的入口；不进行全量存储技术迁移。
4. CarPlayController 继续管理 MFi／USB／无线协议流程；本轮移除应用页面依赖和重复命令调度责任，不顺带重写三千行连接流程。
5. 所有权迁移与纯文件提取分开审阅；禁止在提取 AudioRenderer 时顺手调增益，在提取 VideoDecoder 时顺手修改颜色矩阵。
6. 行数减少是副产品。验收看 Activity 是否还能直接创建／关闭会话、同一资源是否有两个 owner、诊断是否还有双自动编排。

## 10. 工作包、依赖与测试时机

~~~mermaid
flowchart LR
    T00[T00 基线归档] --> D[T01-T05 诊断可靠性与迁移]
    T00 --> Q[T06-T07 测试债务与门禁]
    D --> S[T08-T12 会话所有权]
    Q --> S
    S --> V[T13-T17 视频方案与媒体拆分]
    V --> I[T18-T19 输入队列与观测]
    I --> U[T20 页面与兼容收尾]
    U --> R[T21 静态复核]
    R --> Test[T22 统一测试]
    Test --> Car[T23 集中实车验收]
    Car --> Deliver[T24 交付归档]
~~~

这些工作包用于明确依赖和回退边界，不代表可以边写边跑测试。默认在本次被授权的实施范围内完成所有代码与用例后，才执行 T22。若用户以后明确只实施某个工作包，则该工作包全部代码完成后统一验证。禁止用此划分绕过 AGENTS.md。

T00 读取已有报告与代码，不重新运行测试。T06 可以先静态分类历史失败；需要运行才能确认的分类，在 T22 补证，不凭猜测归为旧测试。

M01—M05 是 2026-10-29 从上游 v0.2.22 筛选出的通用修复，不并入原架构依赖图。M01、M02、M05 可在 T00 后实施；M03 与 T11 协调，M04 与 T20 协调。它们完成后仍统一进入 T21—T24。热点接口与音乐时钟暂不列入 M01—M05；热点接口的等价性结论按第 18 节细分核对，不能笼统视为已全部移植。BYD／DiLink 等车型专属功能禁止合并。

## 11. 具体 Task 清单

### 路径约定

为避免每行重复长路径，以下缩写有唯一含义：

| 缩写 | 仓库内完整目录 |
|---|---|
| C | common/src/main/java/com/shilapi/xcertplay/ |
| CT | common/src/test/java/com/shilapi/xcertplay/ |
| S | shared/src/main/java/com/shilapi/xcertplay/ |
| ST | shared/src/test/java/com/shilapi/xcertplay/ |
| E | integrations/flyme-eas/src/main/java/com/shilapi/flymeeas/ |

T00 已完成基线冻结；T01—T03 正在实施，未验收，其余任务尚未实施。任务中的“验证”指要编写并在 T22 统一执行的验证，不是要求当场运行。新建路径为建议落点；如调整，必须同步本表与测试映射。

### T00. 固定当前工作树与验证基线

- 类型：准备；优先级高；依赖：无；对应 A01—A07。
- 文件：新增 docs/superpowers/plans/2026-10-28-architecture-baseline.md；完整快照、文件摘要与已有测试 XML 保存到 artifacts/diplay-architecture/<run-id>/，构建产物不加入源码。
- 输入：当前 HEAD、全部未提交文件、五项改进与解码诊断已有归档。
- 产物：source-manifest.json、baseline-tests.json、environment.json 和行为保留清单。
- [x] 记录 HEAD、git diff、未跟踪源码／素材清单和 SHA-256；排除凭证内容。
- [x] 记录当前 Flyme debug／release 应用 ID、版本、构建参数、JDK／SDK／NDK 实际使用值。
- [x] 复制已有有效 XML／失败差分；保留“全量结果”和“最后专项结果”的区别。
- [x] 将第 3 节的保留行为与现有测试类逐项对应。
- 验收：另一位实施者能说明本次基线包含哪些未提交改动；不能用 HEAD 冒充完整基线。
- 回退：只新增记录，不改业务文件。


执行记录：[T00 基线与版本验证](2026-10-28-architecture-baseline.md)。

### T01. 建立异步诊断存储边界

- 类型：缺陷修复；优先级高；依赖 T00；对应 A02。
- 修改：C/DecoderDiagnosticsRun.kt；新增 C/diagnostics/DiagnosticRunStore.kt、C/diagnostics/DiagnosticStoreResult.kt。
- 测试：新增 CT/diagnostics/DiagnosticRunStoreTest.kt。
- 输入：runId、revision、operationId、已序列化字节；输出：成功、失败或过期结果及耗时。
- [x] 实现单线程、有界容量 32 的存储执行器；队列满返回明确拒绝，不阻塞调用线程。
- [x] 提供异步 loadLatest、checkpoint、readCase、sealSnapshot；所有回调回到指定状态线程。
- [x] 采用第 7.2 节的独立 revision 文件；序列化和落盘使用不可变字节快照。
- [x] 注入 FileAccess 和时钟测试接口，覆盖 write、sync、rename、read 的失败与阻塞。
- [x] 关闭后拒绝新任务，迟到结果按身份丢弃；不无限增开执行器。
- 验证 D01：人为阻塞 sync，主线程仍能处理取消和超时；D02：旧 revision 晚完成不覆盖新运行。
- 验收：该存储接口的正常调用不在主线程执行磁盘操作；损坏／临时文件不作为成功记录。

T01 实施记录（2026-10-29）：已新增 `diagnostics/DiagnosticRunStore.kt`、`DiagnosticStoreResult.kt`，并在 `DecoderDiagnosticsRun` 提供不创建目录的存储构造入口。新增 `DiagnosticRunStoreTest` 的 7 组 API 28 用例，覆盖字节快照隔离、临时文件忽略、摘要校验、阻塞 sync 与取消、队列满拒绝、迟到回调、独立运行 revision 和 I/O 失败。封存写入 `sealed/<revision>.json`，由后续 Coordinator 在成功确认后引用，避免覆盖其他 revision 的封存结果。最初记录为存储层初稿；后续 Service／页面接入进展见 T02。T01 不标记验收完成。按用户要求未运行测试、构建或 lint，统一留至全部授权代码完成后的 T22。

### T02. 将监管流程与 UI 读盘全部迁到异步存储

- 类型：缺陷修复；优先级高；依赖 T01；对应 A02。
- 修改：C/DecoderDiagnosticsService.kt、C/DecoderDiagnosticsRun.kt、C/ColorTestPage.kt、C/VideoAdaptationPage.kt。
- 新增：C/diagnostics/DiagnosticRunCoordinator.kt。
- 测试：CT/DecoderDiagnosticsLifecycleTest.kt；新增 CT/diagnostics/DiagnosticCoordinatorTest.kt。
- [x] 将 stage、封存、latest、active、description 的磁盘部分移到 Store。
- [x] Coordinator 持有内存状态；Service 和页面通过订阅／缓存读状态，不在 500ms 轮询中读取文件。
- [x] 启动恢复使用 RECOVERING 状态，在恢复结束前拒绝重复开始。
- [x] 所有继续执行条件核对 runId／requestId／revision；超时后返回的旧回调只能记录失效。
- [x] 让取消、超时、worker 死亡进入同一个幂等 finish 入口。
- 验证 D03：关闭／重建测试页面不停止后台任务；D04：重复开始只创建一个 run。
- 验收：主 Handler 上没有同步文件、SHA 或 fd.sync 调用，状态推进不等待存储线程完成阻塞调用。

T02 实施记录（2026-10-29）：已新增 `DiagnosticRunCoordinator.kt` 并接入 Service；新运行在内存创建，阶段与每用例结果先异步确认检查点，封存确认后才发布，receipt 经存储队列写入。恢复读取支持旧 `latest.json/run.json`；重复启动按 Service 命令门禁与 RECOVERING 状态拒绝。页面的系统诊断 description／active 经 `DecoderDiagnosticsRun` 读取内存快照，不再同步加载运行文件；旧手动与 AutomaticVideoSuite 路径仍待 T05 清理。进程内复用同一存储线程，超时不另建 writer，存储不可用时保留“报告未保存”状态并独立恢复生产视频。新增 `DiagnosticCoordinatorTest` 6 组用例，迁移已有生命周期断言；追加索引 sync 期间取消的存储回归用例。所有用例尚未执行，T02 不标记验收完成。

T03 实施记录（2026-10-29）：新增 `DiagnosticSourceIndex.kt`，以本轮 inventory 计算的 APK SHA-256 为缓存内容身份，去重素材名后生成摘要；缓存结构、素材集合和规范化索引摘要均需校验，缺失／损坏时重算，素材读取失败不能使用旧值。worker 新增独立 `source_index` 操作，监管预算 15 秒；结果保存至 `reference/source-hashes.json`，nextCase 仅查询内存索引。状态读写预算为 5 秒，恢复／文件读取为 15 秒；封存原始输入或交付预检查 I/O 超时后，主进程进入 storage_unavailable，不允许重建 writer 绕过未退出的线程。ZIP 只收录封存所引用的 revision；已有报告仍经 RETRY 重新发布，不重跑解码。新增 `DiagnosticSourceIndexTest` 4 组缓存与失败用例；存储故障验证复用 `DiagnosticRunStoreTest`、`DiagnosticCoordinatorTest`。源码与用例仍待统一审查和 T22 测试，T03 不标记验收完成。

### T03. 摘要缓存、I/O 超时与降级收尾

- 类型：缺陷修复；优先级高；依赖 T01—T02；对应 A02。
- 修改：C/DecoderDiagnosticsWorkerService.kt、C/DecoderDiagnosticsBundle.kt、C/DecoderDiagnosticsService.kt。
- 测试：新增 CT/diagnostics/DiagnosticStorageFailureTest.kt；扩展 CT/DecoderDiagnosticsEvidenceTest.kt。
- [x] inventory 为本轮素材建立 sourceHash 索引；nextCase 使用内存索引，取消每用例重复读 MP4。
- [x] 对状态 I/O、摘要准备设置第 7.2 节预算并记录 timeoutStage。
- [x] 存储不可用时停止启动新用例，结束执行状态，保留“不曾确认保存”的事实。
- [x] 阻塞线程未退出时拒绝重开一套 writer；迟到结果不复活已结束运行。
- [x] 发布失败保留内部原始目录；重导出不重跑全部解码。
- 验证 D05：磁盘满／权限拒绝／rename 失败有明确结果；D06：摘要失败不误用旧文件哈希。
- 验收：失败不会显示“已保存到 Download”；状态预算到期后仍可操作页面。

### T04. 统一诊断互斥与生产视频租约

- 类型：所有权收敛；优先级高；依赖 T02；对应 A01、A05。
- 修改：C/DecoderDiagnosticsService.kt、C/AutomaticVideoCleanup.kt、C/MainProbeTeardown.kt、C/ColorMainProbeRegistry.kt、C/VideoAdaptationRun.kt；S/media/AndroidMediaSink.kt。
- 新增：C/diagnostics/DiagnosticLease.kt。
- 测试：扩展 ST/media/VideoDiagnosticLeaseTest.kt、CT/MainProbeTeardownTest.kt；新增 CT/diagnostics/DiagnosticLeaseOwnershipTest.kt。
- [x] 用 runId＋会话代次＋leaseId 表示诊断占用；开始、释放、恢复都验证同一身份。
- [x] 将手动主屏探针和新自动诊断接到同一互斥入口；保留底层退休 decoder 屏障。
- [x] 原会话更换后，旧 run 的恢复不得对新 Sink 调用 resume。
- [x] 停止期间未确认释放时保留阻塞状态，不能只清掉 Registry 就允许下一轮。
- 验证 D07：旧诊断恢复不影响新会话；D08：关闭未完成时第二次开始被拒绝。
- 验收：任一时刻只有一个有效诊断租约，生产音频不中断。

### T05. 退役旧自动编排并兼容旧记录

- 类型：迁移收尾；优先级中；依赖 T02—T04；对应 A05。
- 修改：C/ColorTestPage.kt、C/VideoAdaptationPage.kt、C/AutomaticVideoSuite.kt、C/VideoReportPublisher.kt。
- 测试：CT/AutomaticVideoSuiteTest.kt、CT/ColorTestPageTest.kt、CT/VideoAdaptationPageTest.kt。
- [x] 删除 startLegacyAutomatic 及仅服务旧自动推进的定时任务与回调。
- [x] 旧记录提供只读转换：未完成标 interrupted_legacy；已有证据可导出，不自动续跑。
- [x] 保留手动播放、截图、主屏探针和导出；把它们的互斥统一接入 T04。
- [x] 清理测试中对已移除私有方法的反射，改为检验新入口与兼容结果。
- 验证 D09：含旧 pending_token／suite 文件的升级不死锁、不重启旧测试、不丢已有报告。
- 验收：全仓搜索只有一个可启动自动流程；旧格式只有读取／迁移代码，没有隐藏的执行入口。

### T06. 分类并修复历史测试失败

- 类型：验证保障；优先级高；依赖 T00；对应 A03。
- 修改：由失败索引明确列出的 CT／ST 用例；新增 docs/testing/architecture-failure-triage.md。
- [x] 将 99 项历史失败及 T00 冻结前新增的 ReportShare HTTP401 等待处理项逐项登记为测试标识、异常、业务契约、初步分类和处置。
- [x] 分类仅限：陈旧断言、fixture 错误、环境依赖、产品缺陷、尚未确认；最后一类不得写“已解决”。
- [x] 对中文化、删除功能等有明确产品依据的测试更新预期；仍保留可观察行为断言。
- [x] 环境依赖测试隔离与环境探针绑定，不能无条件 ignore。
- [x] 真实产品缺陷编写行为回归，集中修复；若超出本计划范围，明确列为阻塞项，不用白名单掩盖。
- 验证 Q01：T22 对每个失败原名核对最终结果及修复理由。
- 验收：完整套件无未解释失败；目标是 CI 全绿，不能只用“99 未增加”验收。

### T07. 完整结果归档与门禁

- 类型：构建保障；优先级高；依赖 T06；对应 A03。
- 修改：.github/workflows/android.yml；新增 scripts/summarize_architecture_validation.py。
- [x] 汇总 JUnit XML，分别列 test count、failure、error、skip 和缺失报告；任务未运行不能算通过。
- [x] 明确构建失败／测试失败／环境阻断的退出状态；不使用忽略返回码的写法制造绿灯。
- [x] 上传 flyme-eas、shared、common 结果、lint、构建日志和实际环境。
- [x] 保留 Flyme debug 构建与 debug／release Manifest 检查，归档 API 28 路径覆盖清单。
- 验证 Q02：用人工构造的通过、失败、缺文件 XML 测汇总器；失败样本必须返回失败。
- 验收：报告能直接回答“哪些执行了，哪些没执行，哪些失败”，无需人工猜测。

### T08. 会话状态、工厂与订阅合同

- 类型：结构重构；优先级高；依赖 T00；对应 A01、A07。
- 新增：C/session/SessionState.kt、SessionFactory.kt、SessionRuntime.kt、SessionClient.kt。
- 修改：C/CarPlayHostActivity.kt 的 createRuntimeConfig／createMediaSink／createMediaEngine 调用边界。
- 测试：新增 CT/session/SessionRuntimeStateTest.kt、SessionFactoryTest.kt。
- [x] 实现第 5 节合同和单代次状态转换；协议进度保留现有 CarPlayStatus。
- [x] 工厂使用 applicationContext；把配对存储、定位、配置读取回调从 Activity 捕获中移出。
- [x] 订阅登记与首次完整快照在同一状态线程完成，避免订阅与读当前值之间丢事件。
- [x] close subscription 幂等；取消订阅后已排队回调也应校验订阅身份。
- 验证 S01：订阅与会话切换交错时最终快照为当前会话；S02：工厂构造不保留 Activity。
- 验收：Runtime 不 import Activity／View；UI 订阅可以完整解除。

### T09. Service 接管会话创建与停止

- 类型：所有权迁移；优先级高；依赖 T08；对应 A01。
- 修改：C/DiPlaySessionService.kt、C/CarPlayHostActivity.kt、C/session/SessionRuntime.kt、SessionClient.kt。
- 测试：新增 CT/session/SessionServiceOwnershipTest.kt。
- [x] Service 创建并持有唯一 Runtime，提供同进程 Binder 给 SessionClient。
- [x] Host 完成权限／窗口前置条件后发送 start request，不再直接 new Controller／Sink。
- [x] 通知断开、设置退出、onTaskRemoved 统一调用 Runtime.stop。
- [x] Foreground 启动失败时不启动底层连接；保留 API 28 通知与权限路径。
- [x] 明确主动停止和系统销毁不同处理；绑定页面离开不隐式 stopSelf。
- 验证 S03：两个入口同时连接只创建一条链路；S04：通知停止只关闭一次且所有等待者收到结果。
- 验收：生产路径中 Controller／Sink 的应用层创建仅存在于 SessionFactory。

### T10. 输出绑定与前后台恢复迁移

- 类型：生命周期重构；优先级高；依赖 T09；对应 A01、A07。
- 新增：C/session/ProjectionSurfaceBinding.kt。
- 修改：C/CarPlayHostActivity.kt、C/CarPlayVideoSurface.kt；复用 S/media/VideoSurfaceHandoff.kt。
- 测试：CT/ProjectionVideoLifecycleTest.kt、CT/CarPlayHostVideoSurfaceTest.kt、ST/media/AndroidMediaSinkCodecDetachTest.kt。
- [x] Surface 回调通过 bindingId 和会话身份交给 Runtime；旧页面 clear 不能拆新输出。
- [x] 保留短后台 500ms 延迟、停车输出、关键帧刷新、旧图覆盖和不支持时重配回退。
- [x] Activity onDestroy 关闭订阅／输出绑定，不关闭运行会话。
- [x] 不提前释放 Texture wrapper 或框架 Surface；保留真实 decoder 退出通知。
- 验证 S05：499ms 返回不停车，500ms 后停车；S06：旧 Surface 销毁晚于新绑定仍不误拆。
- 验收：前后台恢复和 Surface 所有权原有断言全部保留；页面重建不重复建立协议连接。

### T11. 重连、停止与资源退休统一

- 类型：所有权迁移；优先级高；依赖 T09—T10；对应 A01。
- 修改：C/session/SessionRuntime.kt、C/CarPlayHostActivity.kt 的 maybeStartCarPlay／reconnectAfterLoss／restartCarPlay／shutdown。
- 测试：新增 CT/session/SessionRuntimeLifecycleTest.kt；迁移 CT/BackgroundSessionReconnectTest.kt 至 API 28 必测路径。
- [x] 将重连预算、authentication teardown、generation、stop waiters 移给 Runtime。
- [x] 同代次重复断线合并；显式停止使旧重连定时任务失效。
- [x] 关闭超时进入 BLOCKED_RELEASE 并保留资源；真实释放前不建立竞争 MFi／codec 实例。
- [x] 权限恢复与配置重连重新读取真实系统权限，保留拒绝麦克风仍可连接的既有语义。
- 验证 S07：停止与重连交错不重生连接；S08：旧释放晚到不清新会话；S09：超时后资源后来退出可受控恢复。
- 验收：后台恢复、MFi 身份不重叠、双重停止和权限恢复均有确定性用例。

### T12. 媒体／Flyme／诊断消费者迁移，移除全局 owner

- 类型：迁移收尾；优先级高；依赖 T04、T11；对应 A01。
- 修改：C/CarPlayMediaKeys.kt、C/FlymeWheelIntegration.kt、C/CarPlayVideo.kt、C/DecoderDiagnosticsService.kt、C/MapMirrors.kt、C/CarPlayHostActivity.kt。
- 测试：CT/CarPlayMediaCallbackTest.kt、CT/CarPlayMediaFocusForwardingTest.kt、CT/EasPlayStateChannelTest.kt；新增 CT/session/SessionConsumerOwnershipTest.kt。
- [x] 将媒体与 Flyme attach／detach 绑定到 Runtime 会话切换，UI 重建不重复注册。
- [x] ready 判断使用 Runtime 的真实会话快照，保留通话门禁、方控去重和 expected controller 身份检查。
- [x] 诊断只通过 T04 租约访问生产视频，不从全局对象自行抓取 Sink。
- [x] 逐个搜索并迁移 CarPlayBackgroundSession 消费者，删除旧 owner、stopAction 和资源快照。
- 验证 S10：UI 重建时 EAS 注册次数不增长；S11：旧回调不能上报／控制新会话。
- 验收：不新增 EAS／方控通路；原播放状态、封面、媒体焦点与原车电话功能保持。

### T13. 视频请求／方案／实际结果模型与纯解析器

- 类型：结构重构；优先级中高；依赖 T08；对应 A04。
- 新增：S/media/VideoPipelinePlan.kt、VideoPlanResolver.kt；C/session/VideoSettingsAdapter.kt。
- 修改：C/CarPlayVideoOutput.kt、C/CarPlayVideoSurface.kt。
- 测试：新增 ST/media/VideoPlanResolverTest.kt、CT/VideoSettingsCompatibilityTest.kt；保留 CT/CarPlayVideoSurfaceTest.kt。
- [x] 实现第 6 节三份模型，区分默认 decoder 和已确认硬件 decoder。
- [x] 将现有开关组合、窗口硬件加速条件、纹理不安全回退写成输入表。
- [x] 由纯 resolver 输出方案与理由；设置适配器仅负责读现有键。
- [x] 固定 planId 和 schemaVersion 生成规则，序列化排除运行句柄。
- 验证 V01：遍历开关与窗口条件组合，输出与旧逻辑一致；V02：不支持能力有明确回退原因。
- 验收：第一轮不改变默认值、色彩参数、请求分辨率或 fps。

### T14. 正式投屏与诊断复用同一视频方案

- 类型：一致性改造；优先级中高；依赖 T03、T13；对应 A04。
- 修改：C/session/SessionFactory.kt、C/ColorTestPlayer.kt、C/DecoderDiagnosticsActivity.kt、C/DecoderDiagnosticsWorkerService.kt、S/media/AndroidMediaSink.kt。
- 测试：新增 CT/VideoPlanParityTest.kt；扩展 CT/DecoderDiagnosticsPlanTest.kt。
- [x] 工厂和测试播放器调用同一 resolver，移除两边自行拼接布尔参数的代码。
- [x] 实际 codec 创建、format 变更、Surface 切换、回退发布 Observation。
- [x] 诊断严格软件路径不可静默接受硬解；请求／实际不一致必须反映到用例结果。
- [x] 不把诊断临时配置写回 AirPlayPersistence。
- 验证 V03：相同请求与能力得到相同 plan；V04：硬件 YUV 回退只记 fallback，不能算原路径通过。
- 验收：报告可直接重建某次实际路径，无需从多段文本日志猜测。

### T15. 提取视频资源所有者

- 类型：纯职责提取；优先级中；依赖 T10、T14；对应 A07。
- 新增：S/media/AndroidVideoPipeline.kt；修改 S/media/AndroidMediaSink.kt。
- 测试：ST/media/AndroidMediaSinkCodecDetachTest.kt、VideoSurfaceHandoffTest.kt、ParkingOutputTest.kt、VideoDiagnosticLeaseTest.kt、VideoDecodeQueueTest.kt。
- [x] 连同锁和退休集合一起迁移 VideoDecoder、ParkingOutput、主／镜像 Surface 所有权。
- [x] 保留 configure／frame／detach／close 的线程顺序和超时语义。
- [x] Sink 将视频接口委托给 Pipeline，不保留第二份 decoder map。
- [x] 将与新结构绑定的测试 fixture 改为接口注入，保留原行为断言。
- 验证 V05：旧流关闭不影响新流；V06：镜像仍阻塞时不能报告全部释放。
- 验收：只有 VideoPipeline 拥有视频资源；提取前后功能与参数一致。

### T16. 提取音频资源所有者

- 类型：纯职责提取；优先级中；依赖 T15；对应 A07。
- 新增：S/media/AndroidAudioPipeline.kt；修改 S/media/AndroidMediaSink.kt。
- 测试：ST/media/AudioFocusAutoYieldTest.kt、AudioCodecOutputReleaseTest.kt、ExperimentalAudioOutputTest.kt、NavigationAudioGainTest.kt；ST/airplay/CarPlayAudioStreamIsolationTest.kt。
- [x] 迁移 AudioRenderer、焦点协调、麦克风和回声参考集合。
- [x] 保留按 AudioStreamId／registration 归属及停流释放机制。
- [x] 保留 mediaAudioChanged、外部焦点转发、HFP、导航与通话增益以及故障恢复。
- [x] Sink.close 统一委托，重复调用幂等，不拆开原本必须有序的停止步骤。
- 验证 V07：关闭旧音频流不影响新流；V08：视频诊断暂停时音乐与电话链路不被停止。
- 验收：音频参数不变，导航不错误取得媒体焦点，所有流关闭后引用清空。

### T17. 方案与资源事件进入统一证据包

- 类型：可观测性；优先级中；依赖 T12、T14—T16；对应 A01、A04。
- 修改：C/DecoderDiagnosticsBundle.kt、C/DecoderDiagnosticsFields.kt、C/VideoAdaptationSummary.kt；新增 C/diagnostics/RuntimeEvidence.kt。
- 测试：CT/DecoderDiagnosticsEvidenceTest.kt；新增 CT/diagnostics/RuntimeEvidenceTest.kt。
- [x] 按第 7.4 节输出新增文件；每项携带 sessionId／generation／runId／单调时间。
- [x] 事件环形缓冲有界，记录截断数量，预算不足时优先保留核心样本和完整性索引。
- [x] 没有实际值时写 unknown 与原因，不从用户开关推导硬件已生效。
- [x] 私钥、token、密码、原始配对数据不进入包。
- 验证 E01：同一个包能关联会话、计划、实际输出和回退；E02：新增文件全被 manifest 校验覆盖。
- 验收：旧报告可读，新报告字段有版本和来源说明。

### T18. 输入命令有界排队与 MOVE 合并

- 类型：行为优化；优先级中；依赖 T11、T17；对应 A06。
- 新增：S/media/InputCommandQueue.kt。
- 修改：S/orchestration/CarPlayController.kt；不改变 S/airplay/AirPlaySession.kt 的加密串行边界。
- 上游参考：v0.2.22 的 #1134／`ad03bf44de` 只允许覆盖“同手指仍在等待发送”的 MOVE，并保留最新坐标；只吸收该语义，不引入其 `CarPlayController` 内的无界按压／释放队列。
- 测试：新增 ST/media/InputCommandQueueTest.kt；保留 ST/media/MediaCommandQueueTest.kt。
- [x] 实现第 8 节合并规则、容量、会话 token 检查和受控拥塞结果。
- [x] 同手指相邻 MOVE 在受控队列内合并，跨指针变化、手势边界、旋钮、语音和电话控制不得合并。
- [x] 接受任务与实际写入分开记录；失败不能被上层解释成 iPhone 已执行。
- [x] 保留 DOWN／UP／CANCEL、指针变化、旋钮和语音／电话控制的先后顺序。
- [x] 关闭清空未发送任务；新会话不执行旧手势或旧延迟动作。
- 验证 I01：1000 个相邻 MOVE 被压缩但最终坐标保留；I02：跨手势／多指变化不合并；I03：可靠边界溢出不会静默丢 UP。
- 验收：正常顺序与现有行为一致；拥塞分支有明确失败和恢复，不积累无界延迟。

### T19. 队列、socket 与帧链路观测对齐

- 类型：可观测性；优先级中；依赖 T18；对应 A06。
- 修改：S/media/InputCommandQueue.kt、S/media/TouchLatencyProbe.kt、S/media/VideoPipelineTiming.kt、C/diagnostics/RuntimeEvidence.kt。
- 测试：新增 ST/media/InputTimingEvidenceTest.kt。
- [x] 记录接收、入队、出队、开始写、写完成的同一单调时钟时间点。
- [x] 记录共享写入锁等待与实际写耗时，区分队列慢和 socket 慢。
- [x] 保留媒体续播任务取消、CSeq、加密序号的现有验证。
- [x] 报告仅计算可证明的本机时间差；没有触摸到对应帧映射就标 unknown。
- 验证 I04：人为阻塞 sender 可见排队增长与合并；解阻后最终 UP 完整发送；I05：时间指标不出现跨时钟相减。
- 验收：可区分输入排队、视频排队和渲染耗时，不能把一个总耗时冒充根因。

### T20. 页面与配置边界收尾

- 类型：结构收尾；优先级中；依赖 T05、T12、T14、T19；对应 A07。
- 新增：C/session/ProjectionUiStateRenderer.kt；修改 C/CarPlayHostActivity.kt、C/DiPlayActivity.kt、C/AirPlayPersistence.kt、C/settings/DisplaySettingsSection.kt。
- 测试：CT/CarPlayHostSettingsTest.kt、CarPlayHostDisplaySizeTest.kt、SettingsLayoutPolicyTest.kt、SettingsTranslationsTest.kt。
- [x] Host 仅保留页面、手势、窗口信息与订阅，不再保存另一套会话重试状态。
- [x] 设置持久化到配置快照只走一个适配器；显示“请求值”和“实际路径”时明确区分。
- [x] 删除仅服务旧所有者的 executor／回调／字段；保留显示与触摸坐标同步。
- [x] 如果改变布局，按 AGENTS.md 补紧凑／宽屏、大字体和必要 RTL 截图；未改变布局不扩展视觉重做。
- 验证 U01：相同设置经正常入口与重建入口得到相同请求；U02：需重连项不会未经确认中断当前连接。
- 验收：所有新增设置仍遵守原分类，不产生第二套参数源。

### T21. 统一执行前静态复核

- 类型：审查；优先级高；依赖 T01—T20 完成代码及用例。
- 产物：docs/superpowers/plans/2026-10-28-architecture-review.md。
- [x] 搜索 Activity 捕获、CarPlayBackgroundSession、new Controller／Sink、同步诊断读写和旧自动入口，逐项核对。
- [x] 对照第 3 节保留行为与第 12 节矩阵，确认每个要求有具体用例。
- [x] 检查锁顺序、阻塞调用所在执行器、关闭幂等、过期结果、资源退休与队列容量。
- [x] 检查所有 API 28 路径、设置默认值与版本号无意外变更。
- 验收：未解决项必须列出；不能把“代码看起来合理”写成实机验收。
- 此阶段是静态审查，不提前执行测试。

### T22. 统一自动验证与集中修正

- 类型：验证；优先级高；依赖 T21。
- [x] 一次运行第 13 节的完整 CI 命令，保存原始退出码、日志、JUnit XML。
- [x] 归档后集中分析失败，不覆盖第一次结果；统一修正后运行失败与受影响范围。
- [x] 若修正涉及全局会话、媒体门面或构建配置，补完整套件；仅局部 fixture 修正可定向验证并如实标明边界。
- [x] 完成 Flyme 构建、lint、签名与 debug／release Manifest 检查，确认实际使用的 NDK。
- [x] 不连接真车的测试结果明确标记为本地／Robolectric／模拟器，不冒充 API 28 车机。
- 验收：满足第 14 节软件门禁，所有报告和失败处置可追溯。

### T23. 集中一次实车验收

- 类型：目标环境验证；优先级高；依赖 T22 软件门禁通过。
- [ ] 安装前保存当前包与设置；本地先验证结果包入口和 Download 文件回读。
- [ ] 按第 12.2 节一张清单连续完成连接、前后台、媒体、诊断、恢复和导出。
- [ ] 自动部分一次运行；必要人工项目集中完成，不让用户反复上车切实验开关。
- [ ] 文件管理器直接确认 ZIP，复制到电脑后独立验 manifest／大小／SHA。
- [ ] 没有完成的项目填写原因，不以“未发现问题”代替通过。
- 验收：运行与交付均有证据；颜色正确与物理跟手仍按专门证据判定。

### T24. 交付、回退材料与任务结项

- 类型：交付；优先级高；依赖 T22，实车结论依赖 T23。
- [x] 输出任务状态、修改清单、测试结果、限制与需实车确认事项。
- [x] 保存基线／候选 APK、签名摘要、包名版本、完整源码指纹和设置兼容说明。
- [x] 按第 15 节提供源码与包回退路径，保留旧报告和原始样本。
- [x] 仅勾选有实现与验证证据的任务；代码完成但未实车的任务标“软件验证完成／实车待验”。
- [x] 用户要求时再提交或升版，不把文档生成当作实施授权。
- 验收：接手者可以从一份 README 找到源码、包、测试、故障记录与回退方法。

### 上游 v0.2.22 通用修复移植（M01—M05）

来源为上游 `shihabal3amri/DiPlay` v0.2.22（2026-10-28 预发布，target commit `28d6002ba00320f0ba745212160b08759e4f60b9`）。以下任务只移植协议、媒体、连接恢复和通用安全逻辑，不整体 cherry-pick，不引入 BYD／DiLink、HUD、仪表地图或其他车型专属改动。

### M01. 移植 USB PCM 麦克风 MTU 适配

- 类型：上游通用修复移植；优先级高；依赖 T00；来源：#1148／`8d5fd4b213`。
- 现状：本地 `S/airplay/CarPlayMediaEngine.kt` 在 iPhone 未给 `framesPerPacket` 时，PCM 仍固定 20 ms；Opus 20 ms 保持不变。
- 修改：S/airplay/CarPlayMediaEngine.kt、S/airplay/MicrophonePacketizer.kt。
- 测试：ST/airplay/MicrophonePacketizerTest.kt。
- [x] PCM 默认从 20／10／5 ms 中选择能容纳于 1,500-byte 链路 MTU 的最长帧；同时计入 IPv6、UDP、RTP、Poly1305 和 nonce 开销。
- [x] 48 kHz mono 使用 10 ms，24／16 kHz 保持 20 ms，48 kHz stereo 使用 5 ms 兜底；iPhone 明确给出的 `framesPerPacket` 仍优先。
- [x] 不改 Opus 的 20 ms 语义，不改变采集率、编码率或 RTP 时钟单位。
- 验证 R01：边界采样率、声道数和显式 `framesPerPacket` 均有断言。
- 验收：软件测试覆盖包大小；目标车 USB 录音需在 T23 用语音备忘录或微信语音实车确认。

### M02. 移植 AirPlay 控制会话对端隔离

- 类型：上游通用安全修复移植；优先级高；依赖 T00；来源：#1123／`aeb1d73356`。
- 修改：S/airplay/AirPlaySession.kt、S/airplay/ScreenStream.kt、S/airplay/IapTunnel.kt、S/airplay/VideoSettingsChannel.kt、S/airplay/NtpClock.kt；保留已经存在的 `BufferedAudioStream` 校验。
- 测试：ST/airplay 下新增或扩展对端隔离测试。
- [x] Screen、iAP、视频设置、事件监听只接受当前控制会话的 iPhone 地址；其他连接必须关闭且不能占用监听槽。
- [x] NTP 只处理当前控制会话 peer 的响应；状态字段按跨线程访问要求同步。
- [x] Screen 的 AEAD body 短于认证标签时直接丢弃，不能作为明文继续处理。
- [x] 不改变 UDP 音频现有 AEAD 校验，也不把未认证的视频配置伪装成已验证来源。
- 验证 R02：伪造 peer 抢先连接、合法 peer 后到、错误来源 NTP 响应和短认证帧均被正确处理。
- 验收：所有通道在 API 28 路径拒绝会话外来源；合法连接、重连和现有事件时序无回归。

### M03. 移植有线 CarPlay 晚到 USB 网络与重连修复

- 类型：上游通用连接恢复移植；优先级中高；依赖 T00，建议在 T11 后实施；来源：#962、#1057、#1085，关键提交 `b014031aa7`。
- 修改：S/orchestration/CarPlayController.kt、S/orchestration/WiredStartWatchdog.kt 及相关连接状态测试。
- 测试：ST/orchestration/WiredStartWatchdogTest.kt、ST/orchestration 下连接启动测试。
- [x] 首次 `carplay-start-session` 仍按 30 秒预算；当第二次请求仍可能发出时只延长一次到 45 秒，并记录实际等待时间。
- [x] 第二次请求仍获得完整 30 秒；不得产生两套 Controller、重复会话或绕过 T11 的统一停止／重连所有权。
- [x] 覆盖 iPhone 延迟打开 USB 网络、带线重连和重新插拔的时序；超时原因保持可解释。
- 验证 R03：可控时钟推进验证 30 秒边界、45 秒延长、第二次请求重置和终止结果。
- 验收：软件测试通过不等于实车通过；T23 必须验证首次重连成功率及失败提示。

### M04. 移植类型化连接失败原因提示

- 类型：上游通用体验修复移植；优先级中；依赖 T20；来源：#1058、#1060、#1062、#1066，关键提交 `9f6f3275eb`。
- 修改：S/orchestration/CarPlayController.kt、S/orchestration/ConnectionFailureCause.kt、C/ConnectionFailureGuidance.kt、C/CarPlayHostActivity.kt 及简体中文资源。
- 测试：CT/ConnectionFailureGuidanceTest.kt、相关 CarPlayHost 测试。
- [x] 为已选 iPhone 未配对、没有已配对 iPhone、多个 iPhone 无法确定和 Wi-Fi Direct 信道不可用建立类型化原因。
- [x] 页面按类型显示简体中文原因与处理建议；未知异常继续显示通用建议，不从任意异常文字猜测原因。
- [x] 不改变重试预算、重试顺序、异常报告文本和现有设置分类，不新增设置项。
- 验证 R04：四类原因分别映射到正确提示，未知异常不误报，重试次数与迁移前一致。
- 验收：文案进入现有 Connection 信息架构；T23 验证实际失败场景的可读性。

### M05. 移植通用安全与日志卫生修复

- 类型：上游通用安全／可观测性移植；优先级高；依赖 T00，与 T11、T12 协调；来源：#1122、#1125、#1126、#1127、#1128、#1129、#1138、#1139、#1141、#1144。
- 修改：S/airplay 接收路径、S/transport、C 的 Intent／USB 权限入口和诊断脱敏代码；具体落点以本地实现为准。
- 测试：ST/airplay、ST/transport、CT 的对应安全与脱敏测试。
- [x] 对控制头、控制体、iAP 包、bplist 分配／递归、Bonjour 探针行和 NCM NTB 设置明确上限，畸形输入失败但不拖垮进程。
- [x] 远程 MFI 公网请求只允许 HTTPS；外部应用 Intent 只读取允许的字段；USB 权限结果接收器只接受本应用结果。
- [x] 报告脱敏覆盖带连字符的 iPhone UDID，日志不得记录生成的热点名称，USB ACK／控制帧日志有界。
- [x] 不移植 BYD classpath、车载地图嵌入、示例启动器和 HUD 等车型／样例专属改动。
- 验证 R05：恶意长度、递归、错误来源和危险 Intent 均被拒绝；合法路径、报告可读性和 API 28 行为不变。
- 验收：安全边界有测试证据；日志限流不丢失关键失败；不引入新权限或车型适配。

## 12. 测试设计与覆盖矩阵

### 12.1 本地统一测试矩阵

所有并发测试使用可控执行器、单调时钟、latch 或显式事件推进。不要用任意 sleep 等待“碰巧完成”，不要只断言方法被调用而不核对资源和状态。

| 测试 ID | 安排／故障注入 | 必须断言 | 层级／关联任务 |
|---|---|---|---|
| D01 | writer 在 sync 卡住，推进监管时钟至预算后，再发送取消 | 主线程继续响应；状态进入存储失败；没有第二个 writer | API 28 Robolectric＋可控 I/O；T01—T03 |
| D02 | A 的写入超时；解除存储阻塞后启动 B，再投递 A 的迟到完成回调 | B 的状态／索引不被 A 改写；A 不重新推进用例 | 单元；T01—T02 |
| D03 | 自动运行中销毁并重建页面 | runId 不变，进度可重新订阅，worker 不重复启动 | API 28 生命周期；T02 |
| D04 | 双击开始、两个页面入口同时开始 | 只创建一个有效 run／lease；另一请求有拒绝原因 | 单元＋组件；T02、T04 |
| D05 | ENOSPC、权限拒绝、rename 失败、公共目录读回失败 | 保存状态与执行状态分离；保留可用证据；不显示公共交付成功 | 存储组件；T03 |
| D06 | 同名素材变更／摘要索引缺失 | 内容身份变化后重新摘要；缺失项不使用陈旧值 | 单元；T03 |
| D07 | 诊断暂停 A，连接变为 B，再结束 A 的诊断 | 不能恢复／关闭 B 的 Sink；记录会话已替换 | 租约组件；T04、T12 |
| D08 | codec close 不返回，重复开始诊断 | 保留退休资源归属；拒绝争抢；允许结果显示失败 | 可控 codec；T04、T15 |
| D09 | 安装升级后遗留旧 suite／pending_token | 标明旧运行中断，可导出；不执行旧自动流程 | API 28；T05 |
| S01 | 注册 observer 同时切换会话；之后关闭订阅 | 最终完整快照属于新会话，取消后无迟到 UI 回调 | 单元；T08 |
| S02 | 页面 A 创建连接，销毁 A，页面 B 接入 | 工厂依赖仅持有 applicationContext；A 的回调被解除 | 组件＋引用审查；T08—T12 |
| S03 | 同时从页面和自动连接触发 start | 一组 Controller／Sink／媒体注册；请求幂等 | API 28 Service；T09 |
| S04 | 通知、设置同时 stop | 底层只关闭一次；等待者完成一致；资源未释放不报成功 | 单元；T09、T11 |
| S05 | 后台 499ms 返回；另一次超过 500ms 返回 | 前者不停车；后者恢复原会话输出并请求关键帧 | 原有 API 28 测试扩展；T10 |
| S06 | 新 Surface 绑定后旧 Surface destroyed 才到 | 新绑定仍有效；只释放旧自有 wrapper | 可控 codec＋UI；T10 |
| S07 | 排队重连后用户 stop，再推进定时器 | 不新建连接；旧重连事件作废 | 单元；T11 |
| S08 | 新会话运行后旧 Controller／Sink 退出回调到达 | 不清新状态、不关闭新资源、不解绑新 EAS | 组件；T11—T12 |
| S09 | 释放超时，稍后真实退出 | 超时期间不可重建竞争资源；退出后按当前意图继续 | 可控 worker；T11 |
| S10 | UI 重建、后台恢复、旋转／字体配置变化 | EAS 与媒体会话注册不因 UI 重建重复，焦点策略不变 | API 28；T12 |
| S11 | 旧媒体状态／封面／EAS 回调迟到 | 无权覆盖新会话；显式暂停意图仍有效 | 现有媒体与 EAS 套件；T12 |
| V01 | 枚举现有输出开关与窗口组合 | 解析结果与迁移前语义一致；无默改默认值 | 纯单元；T13 |
| V02 | 无软件 codec、不支持 YUV、窗口不支持纹理 | 明确拒绝／回退及依据，不能谎报请求路径生效 | 纯单元＋codec fixture；T13 |
| V03 | 正式路径和诊断传入同一配置、同一能力 | plan 的公共字段完全一致，仅 purpose／严格策略允许不同 | 跨组件；T14 |
| V04 | 请求硬件 YUV，实际 fallback Surface | observation 为实际路径，用例标回退，原请求不变 | codec fixture；T14 |
| V05 | 会话 A、B 同 type 接续，A 关闭 | B 配置、恢复回调与帧交付不被清理 | CarPlayVideoStreamOwnershipTest；T15 |
| V06 | 主 decoder 已退出、镜像仍持有 codec | await／通知不得声称全部释放 | AndroidMediaSinkCodecDetachTest；T15 |
| V07 | 同 AudioStreamId 新 owner 替换旧 owner | 旧音频停止不清理新音轨／麦克风 | 音频隔离套件；T16 |
| V08 | 暂停视频做诊断，音乐／导航／电话仍存在 | 不错误关闭音频、抢焦点、改变增益 | 组件＋实车；T16 |
| I01 | 阻塞 sender，连续注入 1000 MOVE | 任务数量有界，最后坐标保留，DOWN／UP 顺序保留 | 可控队列；T18 |
| I02 | MOVE 中插入多指增减、旋钮或新手势 | 不跨边界合并 | 纯单元；T18 |
| I03 | 队列填满不可合并边界事件 | 明确拥塞故障，不静默丢释放事件，不无限排队 | 纯单元；T18 |
| I04 | sender 等待写锁／写 socket，再解阻 | 分别记录 queueWait 与 write；解阻后顺序正确 | 可控 sender；T19 |
| I05 | 不存在 iPhone 时钟映射或触摸对应帧 | 物理／单向延迟输出 unknown，不计算伪精确结果 | 证据单元；T19 |
| E01 | 生产切换、回退、诊断中断后打包 | 可按身份串起事件；缺口与 droppedCount 可见 | 报告集成；T17 |
| E02 | 篡改／截断 ZIP 任一文件，或缺少 manifest 项 | 校验拒绝，不登记 public_verified | 报告组件；T17、T22 |
| U01 | 相同设置经首次连接、页面重建与重连加载 | 请求方案一致；设置键和值保留 | API 28；T20 |
| U02 | 修改需重连设置后取消重连 | 当前会话保持原配置，新设置等待下一次生效 | UI 行为；T20 |
| Q01 | 全量执行历史失败用例 | 每项有新结果和处置理由，无无依据的 skip | 全套；T06、T22 |
| Q02 | 汇总器输入失败／空目录／缺模块 XML | 不能输出“全部通过”；保留真实退出状态 | 脚本测试；T07 |
| R01 | PCM 在 48 kHz mono／stereo、24／16 kHz 和显式 `framesPerPacket` 下组包 | 默认帧长和封包大小符合 MTU 规则；显式值不被覆盖 | 单元；M01 |
| R02 | 伪造 peer 抢先连接、合法 peer 后到和错误来源 NTP 响应 | 非控制会话连接不占用槽；合法 peer 可连接；短 AEAD 帧不输出明文 | 单元＋可控 socket；M02 |
| R03 | 推进 30 秒、45 秒和第二次启动请求时钟 | 仅在第二请求仍可发出时延长一次；第二请求获得完整预算；不产生重复会话 | 可控时钟；M03 |
| R04 | 四类类型化连接失败与未知异常 | 提示映射准确；未知异常不猜测；重试预算不变 | 单元；M04、T20 |
| R05 | 超长／递归协议输入、危险 Intent、脱敏样本和日志洪泛 | 输入有界；危险来源被拒绝；秘密与热点名不落日志；合法路径不回归 | 单元＋API 28；M05 |

### 12.2 集中实车验收清单

实车前先通过 T22。目标车机停车测试，尽可能一次完成并只回收一个主结果包。人工操作仅用于现阶段无法可靠自动完成的真实系统交互。

| 顺序 | 操作 | 通过标准 | 收集证据 |
|---|---|---|---|
| 1 | 普通已用连接方式启动；验证 CH341／MFi | 成功连接，阶段信息完整，无重复实例 | 启动事件、会话身份、设备清单 |
| 2 | 正常使用音乐、导航、Siri；电话按原车 HFP 路径验证 | 声音路由、增益、通话链路保持；方控不重复 | 音频／焦点／方控事件、人工结果 |
| 3 | 连续前后台切换 3 轮，包含短切与较长停留 | 同会话可恢复，画面不永久黑屏，媒体中心不重复注册 | binding／decoder park／恢复事件 |
| 4 | 设置页返回、投屏页面重新进入；本地集成已覆盖重建 20 轮 | 连接与媒体状态恢复正确，无新增 owner | runtime 快照与资源计数 |
| 5 | 正常切歌、暂停、上一曲、下一曲 | 显式暂停不被自动续播推翻；状态／封面不串会话 | 媒体队列和 EAS 日志 |
| 6 | 启动一次完整自动诊断 | 全部计划项有执行结果或未执行原因；异常可收尾 | 三层视频证据、清晰度／颜色／负载／管线信息 |
| 7 | 检查浅色近白灰阶与深色对照 | 确认是否存在裁剪；实际主题无法自动确认时列为 unknown | 标准片灰阶、真实输入／截图及条件 |
| 8 | 同一真实页面滑动，比较已有默认与软件直出路径；可由诊断统一组织 | 不用静态 20 秒代替运动；记录实际路径和时序，不先承诺改善 | 运动窗口、队列与解码输出数据 |
| 9 | 测试结束返回正常投屏 | 日常设置未改，生产视频恢复，音频与媒体中心正常 | 恢复结果、设置前后差分 |
| 10 | 测试页查看结果，系统文件管理器打开 Download/Apple CarPlay | ZIP 可见、大小非零、页面路径准确；复制到电脑校验通过 | 公共路径／URI／字节数／SHA／receipt |
| 11 | 公共导出失败时重导出已有结果 | 不要求再跑完整测试；成功与失败显示准确 | 导出尝试与回读结果 |

若真实主题无法自动切换或手机没有产生稳定运动内容，应保留证据缺口，不能要求用户逐个实验开关重跑整套。一次集中验收仍不能覆盖所有固件故障；未覆盖项目在报告中明确列出。

### 12.3 验证样例：固定已知视频优先级

新增 CT/VideoSettingsCompatibilityTest.kt 的最小行为示例。此用例保护现有产品语义，不依赖私有字段名称；后续接入 resolver 后同一断言应保持成立。

~~~kotlin
package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class VideoSettingsCompatibilityTest {
    @Test fun softwareDirectDisablesPacingAndRequiresDirectOutput() {
        val output = carPlayVideoOutput(
            softwareDirect = true,
            smooth = true,
            direct = false
        )
        assertTrue(output.software)
        assertTrue(output.direct)
        assertFalse(output.smooth)
    }

    @Test fun hardwareYuvTakesPrecedenceWithoutClaimingSoftwareDecode() {
        val output = carPlayVideoOutput(
            softwareDirect = true,
            smooth = true,
            direct = false,
            hardwareYuv = true
        )
        assertTrue(output.hardwareYuv)
        assertTrue(output.direct)
        assertFalse(output.software)
        assertFalse(output.smooth)
    }
}
~~~

其他故障注入必须按矩阵中的输入／交错／结果编写，不以“异常被 catch”作为通过。对主线程 I/O 的证明需断言实际 FileAccess 所在线程，并在 worker 被 latch 阻塞时验证 main Handler 的取消事件已经执行。

## 13. 统一验证命令、产物与运行顺序

本文 artifacts/diplay-architecture/<run-id>/ 统一指工作区下 /Users/jzhp/Downloads/test-rea/artifacts/diplay-architecture/<run-id>/，位于 DiPlay 仓库之外。run-id 使用实际执行日期时间与短源码指纹，不复用上一轮目录。

以下命令是实施完成后执行的计划，本次文档任务不运行构建。

### 13.1 环境预检查

- 从 docs/BUILD.md 和本机 SDK 配置确认可用环境。
- CI 使用 JDK 25、Android platform 37、NDK 28.2.13676358；运行下限仍为 API 28。
- 上次本机 NDK 28.2 安装不完整，使用过外部 init script 选择 28.0。新一轮必须记录实际 NDK，不能将临时覆盖伪装为声明版本构建成功。
- 开始前归档旧 XML，防止把旧结果当作本次结果。核对 Gradle 任务是否实际执行或命中可证明的缓存。
- 构建所需凭证只使用现有本地配置，不打印、不写入测试包。

### 13.2 完整项目门禁

在仓库根目录执行：

~~~sh
./gradlew :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest   :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
~~~

--continue 用于让其他检查尽可能完成，不改变失败的退出状态。保存命令退出码和日志。

随后验证 Flyme debug／release 配置，无需切换构建属性：

~~~sh
./gradlew :mobile:processDebugMainManifest :mobile:processReleaseMainManifest --console=plain
python3 scripts/check_flyme_manifest.py
~~~

若交付可上车包，使用现有 assembleStandaloneDebug 路径及本地认证资产流程，仅归档 Flyme 包。版本号不自动变化，签名遵守现有平台签名流程。

### 13.3 失败后的验证

1. 保留首次全量结果。
2. 按失败机制集中修正，不只改断言数字让测试通过。
3. 对具体失败类及受影响类定向复测，例如：

~~~sh
./gradlew :common:testDebugUnitTest --tests '*SessionRuntimeLifecycleTest'   --tests '*DecoderDiagnosticsLifecycleTest' --console=plain
~~~

4. 若修正改变公共生命周期、媒体资源所有权或配置解析，再补完整门禁。
5. 汇总器分别保留 full、targeted、combined 三种视图；combined 只能写成合并证据，不能叫“又跑了一次全量”。

### 13.4 归档结构

~~~text
artifacts/diplay-architecture/<run-id>/
  README.md
  source-manifest.json
  environment.json
  baseline-tests.json
  task-status.json
  full/                    # 首次全量日志、XML、lint、构建结果
  targeted/                # 集中修正后针对性结果
  validation-results.json  # 分开记录全量／专项／未执行
  failure-triage.json
  apk/                     # Flyme 包和签名、SHA、debug／release Manifest
  device/                  # 实车原始包、验收清单、限制
  rollback.md
~~~

README 首行写明软件门禁和实车门禁各自状态。未做实车时 device/ 不伪造成功记录。

## 14. 验收标准与停止条件

### 14.1 软件架构验收

- [x] 运行会话由 Service 持有的 Runtime 唯一管理；Activity 不创建或关闭 Controller／Sink。
- [x] 全局对象不再持有 Activity owner／stopAction；订阅可解除，过期事件不能访问新页面或新会话。
- [x] 停止／重连幂等，资源真实退出前保留退休归属，不以等待超时冒充释放成功。
- [x] 诊断主线程不读写文件、不做素材摘要；磁盘卡住时取消与超时仍可运行。
- [x] 自动诊断只有一套可执行编排；旧数据可读／可导出，旧逻辑不会自动恢复运行。
- [x] 正式投屏与诊断共用方案解析；报告同时保留请求、方案和实际输出。
- [x] 触摸输入排队有界，合并不跨手势／指针／控制事件；可靠边界事件不能静默丢失。
- [x] MediaSink 门面仍保留，协议的流 owner／退役保护没有被删除。
- [x] Flyme 入口、媒体状态、焦点、方控去重和原车 HFP 保持。
- [x] 设置键、默认值、包名与版本无未授权变化。
- [x] 完整测试无未解释失败，构建／lint／Manifest 检查结果可追溯。

### 14.2 运行与性能验收

| 项目 | 验收规则 |
|---|---|
| 生命周期 | 本地确定性测试覆盖 20 轮重建／交接，资源计数回到预期；实车至少 3 轮前后台恢复无永久黑屏 |
| 诊断响应 | 可控存储阻塞期间，主 Handler 的取消事件被执行；状态在设定预算后结束等待 |
| 线程／资源 | 不因超时无限新增 worker；重复诊断结束后没有持续增长的活动 decoder／writer 数 |
| 视频行为 | 迁移前后同请求的默认解码／输出／调度策略相同；任何差异需单独说明 |
| 拥塞 | 人工 sender 阻塞下队列不超过设计容量，释放边界保留或明确触发受控故障 |
| 画质与跟手 | 本次只判是否引入回归；没有同源同条件数据时不宣称改善。颜色根因结论独立于架构验收 |
| 报告交付 | ZIP 必须可从公开目录读回并验证 manifest；只有内部副本则交付未完成 |
| 无法保存 | 明确错误与已有证据位置，不能承诺在磁盘故障下必有完整 ZIP |

性能基线采用相同固件、相同源样本、相同尺寸／fps／路径比较。至少记录 3 个稳定测量窗口的分布、样本数与运行负载，不用单次 fps 或主观“更顺”代替结论。

初始回归审查阈值：对可比样本，应用侧队列／解码／提交耗时 p95 若增加超过 max(基线的 10%，2ms)，要求复核并解释；这是本计划的筛查阈值，不是已实测车机指标，也不是物理触摸延迟承诺。无法形成可比窗口时记录“性能待验”。

### 14.3 明确停止条件

遇到以下任一情况，不宣布架构改造完成：

1. 两个主体都能创建同一条会话或释放同一资源。
2. 为通过测试删除原来的所有权／释放断言，却没有等价行为证明。
3. 诊断失败仍显示 Download 已保存，或公共文件无法读回。
4. 旧诊断运行、旧页面、旧 Binder 回调能够改变新会话。
5. CI 仍有未解释失败，或关键检查未执行。
6. 关键 API 28 路径只在高版本模拟器验证。

实车未完成时可以交付“代码及软件验证完成”的候选包，必须单列实车待验；不能把该状态写成项目全部完成。

## 15. 回退与兼容策略

### 15.1 源码回退单元

按诊断存储、会话所有权、视频方案、媒体提取、输入队列分组保留变更边界。实现可以分组组织 diff；是否提交由用户决定，不在未授权时自动提交。

- 诊断 I/O 修复和测试修复可独立保留，不必随视频优化一起回退。
- SessionRuntime、Service 入口与消费者迁移作为一个一致单元回退；不能只回退 Activity 留下两套 owner。
- VideoPlan 接入与序列化一起回退；已生成的新报告仍保留，不清理历史证据。
- 输入队列可整体恢复旧调度路径，但不得回退 CSeq 修复、媒体独立队列及显式暂停保护。

### 15.2 数据与包回退

- 本轮不改现有设置键，不做破坏性偏好迁移。
- 新报告只增字段与文件，旧包不认识新报告时也不能删文件。
- 旧运行数据转换为中断历史，保留原始文件以便检查。
- 保存此前可用 APK 与候选 APK 的包名、版本、签名和 SHA。Android 是否允许覆盖／降级取决于版本与签名，不能承诺任意直接覆盖安装。
- 不自动卸载应用清数据。需要改变安装方式时单独说明对配置和报告的影响。
- 发布回退前先复制实车结果包，确保诊断证据不随应用操作丢失。

## 16. 问题—任务—验收追踪

| 问题 | 主要任务 | 对应验证 |
|---|---|---|
| A01 会话依赖 Activity | T08—T12、T20 | S01—S11、D07、D08 |
| A02 诊断主线程 I/O | T01—T03 | D01—D06、E02 |
| A03 红灯测试基线 | T00、T06、T07、T22 | Q01、Q02及完整 CI |
| A04 视频方案分散 | T13、T14、T17 | V01—V04、E01 |
| A05 新旧自动编排并存 | T04、T05 | D04、D07—D09 |
| A06 输入排队与拥塞 | T18、T19 | I01—I05 |
| A07 大类职责集中 | T08—T12、T15、T16、T20 | 生命周期、资源归属、音视频隔离和设置套件 |

### 上游通用修复追踪

| 任务 | 上游来源 | 对应验证 |
|---|---|---|
| M01 USB PCM 麦克风 MTU | #1148／`8d5fd4b213` | R01 |
| M02 AirPlay 控制会话对端隔离 | #1123／`aeb1d73356` | R02 |
| M03 有线重连与晚到 USB 网络 | #962、#1057、#1085／`b014031aa7` | R03 |
| M04 类型化连接失败提示 | #1058、#1060、#1062、#1066／`9f6f3275eb` | R04 |
| M05 通用安全与日志卫生 | #1122、#1125—#1129、#1138、#1139、#1141、#1144 | R05 |
| T18 输入 MOVE 合并 | #1134／`ad03bf44de` | I01—I03 |

## 17. 本文的交付状态

截至 2026-10-30，T00—T22 与 M01—M05 软件实施及本地软件门禁完成，T24 本地源码、测试、包和回退材料已归档。T23 无目标实车环境，保持待验；不能将软件通过写成颜色、原生硬件性能或实际连接成功率已验证。当前版本维持 0.2.26／45，未新增升版、提交、合并或安装。

较早五项改进归档的验证记录为：shared 1239 项零失败、1 项跳过；common 最后专项 180 项通过；完整 common 1480 项中 99 项历史失败；普通／Flyme 构建与 lint 通过。它们仅用于历史对照。T00 冻结前最新 common 为100失败（99历史＋1未定位HTTP401）；本轮升版全量回到99项历史失败，该HTTP401未复现而非已修复。完整结果见 T00 执行记录。

本方案与 [已有解码诊断系统设计](../specs/2026-10-26-decoder-diagnostics-system-design.md) 配合使用：原设计规定要收集哪些颜色、清晰度、码流及硬件证据；本文规定运行架构如何可靠执行、持有资源、记录事实并交付结果。二者冲突时保留更严格的证据真实性和项目准则约束，实施前明确更新冲突条款。


## 18. 本地主分支同步分析补充（2026-10-29）

### 18.1 对比基线与结论边界

本节根据本地 Git 引用和当前工作区静态核对，不联网 fetch，不代表 2026-10-29 上游最新状态，也不替代第 11 节已有 v0.2.22 来源记录。

| 对象 | 本次观察 |
|---|---|
| 当前分支 | develop，HEAD ea408540；比 origin/develop 领先 1 个提交 |
| 本地 main | 1b1ac5f2；比本地 origin/main 落后 192 个提交 |
| 本地 origin/main | 143f4674；其 reflog 最后更新时间为 2026-10-22 10:16:35 +0800 |
| develop 与 origin/main | 各有 82／111 个独有提交；未形成整支合并关系 |
| 工作区 | 存在大量已修改及未跟踪文件，包含诊断、音视频、EAS 与生命周期改动；仅用 HEAD 不能代表交付源码 |

结论：本地已获取的上游更新不能等同于已合入定制分支；提交未出现在 develop 历史中，也不能单凭这一点断定功能未移植，须按当前工作区机制逐项核对。实施前重新记录引用、工作区指纹及与 v0.2.22 的关系，不直接 merge 或整体 cherry-pick。

### 18.2 候选修复与既有任务衔接

下表是补充筛选记录，不新增已承诺的 M 任务，不改变 T01—T24、M01—M05 的未实施状态。顺序仅适用于本表候选，既有高优先级安全与协议任务仍按原计划执行。

| 建议顺序 | 来源／机制 | 当前证据与处理方式 | 关联与验收要求 |
|---|---|---|---|
| 1 | fafa2d8c：通话结束撤销本进程音频模式请求 | AndroidMediaSink 仍保存并恢复 savedAudioMode；恢复其他进程曾请求的语音模式可能使本进程继续占用语音通路。评估结束时请求 MODE_NORMAL 的通用部分，不直接覆盖原车 HFP 策略 | 与 T16 协调；覆盖进入前已有通话模式、结束／异常退出、其他进程仍持有模式及原车 HFP；目标车验证通话后媒体恢复 |
| 2 | 88e847ec：Bonjour 避免反向 DNS | CarPlayBonjour 仍直接调用 JmDNS.create(address, ...)。评估预置本地主机名的地址包装，保留 IPv6 scopeId | 覆盖 IPv4、带 scope 的 IPv6、服务发布和无线重连；不以静态分析宣称启动耗时已改善 |
| 3 | 1bfd23a9：Android 9 TCP peer health | TcpLiveness 当前在 API 29 以下直接跳过调优；上游通过反射获取旧系统 Socket 文件描述符 | 仅移植 API 28 所需路径，不引入低于 API 28 的兼容分支；不得关闭原 Socket 描述符；反射受限／内核选项失败可诊断降级，不能中断连接；实车验证固件可用性 |
| 4 | e63b7b3d：USB 网络晚就绪后补发启动 | 当前 Controller 未见上游 WiredStartAgain 配套接入；涉及 Controller、iAP2、NCM 链路观测和 watchdog，不能孤立修改计时器 | 归入已有 M03，不重复建任务；以 M03 较新来源和预算规则为准，覆盖旧定时器迟到、终止后回调、补发一次和无重复会话 |
| 5 | 48c97171：多指触摸身份与槽位保持 | 上游使用稳定 pointer ID，并由会话维护 HID 槽位；需对照当前 Mapper、HID、Session 完整路径核对 | 与 T18 协调；覆盖首指抬起、指针索引重排、第三指加入、CANCEL 及会话切换；MOVE 合并不得跨指针边界 |
| 6 | ee96bcbb、d7a11368：热点接口识别 | ManualHotspotInterfaces 已有 observed?.isEmpty() == true，保留不可观测状态的修复已等价存在；候选接口过滤尚未见上游 rmnet／r_rmnet／ccmni 排除逻辑，不能写成全部已移植 | 仅评估剩余通用机制；先核对目标固件接口和现有热点所有权规则，覆盖蜂窝与热点同时在线、显式接口配置、上游接口排除及不可观测状态；不照搬其他车型匹配 |

主要源码位置（相对于模块源码包 com/shilapi/xcertplay）：shared/media/AndroidMediaSink.kt、shared/network/CarPlayBonjour.kt、shared/network/TcpLiveness.kt、shared/orchestration/CarPlayController.kt、shared/orchestration/WiredStartWatchdog.kt、shared/media/CarPlayTouchMapper.kt、shared/airplay/AirPlayHid.kt、shared/network/ManualHotspotInterfaces.kt。

### 18.3 排除范围与执行规则

- 吉利／领克 geely_fx11 仪表地图不因同属吉利而认定适用星瑞 2026 昆仑；不纳入本轮移植。
- BYD／DiLink 仪表、歌词、氛围灯与车辆接口不恢复；保留独立通用协议能力。
- 上游方控学习、媒体键和语音键必须服从现有 Flyme EAS 的去重、会话归属、通话门禁及焦点生命周期；不新增并行入口。
- 不整体替换上游设置页面或恢复多语言资源；仅提取符合现有中文分类与布局规则的具体修复。
- 按 T00 保存基线和现有未提交工作后再实施；全部授权代码与用例完成后统一执行第 13 节门禁，将候选的行为验证纳入 T22、实车验证纳入 T23。
- 本节仅记录分析与待评估工作；未修改产品代码，未运行构建或测试，未证明存在相应实车故障，也未证明候选修复有效。


## 19. 2026-10-29 实施进度记录（未验收）

本节前半部分保留2026-10-29、首次执行测试前的实施记录；后续结果及当前验收状态见T22记录与第20节。遵守用户“全部代码完成，最后统一跑测试”的顺序。

| 范围 | 已写入内容 | 剩余事项 |
|---|---|---|
| T01—T03 | 单线程有界存储、不可变版本与指针、恢复修订号下限、状态协调器、素材哈希缓存、阶段／封存回执 | 统一执行存储超时、崩溃恢复、迟到回执与封存回归 |
| T04—T05 | 诊断租约、资源释放确认、旧自动流程只读恢复、Service 单一自动入口 | 统一执行互斥、超时后资源保留与手工测试回归 |
| T06—T07 | 历史 99 例登记、部分过期 UI 夹具适配、XML 实际计数脚本、CI 退出码和证据上传 | 继续迁移旧资源注入／重试测试；失败分类须由当前代码和最终结果支持 |
| T08—T12 | SessionRuntime／Factory／Client、Service 所有权、前台启动门禁、显式重配、后台恢复、Surface 绑定与实际释放屏障 | 统一验证认证预算、权限恢复、旧回调、页面重建、停止与资源超时 |
| T13—T16 | 视频请求／方案／实际观测分离；生产与诊断共用解析器；音视频拆成各自资源所有者，Sink 保留协议外观 | 迁移原私有字段反射测试；统一验证 API 28 音频、解码和 Surface 行为 |
| T17—T19 | 有界会话／视频／存储事件、封存证据清单、触摸稳定指针、有界输入队列与 MOVE 合并、事件写锁计时 | 确认拒绝／丢弃事件完整记录；不能把下一帧到达当作物理触摸延迟 |
| T20 | 读取视频输出设置的适配器、从服务快照推导 UI 状态，移除 Host 重复认证／重试预算 | 页面旧生命周期夹具、设置不经确认不重连的回归收尾 |
| M01—M05 | 按选择移植 PCM MTU、对端隔离、有线启动预算、类型化失败、协议边界和安全限制；保留本地更完整实现 | 统一协议／安全测试；不整体合并上游，不引入车型专属路径 |

静态复核修正记录：孤立版本号冲突、服务销毁后迟到继续执行、被动诊断租约过期泄漏、上游补丁缺少构造参数、显式停止期间接受重配后丢失、Wi-Fi 重置被自动重试、重连不复查权限、视频旧连接回调和播放器状态串入新连接、失败态保存重连被页面门禁拦截、诊断租约结束后服务未续接。回归用例已补写或正在迁移，均不表示已通过。

最终记录要求：T22 使用新目录留存本轮命令、退出码、XML、lint、APK、manifest 与工具版本；T23 缺少目标实车时明确待验证；T24 在软件验证结束后列出已证实结论和缺失证据。不得用历史日志或静态审查替代本轮验证。


### T22 首轮统一验证（2026-10-29／30）

- 首轮目录：`artifacts/diplay-architecture/20261029-T22-first-ea408540`（仓库上一级）。原始日志、命令、退出码与 XML 已保留，后续不覆盖。
- Gradle 退出码 1：shared 测试编译发现两处接口夹具错误；common 编译发现 RuntimeEvidence 泛型参数声明语法错误。shared／common 缺少本轮 XML，明确判定未通过；未把缺失报告计为零失败通过。
- flyme-eas：10 项测试、0 失败、0 错误、0 跳过。Manifest、公开源码凭据边界检查、汇总脚本四项测试通过。APK 与 lint 因依赖编译失败尚未完成。
- 根因已逐项核对并集中修正；因涉及 common 生产代码，下一轮重新执行完整软件命令。软件门禁仍未通过，实车仍待验证。


### T22 后续集中修正（2026-10-30）

- 第三轮全量：flyme-eas 10 项通过；shared 1348 项零失败、1 跳过；common 1491 项中 147 失败。APK、lint、Manifest 通过。原始 XML 与日志归档于 `20261030-T22-round3-ea408540`，不是最终结果。
- 已修复产品遗漏：阶段标题刷新、明确资源提示被关键词回退覆盖、侧栏重建与关闭时的 UI 收尾；输入队列过期丢弃的仪表切换命令现在向等待者返回失败且最多一次，不会发送旧命令到新会话。
- 已迁移夹具：媒体通过 SessionFactory 构建；手工诊断只在自身视频和音频实际释放后结束其租约；恢复完成后才能 adopt；USB 权限接收器覆盖带私有权限的注册重载；过期 token 不可倒退时间复活。
- 验证目录 `20261030-T22-input-and-common-group1`：shared 1350 项、0 失败、0 错误、1 跳过；第一组 common 146 项、0 失败。该 common 是定向范围，不代表全量通过。
- 下一组迁移当前热点卡片编辑／保存／启动交互、保留接口 override、不恢复旧启动弹窗和已退役摇杆；通用会话 token 与输入队列断言保留。其余旧页面／设置断言和最终全量复跑仍待完成。

## 20. 2026-10-30 软件验证与本地交付结果

最终证据目录：`../artifacts/diplay-architecture/20261030-T22-final-full2/`（以仓库根目录为基准，位于仓库上一级）。入口为该目录README、commands.json、validation-results.json、failure-triage.json、task-status.json与apk/metadata.json。

| 检查 | 本轮结果 |
|---|---|
| 完整CI命令 | 退出码0；common全量重新执行，Flyme/shared实现未再修改，Gradle复用上一轮完整通过结果；原始UP-TO-DATE记录保留 |
| flyme-eas | 10项，0失败／错误／跳过 |
| shared | 1350项，0失败／错误，1项环境跳过：主机允许wildcard与指定地址同时绑定，无法满足AirPlayPortSelector端口占用假设 |
| common | 1501项，0失败／错误／跳过 |
| lint／构建 | mobile:lintDebug和assembleDebug通过；lint明细及APK签名日志已归档 |
| 包与兼容下限 | debug com.shihab.diplay.flyme.test／0.2.26-test／45；release Manifest com.shihab.diplay.flyme／0.2.26／45；minSdk28 |
| 认证输入边界 | 临时测试字节仅暂存两个指定文件；最终APK与已有本地offline-mfi输入逐字节匹配，未混入临时fixture；私密输入不进入公共源码快照 |
| 公开树／Manifest／汇总器 | 全部通过，汇总器4项测试通过；缺失XML或命令失败仍判失败 |
| 历史99项失败 | 56项原标识通过，43项按已删除入口或当前语义迁移，逐项关联替代XML；不是把43项记成原用例通过 |

集中修正包含：窗口配置命令核对UI归属与显式停止代次；退休连接等待最新测量尺寸后才续接；短暂相机缩窗恢复不误重连；菜单期间未批准尺寸保持门禁，关闭菜单后处理最新尺寸（包含防抖未结束的情况）；旧/丢弃输入命令完成回执只通知一次。诊断测试为每个Robolectric应用隔离存储和Looper，并等待真实恢复回执，未通过清除诊断租约或放宽生产释放屏障消除失败。

先前失败轮次和定向验证保持独立：`20261030-T22-final-full`记录common的6项异步夹具失败；`20261030-T22-diagnostic-isolation`关联21项通过；最终完整结果以full2为准。硬件流程改动后的完整执行已保留，不以定向结果冒充全量重跑。

本机声明NDK28.2目录为空，使用归档外部init脚本选已安装28.0.13004108，未改项目NDK声明。APK使用现有debug签名，已执行apksigner verify；未生成或发布release APK，未安装到设备。

T23仍待目标星瑞2026昆仑、Flyme Auto Android9/API28实车：USB/无线连接和重连、CH341/MFi、原车HFP、方控焦点去重、Surface/原生资源、颜色/清晰度、真实触摸延迟、一次完整诊断及Download回读。当前证据不证明这些实际环境结果，也不证明ReportShare历史HTTP401的根因已修复。
