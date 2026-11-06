#!/usr/bin/env bash
# Puts the official Xray build for each processor type in app/src/main/jniLibs as libxray.so,
# checked against known hashes. Android runs programs only from the app's library folder, so
# Xray goes there under a library's name. arm32 has no Android build; the Linux one runs too,
# since it is a static program and Bridge gives it the servers' addresses itself.
set -euo pipefail
version=26.10.21
out=app/src/main/jniLibs
tmp=$(mktemp -d)
while read -r abi file sha; do
  curl -fsSL -o "$tmp/$file" "https://github.com/XTLS/Xray-core/releases/download/v$version/$file"
  echo "$sha  $tmp/$file" | sha256sum -c -
  mkdir -p "$out/$abi"
  unzip -p "$tmp/$file" xray > "$out/$abi/libxray.so"
  chmod 755 "$out/$abi/libxray.so"
done <<EOF
arm64-v8a Xray-android-arm64-v8a.zip d81604f35a56c98ea6091142c1c744f4895c7384bc2e1c85cd910612c70794e9
armeabi-v7a Xray-linux-arm32-v7a.zip 89978508fa2ebb4a1e661f2c6ed51d241c4091b3cd17f4c65fdf20baae7dedac
x86_64 Xray-android-amd64.zip 58546b1a2711164dc0db9b847fca3d3d6829b2631ab8d6105dc6790a26c80934
EOF
rm -rf "$tmp"
ls -l "$out"/*/libxray.so
