# 墨斐应用内悬浮宠物设计（2026-07-20）

## 背景

此前墨斐在应用内只是嵌在每个页面滚动列表里的一个小 companion（`MascotCompanion`），静态、易被忽略；真正的「悬浮」形态只有系统级 `MascotOverlayService`，而它被刻意设计成前台不可见（`MainActivity.onResume → dismissForForeground → stopSelf`），且需要 `SYSTEM_ALERT_WINDOW` 权限。结果：普通使用几乎看不到「悬浮墨斐」，也不像电子宠物。同时 `output/mofei/` 的 8 帧数字资产大部分没用上。

本次目标：在应用内提供一个**常驻、可拖拽、可点击互动、富有宠物感**的悬浮墨斐，贯穿整个界面、无需权限、一定能看到；系统级悬浮层保持更简单，并与应用内形态明确区分。

## 两种形态的区别

| 维度 | 应用内悬浮宠物 `FloatingMascot` | 系统级状态胶囊 `MascotOverlayService` |
|------|-------------------------------|--------------------------------------|
| 出现位置 | 应用内所有页面之上、导航栏之下 | 离开应用后，其他应用上层的屏幕边缘 |
| 权限 | 无需任何权限 | 需 `SYSTEM_ALERT_WINDOW` + 前台服务 |
| 视觉 | 宠物：8 帧精灵 + 情绪光晕 + 阴影 + 气泡 + 粒子庆祝 | 轻量素色胶囊（无光晕/粒子/气泡） |
| 互动 | 拖拽吸边、轻点气泡对话+快捷操作、长按菜单、空闲小动作 | 轻点展开/打开事项、长按控制、拖拽吸边 |
| 渲染入口 | `MofeiPetSprite` + `InAppMofeiAssetCatalog` | `MofeiVisual` + `MascotAssetCatalog` |

## 资产

- `output/mofei/<mood>/mofei_<mood>_f01..f08.png` 全部打包至 `apps/android/app/src/main/res/drawable-nodpi/`（`due` 目录文件名即 `mofei_due_soon_fNN`）。
- `IDLE` / `UNAVAILABLE` 复用干净的 `mofei_in_app_idle_f01..f08`（idle 原图为 ChatGPT 生成图，非干净帧）。
- `focus` / `confirm` 的应用内形态优先用更精细的 `mofei_in_app_focus_*` / `mofei_in_app_confirm_*`。

## 交互规格（`FloatingMascot`）

- **常驻容器**：`MainActivity` 的 `Scaffold` 内容槽内，`Box` 包裹 `GradientScreen`；`FloatingMascot` 作为兄弟层叠加，套用同一 `padding`（避开导航栏），故 6 个页面切换时墨斐始终悬浮。
- **拖拽 + 吸边**：`detectDragGestures` 更新 `Animatable` 偏移；松手用 `FloatingMascotController.snapDockSide` 吸到最近左右边并 spring 归位；位置持久化复用 `AppSettings.mascotDockSide` / `mascotVerticalFraction`（含既有归一化）。
- **轻点**：切换玻璃气泡对话，显示 `MascotVisuals.profileFor(state).message`；当 `state.actionCardId` 存在时提供「查看事项」（复用 `overlayNavigation` → 卡片页高亮），以及「收起」。
- **长按**：迷你菜单「本次隐藏」（置 `mascotInAppEnabled=false`）/「打开设置」。
- **空闲小动作**：`rememberInfiniteTransition` 驱动轻微上下浮动 + 微缩放；情绪光晕脉动。均受「减少动态效果」关闭并固定到静止/首帧。
- **情绪表现**：按 `MofeiPalette` 用径向渐变给宠物加光晕+柔和阴影，颜色随 `mood` 变化。
- **完成庆祝**：`MainActivity` 收集 `AppViewModel.mascotInteractions`（既有一次性 `SharedFlow`）自增 `completionSignal`，触发粒子爆发一次性动画。

## 纯逻辑与可测性

placement / 气泡方向 / 快捷操作可见性等像 `MascotOverlayController` 一样抽到 `FloatingMascotController`（无 Android 依赖），由 `FloatingMascotControllerTest` 覆盖吸边、垂直分数钳制、边缘停靠、气泡朝屏幕中心展开、`showsOpenAction`。

## 设置

- 新增 `AppSettings.mascotInAppEnabled: Boolean = true`（键 `mascot_in_app_enabled`），默认开启，保证首次运行即可见。
- 设置页「墨斐悬浮助手」卡片：新增「在应用内显示墨斐宠物」开关；文案区分应用内宠物与系统级状态胶囊。
- 继续复用「减少墨斐动态效果」。

## 降级与异常

- 关闭「在应用内显示墨斐宠物」→ 悬浮层从合成树移除。
- 「减少动态效果」→ 精灵固定首帧、无浮动/光晕脉动/粒子，但保留颜色、气泡与点击行为。
- 资源缺失由 Android 构建直接失败，避免发布不完整动画。

## 验收

- `:app:testDebugUnitTest`：`MascotAssetCatalogTest`（8 帧×8 情绪）、`MascotPreferencesTest`（`mascotInAppEnabled` 持久化/默认）、`FloatingMascotControllerTest`（placement/气泡/快捷操作）。
- `:app:assembleDebug`：确认 35 张新增帧与新增 Compose 均可编译打包。
- 运行：启动即在任意页面看到悬浮墨斐（无需权限）；切页仍在；轻点弹气泡、「查看事项」跳转高亮；拖拽吸边并在重启后保持；制造 urgent/complete 数据验证变色与庆祝；系统级开启后退到后台看到素色胶囊。

## 页面级融入（2026-07-20 追加）

除常驻悬浮宠物外，墨斐还以统一的情绪面板融入每个页面的既有布局，替换此前各页手写的 companion 行与无角色的空状态。新增共享组件 `mascot/MofeiMoodPanels.kt`：

- `MofeiMoodBanner`：紧凑情绪条（精灵 + 光晕 + 「墨斐」名 + 情绪文案），情绪色随 `MascotState` 渐变着色。用于 Home/Import/Preview/Cards/Calendar/Settings 的行内位置。
- `MofeiMoodHero`：大号居中英雄区（精灵 + 光晕 + 标题 + 文案 + 页面动作），用于空状态。

各页集成：

- **Home**：companion 行 → `MofeiMoodBanner`；空状态 `EmptyHomeCard` → `MofeiMoodHero`（导入按钮）。
- **Import**：加载态 Mofei → 常驻 `MofeiMoodBanner`（加载时显示 SCAN 文案，空闲时邀请首张截图）。
- **Preview**：companion 行 → `MofeiMoodBanner`；空态 `EmptyPreviewCard` → `MofeiMoodHero`（重新导入 / 手动添加）。
- **Cards**：完成态 companion → `MofeiMoodBanner`（保留 `mood == COMPLETE` 门控）；空态 → `MofeiMoodHero`。
- **Calendar**：新增 `mascotState` 参数（`MainActivity` 接线）；顶部 `MofeiMoodBanner`；空日程卡内嵌小号 `MofeiPetSprite` + 放松文案。
- **Settings**：新增 `mascotState` 参数；「墨斐悬浮助手」卡片顶部加 `MofeiMoodBanner` **实时预览**，随事项与「减少动态效果」开关变化。

所有面板复用 `MascotVisuals.profileFor` 的颜色与文案、`MofeiPetSprite` 的 8 帧精灵，并遵守 reduce-motion。`assembleDebug` 与 `testDebugUnitTest` 通过。
