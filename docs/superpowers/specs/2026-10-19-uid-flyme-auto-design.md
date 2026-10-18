# UID 检测与 Flyme Auto 风格统一

用户已确认：以当前 Flyme Auto 视觉语言统一，新增测试页 UID tab；非 system UID 打包时检查实际身份。

## UID 检测

使用只读 Android Process / PackageManager API，不改变打包身份，不申请提权。显示进程 UID、安装 UID、appId、包名、两种 UID 是否一致，分别呈现读取成功与 system 身份。appId 采用 Android 每用户 100000 UID 区间，1000 为 system；root、shell 和普通应用不视为 system。缺失数据不报告为已验证。提供刷新按钮，进入页面、返回前台时重新读取。页面选择和滚动保持；离开方控释放焦点，保持原有 MFi 取消机制。

## 风格与模块核查

- 主程序首页、设置和设置子模块：沿用现有 FlymeAutoStyle、DiPlayPalette、SettingsTheme 与 DiPlayButton；保持首页已有按钮尺寸和内边距。
- 测试工具：三个 tab 共用字号、卡片、间距与按钮；MFi 页标题统一为 28sp，与方控和 UID 页一致。
- 通用弹窗：CarDialogStyle 共享给独立 home 模块，保留原回调、选项状态和系统授权流程。
- 裁剪页：操作栏和提示卡片统一控件规范；图像编辑画布保持黑色以显示图片边界。
- home：替换独立蓝灰色；使用共享日夜配色、按钮、地图卡片、小组件容器、弹窗。小窗和短横屏整页滚动，窄屏操作纵向排列；保存编辑、应用列表与待处理组件 ID，以适应系统日夜/方向重建。
- maphost：共享标题、配色、按钮和 dp 留白，窄屏操作纵向排列；地图宽度不超出窗口；日夜/方向重建保留宽度选择。
- automotive：复用 common 主程序 UI，无独立视觉实现需要复制。
- 视频、仪表/HUD、第三方小组件内容：保留用途所需的专用绘制与 OEM 内容，应用自身控件沿用现有规范。
- 浅色主题的蓝色微调为 #1363F2，使背景上的蓝色正文达到 4.5:1 对比度；系统主题同步。

## 源码共享

ui-style/src/main/java 是 presentation 源码的唯一位置。common/home/maphost 通过 androidComponents variant sources 编译同一份源码，不引入 CarPlay 服务依赖。独立样例应用遵循系统日夜模式，主程序保留已有外观生命周期。

## 验证

UID 菜单、切页焦点释放、身份读取、日夜重建；普通/system/多用户/root/shell/缺失身份；测试工具 400/900dp、160/320dpi、1.5 字体、日夜、RTL 截图；home 400x800、800x360、1100x700、日夜、大字体、编辑和待添加组件状态恢复；maphost 窄屏/宽屏地图约束与截图；所有现有 CI gates，并区分原始 HEAD 已存在的失败。
