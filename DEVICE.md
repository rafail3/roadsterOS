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
| Screen | 1280x720 at 160 dpi on 9" and 10" units | 1024x600 on some 7" units |

## Stock software
Vendor launcher (clock + dock: Radio, BT Music, Bluetooth, Music, Video, Navi); AV_IN, Car Info, ApkInstaller, CarbitLink (phone mirroring); Google apps (Chrome, Gmail, Maps, Play Store, YouTube, Voice Search).

## Platform notes
- Flashing: MediaTek SP Flash Tool over USB (scatter file + boot/system images). Firmware comes from the seller as `*.bin` / `*.upd`. A bootloop is recoverable by reflashing; data is wiped.
- Root: `adb root` on `userdebug` builds, or a Magisk-patched `boot.img` via SP Flash Tool.
- Debloat without root: `pm uninstall -k --user 0 <pkg>`; restore with `cmd package install-existing <pkg>`.
- A custom launcher works as HOME: `cmd package set-home-activity <pkg>/<activity>`.
- Bluetooth A2DP audio plays only while the vendor BT Music app is the active source.
- Factory settings menu password is usually 8888, 3368 or 7890.
