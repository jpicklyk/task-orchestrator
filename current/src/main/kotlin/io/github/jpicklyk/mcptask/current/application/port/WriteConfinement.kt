package io.github.jpicklyk.mcptask.current.application.port

/**
 * Opt-in marker on the store port methods that WRITE state an application service must record events for
 * (item, note, dependency, project-config, plan-document, document and link writes).
 *
 * The compiler resolves the receiver of every call, override and callable reference, so a call through any
 * receiver form (scope function, extension receiver, store-returning call, delegation) fails to compile
 * unless the call site opts in. Opt-in is confined to `application/service/` and `application/knowledge/`
 * by `WriteConfinementTest`; the build never opts in for main compilation.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Store writes belong to the application services that record the matching events; route this through the owning service."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class StoreWrite

/**
 * Opt-in marker on the claim and resource-lease write methods ([ClaimStore], [LeaseStore]) that only
 * `ClaimService` may call.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Claim and lease writes belong to ClaimService; route this through it."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class ClaimWrite
