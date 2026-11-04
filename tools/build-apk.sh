#!/usr/bin/env bash
# Builds the APK locally with the same build number CI would use.
# Needs the Android SDK (sdk.dir in android/local.properties, or ANDROID_HOME).
# Pass a keystore to sign with the shared key: KEYSTORE=path KEYSTORE_PASSWORD=... tools/build-apk.sh
set -euo pipefail
cd "$(dirname "$0")/.."
build=$(git rev-list --count HEAD)
args=(-PwebBuild="$build")
if [ -n "${KEYSTORE:-}" ]; then
  args+=(-PkeystorePath="$(realpath "$KEYSTORE")" -PkeystorePassword="${KEYSTORE_PASSWORD:-android}" -PkeyAlias="${KEY_ALIAS:-androiddebugkey}")
fi
(cd android && ./gradlew assembleRelease "${args[@]}")
echo "android/app/build/outputs/apk/release/app-release.apk (build $build)"
