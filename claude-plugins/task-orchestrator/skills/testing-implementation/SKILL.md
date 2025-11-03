---
name: Testing Implementation
description: Comprehensive testing strategies, test automation, quality assurance with JUnit, MockK, Jest. Use for testing, test, qa, quality, coverage tags. Provides test patterns, validation commands, coverage targets.
allowed-tools: Read, Write, Edit, Bash, Grep, Glob
---

# Testing Implementation Skill

Domain-specific guidance for writing comprehensive tests, test automation, and quality assurance.

## When To Use This Skill

Load this Skill when task has tags:
- `testing`, `test`, `qa`, `quality`, `coverage`
- `unit-test`, `integration-test`, `e2e`, `jest`, `junit`

## Validation Commands

### Run Tests
```bash
# Gradle (Kotlin/Java)
./gradlew test
./gradlew test --tests "*UserServiceTest*"
./gradlew test --tests "UserServiceTest.shouldCreateUser"

# NPM (JavaScript/TypeScript)
npm test
npm test -- --coverage
npm test -- UserService.test.ts
npm test -- -t "should create user"

# Python
pytest
pytest tests/test_user_service.py
pytest tests/test_user_service.py::test_create_user
pytest --cov=src tests/
```

### Check Coverage
```bash
# Gradle
./gradlew test jacocoTestReport
# Report: build/reports/jacoco/test/html/index.html

# NPM
npm test -- --coverage
# Report: coverage/lcov-report/index.html

# Python
pytest --cov=src --cov-report=html tests/
# Report: htmlcov/index.html
```

## Success Criteria (Before Completing Task)

✅ **ALL tests MUST pass** (0 failures)
✅ **Coverage goals met** (specified in task, typically 80%+)
✅ **No flaky tests** (run multiple times to verify)
✅ **Test execution time acceptable** (< 5min for unit tests)
✅ **All edge cases covered**

## Common Testing Tasks

### Unit Tests
- Test individual functions/methods in isolation
- Mock external dependencies
- Focus on business logic
- Fast execution (milliseconds)

### Integration Tests
- Test components working together
- Real database (in-memory)
- Real services
- Test actual integration points

### E2E Tests
- Test full user workflows
- Simulated user interactions
- Real or simulated backend
- Validates end-to-end functionality

### Security Tests
- SQL injection attempts
- XSS attacks
- CSRF protection
- Authentication/authorization

### Performance Tests
- Response time under load
- Concurrent request handling
- Memory usage
- Query performance

## Testing Principles

### Test Types and When to Use

**Unit Tests (70% of tests):**
- Pure functions with no side effects ✅
- Business logic calculations ✅
- Validation logic ✅
- Data transformations ✅
- Mock external dependencies

**Integration Tests (20% of tests):**
- API endpoints end-to-end ✅
- Database operations ✅
- Service layer with repositories ✅
- Real infrastructure (in-memory DB)

**E2E Tests (10% of tests):**
- Critical user workflows ✅
- Authentication flows ✅
- Checkout/payment processes ✅
- Slower, more fragile

### Arrange-Act-Assert Pattern

```kotlin
@Test
fun `should calculate total with tax`() {
    // Arrange - Set up test data
    val items = listOf(
        Item(price = 10.0),
        Item(price = 20.0)
    )
    val taxRate = 0.1

    // Act - Execute the function being tested
    val total = calculateTotal(items, taxRate)

    // Assert - Verify the result
    assertEquals(33.0, total)
}
```

### Test Edge Cases

```kotlin
@Test
fun `should handle empty list`() {
    val result = calculateTotal(emptyList(), 0.1)
    assertEquals(0.0, result)
}

@Test
fun `should handle zero tax rate`() {
    val items = listOf(Item(price = 10.0))
    val result = calculateTotal(items, 0.0)
    assertEquals(10.0, result)
}

@Test
fun `should handle negative prices`() {
    val items = listOf(Item(price = -10.0))
    assertThrows<IllegalArgumentException> {
        calculateTotal(items, 0.1)
    }
}
```

### Test Error Conditions

```kotlin
@Test
fun `should throw when user not found`() {
    val nonExistentId = UUID.randomUUID()

    assertThrows<NotFoundException> {
        userService.getUserById(nonExistentId)
    }
}

@Test
fun `should return error response for invalid email`() {
    val response = api.createUser(email = "invalid-email")

    assertEquals(400, response.statusCode)
    assertTrue(response.body.contains("Invalid email"))
}
```

## Common Blocker Scenarios

### Blocker 1: Implementation Has Bugs

**Issue:** Tests fail because code being tested has bugs

**What to try:**
- Debug the implementation code
- Add logging to understand behavior
- Simplify test to isolate issue
- Check if bug is in test or implementation

**If blocked:** Report to orchestrator - implementation needs fixing by Senior Engineer

### Blocker 2: Missing Test Infrastructure

**Issue:** No test database, mock servers, or fixtures available

**What to try:**
- Check for existing test setup in codebase
- Look for test configuration files
- Check documentation for test setup
- Use H2 in-memory database for SQLite/PostgreSQL

**If blocked:** Report to orchestrator - test infrastructure needs provisioning

### Blocker 3: Flaky Existing Tests

**Issue:** Existing tests fail randomly, making new test validation impossible

**What to try:**
- Isolate new tests in separate test class
- Run only new tests: `./gradlew test --tests "NewTestClass"`
- Document flaky test issue

**If blocked:** Report to orchestrator - flaky tests need fixing first

### Blocker 4: Unclear Test Requirements

**Issue:** Don't know what behavior to test or what's expected

**What to try:**
- Review requirements section in task
- Check acceptance criteria
- Look at existing similar tests
- Check API documentation

**If blocked:** Report to orchestrator - need clarification on expected behavior

### Blocker 5: Can't Reproduce Bug

**Issue:** Bug report unclear, can't write test that reproduces issue

**What to try:**
- Follow reproduction steps exactly
- Check environment differences
- Add logging to understand actual behavior
- Test in different configurations

**If blocked:** Report to orchestrator - need clearer reproduction steps

## Blocker Report Format

```
⚠️ BLOCKED - Requires Senior Engineer

Issue: [Specific problem - implementation bug, missing fixtures, unclear requirements]

Attempted Fixes:
- [What you tried #1]
- [What you tried #2]
- [Why attempts didn't work]

Root Cause (if known): [Your analysis]

Partial Progress: [What tests you DID complete]

Context for Senior Engineer:
- Test output: [Test failures]
- Code being tested: [File and method]
- Test code: [Your test code]

Requires: [What needs to happen]
```

## Test Patterns

### Mock External Dependencies (Unit Tests)

```kotlin
@Test
fun `should fetch user from API`() {
    // Arrange - Mock external API
    val mockApi = mockk<UserApi>()
    every { mockApi.getUser(any()) } returns User(id = "123", name = "John")

    val service = UserService(mockApi)

    // Act
    val user = service.getUserById("123")

    // Assert
    assertEquals("John", user.name)
    verify { mockApi.getUser("123") }
}
```

### Use Real Database (Integration Tests)

```kotlin
@SpringBootTest
@Transactional  // Auto-rollback after each test
class UserRepositoryTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    fun `should save and retrieve user`() {
        // Arrange
        val user = User(email = "test@example.com", name = "Test")

        // Act
        val saved = userRepository.save(user)
        val retrieved = userRepository.findById(saved.id)

        // Assert
        assertNotNull(retrieved)
        assertEquals("test@example.com", retrieved?.email)
    }
}
```

### Test Async Operations

```typescript
test('should fetch data asynchronously', async () => {
  // Arrange
  const api = new UserApi();

  // Act
  const user = await api.getUser('123');

  // Assert
  expect(user.name).toBe('John');
});
```

### Test Error Handling

```kotlin
@Test
fun `should handle network error gracefully`() {
    // Arrange - Mock to throw exception
    val mockApi = mockk<UserApi>()
    every { mockApi.getUser(any()) } throws NetworkException("Connection failed")

    val service = UserService(mockApi)

    // Act & Assert
    assertThrows<ServiceException> {
        service.getUserById("123")
    }
}
```

## Coverage Targets

**Good Coverage:**
- Business logic: 90%+
- Service layer: 85%+
- Controllers/APIs: 80%+
- Utilities: 90%+

**Lower Coverage OK:**
- Configuration classes: 50%
- DTOs/Entities: 30%
- Main/startup code: Varies

**Focus on:**
- Critical paths (authentication, payment)
- Complex business logic
- Edge cases and error handling

## What to Test vs What to Skip

### ✅ DO Test

- Business logic and calculations
- API request/response handling
- Database operations
- Error handling
- Edge cases (null, empty, invalid input)
- Security (injection, XSS, auth)
- State transitions
- Conditional logic

### ❌ DON'T Test

- Third-party library internals
- Framework code (Spring, React)
- Getters/setters with no logic
- Private methods (test via public interface)
- Configuration files (unless logic)

## Common Patterns to Follow

1. **Arrange-Act-Assert** structure
2. **One assertion per test** (or closely related assertions)
3. **Descriptive test names** (`shouldCreateUserWhenValidData`)
4. **Independent tests** (no shared state between tests)
5. **Fast unit tests** (< 1 second each)
6. **Test edge cases** (null, empty, boundary values)
7. **Clean up after tests** (transactions, file deletion)

## What NOT to Do

❌ Don't skip edge case testing
❌ Don't write tests that depend on order
❌ Don't test implementation details
❌ Don't create flaky tests (timing-dependent)
❌ Don't mock everything (use real infrastructure where appropriate)
❌ Don't skip test cleanup (database, files)
❌ Don't mark task complete with failing tests

## Focus Areas

When reading task sections, prioritize:
- `requirements` - What needs testing
- `testing-strategy` - Test approach
- `acceptance-criteria` - Success conditions
- `implementation` - Code to test

## Remember

- **Test edge cases** - null, empty, invalid, boundary values
- **Use real infrastructure** - in-memory database for integration tests
- **Fast feedback** - run tests incrementally during development
- **Clear test names** - describe what is being tested
- **Independent tests** - no shared state
- **Report blockers promptly** - implementation bugs, missing infrastructure
- **Coverage goals matter** - aim for 80%+ on business logic
- **Validation is mandatory** - ALL tests must pass before completion

## Additional Resources

For deeper patterns and examples, see:
- **PATTERNS.md** - Advanced testing patterns, mocking strategies (load if needed)
- **BLOCKERS.md** - Detailed testing-specific blockers (load if stuck)
- **examples.md** - Complete test examples (load if uncertain)
