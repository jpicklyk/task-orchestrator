---
name: Frontend Implementation
description: Frontend development with React, Vue, Angular, modern web technologies. Use for frontend, ui, react, vue, angular, web, component tags. Provides validation commands, component patterns, accessibility guidance.
allowed-tools: Read, Write, Edit, Bash, Grep, Glob
---

# Frontend Implementation Skill

Domain-specific guidance for frontend UI development, component implementation, and user interactions.

## When To Use This Skill

Load this Skill when task has tags:
- `frontend`, `ui`, `react`, `vue`, `angular`, `web`
- `component`, `jsx`, `tsx`, `styling`, `responsive`

## Validation Commands

### Run Tests
```bash
# Full test suite
npm test

# With coverage
npm test -- --coverage

# Watch mode
npm test -- --watch

# Specific test file
npm test -- UserProfile.test.tsx

# Specific test pattern
npm test -- -t "should render profile"
```

### Build Project
```bash
# Production build
npm run build

# Development build
npm run build:dev

# Type checking (TypeScript)
npm run type-check

# Linting
npm run lint
```

### Run Application
```bash
# Development server
npm start

# With specific port
PORT=3001 npm start
```

## Success Criteria (Before Completing Task)

✅ **ALL tests MUST pass** (0 failures)
✅ **Build MUST succeed** without errors
✅ **No TypeScript/linting errors**
✅ **Component renders without errors**
✅ **Responsive design works** (mobile, tablet, desktop)
✅ **Accessibility standards met** (ARIA labels, keyboard navigation)

## Common Frontend Tasks

### Component Development
- Create functional components (React hooks, Vue composition API)
- Props and state management
- Event handling
- Conditional rendering
- List rendering with keys

### Styling
- CSS modules or styled-components
- Responsive design (media queries)
- Mobile-first approach
- Consistent with design system

### Forms and Validation
- Form state management (Formik, React Hook Form)
- Input validation (client-side)
- Error display
- Submit handling

### API Integration
- Fetch data with useEffect/axios
- Loading states
- Error handling
- Data transformation

## Testing Principles for Frontend

### Component Testing (Preferred)

✅ **Test user interactions:**
```tsx
test('submits form with valid data', () => {
  render(<LoginForm onSubmit={mockSubmit} />)

  fireEvent.change(screen.getByLabelText('Email'), {
    target: { value: 'user@example.com' }
  })
  fireEvent.change(screen.getByLabelText('Password'), {
    target: { value: 'password123' }
  })
  fireEvent.click(screen.getByText('Login'))

  expect(mockSubmit).toHaveBeenCalledWith({
    email: 'user@example.com',
    password: 'password123'
  })
})
```

### What to Test

✅ **DO test:**
- Component renders without errors
- Correct content displays
- User interactions work (clicks, inputs)
- Conditional rendering logic
- Form validation
- Error states
- Accessibility (ARIA attributes, keyboard navigation)

❌ **DON'T test:**
- Implementation details (state variable names)
- Third-party library internals
- Styling specifics (unless critical)

### Test User-Facing Behavior

```tsx
// ✅ GOOD - Tests what user sees
expect(screen.getByText('Welcome, John')).toBeInTheDocument()
expect(screen.getByRole('button', { name: 'Submit' })).toBeEnabled()

// ❌ BAD - Tests implementation details
expect(component.state.username).toBe('John')
expect(mockFunction).toHaveBeenCalledTimes(1)
```

## Common Blocker Scenarios

### Blocker 1: API Not Ready

**Issue:** Frontend needs API endpoint that doesn't exist yet

**What to try:**
- Check if backend task is marked complete
- Mock API responses for development
- Create mock data file

**If blocked:** Report to orchestrator - backend task may be incomplete

### Blocker 2: Design Assets Missing

**Issue:** Need icons, images, colors not provided

**What to try:**
- Check design system documentation
- Use placeholder assets temporarily
- Check with design team

**If blocked:** Report to orchestrator - need design assets or specifications

### Blocker 3: TypeScript Type Errors

**Issue:** Complex types from API don't match frontend expectations

**What to try:**
- Check API response format (console.log actual response)
- Generate types from API schema (OpenAPI, GraphQL)
- Use `unknown` type and validate at runtime

**Common causes:**
- API changed but types not updated
- Optional fields not marked with `?`
- Nested objects not properly typed

### Blocker 4: Test Environment Issues

**Issue:** Tests fail in CI but pass locally

**What to try:**
- Check Node version consistency
- Check test environment variables
- Check for timing issues (add waitFor)
- Check for browser-specific APIs used without polyfills

### Blocker 5: Responsive Design Conflicts

**Issue:** Component works on desktop but breaks on mobile

**What to try:**
- Test in browser dev tools mobile view
- Check media queries
- Check for fixed widths vs responsive units
- Check for overflow issues

## Blocker Report Format

```
⚠️ BLOCKED - Requires Senior Engineer

Issue: [Specific problem - API endpoint 404, missing design specs, etc.]

Attempted Fixes:
- [What you tried #1]
- [What you tried #2]
- [Why attempts didn't work]

Root Cause (if known): [Your analysis]

Partial Progress: [What work you DID complete]

Context for Senior Engineer:
- Error output: [Console errors, network errors]
- Screenshots: [If visual issue]
- Related files: [Files involved]

Requires: [What needs to happen]
```

## Quick Reference

### React Functional Component

```tsx
import React, { useState, useEffect } from 'react';

interface UserProfileProps {
  userId: string;
  onUpdate?: (user: User) => void;
}

export const UserProfile: React.FC<UserProfileProps> = ({ userId, onUpdate }) => {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`/api/users/${userId}`)
      .then(res => res.json())
      .then(data => {
        setUser(data);
        setLoading(false);
      })
      .catch(err => {
        setError(err.message);
        setLoading(false);
      });
  }, [userId]);

  if (loading) return <div>Loading...</div>;
  if (error) return <div>Error: {error}</div>;
  if (!user) return <div>User not found</div>;

  return (
    <div className="user-profile">
      <h2>{user.name}</h2>
      <p>{user.email}</p>
    </div>
  );
};
```

### Form with Validation

```tsx
import { useState } from 'react';

export const LoginForm = ({ onSubmit }) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState({});

  const validate = () => {
    const newErrors = {};
    if (!email) newErrors.email = 'Email required';
    if (!email.includes('@')) newErrors.email = 'Invalid email';
    if (!password) newErrors.password = 'Password required';
    if (password.length < 8) newErrors.password = 'Min 8 characters';
    return newErrors;
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    const newErrors = validate();
    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }
    onSubmit({ email, password });
  };

  return (
    <form onSubmit={handleSubmit}>
      <div>
        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          aria-invalid={!!errors.email}
          aria-describedby={errors.email ? "email-error" : undefined}
        />
        {errors.email && <span id="email-error" role="alert">{errors.email}</span>}
      </div>
      <div>
        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          aria-invalid={!!errors.password}
        />
        {errors.password && <span role="alert">{errors.password}</span>}
      </div>
      <button type="submit">Login</button>
    </form>
  );
};
```

## Accessibility Checklist

✅ **ARIA labels** for all interactive elements
✅ **Keyboard navigation** works (Tab, Enter, Escape)
✅ **Focus indicators** visible
✅ **Color contrast** meets WCAG AA standards
✅ **Screen reader** compatible
✅ **Semantic HTML** (button, nav, main, header)
✅ **Alt text** for images
✅ **Form labels** associated with inputs

## Common Patterns to Follow

1. **Mobile-first responsive design**
2. **Component composition** over inheritance
3. **Props for configuration, state for interaction**
4. **Lifting state up** when shared between components
5. **Error boundaries** for error handling
6. **Loading states** for async operations
7. **Accessibility by default** (ARIA, keyboard support)

## What NOT to Do

❌ Don't use inline styles for complex styling
❌ Don't forget key prop in lists
❌ Don't mutate state directly
❌ Don't skip accessibility features
❌ Don't hardcode API URLs (use environment variables)
❌ Don't skip loading and error states
❌ Don't forget mobile responsiveness

## Focus Areas

When reading task sections, prioritize:
- `requirements` - What UI needs to be built
- `technical-approach` - Component structure, state management
- `design` - Visual specifications, layout
- `ux` - User interactions, flows

## Remember

- **Test user interactions** - what users see and do, not implementation
- **Accessibility is mandatory** - ARIA labels, keyboard navigation
- **Mobile-first** - design for mobile, enhance for desktop
- **Error and loading states** - always handle async operations
- **Report blockers promptly** - missing APIs, design assets, specifications
- **Follow existing patterns** - check codebase for similar components
- **Validation is mandatory** - ALL tests must pass before completion

## Additional Resources

For deeper patterns and examples, see:
- **PATTERNS.md** - React hooks patterns, state management (load if needed)
- **BLOCKERS.md** - Detailed frontend-specific blockers (load if stuck)
- **examples.md** - Complete component examples (load if uncertain)
