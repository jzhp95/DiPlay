# 当前连接阶段展示

版本保持 0.2.23 / 42，包名 com.shihab.diplay.hudtest；未改版本号。
连接页保留原有友好状态和错误／重试提示，其下新增“当前阶段：…”一行，显示实际状态及有限协议进度：认证设备准备与权限、网络／热点、蓝牙、USB／配对、MFi认证、音视频协商、等待画面。
仅显示固定中文资源，原始协议、body、凭据不展示；没有推算百分比。MFi设备就绪与认证通过分开，不把CH341有效当作认证已成功。沿用原等待面板显示生命周期。
先完成全部代码和静态复核，再统一测试。复核发现的协议body=长度被脱敏过滤而无法更新阶段的问题已修复：脱敏之前仅提取固定标签，UI内检查连接代次、关闭、失败及重试停止状态，原日志过滤保持不变。
版本／签名已通过aapt2与apksigner核实，与上一测试包的签名一致。

## 验证
- 完整项目CI已执行：common1341项测试100项失败，其中99项与原基线名称和消息一致；新增1项截图用例最初因离屏测量未完成字号调整后的布局而失败。统一修正该测试后单独验证通过，8张截图人工检查正常。完整套件不称全绿。
- CarPlayStageTextTest：8项通过（API28/29，包含真实监听器含body=的SETUP、旧连接和关闭回调）。
- ConnectionStageProgressTest：4项通过，覆盖硬件就绪／认证通过区分、协议白名单、失败后忽略迟到事件、USB阶段。
- WaitingScreenAppearanceTest：2项通过。
- ConnectionStageScreenshotsTest：1项通过，内含8种布局（日夜×宽屏／窄屏／短屏／RTL，API28，1.5倍字体）。
- shared1176项无失败、1原有skip；flyme-eas10项无失败。
- mobile Lint：0 errors / 3 warnings；assembleDebug成功。
- 实车阶段变化与最终显示效果待验证；本次未改变连接协议、电话、颜色解码或媒体中心逻辑。

安装包：DiPlay-v0.2.23-连接阶段显示.apk
SHA256：6dc3ddaf9c3da2ef24c2a51aa29adf4544d7cd6f92c65f4bfd7353fc11e2fa8f
完整结果：full-results.json；最终结果：validation-results.json；截图：screenshots。
