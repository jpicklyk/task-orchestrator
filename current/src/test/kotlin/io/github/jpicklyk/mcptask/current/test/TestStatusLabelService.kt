package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.StatusLabelService

/**
 * Test double for [StatusLabelService] that allows explicit label configuration.
 * Defaults to the same hardcoded labels as [NoOpStatusLabelService].
 */
class TestStatusLabelService(
    val labels: Map<String, String?> =
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

/** The server config document carrying these labels as its `status_labels` section (absent trigger -> no label). */
fun TestStatusLabelService.asServerConfig(): ConfigDocument = ConfigDocument.EMPTY.copy(statusLabels = labels)
