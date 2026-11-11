package com.streamline.cinema.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    private const val IDLE_CONNECTIONS = 8
    private const val KEEP_ALIVE_MINUTES = 5L
    private const val KEEP_ALIVE_PING_SECONDS = 30L

    // One pool shared by every client: HLS/DASH playlists and segments reuse the
    // same TLS connection instead of re-handshaking on each request, and HTTP/2
    // pings keep an idle CDN edge from silently dropping the socket.
    private val connectionPool = ConnectionPool(
        IDLE_CONNECTIONS,
        KEEP_ALIVE_MINUTES,
        TimeUnit.MINUTES
    )

    // OkHttp's default dispatcher allows 64 in-flight calls per host. A player
    // that also syncs addons, guides and artwork can queue far more than the
    // radio can carry, so the whole app shares a small ceiling instead. Eight
    // per host still covers HLS video + audio + subtitles + playlist fetches.
    private val dispatcher = Dispatcher().apply {
        maxRequests = 24
        maxRequestsPerHost = 8
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(CinemaDns.router)
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Media reads must never be bounded by a call timeout: a single response
     * body is read for the whole film, and live HLS playlists keep the connection
     * open between segments. Reusing [client] aborted playback at its 35 s call
     * timeout (surfacing as ERROR_CODE_IO_UNSPECIFIED / HttpDataSourceException),
     * so the player gets its own client with only a connect bound. The read
     * timeout is per socket read, not per film: a CDN that stops sending bytes
     * for 45 s is dead, and leaving it unbounded froze the player forever with
     * no SocketTimeoutException for [StreamLoadErrorPolicy] to retry.
     */
    val streamingClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectionPool(connectionPool)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(KEEP_ALIVE_PING_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Adaptive reads (HLS playlists/segments, DASH fragments) are smaller than a
     * progressive film, so a 30 s stall is conclusive. Retries multiply the wait
     * (StreamLoadErrorPolicy backs off 500 ms → 8 s), which is why this stays
     * well above a slow segment but short of the two-minute dead window a 20 s
     * read timeout used to create.
     */
    val adaptiveClient: OkHttpClient by lazy {
        streamingClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Document downloads (IPTV playlists, XMLTV guides). The body is consumed
     * once, so the 35 s call timeout on [client] would abort any transfer slower
     * than a few hundred KB/s; reads still time out when the peer stalls.
     */
    val documentClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectionPool(connectionPool)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { runCatching { call.cancel() } }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

/** Upper bound for API and scrape documents; playlists use their own caps. */
const val DEFAULT_TEXT_BODY_LIMIT = 8 * 1024 * 1024

/**
 * Reads a text body but refuses to allocate more than [maxBytes]. A probe that
 * turns out to be a web page (or a video file) would otherwise be copied into
 * the heap in full just to be inspected for a few markers.
 */
fun Response.bodyStringCapped(maxBytes: Int = DEFAULT_TEXT_BODY_LIMIT): String {
    val responseBody = body ?: return ""
    val declared = responseBody.contentLength()
    if (declared > maxBytes) throw IOException("Response exceeds $maxBytes bytes")
    val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    responseBody.byteStream().use { input ->
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (out.size() + read > maxBytes) {
                throw IOException("Response exceeds $maxBytes bytes")
            }
            out.write(chunk, 0, read)
        }
    }
    return String(out.toByteArray(), charset)
}

/** One 3xx response in a redirect chain. */
data class RedirectHop(val url: String, val code: Int, val target: String)

/**
 * The final, non-redirect response after an explicit chain walk. [finalUrl] is
 * the absolute URL that actually served the content, which is what shortened
 * links must be replaced with: Media3 resolves HLS/DASH relative paths against
 * the URL it was given, not against the redirect target.
 */
class ResolvedResponse internal constructor(
    val requestedUrl: String,
    val response: Response,
    val hops: List<RedirectHop>
) : Closeable {
    val finalUrl: String get() = response.request.url.toString()
    val code: Int get() = response.code
    val contentType: String? get() = response.header("Content-Type")
    val redirected: Boolean get() = hops.isNotEmpty()

    override fun close() = response.close()
}

private const val MAX_REDIRECT_HOPS = 10

/** Only the statuses that define a usable `Location`; 304 is not a redirect. */
private fun isFollowableRedirect(code: Int): Boolean =
    code == 301 || code == 302 || code == 303 || code == 307 || code == 308

/**
 * Follows redirects one hop at a time instead of letting OkHttp hide the chain,
 * so callers learn the final absolute URL. Handles 301/302/303/307/308,
 * relative `Location` values and http↔https switches (shortened links commonly
 * upgrade from plain http). Credentials are stripped when a hop changes host or
 * downgrades HTTPS to HTTP.
 *
 * The returned body belongs to the caller and must be closed. Throws
 * [IOException] on an invalid target, a loop or more than [maxHops] hops.
 */
suspend fun OkHttpClient.awaitRedirects(
    request: Request,
    maxHops: Int = MAX_REDIRECT_HOPS
): ResolvedResponse {
    val walker = newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    var current = request
    val hops = ArrayList<RedirectHop>(4)
    val visited = HashSet<String>(8)
    visited += current.url.toString()
    while (true) {
        val response = walker.await(current)
        val location = response.header("Location")
        if (!isFollowableRedirect(response.code) || location.isNullOrBlank()) {
            return ResolvedResponse(request.url.toString(), response, hops)
        }
        val target = current.url.resolve(location)
        if (target == null) {
            response.close()
            throw IOException("Redirect target is not a valid URL: $location")
        }
        if (hops.size >= maxHops) {
            response.close()
            throw IOException("Too many redirects (more than $maxHops)")
        }
        if (!visited.add(target.toString())) {
            response.close()
            throw IOException("Redirect loop at ${target.host}")
        }
        hops += RedirectHop(current.url.toString(), response.code, target.toString())
        val sameHost = target.host.equals(current.url.host, ignoreCase = true) &&
            target.port == current.url.port
        val downgraded = current.url.isHttps && !target.isHttps
        response.close()
        val next = current.newBuilder().url(target)
        if (!sameHost || downgraded) {
            next.removeHeader("Authorization").removeHeader("Cookie")
        }
        current = next.build()
    }
}
