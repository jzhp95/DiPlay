# 视频运行时设备预检与采集验真协议 (N22 Device Preflight Protocol)

## 1. 预检目标与基本原则

本协议用于验证新视频管线在物理 Android 设备（Geely Xingrui 2026 Kunlun / Flyme Auto, Android 9 / API 28）上的采集链路完整性与测试入口真实性。

### 核心原则
1. **真实生产实例**：必须运行正式构建的 APK（debug 或 optimizedDebug），经真实 `SessionVideoRouter` 与 `VideoRuntimeCoordinator` 调度。
2. **禁止伪造证据**：缺失证据显式标为 `unavailable` 或 `partial`，严禁伪造不存在的 Raw YUV 或未测量的延迟。
3. **路径区分验收**：
   - **YUV 自绘路径 (`PLATFORM_YUV`)**：验证 MediaCodec (OMX.google.h264.decoder 或平台硬件解) → 可读 `Image` 平面 → EGL 纹理上传 → 生产渲染 → 上传前同帧 YUV 采样与 RGB 呈现 → 证据导出与回读。
   - **Surface 直出路径 (`PLATFORM_SURFACE`)**：验证 MediaCodec → `Surface` 直接呈现及系统可获取的帧提交/渲染证据；不强求经过应用 EGL，不伪造不可读取的 Raw YUV。
4. **设备数据安全**：单次测试需经用户明确授权，不卸载、不清除原有配置，测试完成后核对设置恢复。

---

## 2. 预检步骤与操作指引

### 阶段 1：构建产物准备与安装
- 确认安装经过 N21 统一门禁验证的 APK：
  - Debug APK: `mobile-debug.apk` (SHA256: `ff9c08a4aa2da1403ede284533461814e88c69a2861a0ba8865536790a31939f`)
  - 或 OptimizedDebug APK: `mobile-optimizedDebug.apk` (SHA256: `809b629ba99c249f588408b39190c4062ad04cba9c461573728fdc2ac49c6d12`)
- 命令：
  ```bash
  adb install -r mobile/build/outputs/apk/optimizedDebug/mobile-optimizedDebug.apk
  ```

### 阶段 2：UI 偏好项核实与模式选择
1. 打开 DiPlay → 偏好设置 → 高级媒体设置 (`ADVANCED_MEDIA`)。
2. 查看“视频管线”选项：
   - 默认值：原有管线 (`LEGACY`)
   - 切换目标：新视频管线 (`INDEPENDENT`)
3. 检查 UI 状态反馈：
   - 请求实现：`INDEPENDENT`
   - 当前会话实际运行：根据当前连接状态显示（未连接时提示需重连生效）。

### 阶段 3：路径专项预检 (A/B 独立验证)

#### 路径 A：YUV 自绘路径预检 (`PLATFORM_YUV`)
1. **注入/接入固定素材**（例如 SMPTE RP 219 彩条或标准灰阶梯尺流）：
   - 源流格式：H.264 / AVC 1080p60，Full range 声明。
2. **验证解码器创建与帧输出**：
   - 解码器组件：`OMX.google.h264.decoder`（或指定平台硬解）。
   - 帧租约状态：成功出队 `ImagePlane`，三平面 stride / pixelStride 正确解析。
3. **同帧颜色采样**：
   - `ColorEvidenceSampler` 在上传前采集关键 Patch（如灰阶阶梯 Y=16, 32, 64, 128, 235, 240, 255）。
   - 验证采样点数据为非零有效数值，记录采样时间开销。
4. **EGL 渲染与呈现**：
   - EGL 配置：RGB888，成功调用 `eglSwapBuffers`。
   - 验证 `VideoPresentationScheduler` 无死锁、无主线程阻塞。

#### 路径 B：Surface 直出路径预检 (`PLATFORM_SURFACE`)
1. **配置 Surface 模式**：
   - 解码器组件：`c2.qti.avc.decoder` / 硬件 Surface 后端。
2. **验证 Direct 呈现**：
   - MediaCodec 直接绑定 `SurfaceView`。
   - `FrameLease` 跟踪 buffer index 与 release/render 时间戳。
   - 采样证据标记：`raw_yuv_available = false`，不进行假采样。

### 阶段 4：诊断导出与文件完整性回读
1. 在 DiPlay 诊断页面触发“导出诊断报告”。
2. **验证导出文件**：
   - 设备本地存储 URI：非 0 字节，生成有效 ZIP。
   - 通过 `adb pull` 将导出包拉取到电脑：
     ```bash
     adb pull /sdcard/Android/data/com.shihab.diplay.flyme/files/diagnostics/... ./preflight-evidence/
     ```
3. **电脑端校验**：
   - 计算 ZIP 文件 SHA256 与字节数，核对与设备报告一致。
   - 解压 ZIP，检查 `diagnostics.json` 包含：
     - `implementation`: `INDEPENDENT`
     - `backend`: `PLATFORM_YUV` 或 `PLATFORM_SURFACE`
     - `runtime_metrics`: 有效的收到/送解/显示/丢弃计数与延迟分布。
     - 若为 `PLATFORM_YUV`，包含有效采样 points 数组。

---

## 3. 预检收据模板 (Preflight Receipt)

在实际连接物理车机或测试机运行后填入本收据：

```json
{
  "receipt_version": "1.0",
  "timestamp": "UNEXECUTED_PENDING_DEVICE",
  "device": {
    "model": "Geely Xingrui 2026 Kunlun (Flyme Auto)",
    "fingerprint": "UNEXECUTED",
    "android_api": 28,
    "abi": "arm64-v8a"
  },
  "runtime_identity": {
    "requested_implementation": "INDEPENDENT",
    "actual_implementation": "INDEPENDENT",
    "backend_kind": "PLATFORM_YUV",
    "decoder_component": "OMX.google.h264.decoder",
    "output_kind": "SURFACE_VIEW_EGL"
  },
  "color_evidence": {
    "raw_yuv_available": true,
    "points_count": 64,
    "sample_latency_us": 120,
    "patch_samples": [
      {"name": "black_clip_16", "y": 16, "u": 128, "v": 128},
      {"name": "white_clip_235", "y": 235, "u": 128, "v": 128}
    ]
  },
  "export_verification": {
    "local_uri": "UNEXECUTED",
    "file_bytes": 0,
    "sha256": "UNEXECUTED",
    "zip_integrity": "UNCHECKED",
    "computer_readback_verified": false
  },
  "preflight_status": "NOT_RUN"
}
```

> **注意**：当前环境为离线构建与软件验证沙箱，未连接物理车机设备，因此 N22 状态如实记录为 `NOT_RUN`。一旦连接测试机或车机并授权运行，按上述步骤收集有效回读文件。
