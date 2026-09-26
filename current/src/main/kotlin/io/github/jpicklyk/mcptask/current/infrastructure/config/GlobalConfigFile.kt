package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.config.SchemaResolutionMode
import io.github.jpicklyk.mcptask.current.infrastructure.security.configFingerprint
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Path

/**
 * The single, process-wide global config file reader. Reads and parses
 * `.taskorchestrator/config.yaml` (path given by [configPath]) exactly ONCE, lazily, and caches
 * the result (or the absence of a file) for the life of the process — restart to reload, same as
 * every other global-config value.
 *
 * Moved verbatim from `YamlWorkItemSchemaService.loadSchemas()` (this item's C1 restructuring):
 * same [IllegalArgumentException] messages, same fingerprint computed over the same bytes, same
 * WARN/INFO logging. [YamlWorkItemSchemaService], [YamlStatusLabelService], and
 * [YamlActorAuthenticationConfigService] all read the same document from one instance of this
 * class instead of each independently re-reading and re-parsing the file — see their
 * `GlobalConfigFile`-taking constructors.
 *
 * **Fails closed**: a file that exists but cannot be read, is not valid YAML, or whose parsed root
 * is not a mapping throws [IllegalArgumentException] naming [configPath] rather than silently
 * falling back to an empty (schema-free) [ConfigDocument] — see `ServerComposition.build`, which
 * forces this lazy load at startup so the failure surfaces before the readiness marker is written.
 * An absent, empty, or comment-only file is not an error: [layer] returns a
 * [ConfigLayer] wrapping [ConfigDocument.EMPTY] for an empty/comment-only file, and `null` only
 * when the file itself is absent.
 */
class GlobalConfigFile(
    val configPath: Path
) : GlobalConfigSource {
    private val logger = LoggerFactory.getLogger(GlobalConfigFile::class.java)

    /** Lazily loaded layer. `null` means no file was present at load time. */
    private val loadResult: ConfigLayer? by lazy { loadLayer() }

    override fun layer(): ConfigLayer? = loadResult

    @Suppress("UNCHECKED_CAST")
    private fun loadLayer(): ConfigLayer? {
        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; running in schema-free mode", configPath)
            return null
        }

        // Read the bytes once: the fingerprint is computed over, and the YAML is parsed from,
        // this exact same byte array (D4) — no separate re-read of a possibly-changed file.
        val bytes =
            try {
                configPath.toFile().readBytes()
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to read note schemas config from '$configPath': ${e.message}",
                    e
                )
            }
        // JVM String decoding of UTF-8 bytes keeps a leading U+FEFF as a real character, so
        // configFingerprint's BOM-stripping normalization applies here exactly as it does for the
        // raw byte path elsewhere — YAML parsing below still uses the raw bytes.
        val fingerprint = configFingerprint(String(bytes, Charsets.UTF_8))

        val root =
            try {
                val yaml = Yaml(SafeConstructor(LoaderOptions()))
                yaml.load<Any?>(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to load note schemas from '$configPath': ${e.message}",
                    e
                )
            }

        val parsedDocument =
            if (root == null) {
                ConfigDocument.EMPTY
            } else {
                @Suppress("UNCHECKED_CAST")
                val rootMap =
                    root as? Map<String, Any>
                        ?: throw IllegalArgumentException(
                            "Config file '$configPath' root must be a mapping; got '$root'"
                        )
                try {
                    YamlSchemaParser.parseRoot(rootMap)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: Exception) {
                    // An unexpected section shape (e.g. a ClassCastException from an unchecked cast)
                    // must still fail startup naming the file, like every other global-config error.
                    throw IllegalArgumentException("Failed to parse note schemas in '$configPath': ${e.message}", e)
                }
            }

        // schema_resolution: isolated means nothing in the global config: there is no per-root
        // layer above it to isolate from, so treat it as layered (AR-39, C4) and warn once.
        val document =
            if (parsedDocument.schemaResolution == SchemaResolutionMode.ISOLATED) {
                parsedDocument.copy(
                    warnings =
                        parsedDocument.warnings +
                            "schema_resolution: isolated has no effect in the global config " +
                            "(nothing to isolate from); treating as layered"
                )
            } else {
                parsedDocument
            }

        document.warnings.forEach { w -> logger.warn(w) }
        val totalEntries = document.workItemSchemas.values.sumOf { it.notes.size }
        logger.info(
            "Loaded {} schemas ({} entries, {} warnings)",
            document.workItemSchemas.size,
            totalEntries,
            document.warnings.size
        )

        return ConfigLayer(document = document, fingerprint = fingerprint, source = ConfigSource.GLOBAL)
    }
}
