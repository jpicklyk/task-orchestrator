plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Define semantic version components (manually maintained)
val majorVersion = "2"
val minorVersion = "0"
val patchVersion = "0"

// Define release qualifier (empty for stable releases)
// Examples: "", "alpha-01", "beta-02", "rc-01"
val qualifier = "beta-01"


// Calculate build number from Git
fun calculateBuildNumberFromGit(): Int {
    // Option 1: Use Git commit count
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .redirectErrorStream(true)
        .start()
    process.waitFor(10, TimeUnit.SECONDS)
    val gitCount = process.inputStream.bufferedReader().readLine()?.trim()?.toIntOrNull() ?: 0
    return gitCount
}

// Construct the full version
val buildNumber = calculateBuildNumberFromGit()
val baseVersion = "$majorVersion.$minorVersion.$patchVersion"

val fullVersion = if (qualifier.isNotEmpty()) {
    "$baseVersion.$buildNumber-$qualifier"
} else {
    "$baseVersion.$buildNumber"
}

version = fullVersion
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
    //implementation(libs.slf4j.nop)
    implementation(libs.logback)

    // JSON serialization/deserialization
    implementation(libs.kotlinx.serialization.json)

    // YAML parsing
    implementation(libs.snakeyaml)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
    
    // Mockito for additional mocking support
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.mockito:mockito-core:5.8.0")

    // Add H2 database driver for in memory db testing
    testImplementation("com.h2database:h2:2.2.224")
}

tasks.test {
    useJUnitPlatform()
}

// Ensure the version is printed during build
tasks.build {
    dependsOn("printVersion")
}

// Set the archive name explicitly to match Dockerfile expectation
tasks.jar {
    archiveBaseName.set("mcp-task-orchestrator")
}

// Add a task to generate version information for runtime use
tasks.register("generateVersionInfo") {
    val outputDir = layout.buildDirectory.dir("generated/source/version")
    val versionFile = outputDir.get().file("VersionInfo.kt")

    inputs.property("version", version)
    inputs.property("qualifier", qualifier)
    outputs.file(versionFile)

    doLast {
        outputDir.get().asFile.mkdirs()
        versionFile.asFile.writeText(
            """
            // Generated file - do not modify!
            // Created from build.gradle.kts
            object VersionInfo {
                const val VERSION = "$fullVersion"
                const val MAJOR_VERSION = "$majorVersion"
                const val MINOR_VERSION = "$minorVersion"
                const val PATCH_VERSION = "$patchVersion"
                const val BUILD_NUMBER = "$buildNumber"
                const val QUALIFIER = "${qualifier.ifEmpty { "" }}"
                
                // Convenience functions
                fun isPreRelease(): Boolean = QUALIFIER.isNotEmpty()
                fun isAlphaRelease(): Boolean = QUALIFIER.startsWith("alpha")
                fun isBetaRelease(): Boolean = QUALIFIER.startsWith("beta")
                fun isRcRelease(): Boolean = QUALIFIER.startsWith("rc")
            }
        """.trimIndent()
        )
    }
}


// Log version information during build
tasks.register("printVersion") {
    doLast {
        println("Project Version: $fullVersion")
        println("  - Major: $majorVersion")
        println("  - Minor: $minorVersion")
        println("  - Patch: $patchVersion")
        println("  - Build: $buildNumber")
        println("  - Qualifier: ${qualifier.ifEmpty { "none" }}")
    }
}

// Print tag version (without build number) for git tag validation
tasks.register("printTagVersion") {
    doLast {
        val tagVersion = if (qualifier.isNotEmpty()) {
            "$baseVersion-$qualifier"
        } else {
            baseVersion
        }
        println(tagVersion)
    }
}

kotlin {
    jvmToolchain(23)
    sourceSets.main {
        kotlin.srcDir(layout.buildDirectory.dir("generated/source/version"))
    }
}

// Make sure the version info is generated before compilation
tasks.compileKotlin {
    dependsOn("generateVersionInfo")
}

application {
    mainClass.set("MainKt")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "MainKt",
            "Implementation-Version" to fullVersion
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