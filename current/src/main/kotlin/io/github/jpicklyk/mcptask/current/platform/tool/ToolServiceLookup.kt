package io.github.jpicklyk.mcptask.current.platform.tool

import kotlin.reflect.KClass

/**
 * Serves the services holder a select or op declares in its `services` field: the dispatcher looks the holder up at
 * call time and hands it to the handler as `env`, so a spec names only the services its slice uses. A type nobody
 * registered is a programming error, never a call outcome: the lookup throws.
 */
interface ToolServiceLookup {
    fun <S : Any> get(type: KClass<S>): S
}
