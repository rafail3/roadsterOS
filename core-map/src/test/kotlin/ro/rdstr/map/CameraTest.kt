package ro.rdstr.map

import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-6) = assertTrue(abs(expected - actual) < eps, "$expected vs $actual")

    @Test
    fun `the default curve is the cubic bezier 0_25 0_1 0_25 1`() {
        near(0.0, Easing.DEFAULT.at(0.0))
        near(1.0, Easing.DEFAULT.at(1.0))
        // values of cubic-bezier(0.25, 0.1, 0.25, 1), the CSS `ease` curve
        near(0.8024033877399112, Easing.DEFAULT.at(0.5), 1e-5)
        near(0.4085, Easing.DEFAULT.at(0.25), 1e-3)
        var last = 0.0
        for (i in 1..100) { val v = Easing.DEFAULT.at(i / 100.0); assertTrue(v >= last); last = v }
        near(0.3, Easing.LINEAR.at(0.3))
    }

    @Test
    fun `bearings wrap and turn the short way`() {
        near(-170.0, CameraEase.wrap(190.0))
        near(180.0, CameraEase.wrap(-180.0))
        near(10.0, CameraEase.wrap(370.0))
        // from 170 to -170 is 20 degrees clockwise, not 340 back
        near(190.0, CameraEase.normalizeBearing(-170.0, 170.0))
        near(-190.0, CameraEase.normalizeBearing(170.0, -170.0))
        near(90.0, CameraEase.normalizeBearing(90.0, 0.0))
    }

    @Test
    fun `the follow ease moves centre and bearing in a straight line over its time`() {
        val e = CameraEase()
        e.start(1000.0, 25.0, 45.0, 15.5, 170.0, 25.01, 45.0, 15.5, -170.0, 260.0, Easing.LINEAR)
        assertTrue(e.step(1130.0))
        near(25.005, e.lon, 1e-9)
        near(45.0, e.lat, 1e-9)
        near(15.5, e.zoom)
        near(180.0, e.bearing)
        assertFalse(e.step(1260.0))
        near(25.01, e.lon, 1e-9)
        near(-170.0, e.bearing)
        assertFalse(e.running)
    }

    @Test
    fun `a zoom ease keeps the centre and follows the default curve`() {
        val e = CameraEase()
        e.start(0.0, 25.0, 45.0, 15.0, 30.0, 25.0, 45.0, 16.0, 30.0, 250.0, Easing.DEFAULT)
        e.step(125.0)
        near(15.0 + Easing.DEFAULT.at(0.5), e.zoom)
        near(25.0, e.lon, 1e-9)
        near(45.0, e.lat, 1e-9)
        near(30.0, e.bearing)
    }

    @Test
    fun `the zoom is clamped to the map's range`() {
        val e = CameraEase()
        e.start(0.0, 25.0, 45.0, 17.5, 0.0, 25.0, 45.0, 18.5, 0.0, 250.0, Easing.DEFAULT, 4.0, 18.0)
        e.step(250.0)
        near(18.0, e.zoom)
    }

    @Test
    fun `a new ease starts where the running one got to, leaving the rest`() {
        val e = CameraEase()
        // the zoom buttons' ease, then a position's follow ease before it ends
        e.start(0.0, 25.0, 45.0, 15.0, 0.0, 25.0, 45.0, 16.0, 0.0, 250.0, Easing.DEFAULT)
        e.step(100.0)
        val z = e.zoom
        assertTrue(z > 15.0 && z < 16.0)
        e.start(100.0, e.lon, e.lat, e.zoom, e.bearing, 25.001, 45.0, e.zoom, 20.0, 260.0, Easing.LINEAR)
        e.step(360.0)
        near(z, e.zoom)
        near(25.001, e.lon, 1e-9)
        near(20.0, e.bearing)
    }

    @Test
    fun `a zoom around a point keeps that point on the same screen pixel`() {
        val e = CameraEase()
        val ax = 25.002
        val ay = 45.001
        val before = TileMath.worldX(ax, 15.0) - TileMath.worldX(25.0, 15.0) to TileMath.worldY(ay, 15.0) - TileMath.worldY(45.0, 15.0)
        e.startAround(0.0, 25.0, 45.0, 15.0, 0.0, 16.0, ax, ay, 300.0, Easing.DEFAULT)
        e.step(150.0)
        val after = TileMath.worldX(ax, e.zoom) - TileMath.worldX(e.lon, e.zoom) to TileMath.worldY(ay, e.zoom) - TileMath.worldY(e.lat, e.zoom)
        near(before.first, after.first, 1e-6)
        near(before.second, after.second, 1e-6)
    }

    @Test
    fun `a frame of an ease allocates nothing and costs well under a microsecond`() {
        val e = CameraEase()
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        fun run(frames: Int) {
            for (i in 0 until frames) {
                if (!e.running) e.start(0.0, 25.0, 45.0, 15.0, 0.0, 25.01, 45.01, 16.0, 90.0, 500.0, Easing.DEFAULT)
                e.step((i % 31) * 16.7)
            }
        }
        run(200_000) // warm up
        val bytes = threads.getThreadAllocatedBytes(id)
        val started = System.nanoTime()
        run(1_000_000)
        val ns = (System.nanoTime() - started) / 1_000_000.0
        val allocated = threads.getThreadAllocatedBytes(id) - bytes
        assertTrue(allocated < 1024, "allocated $allocated bytes")
        assertTrue(ns < 1000, "$ns ns per frame")
    }
}
