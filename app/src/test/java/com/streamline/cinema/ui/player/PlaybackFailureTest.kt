package com.streamline.cinema.ui.player

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureTest {

    @Test
    fun unknownHostIsANetworkFailure() {
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            IOException(UnknownHostException("cdn.example"))
        )
        assertEquals(FailureKind.NETWORK, failure.kind)
        assertEquals("Cannot reach the provider", failure.title)
    }

    @Test
    fun refusedConnectionIsANetworkFailure() {
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ConnectException("Connection refused")
        )
        assertEquals(FailureKind.NETWORK, failure.kind)
    }

    @Test
    fun readTimeoutIsANetworkFailure() {
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            SocketTimeoutException("Read timed out")
        )
        assertEquals(FailureKind.NETWORK, failure.kind)
        assertEquals("Connection timed out", failure.title)
    }

    @Test
    fun loaderTimeoutIsANetworkFailure() {
        val failure = classifyPlaybackFailure(PlaybackException.ERROR_CODE_TIMEOUT, null)
        assertEquals(FailureKind.NETWORK, failure.kind)
        assertEquals("Connection timed out", failure.title)
    }

    @Test
    fun decoderErrorsAskForADecoderSwitch() {
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            null
        )
        assertEquals(FailureKind.FORMAT, failure.kind)
        assertEquals("This stream cannot be decoded", failure.title)
    }

    @Test
    fun malformedManifestIsASourceFailure() {
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            null
        )
        assertEquals(FailureKind.SOURCE, failure.kind)
        assertEquals("Stream data is malformed", failure.title)
    }

    @Test
    fun expiredLicenceIsADrmFailure() {
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            null
        )
        assertEquals(FailureKind.DRM, failure.kind)
        assertEquals("Protected stream failed", failure.title)
    }

    @Test
    fun cleartextBlockIsASourceFailure() {
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            null
        )
        assertEquals(FailureKind.SOURCE, failure.kind)
    }

    @Test
    fun unknownCodesFallBackToAGenericNotice() {
        val failure = classifyPlaybackFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, null)
        assertEquals(FailureKind.UNKNOWN, failure.kind)
        assertEquals("Playback failed", failure.title)
    }
}
