package norm.generator

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import plugin.Column
import kotlin.reflect.KClass

/**
 * Details on how to map this column's type between Java and SQL.
 */
internal val Column.mappableType: SqlMappable
  get() {
    val baseType = when (val typeName = type?.name) {
      "smallserial", "serial2", "pg_catalog.serial2" -> JdbcTypes.SHORT.decorateForNullable(not_null)
      "serial", "serial4", "pg_catalog.serial4" -> JdbcTypes.INT.decorateForNullable(not_null)
      "bigserial", "serial8", "pg_catalog.serial8" -> JdbcTypes.LONG.decorateForNullable(not_null)

      "smallint", "int2", "pg_catalog.int2" -> JdbcTypes.SHORT.decorateForNullable(not_null)
      "integer", "int", "int4", "pg_catalog.int4" -> JdbcTypes.INT.decorateForNullable(not_null)
      "bigint", "int8", "pg_catalog.int8" -> JdbcTypes.LONG.decorateForNullable(not_null)

      "real", "float4", "pg_catalog.float4" -> JdbcTypes.FLOAT.decorateForNullable(not_null)
      "float", "double precision", "float8", "pg_catalog.float8" -> JdbcTypes.DOUBLE.decorateForNullable(not_null)
      "numeric", "pg_catalog.numeric" -> JdbcTypes.BIG_DECIMAL

      "bool", "pg_catalog.bool" -> JdbcTypes.BOOLEAN.decorateForNullable(not_null)

      "jsonb" -> JdbcTypes.STRING

      "oid", "pg_catalog.oid" -> JdbcTypes.BLOB
      "bytea", "pg_catalog.bytea" -> PostgresSupportedTypes.BYTE_ARRAY

      // Date and time mappings from https://jdbc.postgresql.org/documentation/head/java8-date-time.html
      "date", "pg_catalog.date" -> PostgresSupportedTypes.LOCAL_DATE
      "time", "pg_catalog.time" -> PostgresSupportedTypes.LOCAL_TIME
      "timetz", "pg_catalog.timetz" -> PostgresSupportedTypes.OFFSET_TIME
      "timestamp", "pg_catalog.timestamp" -> PostgresSupportedTypes.LOCAL_DATE_TIME
      "timestamptz", "pg_catalog.timestamptz" -> PostgresSupportedTypes.OFFSET_DATE_TIME

      "text", "varchar", "pg_catalog.varchar", "bpchar", "pg_catalog.bpchar", "string" -> JdbcTypes.STRING

      "uuid", "pg_catalog.uuid" -> PostgresSupportedTypes.UUID

      // 			"void" -> Nothing::class
      // 			"any" -> Any::class
      else -> error("Postgres type $typeName for column $fullyQualifiedName is not mapped to a Kotlin type")
    }

    // Wrap in array decorator if this is an array column
    return if (is_array) {
      // Compute array type name without calling this.typeName (to avoid circular dependency)
      val arrayTypeName = wrapInArrayIfNeeded(baseType.klass)
      ArrayTypeDecorator(baseType, arrayTypeName)
    } else {
      baseType
    }
  }

/**
 * KotlinPoet [TypeName] to use to represent this column.
 *
 * Prefer this to [SqlMappable.typeName] as this contains additional column-specific information.
 */
internal val Column.typeName: TypeName
  get() = wrapInArrayIfNeeded(mappableType.klass)

internal val Column.fullyQualifiedName: String
  get() {
    val tableName = table?.name.orEmpty()
    return tableName + name
  }

private fun Column.wrapInArrayIfNeeded(type: KClass<*>): TypeName = if (is_array) {
  // PostgreSQL JDBC returns boxed arrays for all types. PostgreSQL arrays can contain NULL. The only way to prevent
  // that is with a CHECK constraint, but we don't have access to those. That could be a future improvement.
  ARRAY.parameterizedBy(type.asTypeName().copy(nullable = true))
} else {
  type.asTypeName()
}.copy(nullable = !not_null)
