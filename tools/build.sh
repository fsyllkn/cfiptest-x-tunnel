#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="${WORK:-$ROOT/work}"
BASE="$WORK/upstream-ui"
BINDMOD="$WORK/bindings"
ANDROID="$WORK/android"

BASE_REPO="https://github.com/hhsw2015/x-tunnel.git"
BASE_COMMIT="1cff94567b38fe0fc0c19c6c5ccc041f8d304dd2"
MOBILE_VERSION="v0.0.0-20251126181937-5c265dc024c4"

rm -rf "$WORK"
mkdir -p "$WORK"

: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"

export GOPATH="${GOPATH:-$HOME/go}"
export PATH="$GOPATH/bin:$PATH"

echo '[1/8] Fetch UI scaffold only'
git clone --filter=blob:none "$BASE_REPO" "$BASE"
git -C "$BASE" checkout "$BASE_COMMIT"
python3 "$ROOT/tools/patch_reference_ui.py" "$BASE"

echo '[2/8] Fetch and verify known-good reference native binaries'
REFERENCE_APK_URL="https://github.com/hhsw2015/x-tunnel/releases/download/v.13/x-tunnel-arm64-v8a.apk"
REFERENCE_DIR="$WORK/reference-apk"
REFERENCE_APK="$REFERENCE_DIR/reference.apk"
mkdir -p "$REFERENCE_DIR"
curl -L --fail --retry 3 --retry-delay 2 -o "$REFERENCE_APK" "$REFERENCE_APK_URL"
unzip -q "$REFERENCE_APK" 'lib/arm64-v8a/libgojni.so' 'lib/arm64-v8a/libhev-socks5-tunnel.so' -d "$REFERENCE_DIR"
REFERENCE_LIB_DIR="$REFERENCE_DIR/lib/arm64-v8a"

EXPECTED_GO="f2d46d06ba36c91925ac2e550abe9f7c52100322e2adfcbd55a2c6568afe0a65"
EXPECTED_HEV="776ca62f76817d0fc0f3fe395a223c016643cd82bf1b2b3c55176a67d15c98e1"
ACTUAL_GO=$(sha256sum "$REFERENCE_LIB_DIR/libgojni.so" | awk '{print $1}')
ACTUAL_HEV=$(sha256sum "$REFERENCE_LIB_DIR/libhev-socks5-tunnel.so" | awk '{print $1}')
[[ "$ACTUAL_GO" == "$EXPECTED_GO" ]] || { echo "reference libgojni.so hash mismatch: $ACTUAL_GO" >&2; exit 1; }
[[ "$ACTUAL_HEV" == "$EXPECTED_HEV" ]] || { echo "reference libhev-socks5-tunnel.so hash mismatch: $ACTUAL_HEV" >&2; exit 1; }
go version -m "$REFERENCE_LIB_DIR/libgojni.so" | grep -F 'github.com/xtaci/smux' | grep -F 'v1.5.35'
strings "$REFERENCE_LIB_DIR/libhev-socks5-tunnel.so" | grep -qx 'com/x/tunnel/TProxyService'

echo '[3/8] Generate ABI-matched Java GoMobile bindings'
mkdir -p "$BINDMOD/tunnel"
cp "$ROOT/stub/tunnel/tunnel.go" "$BINDMOD/tunnel/tunnel.go"
cat > "$BINDMOD/go.mod" <<'MOD'
module tunnelbindings

go 1.25.0
MOD

go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
gomobile init
(
  cd "$BINDMOD"
  go get "golang.org/x/mobile@$MOBILE_VERSION"
  gomobile bind -target=android/arm64 -androidapi 24 -javapkg com.x.tunnel     -o "$BINDMOD/tunnel-bindings.aar" ./tunnel
)
unzip -p "$BINDMOD/tunnel-bindings.aar" classes.jar > "$BINDMOD/tunnel-bindings.jar"
for cls in Tunnel ECHPool GlobalConfig ProxyConfig UDPAssociation; do
  jar tf "$BINDMOD/tunnel-bindings.jar" | grep -q "com/x/tunnel/tunnel/${cls}.class"
done

echo '[4/8] Build isolated CFIP scanner executable'
CFIP_OUT="$WORK/libcfipscan.so"
CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
(
  cd "$ROOT"
  GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$CC"     go build -buildmode=pie -trimpath -ldflags='-s -w' -o "$CFIP_OUT" ./cfipcmd
)
file "$CFIP_OUT"
readelf -h "$CFIP_OUT" | grep -E 'Type:.*DYN'

echo '[5/8] Assemble Android source project'
mkdir -p "$ANDROID/app/src" "$ANDROID/app/libs"
cp -a "$BASE/x-tunnel-android-gui-src/src/main" "$ANDROID/app/src/main"
rm -rf "$ANDROID/app/src/main/jni"
mkdir -p "$ANDROID/app/src/main/jniLibs/arm64-v8a"
cp "$REFERENCE_LIB_DIR/libgojni.so" "$ANDROID/app/src/main/jniLibs/arm64-v8a/"
cp "$REFERENCE_LIB_DIR/libhev-socks5-tunnel.so" "$ANDROID/app/src/main/jniLibs/arm64-v8a/"
cp "$CFIP_OUT" "$ANDROID/app/src/main/jniLibs/arm64-v8a/libcfipscan.so"
cp "$BINDMOD/tunnel-bindings.jar" "$ANDROID/app/libs/tunnel-bindings.jar"
cp "$ROOT/overlay/java/com/x/tunnel/"*.java "$ANDROID/app/src/main/java/com/x/tunnel/"

cat > "$ANDROID/settings.gradle" <<'GRADLE'
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = 'XTunnelApkBaseCFIP'
include ':app'
GRADLE

cat > "$ANDROID/build.gradle" <<'GRADLE'
plugins {
    id 'com.android.application' version '8.7.3' apply false
}
GRADLE

cat > "$ANDROID/gradle.properties" <<'GRADLE'
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
GRADLE

cat > "$ANDROID/app/build.gradle" <<'GRADLE'
plugins { id 'com.android.application' }

android {
    namespace 'com.x.tunnel'
    compileSdk 35

    defaultConfig {
        applicationId 'com.x.tunnel'
        minSdk 24
        targetSdk 35
        versionCode 3
        versionName '1.0-apkbase-cfip'
        ndk { abiFilters 'arm64-v8a' }
    }

    buildTypes {
        debug { minifyEnabled false }
        release { minifyEnabled false }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging true
            keepDebugSymbols += ['**/libcfipscan.so']
        }
    }
}

dependencies {
    implementation files('libs/tunnel-bindings.jar')
    implementation 'androidx.core:core:1.13.1'
}
GRADLE

printf 'sdk.dir=%s
' "$ANDROID_HOME" > "$ANDROID/local.properties"

echo '[6/8] Compile APK'
cd "$ANDROID"
gradle --no-daemon clean :app:assembleDebug

APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"
OUT="$ROOT/x-tunnel-apkbase-cfip-arm64-debug.apk"
cp "$APK" "$OUT"

echo '[7/8] Verify packaged X-Tunnel core is byte-for-byte reference APK'
VERIFY="$WORK/verify"
rm -rf "$VERIFY" && mkdir -p "$VERIFY"
unzip -q "$OUT" 'lib/arm64-v8a/*' -d "$VERIFY"
PACKAGED_GO=$(sha256sum "$VERIFY/lib/arm64-v8a/libgojni.so" | awk '{print $1}')
PACKAGED_HEV=$(sha256sum "$VERIFY/lib/arm64-v8a/libhev-socks5-tunnel.so" | awk '{print $1}')
[[ "$PACKAGED_GO" == "$EXPECTED_GO" ]] || { echo 'packaged libgojni changed' >&2; exit 1; }
[[ "$PACKAGED_HEV" == "$EXPECTED_HEV" ]] || { echo 'packaged libhev changed' >&2; exit 1; }
strings "$VERIFY/lib/arm64-v8a/libgojni.so" | grep -Fq 'github.com/xtaci/smux'
strings "$VERIFY/lib/arm64-v8a/libhev-socks5-tunnel.so" | grep -qx 'com/x/tunnel/TProxyService'
readelf -h "$VERIFY/lib/arm64-v8a/libcfipscan.so" | grep -E 'Type:.*DYN'

! grep -q 'FOREGROUND_SERVICE_SPECIAL_USE.*minSdkVersion' "$ANDROID/app/src/main/AndroidManifest.xml"
! grep -A2 -B1 'FOREGROUND_SERVICE_SPECIAL_USE' "$ANDROID/app/src/main/AndroidManifest.xml" | grep -q 'minSdkVersion'

echo '[8/8] Final checksums'
sha256sum "$OUT" > "$OUT.sha256"
echo "Built: $OUT"
cat "$OUT.sha256"
