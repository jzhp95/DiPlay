# 原车 HFP 策略与 SPS 颜色对比
用户已授权接入对方 HFP 逻辑、增加 SPS 测试项并升级版本。范围不含 HUD、25 款星瑞优化、媒体中心修改。
HFP 独立开关默认开启，可关闭回退，下一次连接生效。沿用当前不主动断开 HFP、通话期间放行方控的机制；仅在原车 MODE_RINGTONE 时抑制 type=100 / alert 的重复 CarPlay 铃声，按流保持到 stop，避免系统模式瞬变导致重复铃声。保留 CarPlay 通话音频／麦克风回退，不虚构强制电话路由。
SPS 增加 fromSps 临时解码选项；解析已有 avcC/hvcC，只有明确且可映射的颜色字段写入 MediaFormat，手动覆盖优先。正常播放默认不变。测试追加 8 个测试片和 2 个正式主屏项目，保持原 26 项索引不变，导出包含 SPS 请求、输入解析和解码器输出，不把参数匹配当作物理颜色正常。
版本 0.2.23 / 42。API28 验证，完成项目 CI；实车效果明确待验证。

SPS standard 只映射明确支持的 primaries/matrix 组合：1/1→709、5/(5或6)→601 PAL、6/(5或6)→601 NTSC、9/9→2020。未知、冲突或 constant-luminance 不设置 standard，range/transfer 独立处理。
参考 https://android.googlesource.com/platform/frameworks/av/+/master/media/module/foundation/ColorUtils.cpp 的 sStandards。
测试时机遵守 AGENTS.md 新准则：后续全部代码完成后统一测试。
