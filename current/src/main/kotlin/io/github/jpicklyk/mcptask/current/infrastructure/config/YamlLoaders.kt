package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException

/**
 * The one place a SnakeYAML [Yaml] is built (enforced by `YamlLoaderGuardTest`).
 *
 * Every loader uses [SafeConstructor], so a `!!`-tag can never instantiate an arbitrary class (CWE-502).
 * [Yaml] is not thread-safe, so each call returns a NEW instance.
 */
internal object YamlLoaders {
    private val HEX_RUN = Regex("[0-9a-fA-F]{32,}")

    /** A loader that rejects a duplicated mapping key at any depth (SnakeYAML's default is last-wins). */
    fun strict(): Yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))

    /** A loader with SnakeYAML's default options (a duplicated key is last-wins); for reads that must not reject stored text. */
    fun tolerant(): Yaml = Yaml(SafeConstructor(LoaderOptions()))

    /**
     * A digest-free description of a YAML load failure, safe for a startup error that may echo a secret file.
     *
     * A [MarkedYAMLException] becomes `<problem> at line <n>` (runs of 32 or more hex characters in the problem are
     * redacted, and the source snippet is never included); any other exception becomes its simple class name only.
     */
    fun describe(e: Exception): String {
        if (e !is MarkedYAMLException) return e.javaClass.simpleName
        val problem = HEX_RUN.replace(e.problem ?: e.javaClass.simpleName, "<hex redacted>")
        val line = (e.problemMark ?: e.contextMark)?.line?.plus(1)
        return if (line != null) "$problem at line $line" else problem
    }
}
