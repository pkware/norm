package example

import java.math.BigDecimal
import java.sql.Blob
import java.sql.PreparedStatement
import java.sql.ResultSet
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
import kotlin.Unit
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.ConnectionProvider
import norm.Many
import norm.NormDriver
import norm.Query
import norm.RealTransactable
import norm.combineExecBatchResults
import norm.setInt

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, R> all(mapper: (
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
  ) -> T, block: (
    String,
    ResultSet.() -> T,
    (PreparedStatement.() -> Unit)?,
  ) -> R): R {
    val sql = "SELECT * FROM type"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getShort(1),
        getShort(2),
        getShort(3),
        getInt(4),
        getInt(5),
        getInt(6),
        getLong(7),
        getLong(8),
        getLong(9),
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
        getObject(39, LocalDate::class.java),
        getObject(40, LocalDate::class.java),
        getObject(41, LocalDate::class.java),
        getObject(42, LocalTime::class.java),
        getObject(43, LocalTime::class.java),
        getObject(44, LocalTime::class.java),
        getObject(45, OffsetTime::class.java),
        getObject(46, OffsetTime::class.java),
        getObject(47, OffsetTime::class.java),
        getObject(48, LocalDateTime::class.java),
        getObject(49, LocalDateTime::class.java),
        getObject(50, LocalDateTime::class.java),
        getObject(51, OffsetDateTime::class.java),
        getObject(52, OffsetDateTime::class.java),
        getObject(53, OffsetDateTime::class.java),
        getObject(54, UUID::class.java),
        getObject(55, UUID::class.java),
        getObject(56, UUID::class.java),
        getBytes(57),
        getBytes(58),
        getBytes(59),
        getArray(60)?.array?.let {
              @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
              it as Array<Int?>
            },
        getArray(61).array.let {
              @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
              it as Array<Int?>
            },
        getArray(62)?.array?.let {
              @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
              it as Array<String?>
            },
        getArray(63).array.let {
              @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
              it as Array<String?>
            },
      )
    }
    return block(sql, rowReader, null)
  }

  override fun <T : Any> all(mapper: (
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
  ) -> T): Many<T> = all(mapper, driver::queryMany)

  override fun <T : Any> allDynamically(mapper: (
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
  ) -> T): Query<T> = all(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

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

  @Throws(SQLException::class)
  override fun insertOne(string_type: String): Int {
    val sql = "INSERT INTO type(string_type) VALUES (?)"
    return driver.executeRows(sql) {
      setString(1, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> insertOne(
    stream: Iterable<Input>,
    string_type: Input.() -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO type(string_type) VALUES (?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.string_type())
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          results.add(executeBatch())
          batchCount = 0
          // Performance optimization to reduce register updates per loop iteration
          totalCount += batchSize
        }
      }
      if (batchCount > 0) {
        results.add(executeBatch())
        totalCount += batchCount
      }
      combineExecBatchResults(results, totalCount, batchSize)
    }
  }

  @Throws(SQLException::class)
  override fun insertMultiple(string_type: String, int_type: Int?): Int {
    val sql = "INSERT INTO type(string_type, int_type) VALUES (?, ?)"
    return driver.executeRows(sql) {
      setString(1, string_type)
      setInt(2, int_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> insertMultiple(
    stream: Iterable<Input>,
    string_type: Input.() -> String,
    int_type: Input.() -> Int?,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO type(string_type, int_type) VALUES (?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.string_type())
        setInt(2, entry.int_type())
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          results.add(executeBatch())
          batchCount = 0
          // Performance optimization to reduce register updates per loop iteration
          totalCount += batchSize
        }
      }
      if (batchCount > 0) {
        results.add(executeBatch())
        totalCount += batchCount
      }
      combineExecBatchResults(results, totalCount, batchSize)
    }
  }

  @Throws(SQLException::class)
  override fun updateAllStrings(string_type: String): Int {
    val sql = "UPDATE type SET string_type = ? WHERE string_type IS NOT NULL"
    return driver.executeRows(sql) {
      setString(1, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateAllStrings(
    stream: Iterable<Input>,
    string_type: Input.() -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "UPDATE type SET string_type = ? WHERE string_type IS NOT NULL"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.string_type())
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          results.add(executeBatch())
          batchCount = 0
          // Performance optimization to reduce register updates per loop iteration
          totalCount += batchSize
        }
      }
      if (batchCount > 0) {
        results.add(executeBatch())
        totalCount += batchCount
      }
      combineExecBatchResults(results, totalCount, batchSize)
    }
  }

  @Throws(SQLException::class)
  override fun deleteAll(): Int {
    val sql = "DELETE FROM type"
    return driver.executeRows(sql)
  }

  @Throws(SQLException::class)
  override fun deleteById(serial_type: Int): Int {
    val sql = "DELETE FROM type WHERE serial_type = ?"
    return driver.executeRows(sql) {
      setInt(1, serial_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> deleteById(
    stream: Iterable<Input>,
    serial_type: Input.() -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "DELETE FROM type WHERE serial_type = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, entry.serial_type())
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          results.add(executeBatch())
          batchCount = 0
          // Performance optimization to reduce register updates per loop iteration
          totalCount += batchSize
        }
      }
      if (batchCount > 0) {
        results.add(executeBatch())
        totalCount += batchCount
      }
      combineExecBatchResults(results, totalCount, batchSize)
    }
  }

  @Throws(SQLException::class)
  override fun resetTypes() {
    val sql = "CALL reset_type_table()"
    driver.execute(sql, PreparedStatement::execute)
  }

  @Throws(SQLException::class)
  override fun updateStringType(p_id: Int, p_new_value: String) {
    val sql = "CALL update_string_type(?, ?)"
    driver.execute(sql) {
      setInt(1, p_id)
      setString(2, p_new_value)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateStringType(
    stream: Iterable<Input>,
    p_id: Input.() -> Int,
    p_new_value: Input.() -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "CALL update_string_type(?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, entry.p_id())
        setString(2, entry.p_new_value())
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        results.add(executeBatch())
        totalCount += batchCount
      }
      combineExecBatchResults(results, totalCount, batchSize)
    }
  }

  private fun <T : Any, R> filterByStringType(
    string_type: String,
    mapper: (
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
    ) -> T,
    block: (
      String,
      ResultSet.() -> T,
      (PreparedStatement.() -> Unit)?,
    ) -> R,
  ): R {
    val sql = "SELECT * FROM type WHERE string_type = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getShort(1),
        getShort(2),
        getShort(3),
        getInt(4),
        getInt(5),
        getInt(6),
        getLong(7),
        getLong(8),
        getLong(9),
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
        getObject(39, LocalDate::class.java),
        getObject(40, LocalDate::class.java),
        getObject(41, LocalDate::class.java),
        getObject(42, LocalTime::class.java),
        getObject(43, LocalTime::class.java),
        getObject(44, LocalTime::class.java),
        getObject(45, OffsetTime::class.java),
        getObject(46, OffsetTime::class.java),
        getObject(47, OffsetTime::class.java),
        getObject(48, LocalDateTime::class.java),
        getObject(49, LocalDateTime::class.java),
        getObject(50, LocalDateTime::class.java),
        getObject(51, OffsetDateTime::class.java),
        getObject(52, OffsetDateTime::class.java),
        getObject(53, OffsetDateTime::class.java),
        getObject(54, UUID::class.java),
        getObject(55, UUID::class.java),
        getObject(56, UUID::class.java),
        getBytes(57),
        getBytes(58),
        getBytes(59),
        getArray(60)?.array?.let {
              @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
              it as Array<Int?>
            },
        getArray(61).array.let {
              @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
              it as Array<Int?>
            },
        getArray(62)?.array?.let {
              @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
              it as Array<String?>
            },
        getArray(63).array.let {
              @Suppress("UNCHECKED_CAST") // Mapping from Postgres to Kotlin is inherently unchecked. Norm makes it safe.
              it as Array<String?>
            },
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setString(1, string_type)
    }
    return block(sql, rowReader, queryBinder)
  }

  override fun <T : Any> filterByStringType(string_type: String, mapper: (
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
  ) -> T): Many<T> = filterByStringType(string_type, mapper, driver::queryMany)

  private fun <T : Any, R> listNotNullView(mapper: (
    serial_type: Int,
    string_type: String,
    int4_type: Int,
  ) -> T, block: (
    String,
    ResultSet.() -> T,
    (PreparedStatement.() -> Unit)?,
  ) -> R): R {
    val sql = "SELECT * FROM not_null_view"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
      )
    }
    return block(sql, rowReader, null)
  }

  override fun <T : Any> listNotNullView(mapper: (
    serial_type: Int,
    string_type: String,
    int4_type: Int,
  ) -> T): Many<T> = listNotNullView(mapper, driver::queryMany)

  override fun <T : Any> listNotNullViewDynamically(mapper: (
    serial_type: Int,
    string_type: String,
    int4_type: Int,
  ) -> T): Query<T> = listNotNullView(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun <T : Any> getTypeSummary(string_type: String, mapper: (
    string_type: String,
    row_count: Long?,
    average_value: Int?,
  ) -> T): T {
    val sql = "SELECT * FROM type_summary WHERE string_type = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getLong(2).takeUnless { wasNull() },
        getInt(3).takeUnless { wasNull() },
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, string_type)
    }
  }
}
