# Mofei Action Center Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a distinctive Mofei capability ring that invokes in-app image actions, captures the current screen with MediaProjection, responds to saved screenshots, and converts allowlisted notifications into confirmation-only drafts.

**Architecture:** Both the in-app pet and the system overlay render the same action definitions and route commands through a pure `MofeiActionCoordinator`. Android-specific launchers and services execute those commands, while all recognized image/text content reuses the existing `AppViewModel` analysis and preview-confirmation pipeline. Notification candidates stay local in Room until the user opens, confirms, rejects, or lets them expire.

**Tech Stack:** Kotlin 2.x, Jetpack Compose Material 3, Android Activity Result APIs, MediaProjection, foreground services, NotificationListenerService, Room 2.6.1, ML Kit OCR, JUnit 4, Compose UI tests, gpt-image-2 via `@imagegen`, Python/Pillow asset validation.

---

## Implementation constraints

- Work from the existing `feat/mofei-in-app-floating-pet` branch; do not stage `.codegraph/` or existing `output/` artifacts unintentionally.
- Use `@superpowers:test-driven-development` for every code task.
- Do not add an accessibility service or a share target. Remove the existing `ACTION_SEND image/*` intent filter because sharing was explicitly removed from scope.
- Never create a formal action card from a notification or screenshot without the existing preview and explicit `confirmDrafts()` step.
- MediaProjection is one capture session per user consent on target SDK 35. Release every `Image`, `ImageReader`, `VirtualDisplay`, and `MediaProjection` on success, cancellation, timeout, and exceptions.
- Generated art contains no text. Compose owns labels, semantics, hit targets, permission state, counters, and reduced-motion behavior.

### Task 1: Define the pure Mofei action model and coordinator

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiAction.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiActionCoordinator.kt`
- Test: `apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiActionCoordinatorTest.kt`

**Step 1: Write the failing action-catalog test**

Cover the exact action order for `IN_APP` and `OVERLAY`, permission-sealed states, busy-state suppression, and notification draft count:

```kotlin
@Test fun overlayShowsOnlyCrossAppActions() {
    val state = MofeiCapabilityState(
        overlayGranted = true,
        notificationAccessGranted = true,
        screenCaptureSupported = true,
        pendingNotificationDrafts = 2,
    )
    assertEquals(
        listOf(
            MofeiAction.CAPTURE_CURRENT_SCREEN,
            MofeiAction.ANALYZE_LATEST_SCREENSHOT,
            MofeiAction.REVIEW_NOTIFICATION_DRAFTS,
            MofeiAction.OPEN_CURRENT_CARD,
            MofeiAction.OPEN_SETTINGS,
        ),
        MofeiActionCoordinator().actionsFor(MofeiSurface.OVERLAY, state).map { it.action },
    )
}
```

**Step 2: Run the target test and verify RED**

Run: `cd apps/android; .\gradlew.bat testDebugUnitTest --tests "com.suishouban.app.mascot.action.MofeiActionCoordinatorTest" --no-daemon`

Expected: FAIL because the action types do not exist.

**Step 3: Implement the minimal pure model**

Define:

```kotlin
enum class MofeiAction {
    CAPTURE_CURRENT_SCREEN,
    ANALYZE_LATEST_SCREENSHOT,
    PICK_IMAGE,
    TAKE_PHOTO,
    REVIEW_NOTIFICATION_DRAFTS,
    OPEN_CURRENT_CARD,
    OPEN_SETTINGS,
}

enum class MofeiSurface { IN_APP, OVERLAY }
enum class MofeiActionAvailability { READY, NEEDS_PERMISSION, UNSUPPORTED, BUSY }

data class MofeiCapabilityState(
    val overlayGranted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val screenCaptureSupported: Boolean = true,
    val latestScreenshotAvailable: Boolean = false,
    val pendingNotificationDrafts: Int = 0,
    val busyAction: MofeiAction? = null,
)

data class MofeiActionItem(
    val action: MofeiAction,
    val availability: MofeiActionAvailability,
    val badgeCount: Int = 0,
)
```

Keep all surface filtering and availability calculations in `MofeiActionCoordinator`; it must not import Android or Compose classes.

**Step 4: Run tests and verify GREEN**

Run the Task 1 target test. Expected: PASS.

**Step 5: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/mascot/action apps/android/app/src/test/java/com/suishouban/app/mascot/action
git commit -m "feat: define Mofei action policy"
```

### Task 2: Persist notification feature settings and the App allowlist

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/data/repository/AppSettingsRepository.kt`
- Modify: `apps/android/app/src/test/java/com/suishouban/app/data/repository/MascotPreferencesTest.kt`

**Step 1: Add failing preference tests**

Test defaults and round-trip persistence for:

```kotlin
val mofeiNotificationDraftsEnabled: Boolean = false
val mofeiNotificationPackageAllowlist: Set<String> = emptySet()
```

Also verify a copied mutable set cannot mutate the repository's exposed state.

**Step 2: Run the preference test and verify RED**

Run: `cd apps/android; .\gradlew.bat testDebugUnitTest --tests "com.suishouban.app.data.repository.MascotPreferencesTest" --no-daemon`

Expected: FAIL on missing fields.

**Step 3: Implement persistence**

Use `SharedPreferences.getStringSet()` defensively:

```kotlin
mofeiNotificationPackageAllowlist = prefs
    .getStringSet("mofei_notification_package_allowlist", emptySet())
    .orEmpty()
    .toSet()
```

Write a fresh `toSet()` in `update()` and keep the existing placement normalization unchanged.

**Step 4: Run the target test and verify GREEN**

Expected: all `MascotPreferencesTest` methods PASS.

**Step 5: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/data/repository/AppSettingsRepository.kt apps/android/app/src/test/java/com/suishouban/app/data/repository/MascotPreferencesTest.kt
git commit -m "feat: persist Mofei notification allowlist"
```

### Task 3: Add the local notification candidate store

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/data/local/NotificationCandidateEntity.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/data/local/NotificationCandidateDao.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/data/repository/NotificationCandidateInput.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/data/repository/NotificationCandidatePolicy.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/data/repository/NotificationCandidateRepository.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/data/local/AppDatabase.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/SuiShouBanApp.kt`
- Modify: `apps/android/app/build.gradle.kts`
- Test: `apps/android/app/src/test/java/com/suishouban/app/data/repository/NotificationCandidatePolicyTest.kt`
- Test: `apps/android/app/src/androidTest/java/com/suishouban/app/data/local/AppDatabaseMigrationTest.kt`

**Step 1: Write failing policy tests**

Cover allowlist enforcement, local package rejection, OTP and payment rejection, ongoing/group-summary rejection, stable dedupe hash, and 24-hour expiry:

```kotlin
@Test fun otpNotificationIsRejected() {
    val input = NotificationCandidateInput(
        packageName = "com.example.chat",
        appLabel = "Chat",
        title = "验证码",
        body = "您的验证码是 482913，五分钟内有效",
        postedAtMillis = 1_000L,
    )
    assertEquals(NotificationCandidateDecision.SENSITIVE, policy.evaluate(input, setOf("com.example.chat")))
}
```

**Step 2: Run the policy test and verify RED**

Run the new test class. Expected: FAIL because the candidate policy is absent.

**Step 3: Implement the policy and Room schema**

Use a separate table so unconfirmed content never becomes an `ActionCardEntity`:

```kotlin
@Entity(tableName = "notification_candidates")
data class NotificationCandidateEntity(
    @PrimaryKey val id: String,
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val postedAtMillis: Long,
    val contentHash: String,
    val expiresAtMillis: Long,
)
```

DAO requirements: `observeActive(now)`, `findById(id)`, `insertIgnore(entity)`, `delete(id)`, and `deleteExpired(now)`.

Bump `AppDatabase` from version 2 to 3, include the entity/DAO, and add `MIGRATION_2_3` with a unique index on `content_hash`. Construct `NotificationCandidateRepository` once in `SuiShouBanApp`.

**Step 4: Add and run the migration test**

Add `androidTestImplementation("androidx.room:room-testing:2.6.1")`. The migration test must create a version-2 database, insert an existing card, run `MIGRATION_2_3`, then assert that the card survives and `notification_candidates` plus its unique hash index exist.

Run: `cd apps/android; .\gradlew.bat assembleDebugAndroidTest --no-daemon`

Expected: androidTest sources compile. Run `connectedDebugAndroidTest` when a device is attached.

**Step 5: Run tests and compile Room code**

Run: `cd apps/android; .\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`

Expected: policy tests PASS and KSP/Room schema compilation succeeds.

**Step 6: Commit**

```powershell
git add apps/android/app/build.gradle.kts apps/android/app/src/main/java/com/suishouban/app/data apps/android/app/src/main/java/com/suishouban/app/SuiShouBanApp.kt apps/android/app/src/test/java/com/suishouban/app/data/repository/NotificationCandidatePolicyTest.kt apps/android/app/src/androidTest/java/com/suishouban/app/data/local/AppDatabaseMigrationTest.kt
git commit -m "feat: store local notification candidates"
```

### Task 4: Implement notification access, allowlist selection, and candidate ingestion

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/notification/MofeiNotificationListenerService.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/notification/InstalledAppRepository.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiPermissionState.kt`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ui/screens/SettingsScreen.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt`
- Test: `apps/android/app/src/test/java/com/suishouban/app/notification/InstalledAppPolicyTest.kt`

**Step 1: Write failing permission and installed-App policy tests**

Test component-name parsing for `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`, deterministic label sorting, package deduplication, and omission of the current package.

**Step 2: Run the target tests and verify RED**

Expected: FAIL on missing notification components.

**Step 3: Declare and implement the listener**

Manifest declaration:

```xml
<service
    android:name=".notification.MofeiNotificationListenerService"
    android:exported="false"
    android:label="墨斐通知草稿"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

In `onNotificationPosted()`, copy only primitive fields, then dispatch repository work off the main callback. Do not cancel, snooze, mark shown, or mutate the source notification.

**Step 4: Add explicit settings UI**

Add one `SettingsCard` section with:

- Feature toggle, default off.
- Current special-access status.
- Button that safely opens `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` only after an explicit click.
- Launchable installed-App allowlist built with `ACTION_MAIN` + `CATEGORY_LAUNCHER`, not `QUERY_ALL_PACKAGES`.
- Selected count and per-App toggles.

**Step 5: Run tests and build**

Run: `cd apps/android; .\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`

Expected: PASS; manifest merge contains the listener permission.

**Step 6: Commit**

```powershell
git add apps/android/app/src/main/AndroidManifest.xml apps/android/app/src/main/java/com/suishouban/app/notification apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiPermissionState.kt apps/android/app/src/main/java/com/suishouban/app/ui/screens/SettingsScreen.kt apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt apps/android/app/src/test/java/com/suishouban/app/notification
git commit -m "feat: ingest allowlisted notifications"
```

### Task 5: Generate and package the Mofei capability-ring art

**Required skill:** `@imagegen`

**Files:**
- Create: `output/imagegen/mofei-action-center/mofei_action_ring_full_source.png`
- Create: `output/imagegen/mofei-action-center/mofei_action_ring_compact_source.png`
- Create: `output/imagegen/mofei-action-center/mofei_action_glyph_atlas_source.png`
- Create: `tools/mofei/build_action_assets.py`
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_action_ring_full.png`
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_action_ring_compact.png`
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_action_glyph_*.png`
- Create: `apps/android/app/src/main/res/drawable-nodpi/mofei_action_seal.png`
- Test: `apps/android/app/src/test/java/com/suishouban/app/mascot/MofeiActionAssetCatalogTest.kt`

**Step 1: Write the failing asset-catalog test**

Require mappings for capture, recent screenshot, gallery, camera, notification, current card, and settings. Require full/compact shells and a shared seal texture.

**Step 2: Run the test and verify RED**

Expected: missing drawables/catalog.

**Step 3: Generate three reviewed source images**

Use gpt-image-2 through `@imagegen`. Match the existing Mofei language: transparent blue glass, deep navy visor, electric cyan paths, violet confirmation energy, rounded mechanical forms. Explicitly request:

- Transparent or clean isolatable background.
- No words, letters, numbers, UI labels, phone frames, rectangles, or generic card panels.
- Full ring with an open transparent center for the live Mofei sprite.
- Compact side-opening ring for the system overlay.
- A clean 4x2 glyph atlas with isolated symbols and generous gutters.

Review each source visually before packaging. Regenerate if the atlas contains malformed symbols, fused cells, white background, or illegible small details.

**Step 4: Build deterministic runtime assets**

Implement `build_action_assets.py` with Pillow. It must crop the approved atlas, remove residual near-white background if necessary, trim alpha, pad square, resize glyphs to 256x256 and shells to at most 1024px, and fail if any corner alpha exceeds 12 or the nontransparent bounds touch the safe margin.

**Step 5: Run asset validation and tests**

Run the asset builder using the bundled workspace Python after calling `codex_app__load_workspace_dependencies`. Then run:

`cd apps/android; .\gradlew.bat testDebugUnitTest --tests "com.suishouban.app.mascot.MofeiActionAssetCatalogTest" assembleDebug --no-daemon`

Expected: all resource IDs resolve and the APK packages the transparent assets.

**Step 6: Commit only approved sources, tooling, and packaged assets**

```powershell
git add tools/mofei/build_action_assets.py output/imagegen/mofei-action-center apps/android/app/src/main/res/drawable-nodpi/mofei_action_*.png apps/android/app/src/test/java/com/suishouban/app/mascot/MofeiActionAssetCatalogTest.kt
git commit -m "feat: add Mofei capability-ring assets"
```

### Task 6: Build the shared Compose capability ring

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiActionAssets.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiActionRing.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/FloatingMascot.kt`
- Test: `apps/android/app/src/androidTest/java/com/suishouban/app/mascot/MofeiActionRingTest.kt`

**Step 1: Write failing Compose UI tests**

Test full vs compact action semantics, locked action explanation, notification badge count, action callback, collapse callback, and reduced-motion state. Use stable tags such as `mofei-action-ring`, `mofei-action-capture-current-screen`, and `mofei-action-notification-drafts`.

**Step 2: Run instrumentation tests and verify RED**

Run: `cd apps/android; .\gradlew.bat connectedDebugAndroidTest --no-daemon`

Expected: FAIL because the composable does not exist. If no device is attached, record the deferred device test and still compile `androidTest` with `assembleDebugAndroidTest`.

**Step 3: Implement the ring**

`MofeiActionRing` accepts only state and callbacks:

```kotlin
@Composable
fun MofeiActionRing(
    surface: MofeiSurface,
    mascotState: MascotState,
    items: List<MofeiActionItem>,
    expanded: Boolean,
    reduceMotion: Boolean,
    onAction: (MofeiAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Use generated shells/glyphs as decoration; implement labels, focus order, minimum 48dp hit targets, badge text, and sealed-state explanation in Compose. Animate radial/fan expansion only when reduced motion is off.

**Step 4: Replace the old speech bubble action UI**

Keep drag-to-dock and long-press controls, but make a normal tap toggle the capability ring. Preserve current-card navigation and settings as actions within the ring.

**Step 5: Run tests**

Run mascot unit tests plus `assembleDebugAndroidTest`. Expected: PASS/compile.

**Step 6: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/mascot apps/android/app/src/androidTest/java/com/suishouban/app/mascot
git commit -m "feat: render Mofei capability ring"
```

### Task 7: Route in-app actions through MainActivity

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Create: `apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiActionCommandTest.kt`

**Step 1: Add failing command-mapping tests**

Introduce a platform-neutral sealed command and verify every action maps once:

```kotlin
sealed interface MofeiActionCommand {
    data object LaunchPhotoPicker : MofeiActionCommand
    data object LaunchCamera : MofeiActionCommand
    data object RequestScreenCapture : MofeiActionCommand
    data object OpenLatestScreenshot : MofeiActionCommand
    data object OpenNotificationDrafts : MofeiActionCommand
    data class OpenCard(val cardId: String?) : MofeiActionCommand
    data object OpenSettings : MofeiActionCommand
}
```

**Step 2: Run the command test and verify RED**

Expected: missing command mapping.

**Step 3: Update Activity Result launchers**

- Replace `GetContent()` with `PickVisualMedia()` for the capability-ring gallery action.
- Retain `TakePicture()` and `FileProvider` for camera.
- Route all action callbacks through one `executeMofeiCommand()` function.
- Remove `extractSharedImage()` and the `ACTION_SEND image/*` manifest intent filter.
- Keep legacy Home/Import buttons calling the same launchers so existing UI behavior does not diverge.

**Step 4: Run unit tests and build**

Expected: command tests PASS and no manifest intent filter accepts shares.

**Step 5: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt apps/android/app/src/main/AndroidManifest.xml apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiActionCommandTest.kt
git commit -m "feat: route in-app Mofei actions"
```

### Task 8: Extract shared screenshot fingerprinting and latest-screenshot lookup

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/domain/screenshot/ScreenshotFingerprintStore.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/data/repository/LatestScreenshotRepository.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/reminder/ScreenshotMonitorService.kt`
- Test: `apps/android/app/src/test/java/com/suishouban/app/domain/ScreenshotFingerprintStoreTest.kt`

**Step 1: Write failing dedupe tests**

Test same-hash cooldown, ignored-hash cooldown, rate limit, active-capture/system-screenshot cross-source dedupe, and expiry.

**Step 2: Run tests and verify RED**

Expected: shared store absent.

**Step 3: Extract existing private prompt policy**

Move the existing hash/timestamp logic out of `ScreenshotMonitorService` without changing current constants or behavior. Add a `source` field (`MEDIA_STORE` or `MEDIA_PROJECTION`) for diagnostics but dedupe on content regardless of source.

**Step 4: Add latest-screenshot lookup**

Query MediaStore for the newest screenshot candidate using the same path/name/date checks as the monitor service. Return `null` when permission is missing or no candidate exists; do not fall back to arbitrary recent photos.

**Step 5: Run the screenshot tests and build**

Expected: existing screenshot gate behavior remains green and both sources use the shared store.

**Step 6: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/domain/screenshot apps/android/app/src/main/java/com/suishouban/app/data/repository/LatestScreenshotRepository.kt apps/android/app/src/main/java/com/suishouban/app/reminder/ScreenshotMonitorService.kt apps/android/app/src/test/java/com/suishouban/app/domain/ScreenshotFingerprintStoreTest.kt
git commit -m "refactor: share screenshot dedupe policy"
```

### Task 9: Implement one-shot MediaProjection capture

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/capture/MofeiScreenCaptureActivity.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/capture/MofeiScreenCaptureService.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/capture/ScreenCaptureImageWriter.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/capture/ScreenCaptureResult.kt`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/res/xml/file_paths.xml`
- Modify: `apps/android/app/src/main/res/values/styles.xml`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/ScreenshotPreviewActivity.kt`
- Test: `apps/android/app/src/test/java/com/suishouban/app/capture/ScreenCaptureImageWriterTest.kt`

**Step 1: Write failing image policy tests**

Extract pure functions for padded `ImageReader` row conversion, all-black/protected-content detection, cache filename normalization, and timeout cleanup. Test black, transparent, normal, and row-padding cases.

**Step 2: Run tests and verify RED**

Expected: capture policy absent.

**Step 3: Add manifest declarations**

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<activity
    android:name=".capture.MofeiScreenCaptureActivity"
    android:exported="false"
    android:excludeFromRecents="true"
    android:theme="@style/Theme.SuiShouBan.Transparent" />
<service
    android:name=".capture.MofeiScreenCaptureService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

**Step 4: Implement the consent Activity and service handoff**

The Activity requests `createScreenCaptureIntent()`, starts the foreground service only after `RESULT_OK`, remains alive as the user-initiated owner, and receives a `ResultReceiver`. On success it launches the non-exported `ScreenshotPreviewActivity`; on cancellation/failure it shows Mofei feedback and finishes.

The service order must be: `startForeground()` → `getMediaProjection()` → register `MediaProjection.Callback` → create one `VirtualDisplay` → receive one image → close/release/stop. Add a hard timeout and a single idempotent cleanup function called from every exit path.

**Step 5: Integrate private-cache preview**

Add an explicit, non-exported capture intent factory to `ScreenshotPreviewActivity`. Do not pretend the capture came from `ScreenshotMonitorService`; accept it because the Activity itself is not exported and the URI belongs to this app's FileProvider.

**Step 6: Run tests and build**

Run: `cd apps/android; .\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`

Expected: image policy tests PASS; merged manifest contains `mediaProjection` service type.

**Step 7: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/capture apps/android/app/src/main/java/com/suishouban/app/ScreenshotPreviewActivity.kt apps/android/app/src/main/AndroidManifest.xml apps/android/app/src/main/res/xml/file_paths.xml apps/android/app/src/main/res/values/styles.xml
git commit -m "feat: capture current screen with Mofei"
```

### Task 10: Present notification candidates as Mofei message fireflies

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/notification/NotificationCandidateUiModel.kt`
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiNotificationFireflies.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/AppViewModel.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiActionRing.kt`
- Test: `apps/android/app/src/androidTest/java/com/suishouban/app/mascot/MofeiNotificationFirefliesTest.kt`

**Step 1: Write failing UI and ViewModel-facing tests**

Test source label, bounded summary, chronological ordering, open-candidate callback, reject/delete callback, and absence of any direct save/confirm callback.

**Step 2: Run tests and verify RED**

Expected: firefly UI absent.

**Step 3: Expose candidates to the app state**

Add active candidate flow and pending count without copying raw notification text into unrelated saved UI state. Add explicit methods:

```kotlin
fun analyzeNotificationCandidate(id: String, onDone: (Boolean) -> Unit)
fun rejectNotificationCandidate(id: String)
```

`analyzeNotificationCandidate()` loads local text, calls the existing `analyzeTextInternal`/`analyzeText` path, and deletes the candidate only after user rejection or confirmed workflow completion. It must not call `saveConfirmed()` directly.

Track the opened candidate ID separately from the text-analysis result. Extend `confirmDrafts()` so it deletes that candidate only after the selected drafts have been saved successfully; clear the ID without deletion if analysis fails or the user leaves the preview.

**Step 4: Render message fireflies**

Use generated glow/glyph assets with Compose text overlays. Show at most three around Mofei plus a numeric overflow marker; open the candidate list/preview on tap. Preserve 48dp hit targets and content descriptions.

**Step 5: Run tests and build**

Expected: unit tests PASS, androidTest compiles, and action cards remain unchanged until confirmation.

**Step 6: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/notification apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiNotificationFireflies.kt apps/android/app/src/main/java/com/suishouban/app/mascot/MofeiActionRing.kt apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt apps/android/app/src/main/java/com/suishouban/app/AppViewModel.kt apps/android/app/src/androidTest/java/com/suishouban/app/mascot/MofeiNotificationFirefliesTest.kt
git commit -m "feat: review notification drafts with Mofei"
```

### Task 11: Upgrade the system overlay to the compact action ring

**Files:**
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayController.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayService.kt`
- Modify: `apps/android/app/src/test/java/com/suishouban/app/mascot/MascotOverlayControllerTest.kt`

**Step 1: Write failing overlay policy tests**

Test compact ring dimensions, left/right expansion, action command mapping, outside-tap collapse, permission-sealed actions, and window clamping after orientation changes.

**Step 2: Run the target test and verify RED**

Expected: old two-state capsule dimensions/actions fail the new assertions.

**Step 3: Reuse the shared ring and coordinator**

Keep the current foreground-service and opt-in overlay lifecycle. Replace the expanded preview content with `MofeiActionRing(surface = OVERLAY, ...)`. Route capture through an explicit `PendingIntent` to `MofeiScreenCaptureActivity`; route latest screenshot, notification drafts, current card, and settings through explicit intents to `MainActivity`.

Never attempt to launch an Activity from recomposition. Only execute intents from direct user callbacks, and handle `ActivityNotFoundException`/background-start rejection with a notification fallback.

**Step 4: Run overlay tests and build**

Expected: controller tests PASS and overlay service compiles without changing its foreground-only hidden/restore safety policy.

**Step 5: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayController.kt apps/android/app/src/main/java/com/suishouban/app/mascot/MascotOverlayService.kt apps/android/app/src/test/java/com/suishouban/app/mascot/MascotOverlayControllerTest.kt
git commit -m "feat: add actions to cross-app Mofei"
```

### Task 12: Add cleanup, recovery, and permission-revocation behavior

**Files:**
- Create: `apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiRecoveryPolicy.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/capture/MofeiScreenCaptureService.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/notification/MofeiNotificationListenerService.kt`
- Modify: `apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt`
- Test: `apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiRecoveryPolicyTest.kt`

**Step 1: Write failing recovery tests**

Cover projection cancellation, protected/black content, notification access revoked, stale busy state, expired candidates, and capture-cache cleanup.

**Step 2: Run tests and verify RED**

Expected: recovery policy absent.

**Step 3: Implement explicit recovery states**

Map recoverable failures to stable user messages and action availability. Do not loop permission prompts. Clear stale capture files at app startup and after preview completion. Run candidate expiry cleanup when the listener connects and when the action center opens.

**Step 4: Run all Android unit tests**

Run: `cd apps/android; .\gradlew.bat testDebugUnitTest --no-daemon`

Expected: PASS.

**Step 5: Commit**

```powershell
git add apps/android/app/src/main/java/com/suishouban/app/mascot/action/MofeiRecoveryPolicy.kt apps/android/app/src/main/java/com/suishouban/app/capture/MofeiScreenCaptureService.kt apps/android/app/src/main/java/com/suishouban/app/notification/MofeiNotificationListenerService.kt apps/android/app/src/main/java/com/suishouban/app/MainActivity.kt apps/android/app/src/test/java/com/suishouban/app/mascot/action/MofeiRecoveryPolicyTest.kt
git commit -m "fix: recover Mofei action failures safely"
```

### Task 13: Full verification and documentation

**Required skill:** `@superpowers:verification-before-completion`

**Files:**
- Modify: `README.md`
- Modify: `docs/guides/LOCAL_AND_REMOTE_TESTING.md`
- Create: `docs/reports/2026-07-20-mofei-action-center-verification.md`

**Step 1: Run static and unit verification**

```powershell
cd apps/android
.\gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon
```

Expected: all tasks successful; APK at `apps/android/app/build/outputs/apk/debug/app-debug.apk`.

**Step 2: Run connected tests**

With a device/emulator attached:

```powershell
cd apps/android
.\gradlew.bat connectedDebugAndroidTest --no-daemon
```

Expected: capability-ring and notification-firefly UI tests PASS.

**Step 3: Perform real-device checks**

Record evidence for:

1. In-app ring opens and launches Photo Picker and camera.
2. Cross-App ring requests MediaProjection consent, captures one frame, stops its FGS, and opens preview.
3. A normal system screenshot still triggers the existing workflow.
4. The same image from both routes produces only one prompt.
5. A notification from a non-allowlisted App produces nothing.
6. An allowlisted actionable notification produces one local candidate and no action card.
7. Confirming its preview creates the card; rejecting deletes the candidate.
8. OTP/payment notifications produce no candidate.
9. Revoking notification/overlay/media permissions seals only the affected capability.
10. Reduced motion leaves all states understandable.

Use `adb shell dumpsys activity services com.suishouban.app` and `adb shell dumpsys notification` only as read-only evidence. Do not record sensitive notification text in the report.

**Step 4: Inspect packaged visual assets and screenshots**

Verify transparent corners, no rectangular white backgrounds, no generated text, correct compact/full ring positioning, and Mofei visual continuity. Capture final screenshots to `output/verification/` but commit only explicitly approved report images.

**Step 5: Update documentation**

Document exact user permission flow, feature toggles, allowlist behavior, Android 14+ per-session capture consent, privacy/expiry rules, and direct build/install commands.

**Step 6: Re-run the final command after documentation changes**

Run: `cd apps/android; .\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`

Expected: PASS with a fresh APK.

**Step 7: Commit verification**

```powershell
git add README.md docs/guides/LOCAL_AND_REMOTE_TESTING.md docs/reports/2026-07-20-mofei-action-center-verification.md
git commit -m "docs: verify Mofei action center"
```

## Final completion gate

- Run `git status --short --branch` and confirm only known user-owned untracked files remain.
- Review `git log --oneline` for the planned small commits.
- Confirm no share intent filter or accessibility service exists.
- Confirm no notification path calls `saveConfirmed()` without user confirmation.
- Confirm the MediaProjection foreground service is absent after every capture test.
- Report exact test/build results, APK path, device model/API level, and any verification that could not be run.
