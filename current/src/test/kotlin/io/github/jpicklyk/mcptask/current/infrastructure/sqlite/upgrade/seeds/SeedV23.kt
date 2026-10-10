package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V23 re-homes every `plan_documents` row into `documents` + `document_versions` (+ an `adopted_by` link) and drops
 * `plan_documents`. Oracle: the frozen `migration-assessment` note (sections 1, 3, 4) and `task-scope` D1-D6, D16 --
 * nothing here is read off the migration SQL.
 *
 * The dropped-table contract ([relocated]) maps each dumped 3.x row to the `documents` row the migration must produce:
 * the id is kept, `rule/<k>` becomes (rule, `k`) and every other slug becomes (plan, slug) by an exact, case-sensitive
 * prefix match, bodies/hashes/timestamps are copied verbatim, `version` is 1 and `archived_at` and `title` are NULL.
 *
 * [seed] adds five rows on top of the two baseline plan rows ("pending-plan" pending, "adopted-plan" adopted by the
 * baseline feature): a `rule/` slug, an adopted row whose adopter is NULL (adopter deleted under 3.x ON DELETE SET NULL),
 * a CRLF body, an upper-case `RULE/` slug (must stay a plan) and a row adopted by a task of its own. Every row gets a
 * distinct `modified_at` so the per-row copy into `document_versions.created_at` and `links.created_at` is observable.
 */
object SeedV23 : MigrationSeed(23) {
    private const val RULE_PREFIX = "rule/"

    private class Row(
        val name: String,
        val slug: String,
        val body: String,
        val hash: String,
        val status: String,
        val adopter: String?,
        val modifiedAt: String
    ) {
        val id: ByteArray get() = BaselineDataset.id("plan:$name")
        val hexId: String get() = hex(id)
        val kind: String get() = if (slug.startsWith(RULE_PREFIX)) "rule" else "plan"
        val documentSlug: String get() = if (slug.startsWith(RULE_PREFIX)) slug.substring(RULE_PREFIX.length) else slug
    }

    private val ruleRow =
        Row("seed23-rule", "rule/seed23-rule", "# Rule plan\nrule text\n", "hash-seed23-rule", "pending", null, BaselineDataset.TS_PLUS_2)
    private val orphanRow =
        Row("seed23-orphan", "seed23-orphan-adopted", "# Orphan plan\n", "hash-seed23-orphan", "adopted", null, BaselineDataset.TS)
    private val crlfRow =
        Row(
            "seed23-crlf",
            "seed23-crlf",
            "# Crlf plan\r\nsecond line\r\n",
            "hash-seed23-crlf",
            "pending",
            null,
            BaselineDataset.TS_PLUS_2
        )
    private val upperRow =
        Row("seed23-upper", "RULE/seed23-upper", "# Upper plan\n", "hash-seed23-upper", "pending", null, BaselineDataset.TS)
    private val taskAdoptedRow =
        Row(
            "seed23-adopted",
            "seed23-adopted",
            "# Task adopted plan\n",
            "hash-seed23-task",
            "adopted",
            "seed23-adopter",
            BaselineDataset.TS_PLUS_2
        )

    /** The two baseline plan rows, as [BaselineDataset] seeds them (ids "plan:pending"/"plan:adopted"). */
    private val baselinePending =
        Row("pending", "pending-plan", "# Pending plan\n", "hash-pending", "pending", null, BaselineDataset.TS_PLUS_1)
    private val baselineAdopted =
        Row("adopted", "adopted-plan", "# Adopted plan\n", "hash-adopted", "adopted", "feature", BaselineDataset.TS_PLUS_1)

    private val seeded = listOf(ruleRow, orphanRow, crlfRow, upperRow, taskAdoptedRow)

    /** Insertion order: the baseline rows come first, then [seeded]. */
    private val all = listOf(baselinePending, baselineAdopted) + seeded

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexLiteral(name: String): String = "X'" + hex(BaselineDataset.id(name)) + "'"

    override fun relocated(
        table: String,
        row: Map<String, Any?>
    ): Relocated? {
        if (table != "plan_documents") return null
        val slug = row["slug"] as String
        val isRule = slug.startsWith(RULE_PREFIX)
        return Relocated(
            table = "documents",
            id = row["id"] as String,
            expected =
                mapOf(
                    "root_item_id" to row["root_item_id"],
                    "kind" to if (isRule) "rule" else "plan",
                    "slug" to if (isRule) slug.substring(RULE_PREFIX.length) else slug,
                    "title" to null,
                    "body" to row["body"],
                    "content_hash" to row["content_hash"],
                    "version" to 1,
                    "archived_at" to null,
                    "created_at" to row["created_at"],
                    "modified_at" to row["modified_at"]
                )
        )
    }

    override fun seed(conn: Connection) {
        BaselineDataset.extraItem(conn, "seed23-adopter", "queue", "backlog")
        for (r in seeded) {
            BaselineDataset.insert(
                conn,
                "plan_documents",
                "id" to r.id,
                "root_item_id" to BaselineDataset.id("root"),
                "slug" to r.slug,
                "body" to r.body,
                "content_hash" to r.hash,
                "status" to r.status,
                "adopted_by_item_id" to r.adopter?.let { BaselineDataset.id(it) },
                "created_at" to BaselineDataset.TS,
                "modified_at" to r.modifiedAt
            )
        }
    }

    override fun verify(conn: Connection) {
        fun rows(sql: String): List<Map<String, Any?>> =
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) })
                        }
                    }
                }
            }

        fun count(sql: String): Int = (rows(sql).single().values.single() as Number).toInt()

        val rootHex = hex(BaselineDataset.id("root"))

        // 1. the 3.x table is gone
        assertEquals(
            0,
            count("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'plan_documents'"),
            "plan_documents must be dropped"
        )

        // 2. documents: one row per former row, id kept, kind/slug split, bytes copied verbatim
        val documents =
            rows(
                "SELECT lower(hex(id)) AS hid, doc_rowid, lower(hex(root_item_id)) AS hroot, kind, slug, title, body, " +
                    "content_hash, version, archived_at, created_at, modified_at FROM documents"
            ).associateBy { it["hid"] as String }
        assertEquals(all.size, documents.size, "every former plan_documents row is a documents row: ${documents.keys}")
        for (r in all) {
            val d = documents[r.hexId] ?: error("document ${r.name} (${r.hexId}) is missing")
            assertEquals(rootHex, d["hroot"], "${r.name}: root_item_id")
            assertEquals(r.kind, d["kind"], "${r.name}: kind")
            assertEquals(r.documentSlug, d["slug"], "${r.name}: slug")
            assertNull(d["title"], "${r.name}: title")
            assertEquals(r.body, d["body"], "${r.name}: body bytes (CRLF included) must be copied verbatim")
            assertEquals(r.hash, d["content_hash"], "${r.name}: content_hash must not be recomputed in SQL")
            assertEquals(1, (d["version"] as Number).toInt(), "${r.name}: version")
            assertNull(d["archived_at"], "${r.name}: archived_at")
            assertEquals(BaselineDataset.TS, d["created_at"], "${r.name}: created_at")
            assertEquals(r.modifiedAt, d["modified_at"], "${r.name}: modified_at")
        }
        // the case-sensitive prefix split: RULE/ stays a plan with its full slug
        assertEquals("plan", documents.getValue(upperRow.hexId)["kind"])
        assertEquals("RULE/seed23-upper", documents.getValue(upperRow.hexId)["slug"])
        assertEquals("rule", documents.getValue(ruleRow.hexId)["kind"])
        assertEquals("seed23-rule", documents.getValue(ruleRow.hexId)["slug"])
        // CRLF is preserved byte-for-byte (the hash changes only at the first 4.0 rewrite)
        assertTrue((documents.getValue(crlfRow.hexId)["body"] as String).contains("\r\n"), "CRLF must survive the migration")

        // rowid is preserved: baseline rows were rows 1 and 2, the seeded rows follow in insertion order
        val byRowid = documents.values.sortedBy { (it["doc_rowid"] as Number).toLong() }.map { it["hid"] as String }
        assertEquals(all.map { it.hexId }, byRowid, "doc_rowid must keep the 3.x insertion order")
        assertEquals(1L, (documents.getValue(baselinePending.hexId)["doc_rowid"] as Number).toLong())
        assertEquals(2L, (documents.getValue(baselineAdopted.hexId)["doc_rowid"] as Number).toLong())

        // 3. document_versions: exactly one v1 row per document, a copy of the migrated state
        val versions =
            rows("SELECT lower(hex(document_id)) AS hdoc, version, body, content_hash, actor_id, created_at FROM document_versions")
        assertEquals(all.size, versions.size, "one document_versions row per document: $versions")
        for (r in all) {
            val v = versions.single { it["hdoc"] == r.hexId }
            assertEquals(1, (v["version"] as Number).toInt(), "${r.name}: version row number")
            assertEquals(r.body, v["body"], "${r.name}: version body")
            assertEquals(r.hash, v["content_hash"], "${r.name}: version hash")
            assertNull(v["actor_id"], "${r.name}: no actor is known for a migrated row")
            assertEquals(r.modifiedAt, v["created_at"], "${r.name}: version created_at is the row's modified_at")
        }

        // 4. links: one adopted_by link per row that has an adopter, none for the NULL-adopter row
        val links =
            rows(
                "SELECT lower(hex(root_item_id)) AS hroot, from_kind, lower(hex(from_id)) AS hfrom, to_kind, " +
                    "lower(hex(to_id)) AS hto, to_ref, relation, anchor, created_at FROM links"
            )
        val withAdopter = all.filter { it.adopter != null }
        assertEquals(withAdopter.size, links.size, "links == rows with a non-null adopter: $links")
        assertEquals(
            withAdopter.size,
            count("SELECT count(*) FROM links WHERE relation = 'adopted_by'"),
            "adopted count must equal adopted_by link count"
        )
        for (r in withAdopter) {
            val l = links.single { it["hfrom"] == r.hexId }
            assertEquals("document", l["from_kind"], "${r.name}: from_kind")
            assertEquals("item", l["to_kind"], "${r.name}: to_kind")
            assertEquals(hex(BaselineDataset.id(r.adopter!!)), l["hto"], "${r.name}: link target is the row's own adopter")
            assertEquals("adopted_by", l["relation"], "${r.name}: relation")
            assertNull(l["to_ref"], "${r.name}: to_ref")
            assertNull(l["anchor"], "${r.name}: anchor")
            assertEquals(rootHex, l["hroot"], "${r.name}: link root")
            assertEquals(r.modifiedAt, l["created_at"], "${r.name}: link created_at is the row's modified_at")
        }
        assertTrue(
            links.none { it["hfrom"] == orphanRow.hexId },
            "a row adopted by a since-deleted item (NULL adopter) migrates unadopted: no link"
        )

        // 5. both document FTS indexes cover every row, are keyed by doc_rowid and find the seeded token
        for (fts in listOf("documents_fts_trigram", "documents_fts_text")) {
            assertEquals(all.size, count("SELECT count(*) FROM ${fts}_docsize"), "$fts must index every migrated document")
            assertTrue(
                count("SELECT count(*) FROM $fts WHERE $fts MATCH '${BaselineDataset.DOCUMENT_FTS_TOKEN}'") >= 1,
                "$fts must find '${BaselineDataset.DOCUMENT_FTS_TOKEN}'"
            )
            val hit = rows("SELECT rowid AS r FROM $fts WHERE $fts MATCH 'crlf'").map { (it["r"] as Number).toLong() }
            assertEquals(
                listOf((documents.getValue(crlfRow.hexId)["doc_rowid"] as Number).toLong()),
                hit,
                "$fts: the CRLF row is found under its doc_rowid"
            )
        }

        // 6. indexes exist
        val indexes = rows("SELECT name FROM sqlite_master WHERE type = 'index'").map { it["name"] as String }.toSet()
        for (name in listOf(
            "idx_documents_root_kind_slug",
            "idx_documents_root",
            "idx_document_versions_doc_version",
            "idx_links_from",
            "idx_links_to",
            "idx_links_unique"
        )) {
            assertTrue(name in indexes, "$name missing: $indexes")
        }

        // 7. uniqueness: (root, kind, slug) is unique, but a plan and a rule may share a slug
        fun insertDocument(
            kind: String,
            slug: String
        ) = conn.createStatement().use {
            it.executeUpdate(
                "INSERT INTO documents (root_item_id, kind, slug, body, content_hash, created_at, modified_at) VALUES " +
                    "(${hexLiteral("root")}, '$kind', '$slug', 'b', 'h', '${BaselineDataset.TS}', '${BaselineDataset.TS}')"
            )
        }
        assertFailsWith<SQLException>("a second (root, kind, slug) document must violate idx_documents_root_kind_slug") {
            insertDocument("plan", "seed23-crlf")
        }
        insertDocument("rule", "seed23-crlf")
        assertEquals(
            1,
            count("SELECT count(*) FROM documents WHERE kind = 'rule' AND slug = 'seed23-crlf'"),
            "the same slug under another kind is a different document"
        )

        // 8. the partial unique index rejects a duplicate adopted_by link
        assertFailsWith<SQLException>("a duplicate (from, to, relation) link must violate idx_links_unique") {
            conn.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO links (root_item_id, from_kind, from_id, to_kind, to_id, relation, created_at) VALUES " +
                        "(${hexLiteral("root")}, 'document', ${hexLiteral("plan:adopted")}, 'item', ${hexLiteral("feature")}, " +
                        "'adopted_by', '${BaselineDataset.TS}')"
                )
            }
        }
    }
}
