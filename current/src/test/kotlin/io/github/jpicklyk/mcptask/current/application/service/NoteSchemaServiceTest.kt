package io.github.jpicklyk.mcptask.current.application.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NoteSchemaServiceTest {

    @Test
    fun `NoOp returns null for any tags`() {
        assertNull(NoOpNoteSchemaService.getSchemaForTags(listOf("any-tag")))
        assertNull(NoOpNoteSchemaService.getSchemaForTags(emptyList()))
    }

    @Test
    fun `NoOp hasReviewPhase returns false`() {
        assertFalse(NoOpNoteSchemaService.hasReviewPhase(listOf("any-tag")))
    }

    @Test
    fun `NoOp returns null for empty tag list`() {
        assertNull(NoOpNoteSchemaService.getSchemaForTags(emptyList()))
    }

    @Test
    fun `NoOp returns null for multiple tags`() {
        assertNull(NoOpNoteSchemaService.getSchemaForTags(listOf("tag1", "tag2", "tag3")))
    }

    @Test
    fun `NoOp hasReviewPhase returns false for empty tags`() {
        assertFalse(NoOpNoteSchemaService.hasReviewPhase(emptyList()))
    }
}
