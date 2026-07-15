package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import org.jetbrains.exposed.v1.jdbc.Database
import java.security.MessageDigest
import java.util.UUID

/** Raw bearer token used in all test setups. */
const val TEST_TOKEN = "integration-test-token-abc123"

/** Token id corresponding to [TEST_TOKEN]. */
const val TEST_TOKEN_ID = "test-read-principal"

/** Admin token id for tests that need ADMIN capability. */
const val ADMIN_TOKEN = "integration-admin-token-xyz789"
const val ADMIN_TOKEN_ID = "test-admin-principal"

/**
 * Write-capable token for Phase 5 write-route tests. Granted READ + all write capabilities
 * (WRITE_ITEMS, WRITE_NOTES, ADVANCE, MANAGE_DEPENDENCIES, WRITE_CONFIG) but NOT admin.
 */
const val WRITE_TOKEN = "integration-write-token-w0987"
const val WRITE_TOKEN_ID = "test-write-principal"

fun sha256(input: String): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(input.toByteArray(Charsets.UTF_8))
}

/**
 * Builds a [DefaultRepositoryProvider] backed by an H2 in-memory DB.
 * The schema is set up via [DirectDatabaseSchemaManager].
 */
fun buildH2RepositoryProvider(): DefaultRepositoryProvider {
    val dbName = "api_test_${System.nanoTime()}"
    val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    val databaseManager = DatabaseManager(database)
    DirectDatabaseSchemaManager().updateSchema()
    return DefaultRepositoryProvider(databaseManager)
}

/**
 * Builds a [DefaultRepositoryProvider] backed by an H2 in-memory DB with a specific scope root.
 * Identical to [buildH2RepositoryProvider] — the scope restriction is on the token, not the repo.
 */
fun buildH2RepositoryProviderScoped(): DefaultRepositoryProvider = buildH2RepositoryProvider()

/**
 * Like [buildH2RepositoryProvider] but also returns the raw [Database] handle, for tests that
 * need to bypass the repository layer (e.g. writing a corrupt/invalid-domain row directly via
 * Exposed to simulate validation-drop scenarios — see [WorkItemsTable] usage in repository tests).
 */
fun buildH2RepositoryProviderWithDatabase(): Pair<DefaultRepositoryProvider, Database> {
    val dbName = "api_test_${System.nanoTime()}"
    val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    val databaseManager = DatabaseManager(database)
    DirectDatabaseSchemaManager().updateSchema()
    return DefaultRepositoryProvider(databaseManager) to database
}

/**
 * Returns a [ApiAuthConfig.Bearer] with two principals:
 * - [TEST_TOKEN] → `read` capability, unrestricted scope (optionally scoped by [scopeRootIds] and/or [tagsInclude])
 * - [ADMIN_TOKEN] → `admin` + `read` capabilities, unrestricted scope
 */
fun makeTestAuthConfig(
    scopeRootIds: Set<UUID>? = null,
    tagsInclude: Set<String> = emptySet(),
): ApiAuthConfig.Bearer {
    val readPrincipal =
        ApiPrincipal(
            tokenId = TEST_TOKEN_ID,
            scope = ApiScope(rootIds = scopeRootIds, tagsInclude = tagsInclude),
            capabilities = setOf(ApiCapability.READ),
            authMode = ApiAuthMode.BEARER,
        )
    val adminPrincipal =
        ApiPrincipal(
            tokenId = ADMIN_TOKEN_ID,
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = setOf(ApiCapability.READ, ApiCapability.ADMIN),
            authMode = ApiAuthMode.BEARER,
        )
    return ApiAuthConfig.Bearer(
        tokens =
            mapOf(
                HashBytes(sha256(TEST_TOKEN)) to readPrincipal,
                HashBytes(sha256(ADMIN_TOKEN)) to adminPrincipal,
            ),
    )
}

/**
 * Returns a [ApiAuthConfig.Bearer] with three principals (extends [makeTestAuthConfig] for
 * Phase 5 write-route tests):
 * - [TEST_TOKEN]  → `read` only, scope = [scopeRootIds]
 * - [ADMIN_TOKEN] → `admin` + `read`, unrestricted scope
 * - [WRITE_TOKEN] → `read` + all write capabilities, scope = [scopeRootIds]
 *
 * The WRITE principal shares [scopeRootIds] with the READ principal so scope-enforcement tests
 * can exercise write routes with a constrained scope.
 */
fun makeWriteAuthConfig(scopeRootIds: Set<UUID>? = null): ApiAuthConfig.Bearer {
    val base = makeTestAuthConfig(scopeRootIds)
    val writePrincipal =
        ApiPrincipal(
            tokenId = WRITE_TOKEN_ID,
            scope = ApiScope(rootIds = scopeRootIds, tagsInclude = emptySet()),
            capabilities =
                setOf(
                    ApiCapability.READ,
                    ApiCapability.WRITE_ITEMS,
                    ApiCapability.WRITE_NOTES,
                    ApiCapability.ADVANCE,
                    ApiCapability.MANAGE_DEPENDENCIES,
                    ApiCapability.WRITE_CONFIG,
                ),
            authMode = ApiAuthMode.BEARER,
        )
    return ApiAuthConfig.Bearer(
        tokens = base.tokens + (HashBytes(sha256(WRITE_TOKEN)) to writePrincipal),
    )
}

/**
 * Common Ktor application configuration for route integration tests.
 *
 * Installs [ContentNegotiation], [SSE], [ApiBearerAuth], and registers routes via [routeBlock].
 *
 * @param authConfig The auth config to install on `/api/v1`. Accepts any [ApiAuthConfig] variant
 *   (e.g. [ApiAuthConfig.Unauthenticated] for opt-in unauth-mode tests) — token entries are only
 *   populated when it is [ApiAuthConfig.Bearer].
 * @param routeBlock Route registration block called inside `route("/api/v1") { ... }`.
 */
fun Application.configureTestApp(
    authConfig: ApiAuthConfig = makeTestAuthConfig(),
    routeBlock: io.ktor.server.routing.Route.() -> Unit,
) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    routing {
        route("/api/v1") {
            install(ApiBearerAuth) {
                this.authConfig = authConfig
                tokenEntries =
                    (authConfig as? ApiAuthConfig.Bearer)?.tokens?.mapValues { (_, p) ->
                        BearerTokenStore.TokenEntry(p, expiresAt = null)
                    } ?: emptyMap()
            }
            routeBlock()
        }
    }
}
