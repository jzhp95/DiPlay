# EAS 播放状态实施清单

用户授权直接实施；内联执行，不提交、不升版。遵守 AGENTS：所有代码完成后统一测试。

- [x] 新增 integrations/flyme-eas/.../EasPlayStateChannel.java：独立串行通知、Framework绑定、保留最新状态、重试/看门狗、关闭清理。
- [x] EasAdapter.java：connect启动、updatePlayback同步真实状态、close停止；原注册/按键/焦点不变。
- [x] common/.../EasPlayStateChannelTest.kt：API28真实Binder/Parcel边界与会话生命周期回归；FlymeMediaPublicationTest验证入口串联。
- [x] 更新 docs/TODO.md 为已接入待实车验证，保留未知状态风险。
- [x] 统一执行 flyme-eas/shared/common 单测、mobile lint、assembleDebug；失败按既有基线区分。
- [x] 独立代码审查、修复后针对受影响范围验证，保存结果与APK路径。

验证结果：专项116/116（API28新通道14/14），shared1218/0（skip1），flyme-eas10/0。common合并已验证结果1473项、原有99失败，新增/类型变化均0；普通与Flyme构建/lint通过。详见 ../../../../artifacts/diplay-eas-play-state-20261027/README.md。
