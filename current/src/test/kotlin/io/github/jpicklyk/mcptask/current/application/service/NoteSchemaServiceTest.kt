package io.github.jpicklyk.mcptask.current.application.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteSchemaServiceTest {
    @Test
    fun `NoOp exposes empty catalogues`() {
        assertTrue(NoOpNoteSchemaService.getAllSchemas().isEmpty())
        assertTrue(NoOpNoteSchemaService.getAllTraits().isEmpty())
        assertTrue(NoOpNoteSchemaService.getTraitResources("any").isEmpty())
        assertTrue(NoOpNoteSchemaService.getTraitDispatch("any").isEmpty())
        assertEquals(emptyList(), NoOpNoteSchemaService.getLoadWarnings())
        assertNull(NoOpNoteSchemaService.getConfigFingerprint())
    }

    @Test
    fun `NoteSchemaService typealias resolves to WorkItemSchemaService`() {
        // If the typealias is broken this assignment would fail to compile.
        val service: NoteSchemaService = NoOpNoteSchemaService
        val serviceAsAlias: WorkItemSchemaService = service
        assertNotNull(serviceAsAlias)
    }
}
