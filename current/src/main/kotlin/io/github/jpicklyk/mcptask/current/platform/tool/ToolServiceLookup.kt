package io.github.jpicklyk.mcptask.current.platform.tool

import kotlin.reflect.KClass

/**
 * Serves the services holder a select or op declares in its `services` field: the dispatcher looks the holder up at
 * call time and hands it to the handler as `env`, so a spec names only the services its slice uses. A type nobody
 * registered is a programming error, never a call outcome: the lookup throws, and [isRegistered] lets the dispatcher
 * refuse such a spec before any call.
 */
interface ToolServiceLookup {
    /** True when a holder of [type] is served, so [get] of it cannot fail for want of one. */
    fun isRegistered(type: KClass<*>): Boolean

    fun <S : Any> get(type: KClass<S>): S
}
