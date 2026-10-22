# 验证清单

目标：吉利星瑞 2026 款昆仑，Flyme Auto / Android 9（API 28）。

## 源码检查

在仓库根目录运行当前 CI 命令：

```sh
./gradlew :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug
```

具体环境见 [构建指南](BUILD.md)。每次验证记录实际命令、退出码、测试失败及环境覆盖；历史失败不等于全量通过。近期结果见 [验证索引](VALIDATION.md)。

## 实车检查

- USB 和无线连接、断开、重连及权限拒绝后的恢复。
- 画面、触摸、音乐、导航、Siri 和通话。
- Flyme EAS 注册与释放、按键去重、活动会话归属及通话期间处理。
- 切换前后台、系统回收、重启及长时间运行。
- 设置保存和取消，下一次连接生效提示与用户已有配置保留。
- 诊断导出、查看和分享；日志不得包含认证资料或热点密码。

记录 APK、固件、iOS、连接方式、实际结果与未验证项。单元测试、静态检查和构建产物检查不能替代实车结果。
