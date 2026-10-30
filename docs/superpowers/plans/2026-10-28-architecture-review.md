# 系统架构实施静态复核（2026-10-29）

本记录对应工作区 T01—T20 与 M01—M05 实施代码。静态检查不能证明运行正确；本段为首次统一执行前的记录；2026-10-30软件门禁与后续复核结论见文末，实车仍待验。

| 边界 | 实施与静态核对 | 验证入口 |
|---|---|---|
| 诊断存储 | 有界单写入器；不可变修订、原子指针、恢复编号下限；状态线程只持内存与序列化快照；迟到回执不推进旧状态 | DiagnosticRunStoreTest、DiagnosticCoordinatorTest、DiagnosticSourceIndexTest |
| 诊断归属 | 单一 Service 自动入口；旧运行只读恢复；租约覆盖释放超时，无法确认时继续保留 | DiagnosticLeaseOwnershipTest、DecoderDiagnosticsLifecycleTest、MainProbeTeardownTest |
| 会话归属 | Runtime／Factory 在 Service；Factory 只捕获 applicationContext；Client 与 UI 订阅可关闭；页面不保存控制器／媒体输出副本 | SessionRuntimeStateTest、SessionAuthenticationReleaseTest、WirelessStartupRecoveryTest |
| 退出与恢复 | 单一退休资源；6.5 秒状态截止独立于原生关闭线程；重复停止有回执；显式停止取消续接；认证只有一次新连接重试；恢复连接取消旧定时器 | SessionRuntimeStateTest、WirelessStartupRecoveryTest、SmoothVideoHostLifecycleTest |
| Surface | 绑定核对会话与 UI 所有者；应用纹理异步确认后释放；SurfaceHolder 保留原有有界交接；迟到回执按实际请求身份丢弃 | ProjectionVideoLifecycleTest、AndroidMediaSinkCodecDetachTest、VideoOutputThreadTest |
| 视频与音频 | Sink 为协议外观；Video／Audio 各拥有资源注册表；音频退休参与重新创建屏障；请求方案与实际观测区分 | VideoPlanResolverTest、NativeHfpSinkTest、TelephonyMicrophoneTest、NavigationAudioGainTest、原有隔离套件 |
| 输入队列 | 有界单队列；只合并同手势、同指针集的相邻 MOVE；可靠边界溢出关闭当前连接；接受与实际写入分别记录 | InputCommandQueueTest、AirPlayTouchSlotsTest、InputTimingEvidenceTest |
| 证据 | 有界环形事件；时钟、原会话身份、采集 runId 分开；白名单字段；未知硬件路径不推断 | RuntimeEvidenceTest、DecoderDiagnosticsEvidenceTest |
| 设置与页面 | 沿用已有设置键／默认值；页面重建使用活动输出方案；显式保存才重新配置；旧页面预算与握手字段移除 | CarPlayHostSettingsTest、CarPlayHostDisplaySizeTest、SoftwareYuvHostTest、SoftwareDirectVideoOutputTest |
| 上游移植 | 通用协议／网络／安全补丁拆选；CH341、本地输入所有权与 API 28 降级保留；未恢复车型专属通路 | PCM MTU、Peer、WiredStartAgain、控制帧边界和 USB 安全测试 |

已集中修正：孤立修订号、销毁后迟到继续、被动租约泄漏、停止／重配竞态、诊断等待不续接、权限检查异常、重复认证通知、普通连接恢复后过期重连、旧视频播放器回调、主屏诊断遗漏音频释放、异步分离提前释放 SurfaceTexture。

静态搜索确认 Host 不再 new Controller／Sink，旧自动实现无执行入口；git diff --check 无格式错误。版本维持 0.2.26／45。本机 NDK 28.2 目录为空，本轮用归档 init-script 选择已安装 28.0，不修改项目声明。

限制：SurfaceHolder 的框架资源不能无限延长销毁回调；超过既有有界交接预算时继续记录未确认、保留退休资源并禁止替换，不能宣称原生已释放。注册表计数不是原生句柄完整清单；触摸物理延迟没有实测值。旧测试夹具迁移和历史 99 项失败的最终分类以本轮结果为准。T23 目标实车验证仍未执行。

## 2026-10-30 复核闭环

后续只读复核发现并修正了三类窗口配置问题：debounce取消误拆健康连接、finally误批准旧窗口快照、菜单关闭时遗漏待定尺寸（含防抖尚未结束的时序）。UI命令绑定owner/ownerGeneration/stopGeneration；仅经明确配置判断后批准原请求，准备失败继续等待。对应Host窗口与Runtime回归已进入最终common全量。

完整软件门禁通过：Flyme10/shared1350/common1501，零失败，shared一项主机端口环境跳过；lint、APK、签名与Manifest通过。独立证据见同级实施计划第20节和仓库上一级artifacts/diplay-architecture/20261030-T22-final-full2/。诊断存储/Looper的测试隔离不更改生产资源屏障。实车、物理延迟与颜色根因结论继续待证。
