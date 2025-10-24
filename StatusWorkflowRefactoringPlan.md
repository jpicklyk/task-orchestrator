# Status Workflow Refactoring Plan

## Overview
Create a **hybrid MCP tool + simplified Skill** following the RecommendAgent pattern to intelligently determine next status based on config-driven workflows with tag-to-flow mapping.

## Key Decisions
- ✅ **Read-only MCP tool** (`get_next_status`) - suggests next, ManageContainer applies
- ✅ **Hybrid config** - config.yaml mappings + code fallback
- ✅ **Hybrid pattern** - MCP tool (universal) + Skill (Claude Code workflows)
- ✅ **Overhaul Skill** - Simplify from 1048 lines to ~250 lines (76% reduction)
- ✅ **Remove `allowed_statuses`** - Redundant with flows, derive from flows + emergency + terminal
- ✅ **Remove unused flows** - Delete `with_review`, `with_review_iterations`, etc. (dead code)

---

## Phase 1: Config Schema Enhancement

**Update `default-config.yaml`:**

### Tasks Section:
```yaml
status_progression:
  tasks:
    # Valid task statuses (defined by TaskStatus enum):
    # backlog, pending, in-progress, in-review, changes-requested, testing,
    # blocked, on-hold, completed, cancelled, deferred, deployed, ready-for-qa, investigating

    # Default flow for standard software development
    default_flow: [backlog, pending, in-progress, testing, completed]

    # Alternative flows for different task types
    bug_fix_flow: [pending, in-progress, testing, completed]  # Skip backlog
    documentation_flow: [pending, in-progress, in-review, completed]  # No testing
    hotfix_flow: [in-progress, testing, completed]  # Emergency, skip backlog+pending

    # Tag-to-flow mappings (priority order matters - first match wins)
    flow_mappings:
      - tags: [bug, bugfix, fix]
        flow: bug_fix_flow
      - tags: [documentation, docs]
        flow: documentation_flow
      - tags: [hotfix, emergency, urgent]
        flow: hotfix_flow
    # If no tags match, default_flow is used

    # Statuses that can be entered from any state
    emergency_transitions: [blocked, on-hold, cancelled, deferred]

    # Terminal statuses (no progression from these)
    terminal_statuses: [completed, cancelled, deferred]
```

### Features Section:
```yaml
  features:
    # Valid feature statuses (defined by FeatureStatus enum):
    # draft, planning, in-development, testing, validating, pending-review,
    # blocked, on-hold, completed, archived, deployed

    # Default flow for software development
    default_flow: [draft, planning, in-development, testing, validating, completed]

    # Alternative flows for different feature types
    rapid_prototype_flow: [draft, in-development, completed]  # Skip planning/testing
    experimental_flow: [draft, in-development, archived]  # Experiments can skip completion

    # Tag-to-flow mappings
    flow_mappings:
      - tags: [prototype, poc, spike]
        flow: rapid_prototype_flow
      - tags: [experiment, research]
        flow: experimental_flow

    # Emergency transitions
    emergency_transitions: [blocked, on-hold, archived]

    # Terminal statuses
    terminal_statuses: [completed, archived]
```

### Projects Section:
```yaml
  projects:
    # Valid project statuses (defined by ProjectStatus enum):
    # planning, in-development, on-hold, cancelled, completed, archived

    # Default project flow
    default_flow: [planning, in-development, completed, archived]

    # No alternative flows needed (projects are high-level)

    # Emergency transitions
    emergency_transitions: [on-hold, cancelled]

    # Terminal statuses
    terminal_statuses: [completed, archived, cancelled]
```

---

## Phase 2: StatusValidator Refactoring

**Update `StatusValidator.kt`:**

### 2.1: Remove `allowed_statuses` validation logic

**Current (lines 492-496):**
```kotlin
private fun getAllowedStatusesV2(containerType: String, config: Map<String, Any?>): List<String> {
    val statusProgression = getStatusProgressionConfig(containerType, config)
    @Suppress("UNCHECKED_CAST")
    return statusProgression["allowed_statuses"] as? List<String> ?: emptyList()
}
```

**New:**
```kotlin
/**
 * Derives allowed statuses from configured flows, emergency transitions, and terminal statuses.
 * This eliminates redundancy - flows naturally define reachable statuses.
 */
private fun getAllowedStatusesV2(containerType: String, config: Map<String, Any?>): List<String> {
    val statusProgression = getStatusProgressionConfig(containerType, config)
    val allowedStatuses = mutableSetOf<String>()

    // Add all statuses from all defined flows
    @Suppress("UNCHECKED_CAST")
    val allFlows = statusProgression.filterKeys { it.endsWith("_flow") }
    allFlows.values.forEach { flowValue ->
        if (flowValue is List<*>) {
            flowValue.filterIsInstance<String>().forEach { allowedStatuses.add(it) }
        }
    }

    // Add emergency transitions
    val emergencyStatuses = getEmergencyStatuses(containerType, statusProgression)
    allowedStatuses.addAll(emergencyStatuses)

    // Add terminal statuses
    val terminalStatuses = getTerminalStatuses(containerType, statusProgression)
    allowedStatuses.addAll(terminalStatuses)

    return allowedStatuses.toList()
}
```

### 2.2: Update `validateTransitionV2` to support dynamic flow selection

**Add method to determine active flow based on tags:**
```kotlin
/**
 * Determines which flow to use based on entity tags.
 * Matches tags against flow_mappings, falls back to default_flow.
 */
private fun getActiveFlow(containerType: String, statusProgression: Map<String, Any?>, tags: List<String>): List<String> {
    @Suppress("UNCHECKED_CAST")
    val flowMappings = statusProgression["flow_mappings"] as? List<Map<String, Any>>

    if (flowMappings != null && tags.isNotEmpty()) {
        // Normalize tags for matching
        val normalizedTags = tags.map { it.lowercase().trim() }

        // Try each mapping in order (priority order)
        for (mapping in flowMappings) {
            val mappingTags = (mapping["tags"] as? List<String>)?.map { it.lowercase().trim() } ?: emptyList()

            // Check if any entity tag matches any mapping tag
            if (normalizedTags.any { tag -> mappingTags.contains(tag) }) {
                val flowName = mapping["flow"] as? String
                if (flowName != null) {
                    val flow = statusProgression[flowName] as? List<String>
                    if (flow != null) {
                        return flow
                    }
                }
            }
        }
    }

    // Fallback to default_flow
    return getDefaultFlow(containerType, statusProgression)
}
```

**Update `validateTransitionV2` to use `getActiveFlow`:**
```kotlin
private fun validateTransitionV2(
    currentStatus: String,
    newStatus: String,
    containerType: String,
    config: Map<String, Any?>,
    tags: List<String> = emptyList()  // NEW parameter
): ValidationResult {
    val normalizedCurrent = normalizeStatus(currentStatus)
    val normalizedNew = normalizeStatus(newStatus)

    val validationConfig = getValidationConfig(config)
    val statusProgression = getStatusProgressionConfig(containerType, config)

    // Check terminal statuses
    val terminalStatuses = getTerminalStatuses(containerType, statusProgression)
    if (terminalStatuses.contains(normalizedCurrent)) {
        return ValidationResult.Invalid(
            "Cannot transition from terminal status '$currentStatus'",
            emptyList()
        )
    }

    // Check emergency transitions
    val emergencyStatuses = getEmergencyStatuses(containerType, statusProgression)
    if (emergencyStatuses.contains(normalizedNew)) {
        val allowEmergency = validationConfig["allow_emergency"] as? Boolean ?: true
        if (allowEmergency) {
            return ValidationResult.Valid
        }
    }

    // Get active flow based on tags (NEW)
    val activeFlow = getActiveFlow(containerType, statusProgression, tags)

    val allowBackward = validationConfig["allow_backward"] as? Boolean ?: true
    val enforceSequential = validationConfig["enforce_sequential"] as? Boolean ?: true

    val currentIndex = activeFlow.indexOf(normalizedCurrent)
    val newIndex = activeFlow.indexOf(normalizedNew)

    // If either status not in active flow, allow transition
    if (currentIndex == -1 || newIndex == -1) {
        return ValidationResult.Valid
    }

    // Check backward transitions
    if (newIndex < currentIndex) {
        return if (allowBackward) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                "Backward transition from '$currentStatus' to '$newStatus' not allowed",
                emptyList()
            )
        }
    }

    // Check sequential enforcement
    if (enforceSequential && newIndex > currentIndex + 1) {
        val skippedStatuses = activeFlow.subList(currentIndex + 1, newIndex)
        return ValidationResult.Invalid(
            "Cannot skip statuses. Must transition through: ${skippedStatuses.joinToString(" → ")}",
            listOf(activeFlow[currentIndex + 1])
        )
    }

    return ValidationResult.Valid
}
```

### 2.3: Update public API to accept tags

**Update `validateTransition` method signature:**
```kotlin
suspend fun validateTransition(
    currentStatus: String,
    newStatus: String,
    containerType: String,
    containerId: java.util.UUID? = null,
    context: PrerequisiteContext? = null,
    tags: List<String> = emptyList()  // Already exists - use it!
): ValidationResult {
    // ... existing code ...

    val transitionResult = if (config != null) {
        validateTransitionV2(currentStatus, newStatus, containerType, config, tags)  // Pass tags
    } else {
        ValidationResult.Valid
    }

    // ... rest of method ...
}
```

---

## Phase 3: Service Layer

**Create new package:** `io.github.jpicklyk.mcptask.application.service.progression`

### 3.1: Create `StatusProgressionService.kt` (Interface)

```kotlin
package io.github.jpicklyk.mcptask.application.service.progression

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import java.util.UUID

/**
 * Service for determining next status in workflows based on config-driven flows and tags.
 */
interface StatusProgressionService {
    /**
     * Result of status progression recommendation.
     */
    data class NextStatusRecommendation(
        val currentStatus: String,
        val nextStatus: String?,
        val flow: String,  // e.g., "default_flow", "bug_fix_flow"
        val flowPath: List<String>,
        val currentPosition: Int,  // Index in flowPath
        val matchedTags: List<String>,
        val reason: String
    )

    /**
     * Result of readiness check for status progression.
     */
    data class ReadinessResult(
        val ready: Boolean,
        val blockers: List<String> = emptyList(),
        val suggestions: List<String> = emptyList()
    )

    /**
     * Determine next status based on current state and tags.
     * Returns null if already at terminal status or flow not found.
     */
    fun getNextStatus(
        currentStatus: String,
        containerType: String,
        tags: List<String> = emptyList()
    ): NextStatusRecommendation?

    /**
     * Get complete flow path based on tags.
     * Used for visualization and navigation.
     */
    fun getFlowPath(
        containerType: String,
        tags: List<String> = emptyList()
    ): List<String>

    /**
     * Check if entity is ready to progress to next status.
     * Integrates with StatusValidator for prerequisite checking.
     */
    suspend fun checkReadiness(
        containerId: UUID,
        targetStatus: String,
        containerType: String,
        context: StatusValidator.PrerequisiteContext
    ): ReadinessResult
}
```

### 3.2: Create `StatusProgressionServiceImpl.kt`

**Pattern:** Follow `AgentRecommendationServiceImpl.kt` exactly

```kotlin
package io.github.jpicklyk.mcptask.application.service.progression

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

class StatusProgressionServiceImpl(
    private val statusValidator: StatusValidator
) : StatusProgressionService {

    private val logger = LoggerFactory.getLogger(StatusProgressionServiceImpl::class.java)

    // Cached config (same pattern as StatusValidator)
    @Volatile
    private var cachedConfig: Map<String, Any?>? = null
    @Volatile
    private var lastConfigCheck: Long = 0L
    @Volatile
    private var cachedUserDir: String? = null
    private val configCacheTimeout = 60_000L

    private data class FlowMapping(
        val tags: List<String>,
        val flow: String
    )

    override fun getNextStatus(
        currentStatus: String,
        containerType: String,
        tags: List<String>
    ): StatusProgressionService.NextStatusRecommendation? {
        val config = loadConfig() ?: return null

        val statusProgression = getStatusProgressionConfig(containerType, config)
        val normalizedCurrent = normalizeStatus(currentStatus)

        // Determine active flow based on tags
        val (flowName, flowPath) = determineActiveFlow(statusProgression, tags)

        // Find current position in flow
        val currentIndex = flowPath.indexOf(normalizedCurrent)

        if (currentIndex == -1) {
            // Current status not in active flow - might be emergency status
            return null
        }

        // Check if at terminal status
        val terminalStatuses = getTerminalStatuses(statusProgression)
        if (terminalStatuses.contains(normalizedCurrent)) {
            return StatusProgressionService.NextStatusRecommendation(
                currentStatus = currentStatus,
                nextStatus = null,
                flow = flowName,
                flowPath = flowPath,
                currentPosition = currentIndex,
                matchedTags = emptyList(),
                reason = "Already at terminal status"
            )
        }

        // Get next status
        val nextStatus = if (currentIndex < flowPath.size - 1) {
            flowPath[currentIndex + 1]
        } else {
            null
        }

        val matchedTags = findMatchedTags(statusProgression, tags, flowName)

        return StatusProgressionService.NextStatusRecommendation(
            currentStatus = currentStatus,
            nextStatus = nextStatus,
            flow = flowName,
            flowPath = flowPath,
            currentPosition = currentIndex,
            matchedTags = matchedTags,
            reason = if (matchedTags.isEmpty()) "Using default workflow" else "Using $flowName based on tags: ${matchedTags.joinToString(", ")}"
        )
    }

    override fun getFlowPath(
        containerType: String,
        tags: List<String>
    ): List<String> {
        val config = loadConfig() ?: return emptyList()
        val statusProgression = getStatusProgressionConfig(containerType, config)
        val (_, flowPath) = determineActiveFlow(statusProgression, tags)
        return flowPath
    }

    override suspend fun checkReadiness(
        containerId: UUID,
        targetStatus: String,
        containerType: String,
        context: StatusValidator.PrerequisiteContext
    ): StatusProgressionService.ReadinessResult {
        val validationResult = statusValidator.validatePrerequisites(
            containerId,
            targetStatus,
            containerType,
            context
        )

        return when (validationResult) {
            is StatusValidator.ValidationResult.Valid -> {
                StatusProgressionService.ReadinessResult(ready = true)
            }
            is StatusValidator.ValidationResult.Invalid -> {
                StatusProgressionService.ReadinessResult(
                    ready = false,
                    blockers = listOf(validationResult.reason),
                    suggestions = validationResult.suggestions
                )
            }
            is StatusValidator.ValidationResult.ValidWithAdvisory -> {
                // Advisory doesn't block progression
                StatusProgressionService.ReadinessResult(
                    ready = true,
                    suggestions = listOf(validationResult.advisory)
                )
            }
        }
    }

    // ========== PRIVATE HELPERS ==========

    /**
     * Determines which flow to use based on tags.
     * Returns (flowName, flowPath).
     */
    private fun determineActiveFlow(
        statusProgression: Map<String, Any?>,
        tags: List<String>
    ): Pair<String, List<String>> {
        @Suppress("UNCHECKED_CAST")
        val flowMappings = statusProgression["flow_mappings"] as? List<Map<String, Any>>

        if (flowMappings != null && tags.isNotEmpty()) {
            val normalizedTags = tags.map { it.lowercase().trim() }

            // Try each mapping in order
            for (mapping in flowMappings) {
                val mappingTags = (mapping["tags"] as? List<String>)?.map { it.lowercase().trim() } ?: emptyList()

                if (normalizedTags.any { tag -> mappingTags.contains(tag) }) {
                    val flowName = mapping["flow"] as? String
                    if (flowName != null) {
                        val flow = statusProgression[flowName] as? List<String>
                        if (flow != null) {
                            return Pair(flowName, flow)
                        }
                    }
                }
            }
        }

        // Fallback to default_flow
        @Suppress("UNCHECKED_CAST")
        val defaultFlow = statusProgression["default_flow"] as? List<String> ?: emptyList()
        return Pair("default_flow", defaultFlow)
    }

    private fun findMatchedTags(
        statusProgression: Map<String, Any?>,
        tags: List<String>,
        activeFlowName: String
    ): List<String> {
        @Suppress("UNCHECKED_CAST")
        val flowMappings = statusProgression["flow_mappings"] as? List<Map<String, Any>>

        if (flowMappings != null) {
            val normalizedTags = tags.map { it.lowercase().trim() }

            for (mapping in flowMappings) {
                val flowName = mapping["flow"] as? String
                if (flowName == activeFlowName) {
                    val mappingTags = (mapping["tags"] as? List<String>)?.map { it.lowercase().trim() } ?: emptyList()
                    return normalizedTags.filter { tag -> mappingTags.contains(tag) }
                }
            }
        }

        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getTerminalStatuses(statusProgression: Map<String, Any?>): List<String> {
        return statusProgression["terminal_statuses"] as? List<String> ?: emptyList()
    }

    private fun normalizeStatus(status: String): String {
        return status.lowercase().replace('_', '-')
    }

    // Config loading (same as StatusValidator pattern)
    private fun loadConfig(): Map<String, Any?>? {
        val now = System.currentTimeMillis()
        val currentUserDir = System.getProperty("user.dir")

        if (cachedUserDir != null && cachedUserDir != currentUserDir) {
            cachedConfig = null
            lastConfigCheck = 0L
        }

        if (cachedConfig != null && (now - lastConfigCheck) < configCacheTimeout) {
            return cachedConfig
        }

        val configPath = getConfigPath()

        if (!Files.exists(configPath)) {
            logger.debug("Config file not found at $configPath")
            lastConfigCheck = now
            cachedConfig = null
            cachedUserDir = currentUserDir
            return null
        }

        return try {
            FileInputStream(configPath.toFile()).use { inputStream ->
                val yaml = Yaml()
                val config = yaml.load<Map<String, Any?>>(inputStream)
                cachedConfig = config
                lastConfigCheck = now
                cachedUserDir = currentUserDir
                config
            }
        } catch (e: Exception) {
            logger.error("Failed to load config from $configPath", e)
            lastConfigCheck = now
            cachedConfig = null
            cachedUserDir = currentUserDir
            null
        }
    }

    private fun getConfigPath(): Path {
        return Paths.get(System.getProperty("user.dir"), ".taskorchestrator", "config.yaml")
    }

    @Suppress("UNCHECKED_CAST")
    private fun getStatusProgressionConfig(containerType: String, config: Map<String, Any?>): Map<String, Any?> {
        val statusProgression = config["status_progression"] as? Map<String, Any?> ?: return emptyMap()
        val pluralType = when (containerType) {
            "project" -> "projects"
            "feature" -> "features"
            "task" -> "tasks"
            else -> return emptyMap()
        }
        return statusProgression[pluralType] as? Map<String, Any?> ?: emptyMap()
    }
}
```

---

## Phase 4: MCP Tool - `get_next_status`

**Create:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/GetNextStatusTool.kt`

See full implementation in plan above.

**Register in `McpServer.kt`:**
```kotlin
// In createTools() method, add to list:
GetNextStatusTool()
```

---

## Phase 5: Simplified Status Progression Skill

**Update:** `src/main/resources/claude/skills/status-progression/SKILL.md`

**Goal:** Reduce from 1048 lines to ~250 lines (76% reduction)

See full simplified skill content in plan above.

---

## Phase 6: Tests

### 6.1: StatusProgressionServiceImplTest

Test flow selection, tag matching, priority, terminal statuses, and flow path retrieval.

### 6.2: GetNextStatusToolTest

Test tool validation, parameter handling, and integration with service.

### 6.3: StatusValidator Integration Tests

Update existing tests for tag-aware flow selection.

---

## Phase 7: Documentation

### 7.1: Update CLAUDE.md

Add `get_next_status` tool documentation under "Consolidated Tools (v2.0+)".

### 7.2: Create docs/status-progression.md

Move detailed examples, hook integration, and scenario patterns (250+ lines from skill).

### 7.3: Update api-reference.md

Add complete API documentation for `get_next_status` tool.

---

## Phase 8: Cleanup

1. Remove unused flow definitions from config.yaml:
   - `with_review`, `with_review_iterations`, `with_blocking`, `with_review_flow`

2. Update setup_claude_orchestration tool to use new config schema

3. Run all tests and fix integration issues

---

## Implementation Order

1. ✅ Update `default-config.yaml` schema (Phase 1)
2. ✅ Refactor `StatusValidator.kt` (Phase 2)
3. ✅ Create `StatusProgressionService` interface (Phase 3.1)
4. ✅ Implement `StatusProgressionServiceImpl` (Phase 3.2)
5. ✅ Create `GetNextStatusTool` (Phase 4)
6. ✅ Register tool in `McpServer` (Phase 4)
7. ✅ Simplify Status Progression Skill (Phase 5)
8. ✅ Write tests (Phase 6)
9. ✅ Update documentation (Phase 7)
10. ✅ Cleanup and final validation (Phase 8)

---

## Success Metrics

✅ **Config schema cleaned:** `allowed_statuses` removed, unused flows deleted
✅ **Service layer testable:** StatusProgressionService isolated and tested
✅ **Tool functional:** get_next_status works universally across MCP clients
✅ **Skill simplified:** 76% reduction (1048 → 250 lines)
✅ **Pattern consistency:** Follows RecommendAgent pattern exactly
✅ **Tag-aware:** Different flows for bugs, docs, hotfixes, prototypes
✅ **Read-only:** Leverages existing ManageContainer for writes

---

## File Structure Summary

```
src/main/kotlin/.../application/
├── service/
│   ├── StatusValidator.kt (refactored - tag-aware flow selection)
│   └── progression/
│       ├── StatusProgressionService.kt (new interface)
│       └── StatusProgressionServiceImpl.kt (new implementation)
└── tools/
    └── status/
        └── GetNextStatusTool.kt (new MCP tool)

src/main/resources/
├── claude/
│   ├── configuration/
│   │   └── default-config.yaml (updated schema)
│   └── skills/
│       └── status-progression/
│           └── SKILL.md (simplified to 250 lines)

src/test/kotlin/.../
├── application/
│   ├── service/
│   │   ├── StatusValidatorTest.kt (updated)
│   │   └── progression/
│   │       └── StatusProgressionServiceImplTest.kt (new)
│   └── tools/status/
│       └── GetNextStatusToolTest.kt (new)

docs/
└── status-progression.md (new - detailed examples)

StatusWorkflowRefactoringPlan.md (this file - at project root)
```
