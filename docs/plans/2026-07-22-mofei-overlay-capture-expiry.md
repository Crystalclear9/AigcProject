# Mofei Overlay Capture and Expiry Fix Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep one reliable external screenshot action, prevent the app task and duplicate Mofei from appearing during capture, and let the next eligible task drive Mofei after a deadline expires.

**Architecture:** Keep the existing MediaProjection and screenshot-preview pipeline, but launch its transparent consent Activity in an isolated task excluded from recents and keep overlay restoration owned by the capture/preview lifecycle. Restrict the overlay catalog at the pure coordinator boundary. Filter expired timed cards before existing urgency and priority selection.

**Tech Stack:** Kotlin, Android Activity/Service/MediaProjection, Jetpack Compose, JUnit 4, Gradle Android plugin.

---

### Task 1: Restrict the external action catalog

**Files:**
- Modify: `apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiActionCoordinatorTest.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiActionCoordinator.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiActionRing.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayService.kt`

**Step 1: Write the failing test**

Change the overlay catalog expectation to:

```kotlin
assertEquals(
    listOf(
        MofeiAction.CAPTURE_CURRENT_SCREEN,
        MofeiAction.REVIEW_NOTIFICATION_DRAFTS,
        MofeiAction.OPEN_CURRENT_CARD,
    ),
    actions.map { it.action },
)
```

**Step 2: Run the focused test and verify failure**

Run from `apps/android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.suishouban.app.mascot.action.MofeiActionCoordinatorTest"
```

Expected: the overlay catalog test fails because `ANALYZE_LATEST_SCREENSHOT` and `OPEN_SETTINGS` are still present.

**Step 3: Implement the catalog and labels**

Set `OVERLAY_ACTIONS` to the three approved actions. Change the visible and fallback label of `CAPTURE_CURRENT_SCREEN` to `截屏`; keep the in-app catalog otherwise unchanged.

**Step 4: Run the focused test and verify pass**

Run the command from Step 2. Expected: all coordinator tests pass.

### Task 2: Skip expired cards and select the next eligible task

**Files:**
- Modify: `apps/android/app/src/test/java/com/suishouban/app/mascot/MascotStateResolverTest.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotStateResolver.kt`

**Step 1: Replace the obsolete overdue expectation with failing behavior tests**

Add a test where an expired high-priority card and two future cards are supplied. Assert that the expired card is ignored and the highest-priority future candidate owns `actionCardId`. Add a second test asserting that a lone expired card yields `IDLE`.

```kotlin
assertEquals("future-high", state.actionCardId)
assertEquals(MascotMood.REMINDER, state.mood)
```

**Step 2: Run the focused test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.suishouban.app.mascot.MascotStateResolverTest"
```

Expected: the resolver selects the expired card as `URGENT`.

**Step 3: Implement the minimal eligibility filter**

Build `datedCards` only when `deadline >= now`:

```kotlin
parseDeadline(card.deadline)
    ?.takeUnless { it.isBefore(now) }
    ?.let { deadline -> TimedCard(card, deadline) }
```

Keep exact-now deadlines eligible and retain the existing urgency, priority, deadline, and ID ordering.

**Step 4: Run the focused test and verify pass**

Run the command from Step 2. Expected: all resolver tests pass.

### Task 3: Isolate the MediaProjection consent task

**Files:**
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/capture/MofeiScreenCaptureActivity.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayService.kt`

**Step 1: Add isolated-task launch properties**

Declare the capture Activity with an empty task affinity, exclusion from recents, and a translucent theme. Do not use `noHistory`, because the Activity must survive the system consent screen and receive its result:

```xml
android:excludeFromRecents="true"
android:taskAffinity=""
```

Add `FLAG_ACTIVITY_NEW_TASK`, `FLAG_ACTIVITY_MULTIPLE_TASK`, and `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` in the capture intent factory. Comment why these flags preserve the external app below the transparent consent flow. Explicitly remove the temporary task on cancellation or failure; on success, retain it only for the preview Activity.

**Step 2: Harden overlay restoration ownership**

Keep the overlay removed before launching capture. Ensure successful capture delegates restoration to `ScreenshotPreviewActivity`; cancellation and failure restore once. Do not route the action through `MainActivity`.

**Step 3: Verify merged manifest and compilation**

```powershell
.\gradlew.bat :app:processDebugMainManifest :app:compileDebugKotlin
Select-String -LiteralPath '.\app\build\intermediates\merged_manifests\debug\processDebugManifest\AndroidManifest.xml' -Pattern 'MofeiScreenCaptureActivity|taskAffinity|noHistory'
```

Expected: Gradle succeeds and the merged capture Activity contains the isolated-task attributes.

### Task 4: Regression verification and APK build

**Files:**
- Verify only; no planned source changes.

**Step 1: Run the full Android unit suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` and XML test reports contain no failures or errors.

**Step 2: Build a fresh Debug APK**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `apps/android/app/build/outputs/apk/debug/app-debug.apk` has a timestamp from this run.

**Step 3: Inspect the final diff**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors and only intended source, test, manifest, and plan files are changed.

**Step 4: Install when the configured device is online**

```powershell
adb devices -l
adb -s 10AFA30A7Z002Q5 install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s 10AFA30A7Z002Q5 shell dumpsys package com.suishouban.app
```

Expected: install reports `Success`; package information and process launch are verified. If the device is absent or the user rejects USB installation, report that external blocker without claiming device validation.
