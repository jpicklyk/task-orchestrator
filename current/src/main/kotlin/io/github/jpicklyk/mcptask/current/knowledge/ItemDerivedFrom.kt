package io.github.jpicklyk.mcptask.current.knowledge

import io.github.jpicklyk.mcptask.current.knowledge.internal.app.link.DocumentTarget
import io.github.jpicklyk.mcptask.current.knowledge.internal.app.link.LinkParser
import io.github.jpicklyk.mcptask.current.model.knowledge.DocumentKind
import io.github.jpicklyk.mcptask.current.model.knowledge.EntityRef
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import java.util.UUID

/**
 * A source a new item derives from, as the caller wrote it: a note (`item:<id>#<key>`) or a document
 * (`[plan:|page:|rule:]<slug>[#<anchor>]`). The note's item is not resolved yet ([itemRef] as written).
 */
sealed interface ItemDerivedFromRef {
    /** The note [key] on the item [itemRef] names. */
    data class Note(
        val itemRef: IdRef,
        val key: String,
    ) : ItemDerivedFromRef

    /** The document [slug], of [kind] (null: the bare slug, PAGE > PLAN > RULE), optionally at heading [anchor]. */
    data class Document(
        val kind: DocumentKind?,
        val slug: String,
        val anchor: String?,
    ) : ItemDerivedFromRef

    companion object {
        private const val NOTE_PREFIX = "item"

        /**
         * Parses [text], or null when it is not a source. A note is `item:<id>#<key>`: the id runs to the first `#`,
         * the key is the rest, verbatim and non-blank; `item:<id>` without a key is no source. Anything else is read by
         * the `[[...]]` document grammar ([LinkParser.parseTarget]); a prefix other than plan, page or rule is no source.
         */
        fun parse(text: String): ItemDerivedFromRef? {
            val trimmed = text.trim()
            val hash = trimmed.indexOf('#')
            val head = if (hash < 0) trimmed else trimmed.substring(0, hash)
            val colon = head.indexOf(':')
            if (colon >= 0 && head.substring(0, colon).equals(NOTE_PREFIX, ignoreCase = true)) {
                if (hash < 0) return null
                val id = IdRef.parse(head.substring(colon + 1).trim()) ?: return null
                val key = trimmed.substring(hash + 1)
                return if (key.isBlank()) null else Note(id, key)
            }
            val target = LinkParser.parseTarget(trimmed) as? DocumentTarget ?: return null
            return Document(target.kind, target.slug, target.anchor)
        }
    }
}

/** An [ItemDerivedFromRef] whose note item is resolved and scope-checked by the caller. */
sealed interface ItemDerivedFromTarget {
    /** The note [key] on item [itemId]. */
    data class Note(
        val itemId: UUID,
        val key: String,
    ) : ItemDerivedFromTarget

    /** The document [slug], of [kind] (null: the bare slug), optionally at heading [anchor]. */
    data class Document(
        val kind: DocumentKind?,
        val slug: String,
        val anchor: String?,
    ) : ItemDerivedFromTarget
}

/** One resolved source of an item: the row [LinkService.recordItemDerivedFrom] stores. */
data class ResolvedItemDerivedFrom(
    val to: EntityRef,
    /** `<kind>:<slug>` for a document (kept when the document is deleted), null for a note. */
    val toRef: String?,
    val anchor: String?,
)
