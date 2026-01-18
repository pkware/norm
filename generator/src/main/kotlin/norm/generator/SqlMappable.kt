package norm.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import java.math.BigDecimal
import java.sql.Blob
import java.sql.ResultSet
import java.sql.Statement
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
    get() = delegate.statementAction
  override val resultSetAction: (index: Int) -> CodeBlock
    get() = { CodeBlock.of("%L.takeUnless { wasNull() }", delegate.resultSetAction(it)) }
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
