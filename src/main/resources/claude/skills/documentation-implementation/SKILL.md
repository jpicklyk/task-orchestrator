---
name: Documentation Implementation
description: Technical documentation, API references, user guides, maintaining documentation quality. Use for documentation, docs, user-docs, api-docs, guide, readme tags. Provides documentation patterns, validation, clarity standards.
allowed-tools: Read, Write, Edit, Grep, Glob
---

# Documentation Implementation Skill

Domain-specific guidance for creating clear, comprehensive technical documentation.

## When To Use This Skill

Load this Skill when task has tags:
- `documentation`, `docs`, `user-docs`, `api-docs`
- `guide`, `readme`, `tutorial`, `reference`

## Validation Commands

### Check Documentation
```bash
# Markdown linting
npx markdownlint **/*.md

# Spell check
npx cspell "**/*.md"

# Link checking
npx markdown-link-check docs/**/*.md

# Build documentation site (if applicable)
npm run docs:build
mkdocs build
```

### Preview Documentation
```bash
# Live preview
npm run docs:serve
mkdocs serve

# Static site preview
python -m http.server 8000 -d docs/
```

## Success Criteria (Before Completing Task)

✅ **Documentation is accurate** (reflects actual behavior)
✅ **Documentation is complete** (all required sections present)
✅ **Examples work** (code examples run without errors)
✅ **Links are valid** (no broken links)
✅ **Spelling and grammar correct**
✅ **Follows project style guide**

## Common Documentation Tasks

### API Documentation
- Endpoint descriptions (path, method)
- Request parameters (required, optional, types)
- Response schemas (success, error)
- Status codes (200, 400, 401, 404, 500)
- Example requests/responses
- Authentication requirements

### User Guides
- Step-by-step instructions
- Screenshots or diagrams
- Prerequisites
- Troubleshooting section
- FAQs

### README Files
- Project overview
- Installation instructions
- Quick start guide
- Configuration options
- Contributing guidelines

### Code Documentation
- Function/method descriptions
- Parameter documentation
- Return value documentation
- Usage examples
- Edge cases and gotchas

## Documentation Patterns

### API Endpoint Documentation

```markdown
## POST /api/users

Creates a new user account.

**Authentication:** Required (Bearer token)

**Request Body:**
```json
{
  "email": "user@example.com",    // Required, must be valid email
  "password": "secure123",        // Required, min 8 characters
  "name": "John Doe"              // Required
}
```

**Success Response (201 Created):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "name": "John Doe",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

**Error Responses:**

- **400 Bad Request** - Invalid input
  ```json
  {
    "error": "Invalid email format",
    "code": "VALIDATION_ERROR"
  }
  ```

- **409 Conflict** - Email already exists
  ```json
  {
    "error": "Email already registered",
    "code": "DUPLICATE_EMAIL"
  }
  ```

**Example Request:**
```bash
curl -X POST https://api.example.com/api/users \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "secure123",
    "name": "John Doe"
  }'
```
```

### User Guide Pattern

```markdown
# Setting Up User Authentication

This guide walks you through enabling user authentication in your application.

## Prerequisites

- Node.js 18+ installed
- Database configured
- Admin access to the application

## Step 1: Install Required Packages

```bash
npm install bcrypt jsonwebtoken express-session
```

## Step 2: Configure Environment Variables

Create a `.env` file in your project root:

```env
JWT_SECRET=your-secret-key-here
SESSION_TIMEOUT=3600
```

## Step 3: Enable Authentication

Edit `config/app.js` and add:

```javascript
const authMiddleware = require('./middleware/auth');
app.use(authMiddleware);
```

## Step 4: Test Authentication

1. Start your application: `npm start`
2. Navigate to http://localhost:3000/login
3. Use test credentials:
   - Email: test@example.com
   - Password: test123
4. You should be redirected to the dashboard

## Troubleshooting

**Problem:** Login fails with "Invalid credentials"
**Solution:** Check that you've run database migrations: `npm run migrate`

**Problem:** Session expires immediately
**Solution:** Verify JWT_SECRET is set in `.env` file
```

### Code Documentation Pattern

```kotlin
/**
 * Creates a new user account with the provided information.
 *
 * This function validates the user data, hashes the password using bcrypt,
 * and persists the user to the database. Email uniqueness is enforced at
 * the database level.
 *
 * @param email User's email address (must be valid format and unique)
 * @param password Plain text password (min 8 characters, will be hashed)
 * @param name User's full name
 * @return Created user with generated ID and timestamps
 * @throws ValidationException if email format is invalid or password too short
 * @throws DuplicateEmailException if email already registered
 * @throws DatabaseException if database operation fails
 *
 * @example
 * ```kotlin
 * val user = userService.createUser(
 *     email = "john@example.com",
 *     password = "secure123",
 *     name = "John Doe"
 * )
 * println(user.id)  // UUID generated by database
 * ```
 *
 * @see User
 * @see validateEmail
 * @see hashPassword
 */
fun createUser(email: String, password: String, name: String): User {
    // Implementation
}
```

## Common Blocker Scenarios

### Blocker 1: Implementation Incomplete

**Issue:** Cannot document features that don't exist yet

**What to try:**
- Check if implementation task is truly complete
- Test the feature manually
- Check API responses match requirements

**If blocked:** Report to orchestrator - implementation incomplete or requirements unclear

### Blocker 2: API Behavior Unclear

**Issue:** Don't know what endpoint does, what parameters mean, or what responses look like

**What to try:**
- Test API endpoints manually (Postman, curl)
- Read implementation code
- Check for existing API specs (OpenAPI, GraphQL schema)
- Check task requirements

**If blocked:** Report to orchestrator - need clarification on API behavior

### Blocker 3: Missing Design Assets

**Issue:** User guide needs screenshots but UI not implemented or accessible

**What to try:**
- Use placeholder images with captions
- Describe steps verbally without screenshots
- Check if mockups/designs available

**If blocked:** Report to orchestrator - need access to UI or design assets

### Blocker 4: Contradictory Information

**Issue:** Code does X, requirements say Y, existing docs say Z

**What to try:**
- Test actual behavior
- Document what code actually does
- Note discrepancy in comments

**If blocked:** Report to orchestrator - need authoritative answer on correct behavior

### Blocker 5: Technical Details Missing

**Issue:** Don't know how something works internally to document it

**What to try:**
- Read implementation code
- Ask implementation engineer (check task history)
- Document what's observable from outside

**If blocked:** Report to orchestrator - need technical details from implementer

## Blocker Report Format

```
⚠️ BLOCKED - Requires Senior Engineer

Issue: [Specific problem - implementation incomplete, unclear behavior, etc.]

Attempted Research:
- [What sources you checked]
- [What you tried to find out]
- [Why it didn't work]

Blocked By: [Task ID / incomplete implementation / unclear requirements]

Partial Progress: [What documentation you DID complete]

Requires: [What needs to happen to unblock documentation]
```

## Documentation Quality Checklist

### Clarity
✅ Uses simple, clear language
✅ Defines technical terms on first use
✅ Short sentences (< 25 words)
✅ Active voice ("Click the button" not "The button should be clicked")
✅ Consistent terminology (don't switch between "user" and "account")

### Completeness
✅ All required sections present
✅ All parameters documented
✅ All status codes explained
✅ Edge cases covered
✅ Examples provided
✅ Troubleshooting section included

### Accuracy
✅ Code examples run without errors
✅ Screenshots match current UI
✅ API responses match actual responses
✅ Links work (no 404s)
✅ Version numbers correct

### Formatting
✅ Consistent heading levels
✅ Code blocks have language specified
✅ Lists use consistent bullet style
✅ Tables formatted correctly
✅ Proper markdown syntax

## Writing Style Guidelines

### Use Active Voice

❌ **Passive:** "The user object is returned by the API"
✅ **Active:** "The API returns the user object"

### Be Specific

❌ **Vague:** "Call the endpoint with the data"
✅ **Specific:** "Send a POST request to /api/users with email, password, and name"

### Show, Don't Just Tell

❌ **Abstract:** "Configure the authentication settings"
✅ **Concrete:**
```
Edit config/auth.js and set:
```javascript
module.exports = {
  jwtSecret: 'your-secret-here',
  tokenExpiry: '24h'
};
```
```

### Include Examples

Every documented feature should have:
- Code example showing usage
- Expected output
- Common use cases

### Anticipate Questions

After each instruction, ask:
- What could go wrong here?
- What might be unclear?
- What would I wonder about?

Add troubleshooting for those questions.

## Common Patterns to Follow

1. **Start with overview** - what it is, why it matters
2. **Prerequisites first** - what user needs before starting
3. **Step-by-step instructions** - numbered, one action per step
4. **Examples that work** - test all code examples
5. **Troubleshooting section** - common problems and solutions
6. **Clear formatting** - headings, code blocks, lists
7. **Links to related docs** - help users find more information

## What NOT to Do

❌ Don't use jargon without explaining it
❌ Don't assume prior knowledge
❌ Don't skip error cases
❌ Don't provide untested code examples
❌ Don't use vague terms ("simply", "just", "obviously")
❌ Don't forget to update docs when code changes
❌ Don't document implementation details users don't need

## Focus Areas

When reading task sections, prioritize:
- `requirements` - What needs documenting
- `context` - Purpose and audience
- `documentation` - Existing docs to update
- `implementation` - How it actually works

## Remember

- **Accuracy is critical** - test everything you document
- **Clarity over cleverness** - simple language wins
- **Examples are essential** - every feature needs working examples
- **Update, don't duplicate** - check if docs already exist
- **Test your instructions** - follow them yourself
- **Report blockers promptly** - missing information, incomplete features
- **Users read docs when stuck** - be helpful and thorough

## Additional Resources

For deeper patterns and examples, see:
- **PATTERNS.md** - Advanced documentation patterns, style guides (load if needed)
- **BLOCKERS.md** - Detailed documentation-specific blockers (load if stuck)
- **examples.md** - Complete documentation examples (load if uncertain)
