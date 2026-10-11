package io.github.jpicklyk.mcptask.current.application.config

/**
 * The server-scope config layer, read from the DB head of `(SERVER, null)` on every call (the `server-config-import`
 * boot step appends the mounted global file as a `file-import` version). `null` = absence: no server version exists,
 * or its head is a reset. A head whose stored canonical form is missing or undecodable throws
 * `PerRootConfigInvalidException(rootId = null, ...)` (fail closed, never absence); a read fault with nothing
 * servable throws `PerRootConfigUnavailableException(rootId = null, ...)`.
 */
fun interface ServerConfigSource {
    suspend fun layer(): ConfigLayer?
}
