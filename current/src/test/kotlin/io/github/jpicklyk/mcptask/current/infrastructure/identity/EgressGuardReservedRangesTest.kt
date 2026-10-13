package io.github.jpicklyk.mcptask.current.infrastructure.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Fix round a1 for item 385444c2 (R8): the reserved, multicast and embedded-IPv4 ranges that
 * [EgressGuard] must refuse in addition to the original private classes.
 *
 * Oracle: the a1 contract spec (egress-a1-spec.txt). Ranges: 0.0.0.0/8 except 0.0.0.0 (this
 * network), 224.0.0.0/4 and ff00::/8 (multicast), 240.0.0.0/4 (reserved), 192.0.0.0/24 (IETF
 * protocol), 198.18.0.0/15 (benchmarking), fec0::/10 (site-local), ::/96 other than :: and ::1
 * (IPv4-compatible, always refused), NAT64 64:ff9b::/96 and 6to4 2002::/16 classified by their
 * embedded IPv4 (public admitted; non-public refused, loopback included, even with allowLoopback).
 *
 * Every resolver is injected; every address is a numeric literal, so no test performs a DNS lookup.
 */
class EgressGuardReservedRangesTest {
    private val url = "https://range.eg.example/jwks.json"
    private val embeddedLabel = "NAT64/6to4 wrapping a non-public IPv4"

    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun guardResolvingTo(
        vararg addresses: String,
        allowLoopback: Boolean = false,
    ) = EgressGuard(allowLoopback) { addresses.map { addr(it) } }

    private fun refusal(
        guard: EgressGuard,
        target: String = url,
    ): EgressRefusedException? =
        try {
            guard.check(target)
            null
        } catch (e: EgressRefusedException) {
            e
        }

    private fun assertRefusedAs(
        address: String,
        label: String,
        allowLoopback: Boolean = false,
    ) {
        val e =
            refusal(guardResolvingTo(address, allowLoopback = allowLoopback))
                ?: fail("expected $address (allowLoopback=$allowLoopback) to be refused")
        assertTrue(
            e.reason.contains(label),
            "refusal of $address should carry the label [$label] but was: ${e.reason}"
        )
    }

    private fun assertRefusedBoth(address: String) {
        listOf(false, true).forEach { lb ->
            if (refusal(guardResolvingTo(address, allowLoopback = lb)) == null) {
                fail("expected $address to be refused with allowLoopback=$lb")
            }
        }
    }

    private fun assertAdmitted(
        address: String,
        allowLoopback: Boolean = false,
    ) {
        val e = refusal(guardResolvingTo(address, allowLoopback = allowLoopback))
        assertNull(e, "expected $address (allowLoopback=$allowLoopback) to be admitted but: ${e?.reason}")
    }

    // ---- 0.0.0.0/8 (this network), 0.0.0.0 itself stays UNSPECIFIED ----

    @Test
    fun `this-network 0_0_0_0 slash 8 is refused except that 0_0_0_0 keeps its own class`() {
        listOf("0.0.0.1", "0.1.2.3", "0.128.0.0", "0.255.255.255").forEach {
            assertRefusedAs(it, "this network (0.0.0.0/8)")
            assertRefusedBoth(it)
        }
        val e = refusal(guardResolvingTo("0.0.0.0")) ?: fail("0.0.0.0 must be refused")
        assertTrue(!e.reason.contains("this network (0.0.0.0/8)"), "0.0.0.0 must keep UNSPECIFIED: ${e.reason}")
    }

    @Test
    fun `the address just after 0_0_0_0 slash 8 is public`() {
        assertAdmitted("1.0.0.0")
        assertAdmitted("1.0.0.1")
    }

    // ---- multicast ----

    @Test
    fun `IPv4 multicast 224 slash 4 is refused at both edges and inside`() {
        listOf("224.0.0.0", "224.0.0.1", "232.1.2.3", "239.255.255.254", "239.255.255.255").forEach {
            assertRefusedAs(it, "multicast")
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `address just below 224 slash 4 is admitted`() {
        assertAdmitted("223.255.255.255")
        assertAdmitted("223.0.0.1")
    }

    @Test
    fun `IPv6 multicast ff00 slash 8 is refused including uppercase and compressed forms`() {
        listOf("ff00::", "ff02::1", "FF02::1", "ff05:0:0:0:0:0:0:2", "ff0e::1", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff").forEach {
            assertRefusedAs(it, "multicast")
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `IPv6 address below ff00 slash 8 is not multicast`() {
        assertAdmitted("2606:2800:220:1::1")
    }

    // ---- 240.0.0.0/4 reserved ----

    @Test
    fun `reserved 240 slash 4 is refused at both edges and for the broadcast address`() {
        listOf("240.0.0.0", "240.0.0.1", "247.1.2.3", "254.255.255.255", "255.255.255.254", "255.255.255.255").forEach {
            assertRefusedAs(it, "reserved (240.0.0.0/4)")
            assertRefusedBoth(it)
        }
    }

    // ---- 192.0.0.0/24 IETF protocol ----

    @Test
    fun `IETF protocol assignments 192_0_0_0 slash 24 are refused at both edges`() {
        listOf("192.0.0.0", "192.0.0.1", "192.0.0.170", "192.0.0.255").forEach {
            assertRefusedAs(it, "IETF protocol assignment (192.0.0.0/24)")
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `addresses just outside 192_0_0_0 slash 24 are admitted`() {
        assertAdmitted("192.0.1.0")
        assertAdmitted("192.0.1.1")
        assertAdmitted("191.255.255.255")
    }

    // ---- 198.18.0.0/15 benchmarking ----

    @Test
    fun `benchmarking 198_18 slash 15 is refused at both edges and across the two slash 16s`() {
        listOf("198.18.0.0", "198.18.0.1", "198.18.255.255", "198.19.0.0", "198.19.255.255").forEach {
            assertRefusedAs(it, "benchmarking (198.18.0.0/15)")
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `addresses just outside 198_18 slash 15 are public`() {
        assertAdmitted("198.17.255.255")
        assertAdmitted("198.17.0.1")
        assertAdmitted("198.20.0.0")
        assertAdmitted("198.20.0.1")
    }

    // ---- fec0::/10 site-local ----

    @Test
    fun `deprecated site-local fec0 slash 10 is refused at both edges`() {
        listOf(
            "fec0::",
            "fec0::1",
            "FEC0::1",
            "fee0::1",
            "feff::1",
            "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
        ).forEach {
            assertRefusedAs(it, "deprecated site-local (fec0::/10)")
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `neighbours of fec0 slash 10 keep their original verdicts`() {
        // febf::1 is link-local (fe80::/10), refused; fbff::1 is below fc00::/7 and public.
        assertRefusedBoth("febf::1")
        assertAdmitted("fbff::1")
    }

    // ---- IPv4-compatible ::/96 ----

    @Test
    fun `IPv4-compatible IPv6 is refused whatever the embedded IPv4 is`() {
        listOf(
            "::8.8.8.8",
            "::0.0.0.2",
            "::0.0.0.3",
            "::127.0.0.1",
            "::10.0.0.1",
            "::808:808",
            "0:0:0:0:0:0:808:808",
            "::255.255.255.255"
        ).forEach {
            assertRefusedAs(it, "IPv4-compatible IPv6 (::/96)")
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `IPv4-compatible refusal leaves unspecified, loopback and mapped addresses alone`() {
        val unspecified = refusal(guardResolvingTo("::")) ?: fail(":: must be refused")
        assertTrue(!unspecified.reason.contains("IPv4-compatible"), unspecified.reason)
        assertAdmitted("::1", allowLoopback = true)
        assertTrue(refusal(guardResolvingTo("::1")) != null)
        assertAdmitted("::ffff:8.8.8.8")
        assertAdmitted("::ffff:127.0.0.1", allowLoopback = true)
    }

    // ---- NAT64 64:ff9b::/96 ----

    @Test
    fun `NAT64 wrapping a public IPv4 is admitted`() {
        assertAdmitted("64:ff9b::8.8.8.8")
        assertAdmitted("64:ff9b::808:808")
        assertAdmitted("64:ff9b::5db8:d822")
        assertAdmitted("64:ff9b::8.8.8.8", allowLoopback = true)
    }

    @Test
    fun `NAT64 wrapping a non-public IPv4 is refused with the embedded label`() {
        listOf(
            "64:ff9b::10.0.0.1",
            "64:ff9b::a00:1",
            "64:ff9b::10.255.255.255",
            "64:ff9b::192.168.1.1",
            "64:ff9b::192.168.255.255",
            "64:ff9b::172.16.0.1",
            "64:ff9b::169.254.169.254",
            "64:ff9b::100.64.0.1",
            "64:ff9b::224.0.0.1",
            "64:ff9b::255.255.255.255",
            "64:ff9b::0.0.0.0",
            "64:FF9B::A00:1"
        ).forEach {
            assertRefusedAs(it, embeddedLabel)
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `NAT64 wrapping loopback is refused as embedded and not admitted by allowLoopback`() {
        listOf("64:ff9b::127.0.0.1", "64:ff9b::7f00:1", "64:ff9b::127.255.255.255").forEach {
            assertRefusedAs(it, embeddedLabel, allowLoopback = false)
            assertRefusedAs(it, embeddedLabel, allowLoopback = true)
        }
    }

    @Test
    fun `only the well-known NAT64 prefix is recognised`() {
        assertAdmitted("64:ff9b:1::10.0.0.1")
        assertAdmitted("64:ff9b:1::1")
        assertAdmitted("64:ff9a:ffff:ffff:ffff:ffff:ffff:ffff")
        assertAdmitted("64:ff9c::1")
    }

    // ---- 6to4 2002::/16 ----

    @Test
    fun `6to4 wrapping a public IPv4 is admitted`() {
        assertAdmitted("2002:0808:0808::1")
        assertAdmitted("2002:808:808::1")
        assertAdmitted("2002:5db8:d822:1:2:3:4:5")
        assertAdmitted("2002:0808:0808::1", allowLoopback = true)
    }

    @Test
    fun `6to4 wrapping a non-public IPv4 is refused at the range edges`() {
        listOf(
            "2002:0a00:0000::",
            "2002:0aff:ffff::1",
            "2002:c0a8:0101::1",
            "2002:c0a8:0000::",
            "2002:c0a8:ffff::1",
            "2002:ac10:0001::1",
            "2002:6440:0001::1",
            "2002:0000:0000::",
            "2002:e000:0001::1",
            "2002:ffff:ffff::1",
            "2002:C0A8:0101::1"
        ).forEach {
            assertRefusedAs(it, embeddedLabel)
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `6to4 wrapping loopback is refused as embedded and not admitted by allowLoopback`() {
        listOf("2002:7f00:0001::1", "2002:7f00:1::1", "2002:7fff:ffff::1").forEach {
            assertRefusedAs(it, embeddedLabel, allowLoopback = false)
            assertRefusedAs(it, embeddedLabel, allowLoopback = true)
        }
    }

    @Test
    fun `6to4 wrapping link-local metadata address 169_254_x is refused`() {
        listOf("2002:a9fe:a9fe::1", "2002:a9fe:0000::", "2002:a9fe:ffff::1").forEach {
            assertRefusedAs(it, embeddedLabel)
            assertRefusedBoth(it)
        }
    }

    @Test
    fun `6to4 uses the 32 bits after 2002 so a private tail with a public prefix is admitted`() {
        assertAdmitted("2002:0808:0808::a00:1")
        assertAdmitted("2002:0808:0808:0:0:0:c0a8:101")
    }

    @Test
    fun `addresses just outside 2002 slash 16 are ordinary IPv6`() {
        assertAdmitted("2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        assertAdmitted("2003::1")
    }

    // ---- new ranges via embedded v4 (the embedded rule uses the full IPv4 rule set) ----

    @Test
    fun `embedded IPv4 in the newly reserved ranges is refused through NAT64 and 6to4`() {
        listOf(
            "64:ff9b::198.18.0.1",
            "64:ff9b::192.0.0.1",
            "64:ff9b::240.0.0.1",
            "2002:c612:0001::1",
            "2002:c000:0001::1",
            "2002:f000:0001::1"
        ).forEach {
            assertRefusedAs(it, embeddedLabel)
        }
        assertAdmitted("64:ff9b::198.20.0.1")
        assertAdmitted("64:ff9b::192.0.1.1")
        assertAdmitted("2002:c614:0001::1")
        assertAdmitted("2002:c000:0101::1")
    }

    // ---- literal URLs and fail-closed ----

    @Test
    fun `literal URLs in the new ranges are refused without any DNS lookup`() {
        val guard =
            EgressGuard(false) { host ->
                fail("a literal IP must not be resolved, but the resolver was asked for $host")
            }
        listOf(
            "https://0.1.2.3/x",
            "https://224.0.0.1/x",
            "https://239.255.255.255/x",
            "https://240.0.0.1/x",
            "https://255.255.255.255/x",
            "https://192.0.0.8/x",
            "https://198.18.0.1/x",
            "https://198.19.255.255/x",
            "https://[ff02::1]/x",
            "https://[fec0::1]/x",
            "https://[::808:808]/x",
            "https://[64:ff9b::a00:1]/x",
            "https://[2002:c0a8:101::1]/x",
            "https://[2002:a9fe:a9fe::1]/x"
        ).forEach { u ->
            if (refusal(guard, u) == null) fail("expected $u to be refused")
        }
        listOf("https://198.20.0.0/x", "https://223.255.255.255/x", "https://[64:ff9b::808:808]/x", "https://[2002:808:808::1]/x")
            .forEach { u ->
                assertNull(refusal(guard, u)?.reason, "expected $u to be admitted")
            }
    }

    @Test
    fun `a host resolving to one public and one newly reserved address is refused in either order`() {
        listOf(
            "224.0.0.1",
            "240.0.0.1",
            "198.18.0.1",
            "192.0.0.1",
            "0.1.2.3",
            "::8.8.8.8",
            "fec0::1",
            "ff02::1",
            "64:ff9b::a00:1",
            "2002:c0a8:101::1"
        ).forEach { bad ->
            val first = refusal(guardResolvingTo("93.184.216.34", bad)) ?: fail("public then $bad must be refused")
            assertEquals("range.eg.example", first.host)
            if (refusal(guardResolvingTo(bad, "93.184.216.34")) == null) fail("$bad then public must be refused")
        }
    }

    @Test
    fun `allowLoopback does not admit a loopback plus a newly reserved answer`() {
        listOf("224.0.0.1", "240.0.0.1", "198.18.0.1", "192.0.0.1", "0.1.2.3", "::127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::1")
            .forEach { bad ->
                if (refusal(guardResolvingTo("127.0.0.1", bad, allowLoopback = true)) == null) {
                    fail("127.0.0.1 plus $bad must be refused even with allowLoopback")
                }
            }
    }
}
