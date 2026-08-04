# Mofei External Capture Actions Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the external Mofei ring expose practical capture actions. Screenshot must capture the current display and enter the existing OCR preview/save workflow without MediaProjection or a screen-sharing consent dialog.

**Architecture:** Keep the existing action model and preview workflow. The overlay service handles screenshots directly through a dedicated AccessibilityService, removes its own window before capture, writes the bitmap to a private FileProvider URI, and opens ScreenshotPreviewActivity. Camera and gallery reuse the existing activity-result launchers. Pure routing and catalog rules use JVM tests; Android integration uses lint, unit tests, APK installation, and real-device evidence.

**Tech Stack:** Kotlin, Android SDK, Jetpack Compose, AccessibilityService screenshot API (API 30+), JUnit, Gradle, ADB.

---

### Task 1: Define the external action catalog

**Files:**
- Modify: apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiActionCoordinator.kt
- Modify: apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiActionCoordinatorTest.kt

**Step 1: Add failing tests**

Cover these rules:
- CAPTURE_CURRENT_SCREEN, TAKE_PHOTO, and PICK_IMAGE are always present in that order.
- OPEN_CURRENT_CARD appears only when a valid action-card ID exists.
- REVIEW_NOTIFICATION_DRAFTS appears only when the pending count is positive and shows that count.
- In-app actions retain their existing catalog.

**Step 2: Run the focused test and confirm failure**

    rtk .\gradlew.bat :app:testDebugUnitTest --tests "com.suishouban.app.mascot.action.MofeiActionCoordinatorTest"

Expected: failure because the current external catalog omits camera/gallery and always includes contextual actions.

**Step 3: Implement the minimal catalog change**

Add an explicit current-card availability field to MofeiCapabilityState and build the overlay list from the three fixed capture actions plus conditional contextual actions. Keep capability availability separate from action visibility.

**Step 4: Re-run the focused test**

Expected: all MofeiActionCoordinatorTest cases pass.

**Step 5: Commit**

    git add apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiActionCoordinator.kt apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiActionCoordinatorTest.kt
    git commit -m "feat: expand external Mofei actions"

### Task 2: Add an accessibility screenshot service

**Files:**
- Create: apps/android/app/src/main/java/com/suishouban/app/capture/MofeiScreenshotAccessibilityService.kt
- Create: apps/android/app/src/main/java/com/suishouban/app/capture/AccessibilityScreenshotWriter.kt
- Create: apps/android/app/src/main/res/xml/mofei_screenshot_accessibility_service.xml
- Modify: apps/android/app/src/main/AndroidManifest.xml
- Modify: apps/android/app/src/main/res/values/strings.xml
- Modify: apps/android/app/src/main/java/com/suishouban/app/data/ScreenshotCaptureFingerprint.kt
- Create or modify focused tests under apps/android/app/src/test/java/com/suishouban/app/capture/

**Step 1: Add failing policy tests**

Test:
- API below 30 is unsupported.
- API 30+ with an active accessibility service captures directly.
- API 30+ without an active service requires accessibility setup.
- The new fingerprint source is distinct from MediaProjection.

**Step 2: Run focused tests and confirm failure**

    rtk .\gradlew.bat :app:testDebugUnitTest --tests "com.suishouban.app.capture.*"

**Step 3: Implement the service and writer**

- Reject API levels below 30 without another capture mechanism.
- Keep only the connected service instance and clear it on interruption/destruction.
- Convert the returned hardware buffer to an immutable software bitmap and always close it.
- Compress into the app-private capture directory and expose only a FileProvider URI.
- Return typed success/failure results.
- Never call or fall back to MediaProjectionManager.

Register the service using android.permission.BIND_ACCESSIBILITY_SERVICE and minimal metadata that does not request window-content retrieval.

**Step 4: Re-run focused tests and compile**

    rtk .\gradlew.bat :app:testDebugUnitTest --tests "com.suishouban.app.capture.*"
    rtk .\gradlew.bat :app:compileDebugKotlin

**Step 5: Commit**

    git add apps/android/app/src/main/java/com/suishouban/app/capture apps/android/app/src/main/res/xml/mofei_screenshot_accessibility_service.xml apps/android/app/src/main/AndroidManifest.xml apps/android/app/src/main/res/values/strings.xml apps/android/app/src/main/java/com/suishouban/app/data/ScreenshotCaptureFingerprint.kt apps/android/app/src/test/java/com/suishouban/app/capture
    git commit -m "feat: capture external screen through accessibility"

### Task 3: Route the overlay screenshot without opening the app first

**Files:**
- Modify: apps/android/app/src/main/java/com/suishouban/app/mascot/overlay/MascotOverlayService.kt
- Create: apps/android/app/src/main/java/com/suishouban/app/capture/MofeiAccessibilitySetupActivity.kt
- Modify: apps/android/app/src/main/AndroidManifest.xml
- Modify: apps/android/app/src/main/res/values/strings.xml
- Create or modify tests under apps/android/app/src/test/java/com/suishouban/app/mascot/overlay/

**Step 1: Add failing routing tests**

Cover:
- External screenshot removes overlay controls before capture.
- Success opens ScreenshotPreviewActivity with the captured URI and overlay-restoration flag.
- Failure restores the overlay and shows an actionable error.
- Missing accessibility setup opens an explanation screen, then system accessibility settings.
- External screenshot routing never constructs MofeiScreenCaptureActivity or a MediaProjection request.

**Step 2: Run focused tests and confirm failure**

    rtk .\gradlew.bat :app:testDebugUnitTest --tests "com.suishouban.app.mascot.overlay.*"

**Step 3: Implement direct capture**

In MascotOverlayService:
- Remove all overlay windows.
- Wait one UI frame so Mofei is not included.
- Request a screenshot from MofeiScreenshotAccessibilityService.
- On success, start the existing preview in a new excluded task.
- On failure, restore the overlay and display a concise error.

When setup is missing, launch MofeiAccessibilitySetupActivity. It explains the one-shot screenshot use and provides a button to Settings.ACTION_ACCESSIBILITY_SETTINGS. Returning without enabling restores the overlay.

**Step 4: Re-run focused tests**

Expected: overlay routing tests pass.

**Step 5: Commit**

    git add apps/android/app/src/main/java/com/suishouban/app/mascot/overlay/MascotOverlayService.kt apps/android/app/src/main/java/com/suishouban/app/capture/MofeiAccessibilitySetupActivity.kt apps/android/app/src/main/AndroidManifest.xml apps/android/app/src/main/res/values/strings.xml apps/android/app/src/test/java/com/suishouban/app/mascot/overlay
    git commit -m "fix: route overlay screenshot into preview workflow"

### Task 4: Wire camera and gallery into the external ring

**Files:**
- Modify as needed: apps/android/app/src/main/java/com/suishouban/app/mascot/overlay/MascotOverlayService.kt
- Modify as needed: apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt
- Modify: apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiActionCommandTest.kt

**Step 1: Add or extend command tests**

Assert TAKE_PHOTO resolves to the existing camera launcher and PICK_IMAGE resolves to the existing photo picker, with both paths retaining the OCR preview/save destination.

**Step 2: Run the focused test**

    rtk .\gradlew.bat :app:testDebugUnitTest --tests "com.suishouban.app.mascot.action.MofeiActionCommandTest"

If the existing mappings satisfy the test, make no redundant production change.

**Step 3: Re-run after any required integration change**

Expected: command tests pass.

**Step 4: Commit coverage or integration changes**

    git add apps/android/app/src/main/java/com/suishouban/app/mascot/overlay/MascotOverlayService.kt apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiActionCommandTest.kt
    git commit -m "test: cover external camera and gallery actions"

### Task 5: Full verification and real-device validation

**Step 1: Run all Android checks from apps/android**

    rtk .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

**Step 2: Install the exact APK**

Record APK timestamp and SHA-256, install it, then verify dumpsys package state.

    rtk adb install -r app\build\outputs\apk\debug\app-debug.apk

**Step 3: Establish device prerequisites**

- Confirm the device is authorized.
- Grant or confirm overlay permission.
- Enable the screenshot accessibility service through system settings, or ADB secure settings when the device permits it.
- Confirm MascotOverlayService and the accessibility service are running.

**Step 4: Exercise all external actions**

From another app:
- Verify the fixed screenshot, camera, and gallery actions.
- Screenshot: no MediaProjection dialog, Mofei absent from the image, OCR preview opens, and save persists the item.
- Camera and gallery: selected media reaches the same preview/save workflow.
- Context actions appear only with backing data.

Collect uiautomator, dumpsys window/activity, and filtered logcat evidence.

**Step 5: Re-check regressions**

Verify bottom navigation remains clickable, only one Mofei instance exists, and returning/canceling restores the overlay exactly once.

**Step 6: Report exact evidence**

Report test count, lint/build result, APK hash, package/device state, and any remaining manual permission step. Do not claim device workflows without observed evidence.
