# CarPlay 颜色发白、卡顿与跟手性修复规格

- 文档版本：1.0。
- 日期：2026-10-31。
- 状态：规格与实施任务已编写，待审阅；不代表功能已实现或验证通过。
- 关联任务：[详细实施任务](../plans/2026-10-31-carplay-video-color-latency-remediation-tasks.md)。
- 代码基线：develop，8e604be81a5bab8176ade449428034913a56b5cb。
- 适用产品：DiPlay mobile，吉利星瑞 2026 款昆仑，Flyme Auto，Android 9 / API 28，arm64-v8a。
- 用户执行约束：先完成当前范围内全部代码及测试用例，再统一执行测试；用户当前另有“先别测试”的指令，本文中的命令仅为后续执行说明，不构成本轮执行授权。
- 本轮交付：只新增本规格与任务文档，不修改生产代码，不执行测试，不提交或推送代码。

## 1. 问题定义与交付目标

### 1.1 用户观察

1. 浅色 CarPlay 界面发白，浅灰层次和文字、卡片边界不清晰。
2. 滑动、拖图过程中存在卡顿、停顿，部分模式明显更严重。
3. 手指移动后画面响应落后，感觉不跟手。
4. 普通断开重连、切换画面模式或设置后，可能持续显示“正在等待上一连接释放资源”。

前三项是本规格的主体。第四项是实施和复验的前置可靠性问题，保留独立任务及验收记录，不能用解决重连问题代替完成画质和性能修复。

### 1.2 目标

- G-COLOR：对支持的 SDR 8-bit 输入，保留已知参考画面的灰阶和颜色信息，消除已证实的错误范围扩展。
- G-LATENCY：优先保留普通硬解路径的低延迟，减少软件与 YUV 路径的额外排队。
- G-STUTTER：降低持续运动下的积压、解码器重建和等待关键帧造成的长时间输出空档。
- G-FPS：区分设置帧率、协议声明、实际接收、解码输出和显示提交，查明“设置 60、收到约 30”的边界。
- G-EVIDENCE：每项结论必须绑定实际输入、实际路径、时间窗口及产物，不把请求参数当成执行事实。
- G-LIFECYCLE：修复共用断开流程中可复现的锁等待；旧资源未释放时仍保留资源隔离。
- G-REGRESSION：保留连接、音频、触控、Flyme 方控、诊断互斥和配置持久化行为。

### 1.3 非目标

- 不将所有模式统一切换到软件解码或 YUV。
- 不通过降低分辨率或强制 30 fps 偷换性能验收条件。
- 不通过提高亮度、饱和度或对比度掩盖高光截断。
- 不将 BT.709 强制应用到所有 H.264/HEVC 输入。
- 不修改手机发送的 SPS/VUI，伪造源流颜色声明。
- 不承诺所有固件、所有 OMX/C2 解码器都有同一种修复方式。
- 不增加第三方解码库、其他 ABI、独立测试 App 或其他车型适配。
- 不在此任务重构整个 Activity、音频系统或会话架构。
- 不测造完整触摸到面板延迟，不将截图解释成面板校准结果。
- 不修改版本号，不扩大到自动发布或推送。

## 2. 证据基线与可追溯性

### 2.1 原始输入

日志目录：仓库同级的 ../diplay-log/，不将原始 ZIP 或真机图片提交到公共源码树。

| 证据 ID | 文件 | SHA-256 | 用途 |
| --- | --- | --- | --- |
| E-FIXTURE | decoder-diagnostics-27c2846c-e416-4642-b6ed-f6e3d835c6d6.zip | 93c3d160a5f18127da6a70f309054ea58e9f0147d5cca8e6322a2da027a25092 | 68 个本机素材测试、截图、逐帧轨迹、设备负载 |
| E-LIVE | live-carplay-20261031-114149-46f74016.zip | 525b09cfd0d91164499919dfb9cd6bbb6c5638368204ff230fd264a65448586c | 最新有效真机汇总，含 8 段采样 |
| E-LIVE-SW | live-carplay-20261031-113136-5919a8c1.zip | 0ee7a7facc6f52b3794a950ac38fcf381dd375e3e22a3957e69e7091d4f56a50 | 补充较早软件直出 2 段采样 |

上述哈希已从当前原始文件读取。后续执行必须重新核对；输入变化时创建新的证据版本，不能覆盖本基线。

有效真机 ZIP 之间重复包含历史段。按 sampleId 去重后共 10 段：5 种路径各一段 static 与 dynamic。不能把 10 个 ZIP 当成 10 轮独立实验。

11:44～11:48 的 6 个 live-carplay ZIP 为 0 字节。必须归类为不可读产物，不能纳入“测试通过”或计算性能均值；其原因在本轮没有得到证明。

两个名称近似的 decoder-diagnostics ZIP 归档字节哈希不同，但本轮已核对：entry 名称集合及每个 entry 的内容均一致，属于同一逻辑实验，不能计为两轮。后续去重同时核对 runId、entry 内容与 sampleId，不能只看 ZIP 字节哈希。另一个归档的 SHA-256 为 281991345c742d040ee97ad420c06cfa02fb057e1f5879f88056768eb9c341e6。

### 2.2 本机动态素材性能事实

输入为 h264_motion1080，1920×1080，60 fps，比较 P1～P6。

| 路径 | 实际组件 | 输出 fps | codecQueueToOutput 均值 ms | applicationWait 均值 ms | 本地输出轨迹最大间隔 ms |
| --- | --- | ---: | ---: | ---: | ---: |
| P1 系统默认→TextureView | OMX.MTK.VIDEO.DECODER.AVC | 60.00 | 11.06 | 0.12 | 39.35 |
| P2 系统默认→SurfaceView | OMX.MTK.VIDEO.DECODER.AVC | 59.67 | 10.85 | 0.12 | 38.95 |
| P3 严格软件→TextureView | OMX.google.h264.decoder | 60.01 | 53.31 | 0.32 | 55.59 |
| P4 严格软件→SurfaceView | OMX.google.h264.decoder | 59.87 | 49.65 | 0.27 | 36.86 |
| P5 软件→应用 YUV | OMX.google.h264.decoder | 42.64 | 116.58 | 134.40 | 695.07 |
| P6 硬件→应用 YUV | OMX.MTK.VIDEO.DECODER.AVC | 42.02 | 92.18 | 208.99 | 1093.44 |

来源：cases/h264_motion1080-P*.json 与 traces/h264_motion1080-P*.tsv。

最大间隔的计算：在同一 decoder instance、同一输出事件类型内，按本机 local_ns 排序，相邻 surface_release 或 yuv_draw 事件作差。它是该 case 轨迹的局部输出间隔，不能直接宣称等于面板停顿时长。

P5、P6 日志出现 recovery: video backlog kept growing or exceeded hard limit; waiting for keyframe。
YUV 拷贝均值约 9～10 ms，绘制/交换均值约 6 ms。现有实现已经复用 DirectByteBuffer，且 pixelStride=1 已有按行批量复制；实施不能将这些现有能力重复写成新增优化成果。

上述结果证明该测试条件下普通硬解能接近 60 fps，YUV 路径有吞吐与积压问题；不证明所有真实 CarPlay 内容都能达到 60 fps。

### 2.3 真机 dynamic 采样事实

| 实际路径 | 接收 fps | 显示提交 fps | queueToOutputMillis 均值 ms |
| --- | ---: | ---: | ---: |
| H.264 严格软件直出 | 29.52 | 29.52 | 79.92 |
| H.264 普通硬解 TextureView | 29.00 | 29.00 | 11.71 |
| H.264 软件 YUV | 31.47 | 31.37 | 94.62 |
| H.264 硬件 YUV | 30.16 | 30.11 | 39.95 |
| HEVC 普通输出＋MTK 调优 | 29.86 | 29.86 | 44.51 |

所有请求的 fps 设置为 60，但实际接收约 30 fps。普通 H.264 的接收数与显示提交数接近，不能据此指控本地解码器丢掉一半视频帧。

这些样本不是完全匹配的因果 A/B：手机动作、场景和部分开关不同。HEVC 样本同时开启 MTK 调优，不能把约 33 ms 差值全部归因于编码格式。

static 样本允许低刷新。不能用静止画面的接收 fps 判定 60 fps 性能失败。

### 2.4 触控事实

dynamic 样本触控分发均值约 5.8～6.1 ms，本地入队返回均值约 0.19～0.27 ms，未观察到本地入队拒绝。

该事实仅覆盖 Android 事件交付与入队返回。未覆盖 HID 实际写出、手机确认、手机处理、返回帧对应关系、无线单向传输、屏幕合成和面板扫描。

本轮可以优先处理已观察到的视频额外延迟，但不能宣布“触控链路完全正常”。

### 2.5 颜色事实

1080p h264_light_full 与 h264_dark_full：
- P1、P2 灰阶样点与独立参考一致。
- P3、P4 的 16 变为 0、224 变为 242、232 变为 251、235 及以上接近或达到 255。
- P5、P6 保留上述灰阶差异。
- P4-sps 显式请求 full range 后，仍观察到同一异常。
- 对应 limited 素材的 P1～P6 基本保留灰阶，存在编码/转换造成的小幅量化差异。

640×360 的 h264_full 与 hevc_full：
- P1～P4 均观察到有限范围扩展形状。
- P5、P6 灰阶与参考接近。
- h264_limited、hevc_limited 的 P1～P6 基本正常。

可复核的灰阶样例：

| 参考 RGB 灰阶 | 异常 Surface 截图 | 应用 YUV 截图 |
| ---: | ---: | ---: |
| 16 | 0 | 16 |
| 192 | 205 | 192 |
| 224 | 242 | 224 |
| 232 | 251 | 232 |
| 240 | 255 | 240 |
| 248 | 255 | 248 |
| 252 | 255 | 252 |

结论：存在与 clip((x-16)*255/219) 一致的数值形状；错误路径与分辨率、解码器、输出方式有关。不能将“硬解颜色全通过”或“软件解码必定更准”作为规格前提。

### 2.6 源颜色声明事实

真实 H.264：full_range=true、primaries=1、transfer=13、matrix=6。
真实 HEVC：full_range=true、primaries=1、transfer=13、matrix=1。

必须分别处理范围、矩阵、原色及传递函数。不能只看到 primaries=1 就强制 BT.709 矩阵。解码器 color-standard=86 也不能仅因不属于常见枚举就当作损坏数据或直接覆盖。

### 2.7 未确定事项

- 单个截图不能定位厂商解码器、颜色转换、BufferQueue、SurfaceFlinger 或截图接口中的唯一责任层。
- 640 与 1080 结果不同的具体机制尚未确定。
- 真机接收约 30 fps 的原因尚未确定；maxFPS 是上限声明，不是手机恒定发送承诺。
- HEVC 与 MTK 调优各自影响尚未分离。
- 热降频、GC 或 Wi-Fi 质量没有被证明是本轮主要根因。
- RGB 截图经过截断后，不具备恢复丢失高光细节的信息。
- 本机素材正确不能直接等同于真实 CarPlay 源画面或物理面板正确。

## 3. 当前实现与责任边界

| 文件/模块 | 当前责任 | 本任务允许的改动 |
| --- | --- | --- |
| shared/src/main/java/com/shilapi/xcertplay/media/VideoPlanResolver.kt | 输出与解码策略纯决策 | 补充经验证的策略，不绕过偏好或暗改默认 |
| shared/src/main/java/com/shilapi/xcertplay/media/VideoPipelinePlan.kt | 请求、计划、实际观察 | 明确请求与实际信息，增加兼容的证据字段 |
| shared/src/main/java/com/shilapi/xcertplay/media/VideoColorMetadata.kt | SPS/VUI 解析 | 保留未知值，校验矩阵与范围映射 |
| shared/src/main/java/com/shilapi/xcertplay/media/VideoColorOverride.kt | 输入颜色请求 | 修正经过证据确认的参数传递，不伪造源声明 |
| shared/src/main/java/com/shilapi/xcertplay/media/AndroidVideoPipeline.kt | codec、队列、输出、生命周期 | 色彩决策接线、输出丢弃、恢复策略和计时 |
| shared/src/main/java/com/shilapi/xcertplay/media/SoftwareYuvColor.kt | YUV→RGB 参数解析 | 保持来源优先级、SDR 边界和用户手动设置 |
| shared/src/main/java/com/shilapi/xcertplay/media/Yuv420Frame.kt | crop/stride 平面复制与缓冲 | 基于实测热点增加快路径，保留通用正确路径 |
| shared/src/main/java/com/shilapi/xcertplay/media/SoftwareYuvRenderer.kt | 线程拥有的 EGL/GLES2 输出 | 减少重复查询、分配、上传和交换开销 |
| shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt | 压缩帧队列、参考链 | 有界队列与有参考关系的安全恢复 |
| shared/src/main/java/com/shilapi/xcertplay/media/VideoBacklogRecovery.kt | 持续积压识别 | 区分长期积压、短突发、静止尾帧 |
| shared/src/main/java/com/shilapi/xcertplay/media/InputCommandQueue.kt | 输入串行化、MOVE 合并 | 保留 DOWN/UP/CANCEL、Siri/媒体优先级与归属 |
| shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt | 显示能力声明 | 记录实际 maxFPS 与尺寸，修正确认的传递缺陷 |
| shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt | RTSP、事件通道、关闭 | 独立前置项：关闭顺序和锁等待修复 |
| common/src/main/java/com/shilapi/xcertplay/session/SessionFactory.kt | 每连接构造 sink/controller | 计划接线与释放事实，不能跳过资源确认 |
| common/src/main/java/com/shilapi/xcertplay/session/SessionRuntime.kt | 会话状态与资源归属 | 保留阻塞状态与代际防护，补充可解释证据 |
| common/src/main/java/com/shilapi/xcertplay/VideoAdaptationRun.kt | 真实窗口统计 | 延伸分布、间隔、实际配置，不编造测量 |
| common/src/main/java/com/shilapi/xcertplay/VideoTroubleshooting.kt | A/B 条件核对 | 分离实验变量、阻止混杂因果结论 |
| common/src/main/java/com/shilapi/xcertplay/DecoderDiagnosticsActivity.kt、DecoderDiagnosticsRun.kt 等 | 素材计划、截图、计时、导出 | 配对取证、扩充矩阵、单项持久化 |
| common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt | View、输入、配置快照 | 最小接线，保留触控映射、窗口生命周期 |
| common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt | 用户设置和诊断入口 | 必要的说明与既有设置接线，不新增重复设置入口 |

表中给出现有代码的实际路径；逐项任务进一步列出修改点与测试文件。

## 4. 总体方案与阶段边界

### 4.1 阶段顺序

A. 固化证据、修正证据语义与编写回归用例。
B. 修复共用断开流程中的可证明死锁，并完成视频相关代码修改。
C. 优化已有 YUV 复制/渲染、输出时效和恢复策略。
D. 补齐帧率和 HID 实际写出边界。
E. 在全部代码完成且用户解除测试暂停后，统一执行软件验证。
F. 在目标车机上执行受控矩阵，选择有证据的 Surface 修复分支。
G. 若 F 发现需要新增代码，集中完成该批修正，再对失败与受影响范围验证。
H. 依据完成的验收项交付；没有过车机门槛的路径保持实验性或不可用。

F 是硬件事实采集，不是“写一行改一行反复跑单测”。无法用宿主机模拟替代厂商 Surface 行为。若用户继续要求不测试，则停止于文档/代码待验证状态，不自行开始 F。

### 4.2 首选与兼容路径

- 首选候选：普通硬解 → 当前安全显示路径，保持低延迟；仅在通过颜色门槛后称为颜色修复成功。
- Surface 范围请求有效：采用最小请求/传播修正，记录实际结果；不能仅因 MediaFormat 返回 full 就放行。
- Surface 范围请求无效而原始 YUV 正确：厂商 Surface 路径不可直接认定已修；应用 YUV 是兼容候选，需通过性能门槛。
- 原始 YUV 自身错误：修正解码配置或取消错误修正，不能在 RGB 末端反向猜测原始亮度。
- 两个路径都不满足门槛：交付明确的 blocked 证据，不静默降为 30 fps，不发布“全模式修复完成”。
- 默认、实验性和手动偏好继续由现有设置控制；没有证据不做全局自动切换。

### 4.3 分支选择记录

每个分支必须有：
1. 输入文件/配置哈希、编码、可见尺寸、编码尺寸、裁剪范围。
2. 实际解码器、API、构建指纹、输出路径、范围/矩阵来源。
3. 同一帧或可证明相同首个随机访问画面的 YUV 和 RGB 证据。
4. 修改前后数值、性能结果、未覆盖条件。
5. 采用或拒绝理由，以及回退是否发生。

## 5. 功能性需求

### R-C01：源颜色声明与实际输出保持分离

必须分别记录 requested、source、decoderReported、rendererApplied、captureObserved。
unknown、not_declared、malformed、unsupported 必须区分。
源流没有声明时不得把假设写回“源声明”。
HDR 或宽色域不属于当前 SDR 渲染器支持范围，必须显式拒绝或延续既有明确回退。

场景：
- full=true、matrix=6 的 H.264，自动 YUV 按 full 与 BT.601 矩阵计算。
- full=true、matrix=1 的 HEVC，自动 YUV 按 full 与 BT.709 矩阵计算。
- 缺失 matrix 时采用既有声明过的假设，并记录 assumed，不声称由 SPS 得到。
- 手动范围/矩阵只改变应用渲染参数，不修改编码内容与源证据。

### R-C02：建立配对像素取证

对已知素材，采集三个事实层：
- 源参考：实际编码文件的独立解码参考值及哈希。
- 原始解码输出：首帧有效 Y/U/V 样点、crop、stride、pixelStride、range/matrix 声明。
- RGB：TextureView 或 Surface 缓冲截图中的对应像素。

原始 YUV 取证只在诊断实例且解码器支持无 Surface 的输出路径上进行；生产 Surface 模式通常不能直接 getOutputImage。必须记录“同帧”“同输入同首帧的独立实例”或“无法配对”，不得伪造同实例原始 YUV。

截图帧与性能帧用不同实例时，sameInputAndPath=true 不等于 sameDecoderInstance=true。
首帧冻结、截图完成和资源释放各自记录事实；不能凭一个 Boolean 混合表示。

### R-C03：修复错误范围转换

修复只能根据 R-C02 选择：
- 配置请求未传递：修正传递，并以截图值证明实际生效。
- 解码器报告正确但 Surface RGB 错误：记录为 Surface 输出路径异常，不因 reported full 解除告警。
- 应用 YUV 颜色计算错误：在 SoftwareYuvColor 修正范围/矩阵系数和优先级，保留已有正确组合。
- 源 YUV 正确且应用 YUV 正确：仅将其作为已验证兼容候选，仍须通过性能门槛。
- RGB 已截断：不得用反向 RGB 滤镜宣称恢复高光。

修复必须覆盖 full/limited、H.264/HEVC、640×360/1920×1080、浅色/深色，不能只验证一个黑白样点。

### R-C04：保持用户画面设置语义

- 诊断实例默认 neutral，日常亮度/对比度等偏好不被重置。
- current 路径记录真实日常设置，比较时必须核对是否一致。
- 切换模式仍遵循保存与明确重连，不自动打断正在使用的 CarPlay。
- 镜像与非主屏不得被主屏修正污染。
- 不为本修复恢复已经移除的地图共享、侧栏、BYD 或其他车型逻辑。

### R-P01：优化真实热点，保留 YUV 布局正确性

- 先记录 planeCopy、textureUpload、draw、swap 的独立分布。
- 保留已有 DirectByteBuffer 复用和 pixelStride=1 批量行复制。
- 对 pixelStride=2 交织色度布局增加经过边界校验的批量读取/拆分路径。
- pixelStride 为其他合法值时继续使用通用路径。
- 使用 position/limit 和 Long 地址计算，避免越界与整数溢出。
- 正确处理 odd crop、padding、共享 U/V 底层存储、只读输入。
- 工作集随当前尺寸有界，尺寸下降不会无限保留多套大缓冲。
- 每帧 Image 在 finally 关闭，output buffer 每次只释放一次。
- 热路径不因追求“零拷贝”跨线程持有 codec Image 或破坏 EGL 归属。
- 不在没有实测收益时引入 JNI/NEON、额外 native 库或更高 API 要求。

### R-P02：减少 EGL/GLES 重复工作

- 缓存 program 的 attribute/uniform location。
- 输出 Surface 不变时不重复创建 EGLContext/EGLSurface。
- 纹理尺寸不变时复用纹理存储，保持现有 glTexSubImage2D 行为。
- 仅在窗口几何变化时刷新 viewport 所需尺寸；保留 Surface 实际大小变化的可见更新机制。
- 图像裁剪、stride 和纹理采样结果不能因缓存失效而错误。
- Swap、draw、upload 单独计时；EGL 失败必须保留错误及实际回退结果。
- 退出时先让 codec 不再使用资源，再按既有所有权释放 EGL，不释放框架 Surface。

### R-P03：区分压缩帧与已解码帧的丢弃

压缩帧：
- 不能随意丢弃 H.264/HEVC 参考帧后继续提交依赖帧。
- 队列丢失参考链后，显式等待合法随机访问画面。
- Config、DetachSurface、SurfaceChanged 等控制消息不得因清帧丢失。
- 短暂 USB/Wi-Fi 突发与静止尾帧不能立即触发重建。

已解码输出：
- 对过期且已有更新输出的画面，可以释放而不显示，避免花时间复制/绘制已过期 YUV。
- live 模式按时效策略处理，首帧、静止最后一帧、EOS、诊断冻结/完整素材测量必须有独立规则。
- 丢显示帧不等于丢压缩参考帧；分别计数。
- 每次跳过 Image 仍正确释放 output buffer。
- 不新增无限堆积的“等待更新画面”队列。

### R-P04：恢复策略必须有界且可解释

- 记录触发前 queue depth、最旧年龄、输入速率、输出速率、decoder instance。
- 区分 discard_decoded_output、reference_chain_reset、codec_recreated、keyframe_requested。
- 不把任何一次晚帧都升级为 codec 重建。
- 关键帧请求去重且有节流；没有收到关键帧必须保持真实等待状态。
- 不重复使用已经过期的随机访问画面创建一连串过期解码器。
- input stall、非法配置、厂商异常仍允许真实重建，不以“禁止重建”掩盖故障。
- 单次恢复经历只归因一次，不把尾部未观测到的恢复当作成功。

### R-F01：分离五种帧率

必须记录：
1. fpsSetting：用户保存的请求值。
2. advertisedMaxFps：实际发出的能力声明值。
3. sourceCadence：源 PTS 相邻差统计；无 PTS 则为 unknown。
4. receivedFps：本机接收到的访问单元速率。
5. submittedToDisplayFps：本机提交到显示路径的速率。

另外记录 encodedWidth/Height、visibleWidth/Height、crop、像素宽高比与帧计数口径。
不把重复帧、配置包或无效访问单元算成有效解码画面。
声明 60 且收到 30 时不自动补帧；明确原因未定位或定位到具体边界。

### R-F02：帧率传递审计

沿以下链路核对值：
AirPlayPersistence → CarPlayHostActivity.prepareSessionRequest → SessionStartRequest/SessionFactory → AirPlayConfig.main.fps → AirPlayInfoPlist 的 maxFPS → 实际 SETUP/视频配置可观察字段 → ScreenStream 的 PTS/到达时刻。

如果没有标准化的“手机选定 fps”字段，则字段为 not_reported，禁止由 maxFPS 回填。
发现传递缺陷才修改功能逻辑；没有发现时交付可复核的链路证据，不编造“已修复 60 fps”。

### R-I01：触摸发送证据完整，但不虚构端到端关联

- 保留 Android action、pointer/slot、会话 generation、入队接受、队列等待和 HID 发送结果。
- MOVE 合并只合并合法相邻移动，不丢 DOWN/UP/CANCEL，不把旧会话事件发送给新会话。
- 队列时间、锁等待、write/flush 时间分别统计。
- 实际写出不等于手机收到；手机收到不等于某帧由该触摸触发。
- 不用“下一帧到达减触摸时间”生成 touchToPhoton。
- 现有 6 ms 分发、0.2 ms 入队作为排查依据，不作为触控链路已全部验收的证明。

### R-E01：证据统计与导出

- 每个窗口携带 sampleId、sessionId、generation、decoderInstance、configurationHash、planId。
- 改变实际编码、路径、颜色配置、分辨率或实例时，结束旧窗口并记录边界。
- 包含均值、p50、p95、p99、最大值、样本数、丢失数量和测量范围。
- 缺失字段为 null/unknown，不能写 0。
- 不将逐帧若干不同样本集合的均值相加，宣称完整延迟。
- 编码到达间隔、解码输出间隔、YUV 完成间隔分别命名。
- 统计容量、事件缓冲、触控时间线、原始 YUV 样点数量均固定上限；发布文件计入大小边界。
- 新字段向后兼容；旧报告能被读入并显示缺项。
- 不在报告中记录认证密钥或未经用户授权的原始输入音视频。
- 原始 YUV 只保留固定素材的小量样点；真实 CarPlay 使用现有授权采集边界。

### R-E02：实验变量与比较条件

比较配置至少包括：手机/iOS、场景、主题证据、连接方式、分辨率、fpsSetting、实际编码、实际解码器、View、YUV 模式、颜色参数、画面调节、HEVC、MTK、lowLatency、smooth。

三组独立对照：
- A/B 编码：H.264 与 HEVC，MTK 与 lowLatency 均关闭，其他条件相同。
- A/B MTK：同一编码，只有 MTK 变化。
- A/B 输出：同一输入/编码/颜色，只有输出路径变化。

比较允许变化的字段由 experimentType 定义，其他字段不同即 blocked。
现有 HEVC 对照规则保留历史兼容，但不将它泛化为所有实验。
条件匹配只能允许描述指标差异，不自动等于因果证明。
重复轮次按 A/B/B/A 或 A/B/A/B 交替顺序执行，至少 3 组有效配对；不以一轮决定全局默认。

### R-L01：断开死锁前置项

已有未提交草案：
- shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt。
- shared/src/test/java/com/shilapi/xcertplay/airplay/AirPlaySessionCloseTest.kt。

草案设计意图：
- 先关闭事件 transport，解除阻塞写，再清理媒体。
- 写入错误在释放 eventWriteLock 后触发 close，避免持写锁等待媒体锁。
- 防止关闭期间晚到的 event socket 重新挂入旧 session。

必须审查它的生命周期、并发可见性、通知次数与失败路径，不能因为已写入就标 completed。
先前统一门禁被用户中止，退出码 130；输出曾出现 AirPlayEventPeerTest.aStrangerCannotTakeTheEventSlot 失败。尚未完成原因分析与复验，因此当前状态是 UNVERIFIED。
用户未授权本轮继续测试。后续集中验证必须包含这个失败项及新增 4 个 API 28 用例。
禁止删除失败测试、放宽断言或以强制清空 BLOCKED_RELEASE 放行新 decoder。

### R-L02：资源与会话隔离

- controller、video、audio 的实际释放确认不能用超时替代。
- 旧 connection/generation 的回调不能驱动新会话。
- SurfaceDetach、关闭、诊断 lease 和 UI owner 的归属继续有效。
- native 调用永久阻塞不等于本规格已证明可原地恢复；保留阻塞证据，不能假报释放。
- 不为本修复引入自动杀进程、自动改热点、自动改手机设置。

## 6. 数据契约

### 6.1 版本与兼容

- 保留现有 report 主结构；新增 videoRemediationSchema=1 的嵌套对象。
- 新读者在缺少嵌套对象时继续读取旧 summary，明确 unavailable。
- 不改写历史原始 ZIP；去重索引与分析结果使用独立文件。
- 度量使用 System.nanoTime，同一过程内才允许作差；跨进程只使用明确验证过的本机时钟语义，跨设备不作差。
- Android uptimeMillis、墙钟、源 PTS 均单独命名。

### 6.2 建议的最小字段

以下为规范字段，不要求新建同名 Kotlin 类；优先扩展既有 evidence/report。

| 分组 | 必需字段 |
| --- | --- |
| identity | sampleId, sessionId, generation, decoderInstance, sourceHash, configurationHash, planId |
| requested | fpsSetting, codecPreference, outputPreference, mtkTuning, lowLatency, colorOverride |
| source | codec, declaredRange, primaries, transfer, matrix, visibleSize, codedSize, ptsBasis |
| decoder | name, softwareObservation, outputFormat, fallbackReason |
| applied | renderPath, range, matrix, rangeSource, matrixSource, correctionPolicy |
| timing | distribution 按阶段分组，samples, mean, p50, p95, p99, max, unit |
| flow | received, submitted, decodedDiscarded, invalidCompressed, recoveryCount, keyframeRequests, lastOutputAge |
| comparison | experimentType, changedFactor, matchedConditions, gaps, baselineSampleId, candidateSampleId |
| pixelEvidence | sourceReferenceHash, frameBasis, sameDecoderInstance, sameInputAndPath, rawSamples, rgbSamples |
| limitations | measurementScope, missing, droppedEvents, physicalPanelMeasured=false, touchToPhotonMs=null |

软件/硬件判定维持观测来源；不能仅按用户选项填 actualSoftware=false。
若引入 API 29 的 isHardwareAccelerated 等信息，API 28 必须保持 unknown 或采用现有明确可解释的命名规则。

## 7. 验收标准与判定口径

### 7.1 验收层级

- L1 软件逻辑：单元测试、API 28 模拟生命周期、布局与配置传递。
- L2 本机固定素材：目标车机真实 codec、EGL、截图、轨迹。
- L3 真机 CarPlay：实际连接、固定操作、实际输入和输出模式。
- L4 物理显示/完整触控：外部参考与高速录像，单独可选验收。

L1 通过不能替代 L2/L3。L2/L3 未测时必须标未完成。L4 未完成不阻止交付局部软件修复，但不允许宣称物理色准或完整触控延迟达标。

### 7.2 颜色工程门槛

针对版本固定、独立解码参考的 SDR 素材中心区域：
- 灰阶通道均值相对对应独立参考误差绝对值 ≤2 code。
- 235、240、248、252 中参考差值 ≥4 的相邻灰阶，输出差值必须 >0，不能全部合并为 255。
- 16、20、24 等低灰阶也必须保留对应差异，不能仅修亮部。
- RGB/CMY 和中灰采用每通道误差 ≤3 code 的工程门槛，若编码参考本身非标称值，使用独立参考值。
- 采样避开压缩边界与缩放边缘，区域尺寸、坐标、是否缩放必须记录。
- full 与 limited 均通过，640×360 与 1920×1080 均覆盖。
- 这些数值是本任务的数字图像回归门槛，未经色度计校准，不称为物理色准标准。
- 某 API 截图接口本身改变颜色时，必须给出独立验证并更换可解释观察点，不能修改预期值迎合截图。

### 7.3 普通硬解性能门槛

1920×1080、固定 60 fps 连续运动素材、预热 2 秒后至少连续 20 秒：
- 显示提交 fps ≥57。
- applicationWait p95 ≤5 ms。
- codecQueueToOutput p95 ≤25 ms。
- 无非预期 codec 重建或参考链恢复。
- 稳态同实例输出最大间隔 ≤100 ms。
- 不改变输入尺寸、fps、内容或测量口径换取通过。

以上是拟定工程门槛，实施前后均以同一版本输入、同一环境核对；不将短 4 秒历史结果当作已通过新门槛。

### 7.4 应用 YUV 性能门槛

若要从“颜色兼容实验路径”提升为日常可用候选：
- 同一 1080p60 素材显示提交 fps ≥57。
- applicationWait p95 ≤50 ms，且不持续增长。
- codecQueueToOutput p95 ≤100 ms。
- planeCopy+textureUpload+draw+swap 应提供同帧合计，目标 p95 ≤12 ms；不能简单相加不同统计集合的 p95。
- 稳态同实例输出最大间隔 ≤100 ms，无 ≥250 ms 的应用可归因输出空档。
- 无因持续超载触发的循环重建。
- 连续 5 分钟运行后缓冲、Image、EGL 资源有界，无随帧数增长的保留对象。
- 未达门槛时维持实验性和事实说明，不能强制用户默认为此模式。

软件直出的额外 codec buffering 可能来自平台实现，本任务不承诺把所有软件 decoder 降至硬解水平；不以隐藏指标达标。

### 7.5 真机性能门槛

每组条件至少 3 次有效 dynamic 采样，每段 20 秒，固定场景与操作。
- 普通硬解新增修正后，局部视频延迟中位数不比同条件基线增加 >5 ms，p95 不增加 >10 ms。
- 输入队列不得丢失 DOWN/UP/CANCEL；旧会话事件不得写入新连接。
- 收到的有效画面在稳定窗口内输出保留率 ≥98%，主动解码后丢弃单独列出；跨边界在途帧差异单独解释。
- 有输入持续运动时，不能出现可归因于应用积压/恢复的 ≥250 ms 输出空档。
- 60 fps 请求需要显示实际广告值；若手机仍发送约 30，必须提供阶段证据与未解决原因，不能宣称 60 fps 已修。
- HEVC 与 MTK 的结论分别来自单变量对照；不将来源不同的现有样本当作受控性能差值。

### 7.6 生命周期门槛

目标车机执行至少 20 轮断开→连接与 20 轮画面设置→明确重连：
- 无持续 BLOCKED_RELEASE、ANR、重复 session-ended 通知。
- 真正阻塞时保留哪一层未确认的证据，禁止伪造释放。
- 活动/退休 decoder、音频和 Surface 数量回到预期稳定值。
- 故障注入中事件写入阻塞、写失败、late accept 不形成锁环。
- 断开生命周期的软件验收与车机轮次均完成后，才关闭 R-L01。

## 8. 必须覆盖的测试矩阵

### 8.1 固定素材颜色

| 维度 | 必需取值 |
| --- | --- |
| 编码 | H.264、HEVC |
| 范围 | full、limited |
| 尺寸 | 640×360、1920×1080 |
| 内容 | 灰阶/彩块、浅色近白细节、深色细节 |
| 路径 | P1、P2、P3、P4、P5、P6；不支持项明确 unsupported |
| 参数 | decoder default、fromSps；手动范围/矩阵仅用于诊断隔离 |
| 几何 | 原生尺寸、非 16:9 窗口缩放各至少一组 |
| 颜色声明 | BT.709 矩阵、BT.601 矩阵；缺失与不支持声明 |

不要求全笛卡尔积穷举到失控。最小覆盖集必须包含所有已失败组合及 full/limited 对照；每个省略组合记录覆盖依据和风险。

### 8.2 固定素材性能

- h264_motion1080、h264_clarity1080：P1～P6。
- HEVC 动态素材仅在目标车机具备支持且实际生成/校验输入后加入；不能用 640×360 10 fps 静态 HEVC 代替 1080p60 性能结论。
- 每路径固定源哈希，保持最大帧率、尺寸、日志开销与前后台状态一致。
- 保留历史 4 秒快速样例；正式性能门槛使用 20 秒窗口，长稳态用 5 分钟。
- 丢帧/恢复必须有诊断专用测量策略，不能为了让 fps 好看跳过关键计数。

### 8.3 真机控制矩阵

| 组 | 编码 | 输出 | MTK | lowLatency | smooth |
| --- | --- | --- | --- | --- | --- |
| V0 基线 | H.264 | 普通硬解、当前安全 View | 关 | 关 | 关 |
| V1 编码对照 | HEVC | 与 V0 相同 | 关 | 关 | 关 |
| V2 MTK 对照 | H.264 | 与 V0 相同 | 开 | 关 | 关 |
| V3 HEVC MTK 对照 | HEVC | 与 V1 相同 | 开 | 关 | 关 |
| V4 修正路径 | 与对应基线相同 | 本次修正路径 | 与对应基线相同 | 相同 | 相同 |
| V5 YUV 候选 | H.264 | 硬件 YUV | 关 | 关 | 关 |

每次切换后实际重连并校验观测信息。连接失败或实际回退时保留失败结果，该轮不算请求路径成功。
HEVC 硬件 YUV、软件直出等扩展组按既有功能回归，不无条件增加整轮耗时。

### 8.4 执行预算与中断粒度

- 现有诊断 service 整轮期限为 25 分钟；新增计划生成前必须计算 warmup、measure、截图、释放和发布的最坏预算。
- 20 秒性能窗口仅用于性能 case，不能机械地把全部颜色 case 改成 20 秒导致超时。
- 固定素材颜色组、固定素材压力组与真机采样分别执行；保留现有两个来源入口，不将全部流程重新混为一个不可中断的大任务。
- 固定素材组超出期限时按版本化 groupId 拆为独立可导出的批次，每批拥有独立 runId，界面明确该批覆盖和未执行项。
- 不为凑时限缩短测量、丢弃失败 case 或把后续批次标已完成。
- 已完成 case 原子保存；跨连接/decoder generation 恢复后是新窗口，不能拼接成未中断测量。
- 生成新素材时记录体积增量。优先使用可重复循环的小型素材，不未经说明向 APK 追加多份大体积1080p视频。
- 新增 HEVC 动态素材须保留输入码流/帧率事实；现有 HEVC 静态10fps素材不被覆盖。

## 9. 失败、回退与中断

- 单项失败且资源确认释放：保存结果与错误，再执行独立下一项。
- 资源未确认释放、窗口覆盖/关闭、诊断 lease 丢失：停止该轮，保留已完成项。
- 文件落盘失败：UI 和报告显示失败，不把 0 字节路径作为有效发布。
- 只完成部分颜色或性能项：汇总 partial，逐项列出通过、失败、缺项。
- Surface 请求修正失败：恢复此前偏好，记录 fallbackReason；不静默启用软件路径冒充原路径成功。
- 优化导致颜色回归：单独回退优化提交，保留取证和比较能力。
- 性能未达门槛：保留实验标识，停止默认推广。
- native driver 不释放：不强行清空资源归属、不创建竞争 decoder；记录阻塞事实和恢复边界。
- 断线后开始的新窗口不能继承旧 decoder 的时序分布。

## 10. 构建、验证与产物

遵守 AGENTS.md 和 .github/workflows/android.yml。全部代码完成后，且用户允许恢复测试时，再执行：

```sh
./gradlew :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest   :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
```

补充构建 Flyme 普通完整包与 R8 完整包、核对 debug/release Manifest。实际安装包需要明确 -PflymeSystem=true。
使用 CI 的 JDK25、Android SDK 与声明 NDK；若本地通过外部 init script 使用已安装 NDK28.0，则必须记录与仓库28.2的差异，不能无声修改项目配置。

产物：
- 软件检查日志、JUnit 原始 XML、Lint 报告、环境清单。
- 两个完整 APK、各自 SHA-256、ABI 检查与签名身份检查。
- 每个 R8 APK 对应唯一 mapping.txt，不混用不同构建映射。
- 固定素材 case、配对像素样点、原始截图、阶段时序、路径事实。
- 真机每组 3 次有效窗口、条件比较、失败/缺项和回退记录。
- 最终验证矩阵及未解决项。
- Git 提交需独立审阅，不因本文自动提交或推送。

## 11. 需求到任务的追踪

| 需求 | 主任务 |
| --- | --- |
| G-EVIDENCE、2 节证据基线 | T01、T02 |
| R-C01、R-C04 | T03、T07 |
| R-C02 | T04、T05 |
| R-C03 | T06、T07、T16 |
| R-P01 | T08 |
| R-P02 | T09 |
| R-P03 | T10 |
| R-P04 | T11 |
| R-F01、R-F02 | T12 |
| R-I01 | T13 |
| R-E01 | T02、T04、T05、T14 |
| R-E02 | T14、T17 |
| R-L01、R-L02 | T00、T15、T18 |
| 7 节工程门槛 | T16、T17、T18、T19 |
| 9 节失败与回退 | T05、T14、T15、T19 |

## 12. 完成定义

本规格只在以下条件全部满足时标记完成：
- 任务清单所有必需项有实际产物、验证状态和证据路径。
- 颜色错误至少在声明支持的日常路径与目标输入范围内通过 L2/L3。
- 已修性能路径通过对应门槛；未达到的模式不被宣称日常可用或自动切换。
- 实际约 30 fps 的原因已定位并修复，或作为明确未完成项保留，不能删除该目标后宣称全任务完成。
- 断开死锁前置项完成独立验证。
- 无未解释的软件门禁失败，无凭空绿色状态。
- 版本、API28、arm64、Flyme 方控及原有连接功能保持约束。
- 最终说明明确区分：代码已完成、软件已验证、目标车机已验证、物理测量未覆盖。
