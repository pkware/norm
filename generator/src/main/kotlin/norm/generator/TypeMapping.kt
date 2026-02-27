package norm.generator

/**
 * A user-configured mapping from a Postgres type (or specific column) to a Kotlin type via an adapter.
 *
 * Type-level overrides apply to all columns of that Postgres type and suppress auto-generation
 * of the matching enum or domain adapter. Column-level overrides apply to a single column and
 * do NOT suppress auto-generation — other columns of the same type may still need it.
 *
 * @property postgresType The Postgres type name to override (e.g., `"mood"`, `"jsonb"`).
 *   Empty for column-level overrides (the actual type is resolved from the catalog).
 * @property table The table name for column-level overrides. `null` for type-level overrides.
 * @property column The column name for column-level overrides. `null` for type-level overrides.
 * @property kotlinType Fully-qualified Kotlin class name for the application type
 *   (e.g., `"com.example.Mood"`).
 * @property adapterType Fully-qualified Kotlin class name for the
 *   [ColumnAdapter][norm.ColumnAdapter] implementation (e.g., `"com.example.MoodAdapter"`).
 */
public data class TypeMapping(
  val postgresType: String,
  val table: String?,
  val column: String?,
  val kotlinType: String,
  val adapterType: String,
) : java.io.Serializable {

  /** Whether this mapping targets a specific column rather than all columns of a type. */
  val isColumnLevel: Boolean get() = table != null && column != null

  /** Whether this mapping targets all columns of a Postgres type. */
  val isTypeLevel: Boolean get() = !isColumnLevel
}
