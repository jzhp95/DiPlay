# DiPlay · Flyme Auto 适配分支

本分支面向 **吉利星瑞 2026 款昆仑**，目标车机环境为 **Flyme Auto／Android 9（API 28）**，主应用最低支持 API 28。界面使用简体中文与 Flyme Auto 风格，方向盘控制以现有 Flyme EAS 适配为准。

## 功能与范围

- 有线 USB 与无线 CarPlay，支持车机热点、Wi-Fi Direct 和同一局域网连接方式。
- 通用音视频、Siri、定位、媒体信息与本地诊断。
- 虚拟 CarPlay 地图流，可供中控地图卡片与支持的桌面地图宿主使用。
- BYD／DiLink 专属 HUD、物理仪表投屏、车辆数据、氛围灯、通话接管与私有方控适配已移除。
- 不合并其他车型专属适配，或仅用于 Android 9 以下版本的兼容补丁；通用修复按目标车型的实际适用性评估。

## 开发与验证

项目规则见 [AGENTS.md](AGENTS.md)，构建说明见 [docs/BUILD.md](docs/BUILD.md)。默认生成普通包以便日常调试；传 `-PflymeSystem=true` 生成 Flyme 系统包（正式包名 `com.shihab.diplay.flyme`，测试包名 `com.shihab.diplay.flyme.test`，声明系统 UID 与 EAS）。认证资产与签名须按构建说明配置，勿提交密钥。

当前安装、兼容范围和测试入口见 `docs/`；逐次清理的验证证据保留在 `docs/validation/`。单元测试、模拟截图和构建成功不能替代 Android 9 实车验证。

## 来源与许可

基于 [xcertplay](https://github.com/shilapi/xcertplay)，遵循 GPL-3.0；部分界面与网站源自 [DiAuto](https://github.com/shihabal3amri/DiAuto)，相关 AGPL-3.0 许可位于 `docs/licenses`。保留原作者的版权与许可声明，详见 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。CarPlay 与相关商标属于 Apple Inc.，本项目未经 Apple 认证，与汽车厂商不存在官方隶属关系。
