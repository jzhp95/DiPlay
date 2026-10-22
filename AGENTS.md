# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## 目标车型与车型修复范围

本项目的目标车型为吉利星瑞 2026 款昆仑，目标车机为其 Flyme Auto（Android 9／API 28）环境。

- 其他车型专属的适配、功能与修复不合并，包括其他吉利车型；不得仅因同属吉利、使用 Flyme Auto 或平台标识相似，就认定适用于目标车型。
- 由其他车型问题触发的通用协议、网络、音频或生命周期修复，应核对问题机制与执行路径；仅在对目标车型适用时移植通用部分，不引入其他车型专属逻辑。
- 同一上游提交同时包含通用修复与其他车型专属改动时，必须拆分评估，仅移植目标车型需要的部分；无法确认适用性的车型专属改动不合并。
- 此准则用于约束后续开发与合并范围，不要求主动删除现有其他车型代码。
- BYD／DiLink 专属适配已明确要求全部移除；不得恢复其厂商接口、私有按键、HUD／仪表路由、车辆数据及氛围灯实现。通用 CarPlay 协议逻辑须保持独立。

## 目标车机与 Android 兼容性

本项目实际适配的 Flyme Auto 车机运行 Android 9（API 28），开发、修复和上游代码合并均须以该运行环境为兼容性基准。

- 不得默认目标车机运行 Android 10／11 或更高版本；使用高于 API 28 的平台接口时，必须提供版本判断和适用于 Android 9 的实现或明确降级。
- 网络连接与断线检测、蓝牙、Wi-Fi／热点、USB、音频模式、权限及后台生命周期等行为，必须检查 Android 9 上的执行路径，不能仅凭较新 Android 版本的行为判断兼容性。
- Android 9（API 28）是本项目的兼容性下限。仅为 Android 9 以下版本（Android 8.1／API 27 及更低版本）提供兼容的上游补丁不合并，也不为这些版本新增兼容逻辑；已明确要求清理 Android 9 以下兼容实现；主应用、共享模块、Flyme EAS 和原生代码的最低版本统一为 API 28，禁止重新引入更低版本的兼容分支。
- 同时覆盖 Android 8／9 等多个版本的修复，应按其对 Android 9 的实际作用评估；覆盖 Android 9 的稳定性与兼容性修复仍可合并，仅移植目标环境需要的逻辑。
- 验证涉及平台差异的修改时，应覆盖 API 28 的相关路径；模拟测试与较新系统上的结果不能替代 Android 9 实车验证，未完成实车验证时须如实说明。

## 方控适配准则（Flyme 优先）

所有涉及方向盘控制（方控）的开发、修复、重构和上游代码合并，必须以本项目现有的 Flyme 适配为准。

其他吉利／领克车型的方控服务绑定、私有按键订阅及 AIDL 已明确要求删除；仅保留现有 Flyme Auto EAS 厂商方控接入，不得恢复 `com.geely.inputservice` 的并行通路。通用 Android 媒体事件与诊断继续遵守 EAS 的归属及去重约束。

- 现有适配入口为 `common/src/main/java/com/shilapi/xcertplay/FlymeWheelIntegration.kt`，底层协议与焦点生命周期由 `integrations/flyme-eas/` 管理；相关实现应沿用这套职责划分。
- Siri／语音、播放／暂停、上一曲／下一曲等方控行为，以及相关设置、按键学习和测试工具，均须与现有 Flyme 适配保持一致。
- 必须保留现有按键映射、事件去重、活动会话归属、通话期间处理、焦点获取与释放及连接／断开生命周期约束，避免重复响应或向失效会话发送按键。
- 合并上游涉及方控、媒体按键或语音键的改动时，必须先核对与 Flyme 适配的交互；有冲突时以现有 Flyme 适配为准，按需移植兼容的修复，不得直接覆盖或恢复其他车型的方控实现。
- 新增方控能力应在现有 Flyme 适配机制内扩展，不得另建绕过该机制的并行按键处理通路；修改后须验证相关 Flyme 方控与会话生命周期行为。

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, generic CarPlay navigation |
| Vehicle | how CarPlay fits this car and its driver | driving side, Flyme wheel keys (Siri and media controls), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
UI copy uses Simplified Chinese; use `（实验性）` for the suffix.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string in Simplified Chinese to `common/src/main/res/values/`.
   New Settings copy SHOULD use the `settings_` prefix. Do not reintroduce language-specific resource folders; `SettingsTranslationsTest` guards the Chinese-only resource policy.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings and About. The app language is fixed to Simplified Chinese; do not add a language picker.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test a menu or panel on a narrow window and on a high-density screen.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every `SettingsCategory` except Overview MUST have a link on Overview.
   `AdaptiveSettingsUiTest.overviewLinksToEveryOtherCategory` checks this.
5. A layout change MUST include compact and full screenshots with a large font scale.
   Add an Arabic (right-to-left) screenshot when the change touches row alignment.
