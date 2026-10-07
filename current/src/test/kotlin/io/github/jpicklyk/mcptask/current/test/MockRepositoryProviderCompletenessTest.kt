package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Guards the shared [MockRepositoryProvider]: every accessor declared on [RepositoryProvider]
 * must be stubbed, so adding an accessor to the interface fails here instead of in a distant test.
 */
class MockRepositoryProviderCompletenessTest {
    @Test
    fun `every RepositoryProvider accessor is stubbed on the shared mock`() {
        val provider = MockRepositoryProvider().provider
        val accessors = RepositoryProvider::class.java.methods.filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
        check(accessors.size >= 9) { "expected the 9 RepositoryProvider accessors, found ${accessors.map { it.name }}" }
        for (accessor in accessors) {
            val result =
                try {
                    accessor.invoke(provider)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw AssertionError("RepositoryProvider.${accessor.name}() is not stubbed on MockRepositoryProvider", e.cause)
                }
            if (accessor.name != "database") assertNotNull(result, "RepositoryProvider.${accessor.name}() returned null")
        }
    }
}
