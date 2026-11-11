package com.streamline.cinema.ui.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLoadPolicyTest {

    private fun retry(status: Int? = null, parser: ParserException? = null, count: Int = 1) =
        streamRetryDelayMs(status, parser, count)

    @Test
    fun transportFailuresBackOffExponentiallyAndCap() {
        assertEquals(500L, retry(count = 1))
        assertEquals(1_000L, retry(count = 2))
        assertEquals(2_000L, retry(count = 3))
        assertEquals(4_000L, retry(count = 4))
        assertEquals(8_000L, retry(count = 5))
        assertEquals(8_000L, retry(count = 40))
    }

    @Test
    fun definitive4xxIsFinalButThrottlingIsRetried() {
        assertEquals(C.TIME_UNSET, retry(status = 404))
        assertEquals(C.TIME_UNSET, retry(status = 403))
        assertEquals(500L, retry(status = 408, count = 1))
        assertEquals(500L, retry(status = 429, count = 1))
    }

    @Test
    fun unsupportedSyntaxFailsImmediately() {
        assertEquals(
            C.TIME_UNSET,
            retry(parser = ParserException.createForManifestWithUnsupportedFeature("x", null))
        )
        assertEquals(
            C.TIME_UNSET,
            retry(parser = ParserException.createForUnsupportedContainerFeature("x"))
        )
    }

    @Test
    fun malformedDataGetsOneRetryThenFails() {
        val manifest = ParserException.createForMalformedManifest("bad", null)
        assertEquals(500L, retry(parser = manifest, count = 1))
        assertEquals(C.TIME_UNSET, retry(parser = manifest, count = 2))

        val container = ParserException.createForMalformedContainer("bad", null)
        assertEquals(500L, retry(parser = container, count = 1))
        assertEquals(C.TIME_UNSET, retry(parser = container, count = 2))
    }
}
