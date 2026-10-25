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
if ! grep -Fq 'val stepY=DragStep.axis(dy,frame.cell)' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: bounded held-block position correction missing' >&2
  exit 1
fi
