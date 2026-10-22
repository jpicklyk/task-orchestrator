package io.github.jpicklyk.mcptask.current.infrastructure.identity

import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.ktor.http.Url
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Thrown by [EgressGuard.check] when an outbound key-material fetch would reach a
 * non-public address.
 *
 * @param host The host named by the refused URL (as parsed, without IPv6 brackets).
 * @param address The resolved address that caused the refusal, or null when the refusal happened
 *   before any address was known (malformed URL, failed or empty resolution).
 * @param reason Short human-readable reason (the refused address class, or the parse/resolve failure).
 */
class EgressRefusedException(
    val host: String,
    val address: String?,
    val reason: String,
) : Exception(
        "egress refused for host '$host'" + (address?.let { " ($it)" } ?: "") + ": $reason",
    )

/**
 * Egress guard for every outbound fetch of key material (JWKS, OIDC discovery document) (AR-05 rec 4).
 *
 * [check] parses the URL's host, resolves it (a literal IP needs no DNS) and refuses the fetch when
 * ANY resolved address falls in a non-public class -- fail closed, so a name that resolves to a mix
 * of public and private addresses is refused:
 * - loopback: `127.0.0.0/8`, `::1`
 * - link-local: `169.254.0.0/16`, `fe80::/10`
 * - private (RFC 1918): `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
 * - CGNAT (RFC 6598): `100.64.0.0/10`
 * - unique local (RFC 4193): `fc00::/7`
 * - unspecified: `0.0.0.0`, `::`
 * - "this network" (RFC 1122): the rest of `0.0.0.0/8`
 * - multicast: `224.0.0.0/4`, `ff00::/8`
 * - reserved (RFC 1112): `240.0.0.0/4`, including the limited broadcast `255.255.255.255`
 * - IETF protocol assignments (RFC 6890): `192.0.0.0/24`
 * - benchmarking (RFC 2544): `198.18.0.0/15`
 * - deprecated site-local (RFC 3879): `fec0::/10`
 * - IPv4-compatible IPv6 (deprecated, RFC 4291): `::a.b.c.d`, i.e. `::/96` other than `::` and
 *   `::1` -- refused whatever the embedded IPv4 address is
 * - IPv4-embedding IPv6 wrapping a non-public IPv4 address: NAT64 `64:ff9b::/96` (IPv4 in the last
 *   32 bits) and 6to4 `2002::/16` (IPv4 in bits 16-47)
 *
 * **Embedded-address rule.** An IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) is classified by its
 * IPv4 part. A NAT64 or 6to4 address is classified by its embedded IPv4 address: it is admitted
 * only when that IPv4 address is public, and otherwise refused as [AddressClass.EMBEDDED_IPV4] --
 * never as loopback, so [allowLoopback] does NOT admit a NAT64/6to4 wrapper of `127.0.0.0/8`. An
 * IPv4-compatible address is refused outright.
 *
 * A host whose text looks like a numeric IP literal (only digits and dots, or containing `:`) is
 * parsed as a literal; one that does not parse is refused. A failed or empty resolution is refused
 * too.
 *
 * With [allowLoopback] (wired from `allow_insecure_url` / `API_JWKS_ALLOW_INSECURE_URL`) the
 * loopback class -- and only that class -- is admitted, for local development.
 *
 * **Honest limit:** this is resolve-then-connect. The HTTP client resolves the host again when it
 * connects, so a DNS-rebinding attacker controlling the authoritative name server can answer this
 * check with a public address and the connect with a private one (TOCTOU). Closing that gap needs a
 * connect-by-resolved-IP client and is out of scope here. The guard still stops every statically
 * private key source and every honestly-resolving private name.
 *
 * @param allowLoopback Admit the loopback class (and only it).
 * @param resolver Host-to-addresses resolution; injectable so tests are hermetic (no real DNS).
 *   The production default, [InetAddress.getAllByName], blocks -- coroutine callers run [check] on
 *   an IO dispatcher.
 */
class EgressGuard(
    val allowLoopback: Boolean,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /**
     * Returns normally when [url] may be fetched; throws [EgressRefusedException] otherwise.
     */
    fun check(url: String) {
        val host = parseHost(url)
        val addresses = resolve(host)
        if (addresses.isEmpty()) {
            throw EgressRefusedException(host, null, "host resolved to no addresses")
        }
        for (address in addresses) {
            val refusedClass = classify(address) ?: continue
            if (refusedClass == AddressClass.LOOPBACK && allowLoopback) continue
            throw EgressRefusedException(host, address.hostAddress, "${refusedClass.label} address")
        }
    }

    private fun parseHost(url: String): String {
        val host =
            try {
                Url(url).host
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                throw EgressRefusedException(url, null, "malformed URL: ${e.message}")
            }
        val bare = host.trim().removeSurrounding("[", "]")
        if (bare.isEmpty()) {
            throw EgressRefusedException(url, null, "URL has no host")
        }
        return bare
    }

    private fun resolve(host: String): List<InetAddress> {
        if (looksLikeIpLiteral(host)) {
            // A well-formed literal IP needs no DNS: InetAddress.getByName parses it without a lookup.
            // getByName may still hand an odd literal-looking host (e.g. a trailing dot) to DNS; that
            // is safe because every address it returns is classified below like any other answer, and
            // a lookup failure is refused here.
            return try {
                listOf(InetAddress.getByName(host))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                throw EgressRefusedException(host, null, "malformed IP literal")
            }
        }
        return try {
            resolver(host)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            throw EgressRefusedException(host, null, "DNS resolution failed: ${e.message}")
        }
    }

    private fun looksLikeIpLiteral(host: String): Boolean = host.contains(':') || host.all { it.isDigit() || it == '.' }

    /** Refused address classes; [label] is surfaced in the refusal reason. */
    enum class AddressClass(
        val label: String,
    ) {
        LOOPBACK("loopback"),
        LINK_LOCAL("link-local"),
        PRIVATE("private (RFC 1918)"),
        CGNAT("CGNAT (RFC 6598)"),
        UNIQUE_LOCAL("unique local (RFC 4193)"),
        UNSPECIFIED("unspecified"),
        THIS_NETWORK("this network (0.0.0.0/8)"),
        MULTICAST("multicast"),
        RESERVED("reserved (240.0.0.0/4)"),
        IETF_PROTOCOL("IETF protocol assignment (192.0.0.0/24)"),
        BENCHMARKING("benchmarking (198.18.0.0/15)"),
        SITE_LOCAL("deprecated site-local (fec0::/10)"),
        IPV4_COMPATIBLE("IPv4-compatible IPv6 (::/96)"),
        EMBEDDED_IPV4("NAT64/6to4 wrapping a non-public IPv4"),
    }

    companion object {
        /**
         * Guard for a key source: [allowInsecureUrl] (the flag that admits plaintext http for a
         * loopback host) also admits the loopback address class here, and nothing else.
         */
        fun forConfig(allowInsecureUrl: Boolean): EgressGuard = EgressGuard(allowLoopback = allowInsecureUrl)

        /** Returns the refused class of [address], or null when it is a public address. */
        fun classify(address: InetAddress): AddressClass? {
            val bytes = address.address
            return when {
                address is Inet4Address -> classifyV4(bytes)
                address is Inet6Address && isV4Mapped(bytes) -> classifyV4(bytes.copyOfRange(12, 16))
                address is Inet6Address -> classifyV6(bytes)
                else -> null
            }
        }

        private fun isV4Mapped(bytes: ByteArray): Boolean =
            bytes.size == 16 &&
                (0 until 10).all { bytes[it] == 0.toByte() } &&
                bytes[10] == 0xff.toByte() &&
                bytes[11] == 0xff.toByte()

        private fun classifyV4(bytes: ByteArray): AddressClass? {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            return when {
                bytes.all { it == 0.toByte() } -> AddressClass.UNSPECIFIED
                b0 == 127 -> AddressClass.LOOPBACK
                b0 == 169 && b1 == 254 -> AddressClass.LINK_LOCAL
                b0 == 10 -> AddressClass.PRIVATE
                b0 == 172 && b1 in 16..31 -> AddressClass.PRIVATE
                b0 == 192 && b1 == 168 -> AddressClass.PRIVATE
                b0 == 100 && b1 in 64..127 -> AddressClass.CGNAT
                b0 == 0 -> AddressClass.THIS_NETWORK
                b0 in 224..239 -> AddressClass.MULTICAST
                b0 >= 240 -> AddressClass.RESERVED
                b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xff) == 0 -> AddressClass.IETF_PROTOCOL
                b0 == 198 && (b1 == 18 || b1 == 19) -> AddressClass.BENCHMARKING
                else -> null
            }
        }

        private fun classifyV6(bytes: ByteArray): AddressClass? {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            val zeroPrefix = (0 until 15).all { bytes[it] == 0.toByte() }
            return when {
                zeroPrefix && bytes[15] == 0.toByte() -> AddressClass.UNSPECIFIED
                zeroPrefix && bytes[15] == 1.toByte() -> AddressClass.LOOPBACK
                b0 == 0xfe && (b1 and 0xc0) == 0x80 -> AddressClass.LINK_LOCAL
                (b0 and 0xfe) == 0xfc -> AddressClass.UNIQUE_LOCAL
                b0 == 0xfe && (b1 and 0xc0) == 0xc0 -> AddressClass.SITE_LOCAL
                b0 == 0xff -> AddressClass.MULTICAST
                (0 until 12).all { bytes[it] == 0.toByte() } -> AddressClass.IPV4_COMPATIBLE
                isNat64(bytes) -> embedded(bytes.copyOfRange(12, 16))
                b0 == 0x20 && b1 == 0x02 -> embedded(bytes.copyOfRange(2, 6))
                else -> null
            }
        }

        /** NAT64 well-known prefix `64:ff9b::/96`. */
        private fun isNat64(bytes: ByteArray): Boolean =
            bytes[0] == 0x00.toByte() &&
                bytes[1] == 0x64.toByte() &&
                bytes[2] == 0xff.toByte() &&
                bytes[3] == 0x9b.toByte() &&
                (4 until 12).all { bytes[it] == 0.toByte() }

        /**
         * A NAT64/6to4 wrapper is admitted only when its embedded IPv4 address is public; any
         * non-public embedded class (loopback included) maps to [AddressClass.EMBEDDED_IPV4] so that
         * allowLoopback never admits it.
         */
        private fun embedded(v4: ByteArray): AddressClass? = classifyV4(v4)?.let { AddressClass.EMBEDDED_IPV4 }
    }
}
