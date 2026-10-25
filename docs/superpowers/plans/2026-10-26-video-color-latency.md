# 视频颜色与响应适配实施计划

目标：路线 A 保持软件解码＋Surface 直出的颜色链路，减少输出调度等待；路线 B 验证 MTK 原始 YUV 硬解＋应用颜色转换。两条路线独立记录，不能把回退算作实验成功。

约束：Android 9/API28；全部代码与测试编写完成后统一测试；不升版本、不提交、不改音频与车辆集成。

证据：车机默认 MTK AVC 队列到输出约11ms；Google AVC 直出约93ms；软件YUV约101ms，后者指标包含绘制，不能视为纯解码。现有全范围色块在多种 Surface 路径高光合并，有限范围色块保留灰阶，不能仅据此认定MTK本体错误。屏幕/视频/Surface均1920×1080。自动测试在首个真实主屏关闭阶段中断，缺少控制器/视频分别释放信息。

- [x] shared：复用已有独立输出线程处理软解直出，不改变软件选择与Surface颜色处理；分段累计送入等待、codec输出、YUV复制/绘制与Surface释放调用时间。
- [x] shared：MTK flexible YUV显式选型；失败仅一次退回原Surface路径并报告；不悄悄选软件作为硬解实验成功。保留现有软件YUV行为。
- [x] common：默认关闭的硬解颜色转换开关，覆盖软件实验而保留其偏好；下次连接生效；主屏及后台会话身份匹配。
- [x] common：追加测试用例保持旧索引；一键默认聚焦本次对照组，完整手动测试保留；显示真实路径与分段指标。
- [x] common：关闭主屏测试时并行发起控制器/视频关闭，使用有界完整等待；分别保存确认结果；未确认时保存当前结果并中止，不重叠资源。
- [x] tests：API28配置/回退、软解输出线程、颜色向量、会话身份、聚焦顺序、关闭超时及报告指标。
- [x] 统一验证：项目CI任务、聚焦回归、Lint、构建、签名、UI截图及代码审查；既有失败独立记录。
- [x] 交付APK与验证说明。实际MTK驱动取帧、物理色彩、滑动与静态清晰度必须注明未完成实车验收。

涉及文件：AndroidMediaSink/DiagnosticSoftwareDecoder/SoftwareYuvRenderer/LiveVideoMeter、新VideoPipelineTiming；CarPlayVideoOutput/AirPlayPersistence/DiPlayActivity/CarPlayHostActivity；ColorTestRun/ColorTestPlayer/ColorMainProbeRegistry/AutomaticVideoSuite/ColorTestPage/AutomaticColorMainProbe/VideoAdaptationRun及中文资源。

验收：颜色灰阶保留、滑动接近默认硬解、无黑屏花屏、自动对照完整结束或明确失败且Download有报告。模拟结果只证明代码契约，不证明车机效果。

实现与本地验证已完成；真实车机验收未执行。详见 /Users/jzhp/Downloads/test-rea/artifacts/diplay-video-color-20261026/README.md。
