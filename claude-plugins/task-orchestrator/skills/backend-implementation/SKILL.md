---
name: Backend Implementation
description: Backend development with Kotlin, Spring Boot, REST APIs. Use for backend, api, service, kotlin, rest tags. Provides validation commands, testing patterns, and blocker scenarios.
allowed-tools: Read, Write, Edit, Bash, Grep, Glob
---

# Backend Implementation Skill

Domain-specific guidance for backend API development, service implementation, and business logic.

## When To Use This Skill

Load this Skill when task has tags:
- `backend`, `api`, `service`, `kotlin`, `rest`
- `spring`, `spring-boot`, `controller`, `repository`

## Validation Commands

### Run Tests
```bash
# Full test suite
./gradlew test

# Specific test class
./gradlew test --tests "UserServiceTest"

# Single test method
./gradlew test --tests "UserServiceTest.shouldCreateUser"

# With build
./gradlew clean test
```

### Build Project
```bash
# Build JAR
./gradlew build

# Build without tests (for quick syntax check)
./gradlew build -x test
```

### Run Application
```bash
# Local development
./gradlew bootRun

# With specific profile
./gradlew bootRun --args='--spring.profiles.active=dev'
```

## Success Criteria (Before Completing Task)

✅ **ALL tests MUST pass** (0 failures, 0 errors)
✅ **Build MUST succeed** without compilation errors
✅ **Code follows project conventions** (existing patterns)
✅ **API endpoints tested** (integration tests)
✅ **Error handling implemented** (try-catch, validation)

## Common Backend Tasks

### REST API Endpoints
- Controller with request mapping
- Request/response DTOs
- Service layer business logic
- Repository integration
- Error handling (400, 401, 404, 500)
- Validation (@Valid annotations)

### Service Implementation
- Business logic in service classes
- Transaction management (@Transactional)
- Error handling and exceptions
- Dependency injection (@Autowired, constructor injection)

### Database Integration
- Repository interfaces (JPA, Exposed ORM)
- Entity mapping
- Query methods
- Transaction boundaries

## Testing Principles for Backend

### Use Real Infrastructure for Integration Tests

❌ **AVOID mocking repositories in integration tests:**
```kotlin
// BAD - Mocking repositories misses SQL errors, constraints
@Mock private lateinit var userRepository: UserRepository
when(userRepository.findById(any())).thenReturn(mockUser)
```

✅ **USE real in-memory database:**
```kotlin
// GOOD - Tests actual integration
@SpringBootTest
@Transactional  // Auto-rollback after each test
class UserApiTest {
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var userService: UserService
    // Tests real database, serialization, constraints
}
```

### Test Incrementally, Not in Batches

❌ **Avoid:** Write 200 lines code + 15 tests → run all → 12 failures → no idea which code caused which failure

✅ **Do:**
1. Write basic implementation
2. Write ONE happy path test
3. Run ONLY that test: `./gradlew test --tests "ToolTest.shouldHandleBasicCase"`
4. Fix until passes
5. Add ONE edge case test
6. Run ONLY that test
7. Repeat

**Benefits:** Feedback in seconds, isolates root cause immediately.

### Debug with Actual Output

When test fails:
1. **Read error message carefully** - tells you what's wrong
2. **Print actual output:**
   ```kotlin
   println("Full response: $result")
   println("Response keys: ${result.jsonObject.keys}")
   ```
3. **Verify assumptions about test data** - count manually
4. **Fix root cause, not symptoms**

### Create Complete Test Entities

❌ **BAD - Missing required fields:**
```kotlin
val task = Task(
    id = UUID.randomUUID(),
    title = "Test Task",
    status = TaskStatus.PENDING
    // Missing: summary, priority, complexity, timestamps
)
taskRepository.create(task)  // FAILS: NOT NULL constraint
```

✅ **GOOD - Complete entity:**
```kotlin
val task = Task(
    id = UUID.randomUUID(),
    title = "Test Task",
    summary = "Test summary",               // Required
    status = TaskStatus.PENDING,            // Required
    priority = Priority.HIGH,               // Required
    complexity = 5,                         // Required
    tags = listOf("test"),
    projectId = testProjectId,
    createdAt = Instant.now(),              // Required
    modifiedAt = Instant.now()              // Required
)
```

**How to find required fields:** Check migration SQL or ORM model definition.

## Common Blocker Scenarios

### Blocker 1: Missing Database Schema

**Issue:** Tests expect column that doesn't exist
```
SQLSyntaxErrorException: Unknown column 'users.password_hash'
```

**What to try:**
- Check migration files - is column defined?
- Review prerequisite database tasks - marked complete but incomplete?
- Check if column was renamed

**If blocked:** Report to orchestrator - database task may need reopening

### Blocker 2: NullPointerException in Service

**Issue:** NPE at runtime in service class
```
NullPointerException: Cannot invoke method on null object
```

**What to try:**
- Check dependency injection - is @Autowired present?
- Check constructor injection - all parameters provided?
- Check @Configuration on config class
- Check @Service or @Component on service class
- Add null safety (Kotlin: use `?` operator, nullable types)

**Common causes:**
- Missing @Configuration annotation
- Spring not scanning package
- Circular dependency

### Blocker 3: Integration Test Failures

**Issue:** Integration tests pass locally but fail in CI or for others

**What to try:**
- Check test isolation - are tests cleaning up state?
- Check @Transactional with rollback
- Check test order dependencies (tests should be independent)
- Check H2/in-memory DB configuration matches production DB type
- Check test data initialization

### Blocker 4: Architectural Conflict

**Issue:** Task requirements conflict with existing architecture
```
Task requires middleware auth but project uses annotation-based security
```

**What to try:**
- Review existing patterns in codebase
- Check architecture documentation
- Look for similar implementations

**If blocked:** Report to orchestrator - may need architectural decision or task revision

### Blocker 5: External Dependency Bug

**Issue:** Third-party library has known bug
```
JWT library v3.2.1 has refresh token bug - expires immediately
```

**What to try:**
- Check library changelog - is fix available in newer version?
- Search for known issues in library's issue tracker
- Try workaround if documented

**If blocked:** Report to orchestrator - may need to wait for library update or use alternative

## Blocker Report Format

```
⚠️ BLOCKED - Requires Senior Engineer

Issue: [Specific problem - NPE at UserService.kt:42, missing column, etc.]

Attempted Fixes:
- [What you tried #1]
- [What you tried #2]
- [Why attempts didn't work]

Root Cause (if known): [Your analysis]

Partial Progress: [What work you DID complete]

Context for Senior Engineer:
- Error output: [Paste error]
- Test results: [Test failures]
- Related files: [Files involved]

Requires: [What needs to happen - Senior Engineer investigation, etc.]
```

## Quick Reference

### Spring Boot Patterns

**Controller:**
```kotlin
@RestController
@RequestMapping("/api/users")
class UserController(private val userService: UserService) {

    @PostMapping
    fun createUser(@Valid @RequestBody request: CreateUserRequest): User {
        return userService.createUser(request)
    }

    @GetMapping("/{id}")
    fun getUser(@PathVariable id: UUID): User {
        return userService.findById(id)
            ?: throw NotFoundException("User not found")
    }
}
```

**Service:**
```kotlin
@Service
@Transactional
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {
    fun createUser(request: CreateUserRequest): User {
        val user = User(
            email = request.email,
            passwordHash = passwordEncoder.encode(request.password)
        )
        return userRepository.save(user)
    }
}
```

**Repository:**
```kotlin
@Repository
interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?
}
```

### Error Handling

```kotlin
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(ex.message))
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(ex: ValidationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(ex.message))
    }
}
```

## Common Patterns to Follow

1. **Controller → Service → Repository** layering
2. **Constructor injection** over field injection
3. **@Transactional on service layer** for database operations
4. **DTO pattern** for request/response (don't expose entities)
5. **Exception handling** with @RestControllerAdvice
6. **Validation** with @Valid and constraint annotations
7. **Testing with real database** for integration tests

## What NOT to Do

❌ Don't mock repositories in integration tests
❌ Don't skip tests and mark task complete
❌ Don't expose entities directly in API responses
❌ Don't put business logic in controllers
❌ Don't forget @Transactional for database operations
❌ Don't hardcode configuration (use application.yml)

## Focus Areas

When reading task sections, prioritize:
- `requirements` - What API endpoints need to be built
- `technical-approach` - How to implement (patterns, libraries)
- `implementation` - Specific implementation details
- `testing-strategy` - How to test the implementation

## Remember

- **Run tests incrementally** - one test at a time for fast feedback
- **Use real infrastructure** - in-memory database for integration tests
- **Debug with actual output** - print what you got, don't assume
- **Report blockers promptly** - don't wait, communicate to orchestrator
- **Follow existing patterns** - check codebase for similar implementations
- **Complete test entities** - all required fields must be populated
- **Validation is mandatory** - ALL tests must pass before completion

## Additional Resources

For deeper patterns and examples, see:
- **PATTERNS.md** - Spring Security, REST API design patterns (load if needed)
- **BLOCKERS.md** - Detailed blocker scenarios with solutions (load if stuck)
- **examples.md** - Complete working examples (load if uncertain)
