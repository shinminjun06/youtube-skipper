# Recognition-to-action follow-up

Goal: fix the attached misnamed 0.4.1 APK (internally 0.5.0), which reports a recognized board but does not act, and preserve latest branch changes.

Evidence: latest user screenshot 323819 is a purple event theme with gold frames. Current 54e72af reads TRAY:OCCLUDED_OR_UNKNOWN_COLOR in slot 0; after distinguishing narrow trim from broad unknown faces, slot 1 reveals a second error: face width omits thick gold trim and underestimates cell pitch.

Scope: existing Kotlin/Java, no new permissions, no changes to main. Preserve external commits up to 54e72af. Previous review fixes are retained as regressions, rather than overwriting the newer theme support.

- [ ] Detect original purple screenshot, exact manually labeled board 0c606a2f32eccdc8, exact three pieces and legal plan. Existing detector RED; new center spacing and decoration handling must pass.
- [ ] Preserve safe rejection for ambiguous board reference and unknown/occluded faces. Previous regression corpus 806 assertions plus real purple fixture.
- [ ] Make stabilization, planning, missing foreground identity and touch dispatch state visible. Avoid interpreting partial board detection as full hand readiness.
- [ ] Exercise native MediaProjection+Accessibility controller on gold, dark and gold-framed purple controlled fixtures; test stop, app switch and service disable.
- [ ] Build a correctly named version 0.5.1 APK on GitHub Actions; verify source/hash/signature and provide APK. Physical device/actual game execution is not available here.

Review focus: broad background gradients vs narrow trim; no dropped covered arms; dark cells must not invert occupancy; source pickup remains within colored faces; SurfaceView root absence must not silently stall or permit touches after app switch.
