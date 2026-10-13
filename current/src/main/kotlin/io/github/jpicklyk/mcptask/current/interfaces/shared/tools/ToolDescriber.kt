package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the `data` of a `describe: true` call (the second documentation tier, plan section 3.1):
 *
 * `{tool, description, inputSchema, operations: [{name, description, parameters, errors: [{code, kind, detail?}]}]}`
 *
 * over the selects/ops visible to the view (an MCP profile, or [ToolView.Rest]), in registration order, narrowed to [only] when given.
 * `parameters` is the request class's full generated schema, including its own `required` list.
 * `errors` is the declared codes plus the common ones, sorted by wire code: `invalid_request`,
 * `unknown_parameter`, `unavailable`, `internal` for every operation, plus `invalid_cursor` for a paged
 * select, plus `partial_failure` and `idempotency_mismatch` for an op. `detail` is [ErrorDetailDocs.of].
 */
object ToolDescriber {
    val COMMON_ERRORS: Set<ErrorCode> =
        setOf(ErrorCode.INVALID_REQUEST, ErrorCode.UNKNOWN_PARAMETER, ErrorCode.UNAVAILABLE, ErrorCode.INTERNAL)
    val PAGED_ERRORS: Set<ErrorCode> = setOf(ErrorCode.INVALID_CURSOR)
    val OP_ERRORS: Set<ErrorCode> = setOf(ErrorCode.PARTIAL_FAILURE, ErrorCode.IDEMPOTENCY_MISMATCH)

    fun describe(
        spec: ToolSpec,
        profile: ToolView,
        only: Set<String>?
    ): JsonObject {
        val operations =
            when (spec) {
                is SelectToolSpec ->
                    spec.visibleSelects(profile).map { s ->
                        val extra = if (s.paged) PAGED_ERRORS else emptySet()
                        Operation(s.name, s.doc, s.request.descriptor, s.errors + COMMON_ERRORS + extra)
                    }
                is OpsToolSpec<*> ->
                    spec.visibleOps(profile).map { o ->
                        Operation(o.name, o.doc, o.request.descriptor, o.errors + COMMON_ERRORS + OP_ERRORS)
                    }
            }.filter { only == null || it.name in only }

        return buildJsonObject {
            put("tool", spec.name)
            put("description", spec.description)
            ToolSchemaRenderer.inputSchema(spec, profile)?.let { put("inputSchema", it) }
            put("operations", JsonArray(operations.map { it.render() }))
        }
    }

    private class Operation(
        val name: String,
        val doc: String,
        val request: SerialDescriptor,
        val errors: Set<ErrorCode>
    ) {
        fun render(): JsonObject =
            buildJsonObject {
                put("name", name)
                put("description", doc)
                put("parameters", JsonSchemaGenerator.schema(request))
                put(
                    "errors",
                    buildJsonArray {
                        errors.sortedBy { it.wire }.forEach { code ->
                            add(
                                buildJsonObject {
                                    put("code", code.wire)
                                    put("kind", code.kind.toJsonString())
                                    ErrorDetailDocs.of(code)?.let { put("detail", it) }
                                }
                            )
                        }
                    }
                )
            }
    }
}
