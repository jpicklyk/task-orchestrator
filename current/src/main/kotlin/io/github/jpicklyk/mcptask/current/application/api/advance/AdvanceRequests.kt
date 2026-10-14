package io.github.jpicklyk.mcptask.current.application.api.advance

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.IsoInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** The documentation text of the request fields several `advance` ops share (one text per field name, or the registry rejects the tool). */
object AdvanceDocs {
    const val ITEM_ID = "Item id: a UUID or a dash-free hex prefix of 8 to 32 characters."
    const val TTL_SECONDS = "Claim lifetime in seconds, 1 to 86400; default 900."
}

/** The triggers a caller may request, as wire names. */
@Serializable
enum class TransitionTrigger(
    val wire: String
) {
    @SerialName("start")
    START("start"),

    @SerialName("complete")
    COMPLETE("complete"),

    @SerialName("block")
    BLOCK("block"),

    @SerialName("hold")
    HOLD("hold"),

    @SerialName("resume")
    RESUME("resume"),

    @SerialName("cancel")
    CANCEL("cancel"),

    @SerialName("reopen")
    REOPEN("reopen")
}

/** The role a `claim` selector looks in. */
@Serializable
enum class SelectRole {
    @SerialName("queue")
    QUEUE,

    @SerialName("work")
    WORK,

    @SerialName("review")
    REVIEW,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("terminal")
    TERMINAL
}

/** The priority a `claim` selector filters on. */
@Serializable
enum class SelectPriority {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW
}

/** How a `claim` selector ranks its matches. */
@Serializable
enum class SelectOrder {
    @SerialName("priority")
    PRIORITY,

    @SerialName("oldest")
    OLDEST,

    @SerialName("newest")
    NEWEST
}

/** `advance` op `transition`: move one item between phases. */
@Serializable
data class TransitionRequest(
    @Doc(AdvanceDocs.ITEM_ID) val itemId: IdRef,
    @Doc("The trigger to apply: start, complete, block, hold, resume, cancel or reopen.") val trigger: TransitionTrigger,
    @Doc("Optional human-readable summary recorded on the transition.") val summary: String? = null,
    @Doc(
        "Audit labels of the credentials this transition consumed (opaque labels, never secret values): at most 8, " +
            "each 1 to 128 characters matching ^[a-z0-9][a-z0-9\\-_./]*\$."
    ) val credentialRefs: List<String>? = null
)

/** The filters of a `claim` selector. */
@Serializable
data class ClaimSelect(
    @Doc("Role to look in; default queue.") val role: SelectRole? = null,
    @Doc("Only direct children of this item.") val parentId: IdRef? = null,
    @Doc("Match items carrying any of these tags.") val tags: List<String>? = null,
    @Doc("Only items of this priority.") val priority: SelectPriority? = null,
    @Doc("Only items of this type.") val type: String? = null,
    @Doc("Only items with a complexity of at most this value.") val complexityMax: Int? = null,
    @Doc("Only items created at or after this instant.")
    @Serializable(with = IsoInstantSerializer::class)
    val createdAfter: Instant? = null,
    @Doc("Only items created at or before this instant.")
    @Serializable(with = IsoInstantSerializer::class)
    val createdBefore: Instant? = null,
    @Doc("Only items whose role changed at or after this instant.")
    @Serializable(with = IsoInstantSerializer::class)
    val roleChangedAfter: Instant? = null,
    @Doc("Only items whose role changed at or before this instant.")
    @Serializable(with = IsoInstantSerializer::class)
    val roleChangedBefore: Instant? = null,
    @Doc("Ranking of the matches: priority (default), oldest or newest.") val orderBy: SelectOrder? = null
)

/** `advance` op `claim`: claim one item, by id or by selector. */
@Serializable
data class ClaimRequest(
    @Doc(AdvanceDocs.ITEM_ID) val itemId: IdRef? = null,
    @Doc("Find-and-claim: claim the best eligible item matching these filters.") val select: ClaimSelect? = null,
    @Doc(AdvanceDocs.TTL_SECONDS) val ttlSeconds: Int = DEFAULT_TTL_SECONDS,
    @Doc("Opaque caller reference (at most 64 characters), echoed in the result.") val claimRef: String? = null
) {
    companion object {
        const val DEFAULT_TTL_SECONDS = 900
        const val MAX_TTL_SECONDS = 86400
        const val MAX_CLAIM_REF_LENGTH = 64
    }
}

/** `advance` op `release`: release the claim the caller holds. */
@Serializable
data class ReleaseRequest(
    @Doc(AdvanceDocs.ITEM_ID) val itemId: IdRef
)

/** `advance` op `heartbeat`: extend a claim the caller holds. */
@Serializable
data class HeartbeatRequest(
    @Doc(AdvanceDocs.ITEM_ID) val itemId: IdRef,
    @Doc(AdvanceDocs.TTL_SECONDS) val ttlSeconds: Int = ClaimRequest.DEFAULT_TTL_SECONDS,
    @Doc("Absolute spend so far for the calling seat, {tokens, costUsd}; the highest reported value is kept.")
    val usage: JsonObject? = null
)
