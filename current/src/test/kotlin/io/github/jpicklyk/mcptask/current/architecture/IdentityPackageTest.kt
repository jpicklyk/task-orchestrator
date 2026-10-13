package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent guard (test-author seat, item 0ce7924d / plan 4.1 R1): the identity code lives under
 * `infrastructure/identity/{jwt,did,bearer}`, the trust policy is extracted from `ActorAware` into
 * `application/service/ActorTrustPolicy.kt`, and `CurrentMcpServer` no longer defaults `appConfig`.
 *
 * Oracles: O-PLAN = plan v4-phase3-core.md 4.1 R1; O-SCOPE = the item's task-scope sections 1-3; O-AR58 = AR-58 edge 1
 * (no `application/service` file imports `application.api`); O-AR95 = AR-95 items 1-3; O-CONV = [LayeringTest] convention
 * "an import and a FQN reference on a code line count; KDoc and comment lines do not".
 *
 * Every "none found" assertion is paired with an in-test positive control that runs the SAME matcher on a synthetic source
 * which must hit, and with a count floor on the real scope, so the guard cannot pass vacuously. Static source scan: the
 * Gradle working directory is `current/`.
 */
class IdentityPackageTest {
    // region shared matchers

    private val identityPrefix = "infrastructure/identity/"

    private fun isUnderIdentity(path: String): Boolean = GuardSupport.normalizedPath(path).startsWith(identityPrefix)

    private val templateBody = Regex("""\$\{([^}]*)\}""")

    /** Code of [rawLine] with string contents blanked and a trailing line comment cut; null for a comment/blank line. */
    private fun codeOf(rawLine: String): String? =
        if (GuardSupport.isCodeLine(rawLine)) GuardSupport.stripStrings(rawLine).substringBefore("//") else null

    /** True when a code line (string contents ignored, `${...}` template bodies kept) matches [pattern]. */
    private fun codeMatches(
        rawLine: String,
        pattern: Regex
    ): Boolean {
        val code = codeOf(rawLine) ?: return false
        if (pattern.containsMatchIn(code)) return true
        val templates = templateBody.findAll(rawLine).joinToString(" ") { it.groupValues[1] }
        return pattern.containsMatchIn(templates)
    }

    private fun GuardSupport.Source.hasCodeMatch(pattern: Regex): Boolean = text.lines().any { codeMatches(it, pattern) }

    private val nimbusds = Regex("""(?<![A-Za-z0-9_])com\.nimbusds\b""")

    /** Paths of sources outside `infrastructure/identity/` whose code lines import or FQN-reference `com.nimbusds`. */
    private fun nimbusdsOutsideIdentity(sources: List<GuardSupport.Source>): List<String> =
        sources.filter { !isUnderIdentity(it.path) && it.hasCodeMatch(nimbusds) }.map { it.path }

    private fun synthetic(
        path: String,
        vararg lines: String
    ) = GuardSupport.Source(path, lines.joinToString("\n"))

    private fun packageOf(source: GuardSupport.Source): String? =
        source.text.lines().firstNotNullOfOrNull { Regex("""^\s*package\s+([A-Za-z0-9_.]+)""").find(it)?.groupValues?.get(1) }

    // endregion

    // region S1-S3 nimbusds confinement

    @Test
    fun `S1 com nimbusds is imported only from infrastructure identity and is used there`() {
        val sources = GuardSupport.productionSources()

        val outside = nimbusdsOutsideIdentity(sources)
        assertTrue(
            outside.isEmpty(),
            "com.nimbusds referenced outside infrastructure/identity/:\n" + outside.joinToString("\n") { "  $it" }
        )

        val users = sources.filter { it.hasCodeMatch(nimbusds) }.map { it.path }
        assertTrue(users.isNotEmpty(), "vacuity: at least one production file must use com.nimbusds (the JWKS verifiers do)")
        assertTrue(users.all { isUnderIdentity(it) }, "nimbusds users must all be under $identityPrefix: $users")
    }

    @Test
    fun `S2 the nimbusds matcher flags import, wildcard, aliased import and FQN code use outside identity`() {
        val spellings =
            listOf(
                "import com.nimbusds.jose.JWSObject",
                "import com.nimbusds.jwt.*",
                "import com.nimbusds.jwt.SignedJWT as J",
                "val j = com.nimbusds.jwt.SignedJWT.parse(s)",
                """val s = "x ${'$'}{com.nimbusds.jwt.SignedJWT.parse(t)}""""
            )
        for (line in spellings) {
            val hits = nimbusdsOutsideIdentity(listOf(synthetic("infrastructure/config/X.kt", "package p", line)))
            assertEquals(listOf("infrastructure/config/X.kt"), hits, "must be flagged: $line")
        }
    }

    @Test
    fun `S2 the identity path predicate rejects prefix traps and accepts normalized Windows paths`() {
        val line = "import com.nimbusds.jose.JWSObject"
        for (path in listOf("infrastructure/identityx/A.kt", "infrastructure/Identity/A.kt", "interfaces/identity/A.kt", "identity/A.kt")) {
            assertEquals(listOf(path), nimbusdsOutsideIdentity(listOf(synthetic(path, line))), "not identity, must be flagged: $path")
        }
        for (path in listOf("infrastructure/identity/A.kt", "infrastructure/identity/jwt/A.kt")) {
            assertEquals(emptyList(), nimbusdsOutsideIdentity(listOf(synthetic(path, line))), "identity, must be allowed: $path")
        }
        val windows = "C:\\w\\current\\src\\main\\kotlin\\io\\github\\jpicklyk\\mcptask\\current\\infrastructure\\identity\\jwt\\A.kt"
        assertTrue(isUnderIdentity(windows), "backslash path must normalize to infrastructure/identity/: $windows")
    }

    @Test
    fun `S3 comment, KDoc and string look-alikes of com nimbusds are not flagged`() {
        val lookAlikes =
            listOf(
                "// com.nimbusds.jose",
                "    // import com.nimbusds.jwt.SignedJWT",
                " * [com.nimbusds.jwt.SignedJWT]",
                "/** Uses com.nimbusds.jose. */",
                "/* com.nimbusds.jose */",
                """val s = "com.nimbusds.jose"""",
                "val x = 1 // com.nimbusds.jose trailing"
            )
        for (line in lookAlikes) {
            val hits = nimbusdsOutsideIdentity(listOf(synthetic("infrastructure/config/X.kt", line)))
            assertEquals(emptyList(), hits, "must NOT be flagged: $line")
        }
    }

    // endregion

    // region S4 layering baseline

    @Test
    fun `S4 the layering baseline parses to an empty set`() {
        val baseline = LayeringRules.parseBaseline(File("src/test/resources/architecture/layering-baseline.txt").readLines())
        assertEquals(emptySet(), baseline, "layering-baseline.txt must hold no entry lines (header comments only)")
    }

    @Test
    fun `S4 the baseline parser distinguishes a header-only file from one with an entry`() {
        val header = listOf("# Layering baseline", "#", "", "   ", "# infrastructure -> interfaces (R3)")
        assertEquals(emptySet(), LayeringRules.parseBaseline(header))
        val withEntry = header + "infrastructure/x/A.kt -> io.github.jpicklyk.mcptask.current.interfaces.B"
        assertEquals(1, LayeringRules.parseBaseline(withEntry).size, "control: a real entry line must survive parsing")
    }

    // endregion

    // region S5 file inventory

    private val base = "io.github.jpicklyk.mcptask.current.infrastructure.identity"

    /** Mapped path (relative to the production root) to the package the file must declare. */
    private val inventory: Map<String, String> =
        buildMap {
            for (n in listOf("ApiPrincipal", "KeySourceUrlPolicy", "ScopeClaimParser")) {
                put("infrastructure/identity/$n.kt", base)
            }
            for (n in listOf("ApiAuthConfig", "ApiAuthConfigLoader", "BearerTokenStore")) {
                put("infrastructure/identity/bearer/$n.kt", "$base.bearer")
            }
            val did =
                listOf(
                    "DidDocument",
                    "DidDocumentJwksExtractor",
                    "DidResolver",
                    "DidResolverRegistry",
                    "DidWebIdentifierValidation",
                    "DidWebResolver"
                )
            for (n in did) {
                put("infrastructure/identity/did/$n.kt", "$base.did")
            }
            for (n in listOf("JtiReplayCache", "JwksActorVerifier", "JwksApiVerifier", "JwksKeySetProvider", "TokenLifetimeCap")) {
                put("infrastructure/identity/jwt/$n.kt", "$base.jwt")
            }
        }

    private val formerDirs = listOf("infrastructure/config/", "interfaces/api/v1/auth/", "domain/model/")

    /** Former-location paths still occupied by a file bearing one of the moved file names. */
    private fun leftBehind(sources: List<GuardSupport.Source>): List<String> {
        val names = inventory.keys.map { it.substringAfterLast('/') }
        val present = sources.map { it.path }.toSet()
        return formerDirs.flatMap { dir -> names.map { "$dir$it" } }.filter { it in present }
    }

    @Test
    fun `S5 all 17 identity files exist at their mapped path with the matching package`() {
        assertEquals(17, inventory.size, "the inventory itself must list 17 files")
        val sources = GuardSupport.productionSources().associateBy { it.path }
        for ((path, expectedPackage) in inventory) {
            val source = assertNotNull(sources[path], "missing production file: $path")
            assertEquals(expectedPackage, packageOf(source), "package declaration of $path")
        }
    }

    @Test
    fun `S5 none of the moved file names remains in infrastructure config, interfaces auth or domain model`() {
        val remaining = leftBehind(GuardSupport.productionSources())
        assertTrue(remaining.isEmpty(), "moved files still at their former location:\n" + remaining.joinToString("\n") { "  $it" })
    }

    @Test
    fun `S5 the left-behind matcher detects a moved name at each former directory`() {
        val seeded =
            listOf(
                synthetic("infrastructure/config/DidWebResolver.kt", "package p"),
                synthetic("interfaces/api/v1/auth/ApiPrincipal.kt", "package p"),
                synthetic("domain/model/DidDocument.kt", "package p")
            )
        assertEquals(
            setOf("infrastructure/config/DidWebResolver.kt", "interfaces/api/v1/auth/ApiPrincipal.kt", "domain/model/DidDocument.kt"),
            leftBehind(seeded).toSet()
        )
        assertEquals(emptyList(), leftBehind(listOf(synthetic("infrastructure/config/AppConfig.kt", "package p"))))
    }

    // endregion

    // region S6 JwksKeyCache removal and security package independence

    private val jwksKeyCache = Regex("""\bJwksKeyCache\b""")
    private val securityForbiddenImport =
        Regex("""io\.github\.jpicklyk\.mcptask\.current\.infrastructure\.(?:config|identity)\b""")

    private fun securityOffenders(sources: List<GuardSupport.Source>): List<String> =
        sources
            .filter { GuardSupport.normalizedPath(it.path).startsWith("infrastructure/security/") }
            .filter { it.hasCodeMatch(securityForbiddenImport) }
            .map { it.path }

    @Test
    fun `S6 JwksKeyCache is gone and no production code line mentions it`() {
        val sources = GuardSupport.productionSources()
        assertFalse(sources.any { it.path == "infrastructure/security/JwksKeyCache.kt" }, "JwksKeyCache.kt must be deleted")
        val mentions = sources.filter { it.hasCodeMatch(jwksKeyCache) }.map { it.path }
        assertTrue(mentions.isEmpty(), "code lines still mention JwksKeyCache: $mentions")
        val replacement = Regex("""\bDefaultJwksKeySetProvider\b""")
        assertTrue(
            sources.any { it.path == "infrastructure/identity/jwt/JwksKeySetProvider.kt" && it.hasCodeMatch(replacement) },
            "control: its replacement DefaultJwksKeySetProvider must be declared in JwksKeySetProvider.kt"
        )
    }

    @Test
    fun `S6 the JwksKeyCache matcher flags a code mention and ignores comments and the replacement`() {
        assertTrue(synthetic("a/A.kt", "val c = JwksKeyCache(x)").hasCodeMatch(jwksKeyCache))
        assertTrue(synthetic("a/A.kt", "import io.x.security.JwksKeyCache").hasCodeMatch(jwksKeyCache))
        assertFalse(synthetic("a/A.kt", "// JwksKeyCache was removed").hasCodeMatch(jwksKeyCache))
        assertFalse(synthetic("a/A.kt", " * see [JwksKeyCache]").hasCodeMatch(jwksKeyCache))
        assertFalse(synthetic("a/A.kt", "val c = DefaultJwksKeySetProvider(x)").hasCodeMatch(jwksKeyCache))
    }

    @Test
    fun `S6 infrastructure security files do not depend on infrastructure config or identity`() {
        val sources = GuardSupport.productionSources()
        val security = sources.filter { GuardSupport.normalizedPath(it.path).startsWith("infrastructure/security/") }
        assertTrue(security.isNotEmpty(), "vacuity: infrastructure/security/ must hold production files")
        val offenders = securityOffenders(sources)
        assertTrue(offenders.isEmpty(), "infrastructure/security must not reach config/identity: $offenders")

        val root = "io.github.jpicklyk.mcptask.current.infrastructure"
        val seeded =
            listOf(
                synthetic("infrastructure/security/X.kt", "import $root.config.AppConfig"),
                synthetic("infrastructure/security/Y.kt", "val i = $root.identity.jwt.Z()"),
                synthetic("infrastructure/security/Z.kt", "// import $root.config.AppConfig")
            )
        assertEquals(setOf("infrastructure/security/X.kt", "infrastructure/security/Y.kt"), securityOffenders(seeded).toSet())
    }

    // endregion

    // region S7 ActorTrustPolicy extraction

    private val currentRoot = "io\\.github\\.jpicklyk\\.mcptask\\.current"
    private val applicationApi = Regex("""(?<![A-Za-z0-9_])$currentRoot\.application\.api\b""")

    private fun serviceImportingApi(sources: List<GuardSupport.Source>): List<String> =
        sources
            .filter { GuardSupport.normalizedPath(it.path).startsWith("application/service/") }
            .filter { it.hasCodeMatch(applicationApi) }
            .map { it.path }

    @Test
    fun `S7 ActorTrustPolicy and PolicyResolution are declared in application service ActorTrustPolicy`() {
        val sources = GuardSupport.productionSources().associateBy { it.path }
        val file =
            assertNotNull(sources["application/service/ActorTrustPolicy.kt"], "ActorTrustPolicy.kt must exist under application/service/")
        assertTrue(file.hasCodeMatch(Regex("""\bobject\s+ActorTrustPolicy\b""")), "object ActorTrustPolicy must be declared")
        assertTrue(file.hasCodeMatch(Regex("""\bsealed\s+class\s+PolicyResolution\b""")), "sealed class PolicyResolution must be declared")
        assertTrue(file.hasCodeMatch(Regex("""\bfun\s+resolveTrustedActorId\s*\(""")), "resolveTrustedActorId must be declared")
    }

    @Test
    fun `S7 no application service file imports or references application api`() {
        val sources = GuardSupport.productionSources()
        val services = sources.filter { GuardSupport.normalizedPath(it.path).startsWith("application/service/") }
        assertTrue(services.isNotEmpty(), "vacuity: application/service/ must hold production files")
        val offenders = serviceImportingApi(sources)
        assertTrue(offenders.isEmpty(), "application/service files reaching application.api (AR-58 edge 1): $offenders")
    }

    @Test
    fun `S7 the application api matcher flags an import and a FQN use and ignores KDoc and prefix traps`() {
        val api = "io.github.jpicklyk.mcptask.current.application.api"
        val seeded =
            listOf(
                synthetic("application/service/A.kt", "import $api.ActorAware"),
                synthetic("application/service/B.kt", "import $api.*"),
                synthetic("application/service/C.kt", "val p = $api.ActorParseResult"),
                synthetic("application/service/D.kt", " * [$api.ActorAware]"),
                synthetic("application/service/E.kt", "import ${api}x.Other"),
                synthetic("application/other/F.kt", "import $api.ActorAware")
            )
        assertEquals(
            setOf("application/service/A.kt", "application/service/B.kt", "application/service/C.kt"),
            serviceImportingApi(seeded).toSet()
        )
    }

    @Test
    fun `S7 ActorAware and ActorParseResult live in application api ActorParsing with no companion and no service copy`() {
        val sources = GuardSupport.productionSources().associateBy { it.path }
        val moved = assertNotNull(sources["application/api/ActorParsing.kt"], "application/api/ActorParsing.kt must exist")
        assertEquals("io.github.jpicklyk.mcptask.current.application.api", packageOf(moved))
        assertTrue(moved.hasCodeMatch(Regex("""\bsealed\s+class\s+ActorParseResult\b""")), "ActorParseResult must be declared")
        assertTrue(moved.hasCodeMatch(Regex("""\binterface\s+ActorAware\b""")), "ActorAware must be declared")
        assertFalse(moved.hasCodeMatch(Regex("""\bcompanion\s+object\b""")), "ActorAware.Companion is deleted, no shim")
        assertFalse("application/service/ActorParsing.kt" in sources, "the old application/service/ActorParsing.kt must be gone")
    }

    @Test
    fun `S7 no production code line still calls the policy through ActorAware`() {
        val stale = Regex("""\bActorAware\s*\.\s*resolveTrustedActorId\b""")
        val offenders = GuardSupport.productionSources().filter { it.hasCodeMatch(stale) }.map { it.path }
        assertTrue(offenders.isEmpty(), "callers must use ActorTrustPolicy.resolveTrustedActorId: $offenders")
        assertTrue(
            synthetic("a/A.kt", "return ActorAware.resolveTrustedActorId(c, v, p)").hasCodeMatch(stale),
            "control: the matcher flags the old call"
        )
        assertFalse(synthetic("a/A.kt", "return ActorTrustPolicy.resolveTrustedActorId(c, v, p)").hasCodeMatch(stale))
    }

    // endregion

    // region S8 fromEnv confinement and the appConfig default

    private val fromEnvCall = Regex("""\bfromEnv\s*\(""")
    private val fromEnvAllowed = setOf("CurrentMain.kt", "infrastructure/config/AppConfig.kt")

    private fun fromEnvOutsideAllowed(sources: List<GuardSupport.Source>): List<String> =
        sources.filter { it.path !in fromEnvAllowed && it.hasCodeMatch(fromEnvCall) }.map { it.path }

    @Test
    fun `S8 fromEnv is called only from CurrentMain and defined only in AppConfig`() {
        val sources = GuardSupport.productionSources()
        val offenders = fromEnvOutsideAllowed(sources)
        assertTrue(offenders.isEmpty(), "fromEnv( on a code line outside CurrentMain.kt and AppConfig.kt: $offenders")

        val main = assertNotNull(sources.firstOrNull { it.path == "CurrentMain.kt" }, "CurrentMain.kt must be in the production scope")
        assertTrue(main.hasCodeMatch(Regex("""\bAppConfig\s*\.\s*fromEnv\s*\(""")), "CurrentMain must call AppConfig.fromEnv(...)")
        val appConfig = assertNotNull(sources.firstOrNull { it.path == "infrastructure/config/AppConfig.kt" }, "AppConfig.kt must exist")
        assertTrue(appConfig.hasCodeMatch(Regex("""\bfun\s+fromEnv\s*\(""")), "AppConfig must still define fromEnv")
    }

    @Test
    fun `S8 the fromEnv matcher flags a call, a spaced call and a default-argument call outside the allowed files`() {
        val seeded =
            listOf(
                synthetic("bootstrap/A.kt", "val c = AppConfig.fromEnv()"),
                synthetic("bootstrap/B.kt", "val c = AppConfig.fromEnv ()"),
                synthetic("bootstrap/C.kt", "private val appConfig: AppConfig = AppConfig.fromEnv(),"),
                synthetic("bootstrap/D.kt", "// AppConfig.fromEnv()"),
                synthetic("CurrentMain.kt", "val c = AppConfig.fromEnv()")
            )
        assertEquals(setOf("bootstrap/A.kt", "bootstrap/B.kt", "bootstrap/C.kt"), fromEnvOutsideAllowed(seeded).toSet())
    }

    @Test
    fun `S8 CurrentMcpServer declares appConfig without a default value`() {
        val servers = GuardSupport.productionSources().filter { it.path.endsWith("/CurrentMcpServer.kt") }
        assertEquals(1, servers.size, "exactly one CurrentMcpServer.kt expected: ${servers.map { it.path }}")
        val server = servers.single()
        val declaration = Regex("""\bprivate\s+val\s+appConfig\s*:\s*AppConfig\s*,\s*$""")
        val defaulted = Regex("""\bappConfig\s*:\s*AppConfig\s*=""")
        assertTrue(server.hasCodeMatch(declaration), "CurrentMcpServer must declare `private val appConfig: AppConfig,`")
        assertFalse(server.hasCodeMatch(defaulted), "appConfig must have no default value")
        val withDefault = synthetic("a/A.kt", "    private val appConfig: AppConfig = AppConfig.fromEnv(),")
        assertTrue(withDefault.hasCodeMatch(defaulted), "control: matcher flags a default")
        assertFalse(withDefault.hasCodeMatch(declaration), "control: a defaulted parameter is not the no-default declaration")
    }

    // endregion

    // region S9 identity does not depend on interfaces

    private val interfacesRef = Regex("""(?<![A-Za-z0-9_])$currentRoot\.interfaces\b""")

    private fun identityReachingInterfaces(sources: List<GuardSupport.Source>): List<String> =
        sources.filter { isUnderIdentity(it.path) && it.hasCodeMatch(interfacesRef) }.map { it.path }

    @Test
    fun `S9 no infrastructure identity file imports or references interfaces`() {
        val sources = GuardSupport.productionSources()
        val identity = sources.filter { isUnderIdentity(it.path) }
        assertTrue(identity.size >= 15, "vacuity: expected >= 15 identity files, found ${identity.size}")
        val offenders = identityReachingInterfaces(sources)
        assertTrue(offenders.isEmpty(), "identity files reaching interfaces.*: $offenders")
    }

    @Test
    fun `S9 the interfaces matcher flags import, wildcard and FQN use and ignores KDoc`() {
        val iface = "io.github.jpicklyk.mcptask.current.interfaces"
        val seeded =
            listOf(
                synthetic("infrastructure/identity/A.kt", "import $iface.api.v1.auth.ApiPrincipal"),
                synthetic("infrastructure/identity/jwt/B.kt", "import $iface.api.*"),
                synthetic("infrastructure/identity/did/C.kt", "val p = $iface.mcp.X()"),
                synthetic("infrastructure/identity/bearer/D.kt", " * [$iface.api.X]"),
                synthetic("infrastructure/config/E.kt", "import $iface.api.X")
            )
        assertEquals(
            setOf("infrastructure/identity/A.kt", "infrastructure/identity/jwt/B.kt", "infrastructure/identity/did/C.kt"),
            identityReachingInterfaces(seeded).toSet()
        )
    }

    // endregion
}
