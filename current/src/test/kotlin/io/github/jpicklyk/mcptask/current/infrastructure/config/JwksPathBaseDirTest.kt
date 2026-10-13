package io.github.jpicklyk.mcptask.current.infrastructure.config

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.DefaultJwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.toKeySource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Independent tests (test-author seat, item ad684e46 / Q17, scenario S10) for the injected base directory of
 * [DefaultJwksKeySetProvider]: a relative `jwks_path` resolves against the `configBaseDir` it is constructed with, not against
 * the process working directory and not through an environment read.
 *
 * Oracle: task-scope 1.7 ("`DefaultJwksKeySetProvider(config: VerifierConfig.Jwks, configBaseDir: Path, ...)`: `jwks_path`
 * resolves against `configBaseDir` (no env read)") and AR-41. The fixtures make the base directory the only place the file
 * can be found: the probe path carries a unique directory name that exists nowhere under the working directory, so a
 * provider that resolved against the working directory (or the former AGENT_CONFIG_DIR env fallback) fails to load it.
 *
 * Probes recorded as inapplicable: `..` traversal out of the base directory (the contract states no containment rule, so
 * there is no oracle for what it should do).
 */
class JwksPathBaseDirTest {
    @TempDir
    lateinit var tempDir: Path

    private val baseKey = RSAKeyGenerator(2048).keyID("base-dir-key").generate()
    private val elsewhereKey = RSAKeyGenerator(2048).keyID("elsewhere-key").generate()

    private fun writeJwks(
        file: Path,
        key: com.nimbusds.jose.jwk.RSAKey
    ): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, JWKSet(listOf(key.toPublicJWK())).toString())
        return file
    }

    private fun keyIds(
        baseDir: Path,
        jwksPath: String
    ): List<String?> {
        val provider = DefaultJwksKeySetProvider(VerifierConfig.Jwks(jwksPath = jwksPath).toKeySource(), baseDir)
        try {
            return runBlocking { provider.getKeySet() }.keys.keys.map { it.keyID }
        } finally {
            provider.close()
        }
    }

    private fun probePath() = "jwks-basedir-probe-${UUID.randomUUID()}/keys/jwks.json"

    @Test
    fun `S10 a relative jwks_path is loaded from the injected base directory`() {
        val relative = probePath()
        val base = tempDir.resolve("base")
        writeJwks(base.resolve(relative), baseKey)

        assertEquals(listOf("base-dir-key"), keyIds(base, relative))
    }

    @Test
    fun `S10 the same relative path under a different base directory is a different file`() {
        val relative = probePath()
        val first = tempDir.resolve("first")
        val second = tempDir.resolve("second")
        writeJwks(first.resolve(relative), baseKey)
        writeJwks(second.resolve(relative), elsewhereKey)

        assertEquals(listOf("base-dir-key"), keyIds(first, relative))
        assertEquals(listOf("elsewhere-key"), keyIds(second, relative))
    }

    @Test
    fun `S10 a relative jwks_path that exists only elsewhere is not found through the base directory`() {
        val relative = probePath()
        writeJwks(tempDir.resolve("elsewhere").resolve(relative), elsewhereKey)
        val emptyBase = tempDir.resolve("empty-base").also { Files.createDirectories(it) }

        assertFails("the file is not under the base directory, so loading must fail") { keyIds(emptyBase, relative) }
    }

    @Test
    fun `S10 an absolute jwks_path ignores the base directory`() {
        val absolute = writeJwks(tempDir.resolve("abs").resolve(probePath()), baseKey).toAbsolutePath()
        val unrelatedBase = tempDir.resolve("unrelated-base").also { Files.createDirectories(it) }

        assertEquals(listOf("base-dir-key"), keyIds(unrelatedBase, absolute.toString()))
    }
}
