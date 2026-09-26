# Carrier IMS for Pixel (TurboIMS)

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" alt="Carrier IMS logo" />
</p>

<p align="center">
  <strong>Carrier and IMS toolkit for Google Pixel</strong><br/>
  Tune VoLTE / VoWiFi / VoNR, 5G display behavior, and network compatibility with Shizuku privileges.
</p>

<p align="center">
  <a href="README.md">中文（默认）</a> | English
</p>

<p align="center">
  <a href="https://github.com/Hsujo/carrier-ims-for-pixel/releases"><img alt="Release" src="https://img.shields.io/github/v/release/Hsujo/carrier-ims-for-pixel"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/Hsujo/carrier-ims-for-pixel"></a>
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Android%2013%2B-3DDC84">
  <img alt="Device" src="https://img.shields.io/badge/Device-Pixel%20Tensor-blue">
  <img alt="Permission" src="https://img.shields.io/badge/Requires-Shizuku-orange">
</p>

## Fork Note

- This repository is a fork of [ryfineZ/carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel).
- The upstream ad popup, donation / ad-free unlock and business cooperation pages have been removed; only the functional screens remain.
- The in-app update checker and installer have been removed. Get new builds from this repository's [Releases](https://github.com/Hsujo/carrier-ims-for-pixel/releases).
- "Submit Issue / Open Repo" point to this repository.

## Positioning

This project is a continuously maintained branch based on [Mystery00/TurboIMS](https://github.com/Mystery00/TurboIMS), with major usability and compatibility improvements for Mainland China and cross-region use cases.

Recent improvements include:

- network diagnostics with side-by-side App vs CarrierConfig readback
- denser card layout and tighter spacing for faster operation

## Screenshots

<p align="center">
  <img src="docs/Screenshot1.png" width="46%" alt="screenshot-1" />
  <img src="docs/Screenshot2.png" width="46%" alt="screenshot-2" />
</p>

## Feature Matrix

| Module | Capability | Notes |
|---|---|---|
| System Info | app/device/patch/Shizuku status | quick environment visibility |
| IMS Registration | status query + manual register | one-tap register workflow |
| Carrier Features | VoLTE / VoWiFi / ViLTE / VoNR / UT / Cross-SIM | realtime switches, rollback on failure |
| 5G Features | 5G NR / 5G signal threshold / 5G+ icon | optimized for common CN scenarios |
| Network Fix | captive portal one-tap fix | fixes restricted/exclamation network states |
| TikTok Fix | no-network fix for TikTok (Mainland SIM) | shown only for Mainland SIM |
| Diagnostics | logs / full config view / issue shortcut | submit issues with useful context |

## Quick Start

1. Download APK from [Releases](https://github.com/Hsujo/carrier-ims-for-pixel/releases)
2. Install and start [Shizuku](https://shizuku.rikka.app/)
3. Open app and grant Shizuku permission
4. Select SIM and toggle required features

## Requirements

- Pixel Tensor devices (Pixel 6/7/8/9/10, Fold, Tablet)
- Android 13+
- Shizuku running and authorized

## Build (Developers)

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If local signing is required, configure `local.properties`:

```properties
SIGN_KEY_STORE_FILE=/path/to/your.keystore
SIGN_KEY_STORE_PASSWORD=***
SIGN_KEY_ALIAS=***
SIGN_KEY_PASSWORD=***
```

### Sideload dev build (GitHub Actions)

- Pushing to a `claude/**` branch, merging into `master`, or running **Android Sideload Dev Build** manually builds a sideload dev APK
- Download it from Actions → Android Sideload Dev Build → the run → Artifacts → `TurboIMS-Hsujo-Dev`
- Package `io.github.vvb2060.ims.mod.hsujo`, launcher name "TurboIMS Hsujo Dev"; it installs side by side with the regular app and needs its own Shizuku authorization
- The dev build never restores saved config automatically after boot; it only writes what you trigger in the UI
- The run summary and the `Report APK identity` step show the signing certificate SHA256

#### Fixed dev signing key (optional, one-time)

Without it, every run signs with a fresh debug key, so each new dev APK has a different signature and the previous dev build must be uninstalled first. With it, all runs share one signature and updates install in place. The key is for the dev build only and unrelated to release signing; the password and alias are fixed to `android` / `androiddebugkey`, do not change them.

1. Generate the key (JDK 17+ `keytool`; Android Studio ships one under `jbr/bin/`):
   ```bash
   keytool -genkeypair -keystore sideload.jks -storetype PKCS12 -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TurboIMS Hsujo Dev"
   ```
2. Encode it as single-line Base64: Linux `base64 -w0 sideload.jks > sideload.b64`; macOS `base64 -i sideload.jks > sideload.b64`; Windows PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("sideload.jks")) | Set-Content -NoNewline sideload.b64`
3. **Settings → Secrets and variables → Actions → New repository secret**, name `SIDELOAD_KEYSTORE_BASE64`, value = the whole content of `sideload.b64`
4. Back up `sideload.jks` privately and **never commit it**; delete `sideload.b64` afterwards. `keytool -list -v -keystore sideload.jks -storepass android` shows the SHA256 fingerprint to compare with the CI output

If the secret is present but invalid (truncated Base64, wrong alias or password), the workflow fails with the reason instead of silently falling back to a throwaway key.

## FAQ

### IMS not registered

- confirm Shizuku is ready
- verify VoLTE / VoWiFi availability
- collect logs and submit an issue

### Network has signal but no internet

- check APN first
- then try the network verification fix card

### TikTok still unavailable

- TikTok fix switch only appears for Mainland SIM
- restart target app or refresh its session after changes

### Why "country code modification" was removed and replaced by "TikTok one-tap fix"

- The old "country code" flow only wrote CarrierConfig override `sim_country_iso_override_string`; it did not truly modify baseband-level MCC/MNC.
- Real network identity values (for example `gsm.operator.numeric` and registered MCC/MNC) are usually not changed by this override, so it is not a stable or universal "change country code" method.
- In practice, TikTok availability was not determined by "switching to another country", but by setting ISO to an abnormal value, which could trigger an app-side identification fallback path and bypass part of SIM-region checks.
- Based on this actual behavior, the project changed the entry to a clearer switch: "Fix TikTok No Network", to avoid implying that the app can truly rewrite carrier identity.
- This behavior depends on target app versions and risk-control policy, and may change over time. It is provided for compatibility troubleshooting and testing only.

## Changelog

- Full changelog: [CHANGELOG.md](CHANGELOG.md)
- Releases: [GitHub Releases](https://github.com/Hsujo/carrier-ims-for-pixel/releases)

## Credits

- [ryfineZ/carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel)
- [Mystery00/TurboIMS](https://github.com/Mystery00/TurboIMS)
- [vvb2060/Ims](https://github.com/vvb2060/Ims)
- [kyujin-cho/pixel-volte-patch](https://github.com/kyujin-cho/pixel-volte-patch)
- [nullbytepl/CarrierVanityName](https://github.com/nullbytepl/CarrierVanityName)

## Disclaimer

This app modifies carrier-related system configuration for learning, testing, and personal tuning purposes. Use at your own risk.

## License

Apache-2.0
