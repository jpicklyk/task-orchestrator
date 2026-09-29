package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * [ServerOptions] for tests that wire an SDK `Server` to a client over an in-memory
 * `ChannelTransport` pair and register tools AFTER the session is connected (the per-test
 * `registerToolWithServer` pattern).
 *
 * `tools.listChanged` is deliberately OFF. With it on, every `addTool` after connect makes the SDK
 * (kotlin-sdk 0.12.0) emit `notifications/tools/list_changed` from `SessionNotificationJob`, a
 * collector launched in `FeatureNotificationService`'s own `CoroutineScope(SupervisorJob() +
 * Dispatchers.Default)` with no exception handler and no try/catch around the send. A test that
 * finishes in ~1ms tears the transport down before that job runs, the send throws
 * `McpException: Transport is not ready`, and the exception escapes as an uncaught coroutine
 * exception — which kotlinx-coroutines-test then pins on whichever `runTest`-based test runs next
 * as `UncaughtExceptionsBeforeTest` (bug 947cc2ec). None of these tests exercise list_changed.
 * Production is unaffected: it registers every tool before any session exists.
 */
internal fun inMemoryTestServerOptions(): ServerOptions =
    ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))
