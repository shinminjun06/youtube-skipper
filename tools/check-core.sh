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

# Guard against silently regressing to advice-only mode: the planner must
# actually drive the held pointer, then verify the result, and never auto-play
# a knowingly incomplete tray.
if ! grep -Fq 'if (phase == Phase.PICKED || phase == Phase.ALIGN) { align(image,now,epoch); return }' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: held-block observation path missing' >&2
  exit 1
fi
if ! grep -Fq 'service.beginDrag(' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: autoplay drag action missing' >&2
  exit 1
fi
if ! grep -Fq 'result.moves.size != frame.shapes.size' app/src/main/java/com/pivotry/blockauto/CaptureService.kt; then
  echo 'FAIL: unsafe partial-hand autoplay not gated' >&2
  exit 1
fi
