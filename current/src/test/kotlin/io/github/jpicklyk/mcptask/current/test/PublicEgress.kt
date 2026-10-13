package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.infrastructure.identity.EgressGuard
import java.net.InetAddress

/**
 * A DNS-free [EgressGuard] for tests that fetch over a MockEngine from a non-literal host: every
 * name resolves to the public address 93.184.216.34, so the guard admits it without a real lookup.
 * A literal IP in the URL is still classified as written (literals never reach the resolver).
 */
fun publicEgressGuard(allowLoopback: Boolean = false): EgressGuard =
    EgressGuard(allowLoopback) { listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))) }
