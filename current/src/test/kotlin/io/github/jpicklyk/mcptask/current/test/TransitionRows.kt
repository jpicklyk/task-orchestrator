package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.PageRequest
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.parseProofClaimsOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The applied transitions of an item as [RoleTransition] values rebuilt from its `item.transitioned` event rows
 * (newest first): the audit record since `role_transitions` was retired (V29). For tests that assert on what an
 * advance recorded; the fields are the event's, so an absent verification or actor reads back as null.
 */
suspend fun EventStore.transitionsOf(itemId: UUID): List<RoleTransition> =
    findByEntity(itemId, setOf(DomainEvent.ITEM_TRANSITIONED), PageRequest(PageRequest.MAX_LIMIT, 0)).items.map { it.toRoleTransition() }

/** [EventStore.transitionsOf] over a provider's event store. */
suspend fun RepositoryProvider.transitionsOf(itemId: UUID): List<RoleTransition> = eventStore().transitionsOf(itemId)

private val rowJson = Json
private val rowLogger = LoggerFactory.getLogger("TransitionRows")

/**
 * The domain [VerificationStatus] a stored `events.proof_status` stands for: the proof vocabulary (`verified`,
 * `verified_stale`, `issued`, `none`, `rejected`) read back as the status the audit claim carried (`issued` and `none`
 * are both an unchecked credential), a legacy value through [VerificationStatus.fromString].
 */
private fun String.toVerificationStatus(): VerificationStatus =
    when (trim().lowercase()) {
        "verified", "verified_stale" -> VerificationStatus.VERIFIED
        "issued", "none" -> VerificationStatus.UNCHECKED
        "rejected" -> VerificationStatus.REJECTED
        else -> VerificationStatus.fromString(this)
    }

/** Rebuilds the [RoleTransition] an `item.transitioned` row describes. */
fun EventRecord.toRoleTransition(): RoleTransition {
    val data = rowJson.parseToJsonElement(data) as JsonObject

    fun str(key: String): String? = (data[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    val actor =
        principalId?.let { id ->
            ActorClaim(id = id, kind = ActorKind.fromString(principalKind ?: "external"), parent = str("actorParent"))
        }
    val verification =
        proofStatus?.let { status ->
            VerificationResult(
                status = status.toVerificationStatus(),
                verifier = str("verifier"),
                reason = str("verificationReason"),
                proofSha256 = str("actorProofSha256"),
                proofClaims =
                    (data["actorProofClaims"] as? JsonObject)?.let {
                        parseProofClaimsOrNull(
                            it.toString(),
                            rowLogger,
                            "event $id"
                        )
                    }
            )
        }
    return RoleTransition(
        id = id,
        itemId = entityId,
        fromRole = str("fromRole").orEmpty(),
        toRole = str("toRole").orEmpty(),
        fromStatusLabel = str("fromStatusLabel"),
        toStatusLabel = str("toStatusLabel"),
        trigger = str("trigger").orEmpty(),
        summary = str("summary"),
        transitionedAt = occurredAt,
        actorClaim = actor,
        verification = verification,
        consumedCredentials = (data["consumedCredentials"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    )
}
