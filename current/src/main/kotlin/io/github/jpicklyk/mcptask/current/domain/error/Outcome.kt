package io.github.jpicklyk.mcptask.current.domain.error

/** Success-or-[DomainError] result. Independent of the repository-layer `Result`. */
sealed interface Outcome<out T> {
    data class Ok<out T>(
        val value: T
    ) : Outcome<T>

    data class Err(
        val error: DomainError
    ) : Outcome<Nothing>

    fun <R> map(transform: (T) -> R): Outcome<R> =
        when (this) {
            is Ok -> Ok(transform(value))
            is Err -> this
        }

    fun <R> flatMap(transform: (T) -> Outcome<R>): Outcome<R> =
        when (this) {
            is Ok -> transform(value)
            is Err -> this
        }

    fun getOrNull(): T? =
        when (this) {
            is Ok -> value
            is Err -> null
        }
}
