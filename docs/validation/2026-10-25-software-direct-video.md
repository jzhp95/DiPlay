# 软件解码直接输出兼容版

版本：0.2.23-hud-test / code42；包名com.shihab.diplay.hudtest；minSdk28。未升版本。

## 使用

设置 → 高级 → 视频与音频 → 软件解码直接输出（实验性），开启后点击“立即重新连接”或等待下次连接。默认关闭；保存本身不打断当前会话。若选定格式无已确认的软件解码器，则提示并不保存开启状态。

开启时主屏严格使用系统软件解码器和SurfaceView直接输出，关闭本主屏的平滑视频、硬件低延迟调优与画面调整。原有偏好不覆写，关闭模式后恢复。镜像与其他流保持既有解码/调优策略；HEVC格式在启用模式期间锁定，防止切到无软件解码能力的格式。

用户实测提供依据：正式主屏“软件解码＋直接输出”颜色较正常，仅轻微发白，其余测试路径严重发白。此次只把该路径接入日常使用，不叠加亮度、对比度或SPS颜色修正。该新APK的物理颜色与持续运行性能尚未实车验证，不宣称颜色根因已解决。

## 验证

全部功能代码与回归用例完成后统一运行AGENTS.md要求的命令：
`./gradlew :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --continue --console=plain`。

本机NDK28.2目录缺少source.properties，原命令在配置阶段失败，未执行测试。随后通过临时init脚本使用完整安装的NDK28.0，未修改项目NDK版本。JDK25，ANDROID_HOME指向本机SDK。

- flyme-eas：10项，0失败。
- shared：1177项，0失败，1项原有跳过。
- common：1349项，99项失败。与HFP/SPS版已确认99项历史失败逐项比较，名称和message均相同，无新增失败。完整CI因这些历史失败返回非零，不能称为全绿。
- 最新连接阶段版的full-test-results保留了100项初轮失败；其新增布局失败已在该版layout-proof-results单独修正。本次99项结果不代表此次修复了那个无关布局问题。
- 新增主屏策略、设置保存/能力检查、诊断独立性、保留连接用例通过。
- 最后仅补强测试：连接保留覆盖开启/关闭两个方向，截图改为实际新增设置行；针对8项用例再次运行，全部通过。正式功能代码未再改变。
- Lint：0 errors、3项已有warnings；APK构建成功。
- Android9/API28大字体窄屏/宽屏及RTL系统环境截图已检查。应用保持固定中文布局。
- 独立代码复核完成：修正主屏范围、待重连后台连接保留及有效配置诊断字段，没有剩余已知新增问题。
- apksigner验证成功，证书SHA256与上一测试版一致：a728db520981244c7682258634ef6df9bae179f756c80cdbd67a77c0e1e44819。
- APK包含本机已有的两项运行认证资产，仅核对存在，未输出内容。

完整报告、XML、截图与APK见 [交付目录](/Users/jzhp/Downloads/test-rea/artifacts/diplay-software-direct-20261025/README.md)。
