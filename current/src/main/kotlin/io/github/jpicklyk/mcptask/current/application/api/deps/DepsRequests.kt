package io.github.jpicklyk.mcptask.current.application.api.deps

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire form of a dependency type: lowercase. `is_blocked_by` is not on the 4.0 wire; use `direction` to read it. */
@Serializable
enum class DepType(
    val domain: DependencyType
) {
    @SerialName("blocks")
    BLOCKS(DependencyType.BLOCKS),

    @SerialName("relates_to")
    RELATES_TO(DependencyType.RELATES_TO);

    /** The wire name. */
    val wire: String get() = name.lowercase()

    companion object {
        /** The wire form of a stored type; null for a type with no wire form (a legacy IS_BLOCKED_BY row, never stored). */
        fun of(type: DependencyType): DepType? = entries.firstOrNull { it.domain == type }
    }
}

/** Wire form of the role a blocker must reach before it unblocks its blocked item. */
@Serializable
enum class UnblockAt {
    @SerialName("queue")
    QUEUE,

    @SerialName("work")
    WORK,

    @SerialName("review")
    REVIEW,

    @SerialName("terminal")
    TERMINAL;

    /** The wire name, also the stored `unblockAt` string. */
    val wire: String get() = name.lowercase()
}

/** Which edges of an item `deps_read list` returns. */
@Serializable
enum class DepDirection {
    @SerialName("incoming")
    INCOMING,

    @SerialName("outgoing")
    OUTGOING,

    @SerialName("all")
    ALL
}

private const val ITEM_DOC = "Item id: a UUID or a hex prefix of 8 to 32 characters."
private const val LIMIT_DOC = "Page size, 1 to 200 (default 50)."
private const val CURSOR_DOC = "The page.next value of the previous page."

/** `deps_read list`: the edges touching one item. */
@Serializable
data class DepsListRequest(
    @Doc(ITEM_DOC) val item: IdRef,
    @Doc("incoming = edges whose to is item (what blocks it), outgoing = edges whose from is item. Default all.")
    val direction: DepDirection? = null,
    @Doc("Only edges of this type. Default both.") val type: DepType? = null,
    @Doc(LIMIT_DOC) val limit: Int? = null,
    @Doc(CURSOR_DOC) val cursor: String? = null
)

/** `deps_read graph`: the blocking graph around one item. */
@Serializable
data class DepsGraphRequest(
    @Doc(ITEM_DOC) val item: IdRef,
    @Doc(LIMIT_DOC) val limit: Int? = null,
    @Doc(CURSOR_DOC) val cursor: String? = null
)

private const val FROM_DOC = "Source item of the edge (the blocker for blocks): a UUID or a hex prefix of 8 to 32 characters."
private const val TO_DOC = "Target item of the edge (the blocked item for blocks): a UUID or a hex prefix of 8 to 32 characters."
private const val TYPE_DOC = "Edge type: blocks or relates_to. Create applies blocks when absent; delete matches either type when absent."
private const val UNBLOCK_DOC =
    "Role the blocker must reach to unblock the target: queue, work, review or terminal (default terminal). Not allowed with relates_to."

/** `deps_write create`: one edge. */
@Serializable
data class DepsCreateRequest(
    @Doc(FROM_DOC) val from: IdRef,
    @Doc(TO_DOC) val to: IdRef,
    @Doc(TYPE_DOC) val type: DepType? = null,
    @Doc(UNBLOCK_DOC) val unblockAt: UnblockAt? = null
)

/** `deps_write delete`: exactly one of `id`, `from`+`to`, `item`. */
@Serializable
data class DepsDeleteRequest(
    @Doc("Full dependency UUID (not a prefix).") val id: String? = null,
    @Doc(FROM_DOC) val from: IdRef? = null,
    @Doc(TO_DOC) val to: IdRef? = null,
    @Doc(TYPE_DOC) val type: DepType? = null,
    @Doc("Delete every edge touching this item (either end). Item id: a UUID or a hex prefix of 8 to 32 characters.")
    val item: IdRef? = null
)

/** `deps_write linear`: a chain, each item blocking the next. */
@Serializable
data class DepsLinearRequest(
    @Doc("Items in chain order, at least 2: each one blocks the next.") val ids: List<IdRef>,
    @Doc(TYPE_DOC) val type: DepType? = null,
    @Doc(UNBLOCK_DOC) val unblockAt: UnblockAt? = null
)

/** `deps_write fan_out`: one source to many targets. */
@Serializable
data class DepsFanOutRequest(
    @Doc(FROM_DOC) val from: IdRef,
    @Doc("Target items, at least 1: from blocks each of them.") val toIds: List<IdRef>,
    @Doc(TYPE_DOC) val type: DepType? = null,
    @Doc(UNBLOCK_DOC) val unblockAt: UnblockAt? = null
)

/** `deps_write fan_in`: many sources to one target. */
@Serializable
data class DepsFanInRequest(
    @Doc("Source items, at least 1: each of them blocks to.") val fromIds: List<IdRef>,
    @Doc(TO_DOC) val to: IdRef,
    @Doc(TYPE_DOC) val type: DepType? = null,
    @Doc(UNBLOCK_DOC) val unblockAt: UnblockAt? = null
)
