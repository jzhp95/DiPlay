# 自动 MFi 认证实施计划

**Goal:** 连接时优先 CH341，设备缺失、授权或启动通信失败时回退离线认证。
**Architecture:** 增加 AUTO 目标，保留强制 LOCAL/USB_CH341 和高级 I2C/REMOTE；控制器在认证来源确定前完成芯片可用性检查，单次启动最多回退一次。自动离线路径直接验证已安装材料或应用内材料，CH341 可用时不依赖本地证书。
**Tech Stack:** Kotlin Android Views、现有 MFi/CH341 客户端、JUnit/Robolectric。

## 已确认行为
- 用户已明确：CH341 授权被拒或通信失败也回退离线。
- 每次连接重新检测，已运行 CarPlay 的身份不在会话中切换。
- 自动 CH341 在交给 iPhone 前读取证书并验证签名通信；初始化失败先关闭 USB，再加载离线材料。
- 离线缺失/无效时报认证错误，不使用远程服务；取消或退出后不继续回退。
- 新安装默认 AUTO；旧 LOCAL/USB 设置通过新的选择键默认迁移 AUTO，旧值保留用于回滚。旧 I2C/REMOTE 保留。新界面仍允许显式强制本地或 CH341。
- 不自动提交或推送本次修改。

## Task 1: 选择与迁移
Files: AirPlayPersistence.kt、CarPlayRuntimeConfig.kt、ConnectionSettingsSection.kt、
DiPlayActivity.kt、CarPlayHostActivity.kt、values/strings.xml；对应测试。
- [x] 添加默认自动、旧值迁移、专家模式保留与显式覆盖测试，先验证失败。
- [x] 新增 AUTO；新偏好键优先于旧键，旧键不删除；应用层 AUTO 提供 1a86:5512。
- [x] 主设置和投屏菜单均可选择自动，文案说明下次连接生效。

## Task 2: 启动回退
Files: CarPlayController.kt、common 自动认证集成测试。
- [x] 先添加设备缺失、无 USB 服务、拒绝授权、打开/通信失败、成功 CH341、离线无效与关闭后的回归。
- [x] 每次启动使用 generation 隔离晚到权限/USB 回调，回退至多一次。
- [x] 自动 CH341 在宣布 ready 前完整读取证书并进行一次随机摘要签名，失败释放连接并回退。
- [x] 自动离线读取现有安装或应用内材料；不为可用 CH341 预先要求离线证书。
- [x] 严格手动与高级模式保持现有行为。

## 验证
- [x] 相关专项与窄/宽、大字体、深浅色、RTL 截图。
- [x] 独立复审、git diff --check、check_public_tree.py。
- [x] 完整 CI 使用已安装 NDK 28.0 临时覆盖，并与已有 128 项失败基线逐项对比。
- [ ] 实机 USB/iPhone 仍需用户验证。


## 验证记录（2026-10-18）

- 认证专项：shared 19、common 107，共 126 项通过；包含两项稳定复现后修复的重连交错回归。
- 完整 CI：2942 项测试，128 项失败、1 项跳过；class/test 与修改前 128 项失败基线完全相同，新增和消失失败均为 0，完整 CI 仍处于失败状态。
- 分模块：flyme-eas 10/0，shared 1534/13，common 1392/113，home 6/2（总数/失败）。
- mobile、home、maphost 的 lintDebug 和 assembleDebug 通过。
- 本机默认 NDK 28.2 安装不完整，使用临时 init script 指定已有 28.0.13004108，仓库 NDK 配置未改动。
- 一次并行专项遇到 Mockito JVM 代理挂载失败，串行复跑通过；最终完整检查使用 --max-workers=1。
- 独立复审已确认旧权限/失败回调的 generation 竞态闭环；git diff --check、check_public_tree.py 通过。
- 自动认证选项生成窄/宽、160/320 dpi、1.5 倍字体、深浅色和 RTL 预览；图库位于 common/build/reports/mfi-auto-settings-ui。
- 完整 common 测试报告已保留至 common/build/reports/automatic-mfi-full-ci，防止随后单项截图复跑覆盖。
- 仅增加已有 Bouncy Castle 的测试依赖用于即时生成合成材料，没有新增运行时依赖或真实身份文件。
- 未提交或推送本次修改。真实 CH341、热点和 iPhone 认证仍需实机验证。

## Task 3: 连接周期内故障恢复（用户后续确认）

- [x] 每次新连接重新检测，选定后证书、协议与签名固定同一来源。
- [x] Provider调用失败、iAP2认证拒绝/认证超时、启动材料失败标记为认证故障。
- [x] 立即关闭旧控制器，完整释放后才新建控制器并重新检测，自动恢复最多一次；手动重试重置额度。
- [x] 菜单打开时保留认证故障；迟到成功/失败回调不能改变新连接或复活旧连接。
- [x] 保留释放超时的旧控制器，页面恢复和手动重试均不能绕过释放门禁。
- 第三步代码已完成；复审指出的代际回调、菜单与跨页面释放门禁已补齐。
- 用户要求默认只修改代码，由用户自行调试；最终专项已停止，不再主动运行测试或完整 CI。上方 CI 数字仅对应第三步之前的历史验证。
- 本次仅提交代码，不推送。
