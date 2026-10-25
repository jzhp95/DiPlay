# 一键视频适配与颜色测试验证（2026-10-25）

用户目标：在车机点一次运行，自动跑视频适配与颜色技术矩阵，直接查看数据，日志/截图ZIP保存公共Download。版本保持0.2.24/code43，不自动提交。

## 实现
- 视频适配与画面颜色两页共用一键入口。40项：24个标准片段组合（H264/HEVC、全/有限范围、纹理/Surface、默认/软件/SPS）+12个既有正式主屏+4个追加YUV自绘组合。旧36项索引不变。
- 标准片段出帧后3秒采集，15秒未完成标记失败继续；主屏75秒上限，出帧后20秒采样再截图，自动关闭本次临时连接并确认释放后继续。
- 日常设置不改写；中断/重复/乱序/旧运行回调不推进新轮。主屏或解码器配置变化记录为缺项/失败，不当作成功。
- 自动推进、外观重建和取消都经过进程级释放门控；10秒未确认停止套件、保留日志，阻止新轮直到真实ACK；迟到ACK不恢复运行。进程重启清除失效等待并标记中断。
- 页面显示设备、屏幕、GPU、解码能力、最近视频格式/布局/颜色/性能；正式主屏控件实时显示当前项目和码率/FPS/时延。
- 自动采集不判定物理面板颜色正常，也不自动操作iPhone应用。真实地图运动未验证明确记录；不存在用静止码率冒充动态运动的结论。
- Android9首次请求公共存储权限；允许后ZIP直接保存在内部存储/Download/Apple CarPlay。拒绝/公开目录故障时继续运行，保留应用副本并明确提示降级，可分享或重新授权保存。
- API29+经MediaStore发布，失败清理仅新建条目；公共报告不覆盖、不清理。旧导出必须匹配运行ID，不能污染新运行。结束自动保存，不自动弹出分享界面。

## 验证
所有实现与用例完成后统一执行项目CI：
```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-25.jdk/Contents/Home ANDROID_HOME=/Users/jzhp/Library/Android/sdk ./gradlew --init-script /tmp/diplay-software-direct-use-installed-ndk.gradle :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
```
NDK仅通过临时init使用本机完整28.0，项目配置未改。
首轮common1404项100失败：99项既有失败+1项旧测试仍预期12个主屏、实际追加后为16的数量断言。该断言已更新并验证。共享模块1204零失败、1既有跳过；Flyme10零失败。
独立审查发现释放确认、重建/取消门控和导出归属边界，集中修正并补用例。针对相关范围依次59、35、11项均零失败；无新增改动的已通过检查不重复运行。
按测试类使用较新XML覆盖较旧XML形成可追溯验证集合：common 1409项、99项既有失败；与上一版基线失败名称/类型/消息逐项一致，无新增或变化。此集合不是宣称重新跑了整套全量，原全量CI仍退出1。
本次新增24项全部通过；包括公共ZIP、API28授权/拒绝、API29发布回滚、逐项自动状态、防重复/乱序/迟到导出、主屏超时/自动采样/取消、页面重建等待旧worker、迟到释放不复活、进程死亡等待恢复、临时YUV配置不写日常值。
Lint零错误、3项既有告警；最终assembleDebug成功。签名、版本、minSdk核对一致。窄/宽/RTL大字体自动进度、两页入口截图已查看。
实体车机40项矩阵尚未运行；本APK用于真实采集，不能用模拟测试代替实车画质结论。

## 使用
停车并保持iPhone可连接，打开测试页面→视频适配→一键自动测试视频与颜色。首次允许存储，等待自动运行完成；下方原手动采样入口不必逐项操作。
完成后从文件管理→内部存储→Download→Apple CarPlay取video-adaptation开头的ZIP。以页面显示的实际路径为准；未获权限/不可写时会明确显示应用副本而非声称公共保存成功。
APK：/Users/jzhp/Downloads/test-rea/DiPlay-v0.2.24-一键视频颜色自动测试.apk
SHA256：d3fa940d1f6668f55fb23c9ab79e27e08c2142890cd2f77fbd6314749c553305
验证记录：/Users/jzhp/Downloads/test-rea/artifacts/diplay-automatic-video-20261025
