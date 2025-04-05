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
import norm.Transacter

public interface Queries : Transacter {
  @Throws(SQLException::class)
  public fun <T : Any> all(mapper: (
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
  ) -> T): Many<T>

  @Throws(SQLException::class)
  public fun all(): Many<Type> = all(::Type)
}
