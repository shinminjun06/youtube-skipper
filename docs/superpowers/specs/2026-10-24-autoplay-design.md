# Block Blast Auto: verified, visually aligned moves

The user delegates implementation choices and requests continuous execution through
build and tests. Work only on `blockblast-auto-build`, starting at 3b194f0.

## Findings and approach
- Existing tray dimensions round a tile width and then add a tile; an L-piece's
  bounding-box center may be an empty square. Gold-only classification and fixed
  screen fractions silently misread boards. Gesture completion is not placement.
- A longer blind swipe or a single calibrated offset cannot establish successful
  placement. Use real grid detection, occupied pickup cells, a continued touch,
  screenshot-measured floating-piece alignment, then exact post-drop verification.
- Keep native Kotlin services, use a small pure Java vision/planning/control core
  testable without Android. No network, root, game hooks or heavyweight ML runtime.

## Contracts
Recognition detects an 8x8 square lattice from image evidence, uses adaptive
empty-cell colors and colorful/beveled tiles, and requires coherent tray shapes.
Ambiguous/animated frames never initiate a move. Full-display capture coordinates
map directly to touch coordinates. Resize/rotation stops playback.

Pickup starts inside an occupied tile, holds, then stages below the board with
touch still down. Match the floating piece against the before image to measure
its full-size origin; move by target-origin minus observed-origin. Inspect again
before releasing. Never fall back to a guessed Y offset. Bound time, corrections
and retries; cancel safely when stopped or foreground app changes. Cancel with
a fresh gesture on the service-owned neutral overlay, so a pending final UP
is cancelled instead of being allowed to place on the board.

Post-drop board must equal the bitboard prediction after simultaneous row/column
clears, with the chosen tray piece consumed (or a fresh hand after the last piece).
Stable frames and timestamps prevent animations/stale captures being accepted.

Planning considers all visible piece orders with bounded beam search, line clears,
empty-space connectivity and future-shape mobility. If no complete hand is possible,
return the best legal partial move. Replan after every verified move.

## Evidence and limits
Test original screenshots, resized/translated and recolored fixtures, irregular
pickup cells, measured lift offsets, clears, failure/timeout and random legal games.
Add a debug Android touch fixture to exercise continued accessibility drags and
rendering on an emulator through Actions. This is a controlled test, not the real
Block Blast app. Physical-device success and high scores require actual game runs.
