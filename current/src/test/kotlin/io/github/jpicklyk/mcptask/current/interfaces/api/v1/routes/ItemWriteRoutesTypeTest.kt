package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests for the Q2b `type` rule on the REST write routes (item 2c67eeca): scenario S8 of the frozen
 * test-plan plus probes.
 *
 * Oracles (frozen task-scope 1c, AC1-AC3 and decision D6, none read from the implementation): POST and PATCH map a type
 * violation to `400` with error code `validation_error` and `details = {reason, type, availableTypes}` where `type` is the
 * offending input or null, and `availableTypes` is omitted when nothing is declared (the same status and code
 * `NoteWriteRoutes` already uses for `schema_violation`). `PATCH {"type": null}` and `{"type": ""}` are clearing
 * rejections (RFC 7396 delete reaches the service as a cleared type); a PATCH that leaves `type` out never type-checks.
 * The error envelope shape (`error` is the code string, `details` an object) is the one the existing route tests pin.
 *
 * The declared set comes from a per-root config pushed with the project-config store after the root item exists; a
 * root created with nothing declared accepts any non-blank type (U7), which is how the roots here are made.
 */
class ItemWriteRoutesTypeTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val rootYaml = "work_item_schemas:\n  beta-type:\n    notes: []\n  alpha-type:\n    notes: []\n"
    private val declared = listOf("alpha-type", "beta-type")

    private suspend fun HttpClient.send(
        path: String,
        body: String,
        etag: String? = null,
        asPatch: Boolean = false,
    ): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("Authorization", "Bearer $WRITE_TOKEN")
            if (etag != null) header(HttpHeaders.IfMatch, etag)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (asPatch) patch(path, block) else post(path, block)
    }

    private fun idOf(body: String): String = Regex(""""id"\s*:\s*"([0-9a-f\-]{36})"""").find(body)!!.groupValues[1]

    private fun JsonObject.details(): JsonObject = this["details"]!!.jsonObject

    private fun JsonObject.availableTypes(): List<String> = details()["availableTypes"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun parse(response: String): JsonObject = Json.parseToJsonElement(response).jsonObject

    private fun assertValidationError(
        json: JsonObject,
        context: String,
    ) {
        assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "$context: $json")
    }

    /** Creates a root through the API (nothing declared yet, so any type is accepted) and then pushes [rootYaml] for it. */
    private suspend fun HttpClient.rootWithDeclaredTypes(
        repo: io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider,
    ): String {
        val response = send("/api/v1/items", """{"type":"alpha-type","title":"Root"}""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rootId = idOf(response.bodyAsText())
        repo.configStore().upsert(UUID.fromString(rootId), rootYaml)
        return rootId
    }

    // ---------------------------------------------------------------------------------------------
    // S8 - POST
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 POST without a type in a schema-free server is 400 validation_error with the reason and no availableTypes`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            for (body in listOf(
                """{"title":"No type"}""",
                """{"title":"Null type","type":null}""",
                """{"title":"Empty type","type":""}""",
                """{"title":"Blank type","type":"   "}""",
            )) {
                val response = client.send("/api/v1/items", body)

                assertEquals(HttpStatusCode.BadRequest, response.status, "$body: ${response.bodyAsText()}")
                val json = parse(response.bodyAsText())
                assertValidationError(json, body)
                assertEquals("type is required", json.details()["reason"]?.jsonPrimitive?.content, "$body: $json")
                assertFalse(json.details().containsKey("availableTypes"), "$body: nothing declared, key omitted: $json")
                val typeField = json.details()["type"]
                assertTrue(typeField == null || typeField is JsonNull, "$body: the offending type is null, not a string: $json")
            }
            val persisted = repo.workItemRepository().findByFilters()
            assertEquals(0, persisted.items.size, "no rejected POST persisted an item")
        }

    @Test
    fun `S8 POST under a root with declared types lists them when the type is missing`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val rootId = client.rootWithDeclaredTypes(repo)

            val response = client.send("/api/v1/items", """{"title":"Child","parentId":"$rootId"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val json = parse(response.bodyAsText())
            assertValidationError(json, "missing type")
            assertEquals("type is required", json.details()["reason"]?.jsonPrimitive?.content, "$json")
            assertEquals(declared, json.availableTypes(), "$json")
            val children = repo.workItemRepository().findChildren(UUID.fromString(rootId))
            assertEquals(0, children.size, "the rejected POST wrote nothing")
        }

    @Test
    fun `S8 POST of an undeclared type echoes it in details and a declared type is created`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val rootId = client.rootWithDeclaredTypes(repo)

            val bad = client.send("/api/v1/items", """{"title":"Bad","type":"nope","parentId":"$rootId"}""")
            val badJson = parse(bad.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, bad.status, "$badJson")
            assertValidationError(badJson, "undeclared")
            assertEquals("type 'nope' is not declared", badJson.details()["reason"]?.jsonPrimitive?.content, "$badJson")
            assertEquals("nope", badJson.details()["type"]?.jsonPrimitive?.content, "$badJson")
            assertEquals(declared, badJson.availableTypes(), "$badJson")
            assertEquals(0, repo.workItemRepository().findChildren(UUID.fromString(rootId)).size)

            // Matching is exact: a case variant and a padded spelling of a declared key are undeclared too.
            for (variant in listOf("Alpha-Type", " alpha-type")) {
                val r = client.send("/api/v1/items", """{"title":"Variant","type":"$variant","parentId":"$rootId"}""")
                assertEquals(HttpStatusCode.BadRequest, r.status, "'$variant': ${r.bodyAsText()}")
            }

            val good = client.send("/api/v1/items", """{"title":"Good","type":"beta-type","parentId":"$rootId"}""")
            assertEquals(HttpStatusCode.Created, good.status, good.bodyAsText())
            val stored = repo.workItemRepository().getById(UUID.fromString(idOf(good.bodyAsText())))
            assertNotNull(stored)
            assertEquals("beta-type", stored.type)
        }

    // ---------------------------------------------------------------------------------------------
    // S8 - PATCH
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 PATCH that clears the type with null or empty is 400 and the item is unchanged`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val rootId = client.rootWithDeclaredTypes(repo)
            val created = client.send("/api/v1/items", """{"title":"Item","type":"alpha-type","parentId":"$rootId"}""")
            val itemId = idOf(created.bodyAsText())
            val etag = created.headers[HttpHeaders.ETag]
            assertNotNull(etag)
            val before = repo.workItemRepository().getById(UUID.fromString(itemId))!!

            for (body in listOf("""{"type":null}""", """{"type":""}""", """{"type":"  ","title":"Renamed"}""")) {
                val response = client.send("/api/v1/items/$itemId", body, etag = etag, asPatch = true)

                assertEquals(HttpStatusCode.BadRequest, response.status, "$body: ${response.bodyAsText()}")
                val json = parse(response.bodyAsText())
                assertValidationError(json, body)
                assertEquals("type is required", json.details()["reason"]?.jsonPrimitive?.content, "$body: $json")
                assertEquals(declared, json.availableTypes(), "$body: $json")
                assertEquals(before, repo.workItemRepository().getById(UUID.fromString(itemId)), "$body: nothing about the item changed")
            }
        }

    @Test
    fun `S8 PATCH to an undeclared type is 400 with the type in details, a declared type is applied`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val rootId = client.rootWithDeclaredTypes(repo)
            val created = client.send("/api/v1/items", """{"title":"Item","type":"alpha-type","parentId":"$rootId"}""")
            val itemId = idOf(created.bodyAsText())
            val etag = created.headers[HttpHeaders.ETag]
            assertNotNull(etag)
            val before = repo.workItemRepository().getById(UUID.fromString(itemId))!!

            val bad = client.send("/api/v1/items/$itemId", """{"type":"nope"}""", etag = etag, asPatch = true)
            val badJson = parse(bad.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, bad.status, "$badJson")
            assertValidationError(badJson, "undeclared patch")
            assertEquals("nope", badJson.details()["type"]?.jsonPrimitive?.content, "$badJson")
            assertEquals("type 'nope' is not declared", badJson.details()["reason"]?.jsonPrimitive?.content, "$badJson")
            assertEquals(declared, badJson.availableTypes(), "$badJson")
            assertEquals(before, repo.workItemRepository().getById(UUID.fromString(itemId)))

            val good = client.send("/api/v1/items/$itemId", """{"type":"beta-type"}""", etag = etag, asPatch = true)
            assertEquals(HttpStatusCode.OK, good.status, good.bodyAsText())
            assertEquals("beta-type", repo.workItemRepository().getById(UUID.fromString(itemId))!!.type)
        }

    @Test
    fun `S8 PATCH without a type field leaves a residue item and a stale type editable`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val rootId = client.rootWithDeclaredTypes(repo)
            val parent = UUID.fromString(rootId)

            for (residueType in listOf("untyped", "gone-type")) {
                val seeded =
                    repo.workItemRepository().create(
                        WorkItem(
                            title = "legacy $residueType",
                            role = Role.QUEUE,
                            parentId = parent,
                            depth = 1,
                            rootId = parent,
                            type = residueType
                        ),
                    )
                // PATCH If-Match still validates the 3.x modifiedAt tag; GET /items/{id} serves the 4.0 version ETag since Q19.
                val fetched = client.get("/api/v1/items/${seeded.id}") { header("Authorization", "Bearer $WRITE_TOKEN") }
                assertNotNull(fetched.headers[HttpHeaders.ETag], "GET must supply an ETag: ${fetched.bodyAsText()}")
                val etag = etagFor(seeded.modifiedAt)

                val response =
                    client.send(
                        "/api/v1/items/${seeded.id}",
                        """{"title":"renamed $residueType"}""",
                        etag = etag,
                        asPatch = true
                    )

                assertEquals(HttpStatusCode.OK, response.status, "$residueType: ${response.bodyAsText()}")
                val stored = repo.workItemRepository().getById(seeded.id)!!
                assertEquals("renamed $residueType", stored.title)
                assertEquals(residueType, stored.type, "the unchanged type is never re-checked")
            }
        }
}
