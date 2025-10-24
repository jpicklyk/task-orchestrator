# Feature: Complete v2.0 Status System Alignment & Enhancement

## Overview
Align database schema, Kotlin enums, and config.yaml for complete v2.0 status support. Add 11 new statuses (8 missing + 3 high-value), create alignment tests, enhance Status Progression Skill, and document typical workflows.

---

## 📋 Tasks Breakdown

### Task 1: Database Migration V12 - Complete v2.0 Status Support
**Complexity:** 7 | **Priority:** HIGH

**Objective:** Create single migration adding all missing v2.0 statuses to database CHECK constraints.

**Statuses to Add (11 total):**

**Currently Missing (8):**
- TaskStatus: `TESTING`, `BLOCKED`
- FeatureStatus: `TESTING`, `VALIDATING`, `PENDING_REVIEW`, `BLOCKED`
- ProjectStatus: `ON_HOLD`, `CANCELLED`

**New High-Value (3):**
- TaskStatus: `DEPLOYED`, `READY_FOR_QA`, `INVESTIGATING`
- FeatureStatus: `DEPLOYED`

**Migration File:** `src/main/resources/db/migration/V12__Complete_V2_Status_System.sql`

**Changes:**
1. Rebuild Projects table with CHECK constraint: `['PLANNING', 'IN_DEVELOPMENT', 'ON_HOLD', 'CANCELLED', 'COMPLETED', 'ARCHIVED']`
2. Rebuild Features table with CHECK constraint: `['DRAFT', 'PLANNING', 'IN_DEVELOPMENT', 'TESTING', 'VALIDATING', 'PENDING_REVIEW', 'BLOCKED', 'ON_HOLD', 'COMPLETED', 'ARCHIVED', 'DEPLOYED']`
3. Rebuild Tasks table with CHECK constraint: `['BACKLOG', 'PENDING', 'IN_PROGRESS', 'IN_REVIEW', 'CHANGES_REQUESTED', 'TESTING', 'READY_FOR_QA', 'INVESTIGATING', 'BLOCKED', 'ON_HOLD', 'DEPLOYED', 'COMPLETED', 'CANCELLED', 'DEFERRED']`
4. Preserve all existing data and indexes
5. Follow V11 migration pattern (create new, copy data, drop old, rename)

**Acceptance Criteria:**
- ✅ Migration runs successfully on clean database
- ✅ Migration runs successfully on existing v11 database
- ✅ All data preserved during migration
- ✅ All indexes recreated
- ✅ CHECK constraints accept all new statuses
- ✅ CHECK constraints reject invalid statuses

---

### Task 2: Kotlin Enum Updates
**Complexity:** 3 | **Priority:** HIGH

**Objective:** Update domain model enums to include new statuses.

**Files to Update:**
- `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/Task.kt` - Add `DEPLOYED`, `READY_FOR_QA`, `INVESTIGATING`
- `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/Feature.kt` - Add `DEPLOYED`

**Code Changes:**
```kotlin
// Task.kt
enum class TaskStatus {
    // ... existing statuses ...
    READY_FOR_QA,      // Manual QA gate (after automated testing)
    INVESTIGATING,     // Bug investigation/root cause analysis
    DEPLOYED;          // Deployed to environment (use tags for staging/production)
}

// Feature.kt
enum class FeatureStatus {
    // ... existing statuses ...
    DEPLOYED;          // Deployed to environment (use tags for staging/production)
}
```

**Documentation:**
- Add inline comments explaining each new status
- Document deployment tag convention (`["staging"]`, `["production"]`)

**Acceptance Criteria:**
- ✅ Enums compile successfully
- ✅ All status conversion functions work (fromString)
- ✅ Status normalization works (uppercase with underscores → lowercase with hyphens)

---

### Task 3: Alignment Tests - Schema/Config/Enum Consistency
**Complexity:** 8 | **Priority:** HIGH

**Objective:** Create comprehensive tests ensuring database, config.yaml, and Kotlin enums stay aligned.

**New Test File:** `src/test/kotlin/io/github/jpicklyk/mcptask/integration/SchemaConfigAlignmentTest.kt`

**Test Categories:**

**1. Database-to-Config Alignment:**
- Read V12 migration CHECK constraints
- Parse default-config.yaml allowed_statuses
- Assert: All config statuses are in database CHECK constraints
- Assert: All database CHECK statuses are in config (or explain deviation)

**2. Enum-to-Config Alignment:**
- Get all enum values from TaskStatus, FeatureStatus, ProjectStatus
- Parse default-config.yaml allowed_statuses
- Assert: All config statuses have corresponding enum values
- Assert: All enum values are in config allowed_statuses

**3. Database-to-Enum Alignment:**
- Read database CHECK constraints (query SQLite schema)
- Get all enum values
- Assert: All enum values are in database CHECK constraints
- Assert: All database CHECK values have enum equivalents

**4. Normalization Consistency:**
- Test status string normalization (UPPERCASE → lowercase-with-hyphens)
- Verify enum names match config format after normalization
- Test StatusValidator.normalizeStatus() against all statuses

**Test Patterns:**
```kotlin
@Test
fun `config allowed_statuses match database CHECK constraints for tasks`() {
    // Load config.yaml
    val config = loadConfig()
    val allowedStatuses = config.statusProgression.tasks.allowedStatuses

    // Query database CHECK constraint
    val dbConstraint = database.queryCheckConstraint("tasks", "status")
    val dbStatuses = parseCheckConstraintValues(dbConstraint)

    // Compare
    assertEquals(
        allowedStatuses.sorted(),
        dbStatuses.sorted(),
        "Config allowed_statuses must match database CHECK constraint"
    )
}

@Test
fun `enum values match config allowed_statuses for tasks`() {
    val enumStatuses = TaskStatus.entries
        .map { it.name.lowercase().replace('_', '-') }
        .sorted()

    val config = loadConfig()
    val configStatuses = config.statusProgression.tasks.allowedStatuses.sorted()

    assertEquals(enumStatuses, configStatuses)
}
```

**Acceptance Criteria:**
- ✅ All alignment tests pass after V12 migration
- ✅ Tests fail if any source (DB/config/enum) becomes inconsistent
- ✅ Clear error messages indicate which source is misaligned
- ✅ Tests run as part of standard test suite (./gradlew test)

---

### Task 4: Migration Test - V12 Validation
**Complexity:** 6 | **Priority:** HIGH

**Objective:** Create comprehensive migration test for V12 following existing patterns.

**New Test File:** `src/test/kotlin/io/github/jpicklyk/mcptask/infrastructure/database/migration/CompleteV2StatusSystemTest.kt`

**Test Cases:**
1. ✅ Migration applies successfully on clean database
2. ✅ Migration applies successfully on V11 database with existing data
3. ✅ All new statuses accepted by CHECK constraints
4. ✅ Invalid statuses rejected by CHECK constraints
5. ✅ Data preserved during migration (projects, features, tasks)
6. ✅ Indexes recreated correctly
7. ✅ Performance: Migration completes within reasonable time

**Follow Pattern From:** `StatusConstraintRestorationTest.kt`

**Acceptance Criteria:**
- ✅ All test cases pass
- ✅ Migration tested with real v11 database state
- ✅ Edge cases covered (empty database, large dataset)

---

### Task 5: Update default-config.yaml
**Complexity:** 5 | **Priority:** HIGH

**Objective:** Update config to include ALL supported statuses (even if not in default workflows) and document deployment tag convention.

**File:** `src/main/resources/orchestration/default-config.yaml`

**Changes:**

**1. Add ALL Allowed Statuses:**
```yaml
status_progression:
  tasks:
    # All valid task statuses (v2.0) - 14 total statuses
    allowed_statuses:
      - backlog
      - pending
      - in-progress
      - in-review
      - changes-requested
      - testing
      - ready-for-qa        # NEW: Manual QA gate
      - investigating       # NEW: Bug investigation phase
      - blocked
      - on-hold
      - deployed            # NEW: Deployed to environment (see tags below)
      - completed
      - cancelled
      - deferred

  features:
    # All valid feature statuses (v2.0) - 11 total statuses
    allowed_statuses:
      - draft
      - planning
      - in-development
      - testing
      - validating
      - pending-review
      - blocked
      - on-hold
      - deployed            # NEW: Deployed to environment (see tags below)
      - completed
      - archived

  projects:
    # All valid project statuses (v2.0) - 6 total statuses
    allowed_statuses:
      - planning
      - in-development
      - on-hold
      - cancelled
      - completed
      - archived
```

**2. Add Deployment Tag Convention Documentation:**
```yaml
# ============================================================================
# DEPLOYMENT TAG CONVENTION (v2.0)
# ============================================================================
# Use the DEPLOYED status with environment tags for deployment tracking:
#
# Environment Tags:
# - ["staging"]     - Deployed to staging environment
# - ["production"]  - Deployed to production environment
# - ["canary"]      - Canary deployment (partial production)
# - ["dev"]         - Deployed to development environment
#
# Example Usage:
#   manage_container(
#     operation="setStatus",
#     containerType="task",
#     status="deployed",
#     tags="staging,backend-api"
#   )
#
# Hook Integration:
#   Claude hooks can read tags from tool_input or query database:
#   TAGS=$(echo "$INPUT" | jq -r '.tool_input.tags // empty')
#   if [[ "$TAGS" == *"production"* ]]; then
#     # Trigger production deployment actions
#   fi
#
# Why Tags Instead of Multiple Statuses:
# - Simpler status model (fewer statuses to manage)
# - Composable with other tags (e.g., "production,backend")
# - Hook-friendly (easy to parse and branch on)
# - Flexible (users can define custom environments)
```

**3. Add Status Descriptions for New Statuses:**
```yaml
# READY_FOR_QA: Implementation complete, automated tests pass, ready for manual QA testing.
#               Use when: Code works and CI passes, needs human validation.
#               Next steps: QA testing → DEPLOYED or back to IN_PROGRESS for fixes.
#
# INVESTIGATING: Bug or issue root cause analysis phase.
#                Use when: Problem identified but cause unknown, actively debugging.
#                Next steps: Find root cause → IN_PROGRESS to fix.
#
# DEPLOYED: Deployed to an environment (staging, production, etc.).
#           Use when: Code live in target environment.
#           Environment specified via tags: ["staging"], ["production"], ["canary"], ["dev"]
#           Next steps: Monitoring period → COMPLETED when stable.
```

**4. Add Alternative Workflows:**
```yaml
# QA workflow with manual testing gate
with_qa_gate:
  - backlog
  - pending
  - in-progress
  - testing              # Automated tests
  - ready-for-qa         # Manual QA gate
  - deployed
  - completed

# Bug investigation workflow
bug_workflow:
  - pending
  - investigating        # Root cause analysis
  - in-progress         # Implementing fix
  - testing
  - deployed
  - completed

# Deployment workflow with staging
deployment_workflow:
  - in-progress
  - testing
  - deployed            # Tag: ["staging"]
  - deployed            # Tag: ["production"] (same status, different tag)
  - completed
```

**Acceptance Criteria:**
- ✅ All 14 task statuses documented
- ✅ All 11 feature statuses documented
- ✅ All 6 project statuses documented
- ✅ Deployment tag convention clearly explained
- ✅ Example workflows for new statuses
- ✅ Hook integration examples provided

---

### Task 6: Status Progression Skill Enhancement
**Complexity:** 7 | **Priority:** MEDIUM

**Objective:** Enhance skill to be more proactive about validation and aware of new statuses.

**File:** `src/main/resources/skills/status-progression/SKILL.md`

**Enhancements:**

**1. Add Script for Programmatic Status Navigation:**
```markdown
## Status Navigation Script (Optional)

Skills can include bash/python scripts to help with programmatic checks.

**Script: check-next-status.sh**
```bash
#!/bin/bash
# Checks what status transitions are allowed from current status

CONFIG_PATH="$CLAUDE_PROJECT_DIR/.taskorchestrator/config.yaml"
CURRENT_STATUS="$1"
ENTITY_TYPE="$2"  # tasks, features, projects

# Parse config.yaml and find current status position in default_flow
# Return next allowed statuses

# This script can be called by the AI to programmatically check transitions
```

**2. Add Two-Part Workflow Understanding:**
```markdown
## Two-Part Status Transition Workflow

When users want to change status, follow this two-part process:

### Part 1: Navigation - What's Next/Previous?
**Your responsibility:** Read config.yaml and tell user what statuses they CAN move to.

```javascript
// Read config
config = Read(".taskorchestrator/config.yaml")
flow = config.status_progression.tasks.default_flow
currentIndex = flow.indexOf(currentStatus)

// Calculate allowed transitions
nextStatus = flow[currentIndex + 1]  // Sequential forward
previousStatus = flow[currentIndex - 1]  // If allow_backward: true
emergencyStatuses = config.status_progression.tasks.emergency_transitions

// Present options
"From '{currentStatus}' you can move to:
- Forward: {nextStatus} (next in flow)
- Backward: {previousStatus} (if rework needed)
- Emergency: {emergencyStatuses.join(', ')} (anytime)"
```

### Part 2: Validation - Prerequisites Met?
**StatusValidator's responsibility:** When user calls `manage_container(operation="setStatus")`, automatic validation occurs.

**Your proactive role:** Check prerequisites BEFORE user attempts change to help avoid errors.

```javascript
// Before user attempts completion
if (newStatus === "completed") {
  // Check task prerequisites
  task = query_container(operation="get", containerType="task", id="...")

  // Prerequisite checks:
  summaryLength = task.summary.length
  if (summaryLength < 300 || summaryLength > 500) {
    "⚠️ Not ready: Summary must be 300-500 chars (current: {summaryLength})"
  }

  // Check blocking dependencies
  deps = query_dependencies(taskId="...", direction="incoming", type="BLOCKS")
  incompleteDeps = deps.filter(d => d.status !== "completed")
  if (incompleteDeps.length > 0) {
    "⚠️ Not ready: {incompleteDeps.length} blocking task(s) not completed"
  }
}
```

**Key Principle:** You guide and check proactively, StatusValidator enforces when manage_container is called.
```

**3. Add New Status Awareness:**
```markdown
## New v2.0 Statuses (Deployment & QA)

### DEPLOYED Status
**Meaning:** Task/feature deployed to an environment
**Environment:** Specified via tags (`["staging"]`, `["production"]`, `["canary"]`)

**Hook Integration:** Claude hooks can trigger actions based on deployment tags:
```bash
# Hook example
TAGS=$(echo "$INPUT" | jq -r '.tool_input.tags // empty')
if [[ "$TAGS" == *"production"* ]]; then
  echo "🚀 Production deployment detected!"
  # Run production deployment checks
fi
```

**Workflow Position:**
- testing → deployed (staging) → deployed (production) → completed

### READY_FOR_QA Status
**Meaning:** Automated tests pass, ready for manual QA
**Use When:** CI green, needs human testing

**Prerequisite:** TESTING status completed with passing tests

**Workflow Position:**
- testing → ready-for-qa → (manual QA) → deployed

### INVESTIGATING Status
**Meaning:** Bug root cause analysis phase
**Use When:** Bug found but cause unknown

**Workflow Position:**
- pending → investigating → in-progress (fixing) → testing
```

**Acceptance Criteria:**
- ✅ Skill explains two-part workflow (navigation + validation)
- ✅ Skill is proactive about prerequisite checking
- ✅ Skill aware of all 14 task / 11 feature / 6 project statuses
- ✅ Deployment tag convention explained
- ✅ Hook integration examples included

---

### Task 7: StatusValidator Enhancement
**Complexity:** 5 | **Priority:** MEDIUM

**Objective:** Improve error messages and add support for deployment tag awareness.

**File:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/StatusValidator.kt`

**Enhancements:**

**1. Deployment Tag Validation (Advisory):**
```kotlin
/**
 * Provides advisory warnings about deployment tags.
 * Not a hard validation, just helpful guidance.
 */
private fun checkDeploymentTagsAdvisory(
    status: String,
    tags: List<String>
): List<String> {
    val suggestions = mutableListOf<String>()

    if (status == "deployed" && tags.none { it in listOf("staging", "production", "canary", "dev") }) {
        suggestions.add("Consider adding environment tag: 'staging', 'production', 'canary', or 'dev'")
    }

    return suggestions
}
```

**2. Enhanced Error Messages:**
```kotlin
// Current: "Cannot transition to COMPLETED: Task summary must be 300-500 characters (current: 50)"
// Enhanced: Add more context and fix suggestions

ValidationResult.Invalid(
    "Cannot transition to COMPLETED: Task summary must be 300-500 characters (current: $summaryLength characters)",
    listOf(
        "Update task summary to describe work completed",
        "Example: manage_container(operation=\"update\", containerType=\"task\", id=\"...\", summary=\"...\")",
        "Tip: Good summaries explain WHAT was done and WHY"
    )
)
```

**3. Prerequisite Check for READY_FOR_QA:**
```kotlin
"ready-for-qa" -> {
    // Advisory: Typically comes after TESTING
    // No hard prerequisite, but provide guidance
    ValidationResult.Valid
}
```

**Acceptance Criteria:**
- ✅ Deployment tag advisory works (non-blocking)
- ✅ Enhanced error messages provide actionable guidance
- ✅ All new statuses handled in prerequisite validation
- ✅ Backward compatibility maintained

---

### Task 8: Workflow Documentation
**Complexity:** 6 | **Priority:** MEDIUM

**Objective:** Document typical workflow scenarios for different user types with configuration examples.

**New Documentation File:** `docs/workflow-scenarios.md`

**Content Structure:**

**1. Vibe Coder Workflows**
```markdown
# Workflow Scenarios for Different User Types

## Vibe Coder / Exploratory Workflow

**Characteristics:**
- Quick iteration, minimal process
- Experimenting with ideas
- Solo development

**Recommended Config:**
```yaml
status_validation:
  enforce_sequential: false      # Allow status jumping
  allow_backward: true
  allow_emergency: true
  validate_prerequisites: false  # Relax validation
```

**Typical Flow:**
```
IDEA → BACKLOG → IN_PROGRESS → (works?) → COMPLETED or CANCELLED
```

**Alternative: Spike Flow**
```
DRAFT → INVESTIGATING → IN_PROGRESS → DEPLOYED → COMPLETED
```

**Config Example:** See `examples/vibe-coder-config.yaml`
```

**2. Single Developer Workflows**
```markdown
## Solo Developer / Side Project Workflow

**Characteristics:**
- One person, self-review
- Production deployments
- Need deployment tracking

**Recommended Config:**
```yaml
status_validation:
  enforce_sequential: true       # Keep some structure
  allow_backward: true
  validate_prerequisites: true   # Ensure summaries, etc.
```

**Typical Flow:**
```
BACKLOG → PENDING → IN_PROGRESS → TESTING →
DEPLOYED (tag: staging) → DEPLOYED (tag: production) → COMPLETED
```

**QA Variant (self-testing):**
```
IN_PROGRESS → TESTING → READY_FOR_QA (manual check) → DEPLOYED → COMPLETED
```

**Bug Fix Flow:**
```
PENDING → INVESTIGATING → IN_PROGRESS → TESTING → DEPLOYED → COMPLETED
```

**Config Example:** See `examples/solo-dev-config.yaml`
```

**3. Small Team Workflows**
```markdown
## Small Team (2-5 Developers) Workflow

**Characteristics:**
- Code review required
- QA testing
- Staged deployments
- CI/CD integration

**Recommended Config:**
```yaml
status_validation:
  enforce_sequential: true       # Maintain process
  allow_backward: true          # Allow rework
  validate_prerequisites: true
```

**Typical Flow:**
```
BACKLOG → PENDING → IN_PROGRESS →
IN_REVIEW → (changes?) → CHANGES_REQUESTED → IN_PROGRESS →
IN_REVIEW → TESTING → READY_FOR_QA →
DEPLOYED (staging) → DEPLOYED (production) → COMPLETED
```

**Fast Track (no review):**
```
PENDING → IN_PROGRESS → TESTING → DEPLOYED → COMPLETED
```

**Hotfix Flow:**
```
PENDING → INVESTIGATING → IN_PROGRESS → TESTING →
DEPLOYED (production) → COMPLETED
```

**Config Example:** See `examples/small-team-config.yaml`
```

**4. Hook Integration Examples**
```markdown
## Hook Examples for Workflows

### Auto-Deploy to Staging When DEPLOYED + "staging" Tag
```bash
#!/bin/bash
INPUT=$(cat)

STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')
TAGS=$(echo "$INPUT" | jq -r '.tool_input.tags // empty')

if [ "$STATUS" = "deployed" ] && [[ "$TAGS" == *"staging"* ]]; then
  echo "🚀 Deploying to staging environment..."
  ./scripts/deploy-staging.sh
fi
```

### Require QA Sign-Off Before Production
```bash
#!/bin/bash
INPUT=$(cat)

STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')
TAGS=$(echo "$INPUT" | jq -r '.tool_input.tags // empty')

if [ "$STATUS" = "deployed" ] && [[ "$TAGS" == *"production"* ]]; then
  # Check if QA sign-off exists
  if [ ! -f ".qa-signoff" ]; then
    echo '{"block": true, "message": "❌ Production deployment requires QA sign-off"}'
    exit 2
  fi
fi
```

### Track Deployment Metrics
```bash
#!/bin/bash
INPUT=$(cat)

STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')
TAGS=$(echo "$INPUT" | jq -r '.tool_input.tags // empty')

if [ "$STATUS" = "deployed" ]; then
  TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id')
  TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

  echo "$TIMESTAMP,$TASK_ID,$TAGS" >> deployment-log.csv
fi
```
```

**Configuration Files:**
- `examples/vibe-coder-config.yaml`
- `examples/solo-dev-config.yaml`
- `examples/small-team-config.yaml`

**Acceptance Criteria:**
- ✅ All three user types documented
- ✅ Workflow diagrams clear
- ✅ Config examples provided
- ✅ Hook integration examples included
- ✅ Deployment tag usage explained

---

### Task 9: Update StatusValidatorTest
**Complexity:** 4 | **Priority:** MEDIUM

**Objective:** Add tests for new statuses in existing test suite.

**File:** `src/test/kotlin/io/github/jpicklyk/mcptask/application/service/StatusValidatorTest.kt`

**New Test Cases:**
```kotlin
@Test
fun `validates DEPLOYED status for tasks`(@TempDir tempDir: Path) {
    System.setProperty("user.dir", tempDir.toString())
    val result = validator.validateStatus("deployed", "task")
    assertTrue(result is StatusValidator.ValidationResult.Valid)
}

@Test
fun `validates READY_FOR_QA status for tasks`(@TempDir tempDir: Path) {
    System.setProperty("user.dir", tempDir.toString())
    val result = validator.validateStatus("ready-for-qa", "task")
    assertTrue(result is StatusValidator.ValidationResult.Valid)
}

@Test
fun `validates INVESTIGATING status for tasks`(@TempDir tempDir: Path) {
    System.setProperty("user.dir", tempDir.toString())
    val result = validator.validateStatus("investigating", "task")
    assertTrue(result is StatusValidator.ValidationResult.Valid)
}

@Test
fun `validates DEPLOYED status for features`(@TempDir tempDir: Path) {
    System.setProperty("user.dir", tempDir.toString())
    val result = validator.validateStatus("deployed", "feature")
    assertTrue(result is StatusValidator.ValidationResult.Valid)
}

@Test
fun `validates ON_HOLD status for projects`(@TempDir tempDir: Path) {
    System.setProperty("user.dir", tempDir.toString())
    val result = validator.validateStatus("on-hold", "project")
    assertTrue(result is StatusValidator.ValidationResult.Valid)
}

@Test
fun `validates CANCELLED status for projects`(@TempDir tempDir: Path) {
    System.setProperty("user.dir", tempDir.toString())
    val result = validator.validateStatus("cancelled", "project")
    assertTrue(result is StatusValidator.ValidationResult.Valid)
}
```

**Acceptance Criteria:**
- ✅ All new statuses have test coverage
- ✅ Tests pass with V12 migration applied
- ✅ Both v1 (enum) and v2 (config) modes tested

---

### Task 10: Example Configuration Files
**Complexity:** 3 | **Priority:** LOW

**Objective:** Create example config files for different workflow types.

**Files to Create:**
- `docs/examples/vibe-coder-config.yaml` - Minimal validation, max flexibility
- `docs/examples/solo-dev-config.yaml` - Balanced validation, deployment tracking
- `docs/examples/small-team-config.yaml` - Full validation, code review + QA gates

**Acceptance Criteria:**
- ✅ Each example is complete and valid YAML
- ✅ Comments explain why each setting is chosen
- ✅ Examples referenced in workflow-scenarios.md

---

### Task 11: Update API Documentation
**Complexity:** 2 | **Priority:** LOW

**Objective:** Update API reference docs with new statuses.

**File:** `docs/api-reference.md`

**Updates:**
- Add DEPLOYED, READY_FOR_QA, INVESTIGATING to TaskStatus enum docs
- Add DEPLOYED to FeatureStatus enum docs
- Add ON_HOLD, CANCELLED to ProjectStatus enum docs
- Document deployment tag convention
- Add examples of status transitions with new statuses

**Acceptance Criteria:**
- ✅ All new statuses documented
- ✅ Examples use new statuses
- ✅ Tag convention explained

---

## 🎯 Success Criteria

**Critical (Must Have):**
- ✅ V12 migration applies successfully on all database states
- ✅ All alignment tests pass (schema/config/enum consistency)
- ✅ default-config.yaml includes all 14 task / 11 feature / 6 project statuses
- ✅ Deployment tag convention documented and tested
- ✅ StatusValidator accepts all new statuses
- ✅ Status Progression Skill updated with two-part workflow

**Important (Should Have):**
- ✅ Workflow scenarios documented for 3 user types
- ✅ Hook integration examples provided
- ✅ Example config files for different workflows
- ✅ Migration test validates all new statuses

**Nice to Have (Could Have):**
- ✅ StatusValidator enhanced error messages
- ✅ Script examples in Status Progression Skill
- ✅ API reference updated

---

## 🔄 Dependencies

**Critical Path:**
1. Task 2 (Kotlin Enums) → Task 1 (Migration V12) - Enums must be defined before migration
2. Task 1 (Migration V12) → Task 3 (Alignment Tests) - Migration must exist to test
3. Task 1 (Migration V12) → Task 4 (Migration Test) - Migration must exist to test
4. Task 5 (Config Update) → Task 3 (Alignment Tests) - Config must be updated to align

**Parallel Opportunities:**
- Tasks 6, 7, 8 (Skill, Validator, Docs) can be done in parallel after Task 5
- Tasks 9, 10, 11 (Tests, Examples, API Docs) can be done in parallel

**Recommended Execution Order:**
1. Task 2 (Kotlin Enums) - Foundation
2. Task 1 (Migration V12) - Database changes
3. Task 5 (Config Update) - Alignment target
4. Task 3 (Alignment Tests) - Validation
5. Task 4 (Migration Test) - Migration validation
6. Tasks 6, 7, 8 in parallel (Skill, Validator, Docs)
7. Tasks 9, 10, 11 in parallel (Tests, Examples, API Docs)

---

## 📊 Estimated Complexity

- **Total Story Points:** 56
- **Estimated Time:** 2-3 development days
- **Testing Time:** 1 day
- **Documentation Time:** 1 day

---

## ⚠️ Risks & Mitigation

**Risk 1: Migration Fails on Production Database**
- **Mitigation:** Comprehensive migration tests, backup strategy, rollback plan

**Risk 2: Alignment Tests Miss Edge Cases**
- **Mitigation:** Test against multiple database states, include manual verification step

**Risk 3: Hook Examples Don't Work for Users**
- **Mitigation:** Test all hook examples in real Claude Code environment, provide troubleshooting guide

**Risk 4: Status Progression Skill Still Not Proactive Enough**
- **Mitigation:** Consider adding explicit "check readiness" workflow before status changes, iterate based on user feedback

---

## 📝 Notes

**Design Decision: DEPLOYED + Tags vs Multiple Deployment Statuses**
- **Chosen:** DEPLOYED + environment tags
- **Rationale:** Hook-friendly, composable, simpler status model
- **Trade-off:** Requires tag discipline, less explicit than STAGING/PRODUCTION statuses

**Design Decision: Skill Enhancement Approach**
- **Skills are instruction-based** (markdown with embedded script examples)
- **Actual enforcement** happens in StatusValidator.kt (Kotlin code)
- **Skill's role** is to guide AI to be proactive about checks before calling tools

**Open Question: Subagent Access to Skills**
- User asked if subagents can access skills
- Need to verify: Can Backend Engineer subagent invoke Status Progression Skill?
- If not, may need to document Status Progression patterns in subagent definitions
