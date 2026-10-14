package io.github.jpicklyk.mcptask.current.bootstrap

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Test-author seat 2 (item 754ae3dc), scenario S11 through the public composition root.
 * Oracle: supplementary declarations section A / task-scope D1, D2: during build(), after migration, a bearer entry
 * whose scope.root_ids anchor EXISTS must be a stamped depth-0 root; an existing non-root fails with
 * IllegalArgumentException "API token file: token '<tokenId>' scope.root_ids anchor <uuid> is not a depth-0 root";
 * a MISSING anchor and a root anchor are accepted.
 */
class ServerCompositionAnchorCheckTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val rootR = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
    private val childC = UUID.fromString("00000000-0000-4000-8000-0000000000d2")
    private val missing = UUID.fromString("00000000-0000-4000-8000-0000000000ee")

    private fun seedRootAndChild() =
        runBlocking {
            val repo = db.repositoryProvider().workItemRepository()
            repo.create(WorkItem(id = rootR, title = "root", parentId = null, depth = 0, rootId = rootR))
            repo.create(WorkItem(id = childC, title = "child", parentId = rootR, depth = 1, rootId = rootR))
        }

    private fun configWithAnchors(
        tokenId: String,
        vararg anchors: UUID
    ): AppConfig {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest("raw-$tokenId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val file = dir.resolve("tokens.yaml").toFile()
        file.writeText(
            "version: 1\ntokens:\n" +
                "  - id: $tokenId\n" +
                "    token_sha256: \"$digest\"\n" +
                "    scope:\n" +
                "      root_ids:\n" +
                anchors.joinToString("") { "        - \"$it\"\n" } +
                "      tags_include: []\n" +
                "    capabilities: [read]\n"
        )
        val env =
            mapOf(
                "API_ENABLED" to "true",
                "API_AUTH_MODE" to "bearer",
                "API_TOKENS_PATH" to file.absolutePath
            )
        return AppConfig.fromEnv { key -> env[key] }
    }

    private fun compose(config: AppConfig): CompositionResult =
        ServerComposition(
            appConfig = config,
            databaseManager = db.databaseManager,
            shutdownCoordinator = ShutdownCoordinator()
        ).build()

    @Test
    fun `S11 an existing non-root anchor fails startup naming the token and the anchor`() {
        seedRootAndChild()
        val ex = assertThrows<IllegalArgumentException> { compose(configWithAnchors("tok-nonroot", childC)) }
        assertEquals(
            "API token file: token 'tok-nonroot' scope.root_ids anchor $childC is not a depth-0 root",
            ex.message
        )
    }

    @Test
    fun `S11 a mixed anchor list names only the non-root one`() {
        seedRootAndChild()
        val ex = assertThrows<IllegalArgumentException> { compose(configWithAnchors("tok-mixed", rootR, childC)) }
        assertEquals(
            "API token file: token 'tok-mixed' scope.root_ids anchor $childC is not a depth-0 root",
            ex.message
        )
    }

    @Test
    fun `S11 a missing anchor starts - it admits nothing until it exists`() {
        seedRootAndChild()
        assertNotNull(compose(configWithAnchors("tok-missing", missing)))
    }

    @Test
    fun `S11 a stamped root anchor starts`() {
        seedRootAndChild()
        assertNotNull(compose(configWithAnchors("tok-root", rootR)))
    }
}
