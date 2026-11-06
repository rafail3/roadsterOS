package ro.rdstr.gradle.display

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogicCheckTest {
    private val components = mapOf(
        "Screen.tsx" to """
            import { bridge, type Source } from "../car/bridge";
            import { setBluetooth, useEmulator,
              shellAction, type EmulatorState } from "../car/store";
            import { Drawer } from "./Drawer";
            export function Screen() { const e = useEmulator(); return <Drawer />; }
        """.trimIndent(),
        "Drawer.tsx" to """
            import { setBluetooth, shellAction } from "../car/store";
            export function Drawer() {
              return <button onClick={() => { setBluetooth(true); shellAction({ type: on ? "OpenDevices" : "CloseDevices" }); }} />;
            }
        """.trimIndent(),
        "ControlPanel.tsx" to """import { setSpeedSweep } from "../car/store";""",
    )
    private val car = mapOf(
        "store" to """
            export interface EmulatorState { a: number }
            export function setBluetooth(on: boolean) {}
            export function shellAction(a: unknown) {}
            export function useEmulator() {}
            export function setSpeedSweep() {}
            export function useNow() {}
        """.trimIndent(),
        "bridge" to "export const bridge = {};",
    )
    private val coreCar = listOf("""
        sealed class ShellAction {
            @Serializable @SerialName("OpenDevices") data object OpenDevices : ShellAction()
            @Serializable @SerialName("CloseDevices") data object CloseDevices : ShellAction()
        }
        sealed class CarCommand {
            sealed class Bluetooth : CarCommand() {
                @Serializable @SerialName("Bt.Connect") data class Connect(val device: String) : Bluetooth()
            }
        }
    """.trimIndent())

    @Test
    fun `the screen is what Screen reaches, the control panel is not`() {
        assertEquals(setOf("Screen.tsx", "Drawer.tsx"), LogicCheck.reach(components, "Screen.tsx"))
    }

    @Test
    fun `serial names map to the kotlin a view writes`() {
        val s = LogicCheck.serialNames(coreCar)
        assertEquals("ShellAction" to "ShellAction.OpenDevices", s["OpenDevices"])
        assertEquals("CarCommand" to "CarCommand.Bluetooth.Connect", s["Bt.Connect"])
    }

    @Test
    fun `values are checked, types and bridge are not`() {
        val r = LogicCheck.run(components, car, coreCar, emptyMap())
        assertEquals(setOf("store.setBluetooth", "store.useEmulator", "store.shellAction"), r.uses.map { it.key }.toSet())
        assertEquals(setOf("OpenDevices", "CloseDevices"), r.dispatches.map { it.serial }.toSet())
        // nothing mirrored yet: gaps are reported, the build goes on; strict stops it
        assertTrue(r.failures(strict = false).isEmpty())
        assertTrue(r.failures(strict = true).any { "Drawer.tsx has no view" in it })
    }

    @Test
    fun `a mirrored view needs its logic and its dispatches`() {
        val android = mapOf(
            "app/Runtime.kt" to """
                @Emulates("store.setBluetooth") fun switchBluetooth(on: Boolean) { radios.bluetooth(on) }
                @Emulates("store.shellAction") fun shell(a: ShellAction) = TODO()
                @Emulates("store.useEmulator") val ui = MutableStateFlow(0)
                @Emulates("store.useNow") fun now(): Long =
                    System.currentTimeMillis()
            """.trimIndent(),
            "core-ui/DrawerView.kt" to """
                @Mirrors("Drawer.tsx") class DrawerView { fun tap() { send(ShellAction.OpenDevices) } }
            """.trimIndent(),
        )
        val r = LogicCheck.run(components, car, coreCar, android)
        val f = r.failures(strict = false)
        assertTrue(f.any { "store.shellAction" in it }, "a TODO() body is no implementation: $f")
        assertTrue(f.none { "store.setBluetooth" in it })
        assertTrue(r.implementations.single { it.key == "store.useNow" }.empty.not(), "an expression body on the next line is a body")
        assertTrue(f.any { "CloseDevices" in it }, "the view never sends CloseDevices: $f")
        assertTrue(f.none { "OpenDevices;" in it })
    }

    @Test
    fun `a misspelt contract stops the build`() {
        val android = mapOf("a.kt" to """@Emulates("store.setBluetoth") fun x() { y() }
            @Mirrors("Drawr.tsx") class V""")
        val f = LogicCheck.run(components, car, coreCar, android).failures(strict = false)
        assertTrue(f.any { "setBluetoth" in it } && f.any { "Drawr.tsx" in it })
    }

    /** The real emulator against the real display app. */
    @Test
    fun `the emulator's screen and the display app are compared`() {
        val dir = File("..")
        val r = LogicCheck.run(
            File(dir, "emulator/components").listFiles()!!.filter { it.extension in setOf("tsx", "ts") }.associate { it.name to it.readText() },
            File(dir, "emulator/car").listFiles()!!.filter { it.extension == "ts" }.associate { it.nameWithoutExtension to it.readText() },
            File(dir, "core-car/src/commonMain/kotlin").walk().filter { it.extension == "kt" }.map { it.readText() }.toList(),
            listOf("app/src/main/kotlin", "core-ui/src/main/kotlin").flatMap { File(dir, it).walk().filter { f -> f.extension == "kt" }.toList() }
                .associate { it.path to it.readText() },
        )
        assertTrue("Screen.tsx" in r.screen && "ControlPanel.tsx" !in r.screen && "Inspector.tsx" !in r.screen)
        assertTrue(r.uses.any { it.key == "store.setBluetooth" })
        assertTrue(r.dispatches.any { it.serial == "OpenDevices" && it.kotlin == "ShellAction.OpenDevices" })
        assertTrue(r.problems.isEmpty(), r.problems.joinToString("\n"))
    }
}
