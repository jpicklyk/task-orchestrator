package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference
import io.github.jpicklyk.mcptask.current.domain.lifecycle.SchemaFacts
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.PinLayer
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Pins every item that has no config pin yet (plan section 3.7 PIN phase, U8b/U8c): the first 4.0 boot of a 3.x
 * database freezes each in-flight item on the base schema the 3.x server gave it.
 *
 * For every `work_items` row with `pinned_at IS NULL`:
 * - `pinned_server_cfg` = the server-scope head version (the `file-import` version when a global file is mounted;
 *   `server-config-import` ran in CONFIG_IMPORT), NULL when there is none or it is a reset;
 * - `pinned_root_cfg` = the row's root head version, NULL when the row has no `root_id`, its root has no config, or the
 *   head is a reset;
 * - `pinned_schema_key` / `pinned_schema_layer` = the LEGACY (3.x, `schema_resolution`-aware) resolution of the row's
 *   `type` over those two documents ([LegacyTypeInference.resolve] with the type as the only tag: so a per-root
 *   `default` shadows a server exact type, as it did in 3.x), both NULL when nothing matches; a NULL, blank or
 *   `untyped` type contributes no tag, so only the `default` steps can match;
 * - `lifecycle` = that schema's lifecycle mode, NULL when schema-free;
 * - `pinned_at` = the step's unit instant.
 *
 * Schema keys and modes come from [TypeBackfillStep.layerOf] (a key-only scan, no validation), narrowed to the keys the
 * MIGRATION-mode config parser builds; lifecycles come from that parse (never rejects). A head document that does not parse counts as no layer for the key
 * resolution (WARN), never a startup failure; its version id is still pinned. Writes the pin columns only: no version
 * bump, no `modified_at`, no events (a backfill is not an edit; the `data_steps` ledger row is the record). Idempotent:
 * only `pinned_at IS NULL` rows are touched. Returns the number of rows pinned.
 *
 * [globalConfigPath] is the mounted global config file; the step reads the stored server head, not the file, and only
 * warns when a file is present but no server head exists.
 */
class PinBackfillStep(
    private val globalConfigPath: Path
) : DataStep {
    override val name: String = NAME
    override val phase: DataStepPhase = DataStepPhase.PIN
    override val kind: DataStepKind = DataStepKind.ONCE

    /** One head document: its version id and what the key resolution reads from it. */
    private class Head(
        val versionId: UUID,
        val layer: LegacyTypeInference.Layer?,
        val lifecycles: Map<String, LifecycleMode>
    )

    private class Pin(
        val rowId: Long,
        val server: UUID?,
        val root: UUID?,
        val key: String?,
        val layer: PinLayer?,
        val lifecycle: LifecycleMode?
    )

    override suspend fun run(scope: WriteScope): Int {
        val tx = TransactionManager.current()
        val uuidType = UUIDColumnType()

        var server: Head? = null
        tx.exec(
            "SELECT v.id AS vid, v.yaml AS config_yaml FROM config_heads h JOIN config_versions v ON v.id = h.version_id " +
                "WHERE h.scope = 'server' AND v.source <> 'reset'"
        ) { rs ->
            if (rs.next()) {
                server =
                    headOf(uuidType.valueFromDB(rs.getObject("vid")!!) as UUID, rs.getString("config_yaml"), "server head config")
            }
        }
        if (server == null && Files.isRegularFile(globalConfigPath)) {
            logger.warn("pin-backfill: a global config file exists at {} but no server config version is stored", globalConfigPath)
        }

        val roots = HashMap<String, Head>()
        tx.exec(
            "SELECT lower(hex(h.root_id)) AS root, v.id AS vid, v.yaml AS config_yaml FROM config_heads h " +
                "JOIN config_versions v ON v.id = h.version_id WHERE h.scope = 'root' AND v.source <> 'reset'"
        ) { rs ->
            while (rs.next()) {
                val root = rs.getString("root")
                roots[root] =
                    headOf(
                        uuidType.valueFromDB(rs.getObject("vid")!!) as UUID,
                        rs.getString("config_yaml"),
                        "head config version for root $root"
                    )
            }
        }

        val pins = mutableListOf<Pin>()
        tx.exec("SELECT rowid AS rid, lower(hex(root_id)) AS root, type FROM work_items WHERE pinned_at IS NULL") { rs ->
            while (rs.next()) {
                val root = rs.getString("root")?.let { roots[it] }
                val type = rs.getString("type")
                val tags = if (type.isNullOrBlank() || type == LegacyTypeInference.UNTYPED) emptyList() else listOf(type)
                val resolved = LegacyTypeInference.resolve(tags, root?.layer, server?.layer)
                // Every backfilled row bears a role, so a `lifecycle: none` base schema pins 'auto' (the V26 CHECK ties
                // 'none' to a NULL role; a 'none' pin here would fail the boot).
                val lifecycle =
                    resolved
                        ?.let { r -> (if (r.perRoot) root else server)?.lifecycles?.get(r.key) }
                        ?.let { SchemaFacts.roleBearing(it) }
                pins +=
                    Pin(
                        rowId = rs.getLong("rid"),
                        server = server?.versionId,
                        root = root?.versionId,
                        key = resolved?.key,
                        layer = resolved?.let { if (it.perRoot) PinLayer.ROOT else PinLayer.SERVER },
                        lifecycle = lifecycle
                    )
            }
        }

        val pinnedAt = UtcTimestamp.format(scope.now)
        val textType = TextColumnType()
        val rowType = LongColumnType()
        for (pin in pins) {
            // NULLs are written as literals (a typed bind of null needs a nullable column type).
            val args = ArrayList<Pair<ColumnType<*>, Any?>>()

            fun slot(
                type: ColumnType<*>,
                value: Any?
            ): String =
                if (value == null) {
                    "NULL"
                } else {
                    args += type to value
                    "?"
                }
            val sql =
                "UPDATE work_items SET pinned_server_cfg = ${slot(uuidType, pin.server)}, " +
                    "pinned_root_cfg = ${slot(uuidType, pin.root)}, " +
                    "pinned_schema_key = ${slot(textType, pin.key)}, " +
                    "pinned_schema_layer = ${slot(textType, pin.layer?.wire)}, " +
                    "lifecycle = ${slot(textType, pin.lifecycle?.name?.lowercase())}, " +
                    "pinned_at = ${slot(textType, pinnedAt)} WHERE rowid = ${slot(rowType, pin.rowId)}"
            tx.exec(sql, args = args)
        }
        logger.info("pin-backfill: pinned {} item(s) ({} schema-free)", pins.size, pins.count { it.key == null })
        return pins.size
    }

    /**
     * The head of version [versionId] with document [yaml]: its key layer and its schemas' lifecycles. When the
     * MIGRATION parse succeeds, the key set is narrowed to the schemas that parse built (an entry the parser skips,
     * such as a non-map value, is absent from the document a pinned item is served, so it must not be pinned).
     */
    private fun headOf(
        versionId: UUID,
        yaml: String,
        source: String
    ): Head {
        val lifecycles = lifecyclesOf(yaml, source)
        val layer = TypeBackfillStep.layerOf(yaml, source)
        val served = if (lifecycles == null || layer == null) layer else layer.copy(schemaKeys = layer.schemaKeys intersect lifecycles.keys)
        return Head(versionId, served, lifecycles.orEmpty())
    }

    /** Each parsed schema's lifecycle by key, or null when the document does not parse (WARN). */
    private fun lifecyclesOf(
        yaml: String,
        source: String
    ): Map<String, LifecycleMode>? =
        try {
            when (val outcome = parser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)) {
                is ConfigDocumentParser.Outcome.Parsed -> outcome.document.workItemSchemas.mapValues { it.value.lifecycleMode }
                is ConfigDocumentParser.Outcome.Failed -> {
                    logger.warn("pin-backfill: {} does not parse ({}); its schemas pin no lifecycle", source, outcome.detail)
                    null
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("pin-backfill: {} does not parse ({}); its schemas pin no lifecycle", source, e.message)
            null
        }

    internal companion object {
        const val NAME = "pin-backfill"
        private val logger = LoggerFactory.getLogger(PinBackfillStep::class.java)
        private val parser: ConfigDocumentParser = YamlConfigDocumentParser
    }
}
