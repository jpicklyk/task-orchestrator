# test-harness.full-wiring

A test that exercises a hand-built stand-in for a component, wired up with pre-arranged inputs
chosen by the test author, can pass without ever proving the real production wiring works. This
rule requires tests to assert through the same public entry points production traffic actually
uses, fully wired the way the running system wires them.

1. **Assert through public entry points.** A test for a server-side tool, route, or service calls
   the same public entry point an external caller would use -- the tool's real execution method
   built from the real composition root, the real routing layer with its real middleware installed
   in the real order, or a public service/resolver method -- never an internal helper reached only
   by pre-arranging its inputs by hand.
2. **Never a hand-built replica of the wiring.** Do not construct a stripped-down, parallel version
   of the execution context, route table, or service graph "for testing" that skips steps the real
   composition performs (middleware installation order, request/response content negotiation,
   dependency construction). A replica that diverges from the real wiring can pass while the real
   system fails the same request, and it can fail while the real system succeeds, in either
   direction making the test's verdict meaningless.
3. **Build from the real composition root.** Where the codebase has a single, canonical place that
   assembles a component for production use, tests use that same assembly path (constructing it
   fresh per test, or reusing a fixture that itself uses that path) rather than instantiating the
   component's internals directly and wiring only the pieces the test author judged relevant.
4. **When in doubt, favor the slower, real path.** A test that is slightly slower because it goes
   through the real wiring is worth far more than a fast test that proves nothing about production
   behavior. Speed is not a valid reason to bypass this rule.

## claude:

- In this codebase: MCP-tool tests call the tool's `execute` method built from the real
  composition entry point that assembles the production tool-execution context; REST-route tests
  install the real route-registration function with the same content-negotiation and middleware
  setup production uses, in the same order; service-layer tests call the public service or
  resolver methods, never internal helpers reached with pre-arranged fixture state. A hand-built
  replica of the tool-execution context or the route table is out of bounds even for convenience.
