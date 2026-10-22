package io.github.jpicklyk.mcptask.current.application.upgrade.steps

import io.github.jpicklyk.mcptask.current.application.config.ConfigViolation
import io.github.jpicklyk.mcptask.current.application.port.ConfigFileSource
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ConfigImportResult
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepException
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepKind
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepPhase
import org.slf4j.LoggerFactory

/**
 * Imports the mounted global config file into the database on every boot (plan section 3.6/3.7), so the runtime
 * reads only the stored head. A file that is absent, empty or comment-only imports nothing and never resets a stored
 * head. A rejected file fails startup (`DATA_STEPS`) before any transport binds or the readiness marker is written.
 */
class ServerConfigImportStep(
    private val configService: ConfigService,
    private val file: ConfigFileSource,
) : DataStep {
    private val logger = LoggerFactory.getLogger(ServerConfigImportStep::class.java)

    override val name: String = "server-config-import"
    override val phase: DataStepPhase = DataStepPhase.CONFIG_IMPORT
    override val kind: DataStepKind = DataStepKind.EVERY_BOOT
    override val after: Set<String> = emptySet()

    override suspend fun run(scope: WriteScope): Int {
        val text = file.text()
        if (text == null) {
            logger.info("server-config-import: no global config file at {}; the stored server config, if any, stands", file.description)
            return 0
        }
        return when (val result = configService.importDocument(text)) {
            is ConfigImportResult.Imported -> {
                val target = result.version.rootId?.let { "project root $it" } ?: "server"
                logger.info(
                    "server-config-import: {} -> {} config version {} (appended={}, warnings={})",
                    file.description,
                    target,
                    result.version.version,
                    result.appended,
                    result.warnings.size,
                )
                if (result.appended) 1 else 0
            }
            ConfigImportResult.Skipped -> {
                logger.info("server-config-import: {} is empty; the stored server config, if any, stands", file.description)
                0
            }
            is ConfigImportResult.Invalid -> reject(result.errors.joinToString("; ") { render(it) })
            is ConfigImportResult.ParseError -> reject(result.detail)
            is ConfigImportResult.RootNotFound ->
                {
                    // The exception text is a frozen contract (asserted verbatim); the remedy rides on its own ERROR line.
                    logger.error(
                        "server-config-import: {} sets project.rootId {}, which is not a project root (depth 0) in this " +
                            "database. Remedy: remove project.rootId from the mounted global config, or create or import " +
                            "that root first.",
                        file.description,
                        result.rootId,
                    )
                    reject("project.rootId ${result.rootId} is not a project root (depth 0) in this database")
                }
            is ConfigImportResult.TooLarge -> reject("file is ${result.sizeBytes} bytes, over the ${result.maxBytes} byte limit")
        }
    }

    private fun render(violation: ConfigViolation): String = "${violation.path}: ${violation.reason}"

    private fun reject(reasons: String): Nothing = throw DataStepException("server-config-import: ${file.description} rejected: $reasons")
}
