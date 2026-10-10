package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search

import io.github.jpicklyk.mcptask.current.application.knowledge.search.AccessScope
import io.github.jpicklyk.mcptask.current.application.knowledge.search.Ranker
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.FTS_CANDIDATE_ROWS
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.SearchHit
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.test.sqlite.CycleTriggers
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract of the SQLite [SearchIndex] adapter, per corpus (ITEM and NOTE), on a real migrated database.
 * It replaces the retired `SQLiteWorkItemRepositoryFtsTest`, `SQLiteNoteRepositoryFtsTest` and the ancestor cycle
 * case of `SQLiteWorkItemRepositoryCycleGuardTest`, and carries the FTS5 phrase quoting cases that used to live in
 * `FtsQuerySanitizerTest` (the adapter is now the only place that renders a MATCH expression).
 *
 * Oracles (frozen test-plan S1, S10, S11, S12; none read from the implementation):
 *  - Analyzer semantics: SUBSTRING is a trigram substring match, STEMMED is a Porter-stemmed word match. The pairs
 *    used here ("authentication" vs "authenticated", "auth" inside "OAuth") are the ones the retired suites already
 *    pinned against the same tokenizers.
 *  - Literal terms: the retired `FtsQuerySanitizer` KDoc (base 5bc03359): every special character (`" * : - ( )`) and
 *    every operator word (`AND OR NOT NEAR`) is searched literally, never as FTS5 syntax.
 *  - Principal tag scope (AC3): exact, case-sensitive comma-separated element membership, taken from
 *    `ApiScope`/`allowsItemTags` ("membership is exact (no prefix/substring matching)"), so `%` and `_` are plain
 *    characters, `Alpha` is not `alpha` and a principal tag containing a comma is one element, not two.
 *  - Request tag filter (D4): keeps the 3.x case-insensitive semantics, any-of.
 *  - Principal root ids (AC3/D3, `api-rest.md` section 3): an item is in scope when it or any ancestor is a listed
 *    id, so a NON-depth-0 listed id scopes exactly that subtree, including itself; an item outside every listed
 *    subtree (here an unstamped depth-0 orphan) is never returned for a restricted scope.
 *  - `field` (AC1/D5): the column that actually contains a match, title when both do; `snippet` comes from that
 *    column and carries `<mark>` delimiters.
 *
 * Fixture invariants: item tags are lowercase `[a-z0-9-]` elements (WorkItem rejects uppercase, spaces and other
 * characters, so the case and wildcard probes live on the PRINCIPAL side of the filter); roots are stamped
 * `root_id = id` and children inherit their parent's root, exactly as the production write path places items; an
 * orphan is a depth-0 item whose `root_id` stays NULL.
 *
 * Forbidden-construct declaration: none.
 */
class SqliteSearchIndexContractTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val provider get() = sqliteDb.repositoryProvider()
    private val index: SearchIndex get() = provider.searchIndex()

    // -- fixtures ------------------------------------------------------------------------------------

    private suspend fun root(
        title: String,
        summary: String = "",
        tags: String? = null,
        role: Role = Role.QUEUE,
    ): WorkItem {
        val created =
            assertNotNull(
                provider.workItemRepository().create(WorkItem(title = title, summary = summary, tags = tags, role = role, depth = 0))
            )
        return assertNotNull(provider.workItemRepository().update(created.copy(rootId = created.id)))
    }

    /** A depth-0 item that is not stamped with a root (root_id NULL). */
    private suspend fun orphan(
        title: String,
        summary: String = "",
    ): WorkItem = assertNotNull(provider.workItemRepository().create(WorkItem(title = title, summary = summary, depth = 0)))

    private suspend fun child(
        parent: WorkItem,
        title: String,
        summary: String = "",
        tags: String? = null,
        role: Role = Role.QUEUE,
    ): WorkItem =
        assertNotNull(
            provider.workItemRepository().create(
                WorkItem(
                    title = title,
                    summary = summary,
                    parentId = parent.id,
                    depth = parent.depth + 1,
                    rootId = parent.rootId ?: parent.id,
                    tags = tags,
                    role = role,
                )
            )
        )

    private suspend fun note(
        owner: WorkItem,
        key: String,
        body: String,
    ): Note = assertNotNull(provider.noteRepository().upsert(Note(itemId = owner.id, key = key, role = "work", body = body)))

    private suspend fun cands(
        corpus: Corpus,
        terms: List<String>,
        analyzer: Analyzer,
        filter: ScopeFilter = ScopeFilter(),
        window: Int = FTS_CANDIDATE_ROWS,
    ): List<Candidate> = index.candidates(corpus, TextQuery(terms), analyzer, filter, window)

    private suspend fun owners(
        corpus: Corpus,
        terms: List<String>,
        analyzer: Analyzer,
        filter: ScopeFilter = ScopeFilter(),
    ): Set<UUID> = cands(corpus, terms, analyzer, filter).map { it.ownerItemId }.toSet()

    private fun ids(vararg items: WorkItem): Set<UUID> = items.map { it.id }.toSet()

    // -- S12: analyzers -----------------------------------------------------------------------------------

    @Test
    fun `S12 STEMMED matches a stem variant that SUBSTRING does not`(): Unit =
        runBlocking {
            val target = root("User session handling", summary = "authenticated user management")
            root("Payment processing module")

            assertEquals(ids(target), owners(Corpus.ITEM, listOf("authentication"), Analyzer.STEMMED))
            assertEquals(
                emptySet(),
                owners(Corpus.ITEM, listOf("authentication"), Analyzer.SUBSTRING),
                "'authentication' is not a substring of 'authenticated'"
            )
        }

    @Test
    fun `S12 SUBSTRING matches an infix that STEMMED does not`(): Unit =
        runBlocking {
            val target = root("OAuth integration task", summary = "Implement OAuth flow")
            root("Unrelated database migration")

            assertEquals(ids(target), owners(Corpus.ITEM, listOf("auth"), Analyzer.SUBSTRING))
            assertEquals(emptySet(), owners(Corpus.ITEM, listOf("auth"), Analyzer.STEMMED), "'auth' is not a word of 'OAuth'")
        }

    @Test
    fun `S12 SUBSTRING is case insensitive`(): Unit =
        runBlocking {
            val a = root("Title Containing MixedCaseProbe Segment")

            assertEquals(ids(a), owners(Corpus.ITEM, listOf("mixedcaseprobe"), Analyzer.SUBSTRING))
            assertEquals(ids(a), owners(Corpus.ITEM, listOf("MIXEDCASEPROBE"), Analyzer.SUBSTRING))
        }

    @Test
    fun `S12 several terms are ANDed in both analyzers`(): Unit =
        runBlocking {
            val both = root("alpha beta gamma")
            root("alpha only here")
            root("beta only here")

            assertEquals(ids(both), owners(Corpus.ITEM, listOf("alpha", "beta"), Analyzer.SUBSTRING))
            assertEquals(ids(both), owners(Corpus.ITEM, listOf("alpha", "beta"), Analyzer.STEMMED))
        }

    @Test
    fun `S12 a query that matches nothing yields no candidates`(): Unit =
        runBlocking {
            root("Database design task")
            root("API specification document")

            Analyzer.entries.forEach { analyzer ->
                assertTrue(cands(Corpus.ITEM, listOf("xyzunmatchableterm"), analyzer).isEmpty(), "analyzer $analyzer")
            }
        }

    @Test
    fun `S12 a candidate list never repeats a document`(): Unit =
        runBlocking {
            repeat(12) { root("dedupeprobe item $it", summary = "dedupeprobe again") }

            Analyzer.entries.forEach { analyzer ->
                val list = cands(Corpus.ITEM, listOf("dedupeprobe"), analyzer)
                assertEquals(12, list.size, "analyzer $analyzer")
                assertEquals(12, list.map { it.id }.toSet().size, "analyzer $analyzer must not repeat a document")
            }
        }

    // -- S12: literal terms (the FTS5 quoting cases of the retired sanitizer test) -------------------------------

    @Test
    fun `S12 SUBSTRING searches special characters and operator words literally`(): Unit =
        runBlocking {
            val notBad = root("good NOT bad")
            root("just good")
            val quoted = root("say \"hello\" there")
            root("say hello there")
            val star = root("test* literal")
            root("testing literal")
            root("foo", summary = "title bar")
            val colonLiteral = root("title:foo thing")
            val parens = root("find (this) now")
            val hyphen = root("auth-check module")
            root("auth check module")
            val andWord = root("this AND that")
            val embeddedQuote = root("a\"b c")

            suspend fun sub(vararg terms: String) = owners(Corpus.ITEM, terms.toList(), Analyzer.SUBSTRING)

            assertEquals(ids(notBad), sub("NOT", "bad"), "NOT is a word, not an operator, and 'just good' lacks both words")
            assertEquals(ids(quoted), sub("\"hello\""), "embedded double quotes are literal characters; 'say hello there' has none")
            assertEquals(ids(star), sub("test*"), "* is literal, not a prefix wildcard; 'testing' must not match")
            assertEquals(ids(colonLiteral), sub("title:foo"), "title:foo is not a column filter; a title of 'foo' must not match")
            assertEquals(ids(parens), sub("(this)"))
            assertEquals(ids(hyphen), sub("auth-check"), "the unhyphenated 'auth check' must not match")
            assertEquals(ids(andWord), sub("AND"))
            assertEquals(ids(embeddedQuote), sub("a\"b"))
        }

    @Test
    fun `S12 STEMMED searches operator words and column syntax as plain words`(): Unit =
        runBlocking {
            val notBad = root("good NOT bad")
            root("just good")
            val quoted = root("say \"hello\" there")
            val unquoted = root("say hello there")
            val star = root("test* literal")
            root("foo", summary = "title bar")
            val colonLiteral = root("title:foo thing")
            val andWord = root("this AND that")

            suspend fun stem(vararg terms: String) = owners(Corpus.ITEM, terms.toList(), Analyzer.STEMMED)

            assertEquals(ids(notBad), stem("NOT", "bad"))
            assertEquals(ids(quoted, unquoted), stem("\"hello\""), "quotes are separators for the word analyzer")
            assertTrue(star.id in stem("test*"), "the literal star must neither fail the query nor hide the word")
            assertEquals(
                ids(colonLiteral),
                stem("title:foo"),
                "title:foo must not become a column filter that also matches a title of 'foo'"
            )
            assertEquals(ids(andWord), stem("AND"))
        }

    // -- S1: field and snippet --------------------------------------------------------------------------------

    @Test
    fun `S1 field and snippet name the column that actually contains the match`(): Unit =
        runBlocking {
            val summaryOnly = root("plain heading", summary = "the quokka lives here")
            val titleOnly = root("quokka heading tale", summary = "other words")
            val titleOnlyEmptySummary = root("quokka alone", summary = "")
            val both = root("quokka both tale", summary = "also quokka sentence")

            Analyzer.entries.forEach { analyzer ->
                val byOwner = cands(Corpus.ITEM, listOf("quokka"), analyzer).associateBy { it.ownerItemId }
                assertEquals(ids(summaryOnly, titleOnly, titleOnlyEmptySummary, both), byOwner.keys, "analyzer $analyzer")

                val s = byOwner.getValue(summaryOnly.id)
                assertEquals("summary", s.field, "$analyzer: the term is only in the summary")
                assertTrue("<mark>quokka</mark>" in s.snippet.lowercase(), "$analyzer: summary snippet marks the term: ${s.snippet}")
                assertTrue("lives" in s.snippet, "$analyzer: the snippet is the summary text: ${s.snippet}")
                assertFalse("plain heading" in s.snippet, "$analyzer: the snippet must not be the title text: ${s.snippet}")

                val t = byOwner.getValue(titleOnly.id)
                assertEquals("title", t.field, "$analyzer: the term is only in the title")
                assertTrue("<mark>quokka</mark>" in t.snippet.lowercase(), "$analyzer: ${t.snippet}")
                assertTrue("tale" in t.snippet, "$analyzer: ${t.snippet}")

                assertEquals("title", byOwner.getValue(titleOnlyEmptySummary.id).field, "$analyzer: empty summary")

                val b = byOwner.getValue(both.id)
                assertEquals("title", b.field, "$analyzer: both columns match, title wins (D5)")
                assertTrue("<mark>quokka</mark>" in b.snippet.lowercase(), "$analyzer: ${b.snippet}")
                assertTrue("tale" in b.snippet, "$analyzer: the snippet comes from the title: ${b.snippet}")
                assertFalse("sentence" in b.snippet, "$analyzer: the snippet must not come from the summary: ${b.snippet}")
            }
        }

    @Test
    fun `S1 candidates identify the item on the ITEM corpus`(): Unit =
        runBlocking {
            val a = root("identityprobe one")

            val c = cands(Corpus.ITEM, listOf("identityprobe"), Analyzer.SUBSTRING).single()

            assertEquals(a.id, c.id)
            assertEquals(a.id, c.ownerItemId)
            assertNull(c.noteKey)
        }

    // -- scope filters on the ITEM corpus ---------------------------------------------------------------------------

    @Test
    fun `S12 itemId limits the search to one item and an unknown id finds nothing`(): Unit =
        runBlocking {
            val target = root("Target OAuth service", summary = "authentication and authorization")
            root("Other authentication service", summary = "OAuth flow")

            assertEquals(ids(target), owners(Corpus.ITEM, listOf("auth"), Analyzer.SUBSTRING, ScopeFilter(itemId = target.id)))
            assertEquals(ids(target), owners(Corpus.ITEM, listOf("authentication"), Analyzer.STEMMED, ScopeFilter(itemId = target.id)))
            assertEquals(
                emptySet(),
                owners(Corpus.ITEM, listOf("OAuth"), Analyzer.SUBSTRING, ScopeFilter(itemId = UUID.randomUUID())),
                "an unknown item id is an empty result, not an error"
            )
        }

    @Test
    fun `S12 ancestorId limits the search to the subtree including the ancestor itself`(): Unit =
        runBlocking {
            val top = root("Feature root authentication")
            val mid = child(top, "Child task authentication")
            val leaf = child(mid, "Grandchild authentication")
            root("Unrelated authentication module")
            val term = listOf("authentication")

            assertEquals(ids(top, mid, leaf), owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(ancestorId = top.id)))
            assertEquals(ids(mid, leaf), owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(ancestorId = mid.id)))
            assertEquals(ids(leaf), owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(ancestorId = leaf.id)))
        }

    @Test
    fun `S12 ancestorId over a corrupt parent cycle still returns the reachable descendant`(): Unit =
        runBlocking {
            val top = root("S10 root")
            val a = child(top, "S10 A")
            val b = child(a, "CycleScopeProbeQx19")
            // Close the cycle a -> b -> a with the V7 cycle triggers dropped, simulating pre-guard data.
            CycleTriggers.drop(sqliteDb.database)
            P7Raw.exec(sqliteDb.jdbcUrl, "UPDATE work_items SET parent_id = ? WHERE id = ?", b.id, a.id)

            Analyzer.entries.forEach { analyzer ->
                val found = owners(Corpus.ITEM, listOf("CycleScopeProbeQx19"), analyzer, ScopeFilter(ancestorId = a.id))
                assertTrue(b.id in found, "analyzer $analyzer must find the descendant despite the cycle, got $found")
            }
        }

    @Test
    fun `S12 roles filter keeps only the requested roles`(): Unit =
        runBlocking {
            val queued = root("buckwheat noodles", role = Role.QUEUE)
            val working = root("buckwheat porridge", role = Role.WORK)
            val done = root("buckwheat tea", role = Role.TERMINAL)
            val term = listOf("buckwheat")

            assertEquals(ids(working), owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(roles = setOf(Role.WORK))))
            assertEquals(
                ids(queued, done),
                owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(roles = setOf(Role.QUEUE, Role.TERMINAL)))
            )
            assertEquals(ids(queued, working, done), owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(roles = null)))
        }

    @Test
    fun `S12 request tag filter is any-of and case insensitive`(): Unit =
        runBlocking {
            val onlyAuth = root("match alpha", tags = "auth")
            val authPlusOther = root("match beta", tags = "auth,fts5")
            val middle = root("match gamma", tags = "frontend,fts5,docs")
            val backend = root("match delta", tags = "backend")
            val untagged = root("match epsilon")
            root("match zeta", tags = "unrelated")

            suspend fun tagged(vararg tags: String) =
                owners(Corpus.ITEM, listOf("match"), Analyzer.SUBSTRING, ScopeFilter(tagsAny = tags.toList()))

            assertEquals(ids(onlyAuth, authPlusOther), tagged("auth"))
            assertEquals(ids(onlyAuth, authPlusOther, middle), tagged("auth", "fts5"), "multiple tags are OR")
            assertEquals(ids(backend), tagged("backend"))
            assertEquals(ids(backend), tagged("BACKEND"), "3.x request tag matching ignores case")
            assertEquals(emptySet(), tagged("aut"), "a tag is matched as a whole element, never as a prefix of one")
            assertTrue(untagged.id !in tagged("auth", "fts5", "backend"))
        }
    // -- S10: principal tag scope is exact, case sensitive element membership ----------------------------------------

    private class TagFixture(
        val alpha: WorkItem,
        val abc: WorkItem,
        val planning: WorkItem,
        val plan: WorkItem,
        val ab: WorkItem,
        val alphabet: WorkItem,
        val untagged: WorkItem,
        val middle: WorkItem,
        val start: WorkItem,
        val end: WorkItem,
        val pair: WorkItem,
    ) {
        val all: Set<UUID> = setOf(alpha, abc, planning, plan, ab, alphabet, untagged, middle, start, end, pair).map { it.id }.toSet()
        val withAlpha: Set<UUID> = setOf(alpha, middle, start, end, pair).map { it.id }.toSet()
    }

    private suspend fun tagFixture(make: suspend (title: String, tags: String?) -> WorkItem): TagFixture =
        TagFixture(
            alpha = make("tagprobeqx a1", "alpha"),
            abc = make("tagprobeqx a2", "abc"),
            planning = make("tagprobeqx a3", "planning"),
            plan = make("tagprobeqx a4", "plan"),
            ab = make("tagprobeqx a5", "ab"),
            alphabet = make("tagprobeqx a6", "alphabet"),
            untagged = make("tagprobeqx a7", null),
            middle = make("tagprobeqx a8", "beta,alpha,gamma"),
            start = make("tagprobeqx a9", "alpha,zeta"),
            end = make("tagprobeqx b1", "zeta,alpha"),
            pair = make("tagprobeqx b2", "alpha,beta"),
        )

    @Test
    fun `S10 principal tags on the ITEM corpus match exactly and case sensitively`(): Unit =
        runBlocking {
            val f = tagFixture { title, tags -> root(title, tags = tags) }
            val term = listOf("tagprobeqx")

            suspend fun scoped(vararg principalTags: String): Set<UUID> =
                owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(principalTagsAny = principalTags.toSet()))

            assertEquals(
                f.all,
                owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter()),
                "fixture: every item is searchable without a tag scope"
            )
            assertEquals(
                f.withAlpha,
                scoped("alpha"),
                "alpha as the first, middle, last and only element; 'alphabet' and untagged excluded"
            )
            assertEquals(emptySet(), scoped("Alpha"), "case sensitive: Alpha is not alpha")
            assertEquals(emptySet(), scoped("a_c"), "_ is a literal: it must not match 'abc'")
            assertEquals(emptySet(), scoped("a%"), "% is a literal: it must not match 'ab', 'abc' or 'alpha'")
            assertEquals(emptySet(), scoped("%"), "a lone % is a literal, not a match-everything wildcard")
            assertEquals(ids(f.plan), scoped("plan"), "'planning' is not 'plan'")
            assertEquals(ids(f.ab), scoped("ab"), "'abc' and 'alphabet' are not 'ab'")
            assertEquals(f.withAlpha + f.plan.id, scoped("alpha", "plan"), "several principal tags are OR")
            assertEquals(emptySet(), scoped("alpha,beta"), "a principal tag containing a comma is one element that no item carries")
            assertEquals(emptySet(), scoped("zzz"))
        }

    @Test
    fun `S10 principal tags are ANDed with the request tag filter`(): Unit =
        runBlocking {
            val both = root("andprobeqx one", tags = "alpha,beta")
            root("andprobeqx two", tags = "alpha")
            root("andprobeqx three", tags = "beta")

            val found =
                owners(
                    Corpus.ITEM,
                    listOf("andprobeqx"),
                    Analyzer.SUBSTRING,
                    ScopeFilter(tagsAny = listOf("BETA"), principalTagsAny = setOf("alpha")),
                )

            assertEquals(ids(both), found, "'two' fails the request tag and 'three' fails the principal tag")
        }

    // -- S11: principal root ids ------------------------------------------------------------------------------------

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
    ) {
        val everything: Set<UUID> = setOf(r, f, g, f1, f2, g1, r2, r2c, o).map { it.id }.toSet()
    }

    private suspend fun forest(): Forest {
        val r = root("scopeprobeqx R")
        val f = child(r, "scopeprobeqx F")
        val g = child(r, "scopeprobeqx G")
        val f1 = child(f, "scopeprobeqx F1")
        val f2 = child(f1, "scopeprobeqx F2")
        val g1 = child(g, "scopeprobeqx G1")
        val r2 = root("scopeprobeqx R2")
        val r2c = child(r2, "scopeprobeqx R2c")
        val o = orphan("scopeprobeqx O")
        return Forest(r, f, g, f1, f2, g1, r2, r2c, o)
    }

    @Test
    fun `S11 principal root ids scope exactly the listed subtrees on the ITEM corpus`(): Unit =
        runBlocking {
            val t = forest()
            val term = listOf("scopeprobeqx")

            suspend fun scoped(
                analyzer: Analyzer,
                vararg roots: WorkItem,
            ): Set<UUID> = owners(Corpus.ITEM, term, analyzer, ScopeFilter(rootIds = roots.map { it.id }.toSet()))

            Analyzer.entries.forEach { a ->
                assertEquals(ids(t.f, t.f1, t.f2), scoped(a, t.f), "$a: a non-depth-0 root id scopes exactly its subtree, itself included")
                assertEquals(ids(t.r, t.f, t.g, t.f1, t.f2, t.g1), scoped(a, t.r), "$a: a depth-0 root id scopes the whole tree")
                assertEquals(ids(t.f, t.f1, t.f2, t.r2, t.r2c), scoped(a, t.f, t.r2), "$a: several ids are a union")
                assertEquals(ids(t.g, t.g1), scoped(a, t.g), "$a")
            }
            assertEquals(
                t.everything,
                owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(rootIds = null)),
                "fixture: with no root constraint the orphan is searchable, so its absence above is an exclusion"
            )
        }

    @Test
    fun `S11 principal root ids are ANDed with ancestor and item filters`(): Unit =
        runBlocking {
            val t = forest()
            val term = listOf("scopeprobeqx")
            val inF = setOf(t.f.id)

            assertEquals(
                emptySet(),
                owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(rootIds = inF, ancestorId = t.g.id)),
                "G's subtree lies outside F's scope"
            )
            assertEquals(ids(t.f1, t.f2), owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(rootIds = inF, ancestorId = t.f1.id)))
            assertEquals(ids(t.f1), owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(rootIds = inF, itemId = t.f1.id)))
            assertEquals(emptySet(), owners(Corpus.ITEM, term, Analyzer.SUBSTRING, ScopeFilter(rootIds = inF, itemId = t.g1.id)))
        }

    // -- window ------------------------------------------------------------------------------------------------------

    @Test
    fun `S12 window bounds the number of candidates per call`(): Unit =
        runBlocking {
            repeat(30) { root("windowprobe $it") }

            assertEquals(10, cands(Corpus.ITEM, listOf("windowprobe"), Analyzer.SUBSTRING, window = 10).size)
            assertEquals(1, cands(Corpus.ITEM, listOf("windowprobe"), Analyzer.STEMMED, window = 1).size)
            assertEquals(30, cands(Corpus.ITEM, listOf("windowprobe"), Analyzer.SUBSTRING, window = 50).size)
        }

    @Test
    fun `S12 the default window is the candidate row constant`(): Unit =
        runBlocking {
            repeat(FTS_CANDIDATE_ROWS + 5) { root("defaultwindowprobe $it") }

            val size = index.candidates(Corpus.ITEM, TextQuery(listOf("defaultwindowprobe")), Analyzer.SUBSTRING, ScopeFilter()).size

            assertEquals(FTS_CANDIDATE_ROWS, size)
        }

    // -- index sync (the FTS triggers) ------------------------------------------------------------------------------

    @Test
    fun `updating a title makes the new title searchable and the old one not`(): Unit =
        runBlocking {
            val item = root("Original payload moniker")
            assertEquals(ids(item), owners(Corpus.ITEM, listOf("moniker"), Analyzer.SUBSTRING))

            assertNotNull(provider.workItemRepository().update(item.copy(title = "Refreshed contraption identifier")))

            Analyzer.entries.forEach { analyzer ->
                assertEquals(ids(item), owners(Corpus.ITEM, listOf("contraption"), analyzer), "$analyzer finds the new title")
                assertEquals(emptySet(), owners(Corpus.ITEM, listOf("moniker"), analyzer), "$analyzer no longer finds the old title")
            }
        }

    @Test
    fun `deleting an item removes it from the index`(): Unit =
        runBlocking {
            val item = root("Doomed transient marker")
            assertEquals(ids(item), owners(Corpus.ITEM, listOf("transient"), Analyzer.SUBSTRING))

            assertTrue(provider.workItemRepository().delete(item.id))

            Analyzer.entries.forEach { analyzer ->
                assertEquals(emptySet(), owners(Corpus.ITEM, listOf("transient"), analyzer), "$analyzer")
            }
        }

    // -- titles --------------------------------------------------------------------------------------------------------

    @Test
    fun `titles returns the title of every requested item and omits unknown ids`(): Unit =
        runBlocking {
            val a = root("First title")
            val b = child(a, "Second title")
            val unknown = UUID.randomUUID()

            val titles = index.titles(setOf(a.id, b.id, unknown))

            assertEquals(mapOf(a.id to "First title", b.id to "Second title"), titles)
            assertEquals(emptyMap(), index.titles(emptySet()))
        }

    // -- NOTE corpus ------------------------------------------------------------------------------------------------------

    @Test
    fun `S12 NOTE candidates identify the note its owner and key and report the body field`(): Unit =
        runBlocking {
            val owner = root("Task with note")
            val saved = note(owner, "design-doc", "OAuth authentication design document")
            val other = root("Item with other note")
            note(other, "elsewhere", "completely different subject")

            Analyzer.entries.forEach { analyzer ->
                val c = cands(Corpus.NOTE, listOf("authentication"), analyzer).single()
                assertEquals(saved.id, c.id, "$analyzer: the candidate id is the NOTE id")
                assertEquals(owner.id, c.ownerItemId, "$analyzer")
                assertEquals("design-doc", c.noteKey, "$analyzer")
                assertEquals("body", c.field, "$analyzer")
                assertTrue("<mark>authentication</mark>" in c.snippet.lowercase(), "$analyzer: ${c.snippet}")
            }
        }

    @Test
    fun `S12 NOTE analyzers differ the same way the ITEM analyzers do`(): Unit =
        runBlocking {
            val stemmed = root("User management feature")
            note(stemmed, "impl", "authenticated users receive session tokens via the API")
            val infix = root("OAuth item")
            note(infix, "design", "Implement the OAuth flow using bearer tokens")

            assertEquals(ids(stemmed), owners(Corpus.NOTE, listOf("authentication"), Analyzer.STEMMED))
            assertEquals(emptySet(), owners(Corpus.NOTE, listOf("authentication"), Analyzer.SUBSTRING))
            // "auth" is a substring of both "OAuth" and "authenticated"; it is a word of neither.
            assertEquals(ids(stemmed, infix), owners(Corpus.NOTE, listOf("auth"), Analyzer.SUBSTRING))
            assertEquals(emptySet(), owners(Corpus.NOTE, listOf("auth"), Analyzer.STEMMED))
        }

    @Test
    fun `S12 NOTE corpus searches special characters and operator words literally`(): Unit =
        runBlocking {
            val withQuotes = root("quote host")
            note(withQuotes, "q", "say \"hello\" there")
            val noQuotes = root("plain host")
            note(noQuotes, "p", "say hello there")
            val notBad = root("not host")
            note(notBad, "n", "good NOT bad")
            val justGood = root("good host")
            note(justGood, "g", "just good")
            val columnLiteral = root("column host")
            note(columnLiteral, "c", "body:foo thing")

            assertEquals(
                ids(withQuotes),
                owners(Corpus.NOTE, listOf("\"hello\""), Analyzer.SUBSTRING),
                "the unquoted note has no quote characters"
            )
            assertEquals(ids(notBad), owners(Corpus.NOTE, listOf("NOT", "bad"), Analyzer.SUBSTRING), "the 'just good' note lacks the words")
            assertEquals(ids(notBad), owners(Corpus.NOTE, listOf("NOT", "bad"), Analyzer.STEMMED))
            assertEquals(ids(columnLiteral), owners(Corpus.NOTE, listOf("body:foo"), Analyzer.SUBSTRING))
        }

    @Test
    fun `S12 NOTE itemId and ancestorId filters use the owning item`(): Unit =
        runBlocking {
            val top = root("Root feature")
            val mid = child(top, "Child task")
            val leaf = child(mid, "Grandchild task")
            val outside = root("Outside feature")
            note(top, "root-note", "OAuth authentication requirements document")
            note(mid, "child-note", "authentication implementation details")
            note(leaf, "gc-note", "OAuth token management authentication")
            note(outside, "outside-note", "authentication module in separate tree")
            val term = listOf("authentication")

            assertEquals(ids(top, mid, leaf), owners(Corpus.NOTE, term, Analyzer.SUBSTRING, ScopeFilter(ancestorId = top.id)))
            assertEquals(ids(mid, leaf), owners(Corpus.NOTE, term, Analyzer.SUBSTRING, ScopeFilter(ancestorId = mid.id)))
            assertEquals(ids(mid), owners(Corpus.NOTE, term, Analyzer.SUBSTRING, ScopeFilter(itemId = mid.id)))
            assertEquals(emptySet(), owners(Corpus.NOTE, term, Analyzer.SUBSTRING, ScopeFilter(itemId = UUID.randomUUID())))
        }

    @Test
    fun `S10 principal tags on the NOTE corpus are checked on the owning item`(): Unit =
        runBlocking {
            val f =
                tagFixture { title, tags ->
                    val host = root(title, tags = tags)
                    note(host, "spec", "notetagprobeqx body")
                    host
                }
            val term = listOf("notetagprobeqx")

            suspend fun scoped(vararg principalTags: String): Set<UUID> =
                owners(Corpus.NOTE, term, Analyzer.SUBSTRING, ScopeFilter(principalTagsAny = principalTags.toSet()))

            assertEquals(
                f.all,
                owners(Corpus.NOTE, term, Analyzer.SUBSTRING, ScopeFilter()),
                "fixture: every note is searchable without a tag scope"
            )
            assertEquals(f.withAlpha, scoped("alpha"))
            assertEquals(emptySet(), scoped("Alpha"))
            assertEquals(emptySet(), scoped("a_c"))
            assertEquals(emptySet(), scoped("a%"))
            assertEquals(ids(f.plan), scoped("plan"))
            assertEquals(emptySet(), scoped("alpha,beta"))
        }

    @Test
    fun `S11 principal root ids on the NOTE corpus are checked on the owning item`(): Unit =
        runBlocking {
            val t = forest()
            listOf(t.r, t.f, t.g, t.f1, t.f2, t.g1, t.r2, t.r2c, t.o).forEach { note(it, "n", "notescopeprobeqx body") }
            val term = listOf("notescopeprobeqx")

            suspend fun scoped(vararg roots: WorkItem): Set<UUID> =
                owners(Corpus.NOTE, term, Analyzer.SUBSTRING, ScopeFilter(rootIds = roots.map { it.id }.toSet()))

            assertEquals(ids(t.f, t.f1, t.f2), scoped(t.f))
            assertEquals(ids(t.r, t.f, t.g, t.f1, t.f2, t.g1), scoped(t.r))
            assertEquals(ids(t.f, t.f1, t.f2, t.r2, t.r2c), scoped(t.f, t.r2))
            assertEquals(
                t.everything,
                owners(Corpus.NOTE, term, Analyzer.SUBSTRING, ScopeFilter(rootIds = null)),
                "fixture: the orphan's note is searchable without a root constraint"
            )
        }

    @Test
    fun `an empty note body is indexed without error and never matches`(): Unit =
        runBlocking {
            val withEmpty = root("Item with empty note")
            note(withEmpty, "stub", "")
            val populated = root("Item with content")
            note(populated, "real", "lookup-me-find-this-term")

            Analyzer.entries.forEach { analyzer ->
                assertEquals(ids(populated), owners(Corpus.NOTE, listOf("lookup-me-find-this-term"), analyzer), "$analyzer")
            }
        }

    @Test
    fun `updating a note body re-indexes it and deleting the notes removes them`(): Unit =
        runBlocking {
            val host = root("Note host")
            note(host, "k", "original gizmo wording")
            assertEquals(ids(host), owners(Corpus.NOTE, listOf("gizmo"), Analyzer.SUBSTRING))

            note(host, "k", "replacement contraption wording")

            Analyzer.entries.forEach { analyzer ->
                assertEquals(ids(host), owners(Corpus.NOTE, listOf("contraption"), analyzer), "$analyzer finds the new body")
                assertEquals(emptySet(), owners(Corpus.NOTE, listOf("gizmo"), analyzer), "$analyzer no longer finds the old body")
            }

            provider.noteRepository().deleteByItemId(host.id)

            Analyzer.entries.forEach { analyzer ->
                assertEquals(emptySet(), owners(Corpus.NOTE, listOf("contraption"), analyzer), "$analyzer after delete")
            }
        }

    @Test
    fun `S12 NOTE window bounds the candidates`(): Unit =
        runBlocking {
            repeat(15) { note(root("host $it"), "k", "notewindowprobe number $it") }

            assertEquals(5, cands(Corpus.NOTE, listOf("notewindowprobe"), Analyzer.SUBSTRING, window = 5).size)
            assertEquals(15, cands(Corpus.NOTE, listOf("notewindowprobe"), Analyzer.SUBSTRING, window = 50).size)
        }

    @Test
    fun `the ITEM corpus never returns note text and the NOTE corpus never returns item text`(): Unit =
        runBlocking {
            val host = root("corpusseparationtitle")
            note(host, "k", "corpusseparationbody")

            assertEquals(ids(host), owners(Corpus.ITEM, listOf("corpusseparationtitle"), Analyzer.SUBSTRING))
            assertEquals(emptySet(), owners(Corpus.ITEM, listOf("corpusseparationbody"), Analyzer.SUBSTRING))
            assertEquals(ids(host), owners(Corpus.NOTE, listOf("corpusseparationbody"), Analyzer.SUBSTRING))
            assertEquals(emptySet(), owners(Corpus.NOTE, listOf("corpusseparationtitle"), Analyzer.SUBSTRING))
        }
}

/**
 * SearchService over the real SQLite engine: the end-to-end port of the retired `*FtsPaginationTest` suites
 * (items 0ba7c92d S1-S5/S7 and the note analogs S6a/S6b) and the AUTO-mode fusion cases of the retired FTS tests.
 *
 * Oracles: `MAX_FTS_RESULTS` / `SearchResult` KDoc (cap before slice; totalHits page-invariant; score descending, ties
 * ascending by domain id: work-item id for items, note id for notes) and the RRF formula
 * `sum 1 / (60 + rank_in_source)` with rank 1 as the best of a list (Cormack and Clarke, k = 60). Score multisets
 * below are computed by hand, never read back from the implementation.
 */
class SearchServiceOnSqliteTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val provider get() = sqliteDb.repositoryProvider()
    private val service get() = SearchService(provider.searchIndex())

    private suspend fun item(
        title: String,
        summary: String = "",
        parent: WorkItem? = null,
    ): WorkItem =
        assertNotNull(
            provider.workItemRepository().create(
                WorkItem(
                    title = title,
                    summary = summary,
                    parentId = parent?.id,
                    depth = (parent?.depth ?: -1) + 1,
                    rootId = parent?.let { it.rootId ?: it.id },
                )
            )
        )

    private suspend fun note(
        owner: WorkItem,
        body: String,
        id: UUID = UUID.randomUUID(),
        key: String = "note",
    ): Note = assertNotNull(provider.noteRepository().upsert(Note(id = id, itemId = owner.id, key = key, role = "work", body = body)))

    private suspend fun search(
        query: String,
        corpus: Corpus = Corpus.ITEM,
        mode: SearchMatchMode = SearchMatchMode.AUTO,
        limit: Int = 100,
        offset: Int = 0,
        ancestorId: UUID? = null,
    ) = service.search(
        SearchRequest(
            query = query,
            corpus = corpus,
            matchMode = mode,
            ancestorId = ancestorId,
            limit = limit,
            offset = offset,
            access = AccessScope.unrestricted(),
        )
    )

    private fun approxEquals(
        a: List<Double>,
        b: List<Double>,
        eps: Double = 1e-9,
    ) = a.size == b.size && a.zip(b).all { (x, y) -> abs(x - y) < eps }

    // -- items: pagination ports ------------------------------------------------------------------------------------

    @Test
    fun `S1 pages at offsets 0 10 and 20 partition the bulk result with no duplicates or skips`(): Unit =
        runBlocking {
            val marker = "s1partitionmark"
            val seeded = (1..25).map { item("$marker item $it", summary = "payload $it") }

            val bulk = search(marker)
            assertEquals(25, bulk.hits.size)
            assertEquals(seeded.map { it.id }.toSet(), bulk.hits.map { it.itemId }.toSet())

            val concatenated = (0 until 25 step 10).flatMap { off -> search(marker, limit = 10, offset = off).hits }
            assertEquals(
                bulk.hits.map { it.itemId },
                concatenated.map { it.itemId },
                "concatenated pages equal the bulk call element for element"
            )
            assertEquals(25, concatenated.map { it.itemId }.toSet().size, "no item repeats across pages")
            assertEquals(bulk.hits.map { it.itemId }, search(marker).hits.map { it.itemId }, "replay returns the identical order")
        }

    @Test
    fun `S2 pages crossing a tied score are ordered by ascending item id`(): Unit =
        runBlocking {
            // 3 items only the trigram analyzer matches (query glued into one token) and 3 only the text analyzer
            // matches ("catalog" stems like "cataloging" but is not a substring). Each analyzer therefore ranks
            // its three items 1..3 whatever their physical order, so the fused scores are the multiset
            // {1/61, 1/61, 1/62, 1/62, 1/63, 1/63} by the RRF formula.
            val trigramOnly = (1..3).map { item("zzzcatalogingzzz$it") }
            val textOnly = (1..3).map { item("catalog", summary = "tie filler $it") }

            val bulk = search("cataloging")

            assertEquals((trigramOnly + textOnly).map { it.id }.toSet(), bulk.hits.map { it.itemId }.toSet())
            val expected = listOf(1.0 / 61, 1.0 / 61, 1.0 / 62, 1.0 / 62, 1.0 / 63, 1.0 / 63).sorted()
            assertTrue(approxEquals(expected, bulk.hits.map { it.score }.sorted()), "score multiset, got ${bulk.hits.map { it.score }}")

            val expectedOrder = bulk.hits.sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.itemId })
            assertEquals(expectedOrder.map { it.itemId }, bulk.hits.map { it.itemId }, "score descending then ascending item id")

            val paged = (0 until 6 step 2).flatMap { off -> search("cataloging", limit = 2, offset = off).hits }
            assertEquals(bulk.hits.map { it.itemId }, paged.map { it.itemId })
            val first = search("cataloging", limit = 1, offset = 0).hits.single()
            val second = search("cataloging", limit = 1, offset = 1).hits.single()
            assertEquals(bulk.hits[0].itemId, first.itemId)
            assertEquals(bulk.hits[1].itemId, second.itemId)
            assertTrue(first.itemId != second.itemId, "a tied pair split across pages must not repeat")
        }

    @Test
    fun `S3 totalHits and truncated do not depend on the offset`(): Unit =
        runBlocking {
            val marker = "s3invariantmark"
            repeat(25) { item("$marker entry $it") }

            val pages = listOf(0, 10, 20).map { search(marker, limit = 10, offset = it) }

            assertEquals(listOf(25, 25, 25), pages.map { it.totalHits })
            assertEquals(listOf(false, false, false), pages.map { it.truncated })
        }

    @Test
    fun `S4 a document matched by both analyzers keeps the same absolute position at any offset`(): Unit =
        runBlocking {
            val marker = "crosstablemark"
            repeat(30) { item("zz${marker}zz$it") }
            val x = item(marker)

            val bulk = search(marker)
            assertEquals(31, bulk.hits.size)
            val posX = bulk.hits.indexOfFirst { it.itemId == x.id }
            assertTrue(posX >= 0)
            assertTrue("trigram" in bulk.hits[posX].matchedIn && "text" in bulk.hits[posX].matchedIn, "fixture: X matches both analyzers")

            val offsetA = maxOf(0, posX - 5)
            val offsetB = maxOf(0, posX - 2)
            val localA = search(marker, limit = 15, offset = offsetA).hits.indexOfFirst { it.itemId == x.id }
            val localB = search(marker, limit = 15, offset = offsetB).hits.indexOfFirst { it.itemId == x.id }

            assertTrue(localA >= 0 && localB >= 0)
            assertEquals(posX, offsetA + localA)
            assertEquals(posX, offsetB + localB)
        }

    @Test
    fun `S5 an offset past the match count returns an empty page with unchanged totals`(): Unit =
        runBlocking {
            val marker = "s5boundarymark"
            repeat(25) { item("$marker probe $it") }

            val base = search(marker, limit = 20, offset = 0)
            val past = search(marker, limit = 20, offset = 100)

            assertTrue(past.hits.isEmpty())
            assertNull(past.nextOffset)
            assertEquals(base.totalHits, past.totalHits)
        }

    @Test
    fun `boundary 110 matches are capped at 100 with truncated and the cap rows are reachable`(): Unit =
        runBlocking {
            val marker = "capboundary"
            repeat(110) { item("zz${marker}zz$it") }

            val first = search(marker, mode = SearchMatchMode.SUBSTRING, limit = 20)
            assertEquals(100, first.totalHits)
            assertTrue(first.truncated)

            listOf(100, 101).forEach { off ->
                val page = search(marker, mode = SearchMatchMode.SUBSTRING, limit = 10, offset = off)
                assertTrue(page.hits.isEmpty(), "offset $off")
                assertNull(page.nextOffset, "offset $off")
                assertEquals(100, page.totalHits, "offset $off")
            }
            val last = search(marker, mode = SearchMatchMode.SUBSTRING, limit = 5, offset = 99)
            assertEquals(1, last.hits.size)
            assertNull(last.nextOffset)
            val all =
                (0 until 100 step 20).flatMap { off ->
                    search(marker, mode = SearchMatchMode.SUBSTRING, limit = 20, offset = off).hits.map { it.itemId }
                }
            assertEquals(100, all.toSet().size, "the 100 capped rows are distinct and fully reachable")
        }

    @Test
    fun `S7 pagination partitions correctly under a deep ancestor scope`(): Unit =
        runBlocking {
            val marker = "deepchainmark"
            val top = item("$marker root")
            val chain = mutableListOf(top)
            repeat(6) { chain += item("$marker depth $it", parent = chain.last()) }
            val outsideRoot = item("$marker outside root")
            item("$marker outside child", parent = outsideRoot)

            val bulk = search(marker, ancestorId = top.id)
            assertEquals(chain.map { it.id }.toSet(), bulk.hits.map { it.itemId }.toSet(), "exactly the root plus its chain")
            assertEquals(7, bulk.hits.size)
            val concatenated = listOf(0, 3, 6).flatMap { search(marker, ancestorId = top.id, limit = 3, offset = it).hits }
            assertEquals(bulk.hits.map { it.itemId }, concatenated.map { it.itemId })
        }

    @Test
    fun `a zero-match query reports no hits and no next offset`(): Unit =
        runBlocking {
            item("Something else entirely, unrelated to the probe term")

            val result = search("unmatchedzzzprobetermxyz", limit = 20)

            assertEquals(0, result.totalHits)
            assertNull(result.nextOffset)
            assertTrue(result.hits.isEmpty())
        }

    @Test
    fun `a lowercase SUBSTRING query matches uppercase titles and pages partition the bulk order`(): Unit =
        runBlocking {
            repeat(5) { item("Title Containing MixedCaseProbe Segment $it") }

            val bulk = search("mixedcaseprobe", mode = SearchMatchMode.SUBSTRING, limit = 10)
            assertEquals(5, bulk.totalHits)
            val paged =
                search("mixedcaseprobe", mode = SearchMatchMode.SUBSTRING, limit = 2, offset = 0).hits +
                    search("mixedcaseprobe", mode = SearchMatchMode.SUBSTRING, limit = 3, offset = 2).hits
            assertEquals(bulk.hits.map { it.itemId }, paged.map { it.itemId })
        }

    @Test
    fun `AUTO fuses both analyzers so a document in both outranks one in a single analyzer`(): Unit =
        runBlocking {
            val both = item("authentication service", summary = "OAuth authenticated flow")
            val textOnly = item("identity verification module", summary = "authenticated middleware layer")

            val result = search("authentication")
            val byId = result.hits.associateBy { it.itemId }

            assertEquals(listOf(both.id, textOnly.id), result.hits.map { it.itemId })
            assertEquals(setOf(Ranker.label(Analyzer.SUBSTRING), Ranker.label(Analyzer.STEMMED)), byId.getValue(both.id).matchedIn.toSet())
            assertEquals(listOf(Ranker.label(Analyzer.STEMMED)), byId.getValue(textOnly.id).matchedIn)
            assertNotNull(byId.getValue(both.id).trigramRank)
            assertNotNull(byId.getValue(both.id).textRank)
            assertNull(byId.getValue(textOnly.id).trigramRank)
            assertNotNull(byId.getValue(textOnly.id).textRank)
            assertTrue(byId.getValue(both.id).score > byId.getValue(textOnly.id).score)
        }

    @Test
    fun `SUBSTRING hits report only the trigram source and TEXT hits only the text source`(): Unit =
        runBlocking {
            item("OAuth integration task", summary = "authenticated user")

            val substring = search("auth", mode = SearchMatchMode.SUBSTRING).hits
            val text = search("authentication", mode = SearchMatchMode.TEXT).hits

            assertTrue(substring.isNotEmpty() && substring.all { it.matchedIn == listOf("trigram") })
            assertTrue(text.isNotEmpty() && text.all { it.matchedIn == listOf("text") })
        }

    @Test
    fun `item hits carry the item title`(): Unit =
        runBlocking {
            val a = item("Quarterly zebracrossing audit")

            val hit = search("zebracrossing").hits.single()

            assertEquals(a.id, hit.itemId)
            assertEquals("Quarterly zebracrossing audit", hit.title)
            assertEquals("item", hit.kind)
        }

    // -- notes: pagination ports --------------------------------------------------------------------------------------

    @Test
    fun `S6a note pages partition the bulk result with no duplicates or skips`(): Unit =
        runBlocking {
            val marker = "s6anotepartitionmark"
            val hosts = (1..25).map { item("host item $it") }
            hosts.forEachIndexed { i, host -> note(host, "$marker payload $i") }

            val bulk = search(marker, corpus = Corpus.NOTE)
            assertEquals(25, bulk.hits.size)
            assertEquals(hosts.map { it.id }.toSet(), bulk.hits.map { it.itemId }.toSet())
            assertTrue(bulk.hits.all { it.kind == "note" })
            assertTrue(bulk.hits.all { it.noteKey == "note" })

            val concatenated = (0 until 25 step 10).flatMap { search(marker, corpus = Corpus.NOTE, limit = 10, offset = it).hits }
            assertEquals(bulk.hits.map { it.itemId }, concatenated.map { it.itemId })
            assertEquals(25, concatenated.map { it.itemId }.toSet().size)
        }

    @Test
    fun `S6b note pages crossing a tied score are ordered by ascending note id`(): Unit =
        runBlocking {
            val trigramNoteIds = listOf(10L, 20L, 30L).map { UUID(0L, it) }
            val textNoteIds = listOf(15L, 25L, 35L).map { UUID(0L, it) }
            val noteIdByOwner = mutableMapOf<UUID, UUID>()
            trigramNoteIds.forEachIndexed { i, nid ->
                val host = item("trigram host ${i + 1}")
                note(host, "zzzcatalogingzzz${i + 1}", id = nid)
                noteIdByOwner[host.id] = nid
            }
            textNoteIds.forEachIndexed { i, nid ->
                val host = item("text host ${i + 1}")
                note(host, "catalog filler ${i + 1}", id = nid)
                noteIdByOwner[host.id] = nid
            }

            val bulk = search("cataloging", corpus = Corpus.NOTE)

            assertEquals(noteIdByOwner.keys, bulk.hits.map { it.itemId }.toSet())
            val expected = listOf(1.0 / 61, 1.0 / 61, 1.0 / 62, 1.0 / 62, 1.0 / 63, 1.0 / 63).sorted()
            assertTrue(approxEquals(expected, bulk.hits.map { it.score }.sorted()), "score multiset, got ${bulk.hits.map { it.score }}")
            val expectedOrder =
                bulk.hits.sortedWith(
                    compareByDescending<SearchHit> { it.score }.thenBy { noteIdByOwner.getValue(it.itemId) }
                )
            assertEquals(expectedOrder.map { it.itemId }, bulk.hits.map { it.itemId }, "score descending then ascending NOTE id")
            val paged = (0 until 6 step 2).flatMap { search("cataloging", corpus = Corpus.NOTE, limit = 2, offset = it).hits }
            assertEquals(bulk.hits.map { it.itemId }, paged.map { it.itemId })
        }

    @Test
    fun `a zero-match note query reports no hits`(): Unit =
        runBlocking {
            note(item("host with an unrelated note"), "nothing relevant to the probe term here")

            val result = search("unmatchedzzznotetermxyz", corpus = Corpus.NOTE, limit = 20)

            assertEquals(0, result.totalHits)
            assertNull(result.nextOffset)
            assertTrue(result.hits.isEmpty())
        }

    @Test
    fun `note hits carry the owning item title and the note key`(): Unit =
        runBlocking {
            val host = item("Container item for titles")
            note(host, "the body mentions pomegranate", key = "spec")

            val hit = search("pomegranate", corpus = Corpus.NOTE).hits.single()

            assertEquals(host.id, hit.itemId)
            assertEquals("spec", hit.noteKey)
            assertEquals("body", hit.field)
            assertEquals("Container item for titles", hit.title)
            assertEquals("note", hit.kind)
        }
}
