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
   * @property postgresType the Postgres type name (e.g., `"mood"`, `"jsonb"`).
   */
  public data class ByType(
    val postgresType: String,
    override val kotlinType: String,
    override val adapterType: String,
  ) : TypeMapping

  /**
   * Maps a single column.
   *
   * @property table the table name as written in DDL, truncated to the server's identifier limit before matching.
   * @property column the column name, truncated the same way.
   */
  public data class ByColumn(
    val table: String,
    val column: String,
    override val kotlinType: String,
    override val adapterType: String,
  ) : TypeMapping
}

/**
 * Renders this mapping in the form a user writes it in configuration, for error messages.
 *
 * Example: `column("users", "metadata") -> com.example.Metadata via com.example.MetadataAdapter`.
 */
internal fun TypeMapping.describe(): String {
  val target = when (this) {
    is TypeMapping.ByType -> """type("$postgresType")"""
    is TypeMapping.ByColumn -> """column("$table", "$column")"""
  }
  return "$target -> $kotlinType via $adapterType"
}
