plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "task-orchestrator"

// Active module — v3 MCP Task Orchestrator
include(":current")

// Archived — Clockwork v2 (deprecated). Not built by default CI.
// See clockwork/DEPRECATED.md for build instructions.
include(":clockwork")
