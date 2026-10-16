package io.github.jpicklyk.mcptask.current.docs

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
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
    private val envConstPattern = Regex("""const val\s+\w+_(?:ENV|VAR)\s*=\s*"([A-Z0-9_]+)"""")

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

    // =============================================================================================
    // Release-gate additions (item 6042dfbd). Test names carry a "P6" prefix so they stay distinct
    // from the S1/S3/S4/S5 tests above (a different item's numbering).
    // =============================================================================================

    // ---- compose parsing (pure on its input so synthetic text can prove the harness) --------------

    private fun unquoteYamlScalar(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("\"")) {
            val close = value.indexOf('"', 1)
            return if (close > 0) value.substring(1, close) else value.substring(1)
        }
        if (value.startsWith("'")) {
            val close = value.indexOf('\'', 1)
            return if (close > 0) value.substring(1, close) else value.substring(1)
        }
        return value.replace(Regex("""\s+#.*$"""), "").trim()
    }

    /** service name -> (environment key -> unquoted value), reading only each service's `environment:` block. */
    private fun parseComposeEnvironments(text: String): Map<String, Map<String, String>> {
        val services = linkedMapOf<String, MutableMap<String, String>>()
        var inServices = false
        var current: String? = null
        var inEnvironment = false
        val mapEntry = Regex("""^([A-Za-z0-9_]+):\s*(.*)$""")
        val listEntry = Regex("""^-\s*([A-Za-z0-9_]+)=(.*)$""")
        for (line in text.lines()) {
            val body = line.trim()
            if (body.isEmpty() || body.startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            when {
                indent == 0 -> {
                    inServices = body == "services:"
                    current = null
                    inEnvironment = false
                }
                !inServices -> {}
                indent == 2 -> {
                    current = body.removeSuffix(":")
                    services.getOrPut(current) { linkedMapOf() }
                    inEnvironment = false
                }
                current == null -> {}
                indent == 4 -> inEnvironment = body == "environment:"
                indent >= 6 && inEnvironment -> {
                    val map = mapEntry.matchEntire(body)
                    val list = listEntry.matchEntire(body)
                    if (map != null) {
                        services.getValue(current)[map.groupValues[1]] = unquoteYamlScalar(map.groupValues[2])
                    } else if (list != null) {
                        services.getValue(current)[list.groupValues[1]] = unquoteYamlScalar(list.groupValues[2])
                    }
                }
            }
        }
        return services
    }

    /** Resolve a container path the way the image does: relative values hang off WORKDIR /app. */
    private fun resolveAgainstApp(path: String): String {
        val absolute = if (path.startsWith("/")) path else "/app/$path"
        val parts = ArrayDeque<String>()
        for (segment in absolute.split("/")) {
            when (segment) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun composeServices(): Map<String, Map<String, String>> {
        val services = parseComposeEnvironments(composeText())
        assertTrue(services.isNotEmpty(), "docker-compose.yml: no services parsed -- the compose parser or the file is broken")
        return services
    }

    // ---- P6 harness proofs: the parsers above must preserve the shapes the assertions rely on ------

    @Test
    fun `P6 compose parser keeps quoting, comments, CRLF, list form and absent versus empty keys distinct`() {
        val text =
            listOf(
                "services:",
                "  alpha:",
                "    build:",
                "      context: .",
                "    environment:",
                "      # a comment line",
                "      DATABASE_PATH: data/current-tasks.db",
                "      MCP_SERVER_NAME: \"x\"   # trailing comment",
                "      EMPTY_ONE:",
                "      MCP_HTTP_PORT: \"3001\"",
                "  beta:",
                "    environment:",
                "      - DATABASE_PATH=/app/data/other.db",
                "  gamma:",
                "    image: foo",
            ).joinToString("\r\n")

        val parsed = parseComposeEnvironments(text)

        assertEquals(listOf("alpha", "beta", "gamma"), parsed.keys.toList())
        assertEquals("data/current-tasks.db", parsed.getValue("alpha")["DATABASE_PATH"])
        assertEquals("x", parsed.getValue("alpha")["MCP_SERVER_NAME"], "quotes and trailing comment must be stripped")
        assertEquals("", parsed.getValue("alpha")["EMPTY_ONE"], "set-but-empty must stay distinct from absent")
        assertTrue("MCP_SERVER_NAME" !in parsed.getValue("beta"), "absent key must stay absent")
        assertEquals("/app/data/other.db", parsed.getValue("beta")["DATABASE_PATH"])
        assertTrue(parsed.getValue("gamma").isEmpty(), "a service with no environment block has no keys")
        assertTrue("context" !in parsed.getValue("alpha"), "keys outside environment: must not leak in")
    }

    @Test
    fun `P6 relative and absolute DATABASE_PATH forms resolve to the same container path`() {
        assertEquals("/app/data/current-tasks.db", resolveAgainstApp("data/current-tasks.db"))
        assertEquals("/app/data/current-tasks.db", resolveAgainstApp("/app/data/current-tasks.db"))
        assertEquals("/app/data/current-tasks.db", resolveAgainstApp("./data//current-tasks.db"))
        assertEquals("/app/data/current-tasks.db", resolveAgainstApp("data/../data/current-tasks.db"))
        assertTrue(resolveAgainstApp("data/tasks.db") != resolveAgainstApp("data/current-tasks.db"))
    }

    @Test
    fun `P6 AppConfig fromEnv reads the env names this suite compares against`() {
        val overridden =
            AppConfig.fromEnv {
                when (it) {
                    "MCP_SERVER_NAME" -> "probe-server-name"
                    "DATABASE_PATH" -> "probe/path.db"
                    else -> null
                }
            }
        assertEquals("probe-server-name", overridden.mcpServerName, "MCP_SERVER_NAME must feed AppConfig.mcpServerName")
        assertEquals("probe/path.db", overridden.databasePath, "DATABASE_PATH must feed AppConfig.databasePath")

        val defaults = AppConfig.fromEnv { null }
        assertTrue(defaults.mcpServerName.isNotBlank(), "default mcpServerName must not be blank")
        assertTrue(defaults.databasePath.isNotBlank(), "default databasePath must not be blank")
        assertTrue(defaults.mcpServerName != "probe-server-name" && defaults.databasePath != "probe/path.db")
    }

    // ---- P6 S1: compose DATABASE_PATH parity with the application default -------------------------

    @Test
    fun `P6 S1 every compose service DATABASE_PATH resolves to the application default database path`() {
        val expected = resolveAgainstApp(AppConfig.fromEnv { null }.databasePath)
        val services = composeServices()

        val withPath = services.filter { "DATABASE_PATH" in it.value }
        assertTrue(
            withPath.isNotEmpty(),
            "docker-compose.yml: no service sets DATABASE_PATH -- the parity comparison would be vacuous",
        )

        val mismatches =
            withPath
                .map { (service, env) -> service to env.getValue("DATABASE_PATH") }
                .filter { (_, value) -> resolveAgainstApp(value) != expected }
                .map { (service, value) -> "$service: DATABASE_PATH=$value" }
        assertTrue(
            mismatches.isEmpty(),
            "compose DATABASE_PATH (resolved against /app) must equal the application default $expected: $mismatches",
        )
    }

    // ---- P6 S2: compose MCP_SERVER_NAME, where set, equals the application default -----------------

    @Test
    fun `P6 S2 a compose service that sets MCP_SERVER_NAME sets the application default`() {
        val expected = AppConfig.fromEnv { null }.mcpServerName
        val services = composeServices()

        val mismatches =
            services
                .filter { "MCP_SERVER_NAME" in it.value }
                .map { (service, env) -> service to env.getValue("MCP_SERVER_NAME") }
                .filter { (_, value) -> value != expected }
                .map { (service, value) -> "$service: MCP_SERVER_NAME=$value" }
        assertTrue(
            mismatches.isEmpty(),
            "a compose service that sets MCP_SERVER_NAME must set the application default '$expected' " +
                "(or omit the key): $mismatches",
        )
    }

    // ---- P6 S3: smithery / server.json / Dockerfile identity parity --------------------------------

    private fun imageRepository(reference: String): String {
        val colon = reference.lastIndexOf(':')
        return if (colon > reference.lastIndexOf('/')) reference.substring(0, colon) else reference
    }

    private fun smitheryImageReference(text: String): String {
        val candidates =
            Regex(""""([^"\s]+/[^"\s]+)"""")
                .findAll(text)
                .map { it.groupValues[1] }
                .filter { !it.startsWith("-") }
                .toList()
        assertEquals(1, candidates.size, "smithery.yaml: expected exactly one quoted image reference, found $candidates")
        return candidates.single()
    }

    private fun serverJsonRoot() = Json.parseToJsonElement(serverJsonText()).jsonObject

    private fun serverJsonOciIdentifiers(): List<String> =
        (serverJsonRoot()["packages"]?.jsonArray ?: fail("server.json has no 'packages' array"))
            .filter { it.jsonObject["registryType"]?.jsonPrimitive?.content == "oci" }
            .map { it.jsonObject["identifier"]!!.jsonPrimitive.content }

    @Test
    fun `P6 image reference helpers separate repository from tag`() {
        assertEquals("ghcr.io/o/r", imageRepository("ghcr.io/o/r:latest"))
        assertEquals("ghcr.io/o/r", imageRepository("ghcr.io/o/r:3.16.0"))
        assertEquals("ghcr.io/o/r", imageRepository("ghcr.io/o/r"))
        assertEquals("localhost:5000/o/r", imageRepository("localhost:5000/o/r:1"))
    }

    @Test
    fun `P6 S3 smithery image repository equals the server json oci repository`() {
        val smitheryRepo = imageRepository(smitheryImageReference(smitheryYamlText()))
        val identifiers = serverJsonOciIdentifiers()
        assertEquals(1, identifiers.size, "server.json: expected exactly one oci package, found $identifiers")
        val serverJsonRepo = imageRepository(identifiers.single())

        assertTrue(smitheryRepo.isNotBlank())
        assertEquals(
            serverJsonRepo,
            smitheryRepo,
            "smithery.yaml image repository must equal server.json's oci identifier repository " +
                "(tags may differ: smithery tracks :latest by design)",
        )
    }

    @Test
    fun `P6 S3 Dockerfile server name label equals the server json name`() {
        val labels =
            Regex("""io\.modelcontextprotocol\.server\.name="([^"]+)"""")
                .findAll(dockerfileText())
                .map { it.groupValues[1] }
                .toList()
        assertEquals(1, labels.size, "Dockerfile: expected exactly one io.modelcontextprotocol.server.name label, found $labels")

        val name = serverJsonRoot()["name"]?.jsonPrimitive?.content ?: fail("server.json has no top-level 'name'")
        assertEquals(name, labels.single(), "Dockerfile label io.modelcontextprotocol.server.name must equal server.json name")
    }

    // ---- P6 S4: smithery configSchema ---------------------------------------------------------------

    private fun leadingSpaces(line: String) = line.length - line.trimStart().length

    /** property name -> that property's block lines, for the single `properties:` mapping in smithery.yaml. */
    private fun smitheryConfigProperties(text: String): Map<String, List<String>> {
        val lines = text.lines()
        val starts = lines.indices.filter { lines[it].trim() == "properties:" }
        assertEquals(1, starts.size, "smithery.yaml: expected exactly one 'properties:' mapping, found ${starts.size}")
        val parentIndent = leadingSpaces(lines[starts.single()])
        val block =
            lines
                .drop(starts.single() + 1)
                .takeWhile { it.isBlank() || leadingSpaces(it) > parentIndent }
                .filter { it.isNotBlank() && !it.trim().startsWith("#") }
        if (block.isEmpty()) return emptyMap()
        val childIndent = block.minOf { leadingSpaces(it) }
        val result = linkedMapOf<String, MutableList<String>>()
        var key: String? = null
        for (line in block) {
            if (leadingSpaces(line) == childIndent) {
                key = line.trim().removeSuffix(":")
                result[key] = mutableListOf()
            } else {
                result.getValue(key!!).add(line)
            }
        }
        return result
    }

    @Test
    fun `P6 S4 smithery configSchema offers exactly dataVolume and its default is the server json volume`() {
        val properties = smitheryConfigProperties(smitheryYamlText())
        assertEquals(
            setOf("dataVolume"),
            properties.keys,
            "smithery.yaml configSchema.properties must be exactly [dataVolume]",
        )

        val default =
            properties
                .getValue("dataVolume")
                .firstNotNullOfOrNull { Regex("""^\s*default:\s*"?([^"\s]+)"?\s*$""").matchEntire(it)?.groupValues?.get(1) }
                ?: fail("smithery.yaml: dataVolume has no 'default:' entry")

        val volumes =
            (serverJsonRoot()["packages"]?.jsonArray ?: fail("server.json has no 'packages' array"))
                .flatMap { it.jsonObject["runtimeArguments"]?.jsonArray.orEmpty() }
                .map { it.jsonObject }
                .filter { it["name"]?.jsonPrimitive?.content.let { n -> n == "-v" || n == "--volume" } }
                .map { it["value"]?.jsonPrimitive?.content.orEmpty() }
                .filter { it.contains(":/app/data") }
                .map { it.substringBefore(":/app/data") }
        assertEquals(1, volumes.size, "server.json: expected exactly one -v data-volume mount at /app/data, found $volumes")

        assertEquals(
            volumes.single(),
            default,
            "smithery.yaml dataVolume default must equal the volume name in server.json's -v mount",
        )
    }

    @Test
    fun `P6 smithery properties reader reports keys and blocks faithfully`() {
        val text =
            listOf(
                "startCommand:",
                "  configSchema:",
                "    properties:",
                "      one:",
                "        type: string",
                "        default: \"v1\"",
                "      # comment",
                "      two:",
                "        type: string",
                "  commandFunction: |-",
                "    properties:",
            ).joinToString("\n")
        // A second 'properties:' occurrence must be rejected, never silently merged or skipped.
        val failed = runCatching { smitheryConfigProperties(text) }.isFailure
        assertTrue(failed, "two 'properties:' mappings must be an error, not ignored")

        val single = text.lines().dropLast(1).joinToString("\n")
        val parsed = smitheryConfigProperties(single)
        assertEquals(listOf("one", "two"), parsed.keys.toList())
        assertTrue(parsed.getValue("one").any { it.contains("default: \"v1\"") })
        assertTrue(parsed.getValue("two").none { it.contains("default") })
    }

    // ---- P6 S5/S6/S7: the Environment Variable Reference table --------------------------------------

    private val envTableHeader = "| Variable | Default | Documented in |"
    private val envTableNamePattern = Regex("^`([A-Z][A-Z0-9_]*)`$")

    /** First-cell text of every data row of the section's single table (blank line ends the table). */
    private fun envReferenceFirstCells(docText: String): List<String> {
        val lines = docText.lines()
        val starts = lines.indices.filter { lines[it].trimEnd() == "## Environment Variable Reference" }
        if (starts.size != 1) fail("expected exactly one '## Environment Variable Reference' heading, found ${starts.size}")
        val after = lines.drop(starts.single() + 1)
        val section = after.takeWhile { !it.startsWith("## ") }

        val headerIndexes = section.indices.filter { section[it].trimStart().startsWith("| Variable") }
        if (headerIndexes.size != 1) fail("the section must hold exactly one table, found ${headerIndexes.size} header row(s)")
        val header = section[headerIndexes.single()].trimEnd()
        if (header != envTableHeader) fail("table header must be exactly '$envTableHeader' but is '$header'")

        val rows = section.drop(headerIndexes.single() + 1).takeWhile { it.isNotBlank() }
        if (rows.isEmpty()) fail("the table has no separator row and no data rows")
        if (!Regex(
                """^\|[\s:|-]+\|$"""
            ).matches(rows.first().trim())
        ) {
            fail("row after the header must be a separator row: '${rows.first()}'")
        }
        val dataRows = rows.drop(1)
        if (dataRows.isEmpty()) fail("the table has no data rows")

        return dataRows.map { row ->
            val trimmed = row.trim()
            if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) fail("table row is not pipe-delimited: '$row'")
            val cells = trimmed.removePrefix("|").removeSuffix("|").split("|")
            if (cells.size != 3) fail("table row must have exactly 3 cells (Variable | Default | Documented in): '$row'")
            cells.first().trim()
        }
    }

    /** Problems with the first cells: malformed name, duplicate name, or a name no source reads. */
    private fun envTableProblems(
        firstCells: List<String>,
        known: Set<String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val names = mutableListOf<String>()
        firstCells.forEach { cell ->
            val match = envTableNamePattern.matchEntire(cell)
            if (match == null) problems += "malformed first cell: '$cell'" else names += match.groupValues[1]
        }
        names
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .keys
            .forEach { problems += "duplicate row: $it" }
        names.filter { it !in known }.distinct().forEach { problems += "unknown variable (no source reads it): $it" }
        return problems
    }

    private fun envTableNames(): List<String> {
        val cells = envReferenceFirstCells(readRepoFile("current/docs/fleet-deployment.md"))
        return cells.map { cell ->
            envTableNamePattern.matchEntire(cell)?.groupValues?.get(1) ?: fail("malformed first cell: '$cell'")
        }
    }

    @Test
    fun `P6 S5 the widened const pattern scrapes both _ENV and _VAR constants`() {
        val known = knownEnvKeys()
        val retention =
            setOf(
                "RETENTION_CALL_LOG_DAYS",
                "RETENTION_EVENTS_DAYS",
                "RETENTION_AUDIT_EVENTS_DAYS",
                "RETENTION_LEASE_HISTORY_DAYS",
            )
        assertTrue(known.containsAll(retention), "the _VAR constants must be scraped: missing ${retention - known}")
        assertTrue("RESOURCE_LEASES_ENFORCED" in known, "the _ENV constants must still be scraped")
    }

    @Test
    fun `P6 S5 Environment Variable Reference holds one well formed table of variables the source reads`() {
        val cells = envReferenceFirstCells(readRepoFile("current/docs/fleet-deployment.md"))
        assertTrue(cells.isNotEmpty(), "the table must have at least one row")

        val problems = envTableProblems(cells, knownEnvKeys())
        assertTrue(problems.isEmpty(), "Environment Variable Reference table problems: $problems")
    }

    @Test
    fun `P6 S5 table excludes client side and refusal only variables and expands RETENTION to four names`() {
        val names = envTableNames()

        val clientSide = names.filter { it.startsWith("TASK_ORCHESTRATOR_") }
        assertTrue(clientSide.isEmpty(), "client-side TASK_ORCHESTRATOR_* variables must not be listed: $clientSide")

        val legacy = names.filter { it == "USE_FLYWAY" || it == "DEGRADED_MODE_POLICY" }
        assertTrue(legacy.isEmpty(), "refusal-only legacy variables must not be listed: $legacy")

        val retention = names.filter { it.startsWith("RETENTION_") }.toSet()
        assertEquals(
            setOf(
                "RETENTION_CALL_LOG_DAYS",
                "RETENTION_EVENTS_DAYS",
                "RETENTION_AUDIT_EVENTS_DAYS",
                "RETENTION_LEASE_HISTORY_DAYS",
            ),
            retention,
            "RETENTION_* must be expanded to exactly its four names, with no wildcard row",
        )
    }

    @Test
    fun `P6 S6 server json environment variables are unique and equal the table names`() {
        val tableNames = envTableNames()
        val jsonNames = serverJsonEnvKeys()

        val duplicates =
            jsonNames
                .groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
                .keys
        assertTrue(duplicates.isEmpty(), "server.json environmentVariables has duplicate name(s): $duplicates")

        val tableSet = tableNames.toSet()
        val jsonSet = jsonNames.toSet()
        val missing = (tableSet - jsonSet).sorted()
        val extra = (jsonSet - tableSet).sorted()
        assertTrue(
            missing.isEmpty() && extra.isEmpty(),
            "server.json environmentVariables must equal the Environment Variable Reference table names. " +
                "missing from server.json: $missing; in server.json but not in the table: $extra",
        )
    }

    // ---- P6 S7: the table checks fail loudly on bad input (synthetic documents) ---------------------

    @Test
    fun `P6 S7 an unknown variable row is reported by name`() {
        val problems =
            envTableProblems(
                listOf("`AGENT_CONFIG_DIR`", "`NOT_A_REAL_VARIABLE`"),
                known = setOf("AGENT_CONFIG_DIR"),
            )
        assertEquals(1, problems.size, "exactly the unknown row must be reported: $problems")
        assertTrue(problems.single().contains("NOT_A_REAL_VARIABLE"), "the offending name must appear: $problems")
    }

    @Test
    fun `P6 S7 malformed first cells and duplicate rows are failures not skips`() {
        val known = setOf("FOO", "BAR")
        val malformed =
            listOf("`foo`", "FOO", "`FOO` `BAR`", "`FOO BAR`", "``", "", "`1FOO`", "`FOO*`", "RETENTION_*")
        malformed.forEach { cell ->
            val problems = envTableProblems(listOf("`BAR`", cell), known)
            assertTrue(
                problems.any { it.startsWith("malformed first cell") },
                "first cell '$cell' must be flagged malformed, got $problems",
            )
        }

        val duplicate = envTableProblems(listOf("`FOO`", "`BAR`", "`FOO`"), known)
        assertTrue(duplicate.any { it.contains("duplicate") && it.contains("FOO") }, "a duplicate row must be flagged: $duplicate")

        assertTrue(envTableProblems(listOf("`FOO`", "`BAR`"), known).isEmpty(), "a clean table must produce no problems")
    }

    @Test
    fun `P6 S7 the table reader enforces heading, header, single table, and blank line termination`() {
        fun doc(vararg body: String) = body.joinToString("\r\n")

        val good =
            doc(
                "# Guide",
                "## Environment Variable Reference",
                "",
                "Intro text.",
                "",
                envTableHeader,
                "|---|---|---|",
                "| `FOO` | `1` | here |",
                "| `BAR` | *(none)* | there |",
                "",
                "| `NOT_IN_THE_TABLE` | `x` | after the blank line |",
                "## Next Section",
                "| `ALSO_NOT` | `x` | y |",
            )
        assertEquals(listOf("`FOO`", "`BAR`"), envReferenceFirstCells(good), "rows end at the first blank line")

        val noHeading = doc("## Something Else", envTableHeader, "|---|---|---|", "| `FOO` | 1 | x |")
        val twoTables =
            doc(
                "## Environment Variable Reference",
                envTableHeader,
                "|---|---|---|",
                "| `FOO` | 1 | x |",
                "",
                envTableHeader,
                "|---|---|---|",
                "| `BAR` | 1 | x |",
            )
        val wrongHeader =
            doc("## Environment Variable Reference", "| Name | Default | Documented in |", "|---|---|---|", "| `FOO` | 1 | x |")
        val noRows = doc("## Environment Variable Reference", envTableHeader, "|---|---|---|", "")
        val badArity = doc("## Environment Variable Reference", envTableHeader, "|---|---|---|", "| `FOO` | 1 |")
        val notPiped = doc("## Environment Variable Reference", envTableHeader, "|---|---|---|", "FOO 1 x")
        val duplicateHeading =
            doc(
                "## Environment Variable Reference",
                "## Environment Variable Reference",
                envTableHeader,
                "|---|---|---|",
                "| `FOO` | 1 | x |"
            )

        mapOf(
            "missing heading" to noHeading,
            "two tables" to twoTables,
            "wrong header" to wrongHeader,
            "no data rows" to noRows,
            "wrong cell count" to badArity,
            "row not pipe-delimited" to notPiped,
            "duplicate heading" to duplicateHeading,
        ).forEach { (label, text) ->
            assertTrue(runCatching { envReferenceFirstCells(text) }.isFailure, "$label must fail, not silently yield rows")
        }
    }

    // ---- P6 S8: no stale "v3" release strings in shipped source or the Dockerfile ---------------------

    private val staleV3Pattern = Regex("""Current\s*\(v3\)|\bv3\s+Current\b""", RegexOption.IGNORE_CASE)

    @Test
    fun `P6 S8 the stale v3 matcher catches both historical spellings and spares 4 0 wording`() {
        assertTrue(staleV3Pattern.containsMatchIn("Current (v3) MCP Task Orchestrator"))
        assertTrue(staleV3Pattern.containsMatchIn("# Runtime image (v3 Current - active)"))
        assertTrue(staleV3Pattern.containsMatchIn("current (v3)"))
        assertTrue(!staleV3Pattern.containsMatchIn("Current (4.0) MCP Task Orchestrator"))
        assertTrue(!staleV3Pattern.containsMatchIn("# Runtime image (4.0 - active)"))
    }

    @Test
    fun `P6 S8 no Current v3 string remains in main Kotlin source or the Dockerfile`() {
        val root = repoRoot()
        val mainSrc = root.resolve("current/src/main/kotlin")
        if (!Files.isDirectory(mainSrc)) fail("main source dir not found at $mainSrc")

        // db/migration is excluded by design: V1's header comment is frozen (a changed body changes its
        // Flyway checksum). It is a resource, not Kotlin, but the exclusion is explicit and path-based.
        fun isMigration(path: Path) = path.toString().replace('\\', '/').contains("db/migration")

        val files =
            Files.walk(mainSrc).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") && !isMigration(it) }
                    .toList()
            } + listOf(root.resolve("Dockerfile"))
        assertTrue(files.size > 1, "scan found no Kotlin sources under $mainSrc")

        val offenders =
            files.flatMap { file ->
                Files
                    .readAllLines(file)
                    .withIndex()
                    .filter { staleV3Pattern.containsMatchIn(it.value) }
                    .map { "${root.relativize(file).toString().replace('\\', '/')}:${it.index + 1}: ${it.value.trim()}" }
            }
        assertTrue(
            offenders.isEmpty(),
            "stale 'Current (v3)' wording remains (use 4.0 wording; never edit db/migration): $offenders",
        )
    }

    // ---- P6 S9: a manifest-only edit must invalidate the cached test result --------------------------

    @Test
    fun `P6 S9 Gradle test inputs declare every file this suite reads`() {
        val gradle = readRepoFile("current/build.gradle.kts")
        val required =
            listOf(
                "Dockerfile",
                "docker-compose.yml",
                "server.json",
                "smithery.yaml",
                "current/docs/fleet-deployment.md",
            )
        val missing = required.filter { !gradle.contains("rootProject.file(\"$it\")") }
        assertTrue(
            missing.isEmpty(),
            "current/build.gradle.kts tasks.test inputs.files must declare: $missing (else a docs/manifest-only " +
                "edit leaves :current:test UP-TO-DATE)",
        )
    }
}
