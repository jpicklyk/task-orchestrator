package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent P5b guards (item c01d2e90): S2 (the production default is never weakened), S3-guard (the retired
 * markers and bridges are gone from production code) and S12 (the cancellation-safety baseline only shrinks).
 *
 * Oracles: S2 - task-scope section 6 ("Guard: OutsideUnitPolicy.IMPLICIT in src/main == 0"); S3-guard - task-scope D5
 * and D1 (inTransaction, the CascadeAbort marker, readResult/writeResult, Result/RepositoryError/DBError are deleted);
 * S12 - task-scope section 7 and carry-in TP1 (post-P5b bound <= 59 sites and <= 25 files, it only shrinks).
 * Red proof for S2: seed a production line mentioning OutsideUnitPolicy.IMPLICIT in a scratch copy (orchestrator-run).
 */
class P5bWriteUnitGuardsTest {
    private fun codeLines(text: String): List<String> =
        text
            .lines()
            .filter { GuardSupport.isCodeLine(it) }
            .map { GuardSupport.stripStrings(it).substringBefore("//") }

    private fun occurrences(
        sources: List<GuardSupport.Source>,
        token: Regex
    ): Map<String, Int> = GuardSupport.countByFile(sources) { s -> codeLines(s.text).count { token.containsMatchIn(it) } }

    // ---------------------------------------------------------------- S2
    @Test
    fun `S2 no production code line references OutsideUnitPolicy IMPLICIT`() {
        val hits = occurrences(GuardSupport.productionSources(), Regex("""\bOutsideUnitPolicy\.IMPLICIT\b"""))
        assertEquals(emptyMap(), hits, "OutsideUnitPolicy.IMPLICIT must exist only in test code")
    }

    @Test
    fun `S2 the detector itself fires on a seeded line and ignores comments and strings`() {
        val token = Regex("""\bOutsideUnitPolicy\.IMPLICIT\b""")
        assertEquals(1, codeLines("val p = OutsideUnitPolicy.IMPLICIT").count { token.containsMatchIn(it) })
        assertEquals(0, codeLines("// OutsideUnitPolicy.IMPLICIT is test only").count { token.containsMatchIn(it) })
        assertEquals(0, codeLines(" * OutsideUnitPolicy.IMPLICIT").count { token.containsMatchIn(it) })
        assertEquals(0, codeLines("val s = \"OutsideUnitPolicy.IMPLICIT\"").count { token.containsMatchIn(it) })
    }

    // ---------------------------------------------------------------- S3 guard
    @Test
    fun `S3 the retired transaction markers and Result bridges are absent from production code`() {
        val sources = GuardSupport.productionSources()
        val retired =
            mapOf(
                "inTransaction" to Regex("""\binTransaction\b"""),
                "CascadeAbort" to Regex("""\bCascadeAbort\b"""),
                "readResult" to Regex("""\breadResult\b"""),
                "writeResult" to Regex("""\bwriteResult\b"""),
                "RepositoryError import" to
                    Regex("""import\s+io\.github\.jpicklyk\.mcptask\.current\.(domain\.repository|application\.port)\.RepositoryError\b"""),
                "DBError" to Regex("""\bDBError\b"""),
                "PassthroughUnitOfWork" to Regex("""\bPassthroughUnitOfWork\b"""),
                "application.port.Result import" to
                    Regex("""import\s+io\.github\.jpicklyk\.mcptask\.current\.(domain\.repository|application\.port)\.Result\b""")
            )
        val offenders = retired.mapValues { (_, token) -> occurrences(sources, token) }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap(), offenders, "retired P5b constructs still referenced by production code")
        val deletedFiles = sources.map { it.path }.filter { it == "domain/repository/Result.kt" || it == "application/port/Result.kt" }
        assertEquals(emptyList(), deletedFiles, "task-scope D1: Result.kt (with RepositoryError) is deleted")
    }

    // ---------------------------------------------------------------- S12
    @Test
    fun `S12 the cancellation baseline is at most 59 sites in at most 25 files and never above its pre-P5b counts`() {
        val baseline = GuardSupport.readBaseline("cancellation-safety-baseline.txt")
        assertTrue(baseline.values.sum() <= 59, "baseline sum ${baseline.values.sum()} must be <= 59")
        assertTrue(baseline.size <= 25, "baseline file count ${baseline.size} must be <= 25")
        val grown = baseline.filter { (path, n) -> n > (PRE_P5B_BASELINE[path] ?: 0) }
        assertEquals(emptyMap(), grown, "no baseline entry may grow or appear beyond the pre-P5b (4df201d1) file")
    }

    @Test
    fun `S12 the burned-down P5b files carry no catch-all site in production code`() {
        val sources = GuardSupport.productionSources()
        val actual = GuardSupport.countByFile(sources) { CancellationSafetyTest.violations(it.text) }
        val burned =
            listOf(
                "infrastructure/sqlite/repository/SQLiteWorkItemRepository.kt",
                "infrastructure/sqlite/repository/SqliteItemStore.kt",
                "infrastructure/sqlite/repository/SqliteHierarchyStore.kt",
                "claim/internal/sqlite/SqliteClaimStore.kt",
                "knowledge/internal/sqlite/search/SqliteSearchEngine.kt",
                "platform/sqlite/repository/WorkItemRows.kt",
                "claim/internal/sqlite/SQLiteResourceLeaseRepository.kt",
                "interfaces/api/v1/routes/ItemRoutes.kt"
            )
        val present = sources.map { it.path }.toSet()
        burned.forEach { assertTrue(it in present, "burned file is not a production source (renamed or deleted?): $it") }
        assertEquals(emptyMap(), actual.filterKeys { it in burned }, "task-scope section 7: these files are fully burned down")
        assertTrue(actual.values.sum() <= 59, "actual site count ${actual.values.sum()} must be <= 59")
        assertTrue(actual.size <= 25, "actual file count ${actual.size} must be <= 25")
    }

    private companion object {
        /**
         * The ratchet file at base 4df201d1 (git show 4df201d1:current/src/test/resources/architecture/cancellation-safety-baseline.txt),
         * less the entries of the files Q32 deleted (the 3.x tool layer and its adapter), which no baseline line can name again,
         * and with CurrentMcpServer.kt at its Q32 path (moved from interfaces/mcp to bootstrap, unchanged),
         * and with the six identity files re-keyed to their paths in the identity slice (moved unchanged, counts unchanged),
         * and with the five SQLite engine, schema and helper files re-keyed to their paths under platform/sqlite (moved unchanged, counts unchanged),
         * and with ShutdownCoordinator.kt re-keyed to its path under platform/shutdown (moved unchanged, count unchanged),
         * and with ConfigSession.kt re-keyed to its path under platform/support (moved, count unchanged),
         * and with the four config files re-keyed to their paths in the config slice (moved unchanged, counts unchanged).
         */
        val PRE_P5B_BASELINE: Map<String, Int> =
            """
            CurrentMain.kt 1
            bootstrap/CurrentMcpServer.kt 9
            platform/support/ConfigSession.kt 1
            config/ConfigService.kt 1
            application/service/RoleTransitionHandler.kt 1
            application/service/WorkItemPlacementService.kt 4
            application/service/rest/WorkItemPatchProjection.kt 1
            config/internal/wire/GlobalConfigFile.kt 3
            identity/internal/wire/jwt/JwksKeySetProvider.kt 5
            config/internal/wire/YamlConfigDocumentParser.kt 1
            config/internal/wire/YamlStatusLabelService.kt 1
            platform/sqlite/DatabaseManager.kt 5
            platform/sqlite/StartupCompaction.kt 1
            platform/sqlite/StartupIntegrity.kt 1
            platform/sqlite/schema/management/FlywayDatabaseSchemaManager.kt 4
            platform/sqlite/repository/ProofClaimsSerialization.kt 1
            note/internal/sqlite/SQLiteNoteRepository.kt 1
            claim/internal/sqlite/SQLiteResourceLeaseRepository.kt 6
            infrastructure/sqlite/repository/SQLiteWorkItemRepository.kt 10
            platform/shutdown/ShutdownCoordinator.kt 1
            identity/BearerTokenStore.kt 2
            identity/JwksApiVerifier.kt 6
            interfaces/api/v1/mapping/Mappers.kt 1
            interfaces/api/v1/routes/DependencyRoutes.kt 2
            interfaces/api/v1/routes/DependencyWriteRoutes.kt 4
            interfaces/api/v1/routes/EffectiveConfigRoutes.kt 1
            interfaces/api/v1/routes/ItemRoutes.kt 6
            interfaces/api/v1/routes/ItemWriteRoutes.kt 7
            interfaces/api/v1/routes/NoteRoutes.kt 2
            interfaces/api/v1/routes/NoteWriteRoutes.kt 3
            interfaces/api/v1/routes/ProjectConfigRoutes.kt 1
            interfaces/api/v1/routes/QueryParams.kt 1
            interfaces/api/v1/routes/RuleRoutes.kt 1
            """.trimIndent()
                .lines()
                .associate { it.substringBeforeLast(' ') to it.substringAfterLast(' ').toInt() }
    }
}
