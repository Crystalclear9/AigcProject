# 墨斐运行时资产接入 Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 Android 应用和系统悬浮层中使用 gpt-image-2 生成的墨斐帧资产，而非 Canvas 静态占位视觉。

**Architecture:** 将 24 张原生 PNG 复制到 Android `drawable-nodpi`。`MascotAssetCatalog` 以 `MascotMood` 映射三帧序列，Compose 播放器在状态变化后从首帧开始循环播放；系统悬浮层复用该播放器和已有的服务、权限、拖拽与边缘吸附机制。

**Tech Stack:** Kotlin、Jetpack Compose、Android `painterResource`、前台 `TYPE_APPLICATION_OVERLAY` 服务、JUnit 4。

---

### Task 1: 打包帧资源与目录映射

**Files:**
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_*.png`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotVisuals.kt`
- Test: `apps/android/app/src/test/java/com/suishouban/app/mascot/MascotAssetCatalogTest.kt`

**Step 1:** 写入失败测试，验证每个支持的 mood 返回三张帧资源，`UNAVAILABLE` 退回 `idle`。

**Step 2:** 复制 24 张未经缩放的 gpt-image-2 PNG，按 Android 资源命名约束重命名。

**Step 3:** 实现纯资源目录映射并运行目标测试。

### Task 2: 替换静态 Canvas 渲染

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotVisuals.kt`
- Test: `apps/android/app/src/test/java/com/suishouban/app/mascot/MascotAssetCatalogTest.kt`

**Step 1:** 写入失败测试，验证动画状态不使用单帧、减少动态效果固定停留首帧。

**Step 2:** 用 `painterResource` 和固定时间帧切换实现 `MofeiVisual`；保持 content description 与状态文案。

**Step 3:** 构建 Debug APK，确认 Canvas 占位不再作为主视觉路径。

### Task 3: 验证悬浮宠物闭环

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ui/screens/SettingsScreen.kt`
- Test: `apps/android/app/src/test/java/com/suishouban/app/mascot/MascotOverlayControllerTest.kt`

**Step 1:** 授权返回后持久化开关并立即显示清晰的后台悬浮状态。

**Step 2:** 保持前台隐藏、后台恢复的已有安全策略；不绕过系统 `SYSTEM_ALERT_WINDOW` 授权。

**Step 3:** 在真机验证：设置授权、退出应用、可见悬浮宠物、拖拽吸附、点击展开与回到应用。

