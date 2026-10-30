# Mazda 6 (2010, GH) — head unit fact sheet

Updated: 2026-10-30. Source: box label, Settings screens photographed in the car (see info-display-original/), xda/android-headunits research.

## Identity
| Item | Value | Note |
|---|---|---|
| Box model | XTRONS ICE/HU/PC98M6ML | 9", Mazda 6 2008-2012 fascia, no Bose support |
| Platform | **8227L_demo** (MediaTek "alps") | the most common cheap head-unit platform, big xda community |
| SoC | MediaTek MT8127 / AutoChips AC8227L | quad Cortex-A7 ~1.3 GHz, Mali-450 GPU, 32-bit (armeabi-v7a) |
| RAM | 0.98 GB | 763 MB avg used, 217 MB free, 29 apps in 3 h |
| Storage | 16 GB eMMC | 6.87 GB used |
| Android shown | 10 | **fake**: build is `alps-mp-o1.mp5` = Android 8.1 Oreo (API 27). Verify with CPU-Z |
| Build | `full_8227L_demo-userdebug 10 O11019 1571038753 userdebug` | userdebug → `adb root` usually works |
| Kernel | 3.18.79, built Dec 18 2020 (Linaro GCC 6.3) | 2014-era kernel, no mainline Linux support |
| Security patch | Oct 5 2019 | |
| Serial | (mascat, în notițe locale) | |
| WiFi/BT MAC | (mascat) | same for both → combined WiFi/BT chip |
| Vendor stack | AutoChips (`com.autochips.*`) | e.g. `com.autochips.bluetooth` |
| Resolution | 1280x720 @ 160 dpi (typical for 8227L 9") | confirm via `wm size` |

## Stock apps seen
Launcher (clock + dock: Radio, BT Music, Bluetooth, Music, Video, Navi); AV_IN, Car Info, ApkInstaller, CarbitLink (phone mirroring), Chrome, Email, File Manager, Gallery, Google, GpsTest, Instructions, Maps, Play Store, Settings, Voice Search, YouTube.

## State
- Developer options: ON (2026-10-30). USB debugging / ADB over network: not yet enabled.
- WiFi: no IP yet (not connected to any network).
- Root: not checked yet.

## What the community already knows about 8227L
- Android version string is edited by sellers; real = 8.1 (API 27).
- Flashing: MediaTek SP Flash Tool over USB (scatter file + boot/system images). Firmware files from seller as `*.bin` / `*.upd`. Bootloop = recoverable by reflash, data wiped.
- Root: `adb root` (userdebug) or Magisk-patched boot.img via SP Flash Tool.
- Debloat without root: `pm uninstall -k --user 0 <pkg>`; restore with `cmd package install-existing <pkg>`.
- Custom launcher works as HOME: `cmd package set-home-activity <pkg>/<activity>`.
- Reference: https://github.com/sipamungkas/headunit-flat-launcher (plain-Java launcher for 8227L, 24 MB PSS vs 54 MB stock, 88 packages removed, 0.7 s cold start).
- Known quirk: BT A2DP audio plays only while BT Music app is active source.

## Constraints
- No extra hardware, use only what exists.
- Work step by step, confirm before each step.

## Open questions
- Exact MCU version (Factory settings menu, password usually 8888 / 3368 / 7890 on 8227L).
- Which signals the CAN decoder box delivers (steering keys, reverse, more?).
- Root availability (`adb root`).
- Which vendor package handles MCU/car events (candidate: `com.autochips.*`).
