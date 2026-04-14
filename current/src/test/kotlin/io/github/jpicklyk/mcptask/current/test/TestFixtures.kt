package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.domain.model.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.assertTrue

// ── WorkItem builder ──

fun makeItem(
    id: UUID = UUID.randomUUID(),
    title: String = "Test Item",
    role: Role = Role.QUEUE,
    previousRole: Role? = null,
    parentId: UUID? = null,
    depth: Int = if (parentId != null) 1 else 0,
    priority: Priority = Priority.MEDIUM,
    complexity: Int? = null,
    tags: String? = null,
    statusLabel: String? = null,
    description: String? = null,
    summary: String = "",
    metadata: String? = null,
    requiresVerification: Boolean = false
): WorkItem =
    WorkItem(
        id = id,
        title = title,
        role = role,
        previousRole = previousRole,
        parentId = parentId,
        depth = depth,
        priority = priority,
        complexity = complexity,
        tags = tags,
        statusLabel = statusLabel,
        description = description,
        summary = summary,
        metadata = metadata,
        requiresVerification = requiresVerification
    )

// ── Dependency builders ──

fun blocksDep(
    fromItemId: UUID,
    toItemId: UUID,
    unblockAt: String? = null
): Dependency =
    Dependency(
        fromItemId = fromItemId,
        toItemId = toItemId,
        type = DependencyType.BLOCKS,
        unblockAt = unblockAt
    )

fun relatesDep(
    fromItemId: UUID,
    toItemId: UUID
): Dependency =
    Dependency(
        fromItemId = fromItemId,
        toItemId = toItemId,
        type = DependencyType.RELATES_TO
    )

// ── Note builder ──

fun makeNote(
    itemId: UUID,
    key: String = "test-note",
    role: String = "queue",
    body: String = "Test body",
    actorClaim: ActorClaim? = null,
    verification: VerificationResult? = null
): Note =
    Note(
        itemId = itemId,
        key = key,
        role = role,
        body = body,
        actorClaim = actorClaim,
        verification = verification
    )

// ── Actor attribution builders ──

fun makeActorClaim(
    id: String = "agent-1",
    kind: ActorKind = ActorKind.SUBAGENT,
    parent: String? = null,
    proof: String? = null
): ActorClaim = ActorClaim(id = id, kind = kind, parent = parent, proof = proof)

fun makeVerificationResult(
    status: VerificationStatus = VerificationStatus.UNVERIFIED,
    verifier: String? = "noop",
    reason: String? = null
): VerificationResult = VerificationResult(status = status, verifier = verifier, reason = reason)

// ── JSON param helpers ──

fun params(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

fun buildActorJson(
    id: String = "agent-1",
    kind: String = "subagent",
    parent: String? = null,
    proof: String? = null
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("kind", kind)
        parent?.let { put("parent", it) }
        proof?.let { put("proof", it) }
    }

fun transitionObj(
    itemId: UUID,
    trigger: String,
    summary: String? = null,
    actor: JsonObject? = null
): JsonObject =
    buildJsonObject {
        put("itemId", itemId.toString())
        put("trigger", trigger)
        summary?.let { put("summary", it) }
        actor?.let { put("actor", it) }
    }

fun buildTransitionParams(vararg transitions: JsonObject): JsonObject =
    buildJsonObject {
        put("transitions", buildJsonArray { transitions.forEach { add(it) } })
    }

// ── Response extraction helpers ──

/**
 * Asserts that the JSON result has `success=true` and returns the `data` object.
 */
fun extractSuccessData(result: JsonElement): JsonObject {
    val obj = result.jsonObject
    assertTrue(obj["success"]?.jsonPrimitive?.boolean == true, "Expected success=true but got: $obj")
    return obj["data"]!!.jsonObject
}

/**
 * Extracts `data.results` as a JsonArray from a successful response.
 */
fun extractResults(result: JsonElement): JsonArray {
    val data = extractSuccessData(result)
    return data["results"]!!.jsonArray
}

/**
 * Extracts `data.summary` as a JsonObject from a successful response.
 */
fun extractSummary(result: JsonElement): JsonObject {
    val data = extractSuccessData(result)
    return data["summary"]!!.jsonObject
}

/**
 * Asserts that the JSON result represents an error response (success=false).
 * Optionally checks that the error message contains [expectedMessage].
 * Returns the full response object for further assertions.
 */
fun assertErrorResponse(
    result: JsonElement,
    expectedMessage: String? = null
): JsonObject {
    val obj = result.jsonObject
    assertTrue(obj["success"]?.jsonPrimitive?.boolean == false, "Expected success=false but got: $obj")
    expectedMessage?.let { expected ->
        val msg = obj["error"]?.jsonPrimitive?.content ?: obj["message"]?.jsonPrimitive?.content ?: ""
        assertTrue(
            msg.contains(expected, ignoreCase = true),
            "Expected error message to contain '$expected' but got: '$msg'"
        )
    }
    return obj
}
