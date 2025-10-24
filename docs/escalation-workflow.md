# Escalation Workflow Documentation

## Architecture Overview

Task Orchestrator v2.0 uses a **layered specialist architecture** with **automatic escalation** for complex problems.

```
┌─────────────────────────────────────────┐
│   Feature Architect (Opus)              │  Strategic Layer
│   Complex reasoning for feature design  │
└─────────────────┬───────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────┐
│   Planning Specialist (Sonnet)          │  Tactical Layer
│   Task breakdown, execution graphs      │
│   Creates well-defined tasks ⬇          │
└─────────────────┬───────────────────────┘
                  │
                  ▼
    ┌─────────────┴───────────────┐
    │                             │
    ▼                             ▼
┌───────────────────────┐   ┌──────────────────────┐
│ Implementation        │   │ Senior Engineer      │  Execution Layer
│ Specialist (Haiku)    │──▶│ (Sonnet)             │
│                       │   │                      │
│ • Standard work       │   │ • Complex problems   │
│ • Follows plans       │   │ • Debugging          │
│ • Uses Skills         │   │ • Unblocking         │
│ • Reports blockers    │   │ • Investigation      │
└───────────────────────┘   └──────────────────────┘
         │                           │
         │ Loads on-demand           │
         ▼                           ▼
    ┌────────────────────┐    ┌─────────────┐
    │  Domain Skills     │    │Investigation│
    │  • Backend         │    │   Skills    │
    │  • Frontend        │    │             │
    │  • Database        │    │             │
    │  • Testing         │    │             │
    │  • Documentation   │    │             │
    └────────────────────┘    └─────────────┘
```

## Agent Responsibilities

### Implementation Specialist (Haiku)
**Role:** Fast, efficient execution of well-defined tasks
**Model:** Haiku (4-5x faster, 1/3 cost of Sonnet)
**When to use:** Standard implementation with clear requirements

**Handles:**
- Backend API development
- Frontend UI components
- Database migrations
- Test writing
- Documentation creation

**With Skills loaded based on tags:**
- `[backend]` → Loads backend-implementation Skill
- `[frontend, database]` → Loads both Skills (composable)

**Escalates when:**
- Encounters blocker it cannot resolve (NPE, test failure, missing dependency)
- Needs complex debugging
- Requires architecture decision
- Blocked by external issue

**Success rate:** 90%+ for well-defined tasks

### Senior Engineer (Sonnet)
**Role:** Complex problem solving, debugging, unblocking
**Model:** Sonnet (better reasoning)
**When to use:** Bugs, errors, blockers, complex issues

**Handles:**
- Bug investigation and fixing
- Debugging NPEs, race conditions, integration failures
- Unblocking Implementation Specialist
- Performance optimization
- Complex refactoring
- Tactical architecture decisions

**Escalates when:**
- Needs strategic architecture decision → Feature Architect
- Missing external resources (cannot solve internally)

**Success rate:** 95%+ for complex debugging

### Feature Architect (Opus)
**Role:** Feature design from ambiguous requirements
**Model:** Opus (complex reasoning)
**When to use:** New features, unclear requirements, strategic architecture

**Handles:**
- Transforming concepts into structured features
- PRD creation
- Strategic architecture decisions
- Template discovery and application

**Hands off to:**
- Planning Specialist for task breakdown

### Planning Specialist (Sonnet)
**Role:** Task decomposition with execution graphs
**Model:** Sonnet (balanced reasoning)
**When to use:** Feature → tasks, complex work breakdown

**Handles:**
- Domain-isolated task breakdown
- Execution graph creation
- Dependency management
- Template application to tasks

**Hands off to:**
- Implementation Specialist for execution

## Escalation Patterns

### Pattern 1: Standard Implementation → Blocker → Senior Engineer

**Scenario:** Implementation Specialist encounters NPE it cannot debug

**Flow:**
```
1. Implementation Specialist (Haiku) working on task
2. Encounters NullPointerException in UserService
3. Attempts fixes (2-3 attempts):
   - Check dependency injection
   - Add null safety
   - Review similar code
4. Cannot resolve → Reports blocker
5. Orchestrator escalates to Senior Engineer (Sonnet)
6. Senior Engineer debugs, finds missing @Configuration
7. Implements fix
8. Returns solution to orchestrator
9. Task marked complete
```

**Blocker Report Format:**
```
⚠️ BLOCKED - Requires Senior Engineer

Issue: NullPointerException in UserService.createUser() at line 42

Attempted Fixes:
- Checked @Autowired annotation - present
- Verified constructor injection - correct
- Added null checks - NPE still occurs

Root Cause (if known): Likely Spring configuration issue

Partial Progress: UserService structure complete, unit tests passing

Context for Senior Engineer:
- Error: NPE at passwordEncoder.encode()
- Files: UserService.kt, SecurityConfig.kt
- Tests: 12 unit tests passing, 3 integration tests failing

Requires: Senior Engineer to debug Spring dependency injection
```

### Pattern 2: Feature Creation → Planning → Implementation

**Scenario:** User requests new feature

**Flow:**
```
1. User: "I want to add user authentication"
2. Orchestrator routes to Feature Architect (Opus)
3. Feature Architect:
   - Extracts requirements
   - Creates structured feature
   - Discovers relevant templates (authentication, security)
   - Applies templates
4. Feature Architect hands off to Planning Specialist (Sonnet)
5. Planning Specialist:
   - Breaks feature into domain-isolated tasks
   - Backend: Implement auth API
   - Database: Add users table
   - Frontend: Login/logout UI
   - Testing: Auth test suite
   - Documentation: Auth API docs
   - Creates execution graph with dependencies
6. Tasks created, ready for Implementation Specialist (Haiku)
7. Implementation Specialist executes tasks one by one
8. Loads appropriate Skills per task (backend → backend-implementation)
```

### Pattern 3: Bug Report → Investigation → Fix

**Scenario:** User reports bug

**Flow:**
```
1. User: "Login is broken - getting 500 error"
2. Orchestrator routes to Senior Engineer (Sonnet) [bug tag]
3. Senior Engineer:
   - Creates structured bug task with reproduction steps
   - Severity assessment
   - Affected component identification
4. Senior Engineer investigates:
   - Reproduces bug
   - Analyzes logs
   - Identifies root cause (missing password encoder)
5. Senior Engineer implements fix:
   - Adds @Configuration annotation
   - Registers password encoder bean
   - Tests fix
6. All tests pass → marks task complete
7. Returns summary to orchestrator
```

### Pattern 4: Multi-Domain Task → Composable Skills

**Scenario:** Task spans multiple domains

**Flow:**
```
1. Task: "Add user profile feature"
2. Tags: [backend, database, frontend]
3. Orchestrator routes to Implementation Specialist (Haiku)
4. Implementation Specialist loads THREE Skills:
   - backend-implementation (API endpoints)
   - database-implementation (profile table)
   - frontend-implementation (profile UI)
5. Full-stack context in single agent
6. Executes efficiently with domain expertise from all 3 Skills
7. If blocked → escalates to Senior Engineer with full context
```

## Decision Tree: Which Agent?

### User Request → Agent Selection

```
User Request
    │
    ├─ New feature, unclear requirements?
    │  └─ YES → Feature Architect (Opus)
    │
    ├─ Break down complex feature into tasks?
    │  └─ YES → Planning Specialist (Sonnet)
    │
    ├─ Bug, error, broken, "doesn't work"?
    │  └─ YES → Senior Engineer (Sonnet)
    │
    ├─ Standard implementation with clear task?
    │  └─ YES → Implementation Specialist (Haiku)
    │     │
    │     ├─ Check task tags
    │     ├─ Load relevant Skills
    │     ├─ Execute with domain knowledge
    │     │
    │     └─ Blocked?
    │        └─ YES → Escalate to Senior Engineer (Sonnet)
```

### Task Tags → Routing

```
Task Tags                    Agent                 Skills Loaded
────────────────────────────────────────────────────────────────
[backend, api]          → Implementation (Haiku)   + backend-implementation
[frontend, ui]          → Implementation (Haiku)   + frontend-implementation
[database, migration]   → Implementation (Haiku)   + database-implementation
[testing, qa]           → Implementation (Haiku)   + testing-implementation
[documentation]         → Implementation (Haiku)   + documentation-implementation

[backend, database]     → Implementation (Haiku)   + backend + database Skills
[frontend, testing]     → Implementation (Haiku)   + frontend + testing Skills

[bug, backend]          → Senior Engineer (Sonnet)  + loads Skills if needed
[blocker, error]        → Senior Engineer (Sonnet)
[complex, refactor]     → Senior Engineer (Sonnet)

[feature-creation]      → Feature Architect (Opus)
[planning]              → Planning Specialist (Sonnet)
```

## Cost and Performance Analysis

### Standard Implementation Task (70% of tasks)

**Before (v1.0):**
- Agent: Backend Engineer (Sonnet)
- Cost: ~$0.03 per task
- Speed: 2-5 seconds per response
- Token usage: ~10,000 tokens

**After (v2.0):**
- Agent: Implementation Specialist (Haiku) + backend-implementation Skill
- Cost: ~$0.01 per task (66% reduction)
- Speed: 0.5-1 second per response (4x faster)
- Token usage: ~3,000 tokens (70% reduction)

**Annual savings (100 tasks):**
- Cost: $2.00 saved
- Time: 250 seconds saved
- Tokens: 700,000 saved

### Complex Problem Task (10% of tasks)

**Before (v1.0):**
- Agent: Backend Engineer (Sonnet) - struggles with debugging
- Success rate: 80%
- Often requires multiple attempts

**After (v2.0):**
- Agent: Senior Engineer (Sonnet) - focused on complex problems
- Success rate: 95%
- Better reasoning, faster resolution

**Improvement:**
- Higher success rate
- Faster resolution
- Better problem-solving focus

### Escalation Overhead (10% of tasks)

**Scenario:** Implementation Specialist blocked → Senior Engineer

**Cost:**
- Implementation Specialist attempt: ~$0.01 (Haiku)
- Senior Engineer resolution: ~$0.03 (Sonnet)
- Total: ~$0.04

**Still cheaper than v1.0:**
- v1.0: All tasks use Sonnet (~$0.03)
- v2.0: 90% use Haiku ($0.01), 10% escalate ($0.04)
- Average: $0.013 per task (57% savings)

## Blocker Escalation Best Practices

### When to Escalate

**✅ DO escalate when:**
- Cannot fix issue after 2-3 reasonable attempts
- Need deeper debugging (NPE, race condition, complex error)
- Require architecture decision beyond task scope
- Missing external resources (database, API, infrastructure)
- Requirements unclear or contradictory

**❌ DON'T escalate for:**
- Simple syntax errors (fix yourself)
- Missing imports (fix yourself)
- Typos in code (fix yourself)
- Test assertions need adjustment (fix yourself)
- Configuration values (check docs/codebase)

### How to Write Good Blocker Reports

**Essential Elements:**
1. **Specific issue** - exact error, line number, symptom
2. **Attempted fixes** - what you tried (2-3 attempts minimum)
3. **Why attempts failed** - explain results
4. **Partial progress** - what you DID complete
5. **Context** - error output, related files
6. **Clear ask** - what's needed to unblock

**Example Good Blocker Report:**
```
⚠️ BLOCKED - Requires Senior Engineer

Issue: Integration tests fail with "Connection refused to localhost:5432"

Attempted Fixes:
1. Checked test configuration - uses H2 in-memory, should not connect to PostgreSQL
2. Reviewed application-test.yml - datasource configured for H2
3. Searched codebase for "5432" - found hardcoded connection in DataSourceConfig
4. Tried overriding with @TestPropertySource - still attempts PostgreSQL connection

Root Cause (if known): DataSourceConfig ignores test profile, always uses production PostgreSQL config

Partial Progress:
- Test structure complete (15 test methods)
- Mock data setup working
- Unit tests passing (12/12)
- Only integration tests fail due to database connection

Context for Senior Engineer:
- Error: java.net.ConnectException: Connection refused (Connection refused)
- Files: DataSourceConfig.kt, application-test.yml
- Stack trace: [paste relevant portion]

Requires: Senior Engineer to fix Spring profile configuration for test environment
```

**Example Bad Blocker Report:**
```
Tests are failing. Not sure why. Can you help?

← Missing: specific issue, attempted fixes, context, partial progress
```

## Skill Loading Optimization

### Progressive Disclosure

Skills use **lazy loading** for token efficiency:

**Metadata (always loaded, ~100 tokens):**
```yaml
---
name: Backend Implementation
description: Backend development with Kotlin, Spring Boot...
---
```

**SKILL.md (loaded when relevant, ~400 tokens):**
- Validation commands
- Common patterns
- Blocker scenarios
- Quick reference

**Additional Resources (loaded if needed, ~200 tokens each):**
- PATTERNS.md - Deeper domain patterns
- BLOCKERS.md - Detailed blocker solutions
- examples.md - Working code examples

**Total cost:**
- Simple task: 100 + 400 = 500 tokens
- Complex task: 100 + 400 + 200 = 700 tokens
- Very complex: 100 + 400 + 400 = 900 tokens

**Compare to v1.0:**
- v1.0: Full specialist definition (1,500+ tokens, always)
- v2.0: Progressive loading (500-900 tokens, as needed)
- **Savings: 40-70% token reduction**

### Multi-Skill Composition

**Task with [backend, database] tags:**

```
Implementation Specialist loads:
├─ backend-implementation Skill (~400 tokens)
│  ├─ Gradle test commands
│  ├─ Spring Boot patterns
│  └─ REST API guidance
└─ database-implementation Skill (~450 tokens)
   ├─ Flyway migration commands
   ├─ SQL patterns
   └─ Schema validation

Total: ~850 tokens for full-stack context
```

**Alternative in v1.0:**
- Would need to route to Backend Engineer (1,500 tokens)
- Then create separate database task → Database Engineer (1,500 tokens)
- Total: 3,000 tokens + task creation overhead
- **v2.0 saves: 72% tokens**

## Monitoring and Metrics

### Key Metrics to Track

**Escalation Rate:**
- Target: 10-20% of tasks escalate from Implementation to Senior Engineer
- If higher: Tasks may be too complex, need better planning
- If lower: May be underutilizing Senior Engineer's capabilities

**Success Rate by Agent:**
- Implementation Specialist: 90%+ (with escalation available)
- Senior Engineer: 95%+ (complex problems)
- Feature Architect: 95%+ (feature design)
- Planning Specialist: 98%+ (task breakdown)

**Cost per Task:**
- Implementation (Haiku): ~$0.01
- Senior Engineer (Sonnet): ~$0.03
- Feature Architect (Opus): ~$0.15
- Blended average: ~$0.015 (57% savings vs v1.0)

**Speed per Task:**
- Implementation (Haiku): 0.5-1s
- Senior Engineer (Sonnet): 2-5s
- Feature Architect (Opus): 5-10s
- Average: 1-2s (3-4x faster vs v1.0)

## Troubleshooting

### Implementation Specialist Not Using Skills

**Problem:** Agent doesn't load Skills even with appropriate tags

**Solutions:**
1. Check task has tags (no tags = no Skill loading)
2. Verify Skills exist in `.claude/skills/` directory
3. Check agent-mapping.yaml skill_routing configuration
4. Ensure agent instructions mention Skill discovery (Step 3)

### Too Many Escalations

**Problem:** Implementation Specialist escalates frequently (>30%)

**Solutions:**
1. Review task quality - are tasks well-defined?
2. Check if Planning Specialist providing enough detail
3. Enhance Skills with more patterns/examples
4. Consider if tasks are inherently complex (should go to Senior Engineer directly)

### Skills Not Loading Correctly

**Problem:** Skill loaded but agent doesn't follow guidance

**Solutions:**
1. Check SKILL.md is under 500 lines (per best practices)
2. Verify YAML frontmatter is correct
3. Ensure guidance is clear and actionable
4. Add concrete examples (agents follow examples well)

### Wrong Agent Selected

**Problem:** Bug goes to Implementation Specialist instead of Senior Engineer

**Solutions:**
1. Check task tags include `bug`, `error`, or `blocker`
2. Review tagPriority in agent-mapping.yaml (bug should be first)
3. Verify tag mapping is correct
4. Update recommend_agent() if needed

## Summary

Task Orchestrator v2.0 provides:

✅ **Fast, cost-effective implementation** - Haiku + Skills (90% of work)
✅ **Complex problem solving** - Senior Engineer (Sonnet) for debugging
✅ **Automatic escalation** - Built-in safety net for blockers
✅ **Composable expertise** - Multi-Skill loading for full-stack tasks
✅ **Progressive disclosure** - Load only what's needed
✅ **Clear escalation paths** - Well-defined handoffs between agents

**Result:** 57% cost reduction, 4x speed improvement, maintained quality

## References

- **Agent Definitions**: `src/main/resources/claude/agents/`
- **Skill Definitions**: `src/main/resources/claude/skills/`
- **Agent Mapping**: `src/main/resources/claude/configuration/agent-mapping-v2.yaml`
- **Architecture Doc**: `docs/agent-orchestration.md`
- **Hybrid Architecture**: `docs/hybrid-architecture.md`
