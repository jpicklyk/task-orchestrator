I added the hook at `claude-plugins/task-orchestrator/hooks/plan-reminder.mjs` and its test at `hooks/tests/plan-reminder.test.mjs`. The new test passes, along with the existing `lib-adoption`, `hooks-readme` and `injected-text` tests I ran next to it.

- **First run in a session:** the hook adds "Check items_read next before you plan." to the model's context as `UserPromptSubmit` additional context.
- **Later runs in the same session:** it emits `{}`. Because each run is a new process, "first" is tracked with a marker file in the temp directory, one per session. It reuses `createMarkerOnce` and `sessionMarkerPath` from `lib/markers.mjs`.
- **No session id:** if the input is empty, isn't valid JSON, or has no `session_id`, the hook stays silent. I chose silence over a shared fallback key, which would otherwise suppress the reminder for every later session. Say so if you'd rather it fire in that case.
- **Registration:** it is not in `hooks-config.json`, and one of the tests asserts that.

The tests cover the first-run reminder, silence on later runs, a separate reminder per session, silence on bad input, and the non-registration.
