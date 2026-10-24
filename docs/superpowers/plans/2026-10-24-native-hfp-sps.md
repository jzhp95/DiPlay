# HFP 与 SPS 实施计划
使用 writing-plans 技能整理并在当前会话执行。
- [x] 先增加 API28 SPS 提示、空／异常输入、手动覆盖和 HFP 铃声流生命周期回归测试，运行确认缺失行为。
- [x] shared 添加独立 NativeHfpRingPolicy；AndroidMediaSink 在 start/RTP/stop/close 接入，原车响铃后关闭同类旧 renderer。common 增加持久化设置、重新连接提示及 host 参数。
- [x] VideoColorOverride 增加 fromSps；configureDecoder 解析一次输入 CSD，buildFormat 将已映射提示写入格式并记录请求。ColorProbeEvidence 共用映射。
- [x] 在现有项目末尾追加 8 个 SPS 测试片对比及 2 个正式主屏 Profile，保持旧结果索引；证据导出记录 fromSps。
- [x] versionName=0.2.23 / versionCode=42；运行 focused tests、项目 CI、UI 截图，审查 diff，输出 APK 和验证记录。

最终统一验证：shared1176/0失败，common1331/99项原有失败（名字与消息一致），flyme10/0失败，lint0error3warning，APK构建成功。实车效果待验证。按用户新增准则，后续全部代码完成再统一测试。
