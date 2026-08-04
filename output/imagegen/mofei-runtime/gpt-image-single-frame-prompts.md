# Mofei single-frame GPT image prompt pack

## Canonical mother image

Upload this exact image as the only reference for every generation:

`mofei_idle_f01.png`

Do not upload an atlas, a previous state, or more than one reference image. The current source file is 256x256, but every newly generated frame should be 1024x1024. Each request must produce one standalone PNG, never a sprite sheet or a multi-frame layout.

## Fixed prompt: paste into every request

```text
Use the uploaded Mofei mother image as the one and only character reference. Create exactly one standalone 1024x1024 square PNG. Pure white background. No text, no logo, no UI, no caption, no shadow base, no scene, no other character, no collage, no contact sheet, no multiple frames.

The Mofei character model is immutable. Keep the same centered front-facing camera, the same camera distance, the same amount of empty margin, the same translucent ice-blue horizontal glass capsule, the same deep navy rounded visor, the same two vertical glowing white eyes, the same four cyan capture-corner brackets, and the same single cyan orbital ring.

Fixed geometry: capsule width is 76 percent of canvas width and capsule height is 36 percent of canvas height, aspect ratio 2.1 to 1; visor width is 58 percent of capsule width and visor height is 68 percent of capsule height; each eye is 9 percent of visor width and 42 percent of visor height; eye spacing is 20 percent of visor width. The four brackets keep the same size, line thickness, and corner positions. Never crop the orbital ring.

Keep the exact transparent-glass material, navy visor, icy blue highlights, cyan bracket color, clean soft 3D illustration style, centered composition, and front-facing orientation from the mother image. Only change the motion or expression explicitly described below. Do not change the body silhouette, proportions, eye count, eye placement, visor shape, bracket positions, orbit position, perspective, or palette.

Negative constraints: no hands, feet, ears, mouth, hair, clothes, accessories, props, background objects, speech bubbles, text, icons, new rings, altered brackets, flat vector style, pixel art, anime redesign, different pose, different camera angle, different crop, or different character.
```

## Use sequence

1. Upload only `mofei_idle_f01.png`.
2. Paste the fixed prompt.
3. Append exactly one frame instruction below.
4. Generate one image and save it with the stated filename.
5. Start every next request from the same mother image, not from the previously generated frame.

Playback targets: 120ms per frame for urgent, 140ms for reminder/due soon/focus/complete, 160ms for idle/confirm/rest.

## Idle: 8 frames, 160ms

```text
Output filename: mofei_idle_f01.png. State: calm idle. Frame 1 of 8. Exact mother pose: capsule vertical position is baseline, eyes are fully open vertical white pills, cyan orbit light is at the lower-right point of the ring.
```

```text
Output filename: mofei_idle_f02.png. State: calm idle. Frame 2 of 8. Move the entire capsule upward by 4 pixels only. Reduce both eye heights by 6 percent only. Move the cyan orbit light slightly clockwise from lower-right toward bottom-right. No other change.
```

```text
Output filename: mofei_idle_f03.png. State: calm idle. Frame 3 of 8. Move the entire capsule upward by 8 pixels only. Reduce both eye heights by 15 percent only. Move the cyan orbit light to bottom center. No other change.
```

```text
Output filename: mofei_idle_f04.png. State: calm idle. Frame 4 of 8. Keep capsule 8 pixels upward. Both eyes are narrow horizontal closed-eye slits, centered at their normal eye positions. Move the cyan orbit light to lower-left. No other change.
```

```text
Output filename: mofei_idle_f05.png. State: calm idle. Frame 5 of 8. Move capsule upward by 4 pixels only. Both eyes reopen to 45 percent of normal height. Move the cyan orbit light to left-lower side. No other change.
```

```text
Output filename: mofei_idle_f06.png. State: calm idle. Frame 6 of 8. Return capsule to baseline. Both eyes reopen to 75 percent of normal height. Move cyan orbit light to left side. No other change.
```

```text
Output filename: mofei_idle_f07.png. State: calm idle. Frame 7 of 8. Keep capsule baseline. Eyes are 95 percent of normal height. Move cyan orbit light to upper-left side of its existing ring. No other change.
```

```text
Output filename: mofei_idle_f08.png. State: calm idle. Frame 8 of 8. Exact baseline body and fully open eyes. Move cyan orbit light to upper-right side of its existing ring. No other change.
```

## Focus: 10 frames, 140ms

```text
Output filename: mofei_focus_f01.png. State: focused scan. Frame 1 of 10. Eyes are 82 percent of normal height and slightly closer together, giving a concentrated expression. Add one thin cyan scan line inside the visor at the top 15 percent of the visor. Orbit light is right side.
```

```text
Output filename: mofei_focus_f02.png. State: focused scan. Frame 2 of 10. Same focused eyes. Move only the thin cyan scan line to 25 percent down the visor and the orbit light clockwise by one tenth of the ring.
```

```text
Output filename: mofei_focus_f03.png. State: focused scan. Frame 3 of 10. Same focused eyes. Move only the scan line to 35 percent down and orbit light clockwise another tenth.
```

```text
Output filename: mofei_focus_f04.png. State: focused scan. Frame 4 of 10. Same focused eyes. Move only the scan line to 45 percent down and orbit light clockwise another tenth.
```

```text
Output filename: mofei_focus_f05.png. State: focused scan. Frame 5 of 10. Same focused eyes. Move only the scan line to 55 percent down and orbit light clockwise another tenth.
```

```text
Output filename: mofei_focus_f06.png. State: focused scan. Frame 6 of 10. Same focused eyes. Move only the scan line to 65 percent down and orbit light clockwise another tenth.
```

```text
Output filename: mofei_focus_f07.png. State: focused scan. Frame 7 of 10. Same focused eyes. Move only the scan line to 75 percent down and orbit light clockwise another tenth.
```

```text
Output filename: mofei_focus_f08.png. State: focused scan. Frame 8 of 10. Same focused eyes. Move only the scan line to 85 percent down and orbit light clockwise another tenth.
```

```text
Output filename: mofei_focus_f09.png. State: focused scan. Frame 9 of 10. Same focused eyes. Keep scan line at the lower edge but fade it to 35 percent opacity. Move orbit light clockwise another tenth.
```

```text
Output filename: mofei_focus_f10.png. State: focused scan. Frame 10 of 10. Eyes return to normal focused open height. Remove the scan line. Move orbit light to upper-right starting position. No other change.
```

## Confirm: 8 frames, 160ms

```text
Output filename: mofei_confirm_f01.png. State: waiting for confirmation. Frame 1 of 8. Add a very subtle violet-blue internal visor glow. Keep left eye normal; move right eye upward by 5 pixels only. Orbit light is lower-right.
```

```text
Output filename: mofei_confirm_f02.png. State: waiting for confirmation. Frame 2 of 8. Same violet-blue glow. Move right eye upward by 8 pixels and left eye downward by 2 pixels. Orbit light moves slightly clockwise.
```

```text
Output filename: mofei_confirm_f03.png. State: waiting for confirmation. Frame 3 of 8. Same glow. Move both eyes 7 pixels toward the right inside the visor. Orbit light moves clockwise.
```

```text
Output filename: mofei_confirm_f04.png. State: waiting for confirmation. Frame 4 of 8. Same glow. Move both eyes 10 pixels toward the right. Reduce eye heights by 10 percent. Orbit light moves clockwise.
```

```text
Output filename: mofei_confirm_f05.png. State: waiting for confirmation. Frame 5 of 8. Same glow. Move both eyes 7 pixels toward the right. Restore normal eye heights. Orbit light moves clockwise.
```

```text
Output filename: mofei_confirm_f06.png. State: waiting for confirmation. Frame 6 of 8. Same glow. Move both eyes 3 pixels toward the right. Orbit light moves clockwise.
```

```text
Output filename: mofei_confirm_f07.png. State: waiting for confirmation. Frame 7 of 8. Same glow. Return eyes to normal centered position. Orbit light moves clockwise.
```

```text
Output filename: mofei_confirm_f08.png. State: waiting for confirmation. Frame 8 of 8. Return to centered normal eyes. Violet-blue glow fades to 50 percent. Orbit light returns upper-right. No other change.
```

## Reminder: 10 frames, 140ms

```text
Output filename: mofei_reminder_f01.png. State: gentle reminder. Frame 1 of 10. Eyes are 108 percent of normal height, alert but friendly. Change only orbit light and a faint visor-edge glow to warm amber. Orbit light is lower-right.
```

```text
Output filename: mofei_reminder_f02.png. State: gentle reminder. Frame 2 of 10. Same alert eyes. Increase amber edge glow to 45 percent opacity. Move orbit light clockwise one tenth.
```

```text
Output filename: mofei_reminder_f03.png. State: gentle reminder. Frame 3 of 10. Same alert eyes. Increase amber edge glow to 70 percent opacity. Move orbit light clockwise one tenth.
```

```text
Output filename: mofei_reminder_f04.png. State: gentle reminder. Frame 4 of 10. Same alert eyes. Keep amber glow at 85 percent opacity. Move capsule upward 4 pixels only. Move orbit light clockwise one tenth.
```

```text
Output filename: mofei_reminder_f05.png. State: gentle reminder. Frame 5 of 10. Same alert eyes. Keep amber glow at 100 percent opacity. Move capsule upward 7 pixels only. Move orbit light clockwise one tenth.
```

```text
Output filename: mofei_reminder_f06.png. State: gentle reminder. Frame 6 of 10. Same alert eyes. Keep amber glow at 85 percent opacity. Move capsule upward 4 pixels only. Move orbit light clockwise one tenth.
```

```text
Output filename: mofei_reminder_f07.png. State: gentle reminder. Frame 7 of 10. Same alert eyes. Reduce amber glow to 70 percent opacity. Return capsule baseline. Move orbit light clockwise one tenth.
```

```text
Output filename: mofei_reminder_f08.png. State: gentle reminder. Frame 8 of 10. Same alert eyes. Reduce amber glow to 45 percent opacity. Move orbit light clockwise one tenth.
```

```text
Output filename: mofei_reminder_f09.png. State: gentle reminder. Frame 9 of 10. Same alert eyes. Reduce amber glow to 20 percent opacity. Move orbit light clockwise one tenth.
```

```text
Output filename: mofei_reminder_f10.png. State: gentle reminder. Frame 10 of 10. Restore normal eye height and remove amber edge glow. Keep only amber orbit light at upper-right. No other change.
```

## Due soon: 10 frames, 140ms

```text
Output filename: mofei_due_soon_f01.png. State: due soon. Frame 1 of 10. Eyes are 82 percent of normal height and 8 percent closer together. Use orange only for orbit light and a very thin visor-edge glow. Capsule baseline.
```

```text
Output filename: mofei_due_soon_f02.png. State: due soon. Frame 2 of 10. Same concerned eyes. Orange edge glow 30 percent opacity. Move orbit light clockwise one eighth.
```

```text
Output filename: mofei_due_soon_f03.png. State: due soon. Frame 3 of 10. Same concerned eyes. Orange edge glow 60 percent opacity. Move capsule upward 3 pixels. Orbit light clockwise one eighth.
```

```text
Output filename: mofei_due_soon_f04.png. State: due soon. Frame 4 of 10. Same concerned eyes. Orange edge glow 95 percent opacity. Move capsule upward 5 pixels. Orbit light clockwise one eighth.
```

```text
Output filename: mofei_due_soon_f05.png. State: due soon. Frame 5 of 10. Same concerned eyes. Orange edge glow 60 percent opacity. Move capsule upward 3 pixels. Orbit light clockwise one eighth.
```

```text
Output filename: mofei_due_soon_f06.png. State: due soon. Frame 6 of 10. Same concerned eyes. Orange edge glow 30 percent opacity. Capsule baseline. Orbit light clockwise one eighth.
```

```text
Output filename: mofei_due_soon_f07.png. State: due soon. Frame 7 of 10. Same concerned eyes. Orange edge glow 60 percent opacity. Capsule upward 3 pixels. Orbit light clockwise one eighth.
```

```text
Output filename: mofei_due_soon_f08.png. State: due soon. Frame 8 of 10. Same concerned eyes. Orange edge glow 95 percent opacity. Capsule upward 5 pixels. Orbit light clockwise one eighth.
```

```text
Output filename: mofei_due_soon_f09.png. State: due soon. Frame 9 of 10. Same concerned eyes. Orange edge glow 45 percent opacity. Capsule upward 2 pixels. Orbit light clockwise one eighth.
```

```text
Output filename: mofei_due_soon_f10.png. State: due soon. Frame 10 of 10. Keep concerned eyes. Remove orange edge glow. Orange orbit light returns upper-right. Capsule baseline.
```

## Urgent: 12 frames, 120ms

```text
Output filename: mofei_urgent_f01.png. State: urgent. Frame 1 of 12. Eyes are 70 percent of normal height and 10 percent closer together, visibly tense. Use coral red only for orbit light and thin visor-edge glow. Capsule baseline.
```

```text
Output filename: mofei_urgent_f02.png. State: urgent. Frame 2 of 12. Same tense eyes. Move capsule 5 pixels left. Coral glow 35 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f03.png. State: urgent. Frame 3 of 12. Same tense eyes. Move capsule 7 pixels right. Coral glow 70 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f04.png. State: urgent. Frame 4 of 12. Same tense eyes. Move capsule 4 pixels left. Coral glow 100 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f05.png. State: urgent. Frame 5 of 12. Same tense eyes. Capsule baseline. Coral glow 70 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f06.png. State: urgent. Frame 6 of 12. Same tense eyes. Move capsule 4 pixels right. Coral glow 35 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f07.png. State: urgent. Frame 7 of 12. Same tense eyes. Capsule baseline. Coral glow 20 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f08.png. State: urgent. Frame 8 of 12. Same tense eyes. Move capsule 5 pixels left. Coral glow 45 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f09.png. State: urgent. Frame 9 of 12. Same tense eyes. Move capsule 7 pixels right. Coral glow 100 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f10.png. State: urgent. Frame 10 of 12. Same tense eyes. Move capsule 4 pixels left. Coral glow 70 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f11.png. State: urgent. Frame 11 of 12. Same tense eyes. Capsule baseline. Coral glow 35 percent opacity. Orbit light clockwise one twelfth.
```

```text
Output filename: mofei_urgent_f12.png. State: urgent. Frame 12 of 12. Same tense eyes. Capsule baseline. Remove coral edge glow, retain coral orbit light at upper-right. No other change.
```

## Complete: 10 frames, 140ms

```text
Output filename: mofei_complete_f01.png. State: task complete. Frame 1 of 10. Turn both eyes into gentle upward curved smiling eye slits, keeping their exact normal centers. Make orbit light mint green. Add exactly two tiny mint star particles above the capsule, one left and one right.
```

```text
Output filename: mofei_complete_f02.png. State: task complete. Frame 2 of 10. Keep smiling eyes. Move capsule upward 3 pixels. Move the two tiny mint stars upward 8 pixels and outward 3 pixels. Orbit light clockwise one tenth.
```

```text
Output filename: mofei_complete_f03.png. State: task complete. Frame 3 of 10. Keep smiling eyes. Move capsule upward 6 pixels. Move stars upward 16 pixels and outward 6 pixels. Orbit light clockwise one tenth.
```

```text
Output filename: mofei_complete_f04.png. State: task complete. Frame 4 of 10. Keep smiling eyes. Move capsule upward 8 pixels. Stars are 70 percent opacity, 24 pixels above start, outward 9 pixels. Orbit light clockwise one tenth.
```

```text
Output filename: mofei_complete_f05.png. State: task complete. Frame 5 of 10. Keep smiling eyes. Move capsule upward 6 pixels. Stars are 45 percent opacity, 30 pixels above start, outward 12 pixels. Orbit light clockwise one tenth.
```

```text
Output filename: mofei_complete_f06.png. State: task complete. Frame 6 of 10. Keep smiling eyes. Move capsule upward 3 pixels. Stars are 20 percent opacity, 34 pixels above start, outward 14 pixels. Orbit light clockwise one tenth.
```

```text
Output filename: mofei_complete_f07.png. State: task complete. Frame 7 of 10. Keep smiling eyes. Capsule baseline. Remove stars. Orbit light clockwise one tenth.
```

```text
Output filename: mofei_complete_f08.png. State: task complete. Frame 8 of 10. Keep smiling eyes. Capsule baseline. Mint orbit light clockwise one tenth. No other change.
```

```text
Output filename: mofei_complete_f09.png. State: task complete. Frame 9 of 10. Keep smiling eyes. Capsule baseline. Mint orbit light clockwise one tenth. No other change.
```

```text
Output filename: mofei_complete_f10.png. State: task complete. Frame 10 of 10. Keep smiling eyes. Capsule baseline. Mint orbit light returns upper-right. No other change.
```

## Rest: 8 frames, 160ms

```text
Output filename: mofei_rest_f01.png. State: quiet rest. Frame 1 of 8. Both eyes are short horizontal closed-eye slits at their normal centers. Lower overall character brightness by 12 percent. Orbit light is muted blue-gray at lower-right.
```

```text
Output filename: mofei_rest_f02.png. State: quiet rest. Frame 2 of 8. Same closed eyes. Move capsule downward 3 pixels. Lower brightness by 15 percent. Orbit light moves clockwise.
```

```text
Output filename: mofei_rest_f03.png. State: quiet rest. Frame 3 of 8. Same closed eyes. Move capsule downward 6 pixels. Lower brightness by 18 percent. Orbit light moves clockwise.
```

```text
Output filename: mofei_rest_f04.png. State: quiet rest. Frame 4 of 8. Same closed eyes. Move capsule downward 7 pixels. Lower brightness by 20 percent. Orbit light moves clockwise.
```

```text
Output filename: mofei_rest_f05.png. State: quiet rest. Frame 5 of 8. Same closed eyes. Move capsule downward 6 pixels. Lower brightness by 18 percent. Orbit light moves clockwise.
```

```text
Output filename: mofei_rest_f06.png. State: quiet rest. Frame 6 of 8. Same closed eyes. Move capsule downward 3 pixels. Lower brightness by 15 percent. Orbit light moves clockwise.
```

```text
Output filename: mofei_rest_f07.png. State: quiet rest. Frame 7 of 8. Same closed eyes. Capsule baseline. Lower brightness by 12 percent. Orbit light moves clockwise.
```

```text
Output filename: mofei_rest_f08.png. State: quiet rest. Frame 8 of 8. Same closed eyes. Capsule baseline. Lower brightness by 12 percent. Orbit light returns upper-right. No other change.
```
