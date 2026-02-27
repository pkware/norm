package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
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
 * JDBC method metadata for a domain's base type, used to generate the correct
 * `ResultSet` and `PreparedStatement` calls for reading and writing domain values.
 *
 * @property getterName The `ResultSet` getter method name (e.g., `"getString"`, `"getInt"`).
 * @property setterName The `PreparedStatement` setter method name (e.g., `"setString"`, `"setInt"`).
 * @property isPrimitive Whether the JDBC getter returns a JVM primitive (`true` for `Int`, `Short`,
 *   `Long`, `Float`, `Double`, `Boolean`). Primitives require a `wasNull()` check for nullable columns
 *   because JDBC returns `0`/`false` instead of `null`.
 * @property sqlTypeConstant The field name on [java.sql.Types] for `setNull()` calls (e.g., `"VARCHAR"`, `"INTEGER"`).
 */
internal data class DomainBaseTypeInfo(
  val getterName: String,
  val setterName: String,
  val isPrimitive: Boolean,
  val sqlTypeConstant: String,
)

/**
 * [SqlMappable] for a PostgreSQL domain column that uses a generated [ColumnAdapter][norm.ColumnAdapter]
 * for encode/decode.
 *
 * Generated types don't exist at generator time, so [klass] is not available — use [typeName] instead.
 *
 * The generated read/write code references an adapter property (e.g., `emailAdapter`) on the enclosing
 * `PostgresQueries` class, which is visible inside the `ResultSet`/`PreparedStatement` receiver lambdas
 * via Kotlin closure scoping.
 *
 * @param domainTypeName The KotlinPoet [ClassName] of the generated value class (e.g., `example.Email`).
 * @param adapterPropertyName The property name on `PostgresQueries` for the adapter (e.g., `"emailAdapter"`).
 * @param notNull Whether the column is `NOT NULL`.
 * @param baseTypeInfo JDBC method info for the domain's base type.
 */
internal class DomainTypeSqlMappable(
  private val domainTypeName: ClassName,
  private val adapterPropertyName: String,
  private val notNull: Boolean,
  private val baseTypeInfo: DomainBaseTypeInfo,
) : SqlMappable {

  override val klass: KClass<*>
    get() = throw UnsupportedOperationException(
      "Generated domain type $domainTypeName has no KClass at generator time. Use typeName instead.",
    )

  override val typeName: TypeName
    get() = domainTypeName

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = if (notNull) {
      { index, parameterName ->
        CodeBlock.of(
          "%N(%L, %N.encode(%L))",
          baseTypeInfo.setterName,
          index,
          adapterPropertyName,
          parameterName,
        )
      }
    } else {
      { index, parameterName ->
        CodeBlock.of(
          "%L?.let { %N(%L, %N.encode(it)) } ?: setNull(%L, %T.%N)",
          parameterName,
          baseTypeInfo.setterName,
          index,
          adapterPropertyName,
          index,
          Types::class,
          baseTypeInfo.sqlTypeConstant,
        )
      }
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = if (notNull) {
      if (baseTypeInfo.isPrimitive) {
        { index -> CodeBlock.of("%N.decode(%N(%L))", adapterPropertyName, baseTypeInfo.getterName, index) }
      } else {
        { index -> CodeBlock.of("%N.decode(%N(%L))", adapterPropertyName, baseTypeInfo.getterName, index) }
      }
    } else {
      if (baseTypeInfo.isPrimitive) {
        { index ->
          CodeBlock.of(
            "%N(%L).takeUnless { wasNull() }?.let { %N.decode(it) }",
            baseTypeInfo.getterName,
            index,
            adapterPropertyName,
          )
        }
      } else {
        { index ->
          CodeBlock.of(
            "%N(%L)?.let { %N.decode(it) }",
            baseTypeInfo.getterName,
            index,
            adapterPropertyName,
          )
        }
      }
    }
}

/**
 * [SqlMappable] for a PostgreSQL enum column that uses a generated [ColumnAdapter][norm.ColumnAdapter]
 * for encode/decode.
 *
 * Generated types don't exist at generator time, so [klass] is not available — use [typeName] instead.
 *
 * The generated read/write code references an adapter property (e.g., `moodAdapter`) on the enclosing
 * `PostgresQueries` class, which is visible inside the `ResultSet`/`PreparedStatement` receiver lambdas
 * via Kotlin closure scoping.
 *
 * @param enumTypeName The KotlinPoet [ClassName] of the generated Kotlin enum (e.g., `example.Mood`).
 * @param adapterPropertyName The property name on `PostgresQueries` for the adapter (e.g., `"moodAdapter"`).
 * @param notNull Whether the column is `NOT NULL`.
 */
internal class EnumTypeSqlMappable(
  private val enumTypeName: ClassName,
  private val adapterPropertyName: String,
  private val notNull: Boolean,
) : SqlMappable {

  override val klass: KClass<*>
    get() = throw UnsupportedOperationException(
      "Generated enum type $enumTypeName has no KClass at generator time. Use typeName instead.",
    )

  override val typeName: TypeName
    get() = enumTypeName

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = if (notNull) {
      { index, parameterName ->
        CodeBlock.of("setString(%L, %N.encode(%L))", index, adapterPropertyName, parameterName)
      }
    } else {
      { index, parameterName ->
        CodeBlock.of(
          "%L?.let { setString(%L, %N.encode(it)) } ?: setNull(%L, %T.VARCHAR)",
          parameterName,
          index,
          adapterPropertyName,
          index,
          Types::class,
        )
      }
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = if (notNull) {
      { index -> CodeBlock.of("%N.decode(getString(%L))", adapterPropertyName, index) }
    } else {
      { index -> CodeBlock.of("getString(%L)?.let { %N.decode(it) }", index, adapterPropertyName) }
    }
}
