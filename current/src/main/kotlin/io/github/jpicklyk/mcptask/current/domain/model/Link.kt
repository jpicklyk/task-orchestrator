package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/** The entity family at one end of a [Link]. Stored lowercase in `links.from_kind` / `links.to_kind`. */
enum class RefKind {
    ITEM,
    NOTE,
    DOCUMENT,
    ;

    /** Lowercase wire/DB form. */
    fun toDbValue(): String = name.lowercase()

    companion object {
        /** Parses the lowercase DB/wire form. Throws [IllegalArgumentException] for any other value. */
        fun fromDbValue(value: String): RefKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown link ref kind: $value")
    }
}

/** A typed reference to one entity: its family and id. */
data class EntityRef(
    val kind: RefKind,
    val id: UUID
)

/**
 * The relation a [Link] records. Only [ADOPTED_BY] is written today (document -> adopting item); the other names are
 * reserved for the knowledge links surface so their stored spelling is fixed now.
 */
enum class LinkRelation(
    private val dbValue: String
) {
    ADOPTED_BY("adopted_by"),
    DERIVED_FROM("derived_from"),
    REFERENCES("references"),
    ATTACHED("attached"),
    ;

    /** Lowercase wire/DB form stored in `links.relation`. */
    fun toDbValue(): String = dbValue

    companion object {
        /** Parses the DB/wire form. Throws [IllegalArgumentException] for any other value. */
        fun fromDbValue(value: String): LinkRelation =
            entries.firstOrNull { it.dbValue == value.lowercase() }
                ?: throw IllegalArgumentException("Unknown link relation: $value")
    }
}

/**
 * A directed, polymorphic edge between two entities of one root.
 *
 * Link ends carry no foreign key: a link is provenance and outlives the entity it names (a deleted adopter stays
 * named by its `adopted_by` link). Exactly one of [to] and [toRef] is normally set: [to] when the target resolved to
 * an entity, [toRef] (the unresolved target text) while it is dangling.
 *
 * @property rootItemId The project root the link belongs to; deleting the root deletes the link.
 * @property anchor Optional location inside the source (for example a heading), or null.
 */
data class Link(
    val id: UUID,
    val rootItemId: UUID,
    val from: EntityRef,
    val to: EntityRef?,
    val toRef: String?,
    val relation: LinkRelation,
    val anchor: String?,
    val createdAt: Instant
)
