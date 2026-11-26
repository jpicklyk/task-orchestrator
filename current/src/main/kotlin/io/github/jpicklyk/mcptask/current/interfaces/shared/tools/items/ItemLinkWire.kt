package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.item.checkItemScope
import io.github.jpicklyk.mcptask.current.item.resolveItemId
import io.github.jpicklyk.mcptask.current.knowledge.ItemDerivedFromRef
import io.github.jpicklyk.mcptask.current.knowledge.ItemDerivedFromTarget
import io.github.jpicklyk.mcptask.current.model.error.FieldViolation
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Field documentation of the `links` a created item declares. */
internal object ItemLinkDocs {
    const val LINKS = "Sources this item derives from."
    const val TO = "item:<id>#<note key>, or [kind:]slug[#heading]."
    const val RELATION = "derived_from (default)."

    /** The most links one created item may declare. */
    const val MAX_LINKS = 10
}

/** The relation of an item link on the wire; `derived_from` is the only one. */
@Serializable
enum class ItemLinkRelationWire {
    @SerialName("derived_from")
    DERIVED_FROM
}

/** One source a created item derives from: a note or a document, written as [to]. */
@Serializable
data class ItemLinkSpec(
    @Doc(ItemLinkDocs.TO) val to: String,
    @Doc(ItemLinkDocs.RELATION) val relation: ItemLinkRelationWire = ItemLinkRelationWire.DERIVED_FROM
)

/**
 * Parses [links] (at [pointer], e.g. `/links`): every fault as a violation, `<pointer>` for too many entries and
 * `<pointer>/<i>/to` for a target that is no source. The parsed refs are complete only when the violations are empty.
 */
internal fun parseItemLinks(
    links: List<ItemLinkSpec>,
    pointer: String
): Pair<List<ItemDerivedFromRef>, List<FieldViolation>> {
    val violations = ArrayList<FieldViolation>()
    if (links.size > ItemLinkDocs.MAX_LINKS) {
        violations += FieldViolation(pointer, "allows at most ${ItemLinkDocs.MAX_LINKS} entries", links.size.toString())
    }
    val refs = ArrayList<ItemDerivedFromRef>(links.size)
    for ((i, link) in links.withIndex()) {
        // derived_from is the only relation the wire decodes; a new one must say here how its target parses.
        val ref =
            when (link.relation) {
                ItemLinkRelationWire.DERIVED_FROM -> ItemDerivedFromRef.parse(link.to)
            }
        if (ref == null) {
            violations += FieldViolation("$pointer/$i/to", "must be item:<id>#<key> or [plan:|page:|rule:]<slug>[#<anchor>]", link.to)
        } else {
            refs += ref
        }
    }
    return refs to violations
}

/**
 * Resolves the note items of [refs] (at [pointer]) by the scope-aware id rule and checks the scope admits each, so a
 * restricted caller gets `forbidden`, never an existence answer. Documents pass through unresolved.
 */
internal suspend fun linkTargetsInScope(
    env: WorkflowToolServices,
    ctx: CallContext,
    refs: List<ItemDerivedFromRef>,
    pointer: String
): Outcome<List<ItemDerivedFromTarget>> {
    val out = ArrayList<ItemDerivedFromTarget>(refs.size)
    for ((i, ref) in refs.withIndex()) {
        out +=
            when (ref) {
                is ItemDerivedFromRef.Document -> ItemDerivedFromTarget.Document(ref.kind, ref.slug, ref.anchor)
                is ItemDerivedFromRef.Note -> {
                    val id =
                        when (val r = resolveItemId(env, ctx.scope, ref.itemRef, "$pointer/$i/to")) {
                            is Outcome.Err -> return r
                            is Outcome.Ok -> r.value
                        }
                    (checkItemScope(env, ctx, id) as? Outcome.Err)?.let { return it }
                    ItemDerivedFromTarget.Note(id, ref.key)
                }
            }
    }
    return Outcome.Ok(out)
}
