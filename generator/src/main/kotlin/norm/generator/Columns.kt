package norm.generator

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.BOOLEAN_ARRAY
import com.squareup.kotlinpoet.DOUBLE_ARRAY
import com.squareup.kotlinpoet.FLOAT_ARRAY
import com.squareup.kotlinpoet.INT_ARRAY
import com.squareup.kotlinpoet.LONG_ARRAY
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.SHORT_ARRAY
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import plugin.Column

/**
 * Details on how to map this column's type between Java and SQL.
 */
internal val Column.mappableType: SqlMappable
  get() = when (val typeName = type?.name) {
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

    "blob" -> JdbcTypes.BLOB
    // TODO Handle additional types
//     			"bytea", "pg_catalog.bytea" -> ByteArray::class

    // Date and time mappings from https://jdbc.postgresql.org/documentation/head/java8-date-time.html
    "date", "pg_catalog.date" -> PostgresSupportedTypes.LOCAL_DATE
    "time", "pg_catalog.time" -> PostgresSupportedTypes.LOCAL_TIME
    "timetz", "pg_catalog.timetz" -> PostgresSupportedTypes.OFFSET_TIME
    "timestamp", "pg_catalog.timestamp" -> PostgresSupportedTypes.LOCAL_DATE_TIME
    "timestamptz", "pg_catalog.timestamptz" -> PostgresSupportedTypes.OFFSET_DATE_TIME

    "text", "varchar", "pg_catalog.varchar", "bpchar", "pg_catalog.bpchar", "string" -> JdbcTypes.STRING
    //
    // 			"uuid" -> UUID::class
    //
    // 			"void" -> Nothing::class
    // 			"any" -> Any::class
    else -> error("Postgres type $typeName is not mapped to a Kotlin type")
  }

/**
 * KotlinPoet [TypeName] to use to represent this column.
 *
 * Prefer this to [SqlMappable.typeName] as this contains additional column-specific information.
 */
internal val Column.typeName: TypeName
  get() {
    val type = mappableType.klass

    val typeName = if (is_array) {
      when (type) {
        Boolean::class -> BOOLEAN_ARRAY
        Short::class -> SHORT_ARRAY
        Int::class -> INT_ARRAY
        Long::class -> LONG_ARRAY
        Float::class -> FLOAT_ARRAY
        Double::class -> DOUBLE_ARRAY
        else -> ARRAY.parameterizedBy(type.asTypeName())
      }
    } else {
      type.asTypeName()
    }.copy(nullable = !not_null)

    return typeName
  }
