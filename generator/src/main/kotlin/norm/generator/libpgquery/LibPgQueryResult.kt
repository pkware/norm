package norm.generator.libpgquery

/**
 * The outcome of a libpg_query call on a text that PostgreSQL may reject.
 *
 * @param T what an accepted text yields.
 */
internal sealed interface LibPgQueryResult<out T : Any> {
  /** Returns the value of an [Accepted] result, or `null` for a [Rejected] one. */
  fun valueOrNull(): T?

  /**
   * The text was accepted.
   *
   * @property value what the call produced.
   */
  data class Accepted<out T : Any>(val value: T) : LibPgQueryResult<T> {
    override fun valueOrNull(): T = value
  }

  /**
   * PostgreSQL's grammar or lexer rejected the text.
   *
   * @property message PostgreSQL's error message.
   * @property cursorIndex 0-based `String` index into the rejected text that the error points at, or `null` when
   *   PostgreSQL reports no position.
   */
  data class Rejected(val message: String, val cursorIndex: Int?) : LibPgQueryResult<Nothing> {
    override fun valueOrNull(): Nothing? = null
  }
}
