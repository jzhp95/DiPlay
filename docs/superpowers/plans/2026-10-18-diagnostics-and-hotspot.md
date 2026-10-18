# 测试工具与统一热点配置实施计划

**Goal:** 测试工具采用左菜单右详情；热点卡片平铺输入且只有一份已保存信息。
**Architecture:** 复用方控控制器、认证客户端和热点控制器。已保存配置由 CarHotspotProfiles 单独持有；系统读取/confirmed 仅负责初始导入和实际核对。
**Tech Stack:** Android Kotlin Views、JUnit、Robolectric、现有 Flyme 样式。

## 已确认设计
- 左菜单依次为“方控测试”“MFi 认证测试”，右侧独立滚动。切换离开方控时释放测试焦点。
- MFi 分别显示本地证书/材料配对、CH341 枚举/权限、芯片通信/证书读取状态。CarPlay 使用中只做被动检查；不显示密钥、原始证书、挑战或签名。
- 热点名称和密码平铺于卡片，密码可切换显示；保存只持久化，不写入系统或开启热点。用户已明确确认。
- “开启本机热点”打开加密方式、频段、信道选项，确认后以同一份名称和密码配置并开启。
- 删除独立手动保存入口、顶部介绍和底部解释文案。保留必要校验与操作结果。
- 已保存配置优先，不能被旧系统信息覆盖。仅在没有已保存配置时导入 system > confirmed > legacy manual。
- 不删除旧偏好数据；保留接口覆盖、WPA3 和 AUTO 意图。失败不能回退到旧系统配置并误报成功。
- 保留中文、现有主题与控件尺寸规范；无新增依赖或车辆专属能力。

## Task 1: 统一热点数据
Files: shared/network/CarHotspotProfiles.kt、CarHotspotProfile.kt、CarHotspotController.kt；
common/AirPlayPersistence.kt；对应测试。
- [x] 先用已存在 resolve 接口和预置 saved_* 偏好写回归，证明旧实现忽略已保存配置。
- [x] 运行测试确认预期失败。
- [x] 提供 saved(context)、saveSaved(context, profile)；保持 resolve(context, manual, log) 调用兼容。
- [x] resolve 优先保存值，缺失时一次性导入有效旧配置；confirmed/draft 不覆盖已保存值。
- [x] enable 使用保存值 apply；无保存值才沿用原系统开启路径；保存值失败不得启用旧配置作为成功回退。
- [x] AirPlayPersistence 兼容读写与原子完整 profile 保存，保留 manualHotspotInterface。
- [x] 验证保存后读取、重复迁移、旧值保留、自动开启、失败/取消和 AUTO/WPA3。

## Task 2: 测试工具与 MFi
Files: common/WheelTestActivity.kt、WheelTestLayout.kt、新的测试菜单/MFi界面；
shared/mfi 的独立诊断服务；对应测试和 values/diagnostics_strings.xml。
- [x] 先补真实 Activity 菜单存在、切换释放焦点的测试，确认旧界面失败。
- [x] 添加左侧固定菜单和右侧详情，保留方控真实/模拟计数和前台生命周期。
- [x] 复用本地客户端验证证书/密钥匹配；USB 被动枚举与显式主动探测分开。
- [x] USB 授权异步结果与退出/切换后取消受 generation 保护；不抢占运行中的 CarPlay。
- [x] 检测 0x10/0x11，优先可读证书；资源/权限接收器均可释放。
- [x] 验证缺材料、材料损坏、设备缺失、无权限、芯片未响应和有响应等状态。
- [x] 生成宽/窄、大字体、深浅色、RTL 截图，检查裁切和滚动。

## Task 3: 热点卡片与集成
Files: common/CarHotspotSettingsUi.kt、DiPlayActivity.kt、values/strings.xml；
对应 UI、配置和源码规则测试。
- [x] 先补平铺编辑、保存无系统副作用、取消参数不写配置的测试，确认旧行为失败。
- [x] 重用现有卡片样式，名称/密码输入和保存按钮平铺，移除独立手动入口。
- [x] 开启弹窗列出三项参数，校验通过才保存选项并 apply。
- [x] 统一三个连接解析点；旧设置/缺信息引导进入同一卡片，不再打开另一套手动凭据。
- [x] 保留已有接口覆盖的读取和可编辑入口；删除被要求的说明文案。
- [x] 保存仅标记下次连接生效，不中断当前会话；保留必要重连提示。

## 验证
- [x] 实现前保存现有完整 CI 的失败清单，避免将基线失败误报为新回归。
- [x] 顺序运行各模块专项，包含 ManualHotspotOwnershipTest。
- [x] 独立审查最终差异；检查修改边界与数据迁移。
- [x] 检查截图并迭代布局，运行 git diff --check 与 public tree 检查。
- [x] 执行 AGENTS.md 的完整 CI，使用临时 NDK 28.0 配置时明确记录；默认 NDK 28.2 缺 source.properties。
- [x] 比较完整失败清单，分别报告新增失败和仍未解决的基线失败。
- [x] 不自动提交或推送本次新功能改动。


## 验证记录（2026-10-18）

- 相关专项：shared 148 + common 108，共 256 项通过。
- 完整 CI：2918 项测试，128 项失败、1 项跳过。失败用例的 class/test 与修改前保存的基线逐项一致，新增失败 0，消失失败 0；因此完整 CI 仍为失败状态。
- 模块：flyme-eas 10/0，shared 1534/13，common 1368/113，home 6/2（总数/失败）。
- mobile、home、maphost 的 lintDebug 与 assembleDebug 均通过；Flyme system UID Manifest 检查通过。
- 本机默认 NDK 28.2 安装不完整，使用临时 init script 指向已安装的 28.0.13004108；仓库构建配置未修改。
- git diff --check、check_public_tree.py 通过；独立复审确认三项并发问题已闭环。
- 截图包括紧凑/宽屏、1.5 倍字体、深浅色和 RTL；测试工具另覆盖 160/320 dpi。
- 检查范围为本机单元测试、静态检查及截图；真实车机热点控制、USB CH341 与 iPhone 认证仍需实机验证。
- 本次新功能按用户要求作本地提交，未推送。已有提交 79833882 属于前一项热点接口归属修复。
