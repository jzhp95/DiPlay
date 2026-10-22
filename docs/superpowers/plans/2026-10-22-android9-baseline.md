# Android 9 兼容下限清理

目标：以 API 28 为主应用及共享代码的安装和运行下限；保留 Android 9 的网络、USB 授权、存储权限、Flyme 方控与会话行为。

1. 将 mobile、common、shared、flyme-eas 和 NDK APP_PLATFORM 下限统一为 28；需要 API30 的地图示例保留现有下限。
2. 删除 Android7 USB 完成线程与 Android8 预先限制队列大小的专用路径，保留 Android9 队列拒绝后的缓存／降级重试。
3. 使用平台音频焦点、通知渠道、定位状态、叠加窗口、Base64 和 java.time；保留 Android9 与更高版本之间的必要分支。
4. 删除旧版本专属测试，把通用行为覆盖迁移到 API28，增加不支持旧版本的边界断言。
5. 最后统一运行单元测试、Lint、三个 Debug APK 构建及安装下限检查；与 f5b6a07b 的历史失败记录比较。保留 Android9 实车验证限制。
