# 软件YUV自绘实验输出

日期：2026-10-25；版本0.2.24-hud-test/code43。用户要求独立实验开关，用于后续上车比较颜色。本次未升版本、未提交Git，未修改媒体中心逻辑及已记录TODO。

## 实现

默认关闭的“软件YUV自绘（实验性）”位于高级→视频与音频。只对主屏使用系统软件MediaCodec，显式要求YUV420Flexible与无codec Surface。读取Image三平面，尊重crop、rowStride、pixelStride与buffer.position，复用平面内存，再由应用GLES2 shader执行全/有限范围及BT709/601转换，通过EGL向现有SurfaceView输出。未使用ZQ的库、shader或授权材料。

范围与矩阵可以自动或手动选择。自动优先明确SPS，其次decoder元数据；未知时明确记录BT709有限范围假设，不冒称来源已确认。拒绝已声明HDR/宽色域/不支持矩阵/非8bit YUV。Image及codec buffer在finally释放，只有实际EGL swap成功才标记首帧。detach强制释放，换Surface重建，EGL均由原解码线程拥有，不释放框架Surface。

保留旧软件直出偏好，新模式优先；关闭后恢复。保存只markReconnectNeeded，Activity重建保留待重连旧sink的实际模式；新会话才应用期望设置。显式颜色诊断配置不受日常开关污染，current反映实际新模式。运行错误给出提示，不静默硬解或回到旧输出。

## 验证

全部任务代码完成后统一运行AGENTS要求的flyme-eas/shared/common单测、mobile lint与assembleDebug（本机NDK28.2不完整，沿用临时脚本使用完整安装的28.0，项目NDK配置未改）。

- flyme-eas：10项通过。
- 初轮shared：1189项，0失败，1原有跳过。
- 初轮common：1357项，100失败。其中99项历史失败的名称和message与0.2.24前版完全一致；新增1项来自UI用例未等待AlertDialog positive button保存回调。不是产品设置保存丢失。
- 集中修正UI用例等待，并显式将shader的三个采样器设为mediump以保留滤波精度；未额外降低varying坐标精度。
- 最终统一执行shared全量1189项与common受影响的46项专项、lint和assembleDebug，全部通过（shared仍1项原有跳过）。未重新运行无新改动的全部common历史失败。
- 专项覆盖颜色数学、边界裁剪、实际无Surface配置、主屏/镜像隔离、Host模式优先级、保留连接、旧直出、诊断、设置布局与中文资源。
- API28大字体窄/宽屏及RTL系统环境下的开关、选择行截图已检查；应用保持中文布局。
- Lint：0错误、3项原有警告；签名验证通过，证书与上一测试版一致。
- 独立代码复核已完成，无剩余已确认新增问题。

尚未运行真实API28 GPU或目标车机，测试没有证明物理颜色已正确。无硬件支持时提供显式不可用，保留上机回退开关。

## 交付

[APK](/Users/jzhp/Downloads/test-rea/DiPlay-v0.2.24-YUV自绘测试.apk)。
[验证、截图与使用说明](/Users/jzhp/Downloads/test-rea/artifacts/diplay-software-yuv-20261025/README.md)。

接口依据：[Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)、[Image.Plane](https://developer.android.com/reference/android/media/Image.Plane)、[GLSL ES 1.00](https://www.khronos.org/files/opengles_shading_language.pdf)。这些文档支持接口与精度语义，不能替代目标设备实测。
