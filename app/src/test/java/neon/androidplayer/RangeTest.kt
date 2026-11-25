package neon.androidplayer

import org.junit.Assert.*
import org.junit.Test

class RangeTest {
    @Test fun boundedAndSuffixRanges() {
        assertEquals(ByteRange(20,39),parseRange("bytes=20-39",100))
        assertEquals(ByteRange(90,99),parseRange("bytes=-10",100))
        assertEquals(ByteRange(80,99),parseRange("bytes=80-",100))
        assertEquals(ByteRange(0,99),parseRange(null,100))
    }
    @Test fun invalidRangesCannotReadOutsideFile() {
        listOf("bytes=100-", "bytes=80-20", "bytes=-0", "bytes=1-2,4-5", "garbage").forEach {
            assertTrue(it,runCatching {parseRange(it,100)}.isFailure)
        }
    }
}
