package com.streamline.cinema.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Loopback HLS proxy for the built-in player.
 *
 * Media3 asks a normal HTTP URL, but the proxy fetches every playlist and
 * segment itself with the configured User-Agent and stream headers, follows
 * redirects and http→https upgrades, and rewrites playlist URIs so variants,
 * keys, init maps and segments keep flowing through the same session. Only
 * HLS is routed here; progressive files keep the direct OkHttp path.
 *
 * The socket binds to 127.0.0.1 on a random port and every route carries a
 * per-process random token, so other apps cannot use it as an open proxy.
 * If the server cannot start, callers fall back to the original URL.
 */
object NativeStreamProxy {
    private const val TAG = "StreamLine:proxy"
    private const val HOST = "127.0.0.1"
    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    // Proxy links are longer than the originals (token + encoded target), so
    // the rewritten document gets a separate, slightly higher ceiling.
    private const val MAX_REWRITTEN_PLAYLIST_BYTES = 6 * 1024 * 1024
    private const val MAX_STREAMS = 96
    // Kept-alive sockets sit idle in a worker until KEEP_ALIVE_SECONDS, so the
    // pool needs headroom for the player's pooled connections plus in-flight
    // loads; six was sized for one-request-per-socket.
    private const val MAX_WORKERS = 12
    private const val KEEP_ALIVE_SECONDS = 10
    private const val UPSTREAM_FAILURE_LIMIT = 3
    private const val PLAYLIST_PREFIX_BYTES = 10
    // Playlists are a few KB and live channels re-fetch them about once a
    // second, so a stall there must not inherit the segment read budget.
    private const val PLAYLIST_TIMEOUT_SECONDS = 10L
    private const val PLAYLIST_ATTEMPTS = 2
    // A player that stops reading (pause, seek, re-tune) fills the loopback
    // send window; the blocking write then holds that worker for the life of
    // the process. Closing the socket after half a minute without a byte
    // reaching the player unblocks the writer and frees the worker. The clock
    // only starts once the response has started: while the proxy is still
    // waiting on the upstream there is nothing the player could read, and a
    // slow CDN (20 s read, one connection retry) is not a stalled client.
    private const val CLIENT_WRITE_STALL_MS = 30_000L
    private const val CLIENT_WATCHDOG_SECONDS = 5L

    private class Upstream(
        val url: String,
        val userAgent: String,
        val headers: Map<String, String>
    ) {
        // Set once the provider accepts the browser profile, so later segments
        // skip the deny round-trip.
        @Volatile var browserMasked = false
    }

    /** A registered stream: the loopback URL to hand Media3 and its id. */
    data class ProxyHandle(val url: String, val streamId: String)

    fun interface UpstreamFailureListener {
        fun onUpstreamFailure(streamId: String)
    }

    /**
     * One accepted loopback connection. [lastWriteMs] moves whenever a byte
     * reaches the player; the watchdog closes a link that has been serving a
     * request, has already put bytes on the wire, and has written nothing for
     * [CLIENT_WRITE_STALL_MS].
     */
    private class ClientLink(val socket: Socket) {
        @Volatile var lastWriteMs: Long = System.currentTimeMillis()
        @Volatile var serving = false
        // Set by the first byte of the current response. Before that the proxy
        // may still be fetching the upstream head, which is not a client stall.
        @Volatile var wroteForRequest = false
    }

    /** Socket output that stamps [ClientLink.lastWriteMs] on every write. */
    private class ProgressOutput(
        private val link: ClientLink,
        out: OutputStream
    ) : FilterOutputStream(out) {
        override fun write(b: Int) {
            out.write(b)
            link.wroteForRequest = true
            link.lastWriteMs = System.currentTimeMillis()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            link.wroteForRequest = true
            link.lastWriteMs = System.currentTimeMillis()
        }
    }

    // Access-ordered LRU so a long session with many sources stays bounded.
    private val streams = object : LinkedHashMap<String, Upstream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Upstream>?): Boolean =
            size > MAX_STREAMS
    }

    private val lock = Any()
    private val token = randomToken()
    private var server: ServerSocket? = null
    @Volatile private var port = 0

    // Written by pool threads, read by the watchdog only; the list itself is
    // tiny (at most the pool size plus keep-alive headroom).
    private val clientLinks = CopyOnWriteArrayList<ClientLink>()

    private val workers = Executors.newFixedThreadPool(MAX_WORKERS, ThreadFactory { runnable ->
        Thread(runnable, "stream-proxy-io").apply { isDaemon = true }
    })

    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "stream-proxy-watchdog").apply { isDaemon = true }
    }

    init {
        watchdog.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            clientLinks.forEach { link ->
                if (link.serving && link.wroteForRequest &&
                    now - link.lastWriteMs > CLIENT_WRITE_STALL_MS
                ) {
                    Log.w(TAG, "Client stopped reading · closing ${link.socket.remoteSocketAddress}")
                    runCatching { link.socket.close() }
                }
            }
        }, CLIENT_WATCHDOG_SECONDS, CLIENT_WATCHDOG_SECONDS, TimeUnit.SECONDS)
    }

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    // Every proxied StreamLine response carries an explicit type. Native engines
    // (Media3, libVLC and libmpv) pick their demuxer from Content-Type, so a
    // provider that omits the header or answers application/octet-stream must
    // not leave the app guessing from the payload.
    private val streamTypes = mapOf(
        "m3u8" to "application/vnd.apple.mpegurl",
        "m3u" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "m4s" to "video/iso.segment",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "fmp4" to "video/mp4",
        "cmfv" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "aac" to "audio/aac",
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "ac3" to "audio/ac3",
        "ec3" to "audio/eac3",
        "vtt" to "text/vtt",
        "webvtt" to "text/vtt",
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "key" to "application/octet-stream"
    )

    // Types an upstream uses when it does not know the payload; the extension
    // map is more trustworthy than these, and octet-stream is the honest answer
    // when nothing identifies the bytes.
    private val genericTypes = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/unknown",
        "text/plain"
    )

    // Consecutive transport failures per stream. A dead upstream otherwise
    // sits inside Media3's retry budget for over a minute, so listeners are
    // told once the source is conclusively unreachable.
    private val upstreamFailures = HashMap<String, Int>()
    private val upstreamListeners = CopyOnWriteArrayList<UpstreamFailureListener>()

    fun addUpstreamFailureListener(listener: UpstreamFailureListener) {
        upstreamListeners.addIfAbsent(listener)
    }

    fun removeUpstreamFailureListener(listener: UpstreamFailureListener) {
        upstreamListeners.remove(listener)
    }

    @VisibleForTesting
    internal fun noteUpstreamSuccess(id: String) {
        synchronized(lock) { upstreamFailures.remove(id) }
    }

    /**
     * True once this stream only answered under the browser profile, meaning a
     * request with the configured agent is refused. The player uses this to
     * skip its direct-URL fallback, which would present the same denied agent.
     */
    fun requiresBrowserMask(id: String): Boolean =
        synchronized(lock) { streams[id]?.browserMasked == true }

    @VisibleForTesting
    internal fun noteUpstreamFailure(id: String) {
        val tripped = synchronized(lock) {
            val count = (upstreamFailures[id] ?: 0) + 1
            if (count < UPSTREAM_FAILURE_LIMIT) {
                upstreamFailures[id] = count
                if (upstreamFailures.size > MAX_STREAMS) {
                    upstreamFailures.keys.retainAll(streams.keys)
                }
                false
            } else {
                // Consume the counter so a fresh attempt starts with a full
                // budget instead of tripping on its first failure.
                upstreamFailures.remove(id)
                true
            }
        }
        if (!tripped) return
        Log.w(TAG, "Upstream unreachable after $UPSTREAM_FAILURE_LIMIT tries · $id")
        upstreamListeners.forEach { listener ->
            runCatching { listener.onUpstreamFailure(id) }
        }
    }

    /**
     * The player's streaming client bounds reads at 45 s, which is right for a
     * progressive film but too generous here: a stalled CDN segment would pin
     * one of the six proxy workers for most of a minute, and six stalls wedge
     * every later request. HLS segments are small, so the proxy derives a client
     * with a 20 s socket read timeout.
     */
    private val upstreamClient = Http.streamingClient.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Playlist-shaped requests get their own client: one slow manifest refresh
     * otherwise pins a worker for 20 s, turns into a 502 that Media3 backs off
     * on for up to 8 s, and lands squarely in the playlist-stuck detector. A
     * playlist is a few KB, so 10 s is already conclusive.
     */
    private val playlistClient = Http.streamingClient.newBuilder()
        .readTimeout(PLAYLIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Registers [url] and returns the loopback URL Media3 should open, or
     * `null` when proxying is impossible and the caller must use [url] as-is.
     * The returned URL keeps an `.m3u8` path so source detection is unchanged.
     */
    fun proxyHls(
        url: String,
        userAgent: String,
        headers: Map<String, String> = emptyMap()
    ): ProxyHandle? = register(url, userAgent, headers, "index.m3u8")

    /**
     * Registers a single (non-playlist) stream and returns its loopback URL.
     * External players that ignore the hand-off's `headers` extra — VLC reads
     * nothing past the URI — cannot reproduce an addon's pipe headers, so a
     * gated origin only plays when this process relays the request for them.
     * The tail keeps the real extension so the receiving player types the file
     * correctly. The relay needs this process alive, which the paused player
     * activity now guarantees for the whole hand-off.
     */
    fun proxyStream(
        url: String,
        userAgent: String,
        headers: Map<String, String> = emptyMap()
    ): ProxyHandle? {
        val path = url.trim().substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.US)
        val tail = if (streamTypes.containsKey(extension)) "stream.$extension" else "stream"
        return register(url, userAgent, headers, tail)
    }

    private fun register(
        url: String,
        userAgent: String,
        headers: Map<String, String>,
        tail: String
    ): ProxyHandle? {
        val clean = url.trim().substringBefore('#')
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return null
        if (clean.contains("$HOST:")) return null
        val port = ensureStarted() ?: return null
        val id = streamId(clean, userAgent, headers)
        synchronized(lock) {
            streams[id] = Upstream(
                url = clean,
                // Providers bind tokens and session limits to their app's UA.
                // Losing the configured UA here made every proxied request fall
                // back to a browser UA and come back 403/404.
                userAgent = userAgent.ifBlank { Http.BROWSER_USER_AGENT },
                headers = headers.toMap()
            )
            upstreamFailures.remove(id)
        }
        return ProxyHandle("http://$HOST:$port/$token/hls/$id/$tail", id)
    }

    private fun ensureStarted(): Int? = synchronized(lock) {
        if (port > 0) return port
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(HOST), 0), 16)
            server = socket
            port = socket.localPort
            Thread({ acceptLoop(socket) }, "stream-proxy-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "HLS proxy listening on http://$HOST:$port")
            port
        }.onFailure { Log.w(TAG, "HLS proxy unavailable: ${it.message}") }.getOrNull()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            try {
                workers.execute {
                    // Media3 closes segment sockets mid-response on every seek or
                    // channel switch; an escaped SocketException on a pool thread
                    // reaches Android's default handler and kills the process.
                    runCatching { client.use { serve(it) } }
                        .onFailure { Log.w(TAG, "Request failed: ${it.javaClass.simpleName}") }
                }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private class LocalRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>
    )

    /**
     * A write to the player's own socket failed. Aborted segment loads are
     * routine — a seek, a re-tune or a live-edge relocation closes the
     * connection mid-body — and they say nothing about the provider.
     */
    private class LocalConnectionFailure(cause: IOException) : IOException(cause)

    private fun writeLocal(output: OutputStream, body: ByteArray, count: Int = body.size) {
        try {
            output.write(body, 0, count)
        } catch (error: IOException) {
            throw LocalConnectionFailure(error)
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = KEEP_ALIVE_SECONDS * 1000
        val link = ClientLink(socket)
        clientLinks.add(link)
        try {
            val input = BufferedInputStream(socket.getInputStream())
            // One reader per socket: a fresh BufferedReader would read ahead into
            // the request that follows on a kept-alive connection and swallow it.
            val reader = input.bufferedReader(Charsets.ISO_8859_1)
            val output = BufferedOutputStream(ProgressOutput(link, socket.getOutputStream()))
            var first = true
            while (true) {
                val request = runCatching { readRequest(reader) }.getOrNull()
                if (request == null) {
                    // An idle kept-alive socket closes here once its timeout
                    // expires; only a first request that never arrives is a fault.
                    if (first) {
                        // Native engines drop a connection without answering when
                        // their request is malformed; naming that here beats a
                        // silent retry loop.
                        Log.w(TAG, "Unreadable request from ${socket.remoteSocketAddress}")
                    }
                    return
                }
                first = false
                link.lastWriteMs = System.currentTimeMillis()
                link.wroteForRequest = false
                link.serving = true
                try {
                    if (!handle(output, request)) return
                } finally {
                    link.serving = false
                }
            }
        } finally {
            clientLinks.remove(link)
        }
    }

    /** Serves [request]; returns true when the socket may carry another one. */
    private fun handle(output: OutputStream, request: LocalRequest): Boolean {
        if (!isLoopbackHost(request.headers["host"], port)) {
            // A page that rebinds its own domain to 127.0.0.1 still sends that
            // domain in Host; only loopback names for our own port get served.
            Log.w(TAG, "Rejected Host ${request.headers["host"] ?: "(none)"}")
            return writeText(output, 403, "Forbidden host")
        }
        if (request.method == "OPTIONS") {
            return writeResponse(output, 204, emptyMap(), ByteArray(0), keepAlive = true)
        }
        if (request.method != "GET" && request.method != "HEAD") {
            return writeText(output, 405, "Only GET and HEAD are proxied")
        }
        val path = request.target.substringBefore('?')
        val prefix = "/$token/"
        if (!path.startsWith(prefix)) {
            return writeText(output, 404, "Not found")
        }
        val rest = path.removePrefix(prefix)
        val id: String
        var target: String?
        when {
            rest.startsWith("hls/") -> {
                id = rest.removePrefix("hls/").substringBefore('/')
                target = streamUrl(id)
            }
            rest.startsWith("s/") -> {
                id = rest.removePrefix("s/").substringBefore('/')
                val encoded = queryValue(request.target, "u")
                if (encoded != null) {
                    val decoded = decodeBase64Url(encoded)
                        ?: return writeText(output, 400, "Bad target")
                    target = String(decoded)
                } else {
                    target = null
                }
            }
            else -> return writeText(output, 404, "Not found")
        }
        val upstream = target ?: return writeText(output, 404, "Unknown stream")
        return forward(output, request, id, upstream)
    }

    /**
     * DNS rebinding: a web page resolves its own domain to 127.0.0.1 and then
     * fetches this port. The socket is loopback, but the Host header still
     * names the attacker's domain, so only loopback names for the bound port
     * are served. The per-process token in the path stays the primary guard.
     */
    @VisibleForTesting
    internal fun isLoopbackHost(hostHeader: String?, boundPort: Int): Boolean {
        val value = hostHeader?.trim()?.lowercase(Locale.US) ?: return false
        if (value.isEmpty() || value.contains('/') || value.contains('@')) return false
        if (value.startsWith("[")) return false
        val colon = value.indexOf(':')
        val host = if (colon >= 0) value.substring(0, colon) else value
        if (host != HOST && host != "localhost") return false
        if (colon < 0) return boundPort == 80
        return value.substring(colon + 1).toIntOrNull() == boundPort
    }

    private fun streamUrl(id: String): String? = synchronized(lock) { streams[id]?.url }

    /**
     * Playlist-shaped means the path names the HLS manifest. Those requests
     * use the tighter read timeout and retry as a whole, body included, because
     * their body is buffered before the player sees a byte. Extensionless
     * manifests keep the segment path — they are still typed from the payload,
     * but a reset there cannot be retried safely.
     */
    @VisibleForTesting
    internal fun isPlaylistTarget(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun forward(output: OutputStream, request: LocalRequest, id: String, target: String): Boolean {
        val entry = synchronized(lock) { streams[id] }
            ?: return writeText(output, 404, "Unknown stream")
        // Source headers win; otherwise keep the UA the player was registered with,
        // not a browser UA — providers bind tokens and session limits to theirs.
        val explicit = LinkedHashMap<String, String>()
        explicit["User-Agent"] = entry.headers.entries
            .firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: entry.userAgent
        entry.headers.forEach { (key, value) ->
            if (!key.equals("User-Agent", true)) explicit[key] = value
        }
        // Browser defaults fill the gaps (Accept, Accept-Language). ByteDance-
        // backed CDNs deny anything that does not look like a page request.
        val targetUrl = target.toHttpUrlOrNull() ?: return writeText(output, 400, "Bad target URL")

        var headWritten = false
        return try {
            fetch(output, request, id, targetUrl, explicit, entry) { headWritten = true }
        } catch (error: LocalConnectionFailure) {
            // The player aborted the load — a seek, a re-tune or a live-edge
            // relocation closes the connection mid-body all the time. Counting
            // that as an upstream failure tripped the three-strike listener and
            // pushed a healthy stream onto the direct URL the CDN already
            // refused.
            Log.d(TAG, "Player closed the connection · ${error.cause?.javaClass?.simpleName}")
            false
        } catch (error: IOException) {
            noteUpstreamFailure(id)
            val host = targetUrl.host
            Log.w(TAG, "Upstream $host${targetUrl.encodedPath} failed: ${error.javaClass.simpleName}: ${error.message}")
            // Only when nothing reached the player. Once the head is on the
            // wire the body is being parsed; a second HTTP head spliced into
            // it is worse than a clean close.
            if (!headWritten) runCatching { writeText(output, 502, "Upstream unavailable") }
            false
        } finally {
            runCatching { output.flush() }
        }
    }

    /**
     * Retries a playlist that fails before a single byte reached the player.
     * Live playlists reload roughly once a second and weigh a few KB, so a
     * one-off CDN reset becomes invisible on the second attempt instead of a
     * 502 that Media3 backs off on and the stuck detector counts. Segments and
     * keys retry at the connection stage instead (see [forwardOnce]), because
     * once their head is on the wire a reset has to surface.
     */
    private fun fetch(
        output: OutputStream,
        request: LocalRequest,
        id: String,
        targetUrl: HttpUrl,
        explicit: Map<String, String>,
        entry: Upstream,
        onHeadWritten: () -> Unit
    ): Boolean {
        val retryable = isPlaylistTarget(targetUrl.toString())
        var attempts = 0
        var responded = false
        while (true) {
            attempts += 1
            try {
                return forwardOnce(output, request, id, targetUrl, explicit, entry) {
                    responded = true
                    onHeadWritten()
                }
            } catch (error: LocalConnectionFailure) {
                throw error
            } catch (error: IOException) {
                if (!retryable || responded || attempts >= PLAYLIST_ATTEMPTS) throw error
                Log.i(
                    TAG,
                    "Playlist ${error.javaClass.simpleName} before response · " +
                        "retrying once · ${targetUrl.encodedPath}"
                )
            }
        }
    }

    private fun forwardOnce(
        output: OutputStream,
        request: LocalRequest,
        id: String,
        targetUrl: HttpUrl,
        explicit: Map<String, String>,
        entry: Upstream,
        beforePlayerWrite: () -> Unit
    ): Boolean {
        val playlistShaped = isPlaylistTarget(targetUrl.toString())
        val client = if (playlistShaped) playlistClient else upstreamClient
        val persistent = !request.headers["connection"].orEmpty().contains("close", true)

        fun upstream(mask: Boolean): Response {
            val source =
                if (mask) explicit + ("User-Agent" to Http.BROWSER_USER_AGENT) else explicit
            val headers = LinkedHashMap(BrowserMask.withDefaults(source))
            request.headers["range"]?.let { headers["Range"] = it }
            request.headers["if-range"]?.let { headers["If-Range"] = it }
            val builder = Request.Builder().url(targetUrl)
            headers.forEach { (key, value) -> runCatching { builder.header(key, value) } }
            if (request.method == "HEAD") builder.head() else builder.get()
            fun call(): Response = client.newCall(builder.build()).execute()
            if (playlistShaped) return call()
            // Idempotent GET: a reset before the response head reached the
            // player is invisible when the immediate retry succeeds. Playlists
            // handle their own retry in [fetch]; a segment body already on the
            // wire cannot be replayed.
            return try {
                call()
            } catch (error: IOException) {
                Log.i(
                    TAG,
                    "Upstream ${error.javaClass.simpleName} · retrying once · " +
                        "${targetUrl.encodedPath}"
                )
                call()
            }
        }

        var masked = entry.browserMasked
        var response = upstream(masked)
        if (!masked && response.code in 401..403) {
            // A CDN that gates on the browser profile denies every player
            // UA. Retry once as the stock browser and stick to it for this
            // stream, instead of failing every segment the same way.
            val path = response.request.url.encodedPath
            val code = response.code
            response.close()
            Log.i(TAG, "Retrying $code as a browser · $path")
            masked = true
            response = upstream(true)
        }
        if (masked && response.code in 200..299) entry.browserMasked = true
        response.use { response ->
            // Any answer proves the upstream is reachable, even a deny.
            noteUpstreamSuccess(id)
            if (response.code !in 200..299) {
                // Token/session limits surface here long before Media3 sees
                // the status, so name the provider's own deny reason.
                val deny = response.header("X-Deny-Reason").orEmpty()
                val path = response.request.url.encodedPath
                Log.w(TAG, "Upstream ${response.code} ${deny.ifBlank { "-" }} $path")
            }
            val body = response.body
            if (body == null) {
                beforePlayerWrite()
                return writeResponse(
                    output,
                    response.code,
                    responseHeaders(response),
                    ByteArray(0),
                    keepAlive = persistent
                )
            }
            val contentType = response.header("Content-Type").orEmpty()
            val targetPath = response.request.url.encodedPath.lowercase(Locale.US)
            var bodyStream = body.byteStream()
            val declaredPlaylist = contentType.contains("mpegurl", true) ||
                (targetPath.endsWith(".m3u8") && !contentType.contains("html", true))
            // Token endpoints often serve playlists from a path without an
            // extension and label them octet-stream. Missing that leaves the
            // URI lines unrewritten, so the player resolves segments against
            // the loopback proxy and every chunk 404s.
            val playlist = declaredPlaylist || if (isGenericContentType(contentType)) {
                val prefix = peek(bodyStream, PLAYLIST_PREFIX_BYTES)
                bodyStream = SequenceInputStream(ByteArrayInputStream(prefix), bodyStream)
                isHlsPlaylistPrefix(prefix)
            } else {
                false
            }

            // A denied playlist must surface the provider's own status. The
            // rewrite below always answers 200, and an empty deny body then
            // reads to Media3 as a malformed manifest instead of a 403.
            if (playlist && response.code in 200..299) {
                val out = rewritePlaylistStreaming(
                    bodyStream,
                    response.request.url,
                    MAX_PLAYLIST_BYTES,
                    MAX_REWRITTEN_PLAYLIST_BYTES
                ) { absolute -> proxyUrl(id, absolute) }
                // A playlist is rewritten as a whole document, so it always
                // answers 200. libVLC probes with "Range: bytes=0-" and
                // rejects a 206 that carries no Content-Range.
                beforePlayerWrite()
                return writeResponse(
                    output,
                    200,
                    mapOf(
                        "Content-Type" to "application/vnd.apple.mpegurl",
                        "Cache-Control" to "no-cache, no-store"
                    ),
                    out,
                    keepAlive = persistent
                )
            }

            val outHeaders = responseHeaders(response)
            // Fixed-length bodies can keep the socket: the player knows where
            // the document ends. Without a length the only framing is the
            // close, so those stay one-shot.
            val length = response.header("Content-Length")?.toLongOrNull()
            var keepAlive = persistent &&
                (request.method == "HEAD" || (length != null && length >= 0))
            beforePlayerWrite()
            val head = buildString {
                append("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n")
                outHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            writeLocal(output, head)
            if (request.method != "HEAD") {
                var written = 0L
                bodyStream.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        writeLocal(output, buffer, read)
                        written += read
                    }
                }
                // A short body already went out against the declared length;
                // reusing the socket would splice the next request into the
                // fragment, so hand it back to the player to discard.
                if (length != null && written != length) keepAlive = false
            }
            try {
                output.flush()
            } catch (error: IOException) {
                throw LocalConnectionFailure(error)
            }
            return keepAlive
        }
    }

    private fun responseHeaders(response: okhttp3.Response): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        listOf(
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
            "Cache-Control",
            "Expires"
        ).forEach { name ->
            response.header(name)?.let { headers[name] = it }
        }
        headers["Content-Type"] = contentTypeFor(
            response.request.url.toString(),
            response.header("Content-Type")
        )
        if (headers["Accept-Ranges"] == null) headers["Accept-Ranges"] = "bytes"
        return headers
    }

    /**
     * Canonical response type for a proxied stream. A known media extension is
     * authoritative because providers routinely mislabel segments; otherwise a
     * specific upstream type is kept, and a missing or generic one is declared
     * as `application/octet-stream` rather than forwarded verbatim.
     */
    @VisibleForTesting
    internal fun contentTypeFor(url: String, upstream: String?): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        if (path.endsWith(".mpd.xml")) return "application/dash+xml"
        val fileName = path.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', "")
        streamTypes[extension]?.let { return it }
        val offered = upstream.orEmpty()
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
        return when {
            offered.contains("mpegurl") -> "application/vnd.apple.mpegurl"
            offered.isBlank() || offered in genericTypes -> "application/octet-stream"
            else -> offered
        }
    }

    private fun isGenericContentType(contentType: String): Boolean {
        val offered = contentType.substringBefore(';').trim().lowercase(Locale.US)
        return offered.isBlank() || offered in genericTypes
    }

    private fun peek(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val chunk = input.read(buffer, read, count - read)
            if (chunk < 0) break
            read += chunk
        }
        return if (read == count) buffer else buffer.copyOf(read)
    }

    /** The HLS tag is the only reliable type signal for extensionless playlists. */
    @VisibleForTesting
    internal fun isHlsPlaylistPrefix(prefix: ByteArray): Boolean {
        val start = if (prefix.size >= 3 &&
            prefix[0] == 0xEF.toByte() &&
            prefix[1] == 0xBB.toByte() &&
            prefix[2] == 0xBF.toByte()
        ) {
            3
        } else {
            0
        }
        if (prefix.size < start + 7) return false
        return String(prefix, start, 7, Charsets.US_ASCII) == "#EXTM3U"
    }

    internal fun rewritePlaylist(
        text: String,
        base: HttpUrl,
        link: (String) -> String
    ): String = String(
        rewritePlaylistStreaming(
            text.byteInputStream(Charsets.UTF_8),
            base,
            MAX_PLAYLIST_BYTES,
            MAX_REWRITTEN_PLAYLIST_BYTES,
            link
        ),
        Charsets.UTF_8
    )

    /**
     * Rewrites a playlist as it is read and keeps only the rewritten document
     * in memory. The old shape held four full copies (bytes, text, builder and
     * output) at once, per proxy worker; a multi-megabyte master playlist from
     * four concurrent tunes was enough to stall a low-RAM box.
     */
    internal fun rewritePlaylistStreaming(
        input: InputStream,
        base: HttpUrl,
        maxInputBytes: Int,
        maxOutputBytes: Int,
        link: (String) -> String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var consumed = 0
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val raw = reader.readLine() ?: break
                consumed += raw.length + 1
                if (consumed > maxInputBytes) {
                    throw IOException("Playlist exceeds $maxInputBytes bytes")
                }
                val line = raw.trimEnd('\r')
                val trimmed = line.trim()
                val rewritten = when {
                    trimmed.isEmpty() -> "\n"
                    trimmed.startsWith('#') -> uriAttribute.replace(trimmed) { match ->
                        val resolved = base.resolve(match.groupValues[1])
                            ?: return@replace match.value
                        "URI=\"${link(resolved.toString())}\""
                    } + "\n"
                    else -> {
                        val resolved = base.resolve(trimmed)?.toString() ?: trimmed
                        link(resolved) + "\n"
                    }
                }
                out.write(rewritten.toByteArray(Charsets.UTF_8))
                if (out.size() > maxOutputBytes) {
                    throw IOException("Rewritten playlist exceeds $maxOutputBytes bytes")
                }
            }
        }
        return out.toByteArray()
    }

    private fun proxyUrl(id: String, absolute: String): String =
        "http://$HOST:$port/$token/s/$id?u=${encodeBase64Url(absolute.toByteArray(Charsets.UTF_8))}"

    private fun readRequest(reader: BufferedReader): LocalRequest? {
        val line = reader.readLine() ?: return null
        if (line.length > 16 * 1024) return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0) {
                headers[header.substring(0, colon).trim().lowercase(Locale.US)] =
                    header.substring(colon + 1).trim()
            }
        }
        var target = parts[1]
        if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            val url = target.toHttpUrlOrNull() ?: return null
            target = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        }
        return LocalRequest(parts[0].uppercase(Locale.US), target, headers)
    }

    private fun writeText(output: OutputStream, code: Int, message: String): Boolean {
        val body = message.toByteArray(Charsets.UTF_8)
        return writeResponse(
            output,
            code,
            mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString()
            ),
            body
        )
    }

    /** Returns true when the connection is framed for another request. */
    private fun writeResponse(
        output: OutputStream,
        code: Int,
        headers: Map<String, String>,
        body: ByteArray,
        keepAlive: Boolean = false
    ): Boolean {
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            headers.forEach { (key, value) ->
                if (!key.equals("Connection", true) && !key.equals("Content-Length", true)) {
                    append("$key: $value\r\n")
                }
            }
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        // This writes to the player, never upstream, so a broken pipe here is
        // an aborted load and must not be attributed to the provider.
        try {
            output.write(head)
            if (body.isNotEmpty()) output.write(body)
            output.flush()
        } catch (error: IOException) {
            throw LocalConnectionFailure(error)
        }
        return keepAlive
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        502 -> "Bad Gateway"
        else -> "OK"
    }

    private fun queryValue(target: String, name: String): String? {
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val split = pair.indexOf('=')
            if (split > 0 && pair.substring(0, split) == name) {
                return pair.substring(split + 1)
            }
        }
        return null
    }

    internal fun streamId(url: String, userAgent: String, headers: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(url.toByteArray(Charsets.UTF_8))
        digest.update(userAgent.toByteArray(Charsets.UTF_8))
        headers.toSortedMap().forEach { (key, value) ->
            digest.update(key.lowercase(Locale.US).toByteArray(Charsets.UTF_8))
            digest.update(value.toByteArray(Charsets.UTF_8))
        }
        val bytes = digest.digest()
        val hex = StringBuilder(16)
        for (index in 0 until 8) {
            hex.append(HEX[(bytes[index].toInt() ushr 4) and 0x0F])
            hex.append(HEX[bytes[index].toInt() and 0x0F])
        }
        return hex.toString()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(12)
        java.security.SecureRandom().nextBytes(bytes)
        val hex = StringBuilder(24)
        bytes.forEach { byte ->
            hex.append(HEX[(byte.toInt() ushr 4) and 0x0F])
            hex.append(HEX[byte.toInt() and 0x0F])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"
    private const val BASE64_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    internal fun encodeBase64Url(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xFF
            val second = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
            val third = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
            out.append(BASE64_CHARS[first ushr 2])
            out.append(BASE64_CHARS[((first and 0x03) shl 4) or (if (second >= 0) second ushr 4 else 0)])
            if (second >= 0) {
                out.append(BASE64_CHARS[((second and 0x0F) shl 2) or (if (third >= 0) third ushr 6 else 0)])
            }
            if (third >= 0) {
                out.append(BASE64_CHARS[third and 0x3F])
            }
        }
        return out.toString()
    }

    internal fun decodeBase64Url(value: String): ByteArray? {
        val out = ByteArrayOutputStream(value.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (char in value) {
            val digit = when (char) {
                in 'A'..'Z' -> char - 'A'
                in 'a'..'z' -> char - 'a' + 26
                in '0'..'9' -> char - '0' + 52
                '-' -> 62
                '_' -> 63
                '=' -> continue
                else -> return null
            }
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
