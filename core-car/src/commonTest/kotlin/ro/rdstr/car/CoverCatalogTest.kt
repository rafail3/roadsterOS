package ro.rdstr.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverCatalogTest {
    @Test
    fun `queries carry the names encoded`() {
        assertEquals("https://api.deezer.com/search?q=Inim%C4%83%20de%20Ion&limit=5", CoverCatalog.deezerQuery("Inimă de", "Ion"))
        assertTrue(CoverCatalog.itunesQuery("A&B", "C").contains("term=A%26B%20C&entity=song"))
    }

    @Test
    fun `the deezer cover of the same track is picked, not the first result`() {
        val body = """{"data":[
            {"title":"Cupidoane (Remix)","artist":{"name":"Someone Else"},"album":{"cover_xl":"wrong"}},
            {"title":"Cupidoane","artist":{"name":"Tzanca Uraganu"},"album":{"cover_xl":"https://x/1000x1000.jpg"}}]}"""
        assertEquals("https://x/1000x1000.jpg", CoverCatalog.deezerCover(body, "Cupidoane", "Tzanca Uraganu"))
        assertNull(CoverCatalog.deezerCover(body, "Amber Coast", "Tzanca Uraganu"))
    }

    @Test
    fun `the itunes artwork is asked at 1000 px`() {
        val body = """{"results":[{"trackName":"Night Transit","artistName":"Vela Orsini","artworkUrl100":"https://a/100x100bb.jpg"}]}"""
        assertEquals("https://a/1000x1000bb.jpg", CoverCatalog.itunesCover(body, "Night Transit", "Vela Orsini"))
    }

    @Test
    fun `a broken answer finds nothing`() {
        assertNull(CoverCatalog.deezerCover("<html>", "a", "b"))
        assertNull(CoverCatalog.itunesCover("""{"results":"x"}""", "a", "b"))
    }
}
