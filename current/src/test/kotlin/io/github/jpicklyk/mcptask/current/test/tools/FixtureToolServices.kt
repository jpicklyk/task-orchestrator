package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolServiceRegistry
import io.github.jpicklyk.mcptask.current.platform.tool.ToolServiceLookup
import kotlin.reflect.KClass

/**
 * The test-only services lookup for fixture specs that take the whole [ToolExecutionContext] as `env`: the production
 * [ToolServiceRegistry] never serves it (so no production select or op can name it), and this lookup adds it on top.
 * Pass it to a dispatcher that must run [FixtureTools] or the parity fixture specs.
 */
class FixtureToolServices(
    private val env: ToolExecutionContext,
    private val production: ToolServiceLookup = ToolServiceRegistry(env)
) : ToolServiceLookup {
    override fun isRegistered(type: KClass<*>): Boolean = type == ToolExecutionContext::class || production.isRegistered(type)

    override fun <S : Any> get(type: KClass<S>): S = if (type == ToolExecutionContext::class) type.java.cast(env) else production.get(type)
}
