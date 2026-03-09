package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService

/**
 * Test double for [StatusLabelService] that allows explicit label configuration.
 * Defaults to the same hardcoded labels as [NoOpStatusLabelService].
 */
class TestStatusLabelService(
    private val labels: Map<String, String?> =
        mapOf(
            "start" to "in-progress",
            "complete" to "done",
            "block" to "blocked",
            "cancel" to "cancelled",
            "cascade" to "done"
        )
) : StatusLabelService {
    override fun resolveLabel(trigger: String): String? = labels[trigger]
}
