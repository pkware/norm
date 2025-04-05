package example

import java.math.BigDecimal
import java.sql.Blob
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
  public val smallserialtype: Short?,
  public val serial2type: Short?,
  public val pgserial2type: Short?,
  public val serialtype: Int?,
  public val serial4type: Int?,
  public val pgserial4type: Int?,
  public val bigserialtype: Long?,
  public val serial8type: Long?,
  public val pgserial8type: Long?,
  public val smallinttype: Short?,
  public val int2type: Short?,
  public val pgint2type: Short?,
  public val integertype: Int?,
  public val inttype: Int?,
  public val int4type: Int?,
  public val pgint4type: Int?,
  public val biginttype: Long?,
  public val int8type: Long?,
  public val pgint8type: Long?,
  public val realtype: Float?,
  public val float4type: Float?,
  public val pgfloat4type: Float?,
  public val floattype: Double?,
  public val doubletype: Double?,
  public val float8type: Double?,
  public val pgfloat8type: Double?,
  public val numerictype: BigDecimal?,
  public val pgnumerictype: BigDecimal?,
  public val booltype: Boolean?,
  public val pgbooltype: Boolean?,
  public val jsonbtype: String?,
  public val blobtype: Blob?,
  public val texttype: String?,
  public val varchartype: String?,
  public val pgvarchartype: String?,
  public val bpchartype: String?,
  public val pgbpchartype: String?,
  public val stringtype: String?,
)
