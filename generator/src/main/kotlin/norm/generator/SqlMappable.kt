package norm.generator

import com.squareup.kotlinpoet.ARRAY
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import kotlin.reflect.KClass

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
 * Arrays in PostgreSQL are accessed via JDBC's getArray() method, which returns
 * a [java.sql.Array] object. The .array property provides the actual Kotlin array.
 *
 * @param delegate The base type mapper for the array element type
 * @param arrayTypeName The Kotlin array type (e.g., [IntArray], `Array<String>`)
 */
internal class ArrayTypeDecorator(private val delegate: SqlMappable, private val arrayTypeName: TypeName) :
  SqlMappable {

  override val klass: KClass<*>
    get() = delegate.klass

  override val typeName: TypeName
    get() = arrayTypeName

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = { index, parameterName ->
      // For arrays, use setObject which works with both primitive and object arrays
      CodeBlock.of("setObject(%L, %L)", index, parameterName)
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = { index ->
      // JDBC arrays: getArray(i) returns java.sql.Array or null
      // getArray(i).array returns Object (the actual array)
      // Cast to the appropriate Kotlin array type
      val getArray = if (arrayTypeName.isNullable) {
        // For nullable arrays, use safe calls to handle NULL values
        // getArray() returns null when the column value is NULL
        "getArray(%L)?.array?"
      } else {
        "getArray(%L).array"
      }
      CodeBlock.of(
        """
        $getArray.let {
          @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
          it as %T
        }
        """.trimIndent(),
        index,
        arrayTypeName.copy(nullable = false),
      )
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
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, java.util.UUID::class.asTypeName()) },
  ),
  LOCAL_DATE(
    LocalDate::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, LocalDate::class.asTypeName()) },
  ),
  LOCAL_TIME(
    LocalTime::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, LocalTime::class.asTypeName()) },
  ),
  OFFSET_TIME(
    OffsetTime::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, OffsetTime::class.asTypeName()) },
  ),
  LOCAL_DATE_TIME(
    LocalDateTime::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, LocalDateTime::class.asTypeName()) },
  ),
  OFFSET_DATE_TIME(
    OffsetDateTime::class,
    { index, parameterName -> CodeBlock.of("setObject(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getObject(%L, %T::class.java)", index, OffsetDateTime::class.asTypeName()) },
  ),
  BYTE_ARRAY(
    ByteArray::class,
    { index, parameterName -> CodeBlock.of("setBytes(%L, %L)", index, parameterName) },
    { index -> CodeBlock.of("getBytes(%L)", index) },
  ),
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
 */
internal data class JdbcTypeInfo(
  val getterName: String,
  val setterName: String,
  val isPrimitive: Boolean,
  val sqlTypeConstant: String,
  val useSqlTypeHint: Boolean = false,
  val kotlinType: TypeName,
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
            "%N(%L, %N.encode(%L))",
            jdbcTypeInfo.setterName,
            index,
            adapterPropertyName,
            parameterName,
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
            "%L?.let { %N(%L, %N.encode(it)) } ?: setNull(%L, %T.%N)",
            parameterName,
            jdbcTypeInfo.setterName,
            index,
            adapterPropertyName,
            index,
            Types::class,
            jdbcTypeInfo.sqlTypeConstant,
          )
        }
      }
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = if (notNull) {
      { index -> CodeBlock.of("%N.decode(%N(%L))", adapterPropertyName, jdbcTypeInfo.getterName, index) }
    } else if (jdbcTypeInfo.isPrimitive) {
      { index ->
        CodeBlock.of(
          "%N(%L).takeUnless { wasNull() }?.let { %N.decode(it) }",
          jdbcTypeInfo.getterName,
          index,
          adapterPropertyName,
        )
      }
    } else {
      { index ->
        CodeBlock.of(
          "%N(%L)?.let { %N.decode(it) }",
          jdbcTypeInfo.getterName,
          index,
          adapterPropertyName,
        )
      }
    }
}

/**
 * [SqlMappable] for an array column whose elements use a `norm.ColumnAdapter`.
 *
 * Unlike [ArrayTypeDecorator] (which does a direct `UNCHECKED_CAST` from the JDBC array to the
 * final Kotlin array type), this class generates per-element adapter decode/encode calls because
 * the JDBC wire type (`String[]` for enums, `Integer[]` for int4 domains) differs from the
 * application type (`Array<Mood?>`, `Array<PositiveInteger?>`).
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
