import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set("1.5.0")
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
    }
}

// Load version from centralized version.properties
val versionProps = Properties()
file("../version.properties").inputStream().use { versionProps.load(it) }
val vMajor = versionProps.getProperty("VERSION_MAJOR", "2")
val vMinor = versionProps.getProperty("VERSION_MINOR", "0")
val vPatch = versionProps.getProperty("VERSION_PATCH", "0")
val baseVersion = "$vMajor.$vMinor.$vPatch"

// On non-main CI builds, append run number as build portion (e.g. 2.0.0.142)
val ciBuildNumber = System.getenv("CI_BUILD_NUMBER")?.takeIf { it.isNotEmpty() }
version = if (ciBuildNumber != null) "$baseVersion.$ciBuildNumber" else baseVersion

// For git tag validation — always the clean semver, never with build suffix
tasks.register("printTagVersion") {
    doLast { println(baseVersion) }
}

// Generate build-info resource so the app can read its version at runtime
val generateBuildInfo =
    tasks.register("generateBuildInfo") {
        val outputDir = layout.buildDirectory.dir("generated/resources/build-info")
        val versionValue = version.toString()
        inputs.property("version", versionValue)
        outputs.dir(outputDir)
        doLast {
            val dir = outputDir.get().asFile.resolve("build-info")
            dir.mkdirs()
            dir.resolve("version.properties").writeText("version=$versionValue\n")
        }
    }
sourceSets.main { resources.srcDir(generateBuildInfo) }

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
    // Ktor HTTP client — used by JwksKeySetProvider for JWKS URI and OIDC discovery fetching
    implementation(libs.ktor.client.cio)

    // JWT / JWKS — used by JwksActorVerifier for JWT validation against JWKS key sets
    implementation(libs.nimbus.jose.jwt)
    // Google Tink — required at runtime by nimbus Ed25519Verifier for EdDSA/Ed25519 JWT verification
    implementation(libs.google.tink)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mcp.sdk.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)

    // Coroutines test support
    testImplementation(libs.kotlinx.coroutines.test)

    // H2 in-memory database for testing
    testImplementation("com.h2database:h2:2.2.224")

    // Bouncy Castle — provides OctetKeyPair raw key generation in tests
    testImplementation(libs.bouncycastle.provider)
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
        configurations.runtimeClasspath
            .get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    archiveBaseName.set("mcp-task-orchestrator-current")
}
