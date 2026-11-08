# Bridge for Android

The Android version of [Bridge](https://github.com/m4hdisa/Bridge-app), in the same style as the Windows app. Download the APK from [Releases](https://github.com/m4hdisa/Bridge-Android/releases): `arm64-v8a` for almost every phone, `armeabi-v7a` for older ones.

- **Dashboard:** one screen that doesn't scroll: the connect button; where your traffic leaves on a map (your country and the exit country, drawn inside the app; no map service is asked), with the exit IP, city, ping, live speed and traffic used; and the chosen server, which opens the Servers page.
- **Servers:** Free WARP and Auto on top, then a tab for each subscription, with its traffic, days left, update and test, and its configs fastest first. Search, a QR code, the link and removing a config are a tap away.
- **Free WARP:** Cloudflare WARP with no server of your own, through [Aether](https://github.com/CluvexStudio/Aether)'s official Android build (Android 10 and up). The fast ways through your network are tried at once and the first that works wins. "Exit outside Iran" also waits for the two-hop ways; "WARP + Psiphon" carries Psiphon inside WARP for an exit in another country. When Cloudflare's sign-up is blocked, a starter account from Bridge's daily list connects first and your own account is then made through it.
- **Links:** vless://, vmess://, trojan://, ss://, hysteria2:// and tuic://, subscriptions (including a BPB panel's own address), JSON config files from Xray, sing-box and Clash, and QR codes (scan with the camera, or show a config's QR code).
- **Xray** for the links sing-box can't run: xhttp, VLESS Encryption, TCP with HTTP camouflage, mKCP and fragmenting. sing-box stays in charge of the VPN and reaches those servers through the official Xray, which runs beside it as on Windows.
- **Fast DNS (FakeIP):** a new site gets an instant made-up address and the server looks the real one up, so it opens without waiting. Off by default, since some apps keep the made-up addresses after a reconnect; it can be turned on in Settings. Iran's address for blocked sites (10.10.34.x) is always refused at once.
- **Auto:** picks the fastest working server and keeps checking. Switching servers while connected needs no reconnect.
- **Full VPN** by default; **Per-App** (only the apps you choose) in Settings, done by Android itself.
- **Iranian sites skip the VPN**, with the same lists as on Windows ([Chocolate4U/Iran-sing-box-rules](https://github.com/Chocolate4U/Iran-sing-box-rules)).
- **Updates:** Bridge looks for a new version on GitHub by itself and offers it; it downloads when you say so, checks the file's checksum and signature, and Android asks you before installing.
- A quick settings tile, free public configs from Bridge's daily list ([Bridge-configs](https://github.com/m4hdisa/Bridge-configs)) with a warning first, the log of every core with its errors and warnings a tap away, Persian and English.

Bridge's own requests (subscriptions, measuring, Xray's and Aether's connections) stay outside the VPN. When a list is blocked, Bridge fetches it through the VPN's local proxy, which only Bridge can use (it has a random password for each connection); Xray's local ports have one too.

## Building

GitHub Actions builds everything; see `.github/workflows/build.yml`:

1. builds sing-box's Android library (`libbox.aar`) from source, without the parts Bridge doesn't use (Tailscale, NaiveProxy, OpenVPN...), so each APK is much smaller;
2. puts the official Xray build (`.github/get-xray.sh`) and Aether's official Android build with its Psiphon client (`.github/get-aether.sh`) for each processor type in the APK, checked against known hashes;
3. runs the unit tests and checks every generated config with `sing-box check` and `xray run -test`;
4. builds one APK per processor type: `arm64-v8a` (most phones), `armeabi-v7a` (older phones) and `x86_64` (the emulator test);
5. starts a VLESS server and an Xray server (xhttp with REALITY) on the build machine, runs `ConnectTest` on an Android 14 emulator against them and through Free WARP, and keeps a screenshot of every page in English and Persian.

Release APKs come out unsigned and are signed with the release key, which is kept outside this repository. To build by hand, put `libbox.aar` in `app/libs/`, the two `.srs` files in `app/src/main/assets/rules/`, run `bash .github/get-xray.sh` and `bash .github/get-aether.sh`, then `gradle :app:assembleDebug` with JDK 17 or newer. `BRIDGE_SHOTS=<folder> gradle :app:testDebugUnitTest --tests '*PageShots'` draws every page on the build machine.

The icons come from the Windows app's icon sets through `tools/gen-icons.mjs`; the map's land, borders and country points from Natural Earth through `tools/gen-map.mjs`.

## Credits

- [sing-box](https://github.com/SagerNet/sing-box) (GPL-3.0-or-later) runs the VPN, [Xray](https://github.com/XTLS/Xray-core) (MPL-2.0) the servers only it can run, and [Aether](https://github.com/CluvexStudio/Aether) (AGPL-3.0) with [Psiphon](https://github.com/Psiphon-Labs/psiphon-tunnel-core) (GPL-3.0) Free WARP, all unmodified; their licenses are in the APK. This app is not associated with any of them, nor with Cloudflare.
- [Iran-sing-box-rules](https://github.com/Chocolate4U/Iran-sing-box-rules) for the Iranian site and address lists.
- [Natural Earth](https://www.naturalearthdata.com) (public domain) for the map.
- [ZXing](https://github.com/zxing/zxing) and [zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache-2.0) for QR codes.
- [Geist](https://github.com/vercel/geist-font) and [Vazirmatn](https://github.com/rastikerdar/vazirmatn) fonts (SIL Open Font License 1.1, see `licenses/`).
- [Lucide](https://lucide.dev) (ISC) and [Phosphor](https://phosphoricons.com) (MIT) icons; Cloudflare's logo from [Simple Icons](https://simpleicons.org) (CC0).
- How [ZedSecure](https://github.com/CluvexStudio/ZedSecure) runs several cores side by side was a reference for running Xray next to sing-box; no code is taken from it.

## License

GPL-3.0-or-later. See `LICENSE`.
