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

    // --- Gap M8: default method hasReviewPhase on NoOpNoteSchemaService ---

    @Test
    fun `NoOp hasReviewPhase default method returns false via getSchemaForTags returning null`(): Unit {
        // The hasReviewPhase default method on NoteSchemaService calls getSchemaForTags and
        // checks if any entry has role=REVIEW. When getSchemaForTags returns null (as NoOp does),
        // the elvis operator returns false. This test verifies the default method contract.
        val result = NoOpNoteSchemaService.hasReviewPhase(listOf("any-tag"))
        assertFalse(result, "hasReviewPhase should return false when getSchemaForTags returns null")
    }

    @Test
    fun `NoOp returns null which causes hasReviewPhase to return false via default implementation`(): Unit {
        // Verify that a custom NoteSchemaService returning null also gets false from the default
        // hasReviewPhase — confirming the default method logic is correct.
        val customService = object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>) = null
        }
        assertFalse(customService.hasReviewPhase(listOf("tag1")))
        assertFalse(customService.hasReviewPhase(emptyList()))
    }
}
