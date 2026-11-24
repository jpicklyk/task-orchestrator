package io.github.jpicklyk.mcptask.current.knowledge

import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.ItemsReadRelated
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.ItemsReadSearch
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsArchiveOp
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsAttachOp
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsDeleteOp
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsDetachOp
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsGetSelect
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsHistorySelect
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsLinksSelect
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsListSelect
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsUnarchiveOp
import io.github.jpicklyk.mcptask.current.knowledge.internal.wire.docsUpsertOp
import io.github.jpicklyk.mcptask.current.platform.idempotency.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val DOCS_READ = "docs_read"

const val DOCS_WRITE = "docs_write"

private object JsonObjectCodec : IdempotencyCodec<JsonObject> {
    override fun encode(value: JsonObject): JsonElement = value

    override fun decode(json: JsonElement): JsonObject = json as JsonObject
}

/**
 * The knowledge slice's contribution to the tool registry: the `docs_read` and `docs_write` tools, and the `search` and
 * `related` selects of the composite `items_read` tool, so the server names no `knowledge.internal` type for tools.
 */
object KnowledgeTools {
    /** The docs_read tool: documents (plans, rules, pages) of a project root, their versions and links. */
    fun read(): SelectToolSpec =
        SelectToolSpec(
            name = DOCS_READ,
            description =
                "Read the versioned documents of a project root. select: get (one document or one stored version), " +
                    "list (metadata only), history (versions) or links (what the document links to and what links to it).",
            selects = listOf(docsGetSelect, docsListSelect, docsHistorySelect, docsLinksSelect)
        )

    /** The docs_write tool: write, archive, delete and attach documents. Every op needs the write-docs capability. */
    fun write(): OpsToolSpec<JsonObject> =
        OpsToolSpec(
            name = DOCS_WRITE,
            description =
                "Write the versioned documents (plans, rules, pages) of a project root, in batches: upsert, archive, " +
                    "unarchive, delete, attach and detach. Every op needs the write-docs capability.",
            ops = listOf(docsUpsertOp, docsArchiveOp, docsUnarchiveOp, docsDeleteOp, docsAttachOp, docsDetachOp),
            codec = JsonObjectCodec,
            element = { it }
        )

    /** `items_read` select `search`: full-text search over item, note and document content. */
    val itemsReadSearch = ItemsReadSearch.select

    /** `items_read` select `related`: items and documents related to one ref. */
    val itemsReadRelated = ItemsReadRelated.select
}
