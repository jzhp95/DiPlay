# 实验音频验证（2026-10-25）

已按用户要求完成全部任务代码与用例后统一测试；仅验证中发现截图透明背景影响审阅，修正测试背景及测试空值声明后，集中复验受影响用例。未升版、未提交。

## 功能
设置→高级→视频与音频新增四个独立默认关闭开关：麦克风增益、导航独立增益、通话独立增益、音频输出故障恢复。
增益 -12～+12 dB，默认 0；关闭保留数值，下次连接生效并显示待重连提示，不强制断开会话。
麦克风在 AEC 后、编码前；导航与已有衰减合并；通话仅 telephony PCM，原车 HFP 电话音频不受此项控制。
故障输出在 worker 内最多重建两次，处理死输出/无效状态、写入异常、连续三次零写以及播放/缓冲状态异常；释放旧焦点、监听器、轨道并重置缓冲时钟和 AEC 参考，不重播已接受 PCM。
保留既有软件直出与 YUV 自绘功能，不修改媒体中心播放状态待办。

## 检查
完整 CI（使用本机已安装 NDK28.0 的临时 init，未修改项目 NDK）：
```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-25.jdk/Contents/Home ANDROID_HOME=/Users/jzhp/Library/Android/sdk ./gradlew --init-script /tmp/diplay-software-direct-use-installed-ndk.gradle :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
```

- Flyme EAS：10 项，零失败。
- shared：1203 项，零失败，1 项既有跳过。
- common：1362 项，99 项失败；与软件直出版本保存的基线逐项比较，失败名称、异常类型及报错相同，无新增/变化/消失的失败。完整 CI 因既有失败退出 1，不能称全绿。
- 本次新增 19 项均通过：PCM 饱和/范围/独立控制/默认透明、AEC 后编码前增益、部分写入续写、默认关闭不重建、恢复预算/关闭/非法参数/连续零写/异常/避免重播、持久化/会话快照/不中断、窄屏/宽屏/RTL 大字体截图。
- 最后仅复验受测试改动影响的 shared 8 项、common 3 项，全部通过。
- Lint：零错误，3 项既有告警；assembleDebug 成功。
- 独立代码审查未发现可证实的新增正确性问题。
- 已查看三张设置截图；API28 模拟验证完成，实车音量、音质及厂商 AudioTrack 故障恢复尚未验证。

## 交付
APK：/Users/jzhp/Downloads/test-rea/DiPlay-v0.2.24-实验音频与YUV测试.apk
versionName=0.2.24-hud-test，versionCode=43，minSdk=28；签名与原调试包一致。
SHA256：02f447a0da9c97b99c11234711473034ce0721e6a12c0b5e73412d7023e3eadb
完整结果、基线比较、最终受影响复验、截图和 Lint：/Users/jzhp/Downloads/test-rea/artifacts/diplay-experimental-audio-20261025
