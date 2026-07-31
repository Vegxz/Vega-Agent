package com.vepro.code

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Outbound URL policy.
 *
 * There are two very different kinds of URL in this app and they must not share
 * one rule:
 *
 *  * **The user's own endpoint** — the base URL typed into Settings. The user
 *    chose it deliberately; it is routinely a LAN box running Ollama / LM Studio
 *    / llama.cpp, a local proxy, or a self-hosted gateway. Treating it as an
 *    SSRF risk is nonsense: there is no attacker, the user *is* the operator.
 *    Handled by [requireUserEndpoint].
 *  * **A URL the model picked** — anything reached by `web_fetch`,
 *    `web_search`, `download_file` or the human-mode browser. The model can be
 *    steered by page content it just read, so this genuinely is an SSRF
 *    surface and stays guarded. Handled by [requireSafeHttps].
 *
 * ### Why the pre-connect DNS resolution is gone
 *
 * The previous version resolved every hostname up front and rejected the
 * request if *any* returned address was private. That produced the
 * "local-network access is off" failure on perfectly good keys and endpoints,
 * because:
 *
 *  * ISPs that filter DNS answer with a private sentinel address (10.10.34.34
 *    and friends are the well-known Iranian ones), so *every* provider host
 *    resolved "private" and was blocked before a single byte went out;
 *  * a VPN or private DNS can resolve a public host to a private tunnel
 *    address, which is correct and expected;
 *  * the check resolved the name a second time inside `openConnection()`
 *    anyway, so it never actually constrained the socket — the two lookups can
 *    disagree, which is the classic DNS-rebinding TOCTOU. It cost real
 *    latency and a `NetworkOnMainThreadException` risk for a guarantee it
 *    could not make.
 *
 * What is kept is the part that is both cheap and sound: literal IP addresses
 * and internal hostnames are matched without touching the resolver, and the
 * cloud metadata endpoints stay blocked unconditionally. Users who genuinely
 * want the agent's tools to reach their own network can turn that on in
 * Settings ([Prefs.allowLocalNetwork]).
 */
object NetworkPolicy {

    /**
     * Set once at startup from [Prefs]. When true, model-chosen URLs may also
     * reach private/loopback addresses (and may use plain HTTP to do so).
     */
    @Volatile
    var allowLocalNetwork: Boolean = false

    /** Cloud instance-metadata services — never reachable, in any mode. */
    private val METADATA_HOSTS = arrayOf(
        "metadata.google.internal",
        "metadata.goog",
        "169.254.169.254",
        "[fd00:ec2::254]",
        "fd00:ec2::254",
        "instance-data"
    )

    fun applyPrefs(prefs: Prefs) {
        allowLocalNetwork = prefs.allowLocalNetwork()
    }

    // ---- the user's own endpoint ------------------------------------------

    /**
     * Validates the base URL the user configured. HTTPS is required for public
     * hosts so an API key is never sent in the clear, but plain HTTP is allowed
     * for loopback and private addresses, where there is no network to sniff
     * and where local model servers overwhelmingly speak HTTP.
     */
    @Throws(Exception::class)
    fun requireUserEndpoint(value: String?) {
        val uri = parse(value)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: ""
        val host = uri.host?.lowercase(Locale.US) ?: ""
        if (isMetadataHost(host)) {
            throw SecurityException(Fa.NET_BLOCK_METADATA)
        }
        if (scheme == "https") {
            return
        }
        if (scheme != "http") {
            throw SecurityException(Fa.NET_BLOCK_SCHEME)
        }
        // Plain HTTP: only to somewhere that cannot leave the device's network.
        if (isLocalHostname(host) || isPrivateLiteral(host)) {
            return
        }
        throw SecurityException(Fa.NET_BLOCK_PLAINTEXT)
    }

    // ---- URLs the model chose ---------------------------------------------

    /**
     * Guards a URL the model asked to reach. HTTPS only (plus HTTP to local
     * addresses when the user has opted in), no credentials in the authority,
     * and no internal or metadata hosts.
     */
    @Throws(Exception::class)
    fun requireSafeHttps(value: String?) {
        val uri = parse(value)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: ""
        val host = uri.host?.lowercase(Locale.US) ?: ""

        if (isMetadataHost(host)) {
            throw SecurityException(Fa.NET_BLOCK_METADATA)
        }
        val local = isLocalHostname(host) || isPrivateLiteral(host)
        if (local && !allowLocalNetwork) {
            throw SecurityException(Fa.NET_BLOCK_LOCAL)
        }
        if (scheme == "https") {
            return
        }
        if (scheme != "http") {
            throw SecurityException(Fa.NET_BLOCK_SCHEME)
        }
        // Plain HTTP is only ever acceptable to a local address the user opened up.
        if (local && allowLocalNetwork) {
            return
        }
        throw SecurityException(Fa.NET_BLOCK_SCHEME)
    }

    // ---- shared parsing ----------------------------------------------------

    @Throws(Exception::class)
    private fun parse(value: String?): URI {
        if (value.isNullOrBlankJava()) {
            throw SecurityException(Fa.NET_BLOCK_EMPTY)
        }
        val uri = try {
            URI(value.trimJava())
        } catch (bad: Exception) {
            throw SecurityException(Fa.NET_BLOCK_MALFORMED)
        }
        if (uri.scheme == null || uri.host == null || uri.host.isEmpty()) {
            throw SecurityException(Fa.NET_BLOCK_MALFORMED)
        }
        // `user:pass@host` is how a redirect smuggles credentials to a third
        // party, and no legitimate endpoint here needs it.
        if (uri.userInfo != null) {
            throw SecurityException(Fa.NET_BLOCK_MALFORMED)
        }
        if (uri.port == 0 || uri.port < -1 || uri.port > 65535) {
            throw SecurityException(Fa.NET_BLOCK_MALFORMED)
        }
        return uri
    }

    private fun isMetadataHost(host: String): Boolean {
        for (entry in METADATA_HOSTS) {
            if (host == entry) {
                return true
            }
        }
        return false
    }

    /** Names that always mean "this device" or "this LAN", with no lookup. */
    private fun isLocalHostname(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") ||
            host == "local" || host.endsWith(".local") ||
            host == "internal" || host.endsWith(".internal") ||
            host == "home" || host.endsWith(".home") ||
            host.endsWith(".lan") || host.endsWith(".intranet")

    /**
     * True when [host] is a *literal* IP address in a private, loopback,
     * link-local, CGNAT or benchmarking range. Hostnames always return false —
     * this deliberately never resolves anything.
     */
    fun isPrivateLiteral(host: String): Boolean {
        val bare = host.removeSurrounding("[", "]")
        if (bare.isEmpty()) {
            return false
        }
        val v4 = parseIpv4(bare)
        if (v4 != null) {
            val a = v4[0]
            val b = v4[1]
            return a == 0 || a == 10 || a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) ||
                (a == 198 && (b == 18 || b == 19)) ||
                (a == 192 && b == 0) ||
                a >= 224
        }
        if (!looksLikeIpv6(bare)) {
            return false
        }
        return try {
            val address = InetAddress.getByName(bare)
            isPrivateAddress(address)
        } catch (e: Exception) {
            // Unparseable as a literal — it is not an address, so not private.
            false
        }
    }

    /** Strict dotted-quad parse; returns null for anything that is not one. */
    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split(".")
        if (parts.size != 4) {
            return null
        }
        val out = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) {
                return null
            }
            var value = 0
            for (c in part) {
                if (c < '0' || c > '9') {
                    return null
                }
                value = value * 10 + (c - '0')
            }
            if (value > 255) {
                return null
            }
            out[i] = value
        }
        return out
    }

    /** Cheap shape test so a hostname never reaches the resolver. */
    private fun looksLikeIpv6(host: String): Boolean {
        if (!host.contains(":")) {
            return false
        }
        for (c in host) {
            val hex = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            if (!hex && c != ':' && c != '.' && c != '%') {
                return false
            }
        }
        return true
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (address is Inet6Address && bytes.size == 16) {
            val first = bytes[0].toInt() and 255
            val second = bytes[1].toInt() and 255
            // fc00::/7 unique-local, fe80::/10 link-local
            return (first and 0xfe) == 0xfc || (first == 0xfe && (second and 0xc0) == 0x80)
        }
        return false
    }
}
