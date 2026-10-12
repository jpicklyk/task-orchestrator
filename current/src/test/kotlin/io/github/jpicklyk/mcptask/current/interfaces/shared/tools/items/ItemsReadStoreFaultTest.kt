package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.seedItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AR-27 carry-in (note `carry-in-ar27` on f35528b3): the `items_read` selects `context`, `next` and `blocked` must not swallow
 * a store fault. The 3.x views they replace answered an empty list on a failed read (`legacyReadOrNull ?: emptyList()`), which
 * looks like "nothing". Here a store read fails mid-call (the dependency table every one of these views reads is renamed away),
 * and each select must fail the call through the envelope (`ok: false` with an error, no `data`), never answer an ok page.
 */
class ItemsReadStoreFaultTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val context by lazy { ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

    private fun seed(): String = runBlocking { seedItem(context, buildJsonObject { put("title", JsonPrimitive("AR-27 item")) }) }

    private fun breakStore() = rawExec(db.jdbcUrl, "ALTER TABLE dependencies RENAME TO dependencies_gone")

    private fun call(
        select: String,
        args: JsonObject = JsonObject(emptyMap())
    ): JsonObject = runBlocking { ItemContextProbe(select).envelope(context, args) }

    private fun assertFailedNotEmpty(
        body: JsonObject,
        what: String
    ) {
        assertEquals("false", body["ok"]?.jsonPrimitive?.content, "$what must fail the call, not answer an ok page: $body")
        assertNull(body["data"], "$what must carry no data on a store fault: $body")
        val code =
            assertNotNull(
                body["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content,
                "$what carries an error code: $body"
            )
        assertTrue(code.isNotBlank(), "$what: $body")
    }

    @Test
    fun `control - with a healthy store every select answers ok`() {
        val id = seed()
        assertEquals("true", call("next")["ok"]?.jsonPrimitive?.content)
        assertEquals("true", call("blocked")["ok"]?.jsonPrimitive?.content)
        assertEquals("true", call("context")["ok"]?.jsonPrimitive?.content)
        assertEquals("true", call("context", buildJsonObject { put("itemId", id) })["ok"]?.jsonPrimitive?.content)
    }

    @Test
    fun `next fails the call on a store fault instead of answering an empty page`() {
        seed()
        breakStore()
        assertFailedNotEmpty(call("next"), "items_read(next)")
    }

    @Test
    fun `blocked fails the call on a store fault instead of answering an empty page`() {
        seed()
        breakStore()
        assertFailedNotEmpty(call("blocked"), "items_read(blocked)")
    }

    @Test
    fun `context health fails the call on a store fault instead of answering empty lists`() {
        seed()
        breakStore()
        assertFailedNotEmpty(call("context"), "items_read(context)")
    }

    @Test
    fun `context for one item fails the call on a store fault instead of answering an open gate`() {
        val id = seed()
        breakStore()
        assertFailedNotEmpty(call("context", buildJsonObject { put("itemId", id) }), "items_read(context, itemId)")
    }
}
