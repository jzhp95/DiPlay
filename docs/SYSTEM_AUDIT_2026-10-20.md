# 当前项目系统审计：六项缺陷与复现证据

日期：2026-10-20。代码基线 `aaeb7b821f67bb8f5061aa62522a8be159f52b00`。本轮审查覆盖 shared 的网络/USB/媒体/会话所有权、common 的后台接管与权限/界面状态、mobile/automotive 入口，以及 Flyme EAS 和 home/maphost 集成的关键路径。采用代码追踪、临时定向探针和独立只读复核；这是基于风险的系统审查，不构成逐行无缺陷保证。

结论：确认五项业务缺陷及一项 plist 解析校验缺陷。旧的149项失败不能等同于149个产品bug；失败集合不增加也不代表没有未覆盖的缺陷。本轮没有修改业务代码，新增探针仅放在临时目录并归档到构建报告。

## 1. P1：旧视频会话会关闭新会话的同类型输出

位置：[CarPlayMediaEngine.kt](../shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt#L568)，关联 [AndroidMediaSink.kt](../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L494)。

触发：同一 media engine 内，旧会话A与新会话B先后建立同类型视频流，B开始工作后A关闭或TEARDOWN。VpnService允许并存的控制连接，新的SETUP不先删除旧registration，因此该交叠可达。

原因：流注册按 `(session,type)` 管理，sink却按type共享decoder。旧会话关闭无条件发送 `(type,false)`，清理了B的decoder、codec配置和恢复处理器；各自session的currentScreenListener校验不能隔离跨session所有权。

实测：`AuditVideoOwnershipProbeTest.auditOldVideoSessionMustNotClearTheNewVideoConfiguration` 先确认B的配置存在，关闭A后收到 `[110]` inactive事件，B的配置消失。原有两项音频流隔离测试均通过。该状态可导致重连后黑屏/恢复失效；没有测量黑屏持续时间。

修复方向：视频流像音频流一样维护每个输出type的当前registration owner，并让关闭、帧交付、配置和恢复回调共享所有权校验。

## 2. P2：镜像解码器仍运行时，释放完成通知已返回

位置：[AndroidMediaSink.kt](../shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L636)，释放判定见同文件380–405行；消费者为 [CarPlayHostActivity.kt](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L6001)。

触发：地图/Launcher镜像decoder正在无法立即退出的codec调用中，此时关闭sink或替换镜像Surface。

原因：主decoder会进入closingVideoDecoders等待退出，镜像decoder仅异步close后从map清除。awaitVideoReleased/whenVideoReleased只检查主decoder集合。Host因此可能提前移除仍存活的retiring sink，MapMirrors可把同一Surface交给新sink。

实测：复用现有GateCodec阻塞fixture，镜像worker存活且仍被阻塞时，得到 `released=true; notified=true; mirror alive=true`。探针确认完成通知违反实际释放契约；尚未在硬件复现native崩溃、永久泄漏或具体画面异常。

修复方向：主/镜像decoder统一退休跟踪、退出回调与Surface分离屏障，完成通知以真实worker退出为准。

## 3. P2：后台接管遗漏活动 AirPlaySession

位置：[CarPlayHostActivity.kt](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L5404)，关联 [CarPlayController.attachUi](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L458)。

触发：有存活CarPlay连接时，Host因字体配置变化或系统重建而创建新实例并接管；使用默认黑角配置0。

原因：attachUi仅重放status，不重放session-active；接管逻辑仅在IPHONE_MASK模式且已有mask时恢复activeAirPlaySession。普通模式下视频可继续播放，但新Host把活动session保留为null。

实测：真实Activity.create接管已有controller/sink，新Host在默认配置下活动会话字段仍为null；原有三项后台接管/重连对照测试均通过，说明原套件未覆盖该字段恢复。syncAirPlayDarkMode在session=null时直接返回，部分前台Flyme语音键/方控fallback也据此拒绝事件。不是整个后台接管失败，也不代表独立无障碍方控服务全部失效。

修复方向：独立于遮罩配置恢复当前会话身份，或由controller在attachUi时安全重放活动会话及必要状态。

## 4. P2：在系统设置授予麦克风后，同实例仍使用旧拒绝状态

位置：[CarPlayHostActivity.onResume](../common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L1178)，消费者为同文件4908、5148行。

触发：首次拒绝麦克风，随后到系统应用权限授予，再返回同一个Host；之后发生断线或显示配置引起的重连。

原因：microphoneAvailable只在onCreate和自己的权限请求回调赋值；onResume重查定位和无线权限，却不重查麦克风。系统设置授权不触发应用自己的launcher回调，新栈仍读取旧false构造AirPlayConfig和media engine。

实测：模拟真实pause/stop→系统grant→restart/start/resume，checkSelfPermission已经GRANTED，但microphoneAvailable仍为false。原来的“拒绝麦克风仍允许重连”用例也通过，两者是不同场景。

修复方向：恢复页面及准备新连接时读取真实授权；明确麦克风变更对现有engine与下一次连接的生效策略，不能只刷新显示字段。

## 5. P2：通知权限结果恢复后，USB请求变成无线

位置：[DiPlayActivity.kt](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt#L193)，状态初始化158行、保存345行、权限请求3148行。

触发：Android13+首次选择USB连接，通知权限请求尚未结束时Activity因字体配置或进程恢复而重建，随后交付原权限结果。普通旋转已由Manifest处理，不能称旋转必现。

原因：待处理的notificationTransport为普通成员，默认true；请求USB时改成false，但没有保存和恢复。权限结果回调调用connect(notificationTransport)，恢复实例便使用true并保存无线模式。

实测：真实USB权限请求→保存state→新实例create(state)→用原requestCode调用真实onRequestPermissionsResult；最终 `restored notification transport=true; persisted wireless=true`，USB保持断言失败。

修复方向：保存/恢复待处理连接意图，结果回调使用原请求的transport，覆盖授权和拒绝两种返回。

## 6. P2：二进制plist缺少结构校验及解析资源预算

位置：[BplistCodec.kt](../shared/src/main/java/com/shilapi/xcertplay/airplay/BplistCodec.kt#L18)，数组/字典递归在166–183行；入口 [AirPlaySession.handleSetup](../shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt#L641)。

原因：偏移宽度、引用宽度、对象数量、表范围和顶层索引没有先做一致性验证，直接按声明数量分配LongArray；对象递归没有深度、环引用及累计展开预算。RTSP请求体长度限额不能约束这种声明分配或对象图展开。

实测边界：安全的本地测试把有效plist的offset宽度改为无效的0，decode未拒绝且返回String。大量声明分配、循环引用和Error超出Exception捕获范围的后果有直接源码依据，但本轮没有实际触发OOM、栈溢出或发送远端报文，不能把格式测试称作已复现资源耗尽。

修复方向：先验证整数范围、宽度、表边界、引用索引，再分配；增加对象数量、深度、循环引用与累计展开预算，对畸形输入返回受控解析错误。

## 旧149项失败的分类与限制

沿用当前源码基线的完整CI快照：shared1553项中13失败/1跳过，common1433项中136失败；home8、maphost1、Flyme EAS10全部通过。mobile/home/maphost构建成功，Lint均0 error。快照见 `build/reports/flyme-system-validation/full-ci/`，本轮没有修改业务代码，因此没有为了分析再次执行整套构建。

已经直接确认的陈旧预期至少包括common25项：14项反射调用已删除的renderBydAdbControls；6项VPN授权失败用例的权限状态断言已通过，仅英语提示匹配失败；5项英文/日文/繁体中文文案预期与简体中文策略冲突。还有BYD旧开关/legacy字段读取测试依赖已关闭路径，不能把等待不到旧分支或测试snapshot()!!为空直接解释为当前并发bug。

网络方面，不调用应用网络代码的裸JDK IPv4回环测试通过，IPv6回环accept超时；原有双栈监听测试也失败。这支持本机IPv6环境依赖的判断，不能推出Android车机网络实现必然有同样缺陷。

149项的逐项索引见 `build/reports/current-system-audit-2026-10-20/historical-failure-triage.json`：25项陈旧API/文案、2项本机IPv6依赖、其余122项保留为未逐项完成根因复核。未把所有剩余项认定为过时测试，也未禁用或放宽它们制造全绿。

## 复现记录与审查边界

新增临时探针共14项：6项缺陷契约检查失败，5项原有业务对照及1项IPv4基础检查通过，另2项为原双栈测试和裸IPv6测试的环境相关失败。故总计6通过、8失败；这些是审计负向复现，未混入旧149项统计。

探针使用与现有测试相同的Gradle/Robolectric环境，通过临时init script为compileDebugUnitTestKotlin追加外部测试源；没有修改仓库生产或测试源码。第一轮未正确接入测试源的运行不计为复现，以上均来自实际编译并运行后的XML。证据与可复跑源码归档在 `build/reports/current-system-audit-2026-10-20/`，包含first-results、additional-results、probe-summary.json、临时source及接入脚本；运行时外部目录为 `/tmp/diplay-system-audit-20261019/`。

所有六项经过第二位审查者独立核对，确认了生产可达性与证据边界。视频问题优先修复，其次补齐后台/权限状态恢复和解析预算；每项修复应单独提交并加入正式回归。硬件codec冲突、真实车机重建与极限资源后果仍需在相应环境验证。
