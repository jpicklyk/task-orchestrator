package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.StartupIntegrity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for the `traits-from-properties` data step (item 22088359, Q15): scenarios S8 and S12 of the frozen
 * test-plan plus probes. The input is a COPY of the checked-in golden V17 database ([GoldenV17]) migrated to the latest
 * schema, to which the tests add rows with raw SQL; the step is exercised through the production composition
 * ([UpgradeHarness.runProductionDataStepsWithPlan]), so its registration is part of what is proved.
 *
 * Oracles (frozen task-scope B9 and AC7, migration-assessment section 3, none read from the implementation): the step is
 * named `traits-from-properties`, phase ITEM_BACKFILL, kind ONCE, and runs after `type-backfill`. For every `work_items`
 * row whose `properties` is a JSON object with a `traits` key holding an array of strings or a comma-separated string, the
 * names (trimmed, empty dropped, first-wins dedupe) become `item_traits` rows (names the item already has are skipped, the
 * rest are appended after the existing maximum position) and `properties` is rewritten without the `traits` key (NULL when
 * nothing remains). Rows whose `properties` is not a JSON object, whose `traits` is neither an array of strings nor a
 * string, or that hold a name longer than 100 characters are left byte-identical and are not counted. An object without a
 * `traits` key is untouched. The step writes `properties` and `item_traits` only (no `version` or `modified_at` bump, no
 * event rows) and records the number of rewritten rows in `data_steps.rows_affected`; a second boot skips it. The golden
 * V17 rows carry `{"traits":["needs-review","trait-<name>"]}` (see [BaselineDataset]). `StartupIntegrity.reportTraitsResidue`
 * is WARN-only and counts rows whose `properties` still holds a `traits` key.
 *
 * Fixture invariants (work_items rows): each probe row is a depth-1 child of the golden root with root_id = root and a
 * non-null `type`, so the neighbouring type-backfill step never rewrites it; timestamps are the baseline literals.
 */
class TraitsFromPropertiesStepTest {
    @TempDir
    lateinit var dir: File

    private val stepName = "traits-from-properties"
    private val typeStepName = "type-backfill"

    private fun nameOf(length: Int): String = "n".repeat(length)

    /** What the step must do with one probe row. */
    private sealed interface Outcome {
        /** [remaining] is the expected properties object (null = the column becomes NULL). */
        data class Migrated(
            val names: List<String>,
            val remaining: String?
        ) : Outcome

        data object Untouched : Outcome
    }

    private class Probe(
        val name: String,
        val properties: String?,
        val outcome: Outcome
    )

    private fun migrated(
        name: String,
        properties: String,
        vararg names: String,
        remaining: String? = null
    ) = Probe(name, properties, Outcome.Migrated(names.toList(), remaining))

    private fun untouched(
        name: String,
        properties: String?
    ) = Probe(name, properties, Outcome.Untouched)

    private val matrix: List<Probe> =
        listOf(
            migrated("f-array", """{"traits":["a","b"]}""", "a", "b"),
            migrated("f-string", """{"traits":"a, b"}""", "a", "b"),
            migrated("f-string-dirty", """{"traits":" x ,, y , x "}""", "x", "y"),
            migrated("f-array-dups", """{"traits":["p","q","p"," q "]}""", "p", "q"),
            migrated(
                "f-other-keys",
                """{"custom":{"k":[1,2]},"traits":["a"]}""",
                "a",
                remaining = """{"custom":{"k":[1,2]}}"""
            ),
            migrated("f-other-keys-after", """{"traits":["a","b"],"custom":1}""", "a", "b", remaining = """{"custom":1}"""),
            migrated("f-empty-array", """{"traits":[]}"""),
            migrated("f-empty-string", """{"traits":""}"""),
            migrated("f-blank-elements", """{"traits":"  ,  "}"""),
            migrated("f-case", """{"traits":["A","a"]}""", "A", "a"),
            migrated("f-100", """{"traits":["${nameOf(100)}"]}""", nameOf(100)),
            migrated("f-unicode", "{\"traits\":[\"gr\\u00f6\\u00dfe-\\u00e9\"]}", "gr\u00f6\u00dfe-\u00e9"),
            untouched("f-no-key", """{"custom":2}"""),
            untouched("f-nested-key", """{"custom":{"traits":["a"]}}"""),
            untouched("f-nonjson", "not json at all"),
            untouched("f-json-array", """["traits"]"""),
            untouched("f-number", """{"traits":5}"""),
            untouched("f-null-traits", """{"traits":null}"""),
            untouched("f-object-traits", """{"traits":{"a":1}}"""),
            untouched("f-array-number", """{"traits":["a",5]}"""),
            untouched("f-long-name", """{"traits":["${nameOf(101)}"]}"""),
            untouched("f-long-name-string", """{"traits":"ok,${nameOf(101)}"}"""),
            untouched("f-null-props", null),
            untouched("f-blank-props", "   ")
        )

    private fun insertRow(
        conn: Connection,
        name: String,
        properties: String?
    ) = BaselineDataset.insert(
        conn,
        "work_items",
        "id" to BaselineDataset.id(name),
        "parent_id" to BaselineDataset.id("root"),
        "root_id" to BaselineDataset.id("root"),
        "title" to "Probe $name",
        "summary" to "",
        "role" to "queue",
        "priority" to "medium",
        "complexity" to 5,
        "requires_verification" to 0,
        "depth" to 1,
        "type" to "feature-task",
        "properties" to properties,
        "created_at" to BaselineDataset.TS,
        "modified_at" to BaselineDataset.TS_PLUS_1,
        "role_changed_at" to BaselineDataset.TS_PLUS_2,
        "version" to 1
    )

    /** A golden copy migrated to the latest schema (no data step yet), with [probes] inserted and [existing] item_traits rows. */
    private fun prepare(
        name: String,
        probes: List<Probe>,
        existing: List<Triple<String, Int, String>> = emptyList()
    ): String {
        val golden = GoldenV17.copyTo(File(dir, "$name.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        UpgradeHarness.migrate(url)
        DriverManager.getConnection(url).use { conn ->
            check(BaselineDataset.columnsOf(conn, "item_traits").isNotEmpty()) { "the latest schema has no item_traits table" }
            probes.forEach { insertRow(conn, it.name, it.properties) }
            existing.forEach { (item, position, trait) ->
                BaselineDataset.insert(
                    conn,
                    "item_traits",
                    "item_id" to BaselineDataset.id(item),
                    "position" to position,
                    "name" to trait
                )
            }
        }
        return url
    }

    private fun boot(
        url: String,
        run: String
    ) = File(dir, "run-$run").let {
        it.mkdirs()
        UpgradeHarness.runProductionDataStepsWithPlan(url, it)
    }

    private fun <T> query(
        url: String,
        sql: String,
        vararg args: Any?,
        map: (java.sql.ResultSet) -> T
    ): List<T> =
        DriverManager.getConnection(url).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a ->
                    when (a) {
                        is ByteArray -> ps.setBytes(i + 1, a)
                        else -> ps.setString(i + 1, a.toString())
                    }
                }
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
            }
        }

    private fun traitRows(
        url: String,
        item: String
    ): List<Pair<Int, String>> =
        query(url, "SELECT position, name FROM item_traits WHERE item_id = ? ORDER BY position", BaselineDataset.id(item)) {
            it.getInt(1) to it.getString(2)
        }

    private fun propertiesOf(
        url: String,
        item: String
    ): String? = query(url, "SELECT properties FROM work_items WHERE id = ?", BaselineDataset.id(item)) { it.getString(1) }.single()

    private fun scalar(
        url: String,
        sql: String
    ): Int = query(url, sql) { it.getInt(1) }.single()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    // ---- S8: registration -------------------------------------------------------------------------------------

    @Test
    fun `S8 the step is registered in the production plan as a ONCE ITEM_BACKFILL step after type-backfill`() {
        val url = prepare("registration", emptyList())

        val (_, plan) = boot(url, "registration")

        val step = plan.single { it.name == stepName }
        assertEquals(DataStepPhase.ITEM_BACKFILL, step.phase)
        assertEquals(DataStepKind.ONCE, step.kind)
        assertTrue(typeStepName in step.after, "declared after $typeStepName: ${step.after}")
        assertTrue(
            plan.indexOfFirst { it.name == typeStepName } < plan.indexOfFirst { it.name == stepName },
            "type-backfill precedes traits-from-properties in run order: ${plan.map { it.name }}"
        )
    }

    // ---- S8: the golden V17 database ----------------------------------------------------------------------------

    @Test
    fun `S8 the golden V17 database moves array traits into item_traits, empties properties and is skipped on the second boot`() {
        val url = prepare("golden", emptyList())
        val before = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }
        val eventsBefore = scalar(url, "SELECT count(*) FROM events")
        assertEquals(0, scalar(url, "SELECT count(*) FROM item_traits"), "control: the migration alone backfills nothing")

        val (first, _) = boot(url, "golden-1")

        assertTrue(stepName in first.applied, "first boot applies the step: $first")
        assertTrue(
            first.applied.indexOf(typeStepName) < first.applied.indexOf(stepName),
            "applied after type-backfill: ${first.applied}"
        )
        for (name in BaselineDataset.ITEM_NAMES) {
            assertEquals(listOf(0 to "needs-review", 1 to "trait-$name"), traitRows(url, name), "traits of $name, in order")
            assertNull(propertiesOf(url, name), "properties of $name is NULL once only the traits key was in it")
        }
        val after = DriverManager.getConnection(url).use { UpgradeHarness.dump(it) }
        val beforeItems = before.getValue("work_items")
        val afterItems = after.getValue("work_items")
        assertEquals(beforeItems.keys, afterItems.keys, "no work item was added or lost")
        for ((id, row) in beforeItems) {
            val now = afterItems.getValue(id)
            for ((column, value) in row) {
                // properties is this step's column; the lifecycle and pin columns are pin-backfill's (phase PIN, later).
                if (column == "properties" || column == "lifecycle" || column.startsWith("pinned_")) continue
                assertEquals(value, now[column], "work_items $id column $column must not change (no version or modified_at bump)")
            }
        }
        assertEquals(eventsBefore, scalar(url, "SELECT count(*) FROM events"), "a backfill records no event rows")
        val ledger =
            query(url, "SELECT rows_affected, binary_version FROM data_steps WHERE name = '$stepName'") { it.getInt(1) to it.getString(2) }
        assertEquals(1, ledger.size, "exactly one ledger row")
        assertEquals(BaselineDataset.ITEM_NAMES.size, ledger.single().first, "rows_affected is the number of rewritten rows")
        assertTrue(ledger.single().second.isNotBlank(), "binary_version is recorded")

        val itemTraitsAfterFirst = scalar(url, "SELECT count(*) FROM item_traits")
        val (second, _) = boot(url, "golden-2")

        assertTrue(stepName in second.skipped, "second boot skips the step: $second")
        assertTrue(stepName !in second.applied, "second boot does not re-apply it: $second")
        assertEquals(itemTraitsAfterFirst, scalar(url, "SELECT count(*) FROM item_traits"), "nothing is rewritten on the second boot")
        assertEquals(BaselineDataset.ITEM_NAMES.size * 2, itemTraitsAfterFirst)
    }

    // ---- S8 and S12: every properties form ----------------------------------------------------------------------

    @Test
    fun `S8 and S12 every traits form is migrated or left byte-identical, and only rewritten rows are counted`() {
        val url = prepare("matrix", matrix)

        boot(url, "matrix")

        for (probe in matrix) {
            val label = "${probe.name} (${probe.properties})"
            when (val outcome = probe.outcome) {
                is Outcome.Migrated -> {
                    assertEquals(outcome.names.mapIndexed { i, n -> i to n }, traitRows(url, probe.name), "$label: item_traits rows")
                    val stored = propertiesOf(url, probe.name)
                    if (outcome.remaining == null) {
                        assertNull(stored, "$label: an emptied bag becomes NULL, not {}")
                    } else {
                        assertEquals(
                            json(outcome.remaining),
                            json(stored ?: error("$label: properties became NULL")),
                            "$label: other keys kept"
                        )
                        assertTrue("traits" !in json(stored), "$label: the traits key is stripped")
                    }
                }
                is Outcome.Untouched -> {
                    assertEquals(probe.properties, propertiesOf(url, probe.name), "$label: properties must be byte-identical")
                    assertEquals(emptyList(), traitRows(url, probe.name), "$label: no item_traits row may be written")
                }
            }
        }
        val rewritten = BaselineDataset.ITEM_NAMES.size + matrix.count { it.outcome is Outcome.Migrated }
        val recorded = query(url, "SELECT rows_affected FROM data_steps WHERE name = '$stepName'") { it.getInt(1) }.single()
        assertEquals(rewritten, recorded, "rows_affected counts exactly the rewritten rows (a row resolving to no names still counts)")
        val versions =
            query(url, "SELECT version, modified_at FROM work_items WHERE title LIKE 'Probe %'") {
                it.getInt(1) to
                    it.getString(2)
            }
        assertTrue(
            versions.all { it == 1 to BaselineDataset.TS_PLUS_1 },
            "no probe row got a version or modified_at bump: ${versions.distinct()}"
        )
    }

    @Test
    fun `S12 an unparseable row does not stop the neighbouring rows from migrating`() {
        val probes = listOf(untouched("a-nonjson", "{broken"), migrated("z-after", """{"traits":["ok"]}""", "ok"))
        val url = prepare("neighbours", probes)

        val (report, _) = boot(url, "neighbours")

        assertTrue(stepName in report.applied, "the step completes and is recorded: $report")
        assertEquals(listOf(0 to "ok"), traitRows(url, "z-after"))
        assertEquals("{broken", propertiesOf(url, "a-nonjson"))
    }

    // ---- item_traits rows already present --------------------------------------------------------------------------

    @Test
    fun `S8 names an item already has are skipped and the rest are appended after the existing maximum position`() {
        val probes =
            listOf(
                migrated("pre-1", """{"traits":["a","z"]}""", "z", "a"),
                migrated("pre-2", """{"traits":["m","n","o"]}""", "m", "n", "o"),
                migrated("pre-3", """{"traits":["only"]}""", "only")
            )
        val url = prepare("prepopulated", probes, existing = listOf(Triple("pre-1", 0, "z"), Triple("pre-2", 5, "m")))

        boot(url, "prepopulated")

        assertEquals(listOf(0 to "z", 1 to "a"), traitRows(url, "pre-1"), "z kept at 0, a appended at max+1")
        assertEquals(listOf(5 to "m", 6 to "n", 7 to "o"), traitRows(url, "pre-2"), "appended after the existing max position 5")
        assertEquals(listOf(0 to "only"), traitRows(url, "pre-3"), "control: an item with no rows starts at 0")
        for (name in listOf("pre-1", "pre-2", "pre-3")) assertNull(propertiesOf(url, name), "$name: properties emptied")
    }

    // ---- StartupIntegrity.reportTraitsResidue --------------------------------------------------------------------

    private fun captureWarn(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        val saved = root.level
        root.level = Level.WARN
        try {
            block()
            return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            root.detachAppender(appender)
            root.level = saved
        }
    }

    private fun residueWarnings(url: String): List<String> =
        captureWarn { DriverManager.getConnection(url).use { StartupIntegrity.reportTraitsResidue(it) } }
            .filter { it.contains("traits", ignoreCase = true) }

    @Test
    fun `S12 the residue report warns with the count while traits keys remain in properties and drops the migrated rows after the step`() {
        val probes =
            listOf(
                untouched("r-number", """{"traits":5}"""),
                untouched("r-long", """{"traits":["${nameOf(101)}"]}"""),
                untouched("r-nonjson", "not json"),
                untouched("r-array", """["traits"]"""),
                untouched("r-nokey", """{"custom":1}""")
            )
        val url = prepare("residue", probes)
        val residueBefore = BaselineDataset.ITEM_NAMES.size + 2 // the golden rows plus r-number and r-long

        val withResidue = residueWarnings(url)
        assertTrue(withResidue.isNotEmpty(), "a WARN about traits residue is expected before the step ran")
        assertTrue(
            withResidue.any { Regex("""\b$residueBefore\b""").containsMatchIn(it) },
            "the WARN carries the count $residueBefore: $withResidue"
        )

        boot(url, "residue")

        val afterStep = residueWarnings(url)
        assertTrue(afterStep.any { Regex("""\b2\b""").containsMatchIn(it) }, "the two unmigratable rows are still reported: $afterStep")
        assertTrue(
            afterStep.none { Regex("""\b$residueBefore\b""").containsMatchIn(it) },
            "the migrated rows are no longer counted: $afterStep"
        )
    }

    @Test
    fun `S12 the residue report is silent on a database with no traits key left`() {
        val url = prepare("clean", listOf(untouched("c-nokey", """{"custom":1}""")))
        boot(url, "clean")

        assertEquals(emptyList(), residueWarnings(url))
    }
}
