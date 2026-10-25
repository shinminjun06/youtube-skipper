# Android Block Blast drag-method review (2026-10-25)

## Requirements and observed failures

- No root or PC; screen information via MediaProjection; input through Android AccessibilityService gestures.
- Multiple uploaded diagnostics (v1.3.1–v1.3.7) show picked pieces actually moving but search failing due to color/thumbnail overlap, overlay occlusion, render snap, lifted sprite/finger offsets and different frame timings.
- Never intentionally release an unverified piece. A successful accessibility gesture callback does **not** prove that the target game accepted the placement.
- Keep read-only highlight version `blockblast-auto-build` independent from dual-mode `blockblast-autoplay-build`.

## Options compared

| Method | Gesture/capture cost | Robustness | Decision |
| --- | --- | --- | --- |
| Single scripted absolute swipe to grid cell | Best theoretically | Fails when game lifts sprite, snaps previews, screen geometry changes; no feedback | **Reject** for unverified use |
| Existing repeated vision search and 2-cell, 62%-gain correction | Requires many screenshots and continued gestures | Has explicit verification; recurrent pickup/render errors in supplied diagnostics | Keep as **fallback**, not fastest path |
| Fixed manually configured touch offset | Small | No per-shape, per-slot, resolution, update adaptation; error propagates to all moves | Reject as primary controller |
| Pure online open-loop training | Fast on measured device | May drift, change with piece geometry, release at wrong location | Reject without final vision verification |
| **Confidence-gated success-trained calibration + bounded servo + board verification** | Slow for initial uncalibrated placements; fewer strokes once reliable | Learns per-slot normalized finger/sprite offset only after success, checks final preview, observes actual board | **Chosen** |

## Selected hybrid controller

1. Parse a stable game board and all visible pieces; plan the complete current hand.
2. Ensure overlays do not appear in MediaProjection frames while auto input runs.
3. **Cold start**: use the current staged long-press and visual movement check, including thumbnail-overlap recovery. Apply a faster bounded error correction (3 cells max instead of old 2, gain .88 vs .62 when far; gentle near snap).
4. **Warm start after at least two agreeing verified successes for a specific tray slot**: normalize the observed finger-to-piece offset by the piece dimensions and board-cell size; predict the finger position for the desired sprite origin. Execute a direct continued drag *without releasing* and inspect the target preview.
5. If the predicted position is not verified, search for the unique held shape and run bounded visual corrections. Unknown or ambiguous states cause a controlled stop. No calibration is written from a mere pickup or a failed placement.
6. **Only after** the requested cells are visually occupied at the legal location and the held sprite is stable, release the pointer. Verify the resultant board and consumed slot. Train from the observed finger/sprite relation only when this independent verifier confirms the placement.
7. Reset calibration when the geometry changes or offset samples disagree. No persistent calibration across app/device/game sessions in initial rollout.

## Evidence and limitations

- Official Android `dispatchGesture` and `GestureDescription.StrokeDescription.continueStroke` preserve a held pointer across gesture dispatches. Documentation: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService and https://developer.android.com/guide/topics/ui/accessibility/service
- This design is an engineering hypothesis derived from the existing implementation and field failures, **not a measured claim of fastest or best on real devices**.
- Offline simulator benchmarks can compare correction steps against the old 2-cell controller across controlled gains and distances; results are not real device success rates.
- Real-world acceptance criteria: no wrong placements in >100 verified moves, success rate, median gesture/capture count, p95 placement latency, percentage of aborted drags, and drift of learned offsets. An Android emulator is not a substitute for a live game.
- Until real-device A/B evidence exists, preserve an advisor-only mode and deterministic cold-start fallback.

## Explicit release guards

- Test wrong-color/wrong-shape, ambiguous duplicate sprites, frozen images, opposite-direction probes, invalid board destinations, OCR errors and overlays.
- Ensure zero release calls in the calibration helper; only the main state machine issues RELEASE after strict `locateNear + targetCellPreview`.
- Store a pending calibration sample on RELEASE, commit it **only** when `MoveVerifier.matches` succeeds, and discard it on timeout/failure.
- Instrument direct vs fallback path and placement result in logs/diagnostics for measurement.
