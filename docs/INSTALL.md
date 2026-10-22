# 星瑞昆仑 Flyme Auto 分支安装与连接

本分支面向吉利星瑞 2026 款昆仑的 Flyme Auto 车机。主应用最低要求 Android 9（API 28）；Android 8.1 及以下不支持安装。需要更高 API 的地图示例保持其独立下限。

1. 停车后使用本分支构建的 APK 安装到车机，构建入口见 [BUILD.md](BUILD.md)。上游发布包不代表本分支的车型、界面与方控适配。
2. 有线连接时，通过 USB 连接 iPhone 并授权本应用访问设备；Android9 的 USB 权限页面检测与已授权本地 ADB 兜底仍保留。
3. 无线连接可使用车机热点、Wi-Fi Direct 或同一局域网，按连接设置保存手机和网络信息。Android9 的 Wi-Fi Direct 仍使用该版本所需的组管理路径，详见 [ANDROID9_WIFI_DIRECT.md](ANDROID9_WIFI_DIRECT.md)。
4. 按实际使用授予定位、麦克风与设备连接权限。Android9 导出公共下载目录仍可能需要存储权限；应用私有目录、查看和分享报告入口保留。
5. 方控以现有 Flyme EAS 适配为准，避免同时运行其他投屏应用争用媒体焦点。

单元测试与构建成功不能替代 Android9 实车的连接、通话、音视频和方向盘验证。当前支持范围见 [项目说明](../README.md)，历史上游车型与旧 Android 版本记录不代表本分支支持范围。
