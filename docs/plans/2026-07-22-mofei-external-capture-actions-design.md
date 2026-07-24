# Mofei External Capture Actions Design

## Goal

Make the external Mofei overlay useful without presenting a screen-sharing workflow as a screenshot. The overlay must provide three stable capture actions and add contextual task actions only when they have useful content.

## Action catalog

The overlay always shows these actions in this order:

1. Screenshot
2. Camera recognition
3. Gallery import

It conditionally adds:

- Current item, only when a valid highest-priority action card exists.
- Notification drafts, only when pending drafts exist. The action displays the pending count.

The in-app catalog remains unchanged.

## Screenshot architecture

The screenshot action uses Android's accessibility screenshot API instead of `MediaProjection`. This is a literal one-frame screenshot and must never fall back to the system screen-sharing consent flow.

Before requesting the screenshot, the overlay removes its own windows so Mofei is not captured. The accessibility service captures the display, writes one private temporary image, and hands that image to the existing OCR and preview workflow. Every success, cancellation, permission failure, and processing failure restores the overlay when the user remains opted in.

If the accessibility service is not enabled, the action opens a clear permission explanation and then Android accessibility settings. It does not open the MediaProjection consent screen.

## Camera and gallery flow

Camera recognition and gallery import reuse the existing `TakePicture` and system photo-picker contracts. After a successful selection or capture, the image enters the same OCR, draft preview, and save workflow used in the app.

## Overlay permission recovery

The app continues to require explicit system overlay permission. The settings screen must expose the rejected state and provide a route to re-request the permission. The service must not start when permission or user opt-in is absent.

## Error handling

- Missing accessibility permission: show permission guidance; do not invoke screen sharing.
- Screenshot failure: restore the overlay and show a user-visible failure message.
- Camera/gallery cancellation: restore or retain the overlay without creating a draft.
- Empty OCR result: use the existing empty-recognition feedback and do not save an empty task.
- Missing current card or notification drafts: omit the corresponding contextual action.

## Verification

- JVM tests cover the fixed and conditional overlay action catalog.
- Tests cover screenshot permission routing and the absence of a MediaProjection fallback.
- Android build and lint pass.
- On the connected phone, verify overlay permission, service/window presence, and all three fixed actions.
- Confirm screenshot output excludes Mofei and reaches preview without a screen-sharing prompt.
