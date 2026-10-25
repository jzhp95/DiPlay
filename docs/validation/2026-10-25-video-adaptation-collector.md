# 车机视频适配采集验证（2026-10-25）

目标：2026 款星瑞昆仑 Flyme Auto / Android9 API28；用户报告屏幕14.6英寸，该英寸值不是Android自动测量结果。
版本保持0.2.24/code43，未自动提交。保留已提交的YUV自绘和实验音频选项。

## 使用
1. 建立CarPlay连接，打开测试页面→视频适配，可填写iPhone型号、iOS版本。
2. 点击“采集当前CarPlay主屏”。首次会先在后台采集设备能力，再返回真实主屏。
3. 静止页面采样20秒，再进行拖动地图采样20秒。动态阶段核对拖动事件和新视频帧；每段结束保存截图。
4. “结束并返回测试页”，记录清晰度/发热，点击“保存并分享整包报告”。
5. 需要比较其他模式时，沿用设置选择并重连后再采，最近8组保留配置标签；静止和动态需属于同一会话配置。颜色测试历史和图片作为独立参考附件，不冒充本轮已完成。

## 实现与边界
- 设备能力：系统报告的真实/逻辑像素、显示模式/刷新率、DPI可信度说明、固件/硬件/板卡/ABI、GPU独立EGL探针、AVC/HEVC能力清单。枚举在后台、计时之前；API28未知软硬件类别不擅自判定。
- 当前主屏：请求画布、窗口/视图、Surface尺寸、变换、解码器实例/代际/输出格式、SPS配置、颜色信息、实际输出模式、每秒与全段码率/FPS/队列到输出时延/应用CPU核占用。视频字节为接收负载，不是网络带宽；接收与提交差不直接当作丢帧；电池温度不是CPU温度。
- 解码器/配置/尺寸/实时画面调整变化、画面面板打开、后台/退出/令牌失效等会中断或拒绝采样。控件附加于主屏上方，不改视频布局，不替换控制器、不自动断连。
- 截图复用PixelCopy，保持视图原尺寸，最多3840x2160等比例缩小并标记；独立目录不被颜色测试清理。截图不测最终面板/背光/HWC后处理。
- ZIP包含report.json、report.txt、本轮截图、独立颜色参考图片及缺失文件清单。复用已有文本无文件选择器保存降级和FileProvider。无分享目标时文件仍保留，界面显示路径。
- 本页收集证据，不自动调优，也不保证一轮采集即能彻底解决画质问题。缺项明确显示。

## 验证
全部代码和用例写完后统一运行：
```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-25.jdk/Contents/Home ANDROID_HOME=/Users/jzhp/Library/Android/sdk ./gradlew --init-script /tmp/diplay-software-direct-use-installed-ndk.gradle :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
```
临时init使用本机完整NDK28.0，项目NDK配置未改。

第一次统一检查：shared1204项零失败、1项既有跳过，Lint和APK成功；common测试夹具受View.overlay同名属性影响编译失败，未执行common测试。集中修正夹具、独立审查发现的画面参数混采问题，并补充解码格式/拖动事件覆盖后，统一复验；共享模块直接复用匹配的已通过结果，未反复重跑。

最终：
- Flyme EAS：10项，零失败。
- shared：1204项，零失败，1项既有跳过。
- common：1385项，99项失败。对比实验音频版本保存的基线：失败名称/类型/消息逐项一致，新增/变化/消失均0。完整CI因既有失败退出1，不能称全绿。
- 新增24项全部通过；五Tab布局原用例通过，包括窄/宽、160/320dpi、深/浅和RTL大字体组合。
- Lint：零错误、3项既有告警；assembleDebug成功。
- 采集页compact/expanded/RTL大字体截图已查看；独立复查确认无未解决实质问题。
- 尚未在实体车机采集，本APK用于带回真实硬件与视频证据。

## 交付
APK：/Users/jzhp/Downloads/test-rea/DiPlay-v0.2.24-视频适配采集测试.apk
SHA256：6ee096a63eb5ab4f07cc87941779763fe227174916cdfbf3749a065181f9f017
package=com.shihab.diplay.hudtest；versionName=0.2.24-hud-test；versionCode=43；minSdk=28；签名与前包一致。
完整XML、基线比较、截图、Lint和APK：/Users/jzhp/Downloads/test-rea/artifacts/diplay-video-adaptation-20261025
