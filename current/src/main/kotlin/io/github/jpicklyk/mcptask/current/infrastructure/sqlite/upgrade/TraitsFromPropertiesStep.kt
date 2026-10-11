package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory

/**
 * Moves the 3.x `properties.traits` convention into the typed `item_traits` table (plan section 3.7, Q15): for every
 * item whose `properties` is a JSON object with a `traits` key holding an array of strings or a comma-separated string,
 * the names are inserted into `item_traits` and the key is stripped from `properties` (the bag becomes NULL when
 * nothing else remains).
 *
 * Carries its own frozen parser (the 3.x one is gone): names are trimmed, empty ones dropped, duplicates dropped keeping
 * the first. A row is left untouched and REPORTED (one WARN with the count and the first ids, never a startup failure)
 * when its `properties` is not a JSON object, when `traits` is neither an array of strings nor a string, or when a name
 * cannot be stored (over 100 characters, or containing a comma). An object without a `traits` key is left untouched
 * silently.
 *
 * Writes `item_traits` and `properties` only: no version bump, no modified_at, no events (a backfill is not an edit; the
 * `data_steps` ledger row is the record). Names already present for an item are skipped and new ones are appended after
 * its highest position, so a partially populated table never fails the step. Idempotent: a second run finds no `traits`
 * key. Returns the number of rows rewritten (a row whose list resolved to empty still had its key stripped and counts).
 */
class TraitsFromPropertiesStep : DataStep {
    override val name: String = NAME
    override val phase: DataStepPhase = DataStepPhase.ITEM_BACKFILL
    override val kind: DataStepKind = DataStepKind.ONCE
    override val after: Set<String> = setOf(TypeBackfillStep.NAME)

    private class Existing {
        val names = mutableSetOf<String>()
        var nextPosition = 0
    }

    private class Migration(
        val rowId: Long,
        val names: List<String>,
        val remaining: String?,
        val item: String
    )

    override suspend fun run(scope: WriteScope): Int {
        val tx = TransactionManager.current()

        val existing = HashMap<String, Existing>()
        tx.exec("SELECT lower(hex(item_id)) AS item, position, name FROM item_traits") { rs ->
            while (rs.next()) {
                val e = existing.getOrPut(rs.getString("item")) { Existing() }
                e.names += rs.getString("name")
                e.nextPosition = maxOf(e.nextPosition, rs.getInt("position") + 1)
            }
        }

        val migrations = mutableListOf<Migration>()
        val unmigratable = mutableListOf<String>()
        tx.exec(
            "SELECT rowid AS rid, lower(hex(id)) AS item, properties FROM work_items " +
                "WHERE properties IS NOT NULL AND trim(properties) <> ''"
        ) { rs ->
            while (rs.next()) {
                val item = rs.getString("item")
                when (val outcome = parse(rs.getString("properties"))) {
                    Outcome.Untouched -> Unit
                    Outcome.Unmigratable -> unmigratable += item
                    is Outcome.Migrate -> migrations += Migration(rs.getLong("rid"), outcome.names, outcome.remaining, item)
                }
            }
        }

        val rowType = LongColumnType()
        val textType = TextColumnType()
        val intType = IntegerColumnType()
        for (m in migrations) {
            val e = existing.getOrPut(m.item) { Existing() }
            for (traitName in m.names) {
                if (!e.names.add(traitName)) continue
                tx.exec(
                    "INSERT INTO item_traits (item_id, position, name) SELECT id, ?, ? FROM work_items WHERE rowid = ?",
                    args = listOf(intType to e.nextPosition, textType to traitName, rowType to m.rowId)
                )
                e.nextPosition++
            }
            tx.exec("UPDATE work_items SET properties = ? WHERE rowid = ?", args = listOf(textType to m.remaining, rowType to m.rowId))
        }

        if (unmigratable.isNotEmpty()) {
            logger.warn(
                "traits-from-properties: {} item(s) hold a properties value that could not be migrated and were left untouched. First {}: {}",
                unmigratable.size,
                minOf(unmigratable.size, SAMPLE_LIMIT),
                unmigratable.take(SAMPLE_LIMIT).joinToString(", ")
            )
        }
        logger.info("traits-from-properties: rewrote {} item(s)", migrations.size)
        return migrations.size
    }

    internal sealed interface Outcome {
        /** Nothing to do for this row and nothing to report. */
        data object Untouched : Outcome

        /** Left as-is and reported. */
        data object Unmigratable : Outcome

        /** Insert [names] and rewrite `properties` to [remaining] (null when the bag is now empty). */
        data class Migrate(
            val names: List<String>,
            val remaining: String?
        ) : Outcome
    }

    internal companion object {
        const val NAME = "traits-from-properties"
        private const val SAMPLE_LIMIT = 10
        private const val MAX_NAME_LENGTH = 100
        private val logger = LoggerFactory.getLogger(TraitsFromPropertiesStep::class.java)

        /** Classifies one stored `properties` value (see the class KDoc). */
        fun parse(properties: String): Outcome {
            val parsed = runCatchingNonCancellation { Json.parseToJsonElement(properties) }.getOrNull()
            if (parsed !is JsonObject) return Outcome.Unmigratable
            val value = parsed["traits"] ?: return Outcome.Untouched
            val raw: List<String> =
                when {
                    value is JsonArray -> {
                        if (value.any { it !is JsonPrimitive || !it.isString }) return Outcome.Unmigratable
                        value.map { (it as JsonPrimitive).content }
                    }
                    value is JsonPrimitive && value.isString -> value.content.split(",")
                    else -> return Outcome.Unmigratable
                }
            val names = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (names.any { it.length > MAX_NAME_LENGTH || it.contains(',') }) return Outcome.Unmigratable
            val rest = JsonObject(parsed.filterKeys { it != "traits" })
            return Outcome.Migrate(names, if (rest.isEmpty()) null else rest.toString())
        }
    }
}
