# 传输层七项审计核对与修复（2026-10-30）

基线：`be551988`。按用户要求先修Bug，双册PDF制作暂停。本文区分报告结论、可证实机制和实际修改；代码及用例完成前未执行测试。

| 编号 | 核对结论 | 处理与回归 |
|---|---|---|
| 1.1 ACK取反 | 原条件等价于control只为0或ACK。EAK已先进入专用分支，其payload不能作为会话数据再次投递；报告的建议同样排除EAK，不能修复报告所称问题 | 等价改写为显式0/ACK分类；覆盖EAK、ACK+EAK、带数据ACK及无ACK数据，不改变控制帧语义 |
| 1.2 NDP优先级 | Kotlin中缀调用比比较绑定更紧；若按报告方式解析，Int.and(Boolean)将无法编译。此项不是致命解析Bug | 为两侧掩码加括号以消除阅读歧义；覆盖低24位匹配、低位魔数破坏、原高字节兼容规则 |
| 1.3 U64负溢出 | 最后一次移位可能直接返回负值，但setup已有size<0拒绝，因此不构成已证实的大小校验绕过 | 将检查移到移位后，统一返回-1；覆盖符号位、全1、Long.MAX_VALUE、超过32位及素材大小上限的拒绝 |
| 2.1 ACK队列/计时 | minByOrNull不重排发送队列，原累计ACK前缀删除不会因deadline改变而乱序；建议的sequenceDistance==0只删精确ACK，反而会遗漏累计确认的旧包 | 保持累计ACK语义；另确认原先接受越窗/迟到ACK会丢掉未确认包，增加已发送窗口校验；发送窗口满时立即排队，避免协商255时多发成256导致序号距离归零；零窗口沿用一包兼容值，发送与ACK续发使用同一有效窗口。修复新发送、重复ACK或EAK之后选错重传期限的问题，统一取最早deadline；覆盖后退/超前ACK、协商确认、回绕及累计确认 |
| 2.2 重传次数 | 已确认：先递增再判相等，max=1在首次实际重传前就结束链路；timeout和EAK有重复实现 | 两条路径共用重传函数，实际重发前用>=判断已用预算，允许准确的N次重传；重传时刷新期限 |
| 2.3 USBMUX复制 | 已确认：append逐次拼接、消费时复制剩余数据，对碎片和合并帧产生重复复制 | 改head/tail缓冲，按需压缩、几何扩容；已交付payload保留独立副本，保持严格4字节尾随填充判断。覆盖逐字节最大帧、多帧合并、压缩后继续增长和输入所有权 |
| 2.4 封面驱逐 | 已确认：超4任务时旧任务被静默移除 | 驱逐前生成旧id Cancel，随后发送新id Start；旧分片忽略，同id替换不驱逐无关任务；不以空完成错误清除当前封面 |

## 范围与限制

- 仅修改shared传输实现及相关回归，不改车型/媒体路由/权限，不升版。
- USBMUX优化针对其控制流分片；CarPlay音视频另有NCM/IP路径，不据此宣称视频帧率或实车延迟改善。
- 不引入未经证实的EAK语义变更，不把负值已被外层拒绝描述为认证或安全绕过。
- 完整软件门禁已通过；真实iPhone/USB硬件重传仍需实车证据。

## 参考

- Kotlin官方语法及优先级：https://kotlinlang.org/grammar/
- 当前实现中的processFrame、sendExtendedAcknowledgement和handleExtendedAcknowledgement共同界定控制/会话分发；公开解析器也区分EAK载荷：https://gist.github.com/JJTech0130/78527c600f7b4d0a7aeb294eab06d8ba

## 本轮验证结果

- 首轮完整CI：common1501零失败；shared1376中7项失败。7项均来自UsbMuxIssue100RegressionTest.remainder直接把底层数组容量当作剩余有效数据，新可靠性18项已全部通过。原始记录保留于仓库上一级artifacts/diplay-architecture/20261030-transport-audit-be551988/。
- 集中修正旧辅助函数为head..head+bufferedBytes切片，未删改任何原始分片、填充、超时或完整载荷断言，生产实现未再修改。
- 第二轮完整命令退出码0：Flyme10、shared1376、common1501，共2887项，0失败/错误；shared原有1项主机端口环境跳过。
- lint、APK构建、debug/release Manifest、公开凭据边界及汇总脚本4项测试通过。证据：../artifacts/diplay-architecture/20261030-transport-audit-round2-be551988/。
- 后续arm64构建变更再次执行完整CI并通过；单测复用本轮刚完成的同实现结果，日志UP-TO-DATE事实保留。最终包的ABI/ELF核对见20261030-arm64-only-be551988归档。
- 两次只读复核修正了ACK窗口与零窗口续发问题，最终限定范围内无未解决的确定发现。未安装、未提交或推送本轮修改；说明书制作按用户优先级暂缓。
