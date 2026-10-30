# T00：当前工作树与验证基线

日期：2026-10-28。对应 [系统架构改造计划](2026-10-28-system-architecture-remediation.md) 的第一项 T00。

## 范围与状态

T00 固定的是用户当前正在使用的定制分支及其未提交工作，不把 HEAD 当作完整源码。
本轮另按用户明确要求将基本版本从 0.2.25／44 升为 0.2.26／45。
T01—T24 尚未实施；本轮不改变连接、解码、音频或诊断调度逻辑。

- 正式包名：com.shihab.diplay.flyme
- 测试包名：com.shihab.diplay.flyme.test
- 测试版本：0.2.26-test／45
- 目标：2026 星瑞昆仑、Flyme Auto、Android 9／API 28
- 基线 HEAD：ea408540e41aec987f879b624c7c7ac63c32be8e
- 完整产物目录：/Users/jzhp/Downloads/test-rea/artifacts/diplay-architecture/20261028-151713-T00-ea408540
- 阅读入口：[基线归档 README](../../../../artifacts/diplay-architecture/20261028-151713-T00-ea408540/README.md)

## 已冻结的内容

1. 升版前公开源码、当前未提交文本代码及编译使用的新增视频／CSD／JSON 素材。
2. tracked diff、未跟踪文件清单、删除文件清单、每个文件 SHA-256、文件模式和源快照 ZIP。
3. 当前完整 JUnit XML，以及历史 99 项失败、最后 180 项专项、shared 最后完整结果的独立记录。
4. Gradle、JDK、SDK／NDK 配置与本机实际版本；只记录构建需要的环境信息，不导出所有环境变量。
5. 升版后用于编译的源码指纹，以区别“旧版本冻结基线”和“新版本候选”。

私有签名、运行认证资产、.git、Gradle 缓存及不参与构建的外部图标参考目录不复制；排除项在 source-manifest.json 中列出。
构建需要的本地凭证继续由原仓库受忽略目录提供，不能把不含凭证的源码快照叫成可独立完成认证的安装包。

## 测试基线必须分开读

| 证据 | 实际状态 | 解释 |
|---|---|---|
| 此次冻结前最近全量 | common 1480／100 失败；shared 1239／0 失败／1 跳过；Flyme 10／0 失败 | 完整命令退出码 1，不能写全部通过 |
| 较早全量及失败集合 | common 1480／99 失败 | 原 99 项仍同名同异常类型 |
| 较早最后专项 | common 180 项通过 | 只代表受影响范围，不是第二次完整 common |
| 较早最后 shared | 1239 项，零失败、1 跳过 | 与该记录对应的完整 shared 结果 |
| 包名／构建 | 两种实际合并 Manifest 通过；Debug APK 和 Lint 通过 | release 仅校验 Manifest，未生成 release APK |

新增但未定位的失败为：
com.shilapi.xcertplay.reportshare.ReportShareServerTest::theTypedAddressOpensThePageThatAsksForTheCode
期望 HTTP 200，实际 HTTP 401，ReportShareServerTest.kt:195。
T00 记录该问题，不将它改成“历史已知无影响”；未做根因修复，后续任务不得覆盖首次 XML。

本轮升版验证记录在归档的 validation/ 内；它与冻结前报告分开，具体数值以 validation-results.json 为准。

## 行为保留清单

路径均相对仓库。测试存在仅表示有覆盖入口，不代表真实车机行为已经完成验收。

| ID | 必须保留 | 现有回归入口 | 仍需实车确认 |
|---|---|---|---|
| B01 | CH341／MFi 和 USB 所有权，不能重复抢占硬件 | shared/src/test/java/com/shilapi/xcertplay/transport/Ch341UsbOwnershipTest.kt；mfi/ 下测试 | 热插拔、真实认证；用户已确认 CH341 有效 |
| B02 | AirPlay／iAP2 协议及解析边界 | shared/src/test/java/com/shilapi/xcertplay/airplay/RtspMessageLimitsTest.kt、BplistCodecBoundsTest.kt | 真实 iPhone 完整连接 |
| B03 | 流 registration owner，旧会话不能停新输出 | shared/src/test/java/com/shilapi/xcertplay/airplay/CarPlayVideoStreamOwnershipTest.kt、AudioSessionCleanupTest.kt | 重连与多流交叠 |
| B04 | Surface 分离、镜像退休和真实释放屏障 | shared/src/test/java/com/shilapi/xcertplay/media/AndroidMediaSinkCodecDetachTest.kt、VideoSurfaceHandoffTest.kt | 目标硬件 codec 挂起／恢复 |
| B05 | 软件直出、YUV、MTK 回退；不顺便改参数 | common/src/test/java/com/shilapi/xcertplay/CarPlayVideoSurfaceTest.kt；shared/src/test/java/com/shilapi/xcertplay/media/VideoColorMetadataTest.kt | 浅色近白灰阶和运动时延 |
| B06 | 显式暂停保护及切歌续播 | shared/src/test/java/com/shilapi/xcertplay/media/MediaCommandQueueTest.kt | 真实歌曲状态和连续切歌 |
| B07 | CSeq 成功写入后提交 | shared/src/test/java/com/shilapi/xcertplay/airplay/AirPlayEventSequenceTest.kt | 真实事件通道异常恢复 |
| B08 | 本地浮层优先关闭、CarPlay 返回与双击桌面 | common/src/test/java/com/shilapi/xcertplay/ProjectionBackDispatcherTest.kt | 实体返回键 |
| B09 | Flyme 唯一入口、会话身份、焦点与去重、EAS 状态上报 | common/src/test/java/com/shilapi/xcertplay/EasPlayStateChannelTest.kt；integrations/flyme-eas/src/test | 系统媒体区实际显示与方控 |
| B10 | 标准片／同源回放／生产观测分开，诊断租约互斥 | common/src/test/java/com/shilapi/xcertplay/DecoderDiagnosticsEvidenceTest.kt；shared/src/test/java/com/shilapi/xcertplay/media/VideoDiagnosticLeaseTest.kt | 目标解码／显示链路 |
| B11 | Download 文件读回校验、失败重导出 | common/src/test/java/com/shilapi/xcertplay/BinaryReportDownloadsTest.kt、VideoReportPublisherTest.kt | 文件管理器能找到实际 ZIP |
| B12 | 原车 HFP、导航与通话音频策略 | shared/src/test/java/com/shilapi/xcertplay/media/NativeHfpSinkTest.kt、NativeHfpRingPolicyTest.kt；common/src/test/java/com/shilapi/xcertplay/NativeHfpSettingsTest.kt | 原车电话链路 |
| B13 | 前后台保留 decoder，不因页面重建重复连接 | common/src/test/java/com/shilapi/xcertplay/ProjectionVideoLifecycleTest.kt、BackgroundSessionReconnectTest.kt | 倒车／其他应用覆盖后恢复 |

## 验收与回退

- source-baseline.zip 的文件逐个与 source-manifest.json 对照，验证大小、SHA-256、ZIP CRC。
- 删除和未跟踪文件分别列明，不隐藏在一个总 diff 中。
- baseline-tests.json 区分全量、专项、缓存任务与未执行；保留失败标识和异常类型。
- environment.json 区分声明 NDK 28.2.13676358 与本机实际 28.0.13004108。
- 升版后的候选通过真实 APK／Manifest 检查确认版本和包名；不能仅看 Gradle 文本。
- 回退本轮版本只需恢复 mobile/build.gradle.kts 的 0.2.25／44，并恢复本轮修改的当前状态文档；不可 git reset 清除其他未提交工作。
- T00 源快照用于对照和受控恢复；不自动安装、降级或卸载应用。

## 本轮统一验证与交付结果

- T00 已完成，源码 ZIP 的 1358 项逐个通过大小／SHA／CRC 校验；独立复核确认无意外业务源码修改。
- 版本已升级为 0.2.26／45，测试包 0.2.26-test。正式和测试包名分别为 com.shihab.diplay.flyme、com.shihab.diplay.flyme.test。
- shared：1239 项，零失败、1 跳过；flyme-eas：10 项零失败。部分任务 UP-TO-DATE，执行状态见 JSON 与原始日志。
- 完整 common：1480 项、99 失败；与较早99历史失败同名同异常类型，无新增。冻结前那项 HTTP401 本轮未复现，未修改相关代码，根因仍未定位。
- Debug／Standalone APK、Lint（0 error）与两种 Manifest 通过；全量命令仍因历史失败退出1。未构建正式 release APK，未安装目标车机。
- [本轮测试包](../../../../DiPlay-v0.2.26-Flyme-test-20261028.apk)；[机器可读验证结果](../../../../artifacts/diplay-architecture/20261028-151713-T00-ea408540/validation/validation-results.json)。
- T01 尚未开始，未提交。下一项为异步诊断存储边界。
