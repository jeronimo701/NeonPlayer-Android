package neon.androidplayer

import org.junit.Assert.*
import org.junit.Test

class PlayerModelTest {
    @Test fun longMoviePrecisionDoesNotDependOnSinglePixelMinute(){
        assertEquals(18.0,seekSecondsPerPixel(5400.0,300.0,3.0,0),.0001)
        assertEquals(.5/3,seekSecondsPerPixel(5400.0,300.0,3.0,1),.0001)
        assertEquals(.1/3,seekSecondsPerPixel(10800.0,300.0,3.0,2),.0001)
    }
    @Test fun fitFillAndStretchHaveExplicitIndependentGeometry(){
        val fit=videoBox(1080.0,2400.0,16.0/9,1.0,0.0,-1.0,"fit")
        assertEquals(0.0,fit.top,.001);assertEquals(1080.0,fit.width,.001);assertEquals(607.5,fit.height,.001)
        val fill=videoBox(1080.0,2400.0,16.0/9,1.0,0.0,0.0,"fill")
        assertEquals(2400.0,fill.height,.001);assertTrue(fill.left<0)
        val stretch=videoBox(1080.0,2400.0,16.0/9,1.0,0.0,0.0,"stretch")
        assertEquals(1080.0,stretch.width,.001);assertEquals(2400.0,stretch.height,.001)
    }
    @Test fun catalogLinksNormalizeWithoutAcceptingLookalikeDomains(){
        assertEquals("kp178707",normalizeTorrentQuery("https://www.kinopoisk.ru/series/178707/?socialAlias=abc#comments"))
        assertEquals("tt1234567",normalizeTorrentQuery("https://www.imdb.com/title/tt1234567/?ref_=x"))
        assertEquals("kp123456",normalizeTorrentQuery("KP123456"))
        assertEquals("Сериал 178707",normalizeTorrentQuery("Сериал 178707"))
        assertTrue(runCatching{normalizeTorrentQuery("https://kinopoisk.ru.example.com/series/178707/")}.isFailure)
    }

    @Test fun chaptersNeverSkipThePostCreditScene(){
        val skips=chapterSkips(listOf(MediaChapter(0.0,"Opening"),MediaChapter(90.0,"Main"),MediaChapter(2400.0,"Credits"),MediaChapter(2460.0,"Post-credit scene")),2500.0)
        assertEquals(2,skips.size);assertEquals(90.0,skips[0].end,0.0);assertEquals(2460.0,skips[1].end,0.0)
        assertTrue(chapterSkips(listOf(MediaChapter(0.0,"Chapter 1")),2500.0).isEmpty())
        assertTrue(chapterSkips(listOf(MediaChapter(0.0,"Intro")),2500.0).isEmpty())
        assertEquals(90.5,parseMediaTime("1:30.5"),0.0)
        assertTrue(runCatching{parseMediaTime("1:99")}.isFailure)
    }
    @Test fun audioProcessingIsOptInAndLimiterComesLast(){
        assertEquals("",audioGraph(false,false,0,false))
        val graph=audioGraph(true,true,12,true)
        assertTrue(graph.indexOf("volume=12dB")<graph.indexOf("alimiter"))
        assertTrue(graph.contains("level=false"))
    }
}
