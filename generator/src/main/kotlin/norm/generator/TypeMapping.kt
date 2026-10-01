package norm.generator

import java.io.Serializable

/**
 * A user-configured mapping from Postgres values to a Kotlin type via an adapter.
 *
 * A [ByType] mapping applies to every column of a Postgres type and suppresses auto-generation of the
 * matching enum or domain adapter. A [ByColumn] mapping applies to a single column and leaves
 * auto-generation in place, because other columns of the same type may still need it.
 */
public sealed interface TypeMapping : Serializable {

  /** Fully-qualified Kotlin class name for the application type (e.g., `"com.example.Mood"`). */
  public val kotlinType: String

  /**
   * Fully-qualified Kotlin class name of the `norm.ColumnAdapter` implementation (e.g., `"com.example.MoodAdapter"`).
   */
  public val adapterType: String

  /**
   * Maps every column of a Postgres type.
   *
   * @param postgresType the Postgres type name (e.g., `"mood"`, `"jsonb"`).
   */
  public data class ByType(
    val postgresType: String,
    override val kotlinType: String,
    override val adapterType: String,
  ) : TypeMapping

  /**
   * Maps a single column.
   *
   * @param table the table name as written in DDL, truncated to the server's identifier limit before matching.
   * @param column the column name, truncated the same way.
   */
  public data class ByColumn(
    val table: String,
    val column: String,
    override val kotlinType: String,
    override val adapterType: String,
  ) : TypeMapping
}
