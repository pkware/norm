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
import norm.inputValue

public interface Queries {
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
   * Execrows without parameters.
   *
   * ```sql
   * DELETE FROM type
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAll(): Int

  /**
   * Execrows with 1 parameter.
   *
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
   * Execrows with 1 parameter.
   *
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
   * Execrows with 1 parameter.
   *
   * ```sql
   * DELETE FROM type WHERE serial_type = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteById(serial_type: Int): Int

  /**
   * Exec without parameters.
   *
   * ```sql
   * CALL reset_type_table()
   * ```
   */
  @Throws(SQLException::class)
  public fun resetTypes()

  /**
   * Exec with parameters.
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
   * Exec with parameters.
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
   * Exec with parameters.
   *
   * ```sql
   * CALL update_string_type(?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun updateStringType(p_id: Int, p_new_value: String)

  /**
   * :many with a parameter: verify params flow through the Many code path.
   *
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
  ) -> T): Many<T>

  /**
   * :many with a parameter: verify params flow through the Many code path.
   *
   * ```sql
   * SELECT * FROM type WHERE string_type = ?
   * ```
   */
  public fun filterByStringType(string_type: String): Many<Type> = filterByStringType(string_type, ::Type)

  /**
   * Query against a view (pass-through columns preserve nullability from base table).
   *
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
   * Query against a view (pass-through columns preserve nullability from base table).
   *
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
   * Query against a materialized view with computed columns (aggregates are nullable).
   *
   * ```sql
   * SELECT * FROM type_summary WHERE string_type = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getTypeSummary(string_type: String, mapper: (
    string_type: String,
    row_count: Long?,
    average_value: Int?,
  ) -> T): T

  /**
   * Query against a materialized view with computed columns (aggregates are nullable).
   *
   * ```sql
   * SELECT * FROM type_summary WHERE string_type = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getTypeSummary(string_type: String): TypeSummary = getTypeSummary(string_type, ::TypeSummary)

  /**
   * LEFT JOIN: right side NOT NULL columns become nullable (#58).
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
   * LEFT JOIN: right side NOT NULL columns become nullable (#58).
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
   * UNION ALL: node tree has no VAR at top level, nullability from JDBC metadata.
   *
   * ```sql
   * SELECT name FROM department
   * UNION ALL
   * SELECT name FROM employee
   * ```
   */
  public fun <T> allNames(mapper: (name: String?) -> T): Many<T>

  /**
   * UNION ALL: node tree has no VAR at top level, nullability from JDBC metadata.
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
   * Reused named parameter in :execrows — exercises batch body codegen.
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
   * Reused named parameter in :execrows — exercises batch body codegen.
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
   * Reused named parameter in :execrows — exercises batch body codegen.
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
   * Reused named parameter in :one — exercises buildOne body codegen.
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
  ) -> T): T

  /**
   * Reused named parameter in :one — exercises buildOne body codegen.
   *
   * ```sql
   * SELECT * FROM type WHERE string_type = ? AND text_type = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun findByMatchingStrings(`value`: String): Type = findByMatchingStrings(`value`, ::Type)

  /**
   * Reused named parameter in :many — exercises queryBinder body codegen.
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
  ) -> T): Many<T>

  /**
   * Reused named parameter in :many — exercises queryBinder body codegen.
   *
   * ```sql
   * SELECT * FROM type WHERE string_type = ? AND text_type = ?
   * ```
   */
  public fun filterByMatchingStrings(`value`: String): Many<Type> = filterByMatchingStrings(`value`, ::Type)
}
