package neon.androidplayer
import org.junit.Test
import org.junit.Assert.*
class YoutubeAddressTest {
    @Test fun supportedYoutubeShapes(){
        for(url in listOf("https://www.youtube.com/watch?v=jNQXAC9IVRw&t=3", "https://youtu.be/jNQXAC9IVRw?si=test", "https://youtube.com/shorts/jNQXAC9IVRw", "https://m.youtube.com/live/jNQXAC9IVRw", "https://www.youtube-nocookie.com/embed/jNQXAC9IVRw"))assertEquals("jNQXAC9IVRw",youtubeVideoId(url))
    }
    @Test fun rejectsLookalikesAndPlaylists(){
        for(url in listOf("https://youtube.com.evil.test/watch?v=jNQXAC9IVRw", "https://youtube.com/playlist?list=test", "file://youtube.com/watch?v=jNQXAC9IVRw", "https://youtu.be/short"))assertNull(youtubeVideoId(url))
    }
    @Test fun idAndHostAreSeparate(){assertEquals("jNQXAC9IVRw",youtubeVideoId("jNQXAC9IVRw"));assertFalse(youtubeHost("jNQXAC9IVRw"));assertTrue(youtubeHost("https://youtu.be/jNQXAC9IVRw"))}
}
