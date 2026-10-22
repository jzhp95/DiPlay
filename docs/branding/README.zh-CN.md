# CarPlay 图形资源适配

## 当前来源

使用用户指定的 `Apple CarPlay_master_20251010_1554.apk.1`：

- 包名：`com.ts.carplaypermission`
- 版本：`master_20251010_1554`，versionCode 1
- APK SHA-256：`fcd4bb55dfbb1ee928ec34803a747f9f35f630ff495cf1b92242474a15e58a72`
- 全部资源解码位置：仓库外 `../extracted-carplay-master-20251010/`

以上身份来自原 APK 的静态检查，不代表发行方真实性认证。此前绿色 `com.ganfandw.carplay` 包的桌面图标配置由本次适配替代。

## 桌面和应用内标志

原 Manifest 的主图标指向 `@drawable/allmenu_carplay`，即 200×200 的白底黑色 CarPlay 标志。该 PNG 原字节复制到：

- `common/src/main/res/drawable/ic_carplay.png`：沿用应用内 Logo 的现有引用。
- `shared/src/main/res/drawable/ic_carplay_launcher.png`：提供桌面图标独立依赖，避免 shared 反向依赖 common。

原包 `roundIcon` 指向的自适应图标包含 Android 默认机器人和网格背景，与主 CarPlay 图标不一致，因此不导入。当前应用采用如下兼容适配：

- 当前 API 28+：`mipmap-anydpi-v26` 的白色背景和原 PNG 前景；前景四侧各留 8% 自适应遮罩余量。
- 普通和圆形入口均引用同一 CarPlay 标志，最终轮廓由桌面遮罩决定。
- 没有额外声明单色图层：原 PNG 带白底，不能直接作为透明的单色标志。
- 删除此前绿色桌面位图和图层，防止特定密度选择到旧图。

这是围绕原主图标制作的 Android 兼容包装，并非原 APK 默认圆形素材的直接复制。普通版和 UID 版使用相同资源。

## 界面图标

从原包 Flyme 图形资源中按用途映射到项目现有资源 ID，共 17 个界面图标位置：无线、有线连接、连接设置、音频、显示、主页、关于、日间、权限、导航、搜索、返回、右箭头、高级、概览、诊断和控制。

矢量路径、viewport、透明度保持不变，仅将尺寸统一为当前界面的 24dp，并将依赖 `?colorLabelLevel1`、`?colorFaLabelL1` 等主题的填色转换为白色基础色；当前界面已有的 tint 继续控制最终颜色。这避免引入 Flyme 主题、尺寸资源和 UI 库。

源包中的其他图形仍可在完整解码目录中查阅。无对应用途的素材、系统和第三方库图形不批量导入；其他现有图标保留。没有更换布局、主题、连接或方控逻辑，也没有导入原 APK 的代码或认证文件。

## 清单和验证

`carplay-master-resources.json` 记录了 19 个提取资源目标（17 个界面位置及两份原 PNG）、原文件散列、适配后散列，以及 5 个生成的桌面 XML 包装。

桌面最终显示还受车机启动器遮罩和图标缓存影响，需要更新安装后实车确认。

历史资源核验不代表本分支最新整包结果；当前构建与测试以 [验证索引](../VALIDATION.md) 为准。
