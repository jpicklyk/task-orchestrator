plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "task-orchestrator"

// Active module — v3 MCP Task Orchestrator
include(":current")
