package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.docs

import io.github.jpicklyk.mcptask.current.application.api.docs.DocGetResult
import io.github.jpicklyk.mcptask.current.application.api.docs.DocHistoryEntry
import io.github.jpicklyk.mcptask.current.application.api.docs.DocLinkEntry
import io.github.jpicklyk.mcptask.current.application.api.docs.DocListEntry
import io.github.jpicklyk.mcptask.current.application.api.docs.DocView
import io.github.jpicklyk.mcptask.current.application.api.docs.DocWriteOutcome
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The wire shapes of `docs_read` and `docs_write` (nulls omitted, keys in the order the design lists them). */
internal object DocJson {
    fun view(doc: DocView): JsonObject =
        buildJsonObject {
            put("id", doc.id.toString())
            put("rootId", doc.rootId.toString())
            put("kind", doc.kind.toDbValue())
            put("slug", doc.slug)
            doc.title?.let { put("title", it) }
            put("body", doc.body)
            put("contentHash", doc.contentHash)
            put("version", doc.version)
            doc.archivedAt?.let { put("archivedAt", it.toString()) }
            doc.adoptedBy?.let { put("adoptedBy", it.toString()) }
            put("createdAt", doc.createdAt.toString())
            put("modifiedAt", doc.modifiedAt.toString())
        }

    fun getResult(result: DocGetResult): JsonElement =
        when (result) {
            is DocGetResult.Found -> view(result.doc)
            is DocGetResult.Version ->
                buildJsonObject {
                    put("kind", result.version.kind.toDbValue())
                    put("slug", result.version.slug)
                    put("version", result.version.version)
                    put("body", result.version.body)
                    put("contentHash", result.version.contentHash)
                    put("createdAt", result.version.createdAt.toString())
                    result.version.actor?.let { put("actor", it) }
                }
            is DocGetResult.Unchanged ->
                buildJsonObject {
                    put("unchanged", true)
                    put("version", result.version)
                }
        }

    fun entry(entry: DocListEntry): JsonObject =
        buildJsonObject {
            put("id", entry.id.toString())
            put("kind", entry.kind.toDbValue())
            put("slug", entry.slug)
            entry.title?.let { put("title", it) }
            put("contentHash", entry.contentHash)
            put("version", entry.version)
            entry.archivedAt?.let { put("archivedAt", it.toString()) }
            entry.adoptedBy?.let { put("adoptedBy", it.toString()) }
            put("createdAt", entry.createdAt.toString())
            put("modifiedAt", entry.modifiedAt.toString())
        }

    fun history(entry: DocHistoryEntry): JsonObject =
        buildJsonObject {
            put("version", entry.version)
            put("contentHash", entry.contentHash)
            put("bodyLength", entry.bodyLength)
            put("createdAt", entry.createdAt.toString())
            entry.actor?.let { put("actor", it) }
        }

    private fun ref(ref: EntityRef): JsonObject =
        buildJsonObject {
            put("kind", ref.kind.toDbValue())
            put("id", ref.id.toString())
        }

    fun link(entry: DocLinkEntry): JsonObject =
        buildJsonObject {
            put("id", entry.id.toString())
            put("relation", entry.relation.toDbValue())
            put("from", ref(entry.from))
            entry.to?.let { put("to", ref(it)) }
            entry.toRef?.let { put("toRef", it) }
            entry.anchor?.let { put("anchor", it) }
        }

    /** One `results[i]` of `docs_write` (the encoder adds `ok`). */
    fun write(outcome: DocWriteOutcome): JsonObject =
        when (outcome) {
            is DocWriteOutcome.Upserted ->
                buildJsonObject {
                    put("id", outcome.id.toString())
                    put("kind", outcome.kind.toDbValue())
                    put("slug", outcome.slug)
                    put("version", outcome.version)
                    put("contentHash", outcome.contentHash)
                    put("created", outcome.created)
                }
            is DocWriteOutcome.Archived ->
                buildJsonObject {
                    put("id", outcome.id.toString())
                    put("kind", outcome.kind.toDbValue())
                    put("slug", outcome.slug)
                    put("version", outcome.version)
                    put("archived", outcome.archived)
                }
            is DocWriteOutcome.Deleted ->
                buildJsonObject {
                    put("id", outcome.id.toString())
                    put("kind", outcome.kind.toDbValue())
                    put("slug", outcome.slug)
                    put("deleted", true)
                }
            is DocWriteOutcome.Attached ->
                buildJsonObject {
                    put("linkId", outcome.linkId.toString())
                    put("documentId", outcome.documentId.toString())
                    put("itemId", outcome.itemId.toString())
                }
            is DocWriteOutcome.Detached -> buildJsonObject { put("detached", outcome.detached) }
        }
}
