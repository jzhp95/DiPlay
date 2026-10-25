# Software YUV Output Implementation Plan

**Goal:** 增加默认关闭的可控软件YUV主屏输出，供目标车机比较颜色。
**Architecture:** 复用VideoDecoder的队列与SurfaceView；软件codec输出Image，Yuv420Frame打包平面，SoftwareYuvColor决定转换，SoftwareYuvRenderer负责线程内EGL/GLES。
**Tech Stack:** Kotlin、MediaCodec、Image、EGL14/GLES20、Android API28、JUnit/Robolectric。

## Global Constraints

- Android9/API28；无新依赖、第三方二进制或其他车型接口；不修改版本0.2.24/code43与媒体中心待办。
- 全部代码完成后统一运行验证；已有测试失败按归档基线比对。
- 原有模式/镜像/音频与显式颜色测试保持独立；设置只标记重连。

## Tasks

- [x] 编写SoftwareYuvColorTest、Yuv420FrameTest和RawSoftwareYuvDecoderTest，覆盖已知灰阶/颜色向量、crop与stride、实际无Surface配置与严格软件选择、释放。
- [x] 实现SoftwareYuvConfig/Color、Yuv420Frame、SoftwareYuvRenderer；Image三平面输入、动态矩阵范围、EGL输出与完整释放。
- [x] AndroidMediaSink主屏新增YUV配置，VideoDecoder严格选择软件flexible codec；Image路径不调用渲染式releaseOutputBuffer或setOutputSurface。
- [x] AirPlayPersistence与CarPlayVideoOutput/Host接入保存、模式优先级、诊断隔离、后台实际模式、重连重建和错误提示。
- [x] DiPlayActivity高级影音卡增加开关与两项选择；复用布局组件并补Host/UI与大字体截图用例。
- [x] 代码复核后统一运行AGENTS规定的全量测试、lint、assembleDebug；处理新增失败并记录已知基线；核对APK版本、签名、图像路径限制与交付物。

验证完成：shared1189无失败、common46专项通过；初轮99项历史失败无变化，1项测试回调等待已修正。真实GPU/实车颜色待用户测试。见docs/validation/2026-10-25-software-yuv-video.md。
