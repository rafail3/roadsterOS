package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapPacksTest {
    // two square countries side by side, the second with a hole, as GeoJSON from the borders file
    private val borders = MapPacks.parseBorders(
        """{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"code":"RO","name":"Romania"},"geometry":{"type":"Polygon","coordinates":[[[20,44],[30,44],[30,48],[20,48],[20,44]]]}},
          {"type":"Feature","properties":{"code":"HU","name":"Hungary"},"geometry":{"type":"MultiPolygon","coordinates":[[[[16,45],[20,45],[20,48],[16,48],[16,45]],[[17,46],[18,46],[18,47],[17,47],[17,46]]]]}}
        ]}""",
    )

    @Test
    fun `the country under a position comes from the borders`() {
        assertEquals("RO", MapPacks.countryAt(borders, 25.6, 45.65)?.code)
        assertEquals("HU", MapPacks.countryAt(borders, 19.0, 47.5)?.code)
        assertNull(MapPacks.countryAt(borders, 10.0, 50.0))
    }

    @Test
    fun `a hole in a country is outside it`() {
        assertNull(MapPacks.countryAt(borders, 17.5, 46.5))
    }

    @Test
    fun `a country without its map is offered only on wi-fi`() {
        assertEquals(MapPacks.Offer.DOWNLOAD, MapPacks.offer("HU", setOf("RO"), wifi = true))
        assertEquals(MapPacks.Offer.NEEDS_WIFI, MapPacks.offer("HU", setOf("RO"), wifi = false))
    }

    @Test
    fun `nothing is offered for a country already on the unit or outside every border`() {
        assertEquals(MapPacks.Offer.NONE, MapPacks.offer("RO", setOf("RO"), wifi = true))
        assertEquals(MapPacks.Offer.NONE, MapPacks.offer(null, setOf("RO"), wifi = true))
    }

    @Test
    fun `a country turned down or already downloading is not offered again`() {
        assertEquals(MapPacks.Offer.NONE, MapPacks.offer("HU", setOf("RO"), wifi = true, declined = setOf("HU")))
        assertEquals(MapPacks.Offer.NONE, MapPacks.offer("HU", setOf("RO"), wifi = true, downloading = "HU"))
        assertEquals(MapPacks.Offer.DOWNLOAD, MapPacks.offer("HU", setOf("RO"), wifi = true, declined = setOf("BG"), downloading = "BG"))
    }

    @Test
    fun `every offered country has its size and name`() {
        assertEquals(363, MapPacks.PACK_MB["RO"])
        assertEquals("Hungary", borders.first { it.code == "HU" }.name)
    }
}
