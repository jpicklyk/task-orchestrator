// Root project — build aggregator only.
// Active module:   :current  (v3 MCP Task Orchestrator)
// Archived module: :clockwork (v2, deprecated — see clockwork/DEPRECATED.md)

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "io.github.jpicklyk"
    repositories {
        mavenCentral()
    }
}
