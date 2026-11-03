# Bridge for Android

The Android version of [Bridge](https://github.com/m4hdisa/Bridge-app). This is an early prototype: it connects to one VLESS server and can limit the VPN to the apps you choose.

## Building

GitHub Actions builds everything; see `.github/workflows/build.yml`. It builds sing-box's Android library (`libbox.aar`) from source, runs the tests, checks the generated configs with `sing-box check` and produces a test APK. To build by hand, put `libbox.aar` in `app/libs/` and run `gradle :app:assembleDebug` with JDK 17.

## Credits

- [sing-box](https://github.com/SagerNet/sing-box) (GPL-3.0-or-later) runs the VPN. This app is not associated with sing-box.

## License

GPL-3.0-or-later. See `LICENSE`.
