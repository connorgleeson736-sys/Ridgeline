#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../android"
# These tool paths are retained in the prepared cloud snapshot. Normal Android
# Studio users should run ./gradlew directly with their own installed SDK/JDK.
export JAVA_HOME="${JAVA_HOME:-/workspace/android-tools/jdk/jdk-21.0.8+9}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/android-tools/sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/workspace/android-tools/user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/android-tools/gradle-cache}"
if [[ ! -x "$JAVA_HOME/bin/javac" || ! -f "$ANDROID_HOME/platforms/android-36/android.jar" ]]; then
  echo 'JDK and Android SDK platform 36 are required; see android/README.md.' >&2
  exit 1
fi
if [[ $# -eq 0 ]]; then set -- :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; fi
if [[ -x /workspace/android-tools/gradle-8.13/bin/gradle ]]; then
  exec /workspace/android-tools/gradle-8.13/bin/gradle "$@"
fi
exec ./gradlew "$@"
