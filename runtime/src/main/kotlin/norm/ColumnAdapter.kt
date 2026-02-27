package norm

/**
 * Bridges a Kotlin application type to its database representation for a single column.
 *
 * Adapters are used by generated code to convert between Kotlin classes and their database representation.
 *
 * Adapters never see `null` values — null handling is performed by the generated code before
 * calling [decode] or [encode]. Implementations can safely assume non-null inputs.
 *
 * @param ApplicationType The Kotlin type used in application code (e.g., a generated enum class).
 * @param DatabaseType The JDBC type used for database storage (e.g., [String]).
 */
public interface ColumnAdapter<ApplicationType : Any, DatabaseType : Any> {

  /**
   * Converts a database value to the application type.
   *
   * @param databaseValue The non-null value read from the database via JDBC.
   * @return The corresponding application-layer value.
   */
  public fun decode(databaseValue: DatabaseType): ApplicationType

  /**
   * Converts an application value to the database type.
   *
   * @param value The non-null application-layer value to store.
   * @return The corresponding database value for JDBC binding.
   */
  public fun encode(value: ApplicationType): DatabaseType
}
