# Block Blast 10.8.9 — Runtime-state research
> Experimental research. **Not a functional modified APK.** The production autoplay branch remains unchanged.

## Verified with the user-supplied 10.8.9 XAPK
- XAPK has 6 splits: base.apk plus split_0.apk to split_4.apk.
- ARM64 Cocos2d-JS native engine is `lib/arm64-v8a/libcocos2djs.so` in split_0.apk.
- 446 scripts carry `HEK: 01 00 01 02`; 6,337 JSON assets carry `HEK: 03 00 01 02` headers.
- The main boot script `assets/main.js` and primary game logic `assets/assets/main/index.8242c.js` are protected.
- Static encryption headers are NOT sufficient to discover the current board, the three available pieces, or a callable place-piece API.
- All modified APK splits would require coherent re-signing. The original game's installed-data preservation and login/update compatibility are not yet validated.

## Goal
In a *modified game process*, read the true 8x8 board and active hand of 3 pieces, call an internal placement action after planning, and independently verify each board change, without screen capture. The existing `blockblast-autoplay-build` remains the proven, separate fallback.

## Next technical verification
1. Find a reliable script injection point *after* original script loading in the running Cocos VM. A protected JS resource must not be replaced blindly: that could prevent startup.
2. Inject `tools/internal-game/cocos_state_probe.js` and capture its output from a running device.
3. Resolve actual gameplay objects, board representation and action API; replace diagnostic heuristics with typed access.
4. Build and consistently sign all 6 APK splits; validate cold start and correct game logic on Android.
5. Compare internal-state planner accuracy and placement latency against MediaProjection baseline.

The included probe is a diagnostic **prototype only**; it is NOT automatically loaded into the APK and has not been tested in the actual Block Blast process.
