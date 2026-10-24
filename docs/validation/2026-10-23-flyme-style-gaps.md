# 当前 DiPlay 与 Flyme Auto 的风格差异

2026-10-23，基准92ad14d2加当前未提交的控件可见性修复。参考范围仅为本地已提取的Android9/IHU623G/build537系统包，非所有Flyme Auto版本。

## 明确存在的实现差异

| 部分 | 系统资源及当前差异 |
|---|---|
| 页面结构 | 系统SettingsActivity为左导航、右内容且headerShow=false，显示页等采用直接分组。DiPlay仍有返回/标题栏和统一外卡。 |
| 普通控件表层 | 系统普通设置按钮填充日间#F2F7FC、夜间#282C31，所读常态shape无stroke。当前修复为辨识度采用旧版#DCE5F1/#1F2B3D设置控件填充及常态边框，没有保持该系统按钮样式。 |
| 选择、输入等组件 | 系统显示页使用FbButton分段组、SettingsSeekBar、SwitchPreferenceView等专门组件。DiPlay主要用普通Button、EditText、Switch及标题/数值/箭头加弹窗组合；对应外形及各状态没有逐项复刻。使用普通控件本身不是不一致的证明，当前实现与系统对应组件的具体映射仍不完整。 |
| 尺寸与圆角 | DiPlay按普通64dp/紧凑56dp、半径min(24dp,高度乘0.375)等规则绘制。系统区分普通、状态、分组等控件，并有default/sw720/sw1080/sw1600多档px资源；当前不是逐组件逐配置映射。不可直接将px数值等同dp，也尚未证明实机命中哪档资源。 |
| 文字 | 当前按紧凑/完整两档映射sp字号和行高；系统有更细的字阶/宽度覆盖。次级文字统一选择60%透明度，系统区分普通L2和fix L2等角色，没有逐处对应。字体依赖平台sans-serif映射，实际中文fallback和字重尚未实车验证。 |
| 按压动画 | 系统FlymeButton使用SpringAnimation路径；当前使用ObjectAnimator、OvershootInterpolator和项目自定时长/缩放。属于观感近似。 |
| 背景和图标 | 保留DiPlay自有图标与页面背景，没有复刻系统Settings主页的日夜车辆场景背景。这是项目外观边界，不能宣称与系统主页完全一致。 |

品牌蓝#1368FB、纯色页面日夜底色等已有资源映射依据；不能将这些局部匹配扩大为全界面对齐。

## 来源

- 系统设置骨架：工作区 analysis_flyme_style_20261019/decoded/GeelyAutoSettings/res/layout/settings_activity.xml。
- 系统显示页：同目录 fragment_display.xml。
- 普通按钮shape：同APK res/drawable/fa_bg_flyme_button_normal_setting.xml；填充由values/colors.xml与values-night/colors.xml的bg_compound_fb_button解析。
- 多档字阶/尺寸和引用链：工作区 analysis_flyme_style_20261019/style-tokens.json。旧风格提取报告描述的是当时53693213基准，不能直接当作当前代码差异表。
- 当前实现：ui-style/src/main/java/com/shilapi/xcertplay/{DiPlayPalette,FlymeAutoStyle,FlymePressFeedback}.kt，common中的DiPlayActivity与CarHotspotSettingsUi。

## 尚未完成的对照

实际车机的smallestWidthDp、字体映射、主题运行期覆盖，以及焦点、禁用态和动画效果未与系统同窗口同字体比例逐项实测。这些属于未验证项，不应宣称已经一致。当前APK截图只证明本地模拟可见性。
