# 目标车机颜色、跟手与稳定性实车验收方案 (N23 Vehicle Validation Plan)

## 1. 验收目标

在吉利星瑞 2026 昆仑版（Flyme Auto，Android 9 / API 28，arm64-v8a）实车座舱环境下，验证**同一实际视频路径**同时达成：
1. **颜色正确**：不发白、无高光/暗部截断（近黑 0~16、近白 235~255 层级分明，尤其是 Apple Maps、高德地图、网易云音乐等界面的深灰/灰白阴影）。
2. **触控跟手**：拖动地图、滑动应用列表流畅无拖泥带水，HID 触控排队与提交低延迟，无偶发卡顿丢帧。
3. **长期稳定**：至少 20 次重连/应用切换无崩溃、无内存泄漏、无 `BLOCKED_RELEASE` 卡死。

---

## 2. 测试配置矩阵 (按 Spec §3 & 任务 N23)

| 实验组别 | 管线实现 | 后端配置 | 颜色策略 | 目标观察点 |
| :--- | :--- | :--- | :--- | :--- |
| **基线对照组 1 (原有管线)** | `LEGACY` | 旧 Software YUV + 跟随视频 | 旧跟随视频 | 颜色正确，作为颜色基线对照 |
| **对照组 2 (原有强制 Limited)** | `LEGACY` | 旧 Software YUV + 强制 Limited | 旧强制 Limited | 仅用于验证偏色/截断现象，不可作为默认 |
| **目标生产组 (新管线 YUV)** | `INDEPENDENT` | `PLATFORM_YUV` (OMX.google.h264 或合格硬解) | `FULL_AUTO` (跟随源声明，Full SDR) | **重点验收项**：颜色与跟手同时达标 |
| **实验对比组 (新管线 Surface)** | `INDEPENDENT` | `PLATFORM_SURFACE` (c2.qti.avc.decoder Direct) | 硬件输出（Surface 呈现） | 验证跟手响应与硬件直出下的颜色对比 |

---

## 3. 验收细项与判定标准

### 3.1 颜色验收 (Color Fidelity)
- **静态测试图 (SMPTE / Grayscale Ramp)**：
  - 检查 0~16 阶黑阶和 235~255 阶白阶是否可区分。
  - 判定标准：黑位下潜扎实且细节保留，高光不泛白溢出。
- **动态应用实景 (Apple Maps / Music)**：
  - Apple Maps 深色模式路网阴影边缘清晰，无发灰浮白。
  - 浅色模式 UI 卡片阴影过渡自然。

### 3.2 跟手触控验收 (Touch Latency & Responsiveness)
- **手感判定**：
  - 在全屏地图界面快速拖动、缩放、双指旋转。
  - 地图图层应紧密跟随指尖，无肉眼可见的明显落后或回弹卡顿。
- **系统指标辅助**：
  - `VideoRuntimeMetrics` 记录的 `hid_submit_latency_us` 均值 < 2ms。
  - 帧投递排队延迟 `queue_latency_us` 稳定，无长时间堆积。

### 3.3 稳定性与释放恢复 (Stability & Lifecycle)
- **拔插/重连循环**：
  - 连续断开/连接 CarPlay 至少 20 次。
  - 每次断开后确认资源完全释放（`VideoReleaseTicket` 达到 `RELEASED` 状态，不残留旧 decoder 线程）。
  - 会话重连后新视频管线迅速启动（< 500ms 内出第一帧）。
- **长时间运行**：
  - 连续导航并播放音乐 30 分钟以上。
  - 观察进程 RSS 内存，无持续爬升趋势。

---

## 4. 实车验收执行记录

```json
{
  "validation_version": "1.0",
  "status": "NOT_RUN",
  "reason": "Host development sandbox does not have physical in-vehicle Geely Xingrui hardware connected. Implementation and gate tests (N00~N21) passed 100%. Vehicle validation requires physical car and phone connection.",
  "results": {
    "color_verdict": "NOT_RUN",
    "touch_verdict": "NOT_RUN",
    "stability_verdict": "NOT_RUN",
    "overall_production_ready": "PENDING_VEHICLE_TEST"
  }
}
```
