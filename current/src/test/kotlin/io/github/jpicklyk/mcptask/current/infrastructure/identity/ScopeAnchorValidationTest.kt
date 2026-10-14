package io.github.jpicklyk.mcptask.current.infrastructure.identity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthMode
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Security
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 754ae3dc, run r-202610132225-754ae3dc) for anchor validation of principal scopes:
 * scenarios S11 and S12 of the frozen test-plan, plus the root test they share.
 *
 * Oracles (task-scope item 5 and decisions D1, D2; api-rest.md section 3 public text; never read from a run):
 *  - D1 root test for an anchor: a stamped depth-0 item (`parentId == null && rootId == id`). A MISSING anchor is accepted and
 *    admits nothing (fail closed); only an EXISTING non-root anchor fails. An unstamped depth-0 row is not a stamped root.
 *  - JWT (D2): a credential whose `to_scope.root_ids` holds an existing non-root anchor resolves to null (the same 401
 *    `invalid_token` as any bad token), with ONE WARN per `sub` that names the sub and the anchor and never the token.
 *  - A credential whose anchors are roots, or unknown ids, resolves; an unknown anchor then admits nothing (every get is
 *    `forbidden{root}`), because the stamped root_id can never equal it.
 *  - Bearer anchors are checked once at server startup (D2). The token-file loader itself stays pure (alternative (b) of the
 *    task-scope was rejected): a non-root anchor in the file still loads.
 *
 * NOT COVERED here (escalated, see the manifest): the bearer startup error text and the startup order. The check is private to
 * the composition root and the only seam the declarations name is the private `resolveApiWiring`, which reads its API settings
 * from the process environment; no declared test seam exists, and reading the implementation is banned.
 *
 * Fixture invariants satisfied by construction: roots are stamped `root_id = id`; a child's depth is its parent's depth + 1 and its
 * `root_id` is its parent's root; JWT fixtures carry the configured issuer and audience and a whole-second `exp` inside the 24h
 * cap and are signed with the key the mocked key provider publishes.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ScopeAnchorValidationTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("anchor-validation-key").generate()
        private const val ISSUER = "https://idp.test.example"
        private const val AUDIENCE = "task-orchestrator-api"
    }

    private val rootR = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val childC = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
    private val grandG = UUID.fromString("00000000-0000-4000-8000-0000000000a3")
    private val rootB = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
    private val orphan = UUID.fromString("00000000-0000-4000-8000-0000000000c1")
    private val unknown = UUID.fromString("00000000-0000-4000-8000-0000000000ee")

    private val repo: WorkItemRepository get() = db.repositoryProvider().workItemRepository()

    private suspend fun seed(
        id: UUID,
        parent: UUID? = null,
        stamped: Boolean = true
    ) {
        val p = parent?.let { checkNotNull(repo.getById(it)) }
        repo.create(
            WorkItem(
                id = id,
                title = "item-${id.toString().takeLast(2)}",
                parentId = parent,
                depth = (p?.depth ?: -1) + 1,
                rootId = if (!stamped) null else (p?.let { it.rootId ?: it.id } ?: id)
            )
        )
    }

    /** R > C > G, a second root B, and an unstamped depth-0 row. */
    private suspend fun seedTree() {
        seed(rootR)
        seed(childC, rootR)
        seed(grandG, childC)
        seed(rootB)
        seed(orphan, stamped = false)
    }

    // ------------------------------------------------------------------ the shared root test

    @Test
    fun `S11 nonRoots returns the existing anchors that are not stamped depth-0 roots`(): Unit =
        runBlocking {
            seedTree()
            assertEquals(emptySet(), ScopeGuard.nonRoots(setOf(rootR), repo), "a stamped depth-0 root is a root")
            assertEquals(emptySet(), ScopeGuard.nonRoots(setOf(rootR, rootB), repo))
            assertEquals(setOf(childC), ScopeGuard.nonRoots(setOf(childC), repo), "a depth-1 item is a non-root")
            assertEquals(setOf(grandG), ScopeGuard.nonRoots(setOf(grandG), repo), "a depth-2 item is a non-root")
            assertEquals(setOf(childC), ScopeGuard.nonRoots(setOf(rootR, childC), repo), "only the non-root of a mixed set is named")
            assertEquals(setOf(childC, grandG), ScopeGuard.nonRoots(setOf(childC, grandG, rootB), repo))
            assertEquals(setOf(orphan), ScopeGuard.nonRoots(setOf(orphan), repo), "D1: an unstamped depth-0 row is not a stamped root")
        }

    @Test
    fun `S11 nonRoots accepts a missing anchor and an empty set`(): Unit =
        runBlocking {
            seedTree()
            assertEquals(emptySet(), ScopeGuard.nonRoots(setOf(unknown), repo), "D1: a missing anchor is accepted, not an error")
            assertEquals(
                setOf(childC),
                ScopeGuard.nonRoots(setOf(unknown, childC, rootR), repo),
                "a missing anchor does not mask a non-root"
            )
            assertEquals(emptySet(), ScopeGuard.nonRoots(emptySet(), repo))
        }

    @Test
    fun `S11 the token file loader stays pure - a non-root anchor in the file still loads`(): Unit =
        runBlocking {
            val raw = "bearer-anchor-token"
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(raw.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            val file = dir.resolve("tokens.yaml").toFile()
            file.writeText(
                "version: 1\ntokens:\n" +
                    "  - id: anchored\n" +
                    "    token_sha256: \"$digest\"\n" +
                    "    scope:\n" +
                    "      root_ids:\n" +
                    "        - \"$childC\"\n" +
                    "      tags_include: []\n" +
                    "    capabilities: [read]\n"
            )
            val entries = BearerTokenStore(file.absolutePath).loadWithEntries()
            assertEquals(1, entries.size)
            assertEquals(
                setOf(childC),
                entries.values
                    .single()
                    .principal.scope.rootIds,
                "the anchor is carried as listed"
            )
        }

    // ------------------------------------------------------------------ JWT fixtures

    private val jwksConfig =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = ISSUER,
            audience = AUDIENCE,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300
        )

    private fun keyProvider(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        coEvery { provider.getKeySet() } returns result
        coEvery { provider.getKeySetForIssuer(any()) } returns result
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun verifier() = JwksApiVerifier(jwksConfig, keyProvider())

    private fun jwt(
        sub: String,
        rootIds: List<String>?
    ): String {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(sub)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.ofEpochSecond(Instant.now().epochSecond + 300)))
        if (rootIds != null) builder.claim("to_scope", mapOf("root_ids" to rootIds))
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("anchor-validation-key").build(), builder.build())
        signed.sign(RSASSASigner(rsaKey))
        return signed.serialize()
    }

    private fun resolver() = CredentialResolver(emptyMap(), verifier(), nonRootAnchors = { ids -> ScopeGuard.nonRoots(ids, repo) })

    private fun newSub(): String = "sub-" + UUID.randomUUID()

    private suspend fun <T> withWarns(block: suspend () -> T): Pair<T, List<String>> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                root.addAppender(it)
            }
        val savedLevel = root.level
        root.level = Level.WARN
        try {
            val value = block()
            return value to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            root.detachAppender(appender)
            root.level = savedLevel
        }
    }

    private fun Outcome<Unit>.assertForbiddenRoot() {
        assertTrue(this is Outcome.Err, "expected an Err but was $this")
        val error = (this as Outcome.Err).error
        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertEquals(ForbiddenScope.ROOT, (error.detail as ErrorDetail.Forbidden).scope)
    }

    // ------------------------------------------------------------------ S12 JWT anchors

    @Test
    fun `S12 a JWT whose root_ids names an existing non-root resolves to null, a root anchor resolves`(): Unit =
        runBlocking {
            seedTree()
            val resolver = resolver()
            assertNull(resolver.resolve(jwt(newSub(), listOf(childC.toString()))), "a depth-1 anchor is rejected")
            assertNull(resolver.resolve(jwt(newSub(), listOf(grandG.toString()))), "a depth-2 anchor is rejected")
            assertNull(
                resolver.resolve(jwt(newSub(), listOf(rootR.toString(), childC.toString()))),
                "one non-root rejects the whole credential"
            )
            assertNull(resolver.resolve(jwt(newSub(), listOf(orphan.toString()))), "D1: an unstamped depth-0 row is not a stamped root")

            val ok = resolver.resolve(jwt(newSub(), listOf(rootR.toString())))
            assertNotNull(ok, "control: the same credential shape with a root anchor resolves")
            assertEquals(setOf(rootR), ok.principal.scope.rootIds)
            val two = resolver.resolve(jwt(newSub(), listOf(rootR.toString(), rootB.toString())))
            assertEquals(setOf(rootR, rootB), assertNotNull(two).principal.scope.rootIds)
        }

    @Test
    fun `S12 an unrestricted JWT and a JWT without to_scope are unaffected by the anchor check`(): Unit =
        runBlocking {
            seedTree()
            val resolver = resolver()
            val none = assertNotNull(resolver.resolve(jwt(newSub(), null)))
            assertNull(none.principal.scope.rootIds)
        }

    @Test
    fun `S12 a JWT whose root_ids names an unknown id resolves to a principal that is forbidden everywhere`(): Unit =
        runBlocking {
            seedTree()
            val principal = assertNotNull(resolver().resolve(jwt(newSub(), listOf(unknown.toString())))).principal
            assertEquals(setOf(unknown), principal.scope.rootIds)
            for (id in listOf(rootR, childC, grandG, rootB, orphan, unknown)) {
                ScopeGuard.check(principal.scope, id, repo).assertForbiddenRoot()
            }
            // control: a principal anchored on a real root is admitted on its own tree
            val anchored = assertNotNull(resolver().resolve(jwt(newSub(), listOf(rootR.toString())))).principal
            assertTrue(ScopeGuard.check(anchored.scope, grandG, repo) is Outcome.Ok)
            assertTrue(ScopeGuard.check(anchored.scope, rootB, repo) is Outcome.Err)
        }

    @Test
    fun `S12 the anchor is judged on the parsed UUID, so an upper-case spelling of a non-root is rejected too`(): Unit =
        runBlocking {
            seedTree()
            val resolver = resolver()
            assertNull(resolver.resolve(jwt(newSub(), listOf(childC.toString().uppercase()))))
            // the 8-hex prefix is not a UUID: the claim parser rejects it before any anchor check
            assertNull(resolver.resolve(jwt(newSub(), listOf(childC.toString().take(8)))))
            assertNotNull(resolver.resolve(jwt(newSub(), listOf(rootR.toString().uppercase()))), "control: upper-case root resolves")
        }

    @Test
    fun `S12 one WARN per sub names the sub and the anchor, never the token, and a second sub warns again`(): Unit =
        runBlocking {
            seedTree()
            val resolver = resolver()
            val subA = newSub()
            val subB = newSub()
            val tokenA1 = jwt(subA, listOf(childC.toString()))
            val tokenA2 = jwt(subA, listOf(childC.toString()))
            val tokenB = jwt(subB, listOf(grandG.toString()))

            val (results, warns) =
                withWarns {
                    listOf(
                        resolver.resolve(tokenA1),
                        resolver.resolve(tokenA2),
                        resolver.resolve(tokenA1),
                        resolver.resolve(tokenB)
                    )
                }
            assertTrue(results.all { it == null }, "every credential is rejected")

            val forA = warns.filter { subA in it }
            assertEquals(1, forA.size, "exactly one WARN for sub A over three requests: $warns")
            assertTrue(childC.toString() in forA.single(), "the WARN names the anchor: ${forA.single()}")
            val forB = warns.filter { subB in it }
            assertEquals(1, forB.size, "a different sub warns once of its own: $warns")
            assertTrue(grandG.toString() in forB.single(), "the WARN names sub B's anchor: ${forB.single()}")
            assertTrue(warns.none { tokenA1 in it || tokenA2 in it || tokenB in it }, "the raw token never reaches the log")
        }

    @Test
    fun `S12 a root-anchored JWT logs no WARN about its sub`(): Unit =
        runBlocking {
            seedTree()
            val sub = newSub()
            val (principal, warns) = withWarns { resolver().resolve(jwt(sub, listOf(rootR.toString()))) }
            assertNotNull(principal)
            assertTrue(warns.none { sub in it }, "no anchor WARN for a valid credential: $warns")
        }

    // ------------------------------------------------------------------ S12 over HTTP

    private fun emptyMcpServer(): Server =
        Server(
            serverInfo = Implementation(name = "anchor-validation-test", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
        )

    private fun io.ktor.server.application.Application.installAnchorTestApp() {
        installMcpStreamableHttp(emptyMcpServer(), appConfig = AppConfig.fromEnv(), mcpAuth = McpAuthMode.Local)
        val repositoryProvider = db.repositoryProvider()
        installRestApiRoutes(
            apiConfig = jwksConfig,
            eventBus = null,
            effectiveProvider = repositoryProvider,
            apiTokenEntries = emptyMap(),
            allowQueryToken = false,
            serverName = "anchor-validation-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = false,
            serverConfig = ServerConfigSource { null },
            toolContext =
                ToolExecutionContext(
                    repositoryProvider,
                    statusLabelService = NoOpStatusLabelService,
                    perRootConfigService = PerRootConfigService(repositoryProvider.configStore()),
                    unitOfWork = db.unitOfWork()
                ),
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            jwksVerifier = verifier(),
            appConfig = AppConfig.fromEnv()
        )
    }

    @Test
    fun `S12 a JWT anchored on a non-root is 401 invalid_token on a real route, a root anchor is 200`() =
        testApplication {
            seedTree()
            application { installAnchorTestApp() }

            val rejected =
                client.get("/api/v1/items/$rootR") {
                    header(HttpHeaders.Authorization, "Bearer ${jwt(newSub(), listOf(childC.toString()))}")
                }
            assertEquals(HttpStatusCode.Unauthorized, rejected.status)
            val wwwAuth = rejected.headers[HttpHeaders.WWWAuthenticate]
            assertNotNull(wwwAuth, "a 401 in jwks mode carries WWW-Authenticate")
            assertTrue(wwwAuth.contains("invalid_token"), "WWW-Authenticate names invalid_token, got: $wwwAuth")

            val accepted =
                client.get("/api/v1/items/$rootR") {
                    header(HttpHeaders.Authorization, "Bearer ${jwt(newSub(), listOf(rootR.toString()))}")
                }
            assertEquals(HttpStatusCode.OK, accepted.status, "control: the same route accepts a root-anchored credential")
        }
}
