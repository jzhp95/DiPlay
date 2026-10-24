# DiPlay 0.2.23：原车 HFP 策略与 SPS 颜色测试

- versionName 0.2.23-hud-test，versionCode 42；包名 com.shihab.diplay.hudtest，minSdk 28。
- APK：[DiPlay-v0.2.23-HFP-SPS测试.apk](/Users/jzhp/Downloads/test-rea/DiPlay-v0.2.23-HFP-SPS测试.apk)，SHA256：44d0575a11bb142239bf3339ece514432a70681f9666c7a304c6c021dffdb96a。
- 新增“高级 → 保留原车蓝牙电话（实验性）”，默认开启，设置改变只标记下一次连接生效。现有链路不主动断开 HFP、不接管原车电话键；本次增加原车 MODE_RINGTONE 下 type100/alert 重复铃声抑制，按流保持到 stop／close。非 alert 不读取系统模式，已抑制流不重复查询。
- 保留音乐、导航、Siri 和 CarPlay 通话／麦克风回退；未强制禁用全部 CarPlay 通话音频。原车电话需保持配对连接，实际路由由 iPhone 与车机决定。
- 颜色测试从 26 项增到 36 项：新增 2 个正式主屏 SPS 项（纹理／直接，编号27–28）和8个 SPS 测试片项（H264/HEVC×full/limited×纹理/直接，编号29–36）。原26项编号保留。SPS 仅在测试时启用，日常颜色配置不变。
- 复用 SPS 解析，明确可映射的 range／standard／transfer 写入 MediaFormat；standard 同时检查 primaries 与 matrix，未知／冲突／不支持的组合不猜测，手动覆盖优先。证据包含 fromSps、输入 SPS、requested_color_format、输出格式及观察记录。
- 不引入 HUD、25 款星瑞优化，不修改媒体中心注册。
- 已把“全部任务代码写完后统一测试”写入 AGENTS.md。

## 验证

最终已统一执行项目规定的 flyme-eas/shared/common 全量单测、mobile Lint 和 assembleDebug（使用已安装 NDK 28.0 的临时覆盖、JDK25 Mockito premain；未修改依赖）。
- shared：1176 tests，0 failures，1 原有 skip。
- common：1331 tests，99 failures，与上一版基线的失败名称及消息完全一致，0 新增失败。完整 CI 因这99项既有失败退出非零，不能称全绿。
- flyme-eas：10 tests，0 failures。
- Lint：0 errors，3 warnings；APK 构建成功。
- API28 新增 SPS 集成测试验证实际 sink→decoder→MediaCodec.configure 的字段传递，覆盖两种编码、两种范围、开关两种状态。它不证明解码器输出或屏幕物理颜色已正确。
- API28 HFP策略及sink测试、持久化设置测试通过；宽窄屏1.5倍字体截图见 hfp-ui，已人工检查。
- apksigner verify 成功，证书 SHA256 与上一版测试 APK 一致：a728db520981244c7682258634ef6df9bae179f756c80cdbd67a77c0e1e44819。
- 具体测试结果、基线对比与日志见 validation-results.json、final-test-results 和 final-validation.log。
- 独立静态复核已完成；发现的 SPS 原色／矩阵组合映射问题已修正并加入回归测试。
- 实车 HFP 电话表现、来电重复铃声与物理颜色效果待用户验证；用户已反馈车机测试页获得系统 UID，不能用本地包声明替代实车身份。

SPS映射依据：[AOSP ColorUtils](https://android.googlesource.com/platform/frameworks/av/+/master/media/module/foundation/ColorUtils.cpp)。
