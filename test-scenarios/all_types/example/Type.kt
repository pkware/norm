package example

import java.math.BigDecimal
import java.sql.Blob
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.util.UUID
import kotlin.Boolean
import kotlin.Double
import kotlin.Float
import kotlin.Int
import kotlin.Long
import kotlin.Short
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class Type(
  public val smallserial_type: Short?,
  public val serial2_type: Short,
  public val pg_serial2_type: Short?,
  public val serial_type: Int?,
  public val serial4_type: Int,
  public val pg_serial4_type: Int?,
  public val bigserial_type: Long?,
  public val serial8_type: Long,
  public val pg_serial8_type: Long?,
  public val smallint_type: Short?,
  public val int2_type: Short,
  public val pg_int2_type: Short?,
  public val integer_type: Int?,
  public val int_type: Int?,
  public val int4_type: Int,
  public val pg_int4_type: Int?,
  public val bigint_type: Long?,
  public val int8_type: Long,
  public val pg_int8_type: Long?,
  public val real_type: Float?,
  public val float4_type: Float,
  public val pg_float4_type: Float?,
  public val float_type: Double?,
  public val double_type: Double?,
  public val float8_type: Double,
  public val pg_float8_type: Double?,
  public val numeric_type: BigDecimal?,
  public val pg_numeric_type: BigDecimal?,
  public val bool_type: Boolean?,
  public val pg_bool_type: Boolean?,
  public val jsonb_type: String?,
  public val blob_type: Blob?,
  public val text_type: String?,
  public val varchar_type: String?,
  public val pg_varchar_type: String?,
  public val bpchar_type: String?,
  public val pg_bpchar_type: String?,
  public val string_type: String,
  public val date_type: LocalDate?,
  public val date_notnull_type: LocalDate,
  public val pg_date_type: LocalDate?,
  public val time_type: LocalTime?,
  public val time_notnull_type: LocalTime,
  public val pg_time_type: LocalTime?,
  public val timetz_type: OffsetTime?,
  public val timetz_notnull_type: OffsetTime,
  public val pg_timetz_type: OffsetTime?,
  public val timestamp_type: LocalDateTime?,
  public val timestamp_notnull_type: LocalDateTime,
  public val pg_timestamp_type: LocalDateTime?,
  public val timestamptz_type: OffsetDateTime?,
  public val timestamptz_notnull_type: OffsetDateTime,
  public val pg_timestamptz_type: OffsetDateTime?,
  public val uuid_type: UUID?,
  public val uuid_notnull_type: UUID,
  public val pg_uuid_type: UUID?,
)
