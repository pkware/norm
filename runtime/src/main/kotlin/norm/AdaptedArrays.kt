package norm

import java.sql.Connection
import java.sql.SQLException

/**
 * Returns a copy of this JDBC array whose element type is [elementTypeName].
 *
 * The element `ResultSet` of a domain array carries the domain's own OID, and `getObject(index, Class)`
 * cannot map that OID to a Java type. This function reads each element as text and rebuilds the array
 * under the domain's base type. [mapElements] then reads each element like a column of that type.
 *
 * Usage in generated code:
 * - Non-null column: `getArray(i).withElementType(this.statement.connection, "date").mapElements { ... }`
 * - Nullable column: `getArray(i)?.withElementType(this.statement.connection, "date")?.mapElements { ... }`
 *
 * @param connection The JDBC connection that creates the returned array.
 * @param elementTypeName The canonical Postgres name of the domain's base type (`int4`, not `integer`).
 * @throws SQLException if reading the elements fails or [elementTypeName] is not a known Postgres type.
 */
@Throws(SQLException::class)
public fun java.sql.Array.withElementType(connection: Connection, elementTypeName: String): java.sql.Array =
  connection.createArrayOf(elementTypeName, mapElements { getString(2) })

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
