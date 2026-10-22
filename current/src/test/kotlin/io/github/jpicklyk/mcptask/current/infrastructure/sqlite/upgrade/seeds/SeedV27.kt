package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.seeds

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.MigrationSeed
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V27 adds `item_traits` (one row per item and position, a unique name per item, cascade on item delete, an index on
 * `name`); the `traits-from-properties` data step then moves the `traits` key of every 3.x `properties` bag into it.
 *
 * Oracle: migration-assessment sections 1, 3 and 4 (frozen) and task-scope B9: the DDL (columns item_id BLOB, position
 * INTEGER, name TEXT, all NOT NULL, primary key (item_id, position), UNIQUE (item_id, name), CHECK position >= 0, CHECK
 * length(name) BETWEEN 1 AND 100, ON DELETE CASCADE, plain index `idx_item_traits_name`); the step rewrites a row only when
 * `properties` is a JSON object whose `traits` is an array of strings or a comma-separated string with every trimmed name
 * at most 100 characters, strips the key (NULL when nothing remains) and leaves every other row byte-identical.
 *
 * The migration itself rewrites no row, so the bare-chain contract ([expected]) is empty: `properties` keeps its `traits`
 * key until the step runs. The after-data-steps contract ([expectedAfterDataSteps]) is derived here by an independent
 * reading of the step's rule ([rewrite]); it applies to every dumped `work_items` row of a check that ran the production
 * data steps, so the baseline rows (`{"traits":["needs-review","trait-<name>"]}` below V27) must end with `properties`
 * NULL there. The harness runs the production data steps after the migration and before [verify]. Remainders are
 * single-key compact objects so the exact rewritten text is unambiguous.
 *
 * [seed] adds twelve work items covering every form of the `traits` value; [verify] checks the table shape, the indexes,
 * the rows, the untouched rows, `rows_affected`, the other tables' counts and CHECK / UNIQUE / FK probes, then removes its
 * probe rows. The migration alone (without the step) is checked on a scratch copy in [migrationOnlyChecks].
 */
object SeedV27 : MigrationSeed(27) {
    private const val STEP = "traits-from-properties"

    /** The expected effect of the step on one `properties` text: the names it yields and the remaining object text. */
    private class Rewrite(
        val names: List<String>,
        val remaining: String?
    )

    /** Null when the step must leave [properties] untouched. Independent of the implementation: derived from the rule above. */
    private fun rewrite(properties: String?): Rewrite? {
        if (properties == null || properties.isBlank()) return null
        val parsed = runCatching { Json.parseToJsonElement(properties) }.getOrNull() as? JsonObject ?: return null
        val traits = parsed["traits"] ?: return null
        val raw: List<String> =
            when {
                traits is JsonArray ->
                    traits.map { element ->
                        val primitive = element as? JsonPrimitive ?: return null
                        if (!primitive.isString) return null
                        primitive.content
                    }
                traits is JsonPrimitive && traits.isString -> traits.content.split(',')
                else -> return null
            }
        val names = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.any { it.length > MAX_NAME }) return null
        val rest = parsed.filterKeys { it != "traits" }
        return Rewrite(names, if (rest.isEmpty()) null else JsonObject(rest).toString())
    }

    private const val MAX_NAME = 100

    private class Seeded(
        val name: String,
        val properties: String?
    ) {
        val id: ByteArray get() = BaselineDataset.id(name)
    }

    private val seeded =
        listOf(
            Seeded("s27-array", """{"traits":["alpha","beta"]}"""),
            Seeded("s27-comma", """{"traits":" gamma ,, delta , gamma "}"""),
            Seeded("s27-dups", """{"traits":["p","q","p"," q "]}"""),
            Seeded("s27-other-keys", """{"custom":1,"traits":["a"]}"""),
            Seeded("s27-empty-array", """{"traits":[]}"""),
            Seeded("s27-no-key", """{"custom":3}"""),
            Seeded("s27-nonjson", "plain text, not json"),
            Seeded("s27-number", """{"traits":7}"""),
            Seeded("s27-array-number", """{"traits":["a",5]}"""),
            Seeded("s27-long-name", """{"traits":["${"n".repeat(101)}"]}"""),
            Seeded("s27-null", null),
            Seeded("s27-blank", "  ")
        )

    private val baselineNames = BaselineDataset.ITEM_NAMES

    /** Row counts of every user table at the pre-migration version, captured by [seed] and compared by [verify]. */
    @Volatile
    private var before: Map<String, Int>? = null

    private fun insertItem(
        conn: Connection,
        name: String,
        properties: String?,
        parent: String? = "feature",
        depth: Int = 2
    ) = BaselineDataset.insert(
        conn,
        "work_items",
        "id" to BaselineDataset.id(name),
        "parent_id" to parent?.let { BaselineDataset.id(it) },
        "root_id" to BaselineDataset.id(if (parent == null) name else "root"),
        "title" to "Seeded $name",
        "summary" to "",
        "role" to "queue",
        "priority" to "medium",
        "complexity" to 5,
        "requires_verification" to 0,
        "depth" to depth,
        // Typed like every baseline row: a NULL type would be filled by the production type-backfill step.
        "type" to "feature-task",
        "properties" to properties,
        "created_at" to BaselineDataset.TS,
        "modified_at" to BaselineDataset.TS_PLUS_1,
        "role_changed_at" to BaselineDataset.TS_PLUS_2,
        "version" to 1,
        // Pinned like every baseline row: an unpinned row would be pinned by the production pin-backfill step.
        *BaselineDataset.pinColumns()
    )

    override fun seed(conn: Connection) {
        for (s in seeded) insertItem(conn, s.name, s.properties)
        before = UpgradeHarness.userTables(conn).associateWith { count(conn, it) }
    }

    /** The migration alone changes no existing row: `properties` keeps `traits` until the data step runs. */
    override fun expected(
        table: String,
        row: Map<String, Any?>
    ): Map<String, Any?> = emptyMap()

    override fun expectedAfterDataSteps(
        table: String,
        row: Map<String, Any?>
    ): Map<String, Any?> {
        if (table != "work_items" || "properties" !in row) return emptyMap()
        val change = rewrite(row["properties"] as? String) ?: return emptyMap()
        return mapOf("properties" to change.remaining)
    }

    // ---------------------------------------------------------------------------------------------

    override fun verify(conn: Connection) {
        verifyShape(conn)
        verifyIndexes(conn)
        verifyRows(conn)
        verifyOtherTablesUnchanged(conn)
        verifyChecks(conn)
        migrationOnlyChecks()
    }

    private fun verifyShape(conn: Connection) {
        val columns =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(item_traits)").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getInt("pk"))
                            )
                        }
                    }
                }
            }
        assertEquals(
            listOf(
                listOf("item_id", "BLOB", 1, 1),
                listOf("position", "INTEGER", 1, 2),
                listOf("name", "TEXT", 1, 0)
            ),
            columns,
            "item_traits columns (name, type, notnull, pk position)"
        )
    }

    private fun verifyIndexes(conn: Connection) {
        class Index(
            val name: String,
            val unique: Boolean,
            val columns: List<String>
        )

        val indexes =
            conn
                .createStatement()
                .use { st ->
                    st.executeQuery("PRAGMA index_list(item_traits)").use { rs ->
                        buildList { while (rs.next()) add(rs.getString("name") to (rs.getInt("unique") == 1)) }
                    }
                }.map { (name, unique) ->
                    val cols =
                        conn.createStatement().use { st ->
                            st.executeQuery("PRAGMA index_info('$name')").use { rs ->
                                buildList { while (rs.next()) add(rs.getString("name")) }
                            }
                        }
                    Index(name, unique, cols)
                }
        val named = indexes.singleOrNull { it.name == "idx_item_traits_name" }
        assertTrue(named != null, "idx_item_traits_name must exist: ${indexes.map { it.name }}")
        assertEquals(false to listOf("name"), named.unique to named.columns, "idx_item_traits_name is a plain index on (name)")
        val uniqueColumns = indexes.filter { it.unique }.map { it.columns }
        assertTrue(listOf("item_id", "position") in uniqueColumns, "a unique index on (item_id, position): $uniqueColumns")
        assertTrue(listOf("item_id", "name") in uniqueColumns, "a unique index on (item_id, name): $uniqueColumns")
    }

    private fun traitRows(
        conn: Connection,
        item: ByteArray
    ): List<Pair<Int, String>> =
        conn.prepareStatement("SELECT position, name FROM item_traits WHERE item_id = ? ORDER BY position").use { ps ->
            ps.setBytes(1, item)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getInt(1) to rs.getString(2)) } }
        }

    private fun propertiesOf(
        conn: Connection,
        item: ByteArray
    ): String? =
        conn.prepareStatement("SELECT properties FROM work_items WHERE id = ?").use { ps ->
            ps.setBytes(1, item)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next(), "work item row exists")
                rs.getString(1)
            }
        }

    private fun verifyRows(conn: Connection) {
        var rewritten = 0
        // baseline rows: `{"traits":["needs-review","trait-<name>"]}` below V27
        for (name in baselineNames) {
            assertEquals(
                listOf(0 to "needs-review", 1 to "trait-$name"),
                traitRows(conn, BaselineDataset.id(name)),
                "baseline $name traits"
            )
            assertEquals(null, propertiesOf(conn, BaselineDataset.id(name)), "baseline $name properties are NULL once emptied")
            rewritten++
        }
        for (s in seeded) {
            val change = rewrite(s.properties)
            if (change == null) {
                assertEquals(s.properties, propertiesOf(conn, s.id), "${s.name}: properties byte-identical")
                assertEquals(emptyList(), traitRows(conn, s.id), "${s.name}: no item_traits row")
            } else {
                assertEquals(change.names.mapIndexed { i, n -> i to n }, traitRows(conn, s.id), "${s.name}: item_traits rows in order")
                assertEquals(change.remaining, propertiesOf(conn, s.id), "${s.name}: properties without the traits key")
                rewritten++
            }
        }
        val recorded =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT rows_affected FROM data_steps WHERE name = '$STEP'").use { rs ->
                    assertTrue(rs.next(), "the $STEP ledger row exists")
                    rs.getInt(1)
                }
            }
        assertEquals(rewritten, recorded, "rows_affected is the number of rewritten rows")
    }

    private fun verifyOtherTablesUnchanged(conn: Connection) {
        val pre = before ?: error("SeedV27.seed did not run before the migration")
        for ((table, rows) in SeedV29.afterRelocation(pre)) {
            if (table == "data_steps") continue
            assertEquals(rows, count(conn, table), "$table row count changed across V27 and the step")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CHECK, UNIQUE, NOT NULL and FK probes (rows are removed again)
    // ---------------------------------------------------------------------------------------------

    private fun insertTrait(
        conn: Connection,
        item: ByteArray?,
        position: Int?,
        name: String?
    ) = conn.prepareStatement("INSERT INTO item_traits (item_id, position, name) VALUES (?, ?, ?)").use { ps ->
        if (item == null) ps.setNull(1, Types.BLOB) else ps.setBytes(1, item)
        if (position == null) ps.setNull(2, Types.INTEGER) else ps.setInt(2, position)
        if (name == null) ps.setNull(3, Types.VARCHAR) else ps.setString(3, name)
        ps.executeUpdate()
    }

    private fun verifyChecks(conn: Connection) {
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        try {
            insertItem(conn, "s27-probe-a", null)
            insertItem(conn, "s27-probe-b", null)
            val a = BaselineDataset.id("s27-probe-a")
            val b = BaselineDataset.id("s27-probe-b")
            val baseTraits = count(conn, "item_traits")

            assertFailsWith<SQLException>("a negative position must be rejected") { insertTrait(conn, a, -1, "t") }
            assertFailsWith<SQLException>("an empty name must be rejected") { insertTrait(conn, a, 0, "") }
            assertFailsWith<SQLException>("a 101-character name must be rejected") { insertTrait(conn, a, 0, "n".repeat(101)) }
            assertFailsWith<SQLException>("a 101-character name of 2-byte characters must be rejected (length counts characters)") {
                insertTrait(conn, a, 0, "\u00e9".repeat(101))
            }
            assertFailsWith<SQLException>("a NULL item_id must be rejected") { insertTrait(conn, null, 0, "t") }
            assertFailsWith<SQLException>("a NULL position must be rejected") { insertTrait(conn, a, null, "t") }
            assertFailsWith<SQLException>("a NULL name must be rejected") { insertTrait(conn, a, 0, null) }
            assertFailsWith<SQLException>("an unknown item_id must be rejected by the foreign key") {
                insertTrait(conn, BaselineDataset.id("s27-no-such-item"), 0, "t")
            }
            assertEquals(baseTraits, count(conn, "item_traits"), "no rejected insert left a row")

            // accepted at every boundary
            insertTrait(conn, a, 0, "n".repeat(100))
            insertTrait(conn, a, 1, "\u00e9".repeat(100))
            insertTrait(conn, a, 2, "x")
            insertTrait(conn, a, 3, "X") // the name uniqueness is case-sensitive
            insertTrait(conn, b, 0, "x") // the same name on another item is fine
            assertFailsWith<SQLException>("a duplicate (item, position) must be rejected") { insertTrait(conn, a, 2, "other") }
            assertFailsWith<SQLException>("a duplicate (item, name) must be rejected") { insertTrait(conn, a, 9, "x") }
            assertEquals(baseTraits + 5, count(conn, "item_traits"))

            // deleting the item removes its trait rows and only its own
            conn.prepareStatement("DELETE FROM work_items WHERE id = ?").use {
                it.setBytes(1, a)
                it.executeUpdate()
            }
            assertEquals(emptyList(), traitRows(conn, a), "ON DELETE CASCADE removes the item's trait rows")
            assertEquals(listOf(0 to "x"), traitRows(conn, b), "another item's rows survive")
            conn.prepareStatement("DELETE FROM work_items WHERE id = ?").use {
                it.setBytes(1, b)
                it.executeUpdate()
            }
            assertEquals(baseTraits, count(conn, "item_traits"), "the probe rows are removed again")
        } finally {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The migration's own output, before any data step
    // ---------------------------------------------------------------------------------------------

    private fun migrationOnlyChecks() {
        val dir = Files.createTempDirectory("seed-v27-only-")
        try {
            val url = UpgradeHarness.copyAt(26, File(dir.toFile(), "only.db"))
            java.sql.DriverManager.getConnection(url).use { conn ->
                insertItem(conn, "mo-root", """{"traits":["a","b"]}""", parent = null, depth = 0)
            }
            UpgradeHarness.migrate(url)
            java.sql.DriverManager.getConnection(url).use { conn ->
                assertEquals(0, count(conn, "item_traits"), "the migration itself backfills nothing (the data step does)")
                assertEquals(
                    """{"traits":["a","b"]}""",
                    propertiesOf(conn, BaselineDataset.id("mo-root")),
                    "the migration itself leaves properties untouched"
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun count(
        conn: Connection,
        table: String
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $table").use {
                it.next()
                it.getInt(1)
            }
        }
}
