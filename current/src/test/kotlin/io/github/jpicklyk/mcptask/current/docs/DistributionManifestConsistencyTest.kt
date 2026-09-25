package io.github.jpicklyk.mcptask.current.docs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent test-author suite for item 51ae57ad: keeps the repo's distribution manifests
 * (`Dockerfile`, `docker-compose.yml`, `server.json`, `smithery.yaml`) honest against the
 * application's actual environment-variable surface and against each other's data-volume
 * mount path, so a manifest can drift silently no longer.
 *
 * These manifests live OUTSIDE `current/` and are not declared Gradle inputs by default (see
 * `current/build.gradle.kts` `tasks.test { inputs.files(...) }`, which this item also updates to
 * include them) — a manifest-only edit must invalidate this test's cached result, not report
 * UP-TO-DATE.
 *
 * Known-key oracle: every environment-variable NAME the application source actually reads, scraped
 * from `current/src/main/kotlin` via two patterns:
 *   - `(env|envResolver|getenv)("SOME_KEY")` — covers `AppConfig`'s `env(...)` helper, direct
 *     `System.getenv(...)` call sites, and the `envResolver(...)` parameter name.
 *   - `const val SOMETHING_ENV = "SOME_KEY"` — covers named env-key constants declared separately
 *     from the read site (e.g. `AdvanceService.RESOURCE_LEASES_ENFORCED_ENV`).
 * A manifest that declares a key outside this set is either dead (like the bug this item fixes:
 * Dockerfile's old `ENV DATABASE_PATH=/app/data/tasks.db` at the base stage, shadowed by the
 * `runtime-current` stage's own `ENV DATABASE_PATH=data/current-tasks.db`) or a typo — either way
 * the test should fail and name the offending key(s).
 */
class DistributionManifestConsistencyTest {
    // ---- repo-root / file access -------------------------------------------------------------

    /** Walk up from the test working directory until the repo root (contains both marker dirs). */
    private fun repoRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("claude-plugins")) && Files.isDirectory(dir.resolve("current"))) {
                return dir
            }
            dir = dir.parent
        }
        fail("could not locate repo root (looking for a dir containing both 'claude-plugins/' and 'current/')")
    }

    private fun readRepoFile(relativePath: String): String {
        val path = repoRoot().resolve(relativePath)
        if (!Files.isRegularFile(path)) fail("$relativePath not found at $path")
        return Files.readString(path)
    }

    private fun dockerfileText() = readRepoFile("Dockerfile")

    private fun composeText() = readRepoFile("docker-compose.yml")

    private fun serverJsonText() = readRepoFile("server.json")

    private fun smitheryYamlText() = readRepoFile("smithery.yaml")

    // ---- known-key oracle ---------------------------------------------------------------------

    private val envCallPattern = Regex("""(?:\benv|\benvResolver|\bgetenv)\("([A-Z0-9_]+)"\)""")
    private val envConstPattern = Regex("""const val\s+\w+_ENV\s*=\s*"([A-Z0-9_]+)"""")

    private fun knownEnvKeys(): Set<String> {
        val mainSrc = repoRoot().resolve("current/src/main/kotlin")
        if (!Files.isDirectory(mainSrc)) fail("main source dir not found at $mainSrc")
        val keys = mutableSetOf<String>()
        Files.walk(mainSrc).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { file ->
                    val text = Files.readString(file)
                    envCallPattern.findAll(text).forEach { keys += it.groupValues[1] }
                    envConstPattern.findAll(text).forEach { keys += it.groupValues[1] }
                }
        }
        assertTrue(keys.isNotEmpty(), "known-env-key scrape found zero keys under $mainSrc -- scrape regex is broken")
        return keys
    }

    // ---- manifest env-key extraction -----------------------------------------------------------

    /** `ENV KEY=value` lines, in declaration order (duplicates preserved -- S3 checks that). */
    private fun dockerfileEnvDeclarations(): List<String> =
        Regex("""(?m)^\s*ENV\s+([A-Z0-9_]+)=""").findAll(dockerfileText()).map { it.groupValues[1] }.toList()

    /** `KEY: value` lines nested under a compose `environment:` block (6-space indent in this file's style). */
    private fun composeEnvKeys(): List<String> =
        Regex("""(?m)^ {6}([A-Z0-9_]+):""").findAll(composeText()).map { it.groupValues[1] }.toList()

    private fun serverJsonEnvKeys(): List<String> {
        val json = Json.parseToJsonElement(serverJsonText()).jsonObject
        val packages = json["packages"]?.jsonArray ?: fail("server.json has no 'packages' array")
        return packages.flatMap { pkg ->
            pkg.jsonObject["environmentVariables"]?.jsonArray.orEmpty().map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
        }
    }

    // ---- S1: every manifest-declared env key is one the application actually reads ------------

    @Test
    fun `S1 every env key declared in Dockerfile, compose, or server json is a known application env var`() {
        val known = knownEnvKeys()

        val declared =
            mapOf(
                "Dockerfile" to dockerfileEnvDeclarations(),
                "docker-compose.yml" to composeEnvKeys(),
                "server.json" to serverJsonEnvKeys(),
            )

        val unknown =
            declared.flatMap { (source, keys) ->
                keys.filter { it !in known }.map { "$source: $it" }
            }

        assertTrue(
            unknown.isEmpty(),
            "manifest(s) declare env key(s) the application source never reads (dead or typo'd): " +
                unknown.joinToString(", ") +
                "\nknown keys: " + known.sorted(),
        )
    }

    // ---- S3: Dockerfile never shadows an ENV key across stages ----------------------------------

    @Test
    fun `S3 Dockerfile declares each ENV key at most once`() {
        val keys = dockerfileEnvDeclarations()
        val duplicates =
            keys
                .groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
                .keys

        assertTrue(
            duplicates.isEmpty(),
            "Dockerfile declares the same ENV key more than once (a later stage silently shadows an " +
                "earlier one): $duplicates -- each key must be declared exactly once, in the stage that " +
                "actually ships",
        )
    }

    // ---- S4: every DATABASE_PATH value resolves under the /app/data volume ---------------------

    @Test
    fun `S4 every DATABASE_PATH value resolves under the app data volume`() {
        assertTrue(
            Regex("""(?m)^\s*VOLUME\s+/app/data\s*$""").containsMatchIn(dockerfileText()),
            "Dockerfile must declare VOLUME /app/data -- S4's premise (DATABASE_PATH must resolve " +
                "under it) depends on this volume existing",
        )

        val dockerfileValues =
            Regex("""(?m)^\s*ENV\s+DATABASE_PATH=(\S+)""").findAll(dockerfileText()).map { it.groupValues[1] }.toList()
        assertTrue(dockerfileValues.isNotEmpty(), "Dockerfile must declare an ENV DATABASE_PATH=<value>")

        val composeValues =
            Regex("""(?m)^\s*DATABASE_PATH:\s*"?([^"\s]+)"?""").findAll(composeText()).map { it.groupValues[1] }.toList()
        assertTrue(composeValues.isNotEmpty(), "docker-compose.yml must set DATABASE_PATH: <value> on its services")

        // Dockerfile WORKDIR is /app (checked explicitly so a relative-path resolution below isn't
        // silently wrong if WORKDIR ever moves).
        assertTrue(
            Regex("""(?m)^\s*WORKDIR\s+/app\s*$""").containsMatchIn(dockerfileText()),
            "Dockerfile must declare WORKDIR /app -- relative DATABASE_PATH values resolve against it",
        )

        fun resolvesUnderAppData(value: String): Boolean {
            val absolute = if (value.startsWith("/")) value else "/app/$value"
            return absolute == "/app/data" || absolute.startsWith("/app/data/")
        }

        val offenders = (dockerfileValues + composeValues).filterNot(::resolvesUnderAppData)
        assertTrue(
            offenders.isEmpty(),
            "DATABASE_PATH value(s) do not resolve under the /app/data volume (SQLite file would " +
                "not persist across container recreation): $offenders",
        )
    }

    // ---- S5: server.json and smithery.yaml each mount the data volume at /app/data -------------

    @Test
    fun `S5 server json mounts a data volume at app data`() {
        val json = Json.parseToJsonElement(serverJsonText()).jsonObject
        val packages = json["packages"]?.jsonArray ?: fail("server.json has no 'packages' array")

        val mountsAppData =
            packages.any { pkg ->
                pkg.jsonObject["runtimeArguments"]?.jsonArray.orEmpty().any { arg ->
                    val obj = arg.jsonObject
                    val value = obj["value"]?.jsonPrimitive?.content.orEmpty()
                    val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                    (name == "-v" || name == "--volume") && value.contains(":/app/data")
                }
            }

        assertTrue(
            mountsAppData,
            "server.json's oci package must declare a runtimeArguments entry mounting a volume at " +
                "/app/data (e.g. name \"-v\", value \"<volume>:/app/data\") -- without it, every " +
                "registry-driven install runs with an ephemeral (non-persistent) database",
        )
    }

    @Test
    fun `S5 smithery yaml mounts a data volume at app data`() {
        assertTrue(
            smitheryYamlText().contains(":/app/data"),
            "smithery.yaml's commandFunction must mount a volume at /app/data, matching the " +
                "Dockerfile's VOLUME /app/data and server.json's runtimeArguments mount",
        )
    }
}
