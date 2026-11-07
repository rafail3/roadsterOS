package ro.rdstr.ui

// The contract between the browser emulator and the display app, checked at build time by rdstr.apk
// (`:core-ui:checkApkLogic`, report in core-ui/build/reports/rdstr/apk-logic.md).

/**
 * This code is the Android implementation of values the emulator's screen takes from `emulator/car/`, named
 * `module.export` ("store.setBluetooth", "store.useEmulator"). A function needs a body that is more than `TODO()`.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class Emulates(vararg val names: String)

/**
 * This view is the Android side of the emulator's components (file names in `emulator/components`, "Drawer.tsx").
 * Every `ShellAction` and `CarCommand` those components dispatch must be sent from here too.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Mirrors(vararg val components: String)
