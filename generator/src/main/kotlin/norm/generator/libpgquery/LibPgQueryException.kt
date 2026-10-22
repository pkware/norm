package norm.generator.libpgquery

/**
 * A syntax error reported by libpg_query.
 *
 * @property cursorIndex 0-based `String` index into the rejected text that the error points at, or `null` when
 *   PostgreSQL reports no position.
 */
internal class LibPgQueryException(message: String, val cursorIndex: Int?) : RuntimeException(message)
