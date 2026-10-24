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

# Enforce read-only recommendation mode: no Android gesture injection.
if grep -Eq 'dispatchGesture\(|service\.beginDrag\(|service\.moveHeld\(' app/src/main/java/com/pivotry/blockauto/{AutoService.kt,CaptureService.kt}; then
  echo 'FAIL: gameplay gesture injection found in advisor mode' >&2
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
if grep -Fq 'android:canPerformGestures="true"' app/src/main/res/xml/accessibility.xml; then
  echo 'FAIL: accessibility gesture capability must be disabled' >&2
  exit 1
fi
