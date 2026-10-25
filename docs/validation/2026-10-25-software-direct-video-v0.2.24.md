# 软件解码直出兼容版升级至0.2.24

按用户明确要求将mobile版本从0.2.23/code42升级至0.2.24/code43；debug版本为0.2.24-hud-test。软件解码兼容模式功能保持上一版。

已统一执行AGENTS.md规定的flyme-eas/shared/common全量测试、mobile lint和assembleDebug，使用与上轮相同的本地NDK28.0临时配置。构建与签名检查通过，证书与上一测试版一致。

Flyme EAS 10项、shared 1177项无失败；common 1349项中99项历史失败，与上一兼容版失败名称和消息一致，无新增。完整CI因已有失败返回非零。Lint零错误、3项已有警告。

[安装包](/Users/jzhp/Downloads/test-rea/DiPlay-v0.2.24-软件解码直出兼容.apk)；[验证结果](/Users/jzhp/Downloads/test-rea/artifacts/diplay-software-direct-v0.2.24-20261025/validation-results.json)。
