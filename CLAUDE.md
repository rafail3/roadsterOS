# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

roadsterOS (short: rdstr) is an Android-based shell for aftermarket car head units on the MediaTek **8227L_demo** platform: quad Cortex-A7, 1 GB RAM, 16 GB eMMC, Android 8.1 (API 27) even though Settings may claim 10, 32-bit `armeabi-v7a`, kernel 3.18, AutoChips vendor stack (`com.autochips.*`). Platform reference: `DEVICE.md`. The project targets every unit on this platform; one unit is used as the development reference.

The vendor kernel and drivers stay; everything the driver sees and everything that runs in the background is replaced. Our app is the only HOME app and the device owner; vendor and Google bloat is removed per-user; vendor apps that talk to the car (Bluetooth, radio, AV_IN, CAN) stay installed but hidden and are driven from our UI through the `CarBus` interface.

## Working rules

- **One step at a time.** Describe the next step and wait for approval before doing it. A general "go ahead" does not cover the following step.
- **Research before acting** on anything touching the unit (packages to remove, props to change): check what the 8227L community (xda, android-headunits.com) already learned.
- Explain hardware and Android-platform concepts in plain software vocabulary, in Romanian, in chat. No analogies from other industries.
- Decisions are approved from a short in-chat summary, never by asking to read a file. Specs, plans and photos live in the parent folder, outside this repository.
- No additional hardware is assumed. Engine data (rpm, temperatures) is out of scope.

## Public text (commits, PR titles and bodies, issues, releases, README, any tracked file)

Everything tracked in this repository is written in English: code, comments, resource strings, scripts, docs, workflow files. The repository is public. Public text states only what was implemented and with which technologies, short and neutral. It never contains: reasoning behind a decision, intentions or strategy, what is missing or not working, judgements about the product, internal decisions, names of products used as design or feature references, personal details of contributors, AI attribution lines (no `Co-Authored-By`, no "Generated with" footer). Detailed reasoning goes in chat and, where it helps a developer, in code comments. Clean PR bodies before merge: GitHub copies them into the squash commit.

## Target constraints that shape every technical decision

- 1 GB RAM with ~200 MB free on stock. Budget for our app: under 30 MB PSS, cold start under 1 s, APK under 5 MB.
- `minSdk = targetSdk = 27`. No feature that needs a newer API. No native code.
- Mali-450 GPU: flat UI, no blur, no large shadows, at most two overlapping layers.
- Screen 1024x600 at 240 dpi on the reference unit. The layout is designed on a 1280x750 canvas and drawn at 0.8: `--unit-scale` in `emulator/styles/tokens.css`, density 128 inside our app on Android.
- UI stack: Kotlin + classic Android Views. Not Compose, not Flutter.

## Working with the unit

- Connection is ADB over WiFi: unit and laptop on the same hotspot, `adb connect <unit-ip>:5555`. USB ports are host-mode.
- The build is `userdebug`; try `adb root` first.
- Before removing packages: dump `pm list packages -f` and the current HOME to a dated folder under `unit-dumps/`. Remove with `pm uninstall -k --user 0 <pkg>`, restore with `cmd package install-existing <pkg>`. Never remove `com.autochips.*`, the MCU/CAN services, Settings, or anything the boot chain needs.
- Set HOME with `cmd package set-home-activity <pkg>/<activity>`; re-run after every reinstall.
- Bluetooth A2DP audio plays only while the vendor BT app is the active source. Keep `com.autochips.bluetooth` installed.
- Nothing is flashed without an explicit, separate approval and a pulled backup of the original partitions.
- Logs and dumps committed to the repo are masked first: no phone numbers, contact names, MAC addresses, serial numbers.

## Repository layout (update as code lands)

- `DEVICE.md` — hardware/software facts about the target unit.
- `app/`, `core-ui/`, `core-car/`, `core-map/`, `car-vendor/`, `car-sim/`, `companion/` — Gradle modules. `core-ui` holds the display views, one folder per screen (`shell/`, `home/`, `drawer/`, `sheets/`, `media/`, `phone/`, `overlays/`, `setup/`, `settings/`, `sound/`, `maps/`, `keyboard/`, `volume/`) on the shared kit (`kit/`); `app/.../screens/` holds one glue file per screen, registered in `Screens.kt`.
- `build-logic/` — included build with the `rdstr.display` plugin: `generateDisplayUi` writes `ro.rdstr.ui.tokens` (Tokens, Icons, Fonts) and the vector icons and fonts into `core-ui` from `emulator/styles`, `emulator/public/icons` and `emulator/public/fonts`; `checkDisplayLogic` lists emulator logic and dispatches without an Android `@Emulates`/`@Mirrors` (`-Prdstr.display.strict=true` fails on any gap); `compareParity` compares browser and Paparazzi captures.
- `sim/` — emulator AVD config, recorded `.jsonl` scenarios, `send.ps1`.
- `emulator/` — browser emulator: Next.js and React app with the screens and a car control panel, driven by `core-car` compiled to JavaScript; also the live design mockup. `styles/screen.css`, `clock.css` and `tokens.css` are the product, `styles/chrome.css` is the tool around it.
- `install/` — `install.ps1` (APK, HOME activity, the Romania map pack copied over adb); planned: `install.sh`, `rollback.ps1`, `debloat.txt`.
- `scripts/check-device.ps1` — memory, cold-start and package checks on the unit.
- `unit-dumps/` — masked package lists, props and logcat captures, dated.
- `version.txt` — current version, rewritten by release-please; `app/build.gradle.kts` derives `versionName` and `versionCode` from it.


## Git and PR conventions

Branches `type/slug`. Conventional Commits, lowercase subject, English. PR body sections: `## Problem / ## Fix / ## Impact / ## Testing`, content neutral per the public-text rule. One commit per finished sub-task, one push per task, PR opened by the agent, merged by the owner. Never weaken tests. Never watch CI in a loop.

## Build, test, run

- `./gradlew test` — unit tests (JVM).
- `./gradlew assembleSimDebug` / `assembleDeviceDebug` — APK for the emulator / for the unit.
- `./gradlew :core-car:test --tests "ro.rdstr.car.RulesTest"` — one test class.
- `./gradlew lint` — Android lint.
- `./gradlew :core-car:allTests` — core-car tests on the JVM and in JavaScript; `./gradlew :core-car:jsBrowserProductionWebpack` builds the browser bundle used by `emulator/`; `npm run dev` rebuilds it when the Kotlin sources changed.
- Browser emulator: `npm install` once, then `npm run dev` from the repo root and open `http://localhost:8787/`. `?bare` shows the 1024x600 screen alone for captures; `?freeze` stills it (noon, no animations), `?design=solid|glassy` picks the design.
- Visual parity: with the emulator running, `npm run parity:capture` (`-- --base http://localhost:<port>` for another server) writes the browser scenes to `build/parity/web/`; `./gradlew :core-ui:compareParity --no-daemon` renders the core-ui views on the JVM (Paparazzi, `core-ui/src/test/.../parity/ParityCapture.kt`) into `build/parity/android/` and compares them; report in `build/reports/parity/index.md`. Views declare their element with `View.uiKey` (`core-ui` `UiKey.kt`).
- `adb install -r app/build/outputs/apk/sim/debug/app-sim-debug.apk` then `adb shell cmd package set-home-activity ro.rdstr.os/.HomeActivity`.
