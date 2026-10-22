package io.github.jpicklyk.mcptask.current.application.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkItemSchemaServiceTest {
    @Test
    fun `NoOp exposes empty catalogues`() {
        assertTrue(NoOpWorkItemSchemaService.getAllSchemas().isEmpty())
        assertTrue(NoOpWorkItemSchemaService.getAllTraits().isEmpty())
        assertTrue(NoOpWorkItemSchemaService.getTraitResources("any").isEmpty())
        assertTrue(NoOpWorkItemSchemaService.getTraitDispatch("any").isEmpty())
        assertEquals(emptyList(), NoOpWorkItemSchemaService.getLoadWarnings())
        assertNull(NoOpWorkItemSchemaService.getConfigFingerprint())
    }
}
