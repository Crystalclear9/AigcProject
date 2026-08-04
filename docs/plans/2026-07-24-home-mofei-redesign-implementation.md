# Mofei Home Redesign Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rebuild the Android Compose home screen around three image2-generated Mofei scenes while preserving all existing home data, imports, card actions, routes, and accessibility behavior.

**Architecture:** Keep `HomeScreen`'s public API and `AppUiState` calculations unchanged. Add a home-only mascot component that owns asset selection and reduced-motion behavior, then compose it into responsive hero, status, and empty-state cards. Restyle the existing `NavigationBar` without changing the route model.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android resources, image2-generated PNG assets, Gradle/JUnit.

---

### Task 1: Generate and validate the three Mofei assets

**Files:**
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_home_hero.png`
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_home_status.png`
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_home_empty.png`
- Test: `apps/android/app/src/test/java/com/suishouban/app/ui/screens/HomeMofeiAssetsTest.kt`

**Step 1: Generate each source image**

Use the supplied mascot mother image as the identity reference. Generate each variant separately with a flat `#00ff00` chroma-key background. Repeat the no-limbs and five-anchor constraints in every prompt.

**Step 2: Remove the chroma key**

Run the installed imagegen helper for every source:

```powershell
python C:\Users\lenovo\.codex\skills\.system\imagegen\scripts\remove_chroma_key.py `
  --input <source> `
  --out <target> `
  --auto-key border `
  --soft-matte `
  --transparent-threshold 12 `
  --opaque-threshold 220 `
  --despill
```

**Step 3: Validate the output**

Confirm with Pillow that every final PNG is RGBA, all four corners are transparent, subject coverage is neither empty nor full-frame, and no green fringe dominates the boundary.

**Step 4: Write the resource test**

Add a JVM test that references all three generated `R.drawable` IDs and asserts each ID is nonzero. This makes missing or renamed resources a compile/test failure.

**Step 5: Run the focused test**

Run:

```powershell
cd apps\android
.\gradlew.bat :app:testDebugUnitTest --tests com.suishouban.app.ui.screens.HomeMofeiAssetsTest
```

Expected: the focused test passes with zero failures.

### Task 2: Add the home-only mascot component

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/ui/components/HomeMofei.kt`

**Step 1: Define explicit variants**

Create `HomeMofeiVariant` with `HERO`, `STATUS`, and `EMPTY`. Map each variant to its resource, semantic label, resting rotation, and maximum bob distance.

**Step 2: Implement reduced-motion-aware rendering**

Build a composable using `Image`, `ContentScale.Fit`, `rememberInfiniteTransition`, and `graphicsLayer`. When `reduceMotion` is true, render the image at its resting transform with no infinite animation.

**Step 3: Add necessary comments**

Comment the identity invariant and the reason the component is home-only, so it is not accidentally substituted for the interactive floating mascot.

**Step 4: Compile the component**

Run:

```powershell
cd apps\android
.\gradlew.bat :app:compileDebugKotlin
```

Expected: Kotlin compilation exits successfully.

### Task 3: Recompose the home cards

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ui/screens/HomeScreen.kt:58`

**Step 1: Preserve the existing data calculations**

Keep `activeCards`, `urgentCards`, `needConfirm`, `reminders`, `timedCards`, engine labels, workflow status, import dialog callbacks, completion callbacks, and card navigation unchanged.

**Step 2: Replace the hero layout**

Use `BoxWithConstraints` and layered boxes. Keep all required text and the import button interactive. Replace pills with connected step nodes and place the hero mascot at the right/bottom with responsive sizing.

**Step 3: Replace the status card layout**

Keep three equal-weight metrics. Add the compact status mascot in the title region and ensure its measured bounds never overlap the metric values at 320dp.

**Step 4: Replace the empty-state card**

Use a weighted text column and a constrained visual region containing the empty mascot and decorative translucent candidate cards. Do not change the import callback.

**Step 5: Apply the reduced-motion setting**

Pass `state.settings.reduceMascotMotion` to every `HomeMofei` instance.

**Step 6: Compile**

Run:

```powershell
cd apps\android
.\gradlew.bat :app:compileDebugKotlin
```

Expected: Kotlin compilation exits successfully.

### Task 4: Refine the bottom navigation

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt:150`

**Step 1: Restyle the container**

Set a white, lightly translucent navigation container and a subtle top tonal/elevation treatment while retaining `NavigationBar`.

**Step 2: Restyle item states**

Set the selected icon/text to the brand blue, unselected content to gray-blue, and the indicator to a small light-blue rounded treatment. Keep `current == screen.route` and every `onClick` unchanged.

**Step 3: Build**

Run:

```powershell
cd apps\android
.\gradlew.bat :app:assembleDebug
```

Expected: `app/build/outputs/apk/debug/app-debug.apk` is produced.

### Task 5: Verify the UI and behavior

**Files:**
- Modify only if validation exposes a defect.

**Step 1: Run all JVM tests**

```powershell
cd apps\android
.\gradlew.bat :app:testDebugUnitTest
```

Expected: all reported tests pass with zero failures.

**Step 2: Install the fresh APK**

Use the connected-device workflow:

```powershell
adb devices -l
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell am start -n com.suishouban.app/.MainActivity
```

**Step 3: Capture and inspect the rendered home**

Capture the device screenshot and inspect:

- no limbs on any mascot;
- hero text/button unobstructed;
- status metrics readable;
- empty card does not become a centered poster;
- bottom content and navigation do not overlap;
- no horizontal clipping at the connected device width.

**Step 4: Verify navigation and import entry**

Use UI automation or device interaction to open the import source dialog and switch through the five bottom destinations, then return to Today.

### Task 6: Consolidate onto feature-yls

**Files:**
- No new source files.

**Step 1: Review and commit only relevant changes**

Stage the generated final assets, home component, home screen, navigation styling, tests, and the two plan documents. Do not stage `.codegraph`, prior screenshots, generated prompt metadata, or unrelated `output/` files.

**Step 2: Fast-forward feature-yls**

Because `feature-yls` is currently an ancestor of this branch, switch to `feature-yls` and fast-forward it to the verified implementation commit.

**Step 3: Push and verify**

Push `feature-yls`, then compare local `HEAD` with:

```powershell
git ls-remote --heads origin feature-yls
```

Expected: the remote hash exactly matches local `HEAD`.

**Step 4: Audit feature branches before deletion**

List local/remote feature branches, their worktrees, merged state, and unique commits. Delete only branches whose commits are reachable from the pushed `feature-yls` and whose worktrees contain no uncommitted changes. Report any branch that cannot be safely removed.
