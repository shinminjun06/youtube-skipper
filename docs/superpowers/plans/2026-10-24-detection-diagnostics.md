# Detection failure follow-up implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement the following tasks in this isolated branch. The user has delegated implementation decisions and requested continuous execution without questions.

**Goal:** Stop reporting every recognition failure as a missing game board, and accept structurally readable darker colored tiles.
**Architecture:** Preserve `Recognizer.read` for existing consumers. Add a structured analysis result carrying the failed stage and affected slot/cell. Keep uncertain frames non-actionable. Extend the existing colored-tile model only after a dark-palette regression reproduces its failure.
**Tech Stack:** Existing Kotlin/Java Android project, plain Java core checks, Android instrumented fixture checks, GitHub Actions.
**Spec:** User reports “게임판 미검출” with visible tray blocks; original requirements in `2026-10-24-autoplay.md` remain in effect.

## Global constraints

- Work only on `blockblast-auto-build`; preserve main and other projects.
- No extra permissions or network access in the app.
- Do not convert unreadable/occluded blocks into empty slots.
- No claim of real game or physical device validation.

## Review focus

- Dark colorful blocks are occupied, never silently empty.
- Gray unknown blocks and partial occlusion remain uncertain.
- Black capture and wrong screen differ from tray failure.
- Analysis and compatibility `read` return the same actionable frame.
- Held-piece matching and stop cancellation must keep existing native checks passing.

### Task 1: Structured diagnostics

Files: `Recognizer.java`, `CoreChecks.java`, `CaptureService.kt`.
Interface: `Recognizer.analyze(Game.Image)` returns immutable `Detection` with frame, issue, reason, slot/row/column. `read` delegates to it.
- [x] Add diagnostic regression assertions for a valid fixture, a black frame, gray tray block and ambiguous board cell; run and observe RED.
- [x] Implement stage results without relaxing uncertain-frame rejection. Surface the actual failure stage in the overlay and log.
- [x] Run full core checks, expect PASS. Diagnostics and its consumers ship together.

### Task 2: Dark colored tile recognition

Files: `Recognizer.java`, `CoreChecks.java`.
- [x] Add a dark colorful tray/board variant derived from the real screenshot with literal expected hand/board; observe RED.
- [x] Remove the unnecessary bright-only tile cutoff while retaining chroma, background separation, grid shape, and sample consistency checks.
- [x] Run full core checks including unknown gray/occluded input and held-piece matching; expect PASS.

### Task 3: Deliver verified APK

Files: app Gradle version, MainActivity version label, README, workflow artifact name, worklog.
- [x] Update version to 0.4.1 and document evidence/unknowns.
- [ ] Review entire diff; run Java core suite and git diff check.
- [ ] Publish only the named branch; build, lint, and run all native tests via Actions.
- [ ] Download and verify APK hash/source, save deliverable, report actual scope and remaining real-device uncertainty.
