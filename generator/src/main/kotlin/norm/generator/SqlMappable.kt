package norm.generator

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
   * KotlinPoet [TypeName] for the data.
   */
  val typeName: TypeName

  /**
   * Receiver action to call on a [Statement][java.sql.Statement] when mapping the data from Java
   * to SQL.
   */
  val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock

  /**
   * Receiver action to call on a [ResultSet][java.sql.ResultSet] when mapping the data from SQL
   * to Java.
   */
  val resultSetAction: (index: Int) -> CodeBlock
}

/**
 * Wire-level JDBC access for a Postgres base type: which `ResultSet`/`PreparedStatement` methods
 * read and write it, and the Kotlin type JDBC delivers it as. Used both for a plain (adapterless)
 * column ([ScalarSqlMappable]) and for the same type behind a `norm.ColumnAdapter`
 * ([AdaptedTypeSqlMappable]) — a domain, an enum, or a user-configured type mapping.
 */
internal interface WireCodec {

  /**
   * The non-null Kotlin type JDBC delivers this value as (e.g. `String` for text/varchar, `Int`
   * for int4).
   */
  val kotlinType: TypeName

  /**
   * Reads the value at [index]. When [nullable], the rendered expression itself handles a SQL
   * `NULL` (returning Kotlin `null`); when not, it assumes the column is `NOT NULL`.
   */
  fun read(index: Int, nullable: Boolean): CodeBlock

  /**
   * Writes a non-null [value] at [index].
   */
  fun write(index: Int, value: CodeBlock): CodeBlock

  /**
   * Writes SQL `NULL` at [index].
   */
  fun writeNull(index: Int): CodeBlock

  /**
   * Writes a value at [index] that may be `null` at runtime.
   *
   * Defaults to [write]: most codecs' JDBC setter already accepts and forwards a `null` argument
   * correctly (`setObject`, or a plain named setter whose Postgres-side coercion handles `NULL`),
   * so no extra branching is needed. [PrimitiveCodec] overrides this — a JVM primitive setter
   * cannot accept `null` at all — and is the only override; changing any other codec's default
   * here would regenerate goldens for `text`, `numeric`, `oid`, `bytea`, `date`, `time`, `timetz`,
   * `timestamp`, `uuid`, `json`, and `jsonb` plain nullable columns.
   */
  fun writeNullable(index: Int, value: CodeBlock): CodeBlock = write(index, value)
}

/**
 * [WireCodec] for a JVM primitive delivered through a named getter/setter pair (`getInt`/`setInt`,
 * etc.). JDBC getters for primitives return `0`/`false` rather than `null` for a SQL `NULL`, so a
 * nullable read needs a `wasNull()` check; a JVM primitive setter cannot accept `null` at all, so a
 * nullable write goes through a `norm.set<X>` runtime extension (which accepts a nullable argument
 * and calls `setNull` itself when it is `null`) instead of the plain setter.
 *
 * @param methodName The JDBC method name suffix shared by the getter/setter pair (e.g. `"Int"` for
 *   `getInt`/`setInt`).
 * @param sqlTypeConstant The field name on [java.sql.Types] for `setNull()` calls (e.g.
 *   `"INTEGER"`).
 */
internal class PrimitiveCodec(
  override val kotlinType: TypeName,
  private val methodName: String,
  private val sqlTypeConstant: String,
) : WireCodec {

  override fun read(index: Int, nullable: Boolean): CodeBlock {
    val get = CodeBlock.of("%N(%L)", "get$methodName", index)
    return if (nullable) CodeBlock.of("%L.takeUnless { wasNull() }", get) else get
  }

  override fun write(index: Int, value: CodeBlock): CodeBlock =
    CodeBlock.of("%N(%L, %L)", "set$methodName", index, value)

  override fun writeNull(index: Int): CodeBlock =
    CodeBlock.of("setNull(%L, %T.%N)", index, Types::class, sqlTypeConstant)

  override fun writeNullable(index: Int, value: CodeBlock): CodeBlock {
    val member = MemberName("norm", "set$methodName", isExtension = true)
    return CodeBlock.of("%M(%L, %L)", member, index, value)
  }
}

/**
 * [WireCodec] for a non-primitive type delivered through a named getter/setter pair
 * (`getString`/`setString`, `getBigDecimal`/`setBigDecimal`, `getBlob`/`setBlob`,
 * `getBytes`/`setBytes`) whose declared return/parameter type is already the wire type, and whose
 * setter already accepts and forwards `null` correctly. Neither read nor write branches on
 * nullability: the getter returns Kotlin `null` for a SQL `NULL` without a `wasNull()` check, and
 * the setter accepts a nullable argument directly.
 *
 * @param sqlTypeConstant The field name on [java.sql.Types] for `setNull()` calls (e.g.
 *   `"VARCHAR"`).
 */
internal class ObjectGetterCodec(
  override val kotlinType: TypeName,
  private val getterName: String,
  private val setterName: String,
  private val sqlTypeConstant: String,
) : WireCodec {

  override fun read(index: Int, nullable: Boolean): CodeBlock = CodeBlock.of("%N(%L)", getterName, index)

  override fun write(index: Int, value: CodeBlock): CodeBlock = CodeBlock.of("%N(%L, %L)", setterName, index, value)

  override fun writeNull(index: Int): CodeBlock =
    CodeBlock.of("setNull(%L, %T.%N)", index, Types::class, sqlTypeConstant)
}

/**
 * [WireCodec] for a type bound with `setObject(index, value, Types.OTHER)` rather than a named
 * setter — required for Postgres custom/coercion-sensitive types (`json`, `jsonb`, enums) where
 * the JDBC driver refuses to coerce a `VARCHAR` binding; `Types.OTHER` bypasses the driver's type
 * enforcement and lets Postgres perform the coercion itself. `setObject(index, null, targetSqlType)`
 * already delegates to `setNull(index, targetSqlType)`, so, like [ObjectGetterCodec], neither read
 * nor write branches on nullability.
 *
 * @param getterName The `ResultSet` getter method name (always `"getString"` for this codec's
 *   current uses).
 * @param sqlTypeConstant The field name on [java.sql.Types] used both for the `setObject` hint and
 *   for `setNull()` calls (always `"OTHER"` for this codec's current uses).
 */
internal class TypesOtherCodec(
  override val kotlinType: TypeName,
  private val getterName: String,
  private val sqlTypeConstant: String,
) : WireCodec {

  override fun read(index: Int, nullable: Boolean): CodeBlock = CodeBlock.of("%N(%L)", getterName, index)

  override fun write(index: Int, value: CodeBlock): CodeBlock =
    CodeBlock.of("setObject(%L, %L, %T.%N)", index, value, Types::class, sqlTypeConstant)

  override fun writeNull(index: Int): CodeBlock =
    CodeBlock.of("setNull(%L, %T.%N)", index, Types::class, sqlTypeConstant)
}

/**
 * [WireCodec] for a type whose read needs the class-qualified `getObject(index, X::class.java)`
 * overload rather than a named getter — required whenever the wire type has no dedicated JDBC
 * getter: `java.sql.ResultSet.getObject(int)` is declared to return `Object`, so a bare
 * `getObject(index)` call is statically `Any` in Kotlin no matter what concrete type the driver
 * returns at runtime. Covers the `java.time` types (`LocalDate`, `LocalTime`, `OffsetTime`,
 * `LocalDateTime`), where pgjdbc's plain `getObject(int)` returns the legacy
 * `java.sql.Date`/`Time`/`Timestamp` even at runtime, and `uuid`, where pgjdbc's plain
 * `getObject(int)` does return a `java.util.UUID` at runtime (`PgResultSet.internalGetObject`
 * special-cases the Postgres `uuid` type by name) but the static type is still `Any` — the class
 * hint is required in both cases, for different reasons (pgjdbc 42.7.13's
 * `PgResultSet.getObject(int, Class)` special-cases each of these classes explicitly).
 *
 * The write side needs no such qualification: `PgPreparedStatement.setObject(int, Object)` already
 * dispatches on the runtime type of a `LocalDate`/`LocalTime`/`OffsetTime`/`LocalDateTime`/`UUID`
 * argument directly (pgjdbc 42.7.13's source), so `write` is a plain `setObject(index, value)`.
 * Like [ObjectGetterCodec], neither read nor write branches on nullability.
 *
 * @param getterClassHint The class passed to `getObject(index, X::class.java)`; also this codec's
 *   [kotlinType], since the wire and Kotlin representations are the same type for every use of
 *   this codec.
 * @param sqlTypeConstant The field name on [java.sql.Types] for `setNull()` calls (e.g. `"DATE"`,
 *   `"OTHER"` for `uuid`).
 */
internal class ClassHintedObjectCodec(private val getterClassHint: ClassName, private val sqlTypeConstant: String) :
  WireCodec {

  override val kotlinType: TypeName = getterClassHint

  override fun read(index: Int, nullable: Boolean): CodeBlock =
    CodeBlock.of("getObject(%L, %T::class.java)", index, getterClassHint)

  override fun write(index: Int, value: CodeBlock): CodeBlock = CodeBlock.of("setObject(%L, %L)", index, value)

  override fun writeNull(index: Int): CodeBlock =
    CodeBlock.of("setNull(%L, %T.%N)", index, Types::class, sqlTypeConstant)
}

/**
 * [WireCodec] for `timestamptz`, whose Kotlin representation ([Instant]) differs from every
 * `ResultSet`/`PreparedStatement` call's own wire representation ([OffsetDateTime]) — every other
 * codec's wire and Kotlin representations are the same type.
 *
 * pgjdbc does not support `getObject(i, Instant::class.java)`, so reads go through
 * [OffsetDateTime] and convert via `.toInstant()`. Writes convert via
 * `OffsetDateTime.ofInstant(value, ZoneOffset.UTC)` before binding.
 *
 * Unlike [ClassHintedObjectCodec], this requires nullable awareness on both sides: the `.toInstant()`
 * chain on a `null` [OffsetDateTime] read would NPE unless guarded by a safe call, and the JVM
 * `OffsetDateTime.ofInstant(value, ...)` call would NPE on a `null` [Instant] write unless it takes
 * the `writeNullable` `?.let` branch instead.
 */
internal object InstantViaOffsetDateTimeCodec : WireCodec {

  override val kotlinType: TypeName = Instant::class.asTypeName()

  override fun read(index: Int, nullable: Boolean): CodeBlock {
    val raw = CodeBlock.of("getObject(%L, %T::class.java)", index, OffsetDateTime::class)
    return if (nullable) CodeBlock.of("%L?.toInstant()", raw) else CodeBlock.of("%L.toInstant()", raw)
  }

  override fun write(index: Int, value: CodeBlock): CodeBlock =
    CodeBlock.of("setObject(%L, %T.ofInstant(%L, %T.UTC))", index, OffsetDateTime::class, value, ZoneOffset::class)

  override fun writeNull(index: Int): CodeBlock =
    CodeBlock.of("setNull(%L, %T.TIMESTAMP_WITH_TIMEZONE)", index, Types::class)

  override fun writeNullable(index: Int, value: CodeBlock): CodeBlock =
    CodeBlock.of("%L?.let { %L } ?: %L", value, write(index, CodeBlock.of("it")), writeNull(index))
}

/**
 * [SqlMappable] for a plain (adapterless) column of a Postgres base type, built from its
 * [WireCodec].
 *
 * @param notNull Whether the column is `NOT NULL`. Controls [typeName] nullability and which of
 *   [WireCodec.write]/[WireCodec.writeNullable] the write side uses.
 */
internal class ScalarSqlMappable(private val codec: WireCodec, private val notNull: Boolean) : SqlMappable {

  override val typeName: TypeName
    get() = codec.kotlinType.copy(nullable = !notNull)

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = { index, parameterName ->
      if (notNull) codec.write(index, parameterName) else codec.writeNullable(index, parameterName)
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = { index -> codec.read(index, !notNull) }
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
 * [SqlMappable] for a column that uses a `norm.ColumnAdapter` for encode/decode.
 *
 * Covers auto-generated adapters (enums, domains) and user-configured adapters. The adapter's
 * wire type is described by [codec], which determines the JDBC getter/setter methods.
 *
 * Generated types don't exist at generator time, so there is no [kotlin.reflect.KClass] to expose
 * — [typeName] is the only way to describe the type.
 *
 * The generated read/write code references an adapter property (e.g., `emailAdapter`) on the enclosing
 * `PostgresQueries` class, which is visible inside the `ResultSet`/`PreparedStatement` receiver lambdas
 * via Kotlin closure scoping.
 *
 * @param applicationTypeName The KotlinPoet [TypeName] of the application type (e.g., `example.Email`,
 *   or a parameterized type like `kotlin.collections.Map<kotlin.String, kotlin.Any?>`).
 * @param adapterPropertyName The property name on `PostgresQueries` for the adapter (e.g., `"emailAdapter"`).
 * @param notNull Whether the column is `NOT NULL`.
 * @param codec Wire-level access for the adapter's wire type.
 */
internal class AdaptedTypeSqlMappable(
  private val applicationTypeName: TypeName,
  private val adapterPropertyName: String,
  private val notNull: Boolean,
  private val codec: WireCodec,
) : SqlMappable {

  override val typeName: TypeName
    get() = applicationTypeName

  override val statementAction: (index: Int, parameterName: CodeBlock) -> CodeBlock
    get() = if (notNull) {
      { index, parameterName -> codec.write(index, encode(parameterName)) }
    } else {
      { index, parameterName ->
        CodeBlock.of(
          "%L?.let { %L } ?: %L",
          parameterName,
          codec.write(index, encode(CodeBlock.of("it"))),
          codec.writeNull(index),
        )
      }
    }

  override val resultSetAction: (index: Int) -> CodeBlock
    get() = if (notNull) {
      { index -> CodeBlock.of("%N.decode(%L)", adapterPropertyName, codec.read(index, false)) }
    } else {
      { index ->
        CodeBlock.of("%L?.let { %N.decode(it) }", codec.read(index, true), adapterPropertyName)
      }
    }

  /**
   * The encoded, wire-ready form of [valueExpression] (an already-non-null Kotlin value of
   * [applicationTypeName]'s underlying domain/adapter base type): `adapter.encode(value)`.
   */
  private fun encode(valueExpression: CodeBlock): CodeBlock =
    CodeBlock.of("%N.encode(%L)", adapterPropertyName, valueExpression)
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
