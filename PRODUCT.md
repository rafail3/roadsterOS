# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin with classic Android Views (API 27). Design exploration happens first as a static HTML/CSS mockup under `design/`, then the screens are built in Views.

## Users

The driver of a car with an aftermarket 8227L head unit, at the wheel, glancing at a 9" to 10" screen at arm's length, by day and by night, often while moving. Taps are imprecise. The second audience is a developer installing roadsterOS on a compatible unit.

## Product Purpose

roadsterOS replaces the vendor launcher and background services with one fast, consistent shell: home screen, Bluetooth media, radio, phone, reverse camera, the car's dash-display settings and the shell's own settings. Success is a unit that boots into roadsterOS, responds within a frame to every tap and every car event, and never shows the stock Android interface.

## Positioning

A complete shell for the 8227L platform rather than a launcher on top of it: it owns HOME, runs as device owner, drives the vendor apps through one `CarBus` interface and keeps the memory and startup budget of a 1 GB unit.

## Operating Context

- Car events arrive as `CarEvent` values (reverse gear, steering keys, illumination, ACC power, Bluetooth connection, track, playback, call, radio, dash display); commands leave as `CarCommand` values. A pure rules engine maps events to actions.
- Development runs against the simulator flavor in the Android emulator (1280x720, 160 dpi, 1 GB, Android 8.1) with recorded `.jsonl` scenarios; the device flavor runs on the unit.
- Navigation is an external app (opened full screen); the shell overlays a thin bar.

## Capabilities and Constraints

- Screen 1280x720 at 160 dpi, landscape, capacitive touch. Mali-450 GPU: no real-time blur, no large shadows, at most two overlapping translucent layers; flat UI, images at display size.
- Budget: under 30 MB PSS, cold start under 1 s, APK under 5 MB. No native code, no AndroidX, no Compose.
- No engine data (speed, rpm, temperatures) and no embedded map tile: the car does not provide them and Android cannot embed another app's view.
- Home is a single page with no horizontal paging. The top status bar pulls down into the shell's own quick-settings drawer. Android's status bar, navigation bar and settings are never shown.
- Night mode follows the car's illumination signal.
- Terminology: source (BT, radio, none), track, call, camera, drawer, rail.

## Brand Commitments

- Name: roadsterOS, short form rdstr.
- Visual direction, binding: near-black canvas, translucent glass panels with hairline borders, large tabular numerals, one cool ice-blue accent, Inter typeface, serious and minimal. Transparency is simulated over a static background; no real-time blur.

## Evidence on Hand

- `sim/recordings/*.jsonl`: five recorded car scenarios (boot with Bluetooth music, incoming call, reverse while navigating, ACC off/on, radio with steering keys).
- No real photography, no logos, no user research beyond the reference unit.

## Product Principles

- Glanceable first: one dominant element per screen, text readable at 65 cm.
- Every state the car can produce has a designed screen state: no stock dialogs, no empty surprises.
- One visual vocabulary across home, drawer, overlays and settings.
- Performance is a design constraint, not an optimization step.
- The reference unit is one of many: nothing in the UI assumes a specific car.

## Accessibility & Inclusion

Touch targets at least 56 px with 12 px gaps; text contrast at least 4.5:1 in day and night mode; no information carried by color alone.
