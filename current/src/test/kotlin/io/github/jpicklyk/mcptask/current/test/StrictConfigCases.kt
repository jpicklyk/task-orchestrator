package io.github.jpicklyk.mcptask.current.test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * One ROOT-scope config document that carries exactly one defect, the RFC 6901 JSON Pointer of that defect, and the same
 * document with the defect repaired ([fixedYaml], which must be admitted with zero errors).
 *
 * The repaired twin is what makes each case non-vacuous: if the defect document were rejected for some unrelated
 * reason, the twin would be rejected too.
 *
 * [withinEntry] is true where RFC 6901 offers two equally valid locations for the same defect (the offending element
 * itself, or one of its members, e.g. `/notes/0` versus `/notes/0/role` for a missing member); the assertion then
 * accepts the element pointer or any pointer beneath it. It is false where the offending key or value has exactly one
 * location and the pointer must match exactly.
 */
data class StrictConfigCase(
    val name: String,
    val yaml: String,
    val errorPath: String,
    val fixedYaml: String,
    val withinEntry: Boolean = false,
    /** True when a 3.x parser skipped this defect softly (a finding in both parse modes), false for scope-only rules. */
    val parserFinding: Boolean = true
)

/** Shared fixtures for the Q16 strict-validation tests (item edae92cb). */
object StrictConfigCases {
    private const val BASE =
        "work_item_schemas:\n" +
            "  feature-task:\n" +
            "    notes:\n" +
            "      - key: spec\n" +
            "        role: queue\n" +
            "        required: true\n"

    /** A minimal document that is valid at ROOT scope under STRICT. */
    const val VALID_ROOT_YAML: String = BASE

    val ROOT_CASES: List<StrictConfigCase> =
        listOf(
            StrictConfigCase(
                name = "note missing role",
                yaml = "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        required: true\n",
                errorPath = "/work_item_schemas/feature-task/notes/0",
                fixedYaml = BASE,
                withinEntry = true
            ),
            StrictConfigCase(
                name = "note role Work (wrong case)",
                yaml = "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: Work\n        required: true\n",
                errorPath = "/work_item_schemas/feature-task/notes/0/role",
                fixedYaml =
                    "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: work\n" +
                        "        required: true\n"
            ),
            StrictConfigCase(
                name = "unknown note-entry key",
                yaml = "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        foo: bar\n",
                errorPath = "/work_item_schemas/feature-task/notes/0/foo",
                fixedYaml = "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n"
            ),
            StrictConfigCase(
                name = "unknown schema-level key",
                yaml = "work_item_schemas:\n  feature-task:\n    bogus: 1\n    notes: []\n",
                errorPath = "/work_item_schemas/feature-task/bogus",
                fixedYaml = "work_item_schemas:\n  feature-task:\n    notes: []\n"
            ),
            StrictConfigCase(
                name = "lifecycle bogus",
                yaml = "work_item_schemas:\n  feature-task:\n    lifecycle: bogus\n    notes: []\n",
                errorPath = "/work_item_schemas/feature-task/lifecycle",
                fixedYaml = "work_item_schemas:\n  feature-task:\n    lifecycle: auto\n    notes: []\n"
            ),
            StrictConfigCase(
                name = "lifecycle auto-reopen (removed)",
                yaml = "work_item_schemas:\n  feature-task:\n    lifecycle: auto-reopen\n    notes: []\n",
                errorPath = "/work_item_schemas/feature-task/lifecycle",
                fixedYaml = "work_item_schemas:\n  feature-task:\n    lifecycle: auto\n    notes: []\n"
            ),
            StrictConfigCase(
                name = "seat without a name",
                yaml =
                    "work_item_schemas:\n  feature-task:\n    seats:\n" +
                        "      - { name: impl, phase: work, enters: true }\n" +
                        "      - { phase: review }\n" +
                        "    notes: []\n",
                errorPath = "/work_item_schemas/feature-task/seats/1",
                fixedYaml =
                    "work_item_schemas:\n  feature-task:\n    seats:\n" +
                        "      - { name: impl, phase: work, enters: true }\n" +
                        "      - { name: rev, phase: review }\n" +
                        "    notes: []\n",
                withinEntry = true
            ),
            StrictConfigCase(
                name = "dispatch effort HIGH (wrong case)",
                yaml = "traits:\n  delegated:\n    dispatch:\n      work: { agent: some-agent, effort: HIGH }\n",
                errorPath = "/traits/delegated/dispatch/work/effort",
                fixedYaml = "traits:\n  delegated:\n    dispatch:\n      work: { agent: some-agent, effort: high }\n"
            ),
            StrictConfigCase(
                name = "schema_resolution (removed top-level key)",
                yaml = "schema_resolution: legacy\n$BASE",
                errorPath = "/schema_resolution",
                fixedYaml = BASE
            ),
            StrictConfigCase(
                name = "top-level orchestration (plugin key moved under extensions)",
                yaml = "orchestration:\n  mode: workflow\n$BASE",
                errorPath = "/orchestration",
                fixedYaml = "extensions:\n  claude-code:\n    orchestration:\n      mode: workflow\n$BASE",
                parserFinding = false
            ),
            StrictConfigCase(
                name = "top-level retrospective (plugin key moved under extensions)",
                yaml = "retrospective:\n  mode: dispatch\n$BASE",
                errorPath = "/retrospective",
                fixedYaml = "extensions:\n  claude-code:\n    retrospective:\n      mode: dispatch\n$BASE",
                parserFinding = false
            ),
            StrictConfigCase(
                name = "top-level actor_attribution (plugin key moved under extensions)",
                yaml = "actor_attribution:\n  required: true\n$BASE",
                errorPath = "/actor_attribution",
                fixedYaml = "extensions:\n  claude-code:\n    actor_attribution:\n      required: true\n$BASE",
                parserFinding = false
            )
        )

    /** A SERVER-scope registry entry with maxHolders 2 (a former soft skip); the pointer of the offending value. */
    const val SERVER_MAX_HOLDERS_YAML: String = "resources:\n  db-cred:\n    description: \"shared\"\n    maxHolders: 2\n"
    const val SERVER_MAX_HOLDERS_PATH: String = "/resources/db-cred/maxHolders"
    const val SERVER_MAX_HOLDERS_FIXED_YAML: String = "resources:\n  db-cred:\n    description: \"shared\"\n    maxHolders: 1\n"

    /** The 4.0 dogfood fixture (ROOT scope). */
    val dogfoodFixture: String get() = resource("/config/dogfood-4.0.yaml")

    /** The 4.0 global-floor fixture (SERVER scope). */
    val floorFixture: String get() = resource("/config/floor-4.0.yaml")

    fun resource(path: String): String {
        val stream = StrictConfigCases::class.java.getResourceAsStream(path) ?: error("test resource $path not found on the classpath")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /** The repository root: the nearest ancestor of the working directory holding both `claude-plugins/` and `current/`. */
    fun repoRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("claude-plugins")) && Files.isDirectory(dir.resolve("current"))) return dir
            dir = dir.parent
        }
        throw AssertionError("could not locate the repo root (a dir containing both 'claude-plugins/' and 'current/')")
    }
}
