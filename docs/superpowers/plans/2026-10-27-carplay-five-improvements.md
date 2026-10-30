# 五项改进实施计划

用户已授权直接实施；全部修改完成后统一测试，版本不变，不提交。

- [x] shared/media/MediaCommandQueue.kt：独立串行调度、会话核对、切歌续播与取消。
- [x] orchestration/CarPlayController.kt：接入新队列并在close释放；保留现有方控入口。
- [x] airplay/AirPlaySession.kt：sendMedia返回实际写入结果，CSeq成功提交。
- [x] common/ProjectionBackDispatcher.kt及Host：保留本地关闭优先、投屏返回/双击桌面。
- [x] Host：复用现有离屏消费者扩展各Surface输出、500ms背景交接、旧位图/关键帧恢复。
- [x] 新增队列、返回键、事件发送回归，扩展真实codec交接回归到API28及普通输出。
- [x] 统一CI、独立审查、集中修正后针对验证；保存报告和普通/Flyme包。

验证：shared1239项无失败（skip1），common专项180项通过；全量历史99失败未增加。普通/Flyme构建和lint通过。完整记录：[交付与验证](../../../../artifacts/diplay-five-improvements-20261027/README.md)。
