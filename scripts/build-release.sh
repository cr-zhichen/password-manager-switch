#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_TAG:?请设置 RELEASE_TAG，例如 v1.0.2-beat.1}"
: "${BUILD_NUMBER:?请设置正整数 BUILD_NUMBER}"
: "${ANDROID_KEYSTORE_PASSWORD:?请配置 ANDROID_KEYSTORE_PASSWORD}"

gradle --no-daemon --no-configuration-cache lintRelease testReleaseUnitTest assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
build_tools="${ANDROID_HOME:?请通过 mise 运行}/build-tools/36.0.0"
signature="$("$build_tools/apksigner" verify --verbose --print-certs "$apk")"
fingerprint="$(printf '%s\n' "$signature" | awk '/Signer #1 certificate SHA-256 digest:/ {print $NF}')"
if [[ "$fingerprint" != "$(cat signing/certificate-sha256.txt)" ]]; then
  echo 'APK 签名与发布证书不一致。' >&2
  exit 1
fi

badging="$("$build_tools/aapt" dump badging "$apk")"
expected_package="package: name='cn.zgccrui.passwordswitch' versionCode='$((1000 + BUILD_NUMBER))' versionName='${RELEASE_TAG#v}'"
if [[ "$badging" != "$expected_package"* || "$badging" == *application-debuggable* ]]; then
  echo 'APK 包名、版本或发布类型不符合预期。' >&2
  exit 1
fi

mkdir -p dist
filename="password-manager-switch-${RELEASE_TAG}.apk"
cp "$apk" "dist/$filename"
cd dist
shasum -a 256 "$filename" > "$filename.sha256"
printf '签名和版本校验通过：%s\n证书 SHA-256：%s\n' "$filename" "$fingerprint"
