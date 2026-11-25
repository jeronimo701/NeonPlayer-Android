package neon.androidplayer

import org.junit.Test
import org.junit.Assert.*

class PlaybackControlsTest {
    @Test fun changingWheelDirectionOnlyReversesTimeDelta(){
        assertEquals(80.0,seekGestureDelta(200.0,120.0,false),0.0)
        assertEquals(-80.0,seekGestureDelta(200.0,120.0,true),0.0)
        assertEquals(80.0,seekGestureDelta(120.0,200.0,true),0.0)
        assertEquals(0.0,seekGestureDelta(120.0,120.0,true),0.0)
    }
    @Test fun edgeBlendHasNoDiscontinuityAtEitherEndAndNeverReverses(){
        for(smooth in listOf(0f,.5f,1f)){
            assertEquals(1f,edgeBlendWeight(0f,smooth),0f);assertEquals(0f,edgeBlendWeight(1f,smooth),0f)
            val weights=(0..100).map{edgeBlendWeight(it/100f,smooth)}
            assertTrue(weights.zipWithNext().all{(a,b)->a>=b});assertTrue(weights.all{it in 0f..1f})
        }
    }
    @Test fun qualityPresetsBoundWorkAndIncreaseItOnlyWhenSelected(){
        val fast=ambientQuality("fast");val medium=ambientQuality("medium");val heavy=ambientQuality("heavy")
        assertTrue(fast.points<medium.points&&medium.points<heavy.points)
        assertTrue(fast.passes<medium.passes&&medium.passes<heavy.passes)
        assertTrue(fast.intervalMs>medium.intervalMs&&medium.intervalMs>heavy.intervalMs)
        assertTrue(ambientQualities.all{it.points in 16..128&&it.passes in 1..3&&it.intervalMs>=150})
        assertEquals(medium,ambientQuality(null))
    }
}
