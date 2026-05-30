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
 * Returns a [ApiAuthConfig.Bearer] with two principals:
 * - [TEST_TOKEN] → `read` capability, unrestricted scope
 * - [ADMIN_TOKEN] → `admin` + `read` capabilities, unrestricted scope
 */
fun makeTestAuthConfig(scopeRootIds: Set<UUID>? = null): ApiAuthConfig.Bearer {
    val readPrincipal =
        ApiPrincipal(
            tokenId = TEST_TOKEN_ID,
            scope = ApiScope(rootIds = scopeRootIds, tagsInclude = emptySet()),
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
 * Common Ktor application configuration for route integration tests.
 *
 * Installs [ContentNegotiation], [SSE], [ApiBearerAuth], and registers routes via [routeBlock].
 *
 * @param authConfig The auth config to install on `/api/v1`.
 * @param routeBlock Route registration block called inside `route("/api/v1") { ... }`.
 */
fun Application.configureTestApp(
    authConfig: ApiAuthConfig.Bearer = makeTestAuthConfig(),
    routeBlock: io.ktor.server.routing.Route.() -> Unit,
) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    routing {
        route("/api/v1") {
            install(ApiBearerAuth) {
                this.authConfig = authConfig
                tokenEntries =
                    authConfig.tokens.mapValues { (_, p) ->
                        BearerTokenStore.TokenEntry(p, expiresAt = null)
                    }
            }
            routeBlock()
        }
    }
}
