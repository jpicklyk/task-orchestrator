package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

/**
 * Tests for template-related validation in the CreateTaskTool
 */
class CreateTaskToolTemplateValidationTest {
    
    private lateinit var tool: CreateTaskTool
    
    @BeforeEach
    fun setup() {
        tool = CreateTaskTool()
    }
    
    @Test
    fun `test valid template ID parameter validation`() {
        val templateId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "templateIds" to JsonArray(listOf(JsonPrimitive(templateId.toString())))
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }
    
    @Test
    fun `test valid templateIds array validation`() {
        val templateId1 = UUID.randomUUID()
        val templateId2 = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "templateIds" to JsonArray(listOf(
                    JsonPrimitive(templateId1.toString()),
                    JsonPrimitive(templateId2.toString())
                ))
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }
    
    @Test
    fun `test invalid template ID parameter validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "templateIds" to JsonArray(listOf(JsonPrimitive("not-a-uuid")))
            )
        )

        // Should throw an exception for invalid template ID
        val exception = assertThrows<ToolValidationException> { tool.validateParams(params) }
        assertTrue(exception.message!!.contains("is not a valid UUID format"))
    }
    
    @Test
    fun `test invalid templateIds array validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "templateIds" to JsonPrimitive("not-an-array")
            )
        )

        // Should throw an exception for invalid templateIds
        val exception = assertThrows<ToolValidationException> { tool.validateParams(params) }
        assertTrue(exception.message!!.contains("'templateIds' must be an array"))
    }
    
    @Test
    fun `test invalid templateIds array item validation`() {
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "templateIds" to JsonArray(listOf(JsonPrimitive("not-a-uuid")))
            )
        )

        // Should throw an exception for invalid templateIds array item
        val exception = assertThrows<ToolValidationException> { tool.validateParams(params) }
        assertTrue(exception.message!!.contains("is not a valid UUID format"))
    }
}
