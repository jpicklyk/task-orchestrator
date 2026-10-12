package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.ktor.server.routing.Route

/**
 * Test wiring for the item resource GETs: since Q19 [itemRoutes] dispatches `items_read` through the production tool
 * registry and dispatcher. This overload builds both over [provider] (a [ToolExecutionContext] with its default unit of
 * work), so a route test that only has a repository provider mounts the same production routes.
 */
fun Route.itemRoutes(provider: RepositoryProvider) {
    itemRoutes(ToolRegistry(buildToolSpecs()), buildToolDispatcher(ToolExecutionContext(provider)))
}
