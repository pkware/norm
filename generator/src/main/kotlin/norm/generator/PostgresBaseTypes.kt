// PostgresBaseType is the only top-level class in this file, but the file also holds
// POSTGRES_BASE_TYPES and the functions that read it (resolveWireCodec,
// postgresArrayElementTypeName) — deliberately, per this file's role as the single home for
// Postgres base-type mapping, not a naming slip.
@file:Suppress("MatchingDeclarationName")

package norm.generator

import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import java.math.BigDecimal
import java.sql.Blob
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.util.UUID

/**
 * One Postgres base type Norm maps to Kotlin, keyed in [POSTGRES_BASE_TYPES] by its canonical or
 * SQL-standard spelling.
 *
 * @property codec Wire-level JDBC access for this type, shared by a plain (adapterless) column's
 *   [ScalarSqlMappable] and by the same type behind a `ColumnAdapter` (an enum, a domain, or a
 *   user type mapping).
 */
internal class PostgresBaseType(val codec: WireCodec)

/**
 * Every canonical Postgres base type name [TypeRepository.resolveBaseType] accepts, keyed by name
 * (after stripping a `pg_catalog.` qualification — see [TypeRepository.resolveBaseType]).
 *
 * Includes the `serial`/`smallserial`/`bigserial` pseudo-types even though Postgres rejects
 * `CREATE DOMAIN ... AS serial` outright (`type "serial" does not exist` on a live server; a
 * domain's base is always a real, registered `pg_type`, so `domain.baseType` can never actually be
 * one of these), and even though [resolveWireCodec]'s only other callers
 * ([TypeRepository.buildUserConfiguredMappable]'s user-configured type mappings) also only ever see
 * the real, JDBC-reported type name, never a serial alias: a plain column still needs
 * [PostgresBaseType.codec] for one, so the row belongs here regardless.
 *
 * [register]'s `check` guards against a name repeated across two calls silently overwriting the
 * earlier row — `vararg` alone would let that pass unnoticed.
 */
internal val POSTGRES_BASE_TYPES: Map<String, PostgresBaseType> = buildMap {
  fun register(codec: WireCodec, vararg names: String) {
    for (name in names) {
      check(put(name, PostgresBaseType(codec)) == null) { "duplicate base type name: $name" }
    }
  }

  register(
    PrimitiveCodec(Short::class.asTypeName(), "Short", "SMALLINT"),
    "smallserial",
    "serial2",
    "smallint",
    "int2",
  )

  register(
    PrimitiveCodec(Int::class.asTypeName(), "Int", "INTEGER"),
    "serial",
    "serial4",
    "integer",
    "int",
    "int4",
  )

  register(
    PrimitiveCodec(Long::class.asTypeName(), "Long", "BIGINT"),
    "bigserial",
    "serial8",
    "bigint",
    "int8",
  )

  register(
    PrimitiveCodec(Float::class.asTypeName(), "Float", "REAL"),
    "real",
    "float4",
  )

  register(
    PrimitiveCodec(Double::class.asTypeName(), "Double", "DOUBLE"),
    "float",
    "double precision",
    "float8",
  )

  register(
    PrimitiveCodec(Boolean::class.asTypeName(), "Boolean", "BOOLEAN"),
    "bool",
    "boolean",
  )

  register(
    ObjectGetterCodec(BigDecimal::class.asTypeName(), "getBigDecimal", "setBigDecimal", "NUMERIC"),
    "numeric",
  )

  // json and jsonb require setObject(..., Types.OTHER): Postgres JDBC rejects setString() for both
  // in prepared statements, just as it does for enum columns. TypeRepository's ENUM_CODEC reuses
  // this exact codec, so the binding for a plain json/jsonb column and for an enum column can never
  // drift apart.
  register(
    TypesOtherCodec(String::class.asTypeName(), "getString", "OTHER"),
    "json",
    "jsonb",
  )

  register(
    ObjectGetterCodec(String::class.asTypeName(), "getString", "setString", "VARCHAR"),
    "text",
    "varchar",
    "bpchar",
    "string",
  )

  // Scalar oid maps to Blob: pgjdbc's setBlob() creates a Postgres large object and stores its oid,
  // the standard large-object convention, via the plain named getBlob()/setBlob() methods (no
  // class-hint or Types constant needed). oid[] does not share this mapping (see
  // TypeRepository.tryResolveStandardType) because an array of large-object handles has no coherent
  // JDBC semantics, and real-world oid[] columns hold plain catalog identifiers, not large objects.
  register(
    ObjectGetterCodec(Blob::class.asTypeName(), "getBlob", "setBlob", "BLOB"),
    "oid",
  )

  // java.sql.ResultSet.getBytes/PreparedStatement.setBytes are plain named methods for bytea,
  // needing no class-hint.
  register(
    ObjectGetterCodec(ByteArray::class.asTypeName(), "getBytes", "setBytes", "BINARY"),
    "bytea",
  )

  // pgjdbc's plain getObject(int) returns java.sql.Date/Time/Timestamp for date/time/timetz/
  // timestamp columns, not the java.time type, so the read needs the class-qualified
  // getObject(int, Class) overload (ClassHintedObjectCodec). The write side needs no such
  // qualification: PgPreparedStatement.setObject(int, Object) already dispatches on the runtime
  // type of a LocalDate/LocalTime/OffsetTime/LocalDateTime/OffsetDateTime argument directly
  // (pgjdbc 42.7.13's source).
  register(
    ClassHintedObjectCodec(LocalDate::class.asClassName(), "DATE"),
    "date",
  )

  register(
    ClassHintedObjectCodec(LocalTime::class.asClassName(), "TIME"),
    "time",
  )

  register(
    ClassHintedObjectCodec(OffsetTime::class.asClassName(), "TIME_WITH_TIMEZONE"),
    "timetz",
  )

  register(
    ClassHintedObjectCodec(LocalDateTime::class.asClassName(), "TIMESTAMP"),
    "timestamp",
  )

  // InstantViaOffsetDateTimeCodec's wire representation is OffsetDateTime (read via the
  // class-qualified getObject, written via plain setObject — both checked against pgjdbc's source
  // the same way as the other java.time entries above), but the Kotlin representation is Instant,
  // via a `.toInstant()`/`OffsetDateTime.ofInstant(...)` conversion — see
  // InstantViaOffsetDateTimeCodec's KDoc.
  register(
    InstantViaOffsetDateTimeCodec,
    "timestamptz",
  )

  // java.sql.ResultSet.getObject(int) is declared to return Object, so a bare getObject(index) call
  // is statically Any in Kotlin regardless of what pgjdbc returns at runtime — PgResultSet's
  // internalGetObject does special-case the Postgres "uuid" type by name and hands back a
  // java.util.UUID instance (per pgjdbc 42.7.13's source), but that's a runtime fact, not a static
  // type, and a `ColumnAdapter<Application, UUID>.decode` call requires a statically-typed UUID
  // argument. The class-qualified getObject(int, Class) overload (ClassHintedObjectCodec) fixes the
  // static type; pgjdbc's PgResultSet#getObject(int, Class<T>) explicitly special-cases
  // `type == UUID.class` by delegating to the same runtime read and casting, so this is safe.
  register(
    ClassHintedObjectCodec(UUID::class.asClassName(), "OTHER"),
    "uuid",
  )
}

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
 * Maps a Postgres base type name to its [WireCodec], or returns `null` if unsupported.
 *
 * Delegates to [POSTGRES_BASE_TYPES], the single source of truth for both a plain column's
 * [SqlMappable] and its wire-level [WireCodec] — a domain over any base type
 * [TypeRepository.resolveBaseType] itself supports (e.g. `CREATE DOMAIN d AS timestamptz`) always
 * resolves here too, since both come from the same row. [TypeRepository]'s domain resolution
 * chains through this function (see [TypeRepository.tryResolveDomainType] and
 * [domainKotlinBaseType][norm.generator.domainKotlinBaseType]); its `error()` calls are reachable
 * only for a base type [TypeRepository.resolveBaseType] itself does not support either (e.g. `xml`,
 * `interval`, `money` — Postgres allows a domain over any of these, but Norm has never mapped them
 * to a Kotlin type as a plain column type, so the same limitation applies to a domain built on
 * one). That failure is intentional: a clear, immediate `error()` naming the unsupported type is
 * preferable to silently guessing a mapping for a type Norm has no tested behavior for.
 */
internal fun resolveWireCodec(baseTypeName: String): WireCodec? = POSTGRES_BASE_TYPES[baseTypeName]?.codec
