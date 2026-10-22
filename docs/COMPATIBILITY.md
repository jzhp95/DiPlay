# 兼容范围

本分支仅适配吉利星瑞 2026 款昆仑的 Flyme Auto，运行基准和最低安装版本均为 Android 9（API 28）。其他车型不作为已支持车型。

- 应用入口：`mobile`；共用模块为 `common`、`shared` 和 `flyme-eas`。
- 厂商方控：仅使用现有 Flyme Auto EAS，保留会话归属、焦点、通话判断及按键去重。
- 连接方式：USB、车机热点、Wi-Fi Direct、同一局域网。具体能力取决于车机端口、权限及固件。
- Android 9 的 Wi-Fi Direct 无法读取实际协商频率，诊断中的请求频率不能视为实测频率，详见 [Android 9 Wi-Fi Direct](ANDROID9_WIFI_DIRECT.md)。
- 外部桌面地图嵌入服务需要 Android 11；不适用于目标车机 Android 9。主应用的其他地图能力单独管理。
- 当前仍有麦克风音源选择和 MTK 解码器适配代码；是否调整需单独评估，本次文档清理不改变其行为。

构建、单元测试和模拟截图不能替代实车验证。连接、声音、Siri、方控、权限、后台恢复及长时间运行均需在目标车辆确认。

安装见 [INSTALL.md](INSTALL.md)，构建见 [BUILD.md](BUILD.md)，测试见 [TESTING.md](TESTING.md)。
