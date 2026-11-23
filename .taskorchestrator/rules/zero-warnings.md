# zero-warnings

Warnings that nobody reads pile up: every gate stays green while the build output fills with
warnings, and each one hides the next real one. The compiler already fails this build on its own
warnings; this rule covers what the build cannot see.

1. **Hand over a clean build.** A change goes to review, and a pull request is cut, only when its
   build prints no compiler or linter warning.
2. **Never silence a warning to get green.** A suppression (`@Suppress`, a compiler opt-in, a
   linter disable comment, a baseline line) carries a one-line reason at its site that says why the
   warning is wrong or cannot be avoided.
3. **The reviewer checks every suppression the diff adds.** Each one is a finding unless its reason
   holds. A suppression with no reason is blocking.
4. **A bump brings its own fixes.** A deprecation brought in by a dependency or toolchain upgrade is
   fixed in the same change that brings it in, not left for later.
