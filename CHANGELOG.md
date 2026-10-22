# Flyme Auto 分支变更

目标：吉利星瑞 2026 款昆仑，Flyme Auto / Android 9（API 28）。详细变更以 Git 提交记录为准。

- 删除其他车型专属适配，厂商方控统一为 Flyme Auto EAS。
- 最低安装与原生构建版本统一为 API 28，移除更低系统兼容实现。
- 清理旧初始化向导及其无调用辅助逻辑。
- 删除 Automotive、Home、MapHost 独立应用及对应构建、CI 入口。
- 清理旧车型、旧版本指南和失效注释，保留版权许可与近期验证证据。

当前构建入口见 [BUILD.md](docs/BUILD.md)，兼容范围见 [COMPATIBILITY.md](docs/COMPATIBILITY.md)，测试结果见 [VALIDATION.md](docs/VALIDATION.md)。上述变更不代表已完成实车验证。
