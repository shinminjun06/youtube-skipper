#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/core-checks
java com.sun.tools.javac.Main -d build/core-checks app/src/main/java/com/pivotry/blockauto/core/*.java tests/java/com/pivotry/blockauto/core/CoreChecks.java
java -cp build/core-checks com.pivotry.blockauto.core.CoreChecks

# Regression: vendor MediaProjection timestamps can be zero/repeated.
# Never skip the newest acquired image based on Image.timestamp.
if grep -Fq 'img.timestamp > timestamp' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: stale ImageReader timestamp gating reintroduced' >&2
  exit 1
fi
if ! grep -Fq 'cached = latest' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: latest captured frame is not replacing stale cache' >&2
  exit 1
fi

# Automatic touch must only be possible in explicitly selected auto mode.
if ! grep -Fq 'CaptureService.active?.isAutomatic()!=true' app/src/main/java/com/pivotry/blockauto/AutoService.kt; then
  echo 'FAIL: automatic gesture guard removed' >&2
  exit 1
fi
if ! grep -Fq 'if(!automatic) {' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: manual advisor branch removed' >&2
  exit 1
fi
if ! grep -Fq 'service.beginDrag(' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: automatic drag was not connected' >&2
  exit 1
fi
if ! grep -Fq 'MoveVerifier(frame,' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: placement verification removed' >&2
  exit 1
fi
if ! grep -Fq 'GuideMask.strip' app/src/main/java/com/pivotry/blockauto/AutoService.kt; then
  echo 'FAIL: captured highlight exclusion is missing' >&2
  exit 1
fi
if ! grep -Fq 'result.moves.size != frame.shapes.size' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: incomplete visible hand must not be recommended as safe' >&2
  exit 1
fi
if ! grep -Fq 'android:canPerformGestures="true"' app/src/main/res/xml/accessibility.xml; then
  echo 'FAIL: auto mode needs accessibility gesture permission' >&2
  exit 1
fi

# Prevent regressions of the v1.3.3 yellow-T occlusion failure.
# Auto controls must be BELOW the play area; alignment jumps must be
# bounded rather than sending the full measured pixel error at once.
if ! grep -Fq 'params.gravity=if(automatic) Gravity.BOTTOM or Gravity.END' app/src/main/java/com/pivotry/blockauto/AutoService.kt; then
  echo 'FAIL: floating controls can obscure held blocks in auto mode' >&2
  exit 1
fi
if ! grep -Fq 'val step=dragResponse.correction(dx,dy,found.x,found.y,' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: bounded held-block position correction missing' >&2
  exit 1
fi

# v1.3.5 diagnostic: fixed-position floating controls can hide the bottom
# two of five occupied block centers even when docked below the board.
# During automatic play the panel AND highlight must be invisible in the
# captured frame, not merely moved to another fixed position.
if ! grep -Fq 'val hide = autoDock && running' app/src/main/java/com/pivotry/blockauto/AutoService.kt; then
  echo 'FAIL: autoplay capture-safe panel state missing' >&2
  exit 1
fi
if ! grep -Fq 'floating?.visibility=if(hide) View.INVISIBLE else View.VISIBLE' app/src/main/java/com/pivotry/blockauto/AutoService.kt; then
  echo 'FAIL: control panel can still hide dragged block centers' >&2
  exit 1
fi
if ! grep -Fq 'guideView?.visibility=if(hide) View.INVISIBLE else View.VISIBLE' app/src/main/java/com/pivotry/blockauto/AutoService.kt; then
  echo 'FAIL: highlight overlay can still pollute autoplay captures' >&2
  exit 1
fi
if ! grep -Fq 'val x=if(visible)' app/src/main/java/com/pivotry/blockauto/AutoService.kt; then
  echo 'FAIL: cancelling a hidden pointer could tap through to the game' >&2
  exit 1
fi

# A full intact sprite covering its own selected tray MUST be eligible for
# a motion-confirmed pickup on the FIRST probe; no extra reverse gesture
# is allowed merely to bypass source-thumbnail novelty.
if ! grep -Fq 'val sourceOverlapRecovery=if(phase==Phase.PICKED && directed==null)' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: early source-thumbnail-aware pickup fallback missing' >&2
  exit 1
fi
# Hybrid drag: only a successfully verified board transition may
# upgrade the normalized screen/slot model; direct gestures must remain
# held until the strict final target-preview check authorizes release.
if ! grep -Fq 'if(dragCalibration.recordVerified(sample))' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: calibration update must be linked to independently verified placement' >&2
  exit 1
fi
if ! grep -Fq 'dragCalibration.predict(shape.slot,shape.piece,frame.cell' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: verified-only fast path not wired into controller' >&2
  exit 1
fi
if ! grep -Fq 'if (phase == Phase.ALIGN && snappedToTarget)' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: strictly verified release gate missing' >&2
  exit 1
fi

# v1.4.1 ALIGN failure from uploaded diagnostic (8): the visible sprite
# traveled 1.5x the input finger delta. The motor response must be measured
# from a uniquely moving whole shape and cap any predicted screen overshoot.
if ! grep -Fq 'dragResponse.observe(probeDX,probeDY,' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: pixel response gain not learned from full-shape pickup evidence' >&2
  exit 1
fi
if ! grep -Fq 'float right=width-pieceWidth*cell-pad' app/src/main/java/com/pivotry/blockauto/core/DragResponse.java; then
  echo 'FAIL: held-block rectangle may leave captured right edge' >&2
  exit 1
fi
if ! grep -Fq 'measuredSpriteResponseRegression();' tests/java/com/pivotry/blockauto/core/CoreChecks.java; then
  echo 'FAIL: uploaded 1.5x overshoot regression no longer tested' >&2
  exit 1
fi
