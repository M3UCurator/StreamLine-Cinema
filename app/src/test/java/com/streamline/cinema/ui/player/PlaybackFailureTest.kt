package com.streamline.cinema.ui.player

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureTest {

    private fun retryAfter(value: String): Long? =
        retryAfterMs(mapOf("Retry-After" to listOf(value)), nowMs = 0L)

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

    @Test
    fun everyDrmCodeIsReportedAsProtectedStream() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
        )
        codes.forEach { code ->
            val failure = classifyPlaybackFailure(code, null)
            assertEquals("code $code", FailureKind.DRM, failure.kind)
            assertEquals("code $code", "Protected stream failed", failure.title)
        }
    }

    @Test
    fun licenceAcquisitionRetriesTheSameEncryptedSource() {
        val error = PlaybackException(null, null, PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)
        assertTrue(error.isRetryableInPlace())
        assertTrue(error.isLicenceAcquisitionFailure())
        // The retry decides on the error alone; the re-prepared source keeps the
        // same URL, headers and DRM config, so it can never turn clear.
        assertFalse(
            PlaybackException(null, null, PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED)
                .isLicenceAcquisitionFailure()
        )
    }

    @Test
    fun permanentDrmFailuresDoNotRetry() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
        )
        codes.forEach { code ->
            assertFalse("code $code", PlaybackException(null, null, code).isRetryableInPlace())
        }
    }

    @Test
    fun transportFailuresStillRetryInPlace() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        )
        codes.forEach { code ->
            assertTrue("code $code", PlaybackException(null, null, code).isRetryableInPlace())
        }
        assertFalse(
            PlaybackException(null, null, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
                .isRetryableInPlace()
        )
    }

    @Test
    fun sessionAndRateLimitsAreProviderWaitsNotDeadLinks() {
        listOf(401, 403, 429).forEach { code ->
            assertTrue("code $code", isProviderLimitStatus(code))
        }
        listOf(404, 408, 410, 500, 503).forEach { code ->
            assertFalse("code $code", isProviderLimitStatus(code))
        }
        assertFalse(isProviderLimitStatus(null))
        // The panel still explains the limit rather than calling it a dead source.
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            IOException("throttled")
        )
        assertEquals(FailureKind.PROVIDER, failure.kind)
    }

    @Test
    fun behindLiveWindowIsReportedHonestlyOnceRetriesAreSpent() {
        // The re-prepare path normally handles this without a panel; if the
        // budget is exhausted the notice must not claim the stream was lost.
        val failure = classifyPlaybackFailure(
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            null
        )
        assertEquals(FailureKind.NETWORK, failure.kind)
        assertEquals("Fell behind live", failure.title)
    }

    @Test
    fun timeoutsRetryInPlaceButLiveEdgeSlipsDoNot() {
        // A stuck playlist surfaces as ERROR_CODE_TIMEOUT; re-preparing the
        // same source is what fetches a fresh one.
        assertTrue(
            PlaybackException(null, null, PlaybackException.ERROR_CODE_TIMEOUT)
                .isRetryableInPlace()
        )
        // A behind-window slip is re-prepared straight to the edge by its own
        // path, never put behind a reconnect delay.
        assertFalse(
            PlaybackException(null, null, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
                .isRetryableInPlace()
        )
    }

    @Test
    fun onlyChannelFaultsMarkALiveEntryDead() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        ).forEach { code ->
            assertTrue("code $code", PlaybackException(null, null, code).isTransientForChannel())
        }
        listOf(
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_DECODING_FAILED
        ).forEach { code ->
            assertFalse("code $code", PlaybackException(null, null, code).isTransientForChannel())
        }
    }

    @Test
    fun retryAfterSecondsIsHonouredAndClamped() {
        assertEquals(45_000L, retryAfter("45"))
        // A sub-five-second hint still leaves the provider time to release the session.
        assertEquals(5_000L, retryAfter("1"))
        // A multi-minute ban is not hidden behind an endless spinner.
        assertEquals(180_000L, retryAfter("9000"))
        assertNull(retryAfterMs(null, nowMs = 0L))
        assertNull(retryAfter("soon"))
    }

    @Test
    fun retryAfterHttpDateCountsDownToTheServerHint() {
        val now = 1_700_000_000_000L
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date(now + 65_000L))
        assertEquals(
            65_000L,
            retryAfterMs(mapOf("Retry-After" to listOf(date)), nowMs = now)
        )
    }
}
