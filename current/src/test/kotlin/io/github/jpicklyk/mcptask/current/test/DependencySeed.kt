package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Seeds dependency rows for a test through the one write policy ([ToolExecutionContext.dependencyCommandService]),
 * after the 3.x `manage_dependencies` tool that tests used for this was removed. Each element of [specs] is
 * `{fromItemId, toItemId, type?, unblockAt?}`; `type` defaults to BLOCKS and IS_BLOCKED_BY is normalized by the service.
 * Fails the test when the service rejects the batch.
 */
suspend fun ToolExecutionContext.seedDependencies(specs: JsonArray): List<Dependency> {
    val deps =
        specs.map { element ->
            val spec = element.jsonObject
            Dependency(
                fromItemId = UUID.fromString(spec.getValue("fromItemId").jsonPrimitive.content),
                toItemId = UUID.fromString(spec.getValue("toItemId").jsonPrimitive.content),
                type = spec["type"]?.jsonPrimitive?.content?.let { DependencyType.fromString(it) } ?: DependencyType.BLOCKS,
                unblockAt = spec["unblockAt"]?.jsonPrimitive?.content
            )
        }
    return when (val outcome = dependencyCommandService.create(deps, caller = null)) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw AssertionError("dependency setup failed: ${outcome.error}")
    }
}
