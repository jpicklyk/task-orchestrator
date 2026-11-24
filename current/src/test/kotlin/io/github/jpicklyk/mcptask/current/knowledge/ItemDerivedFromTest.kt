package io.github.jpicklyk.mcptask.current.knowledge

import io.github.jpicklyk.mcptask.current.model.knowledge.DocumentKind
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `links[].to` grammar of a created item (task-scope D2). */
class ItemDerivedFromTest {
    private val id = "a1000001-0000-4000-8000-000000000001"

    @Test
    fun `a note source is item id hash key, the key kept verbatim up to the end`() {
        assertEquals(ItemDerivedFromRef.Note(IdRef(id), "goal"), ItemDerivedFromRef.parse("item:$id#goal"))
        assertEquals(ItemDerivedFromRef.Note(IdRef("a1000001"), "a#b c"), ItemDerivedFromRef.parse("ITEM:a1000001#a#b c"))
    }

    @Test
    fun `a document source takes an optional case-insensitive kind and an optional anchor`() {
        assertEquals(ItemDerivedFromRef.Document(DocumentKind.PAGE, "rep", "f-12"), ItemDerivedFromRef.parse("page:rep#f-12"))
        assertEquals(ItemDerivedFromRef.Document(DocumentKind.PLAN, "p", null), ItemDerivedFromRef.parse("Plan:p"))
        assertEquals(ItemDerivedFromRef.Document(DocumentKind.RULE, "r", null), ItemDerivedFromRef.parse("rule:r"))
        assertEquals(ItemDerivedFromRef.Document(null, "bare", "h"), ItemDerivedFromRef.parse("bare#h"))
    }

    @Test
    fun `malformed sources parse to null`() {
        for (text in listOf("", "  ", "item:zz#k", "item:$id", "item:$id#", "item:$id#  ", "x:foo", "plan:", "#only")) {
            assertNull(ItemDerivedFromRef.parse(text), "'$text' is no source")
        }
    }
}
