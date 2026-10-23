#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/core-checks
java com.sun.tools.javac.Main -d build/core-checks app/src/main/java/com/pivotry/blockauto/core/*.java tests/java/com/pivotry/blockauto/core/CoreChecks.java
java -cp build/core-checks com.pivotry.blockauto.core.CoreChecks
