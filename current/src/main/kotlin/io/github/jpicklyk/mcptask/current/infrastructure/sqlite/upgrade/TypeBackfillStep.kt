package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

/**
 * The first shipped data step (plan section 3.7, U8a): gives every pre-4.0 item without a `type` the schema key the 3.x
 * tag step resolved it to ([LegacyTypeInference]), or `untyped` when none matched, so a 4.0 server that resolves by
 * type alone keeps every item on the schema it had.
 *
 * Reads the global config file at [globalConfigPath] (absent = no global layer) and every `project_config` row for the
 * layers; only each document's `schema_resolution` and schema KEYS are read, with its own SnakeYAML scan, so it needs no
 * schema validation and does not depend on the config parsers a later work item reshapes. A document that does not parse
 * counts as no layer (WARN), never a startup failure. Writes the `type` column only (no version bump, no modified_at, no
 * events): a backfill is not an edit, and the `data_steps` ledger row is the record. Idempotent: it only touches rows whose
 * type is NULL or blank, so a second run finds none. Rows with a NULL root_id infer against the global layer only.
 */
class TypeBackfillStep(
    private val globalConfigPath: Path
) : DataStep {
    override val name: String = NAME
    override val phase: DataStepPhase = DataStepPhase.ITEM_BACKFILL
    override val kind: DataStepKind = DataStepKind.ONCE

    override suspend fun run(scope: WriteScope): Int {
        val global = globalLayer()
        val tx = TransactionManager.current()

        val perRoot = mutableMapOf<String, LegacyTypeInference.Layer>()
        tx.exec("SELECT lower(hex(root_item_id)) AS root, config_yaml FROM project_config") { rs ->
            while (rs.next()) {
                val layer = layerOf(rs.getString("config_yaml"), "project_config row for root ${rs.getString("root")}")
                if (layer != null) perRoot[rs.getString("root")] = layer
            }
        }

        class Untyped(
            val rowId: Long,
            val inferred: String
        )
        val untyped = mutableListOf<Untyped>()
        tx.exec(
            "SELECT rowid AS rid, lower(hex(root_id)) AS root, tags FROM work_items WHERE type IS NULL OR trim(type) = ''"
        ) { rs ->
            while (rs.next()) {
                val root = rs.getString("root")
                val inferred =
                    LegacyTypeInference.infer(
                        LegacyTypeInference.parseTags(rs.getString("tags")),
                        root?.let { perRoot[it] },
                        global
                    )
                untyped += Untyped(rs.getLong("rid"), inferred)
            }
        }

        val textType = VarCharColumnType(255)
        val rowType = LongColumnType()
        for (row in untyped) {
            tx.exec("UPDATE work_items SET type = ? WHERE rowid = ?", args = listOf(textType to row.inferred, rowType to row.rowId))
        }
        logger.info(
            "type-backfill: typed {} item(s) ({} untyped)",
            untyped.size,
            untyped.count { it.inferred == LegacyTypeInference.UNTYPED }
        )
        return untyped.size
    }

    /** The layer a 3.x config document [yaml] describes (see the companion's [layerOf]). */
    internal fun layerOf(yaml: String): LegacyTypeInference.Layer? = Companion.layerOf(yaml)

    private fun globalLayer(): LegacyTypeInference.Layer? {
        if (!Files.isRegularFile(globalConfigPath)) return null
        val text =
            try {
                Files.readString(globalConfigPath)
            } catch (e: java.io.IOException) {
                logger.warn("type-backfill: cannot read the global config {}: {}", globalConfigPath, e.message)
                return null
            }
        return layerOf(text, "global config $globalConfigPath")
    }

    internal companion object {
        const val NAME = "type-backfill"
        private val logger = LoggerFactory.getLogger(TypeBackfillStep::class.java)

        /**
         * The layer a 3.x config document [yaml] describes: its schema keys (`work_item_schemas`, else `note_schemas`,
         * the same precedence the config parser uses) and its `schema_resolution`. Null when the document is not a YAML
         * map or does not parse (a WARN names [source]).
         */
        fun layerOf(
            yaml: String,
            source: String = "config document"
        ): LegacyTypeInference.Layer? =
            try {
                val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml)
                if (root !is Map<*, *>) {
                    if (root != null) logger.warn("type-backfill: {} is not a YAML map; treated as no layer", source)
                    null
                } else {
                    val section = if (root.containsKey("work_item_schemas")) root["work_item_schemas"] else root["note_schemas"]
                    val keys =
                        (section as? Map<*, *>)
                            ?.keys
                            ?.mapNotNull { it?.toString() }
                            ?.toSet()
                            .orEmpty()
                    LegacyTypeInference.Layer(keys, LegacyTypeInference.parseMode(root["schema_resolution"] as? String))
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logger.warn("type-backfill: {} does not parse ({}); treated as no layer", source, e.message)
                null
            }
    }
}
