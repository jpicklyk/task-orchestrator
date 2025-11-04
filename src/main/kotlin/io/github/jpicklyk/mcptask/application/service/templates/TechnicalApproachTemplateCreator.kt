package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for technical approach with actionable guidance (no placeholders).
 * Provides decision-making frameworks and checklists to guide implementation planning.
 */
object TechnicalApproachTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Technical Approach",
            description = "Actionable guidance for implementation planning with decision frameworks and validation checklists (no placeholders).",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("technical", "architecture", "implementation", "strategy", "guidance")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Implementation Planning Checklist",
                usageDescription = "Decision-making framework to guide implementation planning before coding",
                contentSample = """### Implementation Planning Checklist

Before you start coding, work through these planning questions:

#### 1. Component Identification
**What are the 2-4 main classes/modules you'll create or modify?**
- List each component with its single responsibility
- Example: `UserService` (CRUD operations), `AuthController` (HTTP endpoints), `TokenValidator` (JWT validation)

**Component boundaries:**
- Is each component focused on one responsibility?
- Are there any components that should be split further?
- Are there existing components you can reuse?

#### 2. Component Interactions
**How will your components communicate?**
- Draw a simple flow: Component A → calls → Component B → updates → Database
- What data flows between components?
- What interfaces or contracts are needed?

**Interface design:**
- What methods will each component expose?
- What parameters and return types?
- What exceptions might be thrown?

#### 3. Technology Decisions
**What libraries or frameworks will you use?**
- List each with justification: "Jackson for JSON parsing (better Kotlin support than Gson)"
- Are there version constraints? "Must use Spring Boot 3.2+ for native compilation"
- Have you considered alternatives and trade-offs?

**Technology checklist:**
- [ ] All required dependencies identified
- [ ] Version compatibility verified
- [ ] License compatibility checked
- [ ] Performance implications understood

#### 4. Data Management
**What data structures and storage are needed?**
- Database tables: What columns, types, indexes?
- In-memory structures: What collections, caching strategies?
- Data transformations: What mappings between layers?

**Data flow:**
- How does data enter the system? (API, file, message queue)
- How is it transformed? (validation, mapping, enrichment)
- Where is it persisted? (database, cache, file system)
- How is it retrieved? (queries, filters, pagination)

#### 5. Error Handling Strategy
**What can go wrong and how will you handle it?**
- External service failures → Retry with backoff? Circuit breaker? Fallback?
- Invalid input → Validation at what layer? What error messages?
- Database errors → Transaction rollback? Logging? User notification?
- Unexpected exceptions → Global handler? Custom error pages?

**Error handling checklist:**
- [ ] All external failure modes identified
- [ ] Validation strategy defined
- [ ] Error logging approach determined
- [ ] User-facing error messages planned

#### 6. Testing Strategy
**How will you verify correctness?**
- Unit tests: What classes need tests? What edge cases?
- Integration tests: What interactions need verification?
- Manual testing: What scenarios to walk through?
- Test data: What fixtures or mocks needed?

**Testing checklist:**
- [ ] Critical path unit tests identified
- [ ] Integration test scenarios listed
- [ ] Test data requirements clear
- [ ] Acceptance criteria testable

#### 7. Risk Assessment
**What could block or delay this work?**
- Technical risks: "Database migration might fail on production data"
- Dependency risks: "External API has rate limits"
- Knowledge gaps: "Unfamiliar with WebSocket protocol"

**Mitigation strategies:**
- For each risk: What's your mitigation plan?
- Example: "Test migration on prod backup first" or "Add caching layer for API calls"
- What's your rollback plan if things go wrong?

#### Document Your Decisions

When you complete this planning:
- **Update task summary** (300-500 chars) with key decisions
- Example: "Implementing OAuth2 auth using Spring Security. Chose JWT over sessions for scalability. Created AuthService (token gen), AuthController (endpoints), UserRepository (data access). Risk: JWT secret rotation - mitigated with external config."

**Note:** This planning helps clarify your approach. Skills provide domain-specific implementation patterns (backend, frontend, database).""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("guidance", "checklist", "planning")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Technical Decision Log",
                usageDescription = "Framework for documenting key technical decisions and their rationale",
                contentSample = """### Technical Decision Log

Use this format to document significant technical decisions:

#### Decision Template

```
**Decision:** [What you decided]
**Context:** [Why this decision was needed]
**Alternatives Considered:** [What other options you evaluated]
**Chosen Approach:** [What you're implementing]
**Rationale:** [Why this is the best choice]
**Trade-offs:** [What you're giving up or accepting]
**Reversibility:** [How hard to change later: Easy/Moderate/Difficult]
```

#### Common Decision Categories

**Architecture Decisions:**
- Monolith vs Microservices → "Chose monolith for simpler deployment and debugging"
- Synchronous vs Asynchronous → "Async for background jobs, sync for user-facing APIs"
- RESTful vs GraphQL → "REST for simplicity, team familiarity"

**Technology Decisions:**
- Library selection → "Jackson vs Gson: Jackson has better Kotlin support"
- Database choice → "PostgreSQL vs MySQL: PostgreSQL for better JSON support"
- Framework choice → "Spring Boot vs Micronaut: Spring for ecosystem maturity"

**Design Pattern Decisions:**
- Repository pattern → "Abstracts data access, easier testing"
- Factory pattern → "Simplifies object creation with many variations"
- Observer pattern → "Decouples event producers from consumers"

**Data Management Decisions:**
- Caching strategy → "Redis for distributed caching, Caffeine for local"
- Database schema → "Normalized vs denormalized: Normalized for data integrity"
- Data migration → "Blue-green deployment with backward-compatible schema"

#### Example Decision

```
**Decision:** Use JWT tokens for authentication
**Context:** Need stateless authentication for horizontal scaling
**Alternatives Considered:**
- Session-based auth (requires sticky sessions or shared session store)
- OAuth2 with third-party provider (adds external dependency)
**Chosen Approach:** JWT tokens with RS256 signing
**Rationale:**
- Stateless: no server-side session storage
- Scalable: any server can verify token
- Standard: well-understood security model
**Trade-offs:**
- Token revocation is difficult (mitigated with short TTL + refresh tokens)
- Token size larger than session IDs (acceptable overhead)
**Reversibility:** Moderate - would need session store and code changes
```

**Documentation:**
- Add significant decisions to task summary (concise)
- Create this section only for complex tasks (complexity 7+)
- Focus on "why" not "what" (code shows what)""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("technical-details", "reference", "decision-log")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Integration Points Checklist",
                usageDescription = "Validation checklist for dependencies, interfaces, and integration concerns",
                contentSample = """### Integration Points Checklist

#### External Dependencies Validation

**For each external library or service:**
- [ ] **Purpose clear:** Why are you using it? What problem does it solve?
- [ ] **Version specified:** Exact version or range? Any known compatibility issues?
- [ ] **Fallback plan:** What happens if it fails or is unavailable?
- [ ] **Configuration:** What environment variables or config files needed?
- [ ] **Testing:** How will you test integration? Mock or real service?

**Common integration concerns:**
- API rate limits → "GitHub API: 5000/hour authenticated, cache responses"
- Authentication → "OAuth2 flow, need client ID/secret in env vars"
- Data format → "Expects JSON, provide examples for validation"
- Network errors → "Timeout after 30s, retry with exponential backoff"

#### Internal Dependencies Validation

**For each internal module or service:**
- [ ] **Interface clear:** What methods/APIs will you call?
- [ ] **Data contract:** What data format? Any validation requirements?
- [ ] **Error handling:** What exceptions might be thrown? How to handle?
- [ ] **Versioning:** Is the interface stable or might it change?
- [ ] **Testing:** Can you test in isolation with mocks?

**Integration patterns:**
- Direct method calls → "Inject dependency via constructor"
- Event-based → "Publish events via EventBus, handle asynchronously"
- Message queue → "Send messages to queue, consumer processes separately"
- REST API → "Make HTTP calls, handle timeouts and retries"

#### Database Integration

**Schema considerations:**
- [ ] **Tables identified:** What tables will you read/write?
- [ ] **Indexes needed:** What queries will you make? Are they indexed?
- [ ] **Constraints:** Foreign keys, unique constraints, check constraints?
- [ ] **Migration plan:** New tables? Column changes? Data migration?
- [ ] **Rollback strategy:** Can you rollback schema changes safely?

**Transaction boundaries:**
- What operations must be atomic?
- Where do transactions start and commit?
- What happens on rollback?

#### Configuration Dependencies

**Environment-specific settings:**
- [ ] **Local development:** What defaults for dev environment?
- [ ] **Testing:** Test-specific overrides needed?
- [ ] **Production:** Production values documented? Secrets managed?

**Configuration checklist:**
- [ ] All config keys documented
- [ ] Sensible defaults for development
- [ ] Validation for required values
- [ ] Secrets not hardcoded

#### API Contracts

**If creating new APIs:**
- [ ] **Endpoint paths:** RESTful? Versioned?
- [ ] **Request format:** JSON? Form data? Query params?
- [ ] **Response format:** JSON structure defined? Status codes?
- [ ] **Error responses:** Consistent error format? Error codes?
- [ ] **Authentication:** How is API secured? Token-based? OAuth?

**API design checklist:**
- [ ] Follows project conventions
- [ ] Backward compatible if modifying existing
- [ ] Documented with examples
- [ ] Versioned appropriately

#### Validation Strategy

**Before implementation:**
- [ ] All integration points identified
- [ ] Dependencies documented
- [ ] Failure modes considered
- [ ] Testing approach defined

**During implementation:**
- [ ] Test each integration point
- [ ] Verify error handling
- [ ] Check performance/timeouts
- [ ] Document any surprises

**Example checklist completion:**
```
✓ External: GitHub API - rate limited, caching strategy defined
✓ Internal: UserService - interface stable, constructor injection
✓ Database: Users table indexed on email, migration V5 created
✓ Config: GitHub token in env, validation on startup
✓ API: POST /api/sync-repos, returns JSON, OAuth2 protected
```

**Note:** This checklist ensures you've thought through all integration concerns before implementation.""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = false,
                tags = listOf("checklist", "guidance", "integration", "validation")
            )
        )

        return Pair(template, sections)
    }
}
