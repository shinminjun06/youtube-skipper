# Block Blast Auto v0.2 — screen recognition and autopilot

Prototype, Android 11+. Detects board (8x8), golden tray pieces in three pockets, calculates exhaustive placement of all visible pieces and drags each through AccessibilityService.

## Setup
1. Install APK, open app, enable AccessibilityService.
2. Tap Screen Share, approve Android capture consent.
3. Open Block Blast (portrait, 709x1536 screenshot layout).
4. Tap overlay "▶ 자동 시작".
5. Stop via overlay or stop screen capture in app.

## Important
Screenshot-dependent recognition and gestures are **experimental** and not validated on a physical device. No guarantee of survival or score. A screenshot can differ because of display cutouts, game version, animation and phone resolution. Adjust Y offset in app if blocks release above/below desired cells. On-device only; no upload/network permission.

## Safety
Requires explicit MediaProjection consent. Only operates after user toggles start; manual stop always available.
