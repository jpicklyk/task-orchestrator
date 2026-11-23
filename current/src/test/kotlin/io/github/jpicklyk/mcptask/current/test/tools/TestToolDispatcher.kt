package io.github.jpicklyk.mcptask.current.test.tools

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.platform.request.RequestDecoder
import io.github.jpicklyk.mcptask.current.platform.tool.ToolServiceLookup

/**
 * A dispatcher over [env] for a test, built with the registry it validates against. Production passes the composition's
 * registry to `buildToolDispatcher`; a test that has none of its own gets a fresh one here, and may pass the [registry]
 * it also serves routes from. A test that runs fixture specs passes [services] (see [FixtureToolServices]); without it the
 * dispatcher takes the production lookup.
 */
fun testToolDispatcher(
    env: ToolExecutionContext,
    registry: ToolRegistry = ToolRegistry(buildToolSpecs()),
    services: ToolServiceLookup? = null
): ToolDispatcher =
    if (services == null) {
        buildToolDispatcher(env, registry)
    } else {
        ToolDispatcher(RequestDecoder(), BatchRunner(env.unitOfWork, env.idempotency), env, registry.tools, services)
    }
