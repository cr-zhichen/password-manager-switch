#!/usr/bin/env bash
set -euo pipefail
umask 077

keystore=signing/release.p12
password_file=.signing/release-password
if [[ -e "$keystore" || -e "$password_file" ]]; then
  echo '发布证书或密码已存在，拒绝覆盖。' >&2
  exit 1
fi

mkdir -p signing .signing
chmod 700 .signing
od -An -N48 -tx1 /dev/urandom | tr -d ' \n' > "$password_file"

keytool -genkeypair \
  -J-Dkeystore.pkcs12.keyProtectionAlgorithm=PBEWithHmacSHA256AndAES_256 \
  -J-Dkeystore.pkcs12.certProtectionAlgorithm=PBEWithHmacSHA256AndAES_256 \
  -J-Dkeystore.pkcs12.keyPbeIterationCount=600000 \
  -J-Dkeystore.pkcs12.certPbeIterationCount=600000 \
  -J-Dkeystore.pkcs12.macIterationCount=600000 \
  -storetype PKCS12 -keystore "$keystore" \
  -storepass:file "$password_file" -keypass:file "$password_file" \
  -alias release -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000 -dname 'CN=Password Manager Switch, O=cr-zhichen, C=CN'

keytool -exportcert -keystore "$keystore" \
  -storepass:file "$password_file" -alias release \
  | shasum -a 256 | awk '{print $1}' > signing/certificate-sha256.txt
chmod 644 "$keystore" signing/certificate-sha256.txt
echo '已生成加密证书 signing/release.p12；密码保存在 .signing/release-password，请单独备份。'
