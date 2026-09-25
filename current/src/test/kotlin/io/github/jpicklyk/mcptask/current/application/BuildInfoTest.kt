package io.github.jpicklyk.mcptask.current.application

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun `loadVersion returns unknown when the resource stream is null`() {
        assertEquals("unknown", BuildInfo.loadVersion(null))
    }

    @Test
    fun `loadVersion reads the version property from a properties stream`() {
        val stream = ByteArrayInputStream("version=9.9.9\n".toByteArray())
        assertEquals("9.9.9", BuildInfo.loadVersion(stream))
    }

    @Test
    fun `loadVersion falls back to unknown when the version property is absent`() {
        val stream = ByteArrayInputStream("other=value\n".toByteArray())
        assertEquals("unknown", BuildInfo.loadVersion(stream))
    }
}
