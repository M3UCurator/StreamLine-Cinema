package com.streamline.cinema.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
