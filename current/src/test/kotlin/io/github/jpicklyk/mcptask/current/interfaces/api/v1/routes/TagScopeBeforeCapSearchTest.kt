package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.knowledge.search.AccessScope
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REST scope-before-cap regression coverage for `GET /search` and `GET /notes/search`:
 *  - S3 / S3b (AC2): a tag-scoped principal gets the in-scope hits even when the unrestricted ranking puts every
 *    in-scope hit behind the whole result cap (the tag filter used to run AFTER the cap, so those hits vanished).
 *  - S10 (AC3): the principal `tags_include` predicate is exact, case sensitive element membership of the item's
 *    comma-separated tags (`ApiScope` / `allowsItemTags`: "membership is exact (no prefix/substring matching)").
 *  - S11 (AC3, D3): principal `root_ids` may name a non-depth-0 item and then scope exactly that subtree
 *    (`api-rest.md` section 3: "an item is accessible if any ancestor (including itself) is in the scope set");
 *    an item outside every listed subtree, such as an unstamped orphan, is never returned.
 *  - S9: an `ancestorId` outside the principal's scope answers 403.
 *
 * Ranking precondition for S3/S3b: the fixture makes the 10 in-scope documents rank AFTER 110 out-of-scope ones
 * (they match only through a long summary or note body, the others through a one-word title or note), and the test
 * asserts that precondition itself through the unrestricted service before it asserts anything about REST.
 * Without it the scope-before-cap assertion could not fail.
 *
 * Item tags are lowercase `[a-z0-9-]` elements because WorkItem rejects anything else, so the case and wildcard
 * probes live on the principal side of the filter. Roots are stamped `root_id = id`, children inherit the parent's
 * root (the production placement); the orphan is a depth-0 item whose `root_id` stays NULL.
 * Forbidden-construct declaration: none.
 */
class TagScopeBeforeCapSearchTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val provider get() = db.repositoryProvider()

    // -- fixtures ------------------------------------------------------------------------------------------

    private suspend fun root(
        title: String,
        summary: String = "",
        tags: String? = null,
    ): WorkItem {
        val created =
            requireNotNull(provider.workItemRepository().create(WorkItem(title = title, summary = summary, tags = tags, depth = 0)))
        return requireNotNull(provider.workItemRepository().update(created.copy(rootId = created.id)))
    }

    private suspend fun orphan(title: String): WorkItem =
        requireNotNull(provider.workItemRepository().create(WorkItem(title = title, depth = 0)))

    private suspend fun child(
        parent: WorkItem,
        title: String,
    ): WorkItem =
        requireNotNull(
            provider.workItemRepository().create(
                WorkItem(title = title, parentId = parent.id, depth = parent.depth + 1, rootId = parent.rootId ?: parent.id)
            )
        )

    private suspend fun note(
        host: WorkItem,
        body: String,
    ) = requireNotNull(provider.noteRepository().upsert(Note(itemId = host.id, key = "spec", role = "queue", body = body)))

    private suspend fun ApplicationTestBuilder.hitIds(
        path: String,
        token: String = TEST_TOKEN,
    ): List<String> {
        val response = client.get("/api/v1$path") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, response.status, "GET $path: ${response.bodyAsText()}")
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonArray
            .map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
    }

    private fun setOfIds(vararg items: WorkItem): Set<String> = items.map { it.id.toString() }.toSet()

    private val padding = (1..40).joinToString(" ") { "padding$it" }

    // -- S3: tag scope is applied before the cap --------------------------------------------------------------

    @Test
    fun `S3 GET search gives a tag-scoped principal its in-scope hits although the unrestricted ranking puts them past the cap`() {
        val (alphaIds, plainCount) =
            runBlocking {
                val alpha = (1..10).map { root("alpha record $it", summary = "$padding zephyrword", tags = "alpha") }
                repeat(110) { root("zephyrword", tags = null) }
                alpha.map { it.id } to 110
            }

        // Precondition: the unrestricted ranking holds a full capped page of OTHER items, so none of the alpha items is visible at all.
        val unrestricted =
            runBlocking {
                SearchService(provider.searchIndex()).search(
                    SearchRequest(
                        query = "zephyrword",
                        corpus = Corpus.ITEM,
                        matchMode = SearchMatchMode.AUTO,
                        limit = 100,
                        access = AccessScope.unrestricted(),
                    )
                )
            }
        assertEquals(100, unrestricted.hits.size, "precondition: $plainCount untagged matches fill the whole capped list")
        assertTrue(unrestricted.truncated, "precondition: more than the cap matched")
        assertTrue(unrestricted.hits.none { it.itemId in alphaIds }, "precondition: no in-scope hit is inside the unrestricted top 100")

        testApplication {
            application { configureTestApp(makeTestAuthConfig(tagsInclude = setOf("alpha"))) { searchRoutes(provider) } }

            val ids = hitIds("/search?q=zephyrword")

            assertEquals(alphaIds.map { it.toString() }.toSet(), ids.toSet(), "all 10 in-scope hits are returned")
            assertEquals(10, ids.size, "and no hit is repeated")
        }
    }

    @Test
    fun `S3b GET notes search gives a tag-scoped principal its in-scope hits although the unrestricted ranking puts them past the cap`() {
        val alphaIds =
            runBlocking {
                val alpha =
                    (1..10).map {
                        val host = root("alpha host $it", tags = "alpha")
                        note(host, "$padding zephyrnote")
                        host
                    }
                repeat(110) { note(root("plain host $it", tags = null), "zephyrnote") }
                alpha.map { it.id }
            }

        val unrestricted =
            runBlocking {
                SearchService(provider.searchIndex()).search(
                    SearchRequest(
                        query = "zephyrnote",
                        corpus = Corpus.NOTE,
                        matchMode = SearchMatchMode.AUTO,
                        limit = 100,
                        access = AccessScope.unrestricted(),
                    )
                )
            }
        assertEquals(100, unrestricted.hits.size, "precondition: the untagged notes fill the whole capped list")
        assertTrue(unrestricted.truncated)
        assertTrue(unrestricted.hits.none { it.itemId in alphaIds }, "precondition: no in-scope note is inside the unrestricted top 100")

        testApplication {
            application { configureTestApp(makeTestAuthConfig(tagsInclude = setOf("alpha"))) { noteRoutes(provider) } }

            val ids = hitIds("/notes/search?q=zephyrnote")

            assertEquals(alphaIds.map { it.toString() }.toSet(), ids.toSet())
            assertEquals(10, ids.size)
        }
    }

    // -- S10: exact, case sensitive tag membership ---------------------------------------------------------------

    private class Tagged(
        val byTags: Map<String, WorkItem>,
    ) {
        fun idsWith(vararg tags: String): Set<String> = tags.map { byTags.getValue(it).id.toString() }.toSet()
    }

    private suspend fun taggedItems(
        titleBase: String,
        withNotes: Boolean,
    ): Tagged {
        val tagsets =
            listOf("alpha", "abc", "planning", "plan", "ab", "alphabet", "beta,alpha,gamma", "alpha,zeta", "zeta,alpha", "alpha,beta")
        val map = tagsets.associateWith { root("$titleBase ${it.replace(',', '-')}", tags = it) }.toMutableMap()
        map["<none>"] = root("$titleBase untagged", tags = null)
        if (withNotes) map.values.forEach { note(it, "$titleBase body") }
        return Tagged(map)
    }

    private val alphaTagsets = arrayOf("alpha", "beta,alpha,gamma", "alpha,zeta", "zeta,alpha", "alpha,beta")

    @Test
    fun `S10 GET search applies principal tags as exact case sensitive element membership`() {
        val tagged = runBlocking { taggedItems("tagprobeqx", withNotes = false) }
        val cases =
            listOf(
                setOf("alpha") to tagged.idsWith(*alphaTagsets),
                setOf("Alpha") to emptySet(),
                setOf("a_c") to emptySet(),
                setOf("a%") to emptySet(),
                setOf("%") to emptySet(),
                setOf("plan") to tagged.idsWith("plan"),
                setOf("ab") to tagged.idsWith("ab"),
                setOf("alpha", "plan") to tagged.idsWith("plan", *alphaTagsets),
                setOf("alpha,beta") to emptySet(),
                emptySet<String>() to
                    tagged.byTags.values
                        .map { it.id.toString() }
                        .toSet(),
            )

        cases.forEach { (principalTags, expected) ->
            testApplication {
                application { configureTestApp(makeTestAuthConfig(tagsInclude = principalTags)) { searchRoutes(provider) } }
                val ids = hitIds("/search?q=tagprobeqx")
                assertEquals(expected, ids.toSet(), "principal tags_include=$principalTags")
                assertEquals(ids.size, ids.toSet().size, "principal tags_include=$principalTags must not repeat a hit")
            }
        }
    }

    @Test
    fun `S10 GET notes search checks principal tags on the owning item`() {
        val tagged = runBlocking { taggedItems("notetagprobeqx", withNotes = true) }
        val cases =
            listOf(
                setOf("alpha") to tagged.idsWith(*alphaTagsets),
                setOf("Alpha") to emptySet(),
                setOf("a%") to emptySet(),
                setOf("plan") to tagged.idsWith("plan"),
                emptySet<String>() to
                    tagged.byTags.values
                        .map { it.id.toString() }
                        .toSet(),
            )

        cases.forEach { (principalTags, expected) ->
            testApplication {
                application { configureTestApp(makeTestAuthConfig(tagsInclude = principalTags)) { noteRoutes(provider) } }
                val ids = hitIds("/notes/search?q=notetagprobeqx")
                assertEquals(expected, ids.toSet(), "principal tags_include=$principalTags")
            }
        }
    }

    @Test
    fun `S10 principal tags are ANDed with the request tag filter on GET search`() {
        val both = runBlocking { root("andprobeqx one", tags = "alpha,beta") }
        runBlocking {
            root("andprobeqx two", tags = "alpha")
            root("andprobeqx three", tags = "beta")
        }

        testApplication {
            application { configureTestApp(makeTestAuthConfig(tagsInclude = setOf("alpha"))) { searchRoutes(provider) } }

            assertEquals(
                setOf(both.id.toString()),
                hitIds("/search?q=andprobeqx&tag=beta").toSet(),
                "'two' fails the request tag, 'three' fails the principal tag"
            )
            assertEquals(setOf(both.id.toString()), hitIds("/search?q=andprobeqx&tag=BETA").toSet(), "the request tag filter ignores case")
        }
    }

    // -- S11 / S9: principal root ids --------------------------------------------------------------------------------

    private class Forest(
        val r: WorkItem,
        val f: WorkItem,
        val g: WorkItem,
        val f1: WorkItem,
        val f2: WorkItem,
        val g1: WorkItem,
        val r2: WorkItem,
        val r2c: WorkItem,
        val o: WorkItem,
    )

    private suspend fun forest(withNotes: Boolean): Forest {
        val r = root("scopeprobeqx R")
        val f = child(r, "scopeprobeqx F")
        val g = child(r, "scopeprobeqx G")
        val f1 = child(f, "scopeprobeqx F1")
        val f2 = child(f1, "scopeprobeqx F2")
        val g1 = child(g, "scopeprobeqx G1")
        val r2 = root("scopeprobeqx R2")
        val r2c = child(r2, "scopeprobeqx R2c")
        val o = orphan("scopeprobeqx O")
        if (withNotes) listOf(r, f, g, f1, f2, g1, r2, r2c, o).forEach { note(it, "scopeprobeqx body") }
        return Forest(r, f, g, f1, f2, g1, r2, r2c, o)
    }

    @Test
    fun `S11 GET search scopes a principal to exactly the listed subtrees`() {
        val t = runBlocking { forest(withNotes = false) }
        val cases =
            listOf(
                setOf(t.f.id) to setOfIds(t.f, t.f1, t.f2),
                setOf(t.r.id) to setOfIds(t.r, t.f, t.g, t.f1, t.f2, t.g1),
                setOf(t.f.id, t.r2.id) to setOfIds(t.f, t.f1, t.f2, t.r2, t.r2c),
                setOf(t.g.id) to setOfIds(t.g, t.g1),
            )

        testApplication {
            application { configureTestApp(makeTestAuthConfig()) { searchRoutes(provider) } }
            assertEquals(
                setOfIds(t.r, t.f, t.g, t.f1, t.f2, t.g1, t.r2, t.r2c, t.o),
                hitIds("/search?q=scopeprobeqx", ADMIN_TOKEN).toSet(),
                "fixture: an unrestricted caller sees all 9 items including the orphan"
            )
        }
        cases.forEach { (roots, expected) ->
            testApplication {
                application { configureTestApp(makeTestAuthConfig(scopeRootIds = roots)) { searchRoutes(provider) } }
                assertEquals(expected, hitIds("/search?q=scopeprobeqx").toSet(), "principal root_ids=$roots")
            }
        }
    }

    @Test
    fun `S11 GET notes search scopes a principal to exactly the listed subtrees`() {
        val t = runBlocking { forest(withNotes = true) }
        val cases =
            listOf(
                setOf(t.f.id) to setOfIds(t.f, t.f1, t.f2),
                setOf(t.r.id) to setOfIds(t.r, t.f, t.g, t.f1, t.f2, t.g1),
                setOf(t.f.id, t.r2.id) to setOfIds(t.f, t.f1, t.f2, t.r2, t.r2c),
            )

        testApplication {
            application { configureTestApp(makeTestAuthConfig()) { noteRoutes(provider) } }
            assertEquals(
                setOfIds(t.r, t.f, t.g, t.f1, t.f2, t.g1, t.r2, t.r2c, t.o),
                hitIds("/notes/search?q=scopeprobeqx", ADMIN_TOKEN).toSet(),
                "fixture: an unrestricted caller sees all 9 notes including the orphan's"
            )
        }
        cases.forEach { (roots, expected) ->
            testApplication {
                application { configureTestApp(makeTestAuthConfig(scopeRootIds = roots)) { noteRoutes(provider) } }
                assertEquals(expected, hitIds("/notes/search?q=scopeprobeqx").toSet(), "principal root_ids=$roots")
            }
        }
    }

    @Test
    fun `S9 an ancestorId is checked against the principal's scope and narrows within it`() {
        val t = runBlocking { forest(withNotes = true) }

        testApplication {
            application {
                configureTestApp(makeTestAuthConfig(scopeRootIds = setOf(t.f.id))) {
                    searchRoutes(provider)
                    noteRoutes(provider)
                }
            }
            val outOfScope = listOf(t.g.id, t.r.id, t.r2.id, t.o.id)
            outOfScope.forEach { ancestor ->
                listOf("/search?q=scopeprobeqx&ancestorId=$ancestor", "/notes/search?q=scopeprobeqx&ancestorId=$ancestor").forEach { path ->
                    val response = client.get("/api/v1$path") { header("Authorization", "Bearer $TEST_TOKEN") }
                    assertEquals(HttpStatusCode.Forbidden, response.status, "GET $path with a principal scoped to F")
                }
            }
            assertEquals(setOfIds(t.f, t.f1, t.f2), hitIds("/search?q=scopeprobeqx&ancestorId=${t.f.id}").toSet())
            assertEquals(
                setOfIds(t.f1, t.f2),
                hitIds("/search?q=scopeprobeqx&ancestorId=${t.f1.id}").toSet(),
                "a descendant of the scope root is in scope"
            )
            assertEquals(setOfIds(t.f1, t.f2), hitIds("/notes/search?q=scopeprobeqx&ancestorId=${t.f1.id}").toSet())
        }
    }

    @Test
    fun `an unscoped principal is not limited by tags or roots`() {
        val a = runBlocking { root("freeprobeqx one", tags = "alpha") }
        val b = runBlocking { root("freeprobeqx two", tags = null) }

        testApplication {
            application { configureTestApp(makeTestAuthConfig()) { searchRoutes(provider) } }

            assertEquals(setOfIds(a, b), hitIds("/search?q=freeprobeqx").toSet())
        }
    }
}
