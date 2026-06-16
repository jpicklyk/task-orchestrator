package io.github.jpicklyk.mcptask.current.application.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val DOPEMUX_PROOF_SCHEMA_VERSION = "1"
private const val DOPEMUX_CANONICAL_WRITER = "task-orchestrator"
private const val NO_REQUEST_ID = "not-provided"
private const val NO_ACTOR = "anonymous"

internal fun buildDopemuxProofEnvelope(
    operation: String,
    workflowId: String,
    transition: String,
    requestId: String?,
    actorId: String?,
    status: String
) = buildJsonObject {
    put("schema_version", JsonPrimitive(DOPEMUX_PROOF_SCHEMA_VERSION))
    put("workflow_id", JsonPrimitive(workflowId))
    put("transition", JsonPrimitive(transition))
    put("idempotency_key", JsonPrimitive(requestId?.takeIf { it.isNotBlank() } ?: NO_REQUEST_ID))
    put("actor", JsonPrimitive(actorId?.takeIf { it.isNotBlank() } ?: NO_ACTOR))
    put("canonical_writer", JsonPrimitive(DOPEMUX_CANONICAL_WRITER))
    put(
        "receipt",
        buildJsonObject {
            put("proof_id", JsonPrimitive("$DOPEMUX_CANONICAL_WRITER:$operation:$workflowId"))
            put("operation", JsonPrimitive(operation))
            put("status", JsonPrimitive(status))
        }
    )
}
