# 旧文档与注释清理验证

基准：`feb84c8b`。当前分支面向星瑞 2026 款昆仑 Flyme Auto / Android 9。

删除 29 份旧车型专用指南、上游发布说明及失效审查资料。更新当前兼容、连接、地图、隐私、测试、变更日志与问题模板；修复删除后的链接，移除已不存在的设置或修复入口说明。版权许可、适用的第三方归属及近期清理验证证据保留。

50 个源码/构建文件只清理旧注释。按识别普通字符串、原始字符串及嵌套块注释的词法扫描，对比 HEAD 与最终文件的非注释内容（保留字符串原值、忽略外部空白）完全一致；逐文件指纹见同目录 JSON。独立复核也确认无执行代码变化。本次没有删除残存的麦克风音源策略或 MTK 解码优化。

## 验证结果

| 模块 | 测试总数 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| shared | 1157 | 0 | 1 |
| common | 1279 | 99 | 0 |
| flyme-eas | 10 | 0 | 0 |

99 项失败均与既有历史失败按类名和方法名匹配，没有新增失败用例；全量命令因此退出 1，不可表述为全量通过。主 APK 构建完成，Lint 0 错误、3 警告。

现行 Markdown 的本地路径链接及代码围栏检查通过；冻结的历史验证、计划与许可目录不按当前操作指南解释。文档复核的三处遗漏均已修正。提交前执行差异空白检查。未进行实车验证。

## 命令与环境

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-25.jdk/Contents/Home \
  ANDROID_HOME=/Users/jzhp/Library/Android/sdk \
  ./gradlew --init-script /tmp/diplay-byd-use-installed-ndk.gradle \
  :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug --continue --console=plain
```

本机指定 NDK 安装不完整，临时 init script 选择已安装的 `28.0.13004108`，仓库 NDK 配置未改。日志：`/tmp/diplay-doc-comment-validation.log`。验证后新增的修改仅为文档及两处注释，最终非注释内容比对通过，未改变测试覆盖的执行逻辑。
