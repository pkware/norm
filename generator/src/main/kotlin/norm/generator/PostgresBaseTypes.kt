package norm.generator

import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import java.math.BigDecimal
import java.sql.Blob
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.util.UUID

/**
 * Every canonical Postgres base type name [TypeRepository.resolveBaseType] accepts, keyed by name
 * (after stripping a `pg_catalog.` qualification — see [TypeRepository.resolveBaseType]) and
 * mapped to the [SqlMappable] used for a plain (non-domain) column of that type.
 *
 * This is the single source of truth for "every type name resolveBaseType accepts": both
 * [TypeRepository.resolveBaseType] itself and [ColumnTypeMappingTest]'s domain-base-type-parity
 * sweep read from these exact keys, so a type added here without a matching [resolveJdbcTypeInfo]
 * entry fails that sweep immediately — see [resolveJdbcTypeInfo]'s KDoc for the invariant this
 * enforces between the two maps.
 *
 * Includes the `serial`/`smallserial`/`bigserial` pseudo-types even though Postgres rejects
 * `CREATE DOMAIN ... AS serial` outright (`type "serial" does not exist` on a live
 * server; a domain's base is always a real, registered `pg_type`, so `domain.baseType` can never
 * actually be one of these), and even though `resolveJdbcTypeInfo`'s only other callers
 * ([TypeRepository.buildUserConfiguredMappable]'s user-configured type mappings) also only ever
 * see the real, JDBC-reported type name, never a serial alias: keeping them out would make this
 * map a proper subset of `resolveBaseType`'s branches, silently reintroducing exactly the kind of
 * incomplete "should be identical" list this map exists to prevent.
 */
internal val BASE_TYPE_RESOLVERS: Map<String, (notNull: Boolean) -> SqlMappable> = mapOf(
  "smallserial" to { notNull: Boolean -> JdbcTypes.SHORT.decorateForNullable(notNull) },
  "serial2" to { notNull: Boolean -> JdbcTypes.SHORT.decorateForNullable(notNull) },
  "serial" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "serial4" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "bigserial" to { notNull: Boolean -> JdbcTypes.LONG.decorateForNullable(notNull) },
  "serial8" to { notNull: Boolean -> JdbcTypes.LONG.decorateForNullable(notNull) },

  "smallint" to { notNull: Boolean -> JdbcTypes.SHORT.decorateForNullable(notNull) },
  "int2" to { notNull: Boolean -> JdbcTypes.SHORT.decorateForNullable(notNull) },
  "integer" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "int" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "int4" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "bigint" to { notNull: Boolean -> JdbcTypes.LONG.decorateForNullable(notNull) },
  "int8" to { notNull: Boolean -> JdbcTypes.LONG.decorateForNullable(notNull) },

  "real" to { notNull: Boolean -> JdbcTypes.FLOAT.decorateForNullable(notNull) },
  "float4" to { notNull: Boolean -> JdbcTypes.FLOAT.decorateForNullable(notNull) },
  "float" to { notNull: Boolean -> JdbcTypes.DOUBLE.decorateForNullable(notNull) },
  "double precision" to { notNull: Boolean -> JdbcTypes.DOUBLE.decorateForNullable(notNull) },
  "float8" to { notNull: Boolean -> JdbcTypes.DOUBLE.decorateForNullable(notNull) },
  "numeric" to { _: Boolean -> JdbcTypes.BIG_DECIMAL },

  "bool" to { notNull: Boolean -> JdbcTypes.BOOLEAN.decorateForNullable(notNull) },
  "boolean" to { notNull: Boolean -> JdbcTypes.BOOLEAN.decorateForNullable(notNull) },

  // Not JdbcTypes.STRING: pgjdbc rejects setString() for json and jsonb columns.
  "json" to { notNull: Boolean -> JsonSqlMappable(notNull) },
  "jsonb" to { notNull: Boolean -> JsonSqlMappable(notNull) },

  // Scalar oid maps to Blob: pgjdbc's setBlob() creates a Postgres large object and stores its
  // oid, the standard large-object convention. oid[] does not share this mapping (see
  // tryResolveStandardType) because an array of large-object handles has no coherent JDBC
  // semantics, and real-world oid[] columns hold plain catalog identifiers, not large objects.
  "oid" to { _: Boolean -> JdbcTypes.BLOB },
  "bytea" to { _: Boolean -> PostgresSupportedTypes.BYTE_ARRAY },

  "date" to { _: Boolean -> PostgresSupportedTypes.LOCAL_DATE },
  "time" to { _: Boolean -> PostgresSupportedTypes.LOCAL_TIME },
  "timetz" to { _: Boolean -> PostgresSupportedTypes.OFFSET_TIME },
  "timestamp" to { _: Boolean -> PostgresSupportedTypes.LOCAL_DATE_TIME },
  "timestamptz" to { notNull: Boolean -> InstantSqlMappable(notNull) },

  "text" to { _: Boolean -> JdbcTypes.STRING },
  "varchar" to { _: Boolean -> JdbcTypes.STRING },
  "bpchar" to { _: Boolean -> JdbcTypes.STRING },
  "string" to { _: Boolean -> JdbcTypes.STRING },

  "uuid" to { _: Boolean -> PostgresSupportedTypes.UUID },
)

/**
 * Canonicalizes a Postgres type name for use as the element type of
 * [java.sql.Connection.createArrayOf].
 *
 * The driver appends `[]` to this name and looks the result up in `pg_type`, so it must be a
 * canonical `pg_type` name. [TypeRepository.resolveBaseType] additionally accepts SQL spellings
 * (`integer`, `boolean`, `double precision`) and `pg_catalog.`-qualified names; without folding
 * those here, `postgresArrayElementTypeName("integer")` would return `"integer"` verbatim and
 * `createArrayOf` would fail with `Unable to find server array type for provided name {0}`, since
 * `pg_type` has no row named `integer` — only `int4`.
 *
 * Every branch below was checked against a live PostgreSQL 17 server via
 * `SELECT typname FROM pg_type WHERE oid = to_regtype(?)`: every alias here resolves to the
 * canonical name on its right-hand side, and every `pg_catalog.`-qualified spelling of an
 * already-canonical name (e.g. `pg_catalog.uuid`, `pg_catalog.timestamptz`) resolves to itself —
 * confirming the universal `removePrefix` below is sufficient for those without a dedicated
 * branch. `pg_catalog.boolean` and `pg_catalog.integer` do not resolve on a live server (`boolean`
 * and `integer` are SQL-standard keyword aliases recognized only unqualified, not as schema-
 * qualified `pg_catalog` names) — but that combination can never actually reach this function:
 * JDBC's `TYPE_NAME`/`getColumnTypeName` always report the canonical, unqualified name.
 *
 * `serial` and its variants need no entry: Postgres has no serial array type, so a serial column
 * can never reach the array path.
 */
internal fun postgresArrayElementTypeName(typeName: String): String =
  when (val canonical = typeName.removePrefix("pg_catalog.")) {
    "smallint" -> "int2"
    "integer", "int" -> "int4"
    "bigint" -> "int8"
    "real" -> "float4"
    "double precision", "float" -> "float8"
    "boolean" -> "bool"
    "string" -> "text"
    else -> canonical
  }

/**
 * Maps a Postgres base type name to its [JdbcTypeInfo], or returns `null` if unsupported.
 *
 * Every key in [BASE_TYPE_RESOLVERS] has an entry here — [ColumnTypeMappingTest]'s domain-base-
 * type-parity sweep asserts this directly, rather than relying on the two lists being hand-kept in
 * sync, so a domain over any base type [TypeRepository.resolveBaseType] itself supports (e.g.
 * `CREATE DOMAIN d AS timestamptz`) always resolves here too: [TypeRepository]'s domain resolution
 * chains through this function (see [TypeRepository.tryResolveDomainType] and
 * [domainKotlinBaseType][norm.generator.domainKotlinBaseType]), and its `error()` calls are
 * reachable only for a base type [TypeRepository.resolveBaseType] itself does not support either
 * (e.g. `xml`, `interval`, `money` — Postgres allows a domain over any of these, but Norm has never
 * mapped them to a Kotlin type as a plain column type, so the same limitation applies to a domain
 * built on one). That failure is intentional: a clear, immediate `error()` naming the unsupported
 * type is preferable to silently guessing a mapping for a type Norm has no tested behavior for.
 *
 * Every getter/setter/Kotlin-type combination below matches [BASE_TYPE_RESOLVERS]'s NON-domain
 * mapping for the same key exactly — see [JdbcTypeInfo.getterClassHint] and
 * [JdbcTypeInfo.convertOffsetDateTimeToInstant] for the cases (`java.time` types, `uuid`, and
 * `timestamptz` specifically) where matching the non-domain path requires more than a plain
 * `getX`/`setX` method pair, each checked against pgjdbc 42.7.13's source rather than assumed.
 */
internal fun resolveJdbcTypeInfo(baseTypeName: String): JdbcTypeInfo? = when (baseTypeName) {
  "smallserial", "serial2", "smallint", "int2" ->
    JdbcTypeInfo("getShort", "setShort", true, "SMALLINT", kotlinType = Short::class.asTypeName())
  "serial", "serial4", "integer", "int", "int4" ->
    JdbcTypeInfo("getInt", "setInt", true, "INTEGER", kotlinType = Int::class.asTypeName())
  "bigserial", "serial8", "bigint", "int8" ->
    JdbcTypeInfo("getLong", "setLong", true, "BIGINT", kotlinType = Long::class.asTypeName())
  "real", "float4" ->
    JdbcTypeInfo("getFloat", "setFloat", true, "REAL", kotlinType = Float::class.asTypeName())
  "float", "double precision", "float8" ->
    JdbcTypeInfo("getDouble", "setDouble", true, "DOUBLE", kotlinType = Double::class.asTypeName())
  "bool", "boolean" ->
    JdbcTypeInfo("getBoolean", "setBoolean", true, "BOOLEAN", kotlinType = Boolean::class.asTypeName())
  "numeric" ->
    JdbcTypeInfo("getBigDecimal", "setBigDecimal", false, "NUMERIC", kotlinType = BigDecimal::class.asTypeName())
  // json and jsonb require setObject(..., Types.OTHER): Postgres JDBC rejects setString() for both
  // in prepared statements, just as it does for enum columns. Keep in sync with JsonSqlMappable,
  // which defines the same binding for plain (adapterless) json and jsonb columns.
  "json", "jsonb" ->
    JdbcTypeInfo(
      "getString",
      "setObject",
      false,
      "OTHER",
      useSqlTypeHint = true,
      kotlinType = String::class.asTypeName(),
    )
  "text", "varchar", "bpchar", "string" ->
    JdbcTypeInfo("getString", "setString", false, "VARCHAR", kotlinType = String::class.asTypeName())
  // Matches JdbcTypes.BLOB, the non-domain scalar mapping for oid (see BASE_TYPE_RESOLVERS):
  // pgjdbc's getBlob()/setBlob() are plain named methods, needing no class-hint or Types constant.
  "oid" ->
    JdbcTypeInfo("getBlob", "setBlob", false, "BLOB", kotlinType = Blob::class.asTypeName())
  // Matches PostgresSupportedTypes.BYTE_ARRAY: java.sql.ResultSet.getBytes/PreparedStatement.setBytes
  // are plain named methods for bytea, needing no class-hint.
  "bytea" ->
    JdbcTypeInfo("getBytes", "setBytes", false, "BINARY", kotlinType = ByteArray::class.asTypeName())
  // Matches PostgresSupportedTypes.LOCAL_DATE/LOCAL_TIME/OFFSET_TIME/LOCAL_DATE_TIME: pgjdbc's
  // plain getObject(int) returns java.sql.Date/Time/Timestamp for these columns, not the java.time
  // type, so the read needs the class-qualified getObject(int, Class) overload (getterClassHint).
  // The write side needs no such qualification: PgPreparedStatement.setObject(int, Object) already
  // dispatches on the runtime type of a LocalDate/LocalTime/OffsetTime/LocalDateTime/OffsetDateTime
  // argument directly (pgjdbc 42.7.13's source).
  "date" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "DATE",
      kotlinType = LocalDate::class.asTypeName(),
      getterClassHint = LocalDate::class.asClassName(),
    )
  "time" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "TIME",
      kotlinType = LocalTime::class.asTypeName(),
      getterClassHint = LocalTime::class.asClassName(),
    )
  "timetz" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "TIME_WITH_TIMEZONE",
      kotlinType = OffsetTime::class.asTypeName(),
      getterClassHint = OffsetTime::class.asClassName(),
    )
  "timestamp" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "TIMESTAMP",
      kotlinType = LocalDateTime::class.asTypeName(),
      getterClassHint = LocalDateTime::class.asClassName(),
    )
  // Matches InstantSqlMappable: the wire representation is OffsetDateTime (read via the
  // class-qualified getObject, written via plain setObject — both checked against pgjdbc's
  // source the same way as the other java.time entries above), but the Kotlin representation the
  // non-domain scalar path uses is Instant, via a `.toInstant()`/`OffsetDateTime.ofInstant(...)`
  // conversion — see JdbcTypeInfo.convertOffsetDateTimeToInstant's KDoc.
  "timestamptz" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "TIMESTAMP_WITH_TIMEZONE",
      kotlinType = Instant::class.asTypeName(),
      getterClassHint = OffsetDateTime::class.asClassName(),
      convertOffsetDateTimeToInstant = true,
    )
  // Matches PostgresSupportedTypes.UUID: java.sql.ResultSet.getObject(int) is declared to return
  // Object, so a bare getObject(index) call is statically Any in Kotlin regardless of what pgjdbc
  // returns at runtime — PgResultSet's internalGetObject does special-case the Postgres "uuid"
  // type by name and hands back a java.util.UUID instance (per pgjdbc 42.7.13's
  // source), but that's a runtime fact, not a static type, and ColumnAdapter<Application,
  // UUID>.decode requires a statically-typed UUID argument. The class-qualified
  // getObject(int, Class) overload (getterClassHint) fixes the static type; pgjdbc's
  // PgResultSet#getObject(int, Class<T>) explicitly special-cases `type == UUID.class` by
  // delegating to the same runtime read and casting, so this is safe.
  "uuid" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "OTHER",
      kotlinType = UUID::class.asTypeName(),
      getterClassHint = UUID::class.asClassName(),
    )
  else -> null
}
