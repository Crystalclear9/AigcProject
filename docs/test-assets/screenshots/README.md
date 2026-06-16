# Complex Screenshot Test Assets

These generated PNGs are used for repeatable screenshot recognition and remote device validation.

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\generate_complex_screenshot_samples.ps1
```

Asset intent:

- `complex_course_notice.png`: course deadline submission.
- `complex_chat_promise.png`: chat commitment with time and materials.
- `complex_competition_poster.png`: poster-style registration deadline.
- `complex_meeting_poster.png`: meeting with preparation task.
- `noise_shopping_promo.png`: shopping promotion that should not prompt.
- `noise_status_only.png`: status/navigation noise only.
- `noise_own_app_settings.png`: app self UI that should not prompt.
