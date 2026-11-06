package ro.rdstr.ui.shell

/**
 * Where a screen's view sits in the shell, back to front: the `.screen` children of Screen.tsx ordered by their
 * z-index and, within one z-index, by their place in the DOM (comments give the CSS z-index). Android draws a
 * FrameLayout's children in this order, so no view needs an elevation.
 *
 * [CLOCK] and [AOD_DIAL] are not layers of the screen but places inside the shell's own views: the clock block in the
 * home field above the app icons, and the dial of the always-on display.
 */
enum class Layer {
    /** z auto: `div.ambient`, the glow behind everything. */
    AMBIENT,
    /** z 1: `nav.rail`, `section.field`, `section.panel`. */
    RAIL, FIELD, PANEL,
    /** z 2: the full-screen apps, in DOM order (the sound window, between Phone Screen and Radio in the DOM, is z 4). */
    MUSIC, PHONE, SETTINGS, PHONE_SCREEN, RADIO, MAPS,
    /** z 3: `header.bar`. */
    BAR,
    /** z 4: the sound window, the devices and Wi-Fi sheets, the full call, the camera. */
    SOUND, DEVICES, NETWORKS, CALL, CAMERA,
    /** z 5: the open apps, the drawer's scrim and the drawer. */
    RECENTS, SCRIM, DRAWER,
    /** z 6: the nav bar and edge strips (Gestures.tsx), the voice assistant, the message banner, the volume HUD. */
    GESTURES, VOICE, BANNER, VOLUME,
    /** z 7: the location and restart prompts, the call bar, the frame keys' bulges. */
    LOCATION_PROMPT, RESTART_PROMPT, CALL_BAR, BULGES,
    /** z 8: the connection notice, the map offer, the boot and setup screens, the keyboard. */
    NOTICE, MAP_OFFER, STARTUP, KEYBOARD,
    /** z 9: the brightness dimmer, the always-on display. */
    DIMMER, AOD,
    /** z 10: the black of the screen switched off. */
    SCREEN_OFF,
    /** z 11: the developer overlays (touches, frame rate). */
    DEV,

    /** Inside the field: the clock block (Clock.tsx), centred above the app icons. */
    CLOCK,
    /** Inside the always-on display: the analog dial (Clock.tsx `Dial`), 320 x 320. */
    AOD_DIAL,
}
