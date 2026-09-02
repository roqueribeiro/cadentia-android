#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --console=plain :app:assembleDebug "$@"
