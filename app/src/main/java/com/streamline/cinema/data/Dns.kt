package com.streamline.cinema.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

data class DnsTestOutcome(val success: Boolean, val message: String)

/**
 * App-wide DNS router. Settings pick a resolver (system, DoH preset or custom
 * UDP/DoH servers); OkHttp talks to the router so switching DNS never requires
 * rebuilding clients.
 */
object CinemaDns {

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CACHE_TTL_MS = 120_000L

    @Volatile
    private var active: Dns = Dns.SYSTEM

    val router: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = active.lookup(hostname)
    }

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val ipv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    private val wireType = "application/dns-message".toMediaType()

    fun configure(settings: AppSettings) {
        active = build(settings)
    }

    fun build(settings: AppSettings): Dns {
        if (settings.dnsMode == DnsMode.SYSTEM) return Dns.SYSTEM
        val resolvers = settings.activeDnsServers.mapNotNull { parseServer(it) }
        if (resolvers.isEmpty()) return Dns.SYSTEM
        return FallbackDns(MultiDns(resolvers, lookupPool), Dns.SYSTEM)
    }

    fun parseServer(raw: String): Dns? {
        val entry = raw.trim()
        if (entry.isEmpty()) return null
        if (entry.startsWith("https://", ignoreCase = true) ||
            entry.startsWith("http://", ignoreCase = true)
        ) {
            val url = entry.toHttpUrlOrNull() ?: return null
            if (url.host.isBlank()) return null
            return DohDns(url.toString())
        }

        var host = entry
        var port = 53
        if (entry.startsWith("[")) {
            val end = entry.indexOf(']')
            if (end <= 1) return null
            host = entry.substring(1, end)
            val rest = entry.substring(end + 1)
            if (rest.startsWith(":")) {
                port = rest.substring(1).toIntOrNull() ?: return null
            } else if (rest.isNotEmpty()) {
                return null
            }
        } else if (entry.count { it == ':' } == 1) {
            val idx = entry.lastIndexOf(':')
            val parsedPort = entry.substring(idx + 1).toIntOrNull()
            if (parsedPort != null) {
                host = entry.substring(0, idx)
                port = parsedPort
            }
        }
        if (port !in 1..65535) return null
        if (!isIpLiteral(host)) return null
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        return UdpDns(address, port)
    }

    fun describe(settings: AppSettings): String = when (settings.dnsMode) {
        DnsMode.SYSTEM -> "System DNS (ISP-assigned)"
        DnsMode.CUSTOM -> {
            val entries = settings.customDnsServers
            if (entries.isEmpty()) "Custom DNS · no servers added yet"
            else "Custom DNS · ${entries.size} server${if (entries.size == 1) "" else "s"}"
        }
        else -> "Encrypted DoH · ${settings.dnsMode.servers.firstOrNull().orEmpty()}"
    }

    fun test(settings: AppSettings, hostname: String = "www.google.com"): DnsTestOutcome {
        val started = System.currentTimeMillis()
        return try {
            val addresses = build(settings).lookup(hostname)
            val elapsed = System.currentTimeMillis() - started
            if (addresses.isEmpty()) {
                DnsTestOutcome(false, "No address returned for $hostname")
            } else {
                val first = addresses.first().hostAddress ?: hostname
                DnsTestOutcome(true, "$hostname → $first · ${elapsed}ms")
            }
        } catch (t: Throwable) {
            DnsTestOutcome(
                false,
                when (t) {
                    is UnknownHostException -> "Unresolved · ${t.message ?: hostname}"
                    is SocketTimeoutException -> "DNS timeout"
                    else -> t.message?.take(80) ?: "DNS lookup failed"
                }
            )
        }
    }

    private fun isIpLiteral(value: String): Boolean =
        value.contains(':') || ipv4.matches(value)

    private data class CacheEntry(val expiresAt: Long, val addresses: List<InetAddress>)

    private fun cached(key: String, host: String): List<InetAddress>? {
        val cacheKey = "$key|$host"
        val entry = cache[cacheKey] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            cache.remove(cacheKey)
            return null
        }
        return entry.addresses
    }

    private fun cache(key: String, host: String, addresses: List<InetAddress>) {
        if (cache.size > 256) cache.clear()
        cache["$key|$host"] = CacheEntry(System.currentTimeMillis() + CACHE_TTL_MS, addresses)
    }

    // Configured resolvers are queried in parallel. Tried in sequence, two
    // servers with their own timeouts could stall a tune for half a minute
    // before the system resolver was even asked; racing them costs one small
    // thread each and bounds the wait by the slowest single resolver.
    private val lookupPool = Executors.newCachedThreadPool(ThreadFactory { runnable ->
        Thread(runnable, "dns-lookup").apply { isDaemon = true }
    })

    private class MultiDns(
        private val resolvers: List<Dns>,
        private val pool: java.util.concurrent.ExecutorService
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (resolvers.size == 1) return resolvers[0].lookup(hostname)
            val pending = resolvers.map { resolver ->
                pool.submit(Callable { resolver.lookup(hostname) })
            }.toMutableList()
            var last: Exception? = null
            while (pending.isNotEmpty()) {
                val ready = pending.firstOrNull { it.isDone }
                if (ready == null) {
                    Thread.sleep(5)
                    continue
                }
                pending.remove(ready)
                val addresses = try {
                    ready.get()
                } catch (e: ExecutionException) {
                    last = (e.cause as? Exception) ?: e
                    continue
                }
                if (addresses.isNotEmpty()) return addresses
            }
            throw last ?: UnknownHostException(hostname)
        }
    }

    private class FallbackDns(private val primary: Dns, private val fallback: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            primary.lookup(hostname)
        } catch (e: Exception) {
            runCatching { fallback.lookup(hostname) }.getOrElse { throw e }
        }
    }

    private class DohDns(private val endpoint: String) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.isEmpty()) throw UnknownHostException(hostname)
            if (hostname.contains(':') || ipv4.matches(hostname)) {
                return Dns.SYSTEM.lookup(hostname)
            }
            cached("doh:$endpoint", hostname)?.let { return it }
            val addresses = mutableListOf<InetAddress>()
            var last: Exception? = null
            for (type in intArrayOf(TYPE_A, TYPE_AAAA)) {
                try {
                    addresses += query(hostname, type)
                } catch (e: Exception) {
                    last = e
                }
            }
            if (addresses.isEmpty()) {
                throw last ?: UnknownHostException(hostname)
            }
            val distinct = addresses.distinct()
            cache("doh:$endpoint", hostname, distinct)
            return distinct
        }

        private fun query(hostname: String, type: Int): List<InetAddress> {
            val body = DnsWire.buildQuery(hostname, type).toRequestBody(wireType)
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/dns-message")
                .post(body)
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("DoH HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException("DoH empty response")
                return DnsWire.parseAddresses(bytes, hostname)
            }
        }
    }

    private class UdpDns(private val server: InetAddress, private val port: Int) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.isEmpty()) throw UnknownHostException(hostname)
            if (hostname.contains(':') || ipv4.matches(hostname)) {
                return Dns.SYSTEM.lookup(hostname)
            }
            cached("udp:${server.hostAddress}:$port", hostname)?.let { return it }
            val addresses = mutableListOf<InetAddress>()
            var last: Exception? = null
            for (type in intArrayOf(TYPE_A, TYPE_AAAA)) {
                try {
                    addresses += exchange(hostname, type)
                } catch (e: Exception) {
                    last = e
                }
            }
            if (addresses.isEmpty()) {
                throw last ?: UnknownHostException(hostname)
            }
            val distinct = addresses.distinct()
            cache("udp:${server.hostAddress}:$port", hostname, distinct)
            return distinct
        }

        private fun exchange(hostname: String, type: Int): List<InetAddress> {
            val query = DnsWire.buildQuery(hostname, type)
            // One attempt per record type: retrying doubled the worst case for
            // a hostname whose server is simply unreachable, and the system
            // resolver fallback is a better use of that time.
            DatagramSocket().use { socket ->
                socket.soTimeout = 3_000
                socket.send(DatagramPacket(query, query.size, server, port))
                val buffer = ByteArray(2_048)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                return DnsWire.parseAddresses(packet.data.copyOf(packet.length), hostname)
            }
        }
    }
}

private object DnsWire {

    fun buildQuery(hostname: String, type: Int): ByteArray {
        val labels = hostname.trimEnd('.').split('.').filter { it.isNotEmpty() }
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val out = ByteArray(size)
        val id = (System.nanoTime() and 0xFFFF).toInt()
        out[0] = (id ushr 8).toByte()
        out[1] = id.toByte()
        out[2] = 0x01 // recursion desired
        out[5] = 0x01 // one question
        var offset = 12
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out[offset++] = bytes.size.toByte()
            bytes.copyInto(out, offset)
            offset += bytes.size
        }
        out[offset++] = 0
        out[offset++] = ((type ushr 8) and 0xFF).toByte()
        out[offset++] = (type and 0xFF).toByte()
        out[offset++] = 0
        out[offset++] = 1
        return out
    }

    fun parseAddresses(data: ByteArray, hostname: String): List<InetAddress> {
        if (data.size < 12) throw IOException("Short DNS response")
        val rcode = data[3].toInt() and 0x0F
        if (rcode != 0) throw UnknownHostException("$hostname · DNS rcode $rcode")
        val questions = u16(data, 4)
        val answers = u16(data, 6)
        var offset = 12
        repeat(questions) {
            offset = skipName(data, offset)
            offset += 4
            if (offset > data.size) throw IOException("Malformed DNS question")
        }
        val result = mutableListOf<InetAddress>()
        var remaining = answers
        while (remaining-- > 0 && offset < data.size) {
            offset = skipName(data, offset)
            if (offset + 10 > data.size) break
            val type = u16(data, offset)
            val length = u16(data, offset + 8)
            val dataStart = offset + 10
            if (dataStart + length > data.size) break
            if (type == 1 && length == 4) {
                result += InetAddress.getByAddress(data.copyOfRange(dataStart, dataStart + 4))
            } else if (type == 28 && length == 16) {
                result += InetAddress.getByAddress(data.copyOfRange(dataStart, dataStart + 16))
            }
            offset = dataStart + length
        }
        return result
    }

    private fun skipName(data: ByteArray, start: Int): Int {
        var offset = start
        var guard = 0
        while (offset < data.size && guard++ < 128) {
            val length = data[offset].toInt() and 0xFF
            if (length == 0) return offset + 1
            if (length and 0xC0 == 0xC0) return offset + 2
            offset += 1 + length
        }
        throw IOException("Malformed DNS name")
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
