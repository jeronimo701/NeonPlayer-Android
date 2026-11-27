package neon.androidplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JacRedContractTest {
    @Test fun nativeSidPirAndJackettBothKeepTheirActualCounts(){
        val native=parseTorrentResults("""[{"title":"Example 1080p","magnet":"magnet:?xt=urn:btih:abc","sid":23,"pir":7,"size":4294967296,"quality":1080,"relased":2024,"createTime":"2026-10-20T12:00:00Z","updateTime":"2026-11-27T08:00:00Z","voices":["Studio"],"seasons":[1,2]}]""").single()
        assertEquals(23,native.seeds);assertEquals(7,native.peers);assertEquals(4294967296L,native.size)
        assertEquals(1080,native.quality);assertEquals(listOf("Studio"),native.voices);assertEquals(listOf("1","2"),native.seasons)
        val jackett=parseTorrentResults("""{"Results":[{"Title":"Other","MagnetUri":"magnet:?xt=urn:btih:def","Seeders":0,"Peers":12,"Size":1024,"PublishDate":"2026-11-25T13:00:00+03:00"},{"Title":"Unknown","Link":"https://example.org/file.torrent","Seeders":null,"Peers":null}]}""")
        assertEquals(0,jackett[0].seeds);assertEquals(12,jackett[0].peers);assertEquals(-1,jackett[1].seeds)
        assertEquals(native,orderedTorrents(listOf(jackett[1],native,jackett[0]),"seeds").first())
        assertEquals(jackett[0],orderedTorrents(listOf(native,jackett[0]),"peers").first())
        assertEquals(jackett[0],orderedTorrents(listOf(native,jackett[0]),"date").first())
        assertEquals(jackett[1],orderedTorrents(jackett,"sizeAsc").last())
        assertEquals("kp178707",normalizeTorrentQuery("https://www.kinopoisk.ru/series/178707/?socialAlias=abc"))
    }
}
