# 墨斐吉祥物 Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add the approved v6 Mofei mascot as an in-app status companion and opt-in Android edge overlay.

**Architecture:** A pure resolver maps `AppUiState` to one ordered mascot state. Compose and `WindowManager` render the shared state; preferences own opt-in and placement, while an explicit foreground service owns the overlay after special permission is granted.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android `WindowManager`, foreground service, SharedPreferences, JUnit 4, Paigod `gpt-image-2` assets.

---

### Task 1: Add the mascot state model and resolver

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotState.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotStateResolver.kt`
- Create: `apps/android/app/src/test/java/com/suishouban/app/mascot/MascotStateResolverTest.kt`

**Step 1: Write failing tests.** Cover urgent deadline, due-soon card, draft confirmation, active workflow, completion feedback, and idle. Inject a clock so deadline tests are deterministic.

```kotlin
@Test fun urgentDeadlineOutranksPendingDraft() {
    assertEquals(MascotMood.URGENT, resolver.resolve(input(cardDueIn(45), draftCount = 1)).mood)
}
```

**Step 2: Verify failure.** Run `cd apps/android; .\gradlew.bat testDebugUnitTest --tests "com.suishouban.app.mascot.MascotStateResolverTest" --no-daemon`. Expected: missing resolver.

**Step 3: Implement.** Define `IDLE`, `FOCUS`, `CONFIRM`, `REMINDER`, `DUE_SOON`, `URGENT`, `COMPLETE`, `REST`, and `UNAVAILABLE`. Return mood, optional action-card id, message, color role, and animation hint. Preserve priority: urgent, due soon, draft, workflow, completion, idle.

**Step 4: Verify pass.** Re-run the focused test.

**Step 5: Commit.** Run `git add apps/android/app/src/main/java/com/suishouban/app/mascot apps/android/app/src/test/java/com/suishouban/app/mascot` and `git commit -m "feat: add mascot state resolver"`.

### Task 2: Persist overlay preferences

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/data/repository/AppSettingsRepository.kt`
- Create: `apps/android/app/src/test/java/com/suishouban/app/data/repository/MascotPreferencesTest.kt`

**Step 1: Write failing tests.** Assert default disabled overlay, right-side docking, midpoint vertical placement, no temporary hide, and animations enabled.

**Step 2: Verify failure.** Run `cd apps/android; .\gradlew.bat testDebugUnitTest --tests "com.suishouban.app.data.repository.MascotPreferencesTest" --no-daemon`.

**Step 3: Implement.** Extend `AppSettings` and SharedPreferences load/update with `mascotOverlayEnabled`, `mascotHiddenUntilMillis`, `mascotDockSide`, `mascotVerticalFraction`, and `reduceMascotMotion`. Clamp the fraction to `[0.1f, 0.9f]` before storage.

**Step 4: Verify pass.** Re-run the focused test.

**Step 5: Commit.** Run `git add apps/android/app/src/main/java/com/suishouban/app/data/repository/AppSettingsRepository.kt apps/android/app/src/test/java/com/suishouban/app/data/repository/MascotPreferencesTest.kt` and `git commit -m "feat: persist mascot overlay preferences"`.

### Task 3: Create approved assets and Compose renderer

**Files:**
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_*.webp`
- Create: `apps/android/app/src/main/java/com/suishouban/app/ui/components/MascotCompanion.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/ui/components/MascotVisuals.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ui/theme/Color.kt`

**Step 1: Produce runtime frames.** Use corrected `paigod_imagegen.py` with `gpt-image-2` to derive idle, focus, confirm, reminder, urgent, complete, unavailable, and half-visible edge-dock frames from `output/imagegen/mofei-mascot-concept-v6.png`. Validate alpha and encode bounded WebP files. Do not use the concept sheet directly at runtime.

**Step 2: Write a failing Compose test.** Render `MascotCompanion` in `URGENT` state and assert its content description and alert message.

**Step 3: Implement.** Use fixed `Box` dimensions, drawable fallback, and Compose-drawn orbit and halo. Map each mood to the approved state color and disable infinite motion when `reduceMascotMotion` is true.

```kotlin
Box(Modifier.size(size).semantics { contentDescription = visual.a11yLabel }) {
    Image(painterResource(visual.drawableRes), contentDescription = null)
    MascotOrbit(color = visual.accent, animate = !reduceMotion)
}
```

**Step 4: Verify renderer.** Run `cd apps/android; .\gradlew.bat connectedDebugAndroidTest --no-daemon` on an emulator or device.

**Step 5: Commit.** Run `git add apps/android/app/src/main/res/drawable-nodpi apps/android/app/src/main/java/com/suishouban/app/ui/components apps/android/app/src/main/java/com/suishouban/app/ui/theme/Color.kt` and `git commit -m "feat: add Mofei visual assets and renderer"`.

### Task 4: Implement the opt-in edge overlay

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayService.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayController.kt`
- Create: `apps/android/app/src/test/java/com/suishouban/app/mascot/MascotOverlayControllerTest.kt`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/SuiShouBanApp.kt`

**Step 1: Write failing controller tests.** Test placement clamping, edge snapping, temporary hide, and card-navigation intent selection behind a `WindowManager` interface.

**Step 2: Verify failure.** Run `cd apps/android; .\gradlew.bat testDebugUnitTest --tests "com.suishouban.app.mascot.MascotOverlayControllerTest" --no-daemon`.

**Step 3: Implement.** Declare `SYSTEM_ALERT_WINDOW`, the correct Android 14+ foreground-service type/permission, and a visible notification. Start only after foreground user action and `Settings.canDrawOverlays` confirmation. Attach a 44dp x 88dp collapsed view revealing 24dp; expand to 156dp x 112dp; snap after drag; handle double tap, long press, hide, and explicit stop without self-restart.

**Step 4: Verify.** Run the unit test, then manually verify permission denial/grant, drag, expansion, temporary hide, stop, and notification on Android 14+.

**Step 5: Commit.** Run `git add apps/android/app/src/main/java/com/suishouban/app/mascot apps/android/app/src/main/AndroidManifest.xml apps/android/app/src/main/java/com/suishouban/app/SuiShouBanApp.kt` and `git commit -m "feat: add opt-in Mofei edge overlay"`.

### Task 5: Connect state, settings, lifecycle, and in-app surfaces

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/AppViewModel.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ui/screens/SettingsScreen.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ui/screens/HomeScreen.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ui/screens/PreviewScreen.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ui/screens/CardsScreen.kt`

**Step 1: Write failing state-publication tests.** Verify changes to cards, drafts, workflow state, and completion feedback update `mascotState`, while completion cannot permanently override urgent work.

**Step 2: Verify failure.** Run `cd apps/android; .\gradlew.bat testDebugUnitTest --tests "com.suishouban.app.mascot.*" --no-daemon`.

**Step 3: Implement.** Publish `mascotState` plus a one-shot interaction event from `AppViewModel`. In `MainActivity`, request overlay permission only from Settings, route overlay actions to the target screen, hide the system overlay in foreground, and restore it only when enabled. Render `MascotCompanion` in home, recognition, confirmation, and completion contexts without covering controls.

**Step 4: Verify app tests.** Run `cd apps/android; .\gradlew.bat testDebugUnitTest connectedDebugAndroidTest --no-daemon`.

**Step 5: Commit.** Run `git add apps/android/app/src/main/java/com/suishouban/app/AppViewModel.kt apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt apps/android/app/src/main/java/com/suishouban/app/ui/screens` and `git commit -m "feat: integrate Mofei across app workflows"`.

### Task 6: Package and validate

**Files:**
- Create: `docs/guides/MOFEI_MASCOT_TESTING.md`
- Modify: `README.md` only if its existing user change is intentionally integrated; otherwise leave it untouched.

**Step 1: Add acceptance coverage.** Document permission rejection/grant, Android 14+ service notification, edge-bubble handoff, duplicate-alert suppression, urgent color, reduced motion, TalkBack, asset fallback, and process death.

**Step 2: Build and test.** Run `cd apps/android; .\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`. Expected: unit tests pass and `app/build/outputs/apk/debug/app-debug.apk` exists.

**Step 3: Validate and commit.** Install with `adb install -r`, execute the checklist, inspect logcat for overlay failures, then run `git add docs/guides/MOFEI_MASCOT_TESTING.md` and `git commit -m "docs: add Mofei mascot validation guide"`.
