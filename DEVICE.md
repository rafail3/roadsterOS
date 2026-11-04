# 8227L_demo platform reference

Facts shared by aftermarket car head units built on the MediaTek `8227L_demo` platform. Values are typical; confirm them on a given unit with `adb shell getprop`, `wm size` and `wm density`.

## Identity
| Item | Value | Note |
|---|---|---|
| Platform | **8227L_demo** (MediaTek "alps") | widespread low-cost head-unit platform |
| SoC | MediaTek MT8127 / AutoChips AC8227L | quad Cortex-A7 ~1.3 GHz, Mali-450 GPU, 32-bit (`armeabi-v7a`) |
| RAM | 1 GB | about 200 MB free on a stock system |
| Storage | 16 GB eMMC | |
| Android shown | 9 or 10 | version string set by sellers; the build is `alps-mp-o1.mp5` = Android 8.1 (API 27) |
| Build type | `userdebug` | `adb root` usually works |
| Kernel | 3.18.x | |
| Vendor stack | AutoChips (`com.autochips.*`) | e.g. `com.autochips.bluetooth` |
| Screen | 1024x600 at 240 dpi or 1280x720 at 160 dpi, depending on the panel | check with `wm size` and `wm density` |

## Stock software
Vendor launcher (clock + dock: Radio, BT Music, Bluetooth, Music, Video, Navi); AV_IN, Car Info, ApkInstaller, CarbitLink (phone mirroring); Google apps (Chrome, Gmail, Maps, Play Store, YouTube, Voice Search).

## Platform notes
- Flashing: MediaTek SP Flash Tool over USB (scatter file + boot/system images). Firmware comes from the seller as `*.bin` / `*.upd`. A bootloop is recoverable by reflashing; data is wiped.
- Root: `adb root` on `userdebug` builds (some builds refuse it), or a Magisk-patched `boot.img` via SP Flash Tool.
- ADB over WiFi listens on port 5555 once USB debugging is on.
- Debloat without root: `pm uninstall -k --user 0 <pkg>`; restore with `cmd package install-existing <pkg>`.
- A custom launcher works as HOME: `cmd package set-home-activity <pkg>/<activity>`.
- Bluetooth A2DP audio plays only while the vendor BT Music app is the active source.
- Factory settings menu password is usually 8888, 3368 or 7890.

## Vendor stack `com.ts.*`
Some units ship the car stack as `com.ts.MainUI` (`/system/priv-app/MainUI`) instead of separate `com.autochips.*` apps. It owns the system bar, radio, Bluetooth screens, AV_IN, the reverse camera, the CAN box link and the MCU link.

| Input | Path | What Android sees |
|---|---|---|
| Frame Home, Back | MCU -> `com.ts.MainUI` | injected `KEYCODE_HOME`, `KEYCODE_BACK` |
| Frame Vol+ / Vol- | MCU -> `com.ts.MainUI` | volume level 0..30, one level per key; written to `STREAM_MUSIC` (0..100) through a fixed table; held keys repeat about every 210 ms |
| Frame Power, short press | MCU -> `com.ts.MainUI` | mute toggle, broadcast `BROADCAST_MAINUI_MUTE_STATE` |
| Steering keys | CAN box -> `com.ts.MainUI` | volume, AVRCP previous / next, source change, mute |
| Headlights | MCU | broadcast `BROADCAST_ADAS_LIGHT_STATE` |
| Reverse gear | native `backcar_service` | `com.ts.backcar.BackcarMainActivity` |

- Bluetooth music is a standard `MediaSession` of `com.android.bluetooth` (`A2dpMediaBrowserService`) with title, artist, album, genre, track number and duration, and the position about once per second. Android 8.1 does not request cover art.
- The Bluetooth stack aborts on BLE advertising requests (`btm_ble_multi_adv`); Google Play Services issues them through Nearby. Removing Play Services for user 0 avoids it.
- The tuner runs on the MCU: FM 87.5-108 MHz in 0.1 MHz steps, AM 522-1620 kHz in 9 kHz steps, RDS PS, PTY, TP, TA and AF.
- With ACC off for about 30 s the unit powers down completely; a cold boot takes about 37 s.
