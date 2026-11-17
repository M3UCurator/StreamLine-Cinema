package com.streamline.cinema.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException

internal enum class FailureKind { NETWORK, PROVIDER, SOURCE, FORMAT, DRM, UNKNOWN }

/**
 * A playback failure the panel can render directly: what went wrong, why, and
 * what the viewer can do about it. Media3 codes stay out of the UI.
 */
internal data class PlaybackFailure(
    val kind: FailureKind,
    val title: String,
    val detail: String,
    val action: String
)

internal fun classifyPlaybackError(error: PlaybackException): PlaybackFailure =
    classifyPlaybackFailure(error.errorCode, error.cause)

internal fun classifyPlaybackFailure(errorCode: Int, cause: Throwable?): PlaybackFailure {
    val chain = cause.chain()

    chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let { http ->
        val code = http.responseCode
        return when {
            code == 401 || code == 403 || code == 407 || code == 451 -> PlaybackFailure(
                FailureKind.PROVIDER,
                "Provider rejected this stream",
                "The provider denied access (HTTP $code). The link may have expired, " +
                    "or the account may be in use on another device.",
                "Reload in a moment, or choose another source."
            )
            code == 429 -> PlaybackFailure(
                FailureKind.PROVIDER,
                "Provider is limiting this stream",
                "The provider is refusing new sessions for now (HTTP 429).",
                "Wait about a minute, then reload."
            )
            code == 404 || code == 410 -> PlaybackFailure(
                FailureKind.SOURCE,
                "Channel or source is gone",
                "The provider no longer serves this stream (HTTP $code).",
                "Choose another source, or refresh the playlist."
            )
            code in 500..599 -> PlaybackFailure(
                FailureKind.PROVIDER,
                "Provider server error",
                "The provider failed while serving the stream (HTTP $code).",
                "Try again in a moment, or choose another source."
            )
            code in 400..499 -> PlaybackFailure(
                FailureKind.SOURCE,
                "Source unavailable",
                "The provider refused this request (HTTP $code).",
                "Choose another source, or refresh the playlist."
            )
            else -> PlaybackFailure(
                FailureKind.PROVIDER,
                "Provider rejected this stream",
                "The provider answered with HTTP $code instead of video.",
                "Reload in a moment, or choose another source."
            )
        }
    }

    when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            return networkFailure("Connection lost", "The stream stopped responding.")

        PlaybackException.ERROR_CODE_TIMEOUT ->
            return networkFailure("Connection timed out", "The provider stopped sending video.")

        // Only shown once the live-edge re-prepare budget is spent: the window
        // keeps moving, so this is a route problem, not a dead channel.
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> return PlaybackFailure(
            FailureKind.NETWORK,
            "Fell behind live",
            "The channel's live window moved past the buffered video.",
            "Reload to rejoin at the live edge, or choose another source."
        )

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> return PlaybackFailure(
            FailureKind.SOURCE,
            "Source not found",
            "The stream address no longer exists.",
            "Choose another source, or refresh the playlist."
        )

        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> return PlaybackFailure(
            FailureKind.SOURCE,
            "Insecure stream blocked",
            "This source only offers a plain HTTP address, which Android blocks.",
            "Choose an HTTPS source."
        )

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> return PlaybackFailure(
            FailureKind.PROVIDER,
            "Provider rejected this stream",
            "The provider answered with an error instead of video.",
            "Reload in a moment, or choose another source."
        )

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> return PlaybackFailure(
            FailureKind.SOURCE,
            "Stream data is malformed",
            "The provider sent a playlist or container the player cannot read.",
            "Choose another source, or refresh the playlist."
        )

        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> return PlaybackFailure(
            FailureKind.FORMAT,
            "This stream cannot be decoded",
            "This device has no working decoder for the video or audio format.",
            "Switch the decoder (HW+ or SW) or choose another source."
        )

        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> return PlaybackFailure(
            FailureKind.DRM,
            "Protected stream failed",
            "The licence was refused, expired, or this device lacks the required DRM module.",
            "Choose another source."
        )
    }

    chain.firstNotNullOfOrNull { it.networkFailureOrNull() }?.let { return it }

    if (chain.any { it is IOException }) {
        return networkFailure("Connection lost", "The stream stopped responding.")
    }

    return PlaybackFailure(
        FailureKind.UNKNOWN,
        "Playback failed",
        "The player could not open this stream.",
        "Reload it, or choose another source."
    )
}

private fun networkFailure(title: String, detail: String): PlaybackFailure = PlaybackFailure(
    FailureKind.NETWORK,
    title,
    detail,
    "Check the network connection, then reload."
)

/**
 * Failures the next attempt can plausibly cure without changing the source.
 * A bad status or a missing file is definitive and must keep failing fast; a
 * reset, timeout or truncated read is not. Licence acquisition joins the
 * transport failures because the licence server is another network hop: a
 * retry re-prepares the same media item through the same DRM session, so an
 * encrypted feed is never retried as a clear stream.
 */
internal fun PlaybackException.isRetryableInPlace(): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    // A playlist that stopped refreshing (Media3's HLS stuck detection) or a
    // load that timed out mid-stream is a transport fault: re-preparing
    // re-fetches the playlist and rejoins where the viewer was.
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> true
    else -> false
}

/**
 * True for failures that say something about the route to a live channel
 * rather than the channel itself. A reset, a timeout or a slip past the live
 * edge cures itself on the next tune, so it must not mark the entry dead and
 * teach the Live tab to skip a stream that works.
 */
internal fun PlaybackException.isTransientForChannel(): Boolean = when {
    isHttpAuthorizationFailure() -> true
    else -> when (errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
        else -> false
    }
}

/** True when the retry is spent on the licence server rather than the media. */
internal fun PlaybackException.isLicenceAcquisitionFailure(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED

/**
 * 401/403/429 from the provider. These are session or rate limits, not proof
 * the link is dead: the same request succeeds again once the provider releases
 * the old session, so the caller waits instead of switching sources.
 */
internal fun PlaybackException.isHttpAuthorizationFailure(): Boolean =
    isProviderLimitStatus(httpStatus())

internal fun isProviderLimitStatus(code: Int?): Boolean =
    code == 401 || code == 403 || code == 429

/**
 * 401/403/407/451 from the origin: the request itself was refused (UA, Referer
 * or geo gating) rather than the account throttled. The loopback proxy re-sends
 * the same URL with the browser profile, so these are worth one proxy retry.
 * 429 stays out: a rate limit is not fixed by changing headers.
 */
internal fun PlaybackException.isOriginDenial(): Boolean =
    httpStatus().let { it == 401 || it == 403 || it == 407 || it == 451 }

internal fun PlaybackException.httpStatus(): Int? =
    cause.httpResponse()?.responseCode

/**
 * The provider's own Retry-After hint, clamped to a window that keeps the
 * viewer informed instead of hiding a multi-minute ban behind a spinner.
 * Accepts delay-seconds and the HTTP-date form.
 */
internal fun PlaybackException.providerRetryAfterMs(
    nowMs: Long = System.currentTimeMillis()
): Long? = retryAfterMs(cause.httpResponse()?.headerFields, nowMs)

internal fun retryAfterMs(headers: Map<String, List<String>>?, nowMs: Long): Long? {
    val raw = headers
        ?.entries?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
        ?.value?.firstOrNull()?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val waitMs = raw.toLongOrNull()?.times(1_000L) ?: runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(raw)!!.time - nowMs
    }.getOrNull() ?: return null
    return waitMs.coerceIn(5_000L, 180_000L)
}

private fun Throwable?.httpResponse(): HttpDataSource.InvalidResponseCodeException? =
    chain().filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

private fun Throwable.networkFailureOrNull(): PlaybackFailure? = when (this) {
    is UnknownHostException -> PlaybackFailure(
        FailureKind.NETWORK,
        "Cannot reach the provider",
        "The provider's address could not be resolved. The network or DNS may be offline.",
        "Check the connection and DNS settings, then reload."
    )
    is SSLException -> PlaybackFailure(
        FailureKind.NETWORK,
        "Secure connection failed",
        "The encrypted connection to the provider could not be established.",
        "Check the network connection, then reload."
    )
    is SocketTimeoutException, is InterruptedIOException ->
        networkFailure("Connection timed out", "The provider stopped answering.")
    is ConnectException ->
        networkFailure("Cannot connect to the provider", "The stream server is unreachable.")
    is SocketException -> networkFailure("Connection lost", "The stream connection was closed.")
    else -> null
}

private fun Throwable?.chain(): List<Throwable> {
    if (this == null) return emptyList()
    val chain = ArrayList<Throwable>(4)
    var current: Throwable? = this
    while (current != null && chain.size < 8 && current !in chain) {
        chain += current
        current = current.cause
    }
    return chain
}
