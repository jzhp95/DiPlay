# 软件YUV自绘实验开关

用户要求实现可控软件视频输出，用于后续上车比较颜色。先使用Android系统软件MediaCodec，配置无解码Surface的YUV420Flexible输出，读取getOutputImage的三平面，再由应用GLES2 shader转换到RGB，通过EGL绘制到现有主屏SurfaceView。无需OpenH264库或ZQ二进制，不添加依赖或独立视频View。

默认关闭；高级→视频与音频提供独立开关，仅主屏使用。开启时覆盖软件直出、平滑视频、纹理画面调整及硬件调优，保留全部原有偏好；关闭恢复。两个附属选择：色彩范围（自动/全/有限）、矩阵（自动/BT709/BT601）。选择仅改变RGB转换，不伪造解码器输入元数据。自动优先明确SPS，其次解码器输出；未知时明确记录BT709有限范围假设。SDR8bit范围外（HDR/宽色域/不支持矩阵/无YUV Image）明确报不可用，不静默硬解或回到旧输出冒充成功。

解码线程拥有codec、Image和EGL；每帧finally关闭Image并releaseOutputBuffer(false)，仅eglSwap成功后计入rendered/首帧。按crop/rowStride/pixelStride与buffer.position复制，可复用平面缓冲。detach强制释放而非setOutputSurface/parking，changeSurface重建解码与EGL，退出不释放框架Surface。保留现有队列、关键帧恢复、触控坐标与截图逻辑。

保存仅markReconnectNeeded，不自动断开。Activity重建按待重连旧sink的实际模式保留连接；真正新会话再应用期望模式。显式颜色诊断项独立于日常模式，current包含新模式。报告记录有效模式、选择值、色彩来源、解码输出和YUV失败；不把自动假设或离线测试当成实车正确颜色。

全部任务代码完成后统一测试：平面布局/裁剪/边界、灰阶与颜色参考向量、SDR限制、软件能力与无Surface实际configure、主屏/镜像隔离、Host设置和重建、待重连、API28大字体宽窄/RTL截图及AGENTS CI。无可用API28真实GPU环境时明确渲染实车未验证；提供可安装测试APK，不升0.2.24/code43，不提交代码或修改媒体中心TODO。
