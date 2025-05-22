package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Test specifically for the blank parameter validation bug fix.
 */
class CreateTaskToolBlankParameterTest {
    private lateinit var tool: CreateTaskTool

    @BeforeEach
    fun setup() {
        tool = CreateTaskTool()
    }

    @Test
    fun `test empty title parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive(""),
                "summary" to JsonPrimitive("This is a test task")
            )
        )

        // Should throw an exception for empty title
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Title validation failed"))
        assertTrue(exception.message!!.contains("cannot be empty"))
    }

    @Test
    fun `test blank title parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("   "),
                "summary" to JsonPrimitive("This is a test task")
            )
        )

        // Should throw an exception for blank title
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Title validation failed"))
        assertTrue(exception.message!!.contains("cannot be empty"))
    }

    @Test
    fun `test empty summary parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("")
            )
        )

        // Should throw an exception for empty summary
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Summary validation failed"))
        assertTrue(exception.message!!.contains("cannot be empty"))
    }

    @Test
    fun `test blank summary parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("   ")
            )
        )

        // Should throw an exception for blank summary
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Summary validation failed"))
        assertTrue(exception.message!!.contains("cannot be empty"))
    }

    @Test
    fun `test valid title and summary pass validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a valid test task summary")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test whitespace-only title is rejected`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("\t\n  \r"),
                "summary" to JsonPrimitive("Valid summary")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Title validation failed"))
        assertTrue(exception.message!!.contains("cannot be empty"))
    }

    @Test
    fun `test whitespace-only summary is rejected`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Valid Title"),
                "summary" to JsonPrimitive("\t\n  \r")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Summary validation failed"))
        assertTrue(exception.message!!.contains("cannot be empty"))
    }
}
