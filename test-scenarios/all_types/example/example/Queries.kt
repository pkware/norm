package example

import java.math.BigDecimal
import java.sql.Blob
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.util.UUID
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Double
import kotlin.Float
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.Short
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many
import norm.Query
import norm.inputValue

public interface Queries {
  public fun <T : Any> all(mapper: (
    smallserial_type: Short,
    serial2_type: Short,
    pg_serial2_type: Short,
    serial_type: Int,
    serial4_type: Int,
    pg_serial4_type: Int,
    bigserial_type: Long,
    serial8_type: Long,
    pg_serial8_type: Long,
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
    date_type: LocalDate?,
    date_notnull_type: LocalDate,
    pg_date_type: LocalDate?,
    time_type: LocalTime?,
    time_notnull_type: LocalTime,
    pg_time_type: LocalTime?,
    timetz_type: OffsetTime?,
    timetz_notnull_type: OffsetTime,
    pg_timetz_type: OffsetTime?,
    timestamp_type: LocalDateTime?,
    timestamp_notnull_type: LocalDateTime,
    pg_timestamp_type: LocalDateTime?,
    timestamptz_type: OffsetDateTime?,
    timestamptz_notnull_type: OffsetDateTime,
    pg_timestamptz_type: OffsetDateTime?,
    uuid_type: UUID?,
    uuid_notnull_type: UUID,
    pg_uuid_type: UUID?,
    bytea_type: ByteArray?,
    bytea_notnull_type: ByteArray,
    pg_bytea_type: ByteArray?,
    int_array_type: Array<Int?>?,
    int_array_notnull_type: Array<Int?>,
    text_array_type: Array<String?>?,
    text_array_notnull_type: Array<String?>,
  ) -> T): Many<T>

  public fun all(): Many<Type> = all(::Type)

  public fun <T : Any> allDynamically(mapper: (
    smallserial_type: Short,
    serial2_type: Short,
    pg_serial2_type: Short,
    serial_type: Int,
    serial4_type: Int,
    pg_serial4_type: Int,
    bigserial_type: Long,
    serial8_type: Long,
    pg_serial8_type: Long,
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
    date_type: LocalDate?,
    date_notnull_type: LocalDate,
    pg_date_type: LocalDate?,
    time_type: LocalTime?,
    time_notnull_type: LocalTime,
    pg_time_type: LocalTime?,
    timetz_type: OffsetTime?,
    timetz_notnull_type: OffsetTime,
    pg_timetz_type: OffsetTime?,
    timestamp_type: LocalDateTime?,
    timestamp_notnull_type: LocalDateTime,
    pg_timestamp_type: LocalDateTime?,
    timestamptz_type: OffsetDateTime?,
    timestamptz_notnull_type: OffsetDateTime,
    pg_timestamptz_type: OffsetDateTime?,
    uuid_type: UUID?,
    uuid_notnull_type: UUID,
    pg_uuid_type: UUID?,
    bytea_type: ByteArray?,
    bytea_notnull_type: ByteArray,
    pg_bytea_type: ByteArray?,
    int_array_type: Array<Int?>?,
    int_array_notnull_type: Array<Int?>,
    text_array_type: Array<String?>?,
    text_array_notnull_type: Array<String?>,
  ) -> T): Query<T>

  public fun allDynamically(): Query<Type> = allDynamically(::Type)

  @Throws(SQLException::class)
  public fun <T : Any> single(mapper: (string_type: String) -> T): T

  @Throws(SQLException::class)
  public fun single(): String = single(::inputValue)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [java.sql.Statement.SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [java.sql.Statement.EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertOne(
    stream: Iterable<Input>,
    string_type: Input.() -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [insertOne] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertOne(stream: Iterable<Input>, string_type: Input.() -> String): IntArray = insertOne(stream, string_type, 100)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun insertOne(string_type: String): Int

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [java.sql.Statement.SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [java.sql.Statement.EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertMultiple(
    stream: Iterable<Input>,
    string_type: Input.() -> String,
    int_type: Input.() -> Int?,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [insertMultiple] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertMultiple(
    stream: Iterable<Input>,
    string_type: Input.() -> String,
    int_type: Input.() -> Int?,
  ): IntArray = insertMultiple(stream, string_type, int_type, 100)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun insertMultiple(string_type: String, int_type: Int?): Int

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [java.sql.Statement.SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [java.sql.Statement.EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateAllStrings(
    stream: Iterable<Input>,
    string_type: Input.() -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [updateAllStrings] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateAllStrings(stream: Iterable<Input>, string_type: Input.() -> String): IntArray = updateAllStrings(stream, string_type, 100)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateAllStrings(string_type: String): Int

  /**
   * Execrows without parameters.
   *
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAll(): Int

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [java.sql.Statement.SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [java.sql.Statement.EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> deleteById(
    stream: Iterable<Input>,
    serial_type: Input.() -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [deleteById] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> deleteById(stream: Iterable<Input>, serial_type: Input.() -> Int): IntArray = deleteById(stream, serial_type, 100)

  /**
   * Execrows with 1 parameter.
   *
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteById(serial_type: Int): Int

  /**
   * Exec without parameters.
   *
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun resetTypes()

  /**
   * Norm: Executes a SQL statement.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [java.sql.Statement.SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [java.sql.Statement.EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateStringType(
    stream: Iterable<Input>,
    p_id: Input.() -> Int,
    p_new_value: Input.() -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [updateStringType] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateStringType(
    stream: Iterable<Input>,
    p_id: Input.() -> Int,
    p_new_value: Input.() -> String,
  ): IntArray = updateStringType(stream, p_id, p_new_value, 100)

  /**
   * Exec with parameters.
   *
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun updateStringType(p_id: Int, p_new_value: String)
}
