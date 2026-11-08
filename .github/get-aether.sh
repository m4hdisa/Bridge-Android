#!/usr/bin/env bash
# Puts Aether (Free WARP, AGPL-3.0, github.com/CluvexStudio/Aether) and the Psiphon client it
# carries (GPL-3.0) in app/src/main/jniLibs as libaether.so and libpsiphon.so, from Aether's
# official Android builds, checked against known hashes. Android runs programs only from the
# app's library folder, so they go there under a library's name, like Xray. Their licenses go
# in the APK's assets. Aether's Android builds need Android 10; Bridge offers WARP from there.
set -euo pipefail
version=2.4.0
jni=app/src/main/jniLibs
licenses=app/src/main/assets/licenses
tmp=$(mktemp -d)
while read -r abi file sha; do
  curl -fsSL -o "$tmp/$file" "https://github.com/CluvexStudio/Aether/releases/download/v$version/$file"
  echo "$sha  $tmp/$file" | sha256sum -c -
  rm -rf "$tmp/x" && mkdir "$tmp/x"
  tar xzf "$tmp/$file" -C "$tmp/x" aether pt/psiphon-tunnel-core LICENSES
  mkdir -p "$jni/$abi" "$licenses"
  install -m 755 "$tmp/x/aether" "$jni/$abi/libaether.so"
  install -m 755 "$tmp/x/pt/psiphon-tunnel-core" "$jni/$abi/libpsiphon.so"
  for l in aether-LICENSE.txt THIRD-PARTY-NOTICES.txt psiphon-tunnel-core-LICENSE.txt psiphon-tunnel-core-THIRD-PARTY-NOTICES.txt; do
    cp "$tmp/x/LICENSES/$l" "$licenses/$l"
  done
done <<EOF
arm64-v8a aether-android-arm64.tar.gz 02f1a1d673cf2f3e5592c90ce7ef4d6f03e218d41a333bdd175e0da92ce2073c
armeabi-v7a aether-android-armv7.tar.gz 13a170d175eab5a64d69c6fa3c174430754f01171d616a00eba0b3598315da1d
x86_64 aether-android-x86_64.tar.gz b8d18baf6b1efe120d04109f819d08ca822c1fafeb3d61297b0e035cf095ae07
EOF
echo "Aether $version (github.com/CluvexStudio/Aether/releases/tag/v$version), unmodified" > "$licenses/AETHER-SOURCE.txt"
rm -rf "$tmp"
ls -l "$jni"/*/libaether.so "$jni"/*/libpsiphon.so
