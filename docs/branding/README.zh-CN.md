# CarPlay 图形资源来源

本次资源提取自用户指定的 `Apple CarPlay_sign.apk(1).1`，包名
`com.ganfandw.carplay`，版本 `1.3.2`（1302）。APK SHA-256：
`fdf8ab7aee2bcb236b80a0e0cca28bf367236352c49dafa1754659a5224b0298`。
这记录了输入文件身份，不代表对发行方身份的认证。

## 已接入的资源

- 桌面图标：mdpi、hdpi、xhdpi、xxhdpi、xxxhdpi 五档普通、圆形、背景、前景和单色图层，均放在 `shared/src/main/res/`。
- 自适应图标：原 APK 的两个 `mipmap-anydpi` XML 移到 `mipmap-anydpi-v26`，内容保持原样。当前应用支持 Android 7.1（API 25），此版本使用各密度位图；Android 8 及以上使用自适应图标，避免旧系统读取不支持的标签。
- 应用内 Logo：替换 `common` 的 `ic_carplay.png`，首页、页面标题及其他原有引用沿用原资源 ID。
- 对应的无线、有线连接、音频、显示、主页、关于、按键、仪表、日间、权限和通知图标：保留当前项目资源 ID，使用 APK 中对应图形。
- 手机应用 Manifest 分别使用 `@mipmap/ic_launcher` 和 `@mipmap/ic_launcher_round`，普通及 UID 构建共用这些资源。

逐项来源、目的路径和 SHA-256 见 `carplay-sign-resources.json`。文件按原字节复制，没有重新绘制、压缩或给图标追加缩小边距。原包没有对应图形的图标继续使用项目现有资源。首页 Logo 原图是 512×300，现有 FIT_CENTER 行为保留。

## 范围与验证

仅替换图形资源和桌面图标引用，不导入源 APK 的代码、Manifest 权限、主题、布局或认证文件，不修改连接与方控逻辑。完整解码结果保存在仓库外的 `../extracted-carplay-sign-1.3.2/`；不要将完整解码目录加入版本管理。

可以检查 JSON 中的散列确认资源未变。桌面图标尺寸和外形还受车机启动器遮罩及缓存影响，需要重新安装后实车确认；不能据此承诺某个启动器上的显示大小。

本次验证：39 个资源的来源散列一致；AAPT2 编译和链接通过；独立资源测试确认 API 25 选择普通位图，API 29/33 选择自适应图标，后两者原生渲染及相关矢量图标加载通过。API 25 测试环境对 WebP 的绘制返回空白占位，不能据此证明旧系统实机显示，仍需设备验证。项目 CI 命令已尝试，但 Gradle wrapper 无法写入用户缓存中的 `.zip.lck`（`Operation not permitted`），未完成整包构建。
