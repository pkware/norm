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
    smallserialtype: Short?,
    serial2type: Short?,
    pgserial2type: Short?,
    serialtype: Int?,
    serial4type: Int?,
    pgserial4type: Int?,
    bigserialtype: Long?,
    serial8type: Long?,
    pgserial8type: Long?,
    smallinttype: Short?,
    int2type: Short?,
    pgint2type: Short?,
    integertype: Int?,
    inttype: Int?,
    int4type: Int?,
    pgint4type: Int?,
    biginttype: Long?,
    int8type: Long?,
    pgint8type: Long?,
    realtype: Float?,
    float4type: Float?,
    pgfloat4type: Float?,
    floattype: Double?,
    doubletype: Double?,
    float8type: Double?,
    pgfloat8type: Double?,
    numerictype: BigDecimal?,
    pgnumerictype: BigDecimal?,
    booltype: Boolean?,
    pgbooltype: Boolean?,
    jsonbtype: String?,
    blobtype: Blob?,
    texttype: String?,
    varchartype: String?,
    pgvarchartype: String?,
    bpchartype: String?,
    pgbpchartype: String?,
    stringtype: String?,
  ) -> T, block: (String, ResultSet.() -> T) -> R): R {
    val sql =
        "SELECT smallserialtype, serial2type, pgserial2type, serialtype, serial4type, pgserial4type, bigserialtype, serial8type, pgserial8type, smallinttype, int2type, pgint2type, integertype, inttype, int4type, pgint4type, biginttype, int8type, pgint8type, realtype, float4type, pgfloat4type, floattype, doubletype, float8type, pgfloat8type, numerictype, pgnumerictype, booltype, pgbooltype, jsonbtype, blobtype, texttype, varchartype, pgvarchartype, bpchartype, pgbpchartype, stringtype FROM Type"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getShort(1).takeUnless { wasNull() },
        getShort(2).takeUnless { wasNull() },
        getShort(3).takeUnless { wasNull() },
        getInt(4).takeUnless { wasNull() },
        getInt(5).takeUnless { wasNull() },
        getInt(6).takeUnless { wasNull() },
        getLong(7).takeUnless { wasNull() },
        getLong(8).takeUnless { wasNull() },
        getLong(9).takeUnless { wasNull() },
        getShort(10).takeUnless { wasNull() },
        getShort(11).takeUnless { wasNull() },
        getShort(12).takeUnless { wasNull() },
        getInt(13).takeUnless { wasNull() },
        getInt(14).takeUnless { wasNull() },
        getInt(15).takeUnless { wasNull() },
        getInt(16).takeUnless { wasNull() },
        getLong(17).takeUnless { wasNull() },
        getLong(18).takeUnless { wasNull() },
        getLong(19).takeUnless { wasNull() },
        getFloat(20).takeUnless { wasNull() },
        getFloat(21).takeUnless { wasNull() },
        getFloat(22).takeUnless { wasNull() },
        getDouble(23).takeUnless { wasNull() },
        getDouble(24).takeUnless { wasNull() },
        getDouble(25).takeUnless { wasNull() },
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

  @Throws(SQLException::class)
  override fun <T : Any> all(mapper: (
    smallserialtype: Short?,
    serial2type: Short?,
    pgserial2type: Short?,
    serialtype: Int?,
    serial4type: Int?,
    pgserial4type: Int?,
    bigserialtype: Long?,
    serial8type: Long?,
    pgserial8type: Long?,
    smallinttype: Short?,
    int2type: Short?,
    pgint2type: Short?,
    integertype: Int?,
    inttype: Int?,
    int4type: Int?,
    pgint4type: Int?,
    biginttype: Long?,
    int8type: Long?,
    pgint8type: Long?,
    realtype: Float?,
    float4type: Float?,
    pgfloat4type: Float?,
    floattype: Double?,
    doubletype: Double?,
    float8type: Double?,
    pgfloat8type: Double?,
    numerictype: BigDecimal?,
    pgnumerictype: BigDecimal?,
    booltype: Boolean?,
    pgbooltype: Boolean?,
    jsonbtype: String?,
    blobtype: Blob?,
    texttype: String?,
    varchartype: String?,
    pgvarchartype: String?,
    bpchartype: String?,
    pgbpchartype: String?,
    stringtype: String?,
  ) -> T): Many<T> = all(mapper, driver::queryMany)

  override fun <T : Any> allDynamically(mapper: (
    smallserialtype: Short?,
    serial2type: Short?,
    pgserial2type: Short?,
    serialtype: Int?,
    serial4type: Int?,
    pgserial4type: Int?,
    bigserialtype: Long?,
    serial8type: Long?,
    pgserial8type: Long?,
    smallinttype: Short?,
    int2type: Short?,
    pgint2type: Short?,
    integertype: Int?,
    inttype: Int?,
    int4type: Int?,
    pgint4type: Int?,
    biginttype: Long?,
    int8type: Long?,
    pgint8type: Long?,
    realtype: Float?,
    float4type: Float?,
    pgfloat4type: Float?,
    floattype: Double?,
    doubletype: Double?,
    float8type: Double?,
    pgfloat8type: Double?,
    numerictype: BigDecimal?,
    pgnumerictype: BigDecimal?,
    booltype: Boolean?,
    pgbooltype: Boolean?,
    jsonbtype: String?,
    blobtype: Blob?,
    texttype: String?,
    varchartype: String?,
    pgvarchartype: String?,
    bpchartype: String?,
    pgbpchartype: String?,
    stringtype: String?,
  ) -> T): Query<T> = all(mapper, driver::dynamic)
}
