# Bridge for Android

The Android version of [Bridge](https://github.com/m4hdisa/Bridge-app), with the same pages and look as the Windows app: Dashboard, Servers, Apps, Logs and Settings.

- **Links:** vless://, vmess://, trojan://, ss://, hysteria2:// and tuic://, subscriptions (including a BPB panel's own address), and JSON config files from Xray, sing-box and Clash.
- **Xray** for the links sing-box can't run: xhttp, VLESS Encryption, TCP with HTTP camouflage, mKCP and fragmenting. sing-box stays in charge of the VPN, and reaches those servers through the official Xray, which runs beside it as on Windows.
- **Auto:** picks the fastest working server and keeps checking. Switching servers while connected needs no reconnect.
- **Per-App:** Android itself keeps the apps you leave out off the VPN.
- **Connection check:** while connected, a real request through the VPN every 30 seconds, with the exit address and country, so "connected but nothing loads" shows at once.
- **Iranian sites skip the VPN**, with the same lists as on Windows ([Chocolate4U/Iran-sing-box-rules](https://github.com/Chocolate4U/Iran-sing-box-rules)).
- **Free public configs** from Bridge's daily list ([Bridge-configs](https://github.com/m4hdisa/Bridge-configs)), with a warning first.
- Measuring servers, the log of both cores, Persian and English.

Bridge's own requests (subscriptions, measuring, Xray's connections) stay outside the VPN. When a list is blocked, Bridge fetches it through the VPN's local proxy, which only Bridge can use (it has a random password for each connection); Xray's local ports have one too.

## Building

GitHub Actions builds everything; see `.github/workflows/build.yml`:

1. builds sing-box's Android library (`libbox.aar`) from source, without the parts Bridge doesn't use (Tailscale, NaiveProxy, OpenVPN...), so each APK is much smaller;
2. puts the official Xray build for each processor type in the APK (`.github/get-xray.sh`, checked against known hashes);
3. runs the unit tests and checks every generated config with `sing-box check` and `xray run -test`;
4. builds one APK per processor type: `arm64-v8a` (most phones), `armeabi-v7a` (older phones) and `x86_64` (the emulator test);
5. starts a VLESS server and an Xray server (xhttp with REALITY) on the build machine, runs `ConnectTest` on an Android 14 emulator against them, and keeps a screenshot of every page in English and Persian.

Release APKs come out unsigned and are signed with the release key, which is kept outside this repository. To build by hand, put `libbox.aar` in `app/libs/`, the two `.srs` files in `app/src/main/assets/rules/` and run `bash .github/get-xray.sh`, then `gradle :app:assembleDebug` with JDK 17 or newer. `BRIDGE_SHOTS=<folder> gradle :app:testDebugUnitTest --tests '*PageShots'` draws every page on the build machine.

The icons come from the Windows app's icon sets through `tools/gen-icons.mjs`.

## Credits

- [sing-box](https://github.com/SagerNet/sing-box) (GPL-3.0-or-later) runs the VPN, and [Xray](https://github.com/XTLS/Xray-core) (MPL-2.0) the servers only it can run. This app is not associated with either.
- [Iran-sing-box-rules](https://github.com/Chocolate4U/Iran-sing-box-rules) for the Iranian site and address lists.
- [Geist](https://github.com/vercel/geist-font) and [Vazirmatn](https://github.com/rastikerdar/vazirmatn) fonts (SIL Open Font License 1.1, see `licenses/`).
- [Lucide](https://lucide.dev) (ISC) and [Phosphor](https://phosphoricons.com) (MIT) icons.
- How [ZedSecure](https://github.com/CluvexStudio/ZedSecure) runs several cores side by side was a reference for running Xray next to sing-box; no code is taken from it.

## License

GPL-3.0-or-later. See `LICENSE`.
