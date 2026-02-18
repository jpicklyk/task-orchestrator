plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

version = "0.1.0-alpha-01"
group = "io.github.jpicklyk"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin standard library and coroutines
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines)

    // MCP SDK
    implementation(libs.mcp.sdk)

    // Database
    implementation(libs.sqlite)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    // Flyway migration
    implementation(libs.flyway.core)

    // Logging
    implementation(libs.slf4j)
    implementation(libs.logback)

    // JSON serialization/deserialization
    implementation(libs.kotlinx.serialization.json)

    // YAML parsing
    implementation(libs.snakeyaml)

    // Ktor BOM — version-aligns all ktor-* artifacts with the SDK's transitive Ktor
    implementation(platform(libs.ktor.bom))
    // CIO engine — not provided transitively by the MCP SDK, must be declared explicitly
    implementation(libs.ktor.server.cio)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)

    // H2 in-memory database for testing
    testImplementation("com.h2database:h2:2.2.224")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.github.jpicklyk.mcptask.current.CurrentMainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    archiveBaseName.set("mcp-task-orchestrator-current")
}
