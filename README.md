# Bridge for Android

The Android version of [Bridge](https://github.com/m4hdisa/Bridge-app).

- **Links:** vless://, vmess://, trojan://, ss://, hysteria2:// and tuic://, and subscriptions, including a BPB panel's own address. Links that need Xray (xhttp, VLESS Encryption, fragment settings) say so; Xray comes in a later version.
- **Auto:** picks the fastest working server and keeps checking. Switching servers while connected needs no reconnect.
- **Per-App:** Android itself keeps the apps you leave out off the VPN.
- **Iranian sites skip the VPN**, with the same lists as on Windows ([Chocolate4U/Iran-sing-box-rules](https://github.com/Chocolate4U/Iran-sing-box-rules)).
- **Free public configs** from Bridge's daily list ([Bridge-configs](https://github.com/m4hdisa/Bridge-configs)), with a warning first.
- Measuring servers, testing the connection (delay, exit IP and country), the log, Persian and English.

Bridge's own requests (subscriptions, measuring) stay outside the VPN. When a list is blocked, Bridge fetches it through the VPN's local proxy, which only Bridge can use (it has a random password for each connection).

## Building

GitHub Actions builds everything; see `.github/workflows/build.yml`:

1. builds sing-box's Android library (`libbox.aar`) from source, without the parts Bridge doesn't use (Tailscale, NaiveProxy, OpenVPN...), so each APK is much smaller;
2. runs the unit tests and checks every generated config with `sing-box check`;
3. builds one APK per processor type: `arm64-v8a` (most phones), `armeabi-v7a` (older phones) and `x86_64` (the emulator test);
4. starts a VLESS server on the build machine and runs `ConnectTest` on an Android 14 emulator against it.

Release APKs come out unsigned and are signed with the release key, which is kept outside this repository. To build by hand, put `libbox.aar` in `app/libs/` and the two `.srs` files in `app/src/main/assets/rules/`, then run `gradle :app:assembleDebug` with JDK 17.

## Credits

- [sing-box](https://github.com/SagerNet/sing-box) (GPL-3.0-or-later) runs the VPN. This app is not associated with sing-box.
- [Iran-sing-box-rules](https://github.com/Chocolate4U/Iran-sing-box-rules) for the Iranian site and address lists.

## License

GPL-3.0-or-later. See `LICENSE`.
