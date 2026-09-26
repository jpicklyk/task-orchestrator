package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Shared [ConfigDocumentParser] implementation for attacker-reachable (pushed) per-root config
 * YAML — used by [PerRootConfigService] on the read path and by
 * [io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService] on the
 * validate-before-write path, so both converge on identical parse behavior for the same document.
 *
 * Uses [SafeConstructor] rather than SnakeYAML's default `Constructor`: the input originates from
 * a pushed document (via `manage_project_config` / `PUT /api/v1/roots/{rootId}/config`), not a
 * trusted local file. The default `Constructor` will instantiate an arbitrary Java type named by a
 * `!!`-tag (CWE-502); `SafeConstructor` only ever builds plain maps/lists/scalars and rejects
 * anything else as a parse failure.
 *
 * Any exception during load or parse becomes [ConfigDocumentParser.Outcome.Failed] with
 * `e.message ?: e.javaClass.simpleName`.
 */
object YamlConfigDocumentParser : ConfigDocumentParser {
    @Suppress("UNCHECKED_CAST", "TooGenericExceptionCaught")
    override fun parse(
        yaml: String,
        warnOnMissingSchemas: Boolean,
    ): ConfigDocumentParser.Outcome =
        try {
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml)
            if (root == null) {
                ConfigDocumentParser.Outcome.Parsed(document = ConfigDocument.EMPTY, rawRoot = null)
            } else {
                val document = YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = warnOnMissingSchemas)
                ConfigDocumentParser.Outcome.Parsed(document = document, rawRoot = root)
            }
        } catch (e: Exception) {
            ConfigDocumentParser.Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
}
