package example

import java.math.BigDecimal
import java.sql.Blob
import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.Double
import kotlin.Float
import kotlin.Int
import kotlin.Long
import kotlin.Short
import kotlin.String
import kotlin.jvm.Throws
import norm.Many
import norm.Query
import norm.Transacter
import norm.inputValue

public interface Queries : Transacter {
  public fun <T : Any> all(mapper: (
    smallserial_type: Short?,
    serial2_type: Short,
    pg_serial2_type: Short?,
    serial_type: Int?,
    serial4_type: Int,
    pg_serial4_type: Int?,
    bigserial_type: Long?,
    serial8_type: Long,
    pg_serial8_type: Long?,
    smallint_type: Short?,
    int2_type: Short,
    pg_int2_type: Short?,
    integer_type: Int?,
    int_type: Int?,
    int4_type: Int,
    pg_int4_type: Int?,
    bigint_type: Long?,
    int8_type: Long,
    pg_int8_type: Long?,
    real_type: Float?,
    float4_type: Float,
    pg_float4_type: Float?,
    float_type: Double?,
    double_type: Double?,
    float8_type: Double,
    pg_float8_type: Double?,
    numeric_type: BigDecimal?,
    pg_numeric_type: BigDecimal?,
    bool_type: Boolean?,
    pg_bool_type: Boolean?,
    jsonb_type: String?,
    blob_type: Blob?,
    text_type: String?,
    varchar_type: String?,
    pg_varchar_type: String?,
    bpchar_type: String?,
    pg_bpchar_type: String?,
    string_type: String,
  ) -> T): Many<T>

  public fun all(): Many<Type> = all(::Type)

  public fun <T : Any> allDynamically(mapper: (
    smallserial_type: Short?,
    serial2_type: Short,
    pg_serial2_type: Short?,
    serial_type: Int?,
    serial4_type: Int,
    pg_serial4_type: Int?,
    bigserial_type: Long?,
    serial8_type: Long,
    pg_serial8_type: Long?,
    smallint_type: Short?,
    int2_type: Short,
    pg_int2_type: Short?,
    integer_type: Int?,
    int_type: Int?,
    int4_type: Int,
    pg_int4_type: Int?,
    bigint_type: Long?,
    int8_type: Long,
    pg_int8_type: Long?,
    real_type: Float?,
    float4_type: Float,
    pg_float4_type: Float?,
    float_type: Double?,
    double_type: Double?,
    float8_type: Double,
    pg_float8_type: Double?,
    numeric_type: BigDecimal?,
    pg_numeric_type: BigDecimal?,
    bool_type: Boolean?,
    pg_bool_type: Boolean?,
    jsonb_type: String?,
    blob_type: Blob?,
    text_type: String?,
    varchar_type: String?,
    pg_varchar_type: String?,
    bpchar_type: String?,
    pg_bpchar_type: String?,
    string_type: String,
  ) -> T): Query<T>

  public fun allDynamically(): Query<Type> = allDynamically(::Type)

  @Throws(SQLException::class)
  public fun <T : Any> single(mapper: (string_type: String) -> T): T

  @Throws(SQLException::class)
  public fun single(): String = single(::inputValue)
}
