# Mofei Side Arc Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the oversized circular action ring with a draggable edge-anchored semicircle and make the cross-app Mofei overlay survive background startup.

**Architecture:** A pure `MofeiSideArcGeometry` owns left/right mirrored action positions so both in-app Compose and the WindowManager overlay share one compact layout. The system overlay installs lifecycle, ViewModelStore, and saved-state owners on the actual WindowManager root before attaching its ComposeView.

**Tech Stack:** Kotlin, Jetpack Compose, Android WindowManager overlay, Lifecycle/SavedState view-tree owners, JUnit 4, Compose UI test, Android instrumentation, ADB.

---

### Task 1: Define compact semicircle geometry

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiSideArcGeometry.kt`
- Modify: `apps/android/app/src/test/java/com/suishouban/app/mascot/MascotOverlayControllerTest.kt`
- Create: `apps/android/app/src/test/java/com/suishouban/app/mascot/MofeiSideArcGeometryTest.kt`

**Step 1: Write failing geometry tests**

Test that seven points fit within a 176×276dp container, left and right points mirror around the vertical axis, and expanded overlay dimensions are 176×276 rather than 284×284.

**Step 2: Verify RED**

Run:

```powershell
cd apps\android
.\gradlew.bat testDebugUnitTest --tests "*MofeiSideArcGeometryTest" --tests "*MascotOverlayControllerTest" --no-daemon --console=plain
```

Expected: compilation failure for missing geometry or assertion failure for old expanded dimensions.

**Step 3: Implement minimal geometry**

Create constants `WIDTH_DP = 176`, `HEIGHT_DP = 276`, `ACTION_SIZE_DP = 48`, an `ArcPoint`, and `positions(dockSide, count)` using a 150-degree inward arc centered on the docked edge. Change `MascotOverlayController` expanded dimensions to the shared constants.

**Step 4: Verify GREEN**

Run the Task 1 command. Expected: PASS.

**Step 5: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiSideArcGeometry.kt apps/android/app/src/test/java/com/suishouban/app/mascot
git commit -m "feat: define compact Mofei side arc"
```

### Task 2: Render the semicircle instead of the full ring

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiActionRing.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/FloatingMascot.kt`
- Modify: `apps/android/app/src/androidTest/java/com/suishouban/app/mascot/MofeiActionRingTest.kt`

**Step 1: Write failing Compose tests**

Require the expanded action center to expose `mofei-side-arc`, seven action nodes, a right/left dock semantic marker, and no always-visible action label or global permission sentence.

**Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin --no-daemon --console=plain
```

Expected: failure because the side-arc tag/dock API does not exist.

**Step 3: Implement minimal Compose layout**

Replace the full PNG ring with two translucent `Canvas` arc strokes and small node glows. Place icon-only 48dp actions with `MofeiSideArcGeometry`; retain content descriptions, callbacks, badges, and a 16dp permission seal. Add explicit `dockSide` and position the arc beside the live Mofei in `FloatingMascot`.

**Step 4: Verify GREEN**

Run the Task 2 command. Expected: Android tests compile.

**Step 5: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/mascot apps/android/app/src/androidTest/java/com/suishouban/app/mascot/MofeiActionRingTest.kt
git commit -m "feat: render Mofei side arc"
```

### Task 3: Install owners on the actual overlay root

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiOverlayViewTreeOwners.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayService.kt`
- Create: `apps/android/app/src/androidTest/java/com/suishouban/app/mascot/MofeiOverlayViewTreeOwnersTest.kt`

**Step 1: Write failing owner test**

Using `MainActivity` as the three owner interfaces, install owners on a detached `FrameLayout` and assert `findViewTreeLifecycleOwner`, `findViewTreeViewModelStoreOwner`, and `findViewTreeSavedStateRegistryOwner` all return the activity.

**Step 2: Verify RED**

Run `compileDebugAndroidTestKotlin`. Expected: missing installer symbol.

**Step 3: Implement the root installer and service wiring**

Add a single `install(root, lifecycleOwner, viewModelStoreOwner, savedStateRegistryOwner)` function. Call it on the root `FrameLayout` before adding the ComposeView and before `windowManager.addView`. Remove owner installation from the child-only path. Align expanded overlay Mofei to the docked edge center and pass the same dock side to the arc.

**Step 4: Verify GREEN**

Run `compileDebugAndroidTestKotlin`. Expected: PASS.

**Step 5: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/mascot apps/android/app/src/androidTest/java/com/suishouban/app/mascot/MofeiOverlayViewTreeOwnersTest.kt
git commit -m "fix: attach owners to Mofei overlay root"
```

### Task 4: Full build and real-device verification

**Files:**
- Modify: `docs/reports/2026-07-20-mofei-action-center-verification.md`

**Step 1: Run clean verification**

```powershell
cd apps\android
.\gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon --console=plain
```

Expected: all tasks pass.

**Step 2: Install and run instrumentation**

```powershell
adb -s 10AFA30A7Z002Q5 install -r app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat connectedDebugAndroidTest --no-daemon --console=plain
```

Expected: install success and instrumentation pass.

**Step 3: Verify foreground/background behavior**

Clear logcat, launch the app, press Home, and verify `MascotOverlayService` remains a foreground service with an overlay window and no `ViewTreeLifecycleOwner` crash. Capture a device screenshot showing the edge Mofei; reopen the app and verify the system overlay is dismissed.

**Step 4: Update evidence**

Record test count, APK SHA-256, device model, service state, crash-log absence, and screenshot paths in the verification report.

**Step 5: Commit**

```powershell
git add docs/reports/2026-07-20-mofei-action-center-verification.md
git commit -m "test: verify Mofei side arc on device"
```
