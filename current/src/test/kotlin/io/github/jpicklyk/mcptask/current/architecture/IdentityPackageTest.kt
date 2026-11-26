package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent guard (test-author seat, item 0ce7924d / plan 4.1 R1): the identity code lives in the `identity` slice
 * (`identity/`, its `internal/` layers), the trust policy is extracted from `ActorAware` into
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

    private val identityPrefix = "identity/"

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

    /** Paths of sources outside `identity/` whose code lines import or FQN-reference `com.nimbusds`. */
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
    fun `S1 com nimbusds is imported only from the identity slice and is used there`() {
        val sources = GuardSupport.productionSources()

        val outside = nimbusdsOutsideIdentity(sources)
        assertTrue(
            outside.isEmpty(),
            "com.nimbusds referenced outside identity/:\n" + outside.joinToString("\n") { "  $it" }
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
        for (path in listOf("identityx/A.kt", "Identity/A.kt", "interfaces/identity/A.kt", "infrastructure/identity/A.kt")) {
            assertEquals(listOf(path), nimbusdsOutsideIdentity(listOf(synthetic(path, line))), "not identity, must be flagged: $path")
        }
        for (path in listOf("identity/A.kt", "identity/internal/wire/jwt/A.kt")) {
            assertEquals(emptyList(), nimbusdsOutsideIdentity(listOf(synthetic(path, line))), "identity, must be allowed: $path")
        }
        val windows = "C:\\w\\current\\src\\main\\kotlin\\io\\github\\jpicklyk\\mcptask\\current\\identity\\internal\\wire\\jwt\\A.kt"
        assertTrue(isUnderIdentity(windows), "backslash path must normalize to identity/: $windows")
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

    private val base = "io.github.jpicklyk.mcptask.current.identity"

    /** Mapped path (relative to the production root) to the package the file must declare. */
    private val inventory: Map<String, String> =
        buildMap {
            for (n in listOf("CredentialResolver", "ApiAuthConfig", "BearerTokenStore", "JwksApiVerifier")) {
                put("identity/$n.kt", base)
            }
            put("identity/internal/domain/KeySourceUrlPolicy.kt", "$base.internal.domain")
            put("identity/internal/app/ScopeClaimParser.kt", "$base.internal.app")
            put("identity/internal/wire/ApiAuthConfigLoader.kt", "$base.internal.wire")
            for (n in listOf("JwksKeySetProvider", "TokenLifetimeCap")) {
                put("identity/internal/wire/jwt/$n.kt", "$base.internal.wire.jwt")
            }
        }

    private val formerDirs =
        listOf(
            "infrastructure/config/",
            "interfaces/api/v1/auth/",
            "domain/model/",
            "infrastructure/identity/",
            "infrastructure/identity/bearer/",
            "infrastructure/identity/jwt/"
        )

    /** Former-location paths still occupied by a file bearing one of the moved file names. */
    private fun leftBehind(sources: List<GuardSupport.Source>): List<String> {
        val names = inventory.keys.map { it.substringAfterLast('/') }
        val present = sources.map { it.path }.toSet()
        return formerDirs.flatMap { dir -> names.map { "$dir$it" } }.filter { it in present }
    }

    @Test
    fun `S5 all 9 identity files exist at their mapped path with the matching package`() {
        assertEquals(9, inventory.size, "the inventory itself must list 9 files")
        val sources = GuardSupport.productionSources().associateBy { it.path }
        for ((path, expectedPackage) in inventory) {
            val source = assertNotNull(sources[path], "missing production file: $path")
            assertEquals(expectedPackage, packageOf(source), "package declaration of $path")
        }
    }

    @Test
    fun `S5 none of the moved file names remains in a former identity location`() {
        val remaining = leftBehind(GuardSupport.productionSources())
        assertTrue(remaining.isEmpty(), "moved files still at their former location:\n" + remaining.joinToString("\n") { "  $it" })
    }

    @Test
    fun `S5 the left-behind matcher detects a moved name at each former directory`() {
        val seeded =
            listOf(
                synthetic("infrastructure/config/JwksApiVerifier.kt", "package p"),
                synthetic("interfaces/api/v1/auth/CredentialResolver.kt", "package p"),
                synthetic("domain/model/BearerTokenStore.kt", "package p")
            )
        assertEquals(
            setOf(
                "infrastructure/config/JwksApiVerifier.kt",
                "interfaces/api/v1/auth/CredentialResolver.kt",
                "domain/model/BearerTokenStore.kt"
            ),
            leftBehind(seeded).toSet()
        )
        assertEquals(emptyList(), leftBehind(listOf(synthetic("infrastructure/config/AppConfig.kt", "package p"))))
    }

    // endregion

    // region S6 JwksKeyCache removal and security package independence

    private val jwksKeyCache = Regex("""\bJwksKeyCache\b""")
    private val securityForbiddenImport =
        Regex("""io\.github\.jpicklyk\.mcptask\.current\.(?:infrastructure\.(?:config|identity)|identity|platform\.(?:settings|yaml))\b""")

    private fun securityOffenders(sources: List<GuardSupport.Source>): List<String> =
        sources
            .filter { GuardSupport.normalizedPath(it.path).startsWith("platform/security/") }
            .filter { it.hasCodeMatch(securityForbiddenImport) }
            .map { it.path }

    @Test
    fun `S6 JwksKeyCache is gone and no production code line mentions it`() {
        val sources = GuardSupport.productionSources()
        assertFalse(
            sources.any { it.path == "infrastructure/security/JwksKeyCache.kt" || it.path == "platform/security/JwksKeyCache.kt" },
            "JwksKeyCache.kt must be deleted"
        )
        val mentions = sources.filter { it.hasCodeMatch(jwksKeyCache) }.map { it.path }
        assertTrue(mentions.isEmpty(), "code lines still mention JwksKeyCache: $mentions")
        val replacement = Regex("""\bDefaultJwksKeySetProvider\b""")
        assertTrue(
            sources.any { it.path == "identity/internal/wire/jwt/JwksKeySetProvider.kt" && it.hasCodeMatch(replacement) },
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
    fun `S6 platform security files do not depend on infrastructure config or identity or platform settings`() {
        val sources = GuardSupport.productionSources()
        val security = sources.filter { GuardSupport.normalizedPath(it.path).startsWith("platform/security/") }
        assertTrue(security.isNotEmpty(), "vacuity: platform/security/ must hold production files")
        val offenders = securityOffenders(sources)
        assertTrue(offenders.isEmpty(), "platform/security must not reach config/identity/settings: $offenders")

        val root = "io.github.jpicklyk.mcptask.current.infrastructure"
        val seeded =
            listOf(
                synthetic("platform/security/X.kt", "import $root.config.AppConfig"),
                synthetic("platform/security/Y.kt", "val i = $root.identity.jwt.Z()"),
                synthetic("platform/security/Z.kt", "// import $root.config.AppConfig"),
                synthetic("platform/security/W.kt", "import io.github.jpicklyk.mcptask.current.platform.settings.AppConfig"),
                synthetic("platform/security/V.kt", "import io.github.jpicklyk.mcptask.current.platform.yaml.YamlSupport"),
                synthetic("platform/security/U.kt", "import io.github.jpicklyk.mcptask.current.identity.CredentialResolver")
            )
        assertEquals(
            setOf(
                "platform/security/X.kt",
                "platform/security/Y.kt",
                "platform/security/W.kt",
                "platform/security/V.kt",
                "platform/security/U.kt"
            ),
            securityOffenders(seeded).toSet()
        )
    }

    // endregion

    // region S7 application service stays below application api

    private val currentRoot = "io\\.github\\.jpicklyk\\.mcptask\\.current"
    private val applicationApi = Regex("""(?<![A-Za-z0-9_])$currentRoot\.(?:application\.api|platform\.call|platform\.request)\b""")

    /**
     * The legacy `application/service` node: its own folder, the `platform/` folders that took its files, and the services
     * that left it with their slice (a file path: the guard follows each service to its slice path, it never shrinks).
     */
    private val serviceNodePaths =
        listOf(
            "application/service/",
            "platform/eventrecording/",
            "platform/idempotency/",
            "claim/ClaimService.kt",
            "claim/FenceGuard.kt",
            "dependency/DependencyCommandService.kt",
            "lifecycle/AdvanceService.kt",
            "lifecycle/AdvanceServiceFactory.kt",
            "lifecycle/GateStatusQuery.kt",
            "lifecycle/LifecycleClass.kt",
            "lifecycle/TreeCompletionService.kt",
            "lifecycle/NextItemRecommender.kt",
            "lifecycle/internal/domain/GatePredicate.kt",
            "lifecycle/internal/domain/PhaseNoteContext.kt",
            "lifecycle/internal/domain/SeatOwnership.kt",
            "lifecycle/internal/domain/CredentialRefValidation.kt",
            "lifecycle/internal/app/TransitionSnapshotLoader.kt",
            "lifecycle/internal/app/ItemSchemaView.kt",
            "lifecycle/internal/app/SchemaEntryJsonBuilder.kt"
        )

    private fun isServiceNode(path: String): Boolean = serviceNodePaths.any { GuardSupport.normalizedPath(path).startsWith(it) }

    private fun serviceImportingApi(sources: List<GuardSupport.Source>): List<String> =
        sources
            .filter { isServiceNode(it.path) }
            .filter { it.hasCodeMatch(applicationApi) }
            .map { it.path }

    @Test
    fun `S7 no application service file imports or references application api`() {
        val sources = GuardSupport.productionSources()
        val services = sources.filter { isServiceNode(it.path) }
        serviceNodePaths.forEach { dir ->
            assertTrue(services.any { GuardSupport.normalizedPath(it.path).startsWith(dir) }, "vacuity: $dir must hold production files")
        }
        val offenders = serviceImportingApi(sources)
        assertTrue(
            offenders.isEmpty(),
            "application/service files, platform/eventrecording, platform/idempotency, platform.call and platform.request reaching application.api (AR-58 edge 1): $offenders"
        )
    }

    @Test
    fun `S7 the application api matcher flags an import and a FQN use and ignores KDoc and prefix traps`() {
        val api = "io.github.jpicklyk.mcptask.current.application.api"
        val call = "io.github.jpicklyk.mcptask.current.platform.call"
        val request = "io.github.jpicklyk.mcptask.current.platform.request"
        val seeded =
            listOf(
                synthetic("application/service/A.kt", "import $api.ActorAware"),
                synthetic("application/service/B.kt", "import $api.*"),
                synthetic("application/service/C.kt", "val p = $api.ActorParseResult"),
                synthetic("application/service/D.kt", " * [$api.ActorAware]"),
                synthetic("application/service/E.kt", "import ${api}x.Other"),
                synthetic("application/other/F.kt", "import $api.ActorAware"),
                synthetic("platform/eventrecording/G.kt", "import $call.CallContext"),
                synthetic("platform/idempotency/H.kt", "val p = $call.Principal"),
                synthetic("platform/port/I.kt", "import $call.Principal"),
                synthetic("application/service/J.kt", "import $request.Doc"),
                synthetic("claim/ClaimService.kt", "import $api.ActorAware"),
                synthetic("claim/FenceGuard.kt", "val p = $call.Principal"),
                synthetic("claim/ClaimStore.kt", "import $api.ActorAware"),
                synthetic("dependency/DependencyCommandService.kt", "import $api.ActorAware"),
                synthetic("dependency/DependencyStore.kt", "import $api.ActorAware")
            )
        assertEquals(
            setOf(
                "application/service/A.kt",
                "application/service/B.kt",
                "application/service/C.kt",
                "platform/eventrecording/G.kt",
                "platform/idempotency/H.kt",
                "application/service/J.kt",
                "claim/ClaimService.kt",
                "claim/FenceGuard.kt",
                "dependency/DependencyCommandService.kt"
            ),
            serviceImportingApi(seeded).toSet()
        )
    }

    @Test
    fun `S7 ActorAware and ActorParseResult are deleted with the body actor (R14)`() {
        val sources = GuardSupport.productionSources()
        assertTrue(sources.isNotEmpty(), "vacuity: production sources are in scope")
        assertTrue(sources.none { it.path.endsWith("/ActorParsing.kt") }, "no ActorParsing.kt remains")
        val declared = Regex("""\b(sealed\s+class\s+ActorParseResult|interface\s+ActorAware)\b""")
        assertTrue(sources.none { it.hasCodeMatch(declared) }, "ActorParseResult and ActorAware are not declared anywhere")
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
    private val fromEnvAllowed = setOf("CurrentMain.kt", "platform/settings/AppConfig.kt")

    private fun fromEnvOutsideAllowed(sources: List<GuardSupport.Source>): List<String> =
        sources.filter { it.path !in fromEnvAllowed && it.hasCodeMatch(fromEnvCall) }.map { it.path }

    @Test
    fun `S8 fromEnv is called only from CurrentMain and defined only in AppConfig`() {
        val sources = GuardSupport.productionSources()
        val offenders = fromEnvOutsideAllowed(sources)
        assertTrue(offenders.isEmpty(), "fromEnv( on a code line outside CurrentMain.kt and AppConfig.kt: $offenders")

        val main = assertNotNull(sources.firstOrNull { it.path == "CurrentMain.kt" }, "CurrentMain.kt must be in the production scope")
        assertTrue(main.hasCodeMatch(Regex("""\bAppConfig\s*\.\s*fromEnv\s*\(""")), "CurrentMain must call AppConfig.fromEnv(...)")
        val appConfig = assertNotNull(sources.firstOrNull { it.path == "platform/settings/AppConfig.kt" }, "AppConfig.kt must exist")
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
    fun `S9 no identity slice file imports or references interfaces`() {
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
                synthetic("identity/A.kt", "import $iface.api.v1.auth.Principal"),
                synthetic("identity/internal/wire/jwt/B.kt", "import $iface.api.*"),
                synthetic("identity/internal/wire/C.kt", "val p = $iface.mcp.X()"),
                synthetic("identity/internal/app/D.kt", " * [$iface.api.X]"),
                synthetic("infrastructure/config/E.kt", "import $iface.api.X")
            )
        assertEquals(
            setOf("identity/A.kt", "identity/internal/wire/jwt/B.kt", "identity/internal/wire/C.kt"),
            identityReachingInterfaces(seeded).toSet()
        )
    }

    // endregion
}
