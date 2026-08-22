package norm.generator

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import java.math.BigDecimal
import java.sql.Blob
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Column index of the `VALUE` column in the element `ResultSet` returned by
 * [java.sql.Array.getResultSet]. Column `1` is `INDEX`.
 */
private const val ELEMENT_VALUE_COLUMN_INDEX = 2

/**
 * Data type that can be mapped between Java and SQL using JDBC.
 */
internal interface SqlMappable {

  /**
   * Kotlin [KClass] for the data.
   */
  val klass: KClass<*>

  /**
   * KotlinPoet [TypeName] for the data.
   */
  val typeName: TypeName
    get() = klass.asTypeName()

  /**
   * Receiver action to call on a [Statement] when mapping the data from Java to SQL.
   */
  val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock

  /**
   * Receiver action to call on a [ResultSet] when mapping the data from SQL to Java.
   */
  val resultSetAction: (index: Int) -> CodeBlock
}

/**
 * Types with first-class support in JDBC.
 */
internal enum class JdbcTypes(override val klass: KClass<*>) : SqlMappable {
  BOOLEAN(Boolean::class),
  SHORT(Short::class),
  INT(Int::class),
  LONG(Long::class),
  FLOAT(Float::class),
  DOUBLE(Double::class),
  BIG_DECIMAL(BigDecimal::class),
  STRING(String::class),
  BLOB(Blob::class),
  ;

  override val statementAction: (Int, CodeBlock) -> CodeBlock =
    { index, parameterName -> CodeBlock.of("%N(%L, %L)", "set${klass.simpleName}", index, parameterName) }
  override val resultSetAction: (Int) -> CodeBlock =
    { index -> CodeBlock.of("%N(%L)", "get${klass.simpleName}", index) }

  /**
   * See [NullablePrimitiveDecorator].
   */
  fun decorateForNullable(notNull: Boolean): SqlMappable = if (notNull) this else NullablePrimitiveDecorator(this)
}

/**
 * Decorates a [SqlMappable] for a primitive value with nullability information.
 */
internal class NullablePrimitiveDecorator(private val delegate: JdbcTypes) : SqlMappable {
  override val klass: KClass<*>
    get() = delegate.klass
  override val typeName: TypeName
    get() = delegate.typeName.copy(true)
  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = { index, parameterName ->
      val member = MemberName("norm", "set${klass.simpleName}", isExtension = true)
      CodeBlock.of("%M(%L, %L)", member, index, parameterName)
    }
  override val resultSetAction: (index: Int) -> CodeBlock
    get() = { CodeBlock.of("%L.takeUnless { wasNull() }", delegate.resultSetAction(it)) }
}

/**
 * Decorates a [SqlMappable] to handle PostgreSQL array types.
 *
 * Writes bind through [norm.toSqlArray], which names the Postgres element type explicitly. A bare
 * `setObject(index, array)` leaves the element OID to the driver's inference, which produces
 * `character varying[]` for `jsonb[]` (rejected by Postgres) and fails outright for every
 * `java.time` element type.
 *
 * @param delegate The base type mapper for the array element type. Supplies the element read via
 *   [SqlMappable.resultSetAction]; its [SqlMappable.statementAction] is unused, because element
 *   values are rendered into a Postgres array literal by the driver rather than bound individually.
 * @param arrayTypeName The Kotlin array type (e.g. `Array<String?>`). Its nullability is the
 *   column's: elements are always nullable, the array itself only when the column is.
 * @param postgresElementTypeName The canonical Postgres element type name passed to
 *   [norm.toSqlArray] (e.g. `"jsonb"`, `"timestamptz"`). See [postgresArrayElementTypeName].
 */
internal class ArrayTypeDecorator(
  private val delegate: SqlMappable,
  private val arrayTypeName: TypeName,
  private val postgresElementTypeName: String,
) : SqlMappable {

  private val toSqlArrayMember = MemberName("norm", "toSqlArray", isExtension = true)
  private val mapElementsMember = MemberName("norm", "mapElements", isExtension = true)

  override val klass: KClass<*>
    get() = delegate.klass

  override val typeName: TypeName
    get() = arrayTypeName

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = if (arrayTypeName.isNullable) {
      { index, parameterName ->
        CodeBlock.of(
          "%L?.let { setArray(%L, it.%M(connection, %S)) } ?: setNull(%L, %T.ARRAY)",
          parameterName,
          index,
          toSqlArrayMember,
          postgresElementTypeName,
          index,
          Types::class,
        )
      }
    } else {
      { index, parameterName ->
        CodeBlock.of(
          "setArray(%L, %L.%M(connection, %S))",
          index,
          parameterName,
          toSqlArrayMember,
          postgresElementTypeName,
        )
      }
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = if (arrayTypeName.isNullable) {
      { index ->
        CodeBlock.of(
          "getArray(%L)?.%M { %L }",
          index,
          mapElementsMember,
          delegate.resultSetAction(ELEMENT_VALUE_COLUMN_INDEX),
        )
      }
    } else {
      { index ->
        CodeBlock.of(
          "getArray(%L).%M { %L }",
          index,
          mapElementsMember,
          delegate.resultSetAction(ELEMENT_VALUE_COLUMN_INDEX),
        )
      }
    }
}

/**
 * Types with support in the Postgres JDBC driver.
 */
internal enum class PostgresSupportedTypes(
  override val klass: KClass<*>,
  override val statementAction: (Int, CodeBlock) -> CodeBlock,
  override val resultSetAction: (index: Int) -> CodeBlock,
) : SqlMappable {
  UUID(
    java.util.UUID::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, java.util.UUID::class) },
  ),
  LOCAL_DATE(
    LocalDate::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, LocalDate::class) },
  ),
  LOCAL_TIME(
    LocalTime::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, LocalTime::class) },
  ),
  OFFSET_TIME(
    OffsetTime::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, OffsetTime::class) },
  ),
  LOCAL_DATE_TIME(
    LocalDateTime::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, LocalDateTime::class) },
  ),
  BYTE_ARRAY(
    ByteArray::class,
    { index, parameterName -> CodeBlock.of("setBytes(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getBytes(%L)", index) },
  ),
}

/**
 * [SqlMappable] for `timestamptz` columns mapped to [Instant].
 *
 * pgjdbc does not support `getObject(i, Instant::class.java)`, so reads go through
 * [OffsetDateTime] and convert via `toInstant()`. Writes convert via
 * `OffsetDateTime.ofInstant(value, ZoneOffset.UTC)`.
 *
 * Unlike [PostgresSupportedTypes] entries, this requires nullable awareness because the
 * `.toInstant()` chain on a null [OffsetDateTime] would NPE. Other [PostgresSupportedTypes]
 * entries return Java platform types directly, so null propagates naturally.
 */
internal class InstantSqlMappable(private val notNull: Boolean) : SqlMappable {

  override val klass: KClass<*> = Instant::class

  override val typeName: TypeName
    get() = klass.asTypeName().copy(nullable = !notNull)

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = if (notNull) {
      { index, parameterName ->
        CodeBlock.of(
          "setObject(%L, %T.ofInstant(%L, %T.UTC))",
          index,
          OffsetDateTime::class,
          parameterName,
          ZoneOffset::class,
        )
      }
    } else {
      { index, parameterName ->
        CodeBlock.of(
          "%L?.let { setObject(%L, %T.ofInstant(it, %T.UTC)) } ?: setNull(%L, %T.TIMESTAMP_WITH_TIMEZONE)",
          parameterName,
          index,
          OffsetDateTime::class,
          ZoneOffset::class,
          index,
          Types::class,
        )
      }
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = if (notNull) {
      { index ->
        CodeBlock.of(
          "getObject(%L, %T::class.java).toInstant()",
          index,
          OffsetDateTime::class,
        )
      }
    } else {
      { index ->
        CodeBlock.of(
          "getObject(%L, %T::class.java)?.toInstant()",
          index,
          OffsetDateTime::class,
        )
      }
    }
}

/**
 * [SqlMappable] for plain (adapterless) `json` and `jsonb` columns.
 *
 * The Postgres JDBC driver rejects `setString()` for both types in prepared statements
 * (`column "..." is of type jsonb but expression is of type character varying`), exactly as it does
 * for enum columns. Binding with `setObject(index, value, Types.OTHER)` sends the value with an
 * unspecified OID and lets Postgres perform the coercion. `json` and `jsonb` differ only in storage
 * and in what Postgres preserves on the way in, not in how a parameter is bound or read, so both
 * share this mapping.
 *
 * Unlike [InstantSqlMappable], the write path does not branch on nullability: pgjdbc's
 * `setObject(index, null, targetSqlType)` delegates to `setNull(index, targetSqlType)`, so one
 * code path covers both cases.
 *
 * Reads use `getString`, which works for both types and returns `null` for SQL `NULL`.
 *
 * Keep in sync with the `"json"` and `"jsonb"` entries in [resolveJdbcTypeInfo], which define the
 * same binding for the adapter path (user-configured `json`/`jsonb` type mappings and domains built
 * on them).
 *
 * @param notNull Whether the column is `NOT NULL`. Affects [typeName] nullability only.
 */
internal class JsonSqlMappable(private val notNull: Boolean) : SqlMappable {

  override val klass: KClass<*> = String::class

  override val typeName: TypeName
    get() = klass.asTypeName().copy(nullable = !notNull)

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = { index, parameterName ->
      CodeBlock.of("setObject(%L, %L, %T.OTHER)", index, parameterName, Types::class)
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = { index -> CodeBlock.of("getString(%L)", index) }
}

/**
 * JDBC method metadata for a type's wire representation, used to generate the correct
 * `ResultSet` and `PreparedStatement` calls for reading and writing values through an adapter.
 *
 * @property getterName The `ResultSet` getter method name (e.g., `"getString"`, `"getInt"`).
 * @property setterName The `PreparedStatement` setter method name (e.g., `"setString"`, `"setInt"`).
 * @property isPrimitive Whether the JDBC getter returns a JVM primitive (`true` for `Int`, `Short`,
 *   `Long`, `Float`, `Double`, `Boolean`). Primitives require a `wasNull()` check for nullable columns
 *   because JDBC returns `0`/`false` instead of `null`.
 * @property sqlTypeConstant The field name on [java.sql.Types] for `setNull()` calls (e.g., `"VARCHAR"`, `"INTEGER"`).
 * @property useSqlTypeHint When `true`, the setter is generated as `setObject(index, value, Types.sqlTypeConstant)`
 *   instead of `setterName(index, value)`. Required for Postgres custom types (enums) where the JDBC driver
 *   refuses to coerce a `VARCHAR` binding — passing `Types.OTHER` bypasses the driver's type enforcement
 *   and lets Postgres perform the coercion itself.
 * @property kotlinType The KotlinPoet [TypeName] for the Kotlin type that JDBC delivers this value as
 *   (e.g., `String` for text/varchar, `Int` for int4). This is the wire type used for adapter type parameters
 *   and domain value class properties.
 * @property getterClassHint When non-`null`, the read is generated as `getObject(index, X::class.java)`
 *   (where `X` is [getterClassHint]) instead of `getterName(index)`. Required for `java.time` types
 *   (`LocalDate`, `LocalTime`, `OffsetTime`, `LocalDateTime`, `OffsetDateTime`): pgjdbc's plain
 *   `getObject(int)` returns the legacy `java.sql.Date`/`Time`/`Timestamp` for these columns, not the
 *   `java.time` type — only the two-argument, class-qualified overload does (verified against pgjdbc
 *   42.7.13's `PgResultSet.getObject(int, Class)`, which special-cases exactly these five classes).
 *   `null` for every type where a plain `getterName(index)` call already returns [kotlinType] directly
 *   (including `uuid`, whose plain `getObject(int)` already returns `java.util.UUID` — verified against
 *   pgjdbc's `PgResultSet.internalGetObject`, which special-cases the Postgres `uuid` type by name).
 * @property convertOffsetDateTimeToInstant When `true`, the wire value read via [getterClassHint]
 *   (always [OffsetDateTime] in this case) is converted with `.toInstant()` after reading, and a
 *   [kotlinType] ([Instant]) value is converted back with `OffsetDateTime.ofInstant(value,
 *   ZoneOffset.UTC)` before writing. Set only for `timestamptz`, whose wire representation
 *   ([OffsetDateTime]) differs from the Kotlin representation the non-domain scalar path uses
 *   ([Instant], see [InstantSqlMappable]) — every other type's wire and Kotlin representations are
 *   the same type, so this is `false` for them.
 */
internal data class JdbcTypeInfo(
  val getterName: String,
  val setterName: String,
  val isPrimitive: Boolean,
  val sqlTypeConstant: String,
  val useSqlTypeHint: Boolean = false,
  val kotlinType: TypeName,
  val getterClassHint: ClassName? = null,
  val convertOffsetDateTimeToInstant: Boolean = false,
)

/**
 * [SqlMappable] for a column that uses a `norm.ColumnAdapter` for encode/decode.
 *
 * Covers auto-generated adapters (enums, domains) and user-configured adapters. The adapter's
 * wire type is described by [jdbcTypeInfo], which determines the JDBC getter/setter methods.
 *
 * Generated types don't exist at generator time, so [klass] is not available — use [typeName] instead.
 *
 * The generated read/write code references an adapter property (e.g., `emailAdapter`) on the enclosing
 * `PostgresQueries` class, which is visible inside the `ResultSet`/`PreparedStatement` receiver lambdas
 * via Kotlin closure scoping.
 *
 * @param applicationTypeName The KotlinPoet [TypeName] of the application type (e.g., `example.Email`,
 *   or a parameterized type like `kotlin.collections.Map<kotlin.String, kotlin.Any?>`).
 * @param adapterPropertyName The property name on `PostgresQueries` for the adapter (e.g., `"emailAdapter"`).
 * @param notNull Whether the column is `NOT NULL`.
 * @param jdbcTypeInfo JDBC method info for the adapter's wire type.
 */
internal class AdaptedTypeSqlMappable(
  private val applicationTypeName: TypeName,
  private val adapterPropertyName: String,
  private val notNull: Boolean,
  private val jdbcTypeInfo: JdbcTypeInfo,
) : SqlMappable {

  override val klass: KClass<*>
    get() = throw UnsupportedOperationException(
      "Generated type $applicationTypeName has no KClass at generator time. Use typeName instead.",
    )

  override val typeName: TypeName
    get() = applicationTypeName

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = if (notNull) {
      if (jdbcTypeInfo.useSqlTypeHint) {
        { index, parameterName ->
          CodeBlock.of(
            "setObject(%L, %N.encode(%L), %T.%N)",
            index,
            adapterPropertyName,
            parameterName,
            Types::class,
            jdbcTypeInfo.sqlTypeConstant,
          )
        }
      } else {
        { index, parameterName ->
          CodeBlock.of(
            "%N(%L, %L)",
            jdbcTypeInfo.setterName,
            index,
            encodedValueExpression(parameterName),
          )
        }
      }
    } else {
      if (jdbcTypeInfo.useSqlTypeHint) {
        { index, parameterName ->
          CodeBlock.of(
            "%L?.let { setObject(%L, %N.encode(it), %T.%N) } ?: setNull(%L, %T.%N)",
            parameterName,
            index,
            adapterPropertyName,
            Types::class,
            jdbcTypeInfo.sqlTypeConstant,
            index,
            Types::class,
            jdbcTypeInfo.sqlTypeConstant,
          )
        }
      } else {
        { index, parameterName ->
          CodeBlock.of(
            "%L?.let { %N(%L, %L) } ?: setNull(%L, %T.%N)",
            parameterName,
            jdbcTypeInfo.setterName,
            index,
            encodedValueExpression(CodeBlock.of("it")),
            index,
            Types::class,
            jdbcTypeInfo.sqlTypeConstant,
          )
        }
      }
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = if (notNull) {
      { index -> CodeBlock.of("%N.decode(%L)", adapterPropertyName, readExpression(index, nullable = false)) }
    } else if (jdbcTypeInfo.isPrimitive) {
      { index ->
        CodeBlock.of(
          "%L.takeUnless { wasNull() }?.let { %N.decode(it) }",
          rawReadExpression(index),
          adapterPropertyName,
        )
      }
    } else {
      { index ->
        CodeBlock.of(
          "%L?.let { %N.decode(it) }",
          readExpression(index, nullable = true),
          adapterPropertyName,
        )
      }
    }

  /**
   * The encoded, wire-ready form of [valueExpression] (an already-non-null Kotlin value of
   * [applicationTypeName]'s underlying domain/adapter base type): `adapter.encode(value)`, or — only
   * when [JdbcTypeInfo.convertOffsetDateTimeToInstant] is set — that same encode call converted from
   * [Instant] to [OffsetDateTime], since `timestamptz`'s wire representation is [OffsetDateTime] but
   * its Kotlin representation ([kotlinType][JdbcTypeInfo.kotlinType]) is [Instant]. See
   * [JdbcTypeInfo.convertOffsetDateTimeToInstant]'s KDoc.
   */
  private fun encodedValueExpression(valueExpression: CodeBlock): CodeBlock {
    val encoded = CodeBlock.of("%N.encode(%L)", adapterPropertyName, valueExpression)
    return if (jdbcTypeInfo.convertOffsetDateTimeToInstant) {
      CodeBlock.of("%T.ofInstant(%L, %T.UTC)", OffsetDateTime::class, encoded, ZoneOffset::class)
    } else {
      encoded
    }
  }

  /**
   * The raw JDBC read at [index]: `getterName(index)`, or — when [JdbcTypeInfo.getterClassHint] is
   * set — `getObject(index, X::class.java)`. See [JdbcTypeInfo.getterClassHint]'s KDoc for why some
   * types need the class-qualified form.
   */
  private fun rawReadExpression(index: Int): CodeBlock = if (jdbcTypeInfo.getterClassHint != null) {
    CodeBlock.of("%N(%L, %T::class.java)", jdbcTypeInfo.getterName, index, jdbcTypeInfo.getterClassHint)
  } else {
    CodeBlock.of("%N(%L)", jdbcTypeInfo.getterName, index)
  }

  /**
   * [rawReadExpression] converted to [JdbcTypeInfo.kotlinType] — only [JdbcTypeInfo.convertOffsetDateTimeToInstant]
   * types need a conversion, applied as a safe call (`?.toInstant()`) when [nullable] so a `NULL`
   * column value stays `null` rather than throwing on the safe-call receiver.
   */
  private fun readExpression(index: Int, nullable: Boolean): CodeBlock {
    val raw = rawReadExpression(index)
    return if (!jdbcTypeInfo.convertOffsetDateTimeToInstant) {
      raw
    } else if (nullable) {
      CodeBlock.of("%L?.toInstant()", raw)
    } else {
      CodeBlock.of("%L.toInstant()", raw)
    }
  }
}

/**
 * [SqlMappable] for an array column whose elements use a `norm.ColumnAdapter`.
 *
 * Like [ArrayTypeDecorator], this class reads and writes elements one at a time rather than
 * casting the bulk JDBC array. It differs in that each element is routed through a
 * `norm.ColumnAdapter`, because the JDBC wire type (`String[]` for enums, `Integer[]` for int4
 * domains) differs from the application type (`Array<Mood?>`, `Array<PositiveInteger?>`).
 *
 * The Kotlin type is always `Array<ApplicationType?>` — elements are nullable because Postgres
 * arrays can contain `NULL` values regardless of the column's `NOT NULL` constraint. Column-level
 * nullability controls only whether the array itself is nullable.
 *
 * For reads, delegates to the runtime `decodeArray` extension. For writes, delegates to the
 * runtime `encodeToSqlArray` extension, which calls `connection.createArrayOf(postgresTypeName, ...)`
 * — required because the Postgres JDBC driver cannot infer the type from a plain `String[]`.
 *
 * @param applicationTypeName The element's application type (e.g., `example.Mood`, or a parameterized
 *   type like `kotlin.collections.Map<kotlin.String, kotlin.Any?>`).
 * @param adapterPropertyName The adapter property name on `PostgresQueries` (e.g., `"moodAdapter"`).
 * @param columnNotNull Whether the column is `NOT NULL` (controls array-level nullability).
 * @param postgresTypeName The Postgres type name for `encodeToSqlArray` (e.g., `"mood"`, `"email"`).
 */
internal class AdaptedArrayTypeSqlMappable(
  private val applicationTypeName: TypeName,
  private val adapterPropertyName: String,
  private val columnNotNull: Boolean,
  private val postgresTypeName: String,
) : SqlMappable {

  private val decodeArrayMember = MemberName("norm", "decodeArray", isExtension = true)
  private val encodeToSqlArrayMember = MemberName("norm", "encodeToSqlArray", isExtension = true)

  override val klass: KClass<*>
    get() = throw UnsupportedOperationException(
      "Generated array type Array<$applicationTypeName?> has no KClass at generator time. Use typeName instead.",
    )

  override val typeName: TypeName
    get() = ARRAY.parameterizedBy(applicationTypeName.copy(nullable = true))

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = if (columnNotNull) {
      { index, parameterName ->
        CodeBlock.of(
          "setArray(%L, %L.%M(connection, %S, %N))",
          index,
          parameterName,
          encodeToSqlArrayMember,
          postgresTypeName,
          adapterPropertyName,
        )
      }
    } else {
      { index, parameterName ->
        CodeBlock.of(
          "%L?.let { setArray(%L, it.%M(connection, %S, %N)) } ?: setNull(%L, %T.ARRAY)",
          parameterName,
          index,
          encodeToSqlArrayMember,
          postgresTypeName,
          adapterPropertyName,
          index,
          Types::class,
        )
      }
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = if (columnNotNull) {
      { index -> CodeBlock.of("getArray(%L).%M(%N)", index, decodeArrayMember, adapterPropertyName) }
    } else {
      { index -> CodeBlock.of("getArray(%L)?.%M(%N)", index, decodeArrayMember, adapterPropertyName) }
    }
}
