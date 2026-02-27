package norm.gradle

import norm.generator.TypeMapping

/**
 * DSL for configuring user-defined type mappings in the Norm Gradle plugin.
 *
 * Usage:
 * ```kotlin
 * typeMappings {
 *   type("jsonb") mapTo "com.example.JsonData" using "com.example.JsonDataAdapter"
 *   column("users", "metadata") mapTo "com.example.Metadata" using "com.example.MetadataAdapter"
 * }
 * ```
 *
 * Type-level overrides apply to all columns of that Postgres type and suppress auto-generation
 * of the matching enum or domain adapter. Column-level overrides apply to a single column only.
 */
public class TypeMappingDsl {
  private val mappings = mutableListOf<TypeMapping>()

  /**
   * Starts a type-level mapping for the given Postgres type.
   *
   * @param postgresType The Postgres type name (e.g., `"mood"`, `"jsonb"`).
   */
  public fun type(postgresType: String): TypeMappingTarget = TypeMappingTarget(postgresType = postgresType)

  /**
   * Starts a column-level mapping for a specific table and column.
   *
   * @param table The table name containing the column.
   * @param column The column name to override.
   */
  public fun column(table: String, column: String): TypeMappingTarget =
    TypeMappingTarget(table = table, column = column)

  /**
   * Intermediate builder holding the target (type or column) of a mapping.
   */
  public inner class TypeMappingTarget internal constructor(
    internal val postgresType: String = "",
    internal val table: String? = null,
    internal val column: String? = null,
  ) {
    /**
     * Specifies the Kotlin type that the Postgres type or column should map to.
     *
     * @param kotlinType Fully-qualified Kotlin class name (e.g., `"com.example.JsonData"`).
     */
    public infix fun mapTo(kotlinType: String): TypeMappingWithKotlinType = TypeMappingWithKotlinType(this, kotlinType)
  }

  /**
   * Intermediate builder holding the target and Kotlin type of a mapping.
   */
  public inner class TypeMappingWithKotlinType internal constructor(
    private val target: TypeMappingTarget,
    private val kotlinType: String,
  ) {
    /**
     * Specifies the adapter class that converts between the Postgres wire type and the Kotlin type.
     *
     * @param adapterType Fully-qualified Kotlin class name of the [ColumnAdapter][norm.ColumnAdapter]
     *   implementation (e.g., `"com.example.JsonDataAdapter"`).
     */
    public infix fun using(adapterType: String) {
      mappings.add(
        TypeMapping(
          postgresType = target.postgresType,
          table = target.table,
          column = target.column,
          kotlinType = kotlinType,
          adapterType = adapterType,
        ),
      )
    }
  }

  internal fun build(): List<TypeMapping> = mappings.toList()
}
