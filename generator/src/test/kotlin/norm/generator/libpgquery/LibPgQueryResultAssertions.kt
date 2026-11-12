package norm.generator.libpgquery

/**
 * Returns the value of an accepted result.
 *
 * @throws AssertionError if PostgreSQL rejected the text.
 */
internal fun <T : Any> LibPgQueryResult<T>.accepted(): T = when (this) {
  is LibPgQueryResult.Accepted -> value
  is LibPgQueryResult.Rejected -> throw AssertionError("PostgreSQL rejected the text: $message")
}

/**
 * Returns the rejection.
 *
 * @throws AssertionError if PostgreSQL accepted the text.
 */
internal fun LibPgQueryResult<*>.rejected(): LibPgQueryResult.Rejected = when (this) {
  is LibPgQueryResult.Rejected -> this
  is LibPgQueryResult.Accepted -> throw AssertionError("PostgreSQL accepted the text")
}
