package norm

import java.sql.Connection
import java.sql.ResultSet

/**
 * Wraps this Kotlin array in a JDBC [java.sql.Array] whose element type is [typeName].
 *
 * Norm's generated code binds plain (adapterless) array parameters through this function rather
 * than `setObject(index, array)`. The Postgres JDBC driver infers an element OID from the Kotlin
 * array's component type, and that inference is either wrong or impossible for several types:
 * `jsonb[]` is inferred as `character varying[]` and rejected by Postgres, and
 * `Array<java.time.LocalDate>` and the other `java.time` arrays are rejected outright with
 * `Cannot cast an instance of [Ljava.time.LocalDate; to type Types.ARRAY`. Naming the element type
 * explicitly removes the driver's inference from the picture.
 *
 * Element values are rendered by the driver, not by Norm: it uses a dedicated encoder where one
 * exists for the component type (`Int`, `Long`, `Short`, `Float`, `Double`, `Boolean`, `String`,
 * `ByteArray`) and `toString()` otherwise. For ordinary values, that `toString()` fallback produces
 * a valid Postgres literal for every type Norm generates, including [java.time.Instant]
 * (`2025-01-01T12:00:00Z`), [java.time.OffsetTime] (`12:00+02:00`), and [java.math.BigDecimal] in
 * exponent form. `java.time` extremes — BC-era dates, the `MIN`/`MAX` infinity sentinels, and years
 * outside `1..9999` — do not get the scalar binding path's `TimestampUtils` treatment here, so they
 * render differently in an array than they would in a scalar column of the same type.
 *
 * Usage in generated code:
 * - Non-null column: `setArray(i, value.toSqlArray(connection, "date"))`
 * - Nullable column: `value?.let { setArray(i, it.toSqlArray(connection, "date")) } ?: setNull(i, Types.ARRAY)`
 *
 * @param connection The JDBC connection, used to create a typed SQL array. In generated code this
 *   resolves to [java.sql.Statement.getConnection] on the enclosing `PreparedStatement` receiver.
 * @param typeName The Postgres **element** type name, which must be a canonical `pg_type` name
 *   (`int4`, not `integer`; `bool`, not `boolean`). The driver appends `[]` and looks the result up
 *   in `pg_type`, so a non-canonical name fails with
 *   `Unable to find server array type for provided name`.
 */
public fun Array<*>.toSqlArray(connection: Connection, typeName: String): java.sql.Array =
  connection.createArrayOf(typeName, this)

/**
 * Reads a JDBC array element by element, applying [read] to each element's row.
 *
 * The receiver of [read] is the element [ResultSet] from [java.sql.Array.getResultSet], which has
 * two columns: column `1` is `INDEX` and column `2` is `VALUE`. Callers read column `2`.
 *
 * Norm's generated code reads plain (adapterless) arrays through this function rather than casting
 * [java.sql.Array.getArray]. The bulk path returns legacy JDBC element classes — `java.sql.Date`
 * for `date`, `java.sql.Time` for both `time` and `timetz`, `java.sql.Timestamp` for `timestamp`
 * and `timestamptz` — so casting it to the `java.time` array Norm advertises throws
 * `ClassCastException`, and converting element by element still cannot represent `timetz` (a
 * `java.sql.Time` carries no offset) or full-precision `time` (millisecond resolution against
 * Postgres' microseconds). The element [ResultSet] has none of these problems: the driver builds it
 * with the element's own OID, so reading column `2` goes through exactly the same code path as
 * reading a scalar column of that type.
 *
 * The cost is one [ResultSet] row per element where the bulk path allocated none, accepted because
 * correctness for `timetz[]` and `time[]` is not available any other way.
 *
 * Elements are always nullable, because a Postgres array can contain `NULL` values regardless of
 * the column's `NOT NULL` constraint. `T` is inferred from [read]'s return type, so
 * `mapElements { getString(2) }` yields `Array<String?>`.
 *
 * Usage in generated code:
 * - Non-null column: `getArray(i).mapElements { getObject(2, LocalDate::class.java) }`
 * - Nullable column: `getArray(i)?.mapElements { getObject(2, LocalDate::class.java) }`
 *
 * @param read Reads one element from the element [ResultSet]'s current row, from column `2`.
 */
public inline fun <reified T> java.sql.Array.mapElements(read: ResultSet.() -> T): Array<T> =
  resultSet.use { elementRows ->
    buildList { while (elementRows.next()) add(elementRows.read()) }.toTypedArray()
  }
