package github.vega.agent

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
 *    / llama.cpp, a local proxy, a self-hosted gateway, or any plain-HTTP
 *    service they run themselves. Treating it as an SSRF risk is nonsense:
 *    there is no attacker, the user *is* the operator, and they explicitly
 *    asked for plain HTTP and local addresses (localhost, 127.0.0.1, 0.0.0.0,
 *    LAN IPs, any port) to be accepted. Handled by [requireUserEndpoint].
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
 *
 * ### How a "literal IP" is recognized — without ever touching DNS
 *
 * The check must run *before* any socket exists, so it cannot ask the
 * resolver what a name means — and it must never trigger a lookup itself
 * (latency, a `NetworkOnMainThreadException` risk, and filtering resolvers
 * that answer with private sentinels). The rule is therefore structural:
 *
 *  * A host containing `:` can never be a DNS name, so it is parsed with
 *    [InetAddress.getByName] directly — for colon-strings that call either
 *    parses an IPv6 literal or throws; it never falls back to DNS.
 *  * Anything else goes through a small hand-written IPv4 parser
 *    ([parseIpv4Literal]) that deliberately accepts a SUPERSET of Java's
 *    literal grammar: 1–4 parts, each decimal, `0x`-hex or `0`-octal. Being
 *    a superset is the safe direction — a form the parser accepts but the
 *    socket rejects just gets blocked (fail-closed); a form the socket
 *    accepts but the parser missed would be a bypass, and there is none
 *    left: `2130706433`, `0x7f.0.0.1`, `0177.0.0.1` and `127.0.0.1.` (one
 *    trailing dot is stripped, as browsers do) all normalize to 127.0.0.1,
 *    and `::ffff:127.0.0.1` / `::ffff:169.254.169.254` normalize through
 *    their IPv4-mapped forms. The private-range and metadata tests then run
 *    on the NORMALIZED bytes, so the exact-match dodge for
 *    `169.254.169.254` (`2852039166`, `0xa9.0xfe.0xa9.0xfe`, …) is closed too.
 *
 * ### Accepted limitation: DNS-rebinding TOCTOU
 *
 * A *hostname* is still checked as a name — `isLocalHostname` and the
 * metadata-name list match without resolving. DNS can rebind between this
 * check and `openConnection()` (the classic time-of-check/time-of-use),
 * and no pre-connect lookup can close that without reintroducing the
 * filtering-resolver breakage documented above. This is accepted: the
 * policy's job is to stop the model from *naming* an internal target, not
 * to outrun a hostile resolver.
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
     * Validates the base URL the user configured. Both HTTPS and plain HTTP
     * are accepted, for any host — public, LAN, loopback or otherwise — and
     * any port: the endpoint is the user's own choice (a local model server,
     * a self-hosted gateway, a tunnel, …), and the user is the operator, so
     * there is no sniffing threat model to protect them from here. The only
     * things still refused are cloud instance-metadata hosts, credentials
     * smuggled in the authority (`user:pass@host`), and malformed URLs.
     */
    @Throws(Exception::class)
    fun requireUserEndpoint(value: String?) {
        val uri = parse(normalizeUserEndpoint(value ?: ""))
        val scheme = uri.scheme?.lowercase(Locale.US) ?: ""
        val host = uri.host?.lowercase(Locale.US) ?: ""
        if (isMetadataHost(host) || isMetadataLiteral(host)) {
            throw SecurityException(Fa.NET_BLOCK_METADATA)
        }
        if (scheme == "https" || scheme == "http") {
            return
        }
        throw SecurityException(Fa.NET_BLOCK_SCHEME)
    }

    /**
     * Repairs the one URL typo users actually type: a stray colon right after
     * a numeric port (`http://0.0.0.0:2128:/v1`). That colon can never be part
     * of a valid authority, so dropping it is unambiguous — and without the
     * repair `java.net.URI` rejects the whole URL. Anything that does not match
     * the exact shape is returned untouched.
     */
    fun normalizeUserEndpoint(raw: String): String {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) {
            return raw
        }
        val afterScheme = raw.substring(schemeEnd + 3)
        var authEnd = afterScheme.length
        for (i in afterScheme.indices) {
            val c = afterScheme[i]
            if (c == '/' || c == '?' || c == '#') {
                authEnd = i
                break
            }
        }
        val authority = afterScheme.substring(0, authEnd)
        if (!authority.endsWith(":")) {
            return raw
        }
        val without = authority.substring(0, authority.length - 1)
        val colon = without.lastIndexOf(':')
        if (colon < 0) {
            return raw
        }
        val port = without.substring(colon + 1)
        if (port.isEmpty() || port.any { it !in '0'..'9' }) {
            return raw
        }
        return raw.substring(0, schemeEnd + 3) + without +
            raw.substring(schemeEnd + 3 + authEnd)
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

        if (isMetadataHost(host) || isMetadataLiteral(host)) {
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
        throw SecurityException(Fa.NET_BLOCK_HTTP)
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
     * this deliberately never resolves anything: see [numericAddress].
     *
     * The address is NORMALIZED first (decimal/hex/octal/trailing-dot IPv4,
     * IPv4-mapped IPv6), so every textual disguise of a private address is
     * caught, not just the dotted-quad spelling.
     */
    fun isPrivateLiteral(host: String): Boolean {
        val addr = numericAddress(host) ?: return false
        return isPrivateBytes(addr)
    }

    /**
     * True when [host] is a *literal* rendering of a cloud instance-metadata
     * address — currently `169.254.169.254` (in any textual form, including
     * `::ffff:`-mapped) or `fd00:ec2::254`. Unlike [isPrivateLiteral] this is
     * consulted even when the user opted into local-network access: metadata
     * endpoints are never reachable, in any mode.
     */
    private fun isMetadataLiteral(host: String): Boolean {
        val addr = numericAddress(host) ?: return false
        if (addr.size == 4) {
            return addr[0] == 169.toByte() && addr[1] == 254.toByte() &&
                addr[2] == 169.toByte() && addr[3] == 254.toByte()
        }
        if (addr.size == 16) {
            val v4 = if (isV4Mapped(addr)) {
                byteArrayOf(addr[12], addr[13], addr[14], addr[15])
            } else {
                addr
            }
            if (v4.size == 4) {
                return v4[0] == 169.toByte() && v4[1] == 254.toByte() &&
                    v4[2] == 169.toByte() && v4[3] == 254.toByte()
            }
            return v4.contentEquals(METADATA_V6_BYTES)
        }
        return false
    }

    /** `fd00:ec2::254` as raw bytes (the EC2 metadata IPv6 endpoint). */
    private val METADATA_V6_BYTES = byteArrayOf(
        0xfd.toByte(), 0x00, 0x0e.toByte(), 0xc2.toByte(),
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54
    )

    /**
     * Normalizes a literal IP host to its address bytes, or null when [host]
     * is not a numeric literal (i.e. it is a hostname — never resolved here).
     *
     * DNS-safety is structural, not assumed:
     *  * anything containing `:` can never be a DNS name, so IPv6 literals go
     *    through [InetAddress.getByName], which for such strings parses or
     *    throws and never falls back to the resolver;
     *  * everything else goes through [parseIpv4Literal], which never touches
     *    the resolver at all (verified: `InetAddress.getByName` DOES fall back
     *    to DNS for some numeric-shaped non-literals such as `1.2.3.256`).
     */
    private fun numericAddress(host: String): ByteArray? {
        var bare = host.removeSurrounding("[", "]")
        if (bare.isEmpty()) {
            return null
        }
        // Browsers treat "127.0.0.1." as "127.0.0.1"; strip one trailing dot
        // so the disguise does not slip past as "not a literal".
        if (bare.endsWith(".") && !bare.endsWith("..")) {
            bare = bare.substring(0, bare.length - 1)
            if (bare.isEmpty()) {
                return null
            }
        }
        if (bare.contains(':')) {
            return try {
                // 4 bytes when Java folds a ::ffff: mapped address to
                // Inet4Address, 16 otherwise.
                InetAddress.getByName(bare).address
            } catch (e: Exception) {
                null
            }
        }
        return parseIpv4Literal(bare)
    }

    /**
     * Parses an IPv4 literal in a SUPERSET of Java's literal grammar — 1 to 4
     * parts, each decimal, `0x`-hex or `0`-octal — returning the 4 address
     * bytes, or null when the string is not an IPv4 literal at all.
     *
     * The superset is deliberate and fail-closed: a spelling the parser takes
     * but the socket would reject (e.g. `0x7f.0.0.1` on JVMs whose dotted
     * parser is decimal-only) is simply blocked; a spelling the socket takes
     * but the parser missed would be a bypass, so the grammar errs wide.
     * Never touches the resolver.
     */
    private fun parseIpv4Literal(host: String): ByteArray? {
        val parts = host.split(".")
        if (parts.size > 4) {
            return null
        }
        val nums = LongArray(parts.size)
        for (i in parts.indices) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 10) {
                return null
            }
            val v: Long
            if (p.length > 2 && (p.startsWith("0x") || p.startsWith("0X"))) {
                val digits = p.substring(2)
                if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    return null
                }
                v = digits.toLongOrNull(16) ?: return null
            } else if (p.length > 1 && p[0] == '0') {
                // Leading zero: octal on classic stacks, decimal on newer
                // ones — accept octal (the dangerous reading) and reject
                // malformed octal like "08" outright.
                if (!p.all { it in '0'..'7' }) {
                    return null
                }
                v = p.toLongOrNull(8) ?: return null
            } else {
                if (!p.all { it in '0'..'9' }) {
                    return null
                }
                v = p.toLongOrNull(10) ?: return null
            }
            if (v < 0) {
                return null
            }
            // Per-part ceilings mirror InetAddress: a lone number is 32 bits,
            // "a.b" gives b 24 bits, "a.b.c" gives c 16 bits.
            val ceiling = when (parts.size) {
                1 -> 0xFFFFFFFFL
                2 -> if (i == 0) 0xFFL else 0xFFFFFFL
                3 -> if (i < 2) 0xFFL else 0xFFFFL
                else -> 0xFFL
            }
            if (v > ceiling) {
                return null
            }
            nums[i] = v
        }
        val full: Long = when (parts.size) {
            1 -> nums[0]
            2 -> (nums[0] shl 24) or nums[1]
            3 -> (nums[0] shl 24) or (nums[1] shl 16) or nums[2]
            else -> (nums[0] shl 24) or (nums[1] shl 16) or (nums[2] shl 8) or nums[3]
        }
        return byteArrayOf(
            (full shr 24).toByte(), (full shr 16).toByte(),
            (full shr 8).toByte(), full.toByte()
        )
    }

    /** True for `::ffff:a.b.c.d` in raw 16-byte form. */
    private fun isV4Mapped(addr: ByteArray): Boolean {
        if (addr.size != 16) {
            return false
        }
        for (i in 0 until 10) {
            if (addr[i] != 0.toByte()) {
                return false
            }
        }
        return addr[10] == 0xFF.toByte() && addr[11] == 0xFF.toByte()
    }

    /**
     * Private/loopback/link-local/CGNAT/benchmarking/multicast test on raw
     * address bytes — the same ranges the old InetAddress-flag version
     * covered, plus IPv4-mapped IPv6 (`::ffff:127.0.0.1` is tested as the
     * IPv4 address it actually is).
     */
    private fun isPrivateBytes(addr: ByteArray): Boolean {
        if (addr.size == 4) {
            return isPrivateV4(
                addr[0].toInt() and 255, addr[1].toInt() and 255
            )
        }
        if (addr.size == 16) {
            if (isV4Mapped(addr)) {
                return isPrivateV4(addr[12].toInt() and 255, addr[13].toInt() and 255)
            }
            // :: (unspecified, the IPv6 0.0.0.0) and ::1 (loopback)
            var zeroPrefix = 0
            while (zeroPrefix < 16 && addr[zeroPrefix] == 0.toByte()) {
                zeroPrefix++
            }
            if (zeroPrefix == 16) {
                return true
            }
            if (zeroPrefix == 15 && addr[15] == 1.toByte()) {
                return true
            }
            val first = addr[0].toInt() and 255
            val second = addr[1].toInt() and 255
            // fc00::/7 unique-local, fe80::/10 link-local, ff00::/8 multicast
            return (first and 0xfe) == 0xfc ||
                (first == 0xfe && (second and 0xc0) == 0x80) ||
                first == 0xff
        }
        return false
    }

    /** The IPv4 private-range test, shared by plain and mapped addresses. */
    private fun isPrivateV4(a: Int, b: Int): Boolean =
        a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) ||
            (a == 198 && (b == 18 || b == 19)) ||
            (a == 192 && b == 0) ||
            a >= 224
}
