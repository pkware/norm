package example

import java.math.BigDecimal
import java.sql.Blob
import java.sql.SQLException
import java.sql.Statement.EXECUTE_FAILED
import java.sql.Statement.SUCCESS_NO_INFO
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
import norm.Transactable
import norm.inputValue

public interface Queries : Transactable {
  /**
   * ```sql
   * SELECT * FROM type
   * ```
   */
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
    json_type: String?,
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
    timestamptz_type: Instant?,
    timestamptz_notnull_type: Instant,
    pg_timestamptz_type: Instant?,
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
    int2_array_type: Array<Short?>?,
    int2_array_notnull_type: Array<Short?>,
    int8_array_type: Array<Long?>?,
    int8_array_notnull_type: Array<Long?>,
    float4_array_type: Array<Float?>?,
    float4_array_notnull_type: Array<Float?>,
    float8_array_type: Array<Double?>?,
    float8_array_notnull_type: Array<Double?>,
    numeric_array_type: Array<BigDecimal?>?,
    numeric_array_notnull_type: Array<BigDecimal?>,
    bool_array_type: Array<Boolean?>?,
    bool_array_notnull_type: Array<Boolean?>,
    json_array_type: Array<String?>?,
    json_array_notnull_type: Array<String?>,
    jsonb_array_type: Array<String?>?,
    jsonb_array_notnull_type: Array<String?>,
    bytea_array_type: Array<ByteArray?>?,
    bytea_array_notnull_type: Array<ByteArray?>,
    uuid_array_type: Array<UUID?>?,
    uuid_array_notnull_type: Array<UUID?>,
    date_array_type: Array<LocalDate?>?,
    date_array_notnull_type: Array<LocalDate?>,
    time_array_type: Array<LocalTime?>?,
    time_array_notnull_type: Array<LocalTime?>,
    timetz_array_type: Array<OffsetTime?>?,
    timetz_array_notnull_type: Array<OffsetTime?>,
    timestamp_array_type: Array<LocalDateTime?>?,
    timestamp_array_notnull_type: Array<LocalDateTime?>,
    timestamptz_array_type: Array<Instant?>?,
    timestamptz_array_notnull_type: Array<Instant?>,
    oid_array_type: Array<Long?>?,
    oid_array_notnull_type: Array<Long?>,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM type
   * ```
   */
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
    json_type: String?,
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
    timestamptz_type: Instant?,
    timestamptz_notnull_type: Instant,
    pg_timestamptz_type: Instant?,
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
    int2_array_type: Array<Short?>?,
    int2_array_notnull_type: Array<Short?>,
    int8_array_type: Array<Long?>?,
    int8_array_notnull_type: Array<Long?>,
    float4_array_type: Array<Float?>?,
    float4_array_notnull_type: Array<Float?>,
    float8_array_type: Array<Double?>?,
    float8_array_notnull_type: Array<Double?>,
    numeric_array_type: Array<BigDecimal?>?,
    numeric_array_notnull_type: Array<BigDecimal?>,
    bool_array_type: Array<Boolean?>?,
    bool_array_notnull_type: Array<Boolean?>,
    json_array_type: Array<String?>?,
    json_array_notnull_type: Array<String?>,
    jsonb_array_type: Array<String?>?,
    jsonb_array_notnull_type: Array<String?>,
    bytea_array_type: Array<ByteArray?>?,
    bytea_array_notnull_type: Array<ByteArray?>,
    uuid_array_type: Array<UUID?>?,
    uuid_array_notnull_type: Array<UUID?>,
    date_array_type: Array<LocalDate?>?,
    date_array_notnull_type: Array<LocalDate?>,
    time_array_type: Array<LocalTime?>?,
    time_array_notnull_type: Array<LocalTime?>,
    timetz_array_type: Array<OffsetTime?>?,
    timetz_array_notnull_type: Array<OffsetTime?>,
    timestamp_array_type: Array<LocalDateTime?>?,
    timestamp_array_notnull_type: Array<LocalDateTime?>,
    timestamptz_array_type: Array<Instant?>?,
    timestamptz_array_notnull_type: Array<Instant?>,
    oid_array_type: Array<Long?>?,
    oid_array_notnull_type: Array<Long?>,
  ) -> T): Query<T>

  public fun allDynamically(): Query<Type> = allDynamically(::Type)

  /**
   * ```sql
   * SELECT string_type FROM type
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> single(mapper: (string_type: String) -> T): T

  /**
   * ```sql
   * SELECT string_type FROM type
   * ```
   */
  @Throws(SQLException::class)
  public fun single(): String = single(::inputValue)

  /**
   * ```sql
   * INSERT INTO type(string_type) VALUES (?)
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertOne(
    stream: Iterable<Input>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO type(string_type) VALUES (?)
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertOne(stream: Iterable<Input>, string_type: (Input) -> String): IntArray = insertOne(stream, string_type, 100)

  /**
   * ```sql
   * INSERT INTO type(string_type) VALUES (?)
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun insertOne(string_type: String): Int

  /**
   * ```sql
   * INSERT INTO type(string_type, int_type) VALUES (?, ?)
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertMultiple(
    stream: Iterable<Input>,
    string_type: (Input) -> String,
    int_type: (Input) -> Int?,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO type(string_type, int_type) VALUES (?, ?)
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertMultiple(
    stream: Iterable<Input>,
    string_type: (Input) -> String,
    int_type: (Input) -> Int?,
  ): IntArray = insertMultiple(stream, string_type, int_type, 100)

  /**
   * ```sql
   * INSERT INTO type(string_type, int_type) VALUES (?, ?)
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun insertMultiple(string_type: String, int_type: Int?): Int

  /**
   * ```sql
   * UPDATE type SET string_type = ? WHERE string_type IS NOT NULL
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateAllStrings(
    stream: Iterable<Input>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE type SET string_type = ? WHERE string_type IS NOT NULL
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateAllStrings(stream: Iterable<Input>, string_type: (Input) -> String): IntArray = updateAllStrings(stream, string_type, 100)

  /**
   * ```sql
   * UPDATE type SET string_type = ? WHERE string_type IS NOT NULL
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateAllStrings(string_type: String): Int

  /**
   * ```sql
   * DELETE FROM type
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAll(): Int

  /**
   * ```sql
   * DELETE FROM type WHERE serial_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> deleteById(
    stream: Iterable<Input>,
    serial_type: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * DELETE FROM type WHERE serial_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> deleteById(stream: Iterable<Input>, serial_type: (Input) -> Int): IntArray = deleteById(stream, serial_type, 100)

  /**
   * ```sql
   * DELETE FROM type WHERE serial_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteById(serial_type: Int): Int

  /**
   * Resets the type table to its initial state.
   *
   * ```sql
   * CALL reset_type_table()
   * ```
   */
  @Throws(SQLException::class)
  public fun resetTypes()

  /**
   * Updates string_type for the given row via a stored procedure.
   *
   * ```sql
   * CALL update_string_type(?, ?)
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateStringType(
    stream: Iterable<Input>,
    p_id: (Input) -> Int,
    p_new_value: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Updates string_type for the given row via a stored procedure.
   *
   * ```sql
   * CALL update_string_type(?, ?)
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateStringType(
    stream: Iterable<Input>,
    p_id: (Input) -> Int,
    p_new_value: (Input) -> String,
  ): IntArray = updateStringType(stream, p_id, p_new_value, 100)

  /**
   * Updates string_type for the given row via a stored procedure.
   *
   * ```sql
   * CALL update_string_type(?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun updateStringType(p_id: Int, p_new_value: String)

  /**
   * ```sql
   * SELECT * FROM type WHERE string_type = ?
   * ```
   */
  public fun <T : Any> filterByStringType(string_type: String, mapper: (
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
    json_type: String?,
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
    timestamptz_type: Instant?,
    timestamptz_notnull_type: Instant,
    pg_timestamptz_type: Instant?,
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
    int2_array_type: Array<Short?>?,
    int2_array_notnull_type: Array<Short?>,
    int8_array_type: Array<Long?>?,
    int8_array_notnull_type: Array<Long?>,
    float4_array_type: Array<Float?>?,
    float4_array_notnull_type: Array<Float?>,
    float8_array_type: Array<Double?>?,
    float8_array_notnull_type: Array<Double?>,
    numeric_array_type: Array<BigDecimal?>?,
    numeric_array_notnull_type: Array<BigDecimal?>,
    bool_array_type: Array<Boolean?>?,
    bool_array_notnull_type: Array<Boolean?>,
    json_array_type: Array<String?>?,
    json_array_notnull_type: Array<String?>,
    jsonb_array_type: Array<String?>?,
    jsonb_array_notnull_type: Array<String?>,
    bytea_array_type: Array<ByteArray?>?,
    bytea_array_notnull_type: Array<ByteArray?>,
    uuid_array_type: Array<UUID?>?,
    uuid_array_notnull_type: Array<UUID?>,
    date_array_type: Array<LocalDate?>?,
    date_array_notnull_type: Array<LocalDate?>,
    time_array_type: Array<LocalTime?>?,
    time_array_notnull_type: Array<LocalTime?>,
    timetz_array_type: Array<OffsetTime?>?,
    timetz_array_notnull_type: Array<OffsetTime?>,
    timestamp_array_type: Array<LocalDateTime?>?,
    timestamp_array_notnull_type: Array<LocalDateTime?>,
    timestamptz_array_type: Array<Instant?>?,
    timestamptz_array_notnull_type: Array<Instant?>,
    oid_array_type: Array<Long?>?,
    oid_array_notnull_type: Array<Long?>,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM type WHERE string_type = ?
   * ```
   */
  public fun filterByStringType(string_type: String): Many<Type> = filterByStringType(string_type, ::Type)

  /**
   * ```sql
   * SELECT * FROM not_null_view
   * ```
   */
  public fun <T : Any> listNotNullView(mapper: (
    serial_type: Int,
    string_type: String,
    int4_type: Int,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM not_null_view
   * ```
   */
  public fun listNotNullView(): Many<NotNullView> = listNotNullView(::NotNullView)

  public fun <T : Any> listNotNullViewDynamically(mapper: (
    serial_type: Int,
    string_type: String,
    int4_type: Int,
  ) -> T): Query<T>

  public fun listNotNullViewDynamically(): Query<NotNullView> = listNotNullViewDynamically(::NotNullView)

  /**
   * Returns aggregate summary statistics for the given string_type.
   *
   * ```sql
   * SELECT * FROM type_summary WHERE string_type = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getTypeSummary(string_type: String, mapper: (
    string_type: String,
    row_count: Long,
    average_value: Int?,
  ) -> T): T

  /**
   * Returns aggregate summary statistics for the given string_type.
   *
   * ```sql
   * SELECT * FROM type_summary WHERE string_type = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getTypeSummary(string_type: String): TypeSummary = getTypeSummary(string_type, ::TypeSummary)

  /**
   * Lists each department alongside an employee's name and nickname, which are `null` when the
   * department has no employees.
   *
   * ```sql
   * SELECT d.id, d.name AS dept_name, e.name AS employee_name, e.nickname
   * FROM department d
   * LEFT JOIN employee e ON e.department_id = d.id
   * ```
   */
  public fun <T : Any> departmentEmployees(mapper: (
    id: Int,
    dept_name: String,
    employee_name: String?,
    nickname: String?,
  ) -> T): Many<T>

  /**
   * Lists each department alongside an employee's name and nickname, which are `null` when the
   * department has no employees.
   *
   * ```sql
   * SELECT d.id, d.name AS dept_name, e.name AS employee_name, e.nickname
   * FROM department d
   * LEFT JOIN employee e ON e.department_id = d.id
   * ```
   */
  public fun departmentEmployees(): Many<DepartmentEmployees> = departmentEmployees(::DepartmentEmployees)

  public fun <T : Any> departmentEmployeesDynamically(mapper: (
    id: Int,
    dept_name: String,
    employee_name: String?,
    nickname: String?,
  ) -> T): Query<T>

  public fun departmentEmployeesDynamically(): Query<DepartmentEmployees> = departmentEmployeesDynamically(::DepartmentEmployees)

  /**
   * Lists all department and employee names together.
   *
   * ```sql
   * SELECT name FROM department
   * UNION ALL
   * SELECT name FROM employee
   * ```
   */
  public fun <T> allNames(mapper: (name: String?) -> T): Many<T>

  /**
   * Lists all department and employee names together.
   *
   * ```sql
   * SELECT name FROM department
   * UNION ALL
   * SELECT name FROM employee
   * ```
   */
  public fun allNames(): Many<String?> = allNames(::inputValue)

  public fun <T> allNamesDynamically(mapper: (name: String?) -> T): Query<T>

  public fun allNamesDynamically(): Query<String?> = allNamesDynamically(::inputValue)

  /**
   * Updates both string_type and text_type for a given row.
   *
   * ```sql
   * UPDATE type SET string_type = ?, text_type = ? WHERE serial_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateBothStrings(
    stream: Iterable<Input>,
    string_type: (Input) -> String,
    serial_type: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * Updates both string_type and text_type for a given row.
   *
   * ```sql
   * UPDATE type SET string_type = ?, text_type = ? WHERE serial_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateBothStrings(
    stream: Iterable<Input>,
    string_type: (Input) -> String,
    serial_type: (Input) -> Int,
  ): IntArray = updateBothStrings(stream, string_type, serial_type, 100)

  /**
   * Updates both string_type and text_type for a given row.
   *
   * ```sql
   * UPDATE type SET string_type = ?, text_type = ? WHERE serial_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateBothStrings(string_type: String, serial_type: Int): Int

  /**
   * Finds a row where string_type and text_type both equal the given value.
   *
   * ```sql
   * SELECT * FROM type WHERE string_type = ? AND text_type = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> findByMatchingStrings(`value`: String, mapper: (
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
    json_type: String?,
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
    timestamptz_type: Instant?,
    timestamptz_notnull_type: Instant,
    pg_timestamptz_type: Instant?,
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
    int2_array_type: Array<Short?>?,
    int2_array_notnull_type: Array<Short?>,
    int8_array_type: Array<Long?>?,
    int8_array_notnull_type: Array<Long?>,
    float4_array_type: Array<Float?>?,
    float4_array_notnull_type: Array<Float?>,
    float8_array_type: Array<Double?>?,
    float8_array_notnull_type: Array<Double?>,
    numeric_array_type: Array<BigDecimal?>?,
    numeric_array_notnull_type: Array<BigDecimal?>,
    bool_array_type: Array<Boolean?>?,
    bool_array_notnull_type: Array<Boolean?>,
    json_array_type: Array<String?>?,
    json_array_notnull_type: Array<String?>,
    jsonb_array_type: Array<String?>?,
    jsonb_array_notnull_type: Array<String?>,
    bytea_array_type: Array<ByteArray?>?,
    bytea_array_notnull_type: Array<ByteArray?>,
    uuid_array_type: Array<UUID?>?,
    uuid_array_notnull_type: Array<UUID?>,
    date_array_type: Array<LocalDate?>?,
    date_array_notnull_type: Array<LocalDate?>,
    time_array_type: Array<LocalTime?>?,
    time_array_notnull_type: Array<LocalTime?>,
    timetz_array_type: Array<OffsetTime?>?,
    timetz_array_notnull_type: Array<OffsetTime?>,
    timestamp_array_type: Array<LocalDateTime?>?,
    timestamp_array_notnull_type: Array<LocalDateTime?>,
    timestamptz_array_type: Array<Instant?>?,
    timestamptz_array_notnull_type: Array<Instant?>,
    oid_array_type: Array<Long?>?,
    oid_array_notnull_type: Array<Long?>,
  ) -> T): T

  /**
   * Finds a row where string_type and text_type both equal the given value.
   *
   * ```sql
   * SELECT * FROM type WHERE string_type = ? AND text_type = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun findByMatchingStrings(`value`: String): Type = findByMatchingStrings(`value`, ::Type)

  /**
   * Lists rows where string_type and text_type both equal the given value.
   *
   * ```sql
   * SELECT * FROM type WHERE string_type = ? AND text_type = ?
   * ```
   */
  public fun <T : Any> filterByMatchingStrings(`value`: String, mapper: (
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
    json_type: String?,
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
    timestamptz_type: Instant?,
    timestamptz_notnull_type: Instant,
    pg_timestamptz_type: Instant?,
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
    int2_array_type: Array<Short?>?,
    int2_array_notnull_type: Array<Short?>,
    int8_array_type: Array<Long?>?,
    int8_array_notnull_type: Array<Long?>,
    float4_array_type: Array<Float?>?,
    float4_array_notnull_type: Array<Float?>,
    float8_array_type: Array<Double?>?,
    float8_array_notnull_type: Array<Double?>,
    numeric_array_type: Array<BigDecimal?>?,
    numeric_array_notnull_type: Array<BigDecimal?>,
    bool_array_type: Array<Boolean?>?,
    bool_array_notnull_type: Array<Boolean?>,
    json_array_type: Array<String?>?,
    json_array_notnull_type: Array<String?>,
    jsonb_array_type: Array<String?>?,
    jsonb_array_notnull_type: Array<String?>,
    bytea_array_type: Array<ByteArray?>?,
    bytea_array_notnull_type: Array<ByteArray?>,
    uuid_array_type: Array<UUID?>?,
    uuid_array_notnull_type: Array<UUID?>,
    date_array_type: Array<LocalDate?>?,
    date_array_notnull_type: Array<LocalDate?>,
    time_array_type: Array<LocalTime?>?,
    time_array_notnull_type: Array<LocalTime?>,
    timetz_array_type: Array<OffsetTime?>?,
    timetz_array_notnull_type: Array<OffsetTime?>,
    timestamp_array_type: Array<LocalDateTime?>?,
    timestamp_array_notnull_type: Array<LocalDateTime?>,
    timestamptz_array_type: Array<Instant?>?,
    timestamptz_array_notnull_type: Array<Instant?>,
    oid_array_type: Array<Long?>?,
    oid_array_notnull_type: Array<Long?>,
  ) -> T): Many<T>

  /**
   * Lists rows where string_type and text_type both equal the given value.
   *
   * ```sql
   * SELECT * FROM type WHERE string_type = ? AND text_type = ?
   * ```
   */
  public fun filterByMatchingStrings(`value`: String): Many<Type> = filterByMatchingStrings(`value`, ::Type)

  /**
   * Updates the jsonb_type column for a given row.
   *
   * ```sql
   * UPDATE type SET jsonb_type = ? WHERE string_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateJsonb(
    stream: Iterable<Input>,
    jsonb_type: (Input) -> String?,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Updates the jsonb_type column for a given row.
   *
   * ```sql
   * UPDATE type SET jsonb_type = ? WHERE string_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateJsonb(
    stream: Iterable<Input>,
    jsonb_type: (Input) -> String?,
    string_type: (Input) -> String,
  ): IntArray = updateJsonb(stream, jsonb_type, string_type, 100)

  /**
   * Updates the jsonb_type column for a given row.
   *
   * ```sql
   * UPDATE type SET jsonb_type = ? WHERE string_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateJsonb(jsonb_type: String?, string_type: String): Int

  /**
   * Updates the json_type column for a given row.
   *
   * ```sql
   * UPDATE type SET json_type = ? WHERE string_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateJson(
    stream: Iterable<Input>,
    json_type: (Input) -> String?,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Updates the json_type column for a given row.
   *
   * ```sql
   * UPDATE type SET json_type = ? WHERE string_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateJson(
    stream: Iterable<Input>,
    json_type: (Input) -> String?,
    string_type: (Input) -> String,
  ): IntArray = updateJson(stream, json_type, string_type, 100)

  /**
   * Updates the json_type column for a given row.
   *
   * ```sql
   * UPDATE type SET json_type = ? WHERE string_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateJson(json_type: String?, string_type: String): Int

  /**
   * Updates the int and text array columns for a given row.
   *
   * ```sql
   * UPDATE type SET
   *   int_array_type = ?,
   *   int_array_notnull_type = ?,
   *   text_array_type = ?,
   *   text_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateIntTextArrays(
    stream: Iterable<Input>,
    int_array_type: (Input) -> Array<Int?>?,
    int_array_notnull_type: (Input) -> Array<Int?>,
    text_array_type: (Input) -> Array<String?>?,
    text_array_notnull_type: (Input) -> Array<String?>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Updates the int and text array columns for a given row.
   *
   * ```sql
   * UPDATE type SET
   *   int_array_type = ?,
   *   int_array_notnull_type = ?,
   *   text_array_type = ?,
   *   text_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateIntTextArrays(
    stream: Iterable<Input>,
    int_array_type: (Input) -> Array<Int?>?,
    int_array_notnull_type: (Input) -> Array<Int?>,
    text_array_type: (Input) -> Array<String?>?,
    text_array_notnull_type: (Input) -> Array<String?>,
    string_type: (Input) -> String,
  ): IntArray = updateIntTextArrays(stream, int_array_type, int_array_notnull_type, text_array_type, text_array_notnull_type, string_type, 100)

  /**
   * Updates the int and text array columns for a given row.
   *
   * ```sql
   * UPDATE type SET
   *   int_array_type = ?,
   *   int_array_notnull_type = ?,
   *   text_array_type = ?,
   *   text_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateIntTextArrays(
    int_array_type: Array<Int?>?,
    int_array_notnull_type: Array<Int?>,
    text_array_type: Array<String?>?,
    text_array_notnull_type: Array<String?>,
    string_type: String,
  ): Int

  /**
   * ```sql
   * UPDATE type SET
   *   int2_array_type = ?,
   *   int2_array_notnull_type = ?,
   *   int8_array_type = ?,
   *   int8_array_notnull_type = ?,
   *   float4_array_type = ?,
   *   float4_array_notnull_type = ?,
   *   float8_array_type = ?,
   *   float8_array_notnull_type = ?,
   *   numeric_array_type = ?,
   *   numeric_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateNumericArrays(
    stream: Iterable<Input>,
    int2_array_type: (Input) -> Array<Short?>?,
    int2_array_notnull_type: (Input) -> Array<Short?>,
    int8_array_type: (Input) -> Array<Long?>?,
    int8_array_notnull_type: (Input) -> Array<Long?>,
    float4_array_type: (Input) -> Array<Float?>?,
    float4_array_notnull_type: (Input) -> Array<Float?>,
    float8_array_type: (Input) -> Array<Double?>?,
    float8_array_notnull_type: (Input) -> Array<Double?>,
    numeric_array_type: (Input) -> Array<BigDecimal?>?,
    numeric_array_notnull_type: (Input) -> Array<BigDecimal?>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE type SET
   *   int2_array_type = ?,
   *   int2_array_notnull_type = ?,
   *   int8_array_type = ?,
   *   int8_array_notnull_type = ?,
   *   float4_array_type = ?,
   *   float4_array_notnull_type = ?,
   *   float8_array_type = ?,
   *   float8_array_notnull_type = ?,
   *   numeric_array_type = ?,
   *   numeric_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateNumericArrays(
    stream: Iterable<Input>,
    int2_array_type: (Input) -> Array<Short?>?,
    int2_array_notnull_type: (Input) -> Array<Short?>,
    int8_array_type: (Input) -> Array<Long?>?,
    int8_array_notnull_type: (Input) -> Array<Long?>,
    float4_array_type: (Input) -> Array<Float?>?,
    float4_array_notnull_type: (Input) -> Array<Float?>,
    float8_array_type: (Input) -> Array<Double?>?,
    float8_array_notnull_type: (Input) -> Array<Double?>,
    numeric_array_type: (Input) -> Array<BigDecimal?>?,
    numeric_array_notnull_type: (Input) -> Array<BigDecimal?>,
    string_type: (Input) -> String,
  ): IntArray = updateNumericArrays(stream, int2_array_type, int2_array_notnull_type, int8_array_type, int8_array_notnull_type, float4_array_type, float4_array_notnull_type, float8_array_type, float8_array_notnull_type, numeric_array_type, numeric_array_notnull_type, string_type, 100)

  /**
   * ```sql
   * UPDATE type SET
   *   int2_array_type = ?,
   *   int2_array_notnull_type = ?,
   *   int8_array_type = ?,
   *   int8_array_notnull_type = ?,
   *   float4_array_type = ?,
   *   float4_array_notnull_type = ?,
   *   float8_array_type = ?,
   *   float8_array_notnull_type = ?,
   *   numeric_array_type = ?,
   *   numeric_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateNumericArrays(
    int2_array_type: Array<Short?>?,
    int2_array_notnull_type: Array<Short?>,
    int8_array_type: Array<Long?>?,
    int8_array_notnull_type: Array<Long?>,
    float4_array_type: Array<Float?>?,
    float4_array_notnull_type: Array<Float?>,
    float8_array_type: Array<Double?>?,
    float8_array_notnull_type: Array<Double?>,
    numeric_array_type: Array<BigDecimal?>?,
    numeric_array_notnull_type: Array<BigDecimal?>,
    string_type: String,
  ): Int

  /**
   * ```sql
   * UPDATE type SET
   *   date_array_type = ?,
   *   date_array_notnull_type = ?,
   *   time_array_type = ?,
   *   time_array_notnull_type = ?,
   *   timetz_array_type = ?,
   *   timetz_array_notnull_type = ?,
   *   timestamp_array_type = ?,
   *   timestamp_array_notnull_type = ?,
   *   timestamptz_array_type = ?,
   *   timestamptz_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateDateTimeArrays(
    stream: Iterable<Input>,
    date_array_type: (Input) -> Array<LocalDate?>?,
    date_array_notnull_type: (Input) -> Array<LocalDate?>,
    time_array_type: (Input) -> Array<LocalTime?>?,
    time_array_notnull_type: (Input) -> Array<LocalTime?>,
    timetz_array_type: (Input) -> Array<OffsetTime?>?,
    timetz_array_notnull_type: (Input) -> Array<OffsetTime?>,
    timestamp_array_type: (Input) -> Array<LocalDateTime?>?,
    timestamp_array_notnull_type: (Input) -> Array<LocalDateTime?>,
    timestamptz_array_type: (Input) -> Array<Instant?>?,
    timestamptz_array_notnull_type: (Input) -> Array<Instant?>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE type SET
   *   date_array_type = ?,
   *   date_array_notnull_type = ?,
   *   time_array_type = ?,
   *   time_array_notnull_type = ?,
   *   timetz_array_type = ?,
   *   timetz_array_notnull_type = ?,
   *   timestamp_array_type = ?,
   *   timestamp_array_notnull_type = ?,
   *   timestamptz_array_type = ?,
   *   timestamptz_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateDateTimeArrays(
    stream: Iterable<Input>,
    date_array_type: (Input) -> Array<LocalDate?>?,
    date_array_notnull_type: (Input) -> Array<LocalDate?>,
    time_array_type: (Input) -> Array<LocalTime?>?,
    time_array_notnull_type: (Input) -> Array<LocalTime?>,
    timetz_array_type: (Input) -> Array<OffsetTime?>?,
    timetz_array_notnull_type: (Input) -> Array<OffsetTime?>,
    timestamp_array_type: (Input) -> Array<LocalDateTime?>?,
    timestamp_array_notnull_type: (Input) -> Array<LocalDateTime?>,
    timestamptz_array_type: (Input) -> Array<Instant?>?,
    timestamptz_array_notnull_type: (Input) -> Array<Instant?>,
    string_type: (Input) -> String,
  ): IntArray = updateDateTimeArrays(stream, date_array_type, date_array_notnull_type, time_array_type, time_array_notnull_type, timetz_array_type, timetz_array_notnull_type, timestamp_array_type, timestamp_array_notnull_type, timestamptz_array_type, timestamptz_array_notnull_type, string_type, 100)

  /**
   * ```sql
   * UPDATE type SET
   *   date_array_type = ?,
   *   date_array_notnull_type = ?,
   *   time_array_type = ?,
   *   time_array_notnull_type = ?,
   *   timetz_array_type = ?,
   *   timetz_array_notnull_type = ?,
   *   timestamp_array_type = ?,
   *   timestamp_array_notnull_type = ?,
   *   timestamptz_array_type = ?,
   *   timestamptz_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateDateTimeArrays(
    date_array_type: Array<LocalDate?>?,
    date_array_notnull_type: Array<LocalDate?>,
    time_array_type: Array<LocalTime?>?,
    time_array_notnull_type: Array<LocalTime?>,
    timetz_array_type: Array<OffsetTime?>?,
    timetz_array_notnull_type: Array<OffsetTime?>,
    timestamp_array_type: Array<LocalDateTime?>?,
    timestamp_array_notnull_type: Array<LocalDateTime?>,
    timestamptz_array_type: Array<Instant?>?,
    timestamptz_array_notnull_type: Array<Instant?>,
    string_type: String,
  ): Int

  /**
   * ```sql
   * UPDATE type SET
   *   bool_array_type = ?,
   *   bool_array_notnull_type = ?,
   *   json_array_type = ?,
   *   json_array_notnull_type = ?,
   *   jsonb_array_type = ?,
   *   jsonb_array_notnull_type = ?,
   *   bytea_array_type = ?,
   *   bytea_array_notnull_type = ?,
   *   uuid_array_type = ?,
   *   uuid_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateOtherArrays(
    stream: Iterable<Input>,
    bool_array_type: (Input) -> Array<Boolean?>?,
    bool_array_notnull_type: (Input) -> Array<Boolean?>,
    json_array_type: (Input) -> Array<String?>?,
    json_array_notnull_type: (Input) -> Array<String?>,
    jsonb_array_type: (Input) -> Array<String?>?,
    jsonb_array_notnull_type: (Input) -> Array<String?>,
    bytea_array_type: (Input) -> Array<ByteArray?>?,
    bytea_array_notnull_type: (Input) -> Array<ByteArray?>,
    uuid_array_type: (Input) -> Array<UUID?>?,
    uuid_array_notnull_type: (Input) -> Array<UUID?>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE type SET
   *   bool_array_type = ?,
   *   bool_array_notnull_type = ?,
   *   json_array_type = ?,
   *   json_array_notnull_type = ?,
   *   jsonb_array_type = ?,
   *   jsonb_array_notnull_type = ?,
   *   bytea_array_type = ?,
   *   bytea_array_notnull_type = ?,
   *   uuid_array_type = ?,
   *   uuid_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateOtherArrays(
    stream: Iterable<Input>,
    bool_array_type: (Input) -> Array<Boolean?>?,
    bool_array_notnull_type: (Input) -> Array<Boolean?>,
    json_array_type: (Input) -> Array<String?>?,
    json_array_notnull_type: (Input) -> Array<String?>,
    jsonb_array_type: (Input) -> Array<String?>?,
    jsonb_array_notnull_type: (Input) -> Array<String?>,
    bytea_array_type: (Input) -> Array<ByteArray?>?,
    bytea_array_notnull_type: (Input) -> Array<ByteArray?>,
    uuid_array_type: (Input) -> Array<UUID?>?,
    uuid_array_notnull_type: (Input) -> Array<UUID?>,
    string_type: (Input) -> String,
  ): IntArray = updateOtherArrays(stream, bool_array_type, bool_array_notnull_type, json_array_type, json_array_notnull_type, jsonb_array_type, jsonb_array_notnull_type, bytea_array_type, bytea_array_notnull_type, uuid_array_type, uuid_array_notnull_type, string_type, 100)

  /**
   * ```sql
   * UPDATE type SET
   *   bool_array_type = ?,
   *   bool_array_notnull_type = ?,
   *   json_array_type = ?,
   *   json_array_notnull_type = ?,
   *   jsonb_array_type = ?,
   *   jsonb_array_notnull_type = ?,
   *   bytea_array_type = ?,
   *   bytea_array_notnull_type = ?,
   *   uuid_array_type = ?,
   *   uuid_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateOtherArrays(
    bool_array_type: Array<Boolean?>?,
    bool_array_notnull_type: Array<Boolean?>,
    json_array_type: Array<String?>?,
    json_array_notnull_type: Array<String?>,
    jsonb_array_type: Array<String?>?,
    jsonb_array_notnull_type: Array<String?>,
    bytea_array_type: Array<ByteArray?>?,
    bytea_array_notnull_type: Array<ByteArray?>,
    uuid_array_type: Array<UUID?>?,
    uuid_array_notnull_type: Array<UUID?>,
    string_type: String,
  ): Int

  /**
   * Updates the oid array columns for a given row.
   *
   * ```sql
   * UPDATE type SET
   *   oid_array_type = ?,
   *   oid_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateOidArray(
    stream: Iterable<Input>,
    oid_array_type: (Input) -> Array<Long?>?,
    oid_array_notnull_type: (Input) -> Array<Long?>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Updates the oid array columns for a given row.
   *
   * ```sql
   * UPDATE type SET
   *   oid_array_type = ?,
   *   oid_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return An array containing the result of each batch. The array has the same number as elements as [stream]
   *         had. The number in each slot can have one of several meanings:
   *         1. A number greater than or equal to zero -- indicates that the
   *            command was processed successfully and is an update count giving the
   *            number of rows in the database that were affected by the command's execution
   *         2. A value of [SUCCESS_NO_INFO] -- indicates that the command was processed successfully
   *            but that the number of rows affected is unknown
   *         3. A value of [EXECUTE_FAILED] -- indicates that the command failed to execute
   *            successfully and occurs only if a driver continues to process commands after a command fails
   */
  @Throws(SQLException::class)
  public fun <Input : Any> updateOidArray(
    stream: Iterable<Input>,
    oid_array_type: (Input) -> Array<Long?>?,
    oid_array_notnull_type: (Input) -> Array<Long?>,
    string_type: (Input) -> String,
  ): IntArray = updateOidArray(stream, oid_array_type, oid_array_notnull_type, string_type, 100)

  /**
   * Updates the oid array columns for a given row.
   *
   * ```sql
   * UPDATE type SET
   *   oid_array_type = ?,
   *   oid_array_notnull_type = ?
   * WHERE string_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun updateOidArray(
    oid_array_type: Array<Long?>?,
    oid_array_notnull_type: Array<Long?>,
    string_type: String,
  ): Int
}
