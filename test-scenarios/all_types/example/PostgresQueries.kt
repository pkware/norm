package example

import java.math.BigDecimal
import java.sql.Blob
import java.sql.ResultSet
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
import norm.NormDriver
import norm.Query
import norm.RealTransacter

public class PostgresQueries(
  driver: NormDriver,
) : RealTransacter(driver),
    Queries {
  private fun <T : Any, R> all(mapper: (
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
  ) -> T, block: (String, ResultSet.() -> T) -> R): R {
    val sql =
        "SELECT smallserial_type, serial2_type, pg_serial2_type, serial_type, serial4_type, pg_serial4_type, bigserial_type, serial8_type, pg_serial8_type, smallint_type, int2_type, pg_int2_type, integer_type, int_type, int4_type, pg_int4_type, bigint_type, int8_type, pg_int8_type, real_type, float4_type, pg_float4_type, float_type, double_type, float8_type, pg_float8_type, numeric_type, pg_numeric_type, bool_type, pg_bool_type, jsonb_type, blob_type, text_type, varchar_type, pg_varchar_type, bpchar_type, pg_bpchar_type, string_type FROM type"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getShort(1).takeUnless { wasNull() },
        getShort(2),
        getShort(3).takeUnless { wasNull() },
        getInt(4).takeUnless { wasNull() },
        getInt(5),
        getInt(6).takeUnless { wasNull() },
        getLong(7).takeUnless { wasNull() },
        getLong(8),
        getLong(9).takeUnless { wasNull() },
        getShort(10).takeUnless { wasNull() },
        getShort(11),
        getShort(12).takeUnless { wasNull() },
        getInt(13).takeUnless { wasNull() },
        getInt(14).takeUnless { wasNull() },
        getInt(15),
        getInt(16).takeUnless { wasNull() },
        getLong(17).takeUnless { wasNull() },
        getLong(18),
        getLong(19).takeUnless { wasNull() },
        getFloat(20).takeUnless { wasNull() },
        getFloat(21),
        getFloat(22).takeUnless { wasNull() },
        getDouble(23).takeUnless { wasNull() },
        getDouble(24).takeUnless { wasNull() },
        getDouble(25),
        getDouble(26).takeUnless { wasNull() },
        getBigDecimal(27),
        getBigDecimal(28),
        getBoolean(29).takeUnless { wasNull() },
        getBoolean(30).takeUnless { wasNull() },
        getString(31),
        getBlob(32),
        getString(33),
        getString(34),
        getString(35),
        getString(36),
        getString(37),
        getString(38),
      )
    }
    return block(sql, rowReader)
  }

  override fun <T : Any> all(mapper: (
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
  ) -> T): Many<T> = all(mapper, driver::queryMany)

  override fun <T : Any> allDynamically(mapper: (
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
  ) -> T): Query<T> = all(mapper, driver::dynamic)

  @Throws(SQLException::class)
  override fun <T : Any> single(mapper: (string_type: String) -> T): T {
    val sql = "SELECT string_type FROM type"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
      )
    }
    return driver.queryOne(sql, rowReader)
  }
}
