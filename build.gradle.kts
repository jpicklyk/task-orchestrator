plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "io.github.jpicklyk"
version = "0.1.0"

// Set the archive name explicitly to match Dockerfile expectation
tasks.jar {
    archiveBaseName.set("mcp-task-orchestrator")
}

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

    // Flyway migration - uncomment when ready to implement Flyway
    // implementation("org.flywaydb:flyway-core:9.20.0")
    // implementation("org.flywaydb:flyway-database-sqlite:9.20.0") // For SQLite support

    // Logging
    implementation(libs.slf4j)
    //implementation(libs.slf4j.nop)
    implementation(libs.logback)

    // JSON serialization/deserialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)

    // Add H2 database driver for in memory db testing
    testImplementation("com.h2database:h2:2.2.224")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(23)
}

application {
    mainClass.set("MainKt")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "MainKt"
        )
    }

    // Include all dependencies to create a fat jar
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    // Exclude signature files from dependencies to avoid security conflicts
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}