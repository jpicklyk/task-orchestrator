# test-file-ownership

The implementer never creates or modifies a file under `src/test/**`, on any item.
Test code has one owner per case: the test-author seat when the item carries `needs-test-author`,
otherwise the orchestrator; the orchestrator's contract-change sweep owns construction repairs.
Keeping the trees apart is what makes the tests an independent check on the implementation.

1. **Dispatch.** Every implementation dispatch excludes `src/test/**` from scope and says so: "Do NOT
   create or modify any file under src/test/**; if the change surfaces a needed test update, report
   it in your return or in `implementation-notes`."
2. **A needed test update is reported, never made.** The implementer names the test and the reason in
   its notes; the test author or the orchestrator owns the edit.
3. **Boundary proof (items carrying `needs-test-author`).** Capture HEAD before and after each seat's dispatch and diff the ranges by name:
   the implementer's range shares no path with `src/test/**`, and the test author's range shares no
   path with `src/main/**` and is not empty. An implementer commit touching `src/test/**`, or a
   test-author commit touching `src/main/**`, is a scope breach even when the edit is reasonable,
   unless it is a declared exception (rule `review-scoping`).
4. **Contract-change repairs.** When a change tightens a contract, the orchestrator repairs test
   construction only (rule `contract-change-sweep`), never an assertion, and declares each repair
   sha. A repair that would touch an expectation goes to the test author.
