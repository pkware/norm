package norm

import java.sql.Connection

/**
 * Decodes a JDBC array into a typed Kotlin array by applying [adapter] to each element.
 *
 * The JDBC driver returns the underlying data as an array of wire-type values
 * (e.g., `String[]` for enum columns, `Integer[]` for int4 domain columns). This function
 * casts to `Array<W?>` and maps each element through [ColumnAdapter.decode].
 *
 * Elements are always `A?` (nullable) because Postgres arrays can contain `NULL` values
 * regardless of the column's `NOT NULL` constraint.
 *
 * Usage in generated code:
 * - Non-null column: `getArray(i).decodeArray(adapter)`
 * - Nullable column: `getArray(i)?.decodeArray(adapter)` (safe call makes result nullable)
 *
 * @param WireType The JDBC wire type returned by the driver for each element (e.g., [String], [Int]).
 * @param ApplicationType The application type decoded by the adapter (e.g., `Mood`, `Email`).
 */
@Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
public inline fun <reified WireType : Any, reified ApplicationType : Any> java.sql.Array.decodeArray(
  adapter: ColumnAdapter<ApplicationType, WireType>,
): Array<ApplicationType?> = (array as Array<WireType?>).map { it?.let(adapter::decode) }.toTypedArray()

/**
 * Encodes a Kotlin array into a JDBC [java.sql.Array] by applying [adapter] to each element.
 *
 * Each element is encoded through [ColumnAdapter.encode]. [connection] is used to call
 * [Connection.createArrayOf] with [typeName], which is required by the Postgres JDBC driver
 * to produce a correctly typed array — calling `setObject` with a raw `String[]` fails for
 * enum arrays because the driver cannot infer the Postgres type.
 *
 * Usage in generated code:
 * - Non-null column: `setArray(i, value.encodeToSqlArray(connection, "mood", adapter))`
 * - Nullable column: `value?.let { setArray(i, it.encodeToSqlArray(connection, "mood", adapter)) } ?: setNull(i, Types.ARRAY)`
 *
 * @param ApplicationType The application type encoded by the adapter (e.g., `Mood`, `Email`).
 * @param WireType The JDBC wire type returned by [ColumnAdapter.encode] (e.g., [String], [Int]).
 * @param connection The JDBC connection, used to create a typed SQL array.
 * @param typeName The Postgres type name for the array elements (e.g., `"mood"`, `"email"`).
 */
public fun <ApplicationType : Any, WireType : Any> Array<ApplicationType?>.encodeToSqlArray(
  connection: Connection,
  typeName: String,
  adapter: ColumnAdapter<ApplicationType, WireType>,
): java.sql.Array = connection.createArrayOf(typeName, map { it?.let(adapter::encode) as? Any }.toTypedArray())
