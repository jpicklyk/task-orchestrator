---
name: security-review
description: Security assessment for items with the needs-security-review trait. Evaluates input validation, injection risks, access control, and data handling against OWASP Top 10. Invoked via skillPointer when filling security-assessment notes.
user-invocable: false
---

# Security Review Framework

Evaluate security posture of changed code. This project is a Kotlin MCP server using Exposed ORM with SQLite, running in Docker.

## Step 1: Input Validation

Read the changed files and check every entry point for external input:
- [ ] **MCP tool parameters** — are all string inputs validated before use (length, format, allowed characters)?
- [ ] **UUID parameters** — parsed with try/catch, not assumed valid?
- [ ] **Enum/role parameters** — validated against allowed values, not passed through raw?
- [ ] **JSON body parsing** — malformed JSON handled gracefully, not crashing?

## Step 2: Injection Risks

- [ ] **SQL injection** — Exposed ORM uses parameterized queries by default. Check for any raw SQL (`exec()`, `SqlExpressionBuilder`) that interpolates user input
- [ ] **Command injection** — any `ProcessBuilder`, `Runtime.exec()`, or shell command construction with user input?
- [ ] **Path traversal** — any file operations using user-provided paths (config loading, file reads)?
- [ ] **XSS in response payloads** — MCP responses are JSON, but check for HTML/script content that could be rendered by a consuming UI

## Step 3: Access Control

- [ ] **Authorization boundaries** — does the change respect existing access patterns? (MCP servers typically trust all callers, but verify no unintended escalation)
- [ ] **Resource isolation** — can one item's data leak into another's response?
- [ ] **Docker security** — runs as non-root user (appuser:1001), no privileged operations added?

## Step 4: Data Handling

- [ ] **Sensitive data in logs** — LOG_LEVEL=DEBUG doesn't print passwords, tokens, or PII
- [ ] **Sensitive data in responses** — no internal implementation details leaked in error messages
- [ ] **Data at rest** — SQLite database on Docker volume, no additional encryption (acceptable for this use case, but flag if sensitive data is added)
- [ ] **Properties/metadata fields** — user-controlled JSON stored as-is; verify no server-side evaluation of content

## Step 5: OWASP Top 10 Check

Quick scan against relevant categories:
- [ ] A01: Broken Access Control
- [ ] A02: Cryptographic Failures (if crypto is involved)
- [ ] A03: Injection (covered in Step 2)
- [ ] A04: Insecure Design (architectural concerns)
- [ ] A05: Security Misconfiguration (Docker, env vars)
- [ ] A08: Software and Data Integrity (dependency supply chain)

## Output

Compose the `security-assessment` note with findings from each step. Flag any OWASP Top 10 concerns with severity and remediation.
