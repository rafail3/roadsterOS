# roadsterOS

An Android-based shell for aftermarket car head units built on the MediaTek 8227L platform (Android 8.1, 1 GB RAM). It runs as the home app and device owner, with its own screens for Bluetooth media, radio, phone, reverse camera, the car's dash display and settings.

## Requirements

- JDK 21, Android SDK (platform 36, build-tools 36.0.0), Android Emulator with the `android-27` x86 image for the simulator build.
- A head unit on the `8227L_demo` platform with ADB over network enabled, for the device build.
- The helper scripts under `sim/` and `scripts/` are PowerShell.

## Build

    ./gradlew test assembleSimDebug assembleDeviceDebug

## Run in the emulator

    ./sim/create-avd.ps1
    ./gradlew installSimDebug
    adb shell cmd package set-home-activity ro.rdstr.os/.HomeActivity
    ./sim/send.ps1 -File ./sim/recordings/incoming-call.jsonl

Events can also be sent one at a time: `./sim/send.ps1 ReverseGear '{"on":true}'`.
`./scripts/check-device.ps1` prints memory use and cold start time of the installed app.

## Modules

- `core-car` — `CarBus` interface, event and command types, JSON Lines journal, rules engine.
- `car-sim` — simulator: journal replay and an `adb` broadcast receiver (`ro.rdstr.sim`).
- `car-vendor` — adapter to the head unit's vendor services.
- `core-ui` — design tokens and custom views.
- `app` — service, home activity, screens. Flavors: `sim`, `device`.

## License

MIT
