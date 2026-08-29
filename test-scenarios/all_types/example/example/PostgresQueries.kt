package example

import java.math.BigDecimal
import java.sql.Blob
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
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
import norm.ManyProcessor
import norm.NormDriver
import norm.Query
import norm.RealTransactable
import norm.combineExecBatchResults
import norm.mapElements
import norm.setInt
import norm.toSqlArray

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, Return> all(mapper: (
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
  ) -> T, processor: ManyProcessor<T, Return>): Return {
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
        getString(32),
        getBlob(33),
        getString(34),
        getString(35),
        getString(36),
        getString(37),
        getString(38),
        getString(39),
        getObject(40, LocalDate::class.java),
        getObject(41, LocalDate::class.java),
        getObject(42, LocalDate::class.java),
        getObject(43, LocalTime::class.java),
        getObject(44, LocalTime::class.java),
        getObject(45, LocalTime::class.java),
        getObject(46, OffsetTime::class.java),
        getObject(47, OffsetTime::class.java),
        getObject(48, OffsetTime::class.java),
        getObject(49, LocalDateTime::class.java),
        getObject(50, LocalDateTime::class.java),
        getObject(51, LocalDateTime::class.java),
        getObject(52, OffsetDateTime::class.java)?.toInstant(),
        getObject(53, OffsetDateTime::class.java).toInstant(),
        getObject(54, OffsetDateTime::class.java)?.toInstant(),
        getObject(55, UUID::class.java),
        getObject(56, UUID::class.java),
        getObject(57, UUID::class.java),
        getBytes(58),
        getBytes(59),
        getBytes(60),
        getArray(61)?.mapElements { getInt(2).takeUnless { wasNull() } },
        getArray(62).mapElements { getInt(2).takeUnless { wasNull() } },
        getArray(63)?.mapElements { getString(2) },
        getArray(64).mapElements { getString(2) },
        getArray(65)?.mapElements { getShort(2).takeUnless { wasNull() } },
        getArray(66).mapElements { getShort(2).takeUnless { wasNull() } },
        getArray(67)?.mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(68).mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(69)?.mapElements { getFloat(2).takeUnless { wasNull() } },
        getArray(70).mapElements { getFloat(2).takeUnless { wasNull() } },
        getArray(71)?.mapElements { getDouble(2).takeUnless { wasNull() } },
        getArray(72).mapElements { getDouble(2).takeUnless { wasNull() } },
        getArray(73)?.mapElements { getBigDecimal(2) },
        getArray(74).mapElements { getBigDecimal(2) },
        getArray(75)?.mapElements { getBoolean(2).takeUnless { wasNull() } },
        getArray(76).mapElements { getBoolean(2).takeUnless { wasNull() } },
        getArray(77)?.mapElements { getString(2) },
        getArray(78).mapElements { getString(2) },
        getArray(79)?.mapElements { getString(2) },
        getArray(80).mapElements { getString(2) },
        getArray(81)?.mapElements { getBytes(2) },
        getArray(82).mapElements { getBytes(2) },
        getArray(83)?.mapElements { getObject(2, UUID::class.java) },
        getArray(84).mapElements { getObject(2, UUID::class.java) },
        getArray(85)?.mapElements { getObject(2, LocalDate::class.java) },
        getArray(86).mapElements { getObject(2, LocalDate::class.java) },
        getArray(87)?.mapElements { getObject(2, LocalTime::class.java) },
        getArray(88).mapElements { getObject(2, LocalTime::class.java) },
        getArray(89)?.mapElements { getObject(2, OffsetTime::class.java) },
        getArray(90).mapElements { getObject(2, OffsetTime::class.java) },
        getArray(91)?.mapElements { getObject(2, LocalDateTime::class.java) },
        getArray(92).mapElements { getObject(2, LocalDateTime::class.java) },
        getArray(93)?.mapElements { getObject(2, OffsetDateTime::class.java)?.toInstant() },
        getArray(94).mapElements { getObject(2, OffsetDateTime::class.java)?.toInstant() },
        getArray(95)?.mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(96).mapElements { getLong(2).takeUnless { wasNull() } },
      )
    }
    return processor.invoke(sql, rowReader, null)
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
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO type(string_type) VALUES (?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, string_type(entry))
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
    string_type: (Input) -> String,
    int_type: (Input) -> Int?,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO type(string_type, int_type) VALUES (?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, string_type(entry))
        setInt(2, int_type(entry))
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
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "UPDATE type SET string_type = ? WHERE string_type IS NOT NULL"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, string_type(entry))
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
    serial_type: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "DELETE FROM type WHERE serial_type = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, serial_type(entry))
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
    p_id: (Input) -> Int,
    p_new_value: (Input) -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "CALL update_string_type(?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, p_id(entry))
        setString(2, p_new_value(entry))
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

  private fun <T : Any, Return> filterByStringType(
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
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
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
        getString(32),
        getBlob(33),
        getString(34),
        getString(35),
        getString(36),
        getString(37),
        getString(38),
        getString(39),
        getObject(40, LocalDate::class.java),
        getObject(41, LocalDate::class.java),
        getObject(42, LocalDate::class.java),
        getObject(43, LocalTime::class.java),
        getObject(44, LocalTime::class.java),
        getObject(45, LocalTime::class.java),
        getObject(46, OffsetTime::class.java),
        getObject(47, OffsetTime::class.java),
        getObject(48, OffsetTime::class.java),
        getObject(49, LocalDateTime::class.java),
        getObject(50, LocalDateTime::class.java),
        getObject(51, LocalDateTime::class.java),
        getObject(52, OffsetDateTime::class.java)?.toInstant(),
        getObject(53, OffsetDateTime::class.java).toInstant(),
        getObject(54, OffsetDateTime::class.java)?.toInstant(),
        getObject(55, UUID::class.java),
        getObject(56, UUID::class.java),
        getObject(57, UUID::class.java),
        getBytes(58),
        getBytes(59),
        getBytes(60),
        getArray(61)?.mapElements { getInt(2).takeUnless { wasNull() } },
        getArray(62).mapElements { getInt(2).takeUnless { wasNull() } },
        getArray(63)?.mapElements { getString(2) },
        getArray(64).mapElements { getString(2) },
        getArray(65)?.mapElements { getShort(2).takeUnless { wasNull() } },
        getArray(66).mapElements { getShort(2).takeUnless { wasNull() } },
        getArray(67)?.mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(68).mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(69)?.mapElements { getFloat(2).takeUnless { wasNull() } },
        getArray(70).mapElements { getFloat(2).takeUnless { wasNull() } },
        getArray(71)?.mapElements { getDouble(2).takeUnless { wasNull() } },
        getArray(72).mapElements { getDouble(2).takeUnless { wasNull() } },
        getArray(73)?.mapElements { getBigDecimal(2) },
        getArray(74).mapElements { getBigDecimal(2) },
        getArray(75)?.mapElements { getBoolean(2).takeUnless { wasNull() } },
        getArray(76).mapElements { getBoolean(2).takeUnless { wasNull() } },
        getArray(77)?.mapElements { getString(2) },
        getArray(78).mapElements { getString(2) },
        getArray(79)?.mapElements { getString(2) },
        getArray(80).mapElements { getString(2) },
        getArray(81)?.mapElements { getBytes(2) },
        getArray(82).mapElements { getBytes(2) },
        getArray(83)?.mapElements { getObject(2, UUID::class.java) },
        getArray(84).mapElements { getObject(2, UUID::class.java) },
        getArray(85)?.mapElements { getObject(2, LocalDate::class.java) },
        getArray(86).mapElements { getObject(2, LocalDate::class.java) },
        getArray(87)?.mapElements { getObject(2, LocalTime::class.java) },
        getArray(88).mapElements { getObject(2, LocalTime::class.java) },
        getArray(89)?.mapElements { getObject(2, OffsetTime::class.java) },
        getArray(90).mapElements { getObject(2, OffsetTime::class.java) },
        getArray(91)?.mapElements { getObject(2, LocalDateTime::class.java) },
        getArray(92).mapElements { getObject(2, LocalDateTime::class.java) },
        getArray(93)?.mapElements { getObject(2, OffsetDateTime::class.java)?.toInstant() },
        getArray(94).mapElements { getObject(2, OffsetDateTime::class.java)?.toInstant() },
        getArray(95)?.mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(96).mapElements { getLong(2).takeUnless { wasNull() } },
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setString(1, string_type)
    }
    return processor.invoke(sql, rowReader, queryBinder)
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
  ) -> T): Many<T> = filterByStringType(string_type, mapper, driver::queryMany)

  private fun <T : Any, Return> listNotNullView(mapper: (
    serial_type: Int,
    string_type: String,
    int4_type: Int,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM not_null_view"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
      )
    }
    return processor.invoke(sql, rowReader, null)
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
    row_count: Long,
    average_value: Int?,
  ) -> T): T {
    val sql = "SELECT * FROM type_summary WHERE string_type = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getLong(2),
        getInt(3).takeUnless { wasNull() },
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, string_type)
    }
  }

  private fun <T : Any, Return> departmentEmployees(mapper: (
    id: Int,
    dept_name: String,
    employee_name: String?,
    nickname: String?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |SELECT d.id, d.name AS dept_name, e.name AS employee_name, e.nickname
        |FROM department d
        |LEFT JOIN employee e ON e.department_id = d.id
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
        getString(4),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> departmentEmployees(mapper: (
    id: Int,
    dept_name: String,
    employee_name: String?,
    nickname: String?,
  ) -> T): Many<T> = departmentEmployees(mapper, driver::queryMany)

  override fun <T : Any> departmentEmployeesDynamically(mapper: (
    id: Int,
    dept_name: String,
    employee_name: String?,
    nickname: String?,
  ) -> T): Query<T> = departmentEmployees(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  private fun <T, Return> allNames(mapper: (name: String?) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = """
        |SELECT name FROM department
        |UNION ALL
        |SELECT name FROM employee
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T> allNames(mapper: (name: String?) -> T): Many<T> = allNames(mapper, driver::queryMany)

  override fun <T> allNamesDynamically(mapper: (name: String?) -> T): Query<T> = allNames(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun updateBothStrings(string_type: String, serial_type: Int): Int {
    val sql = "UPDATE type SET string_type = ?, text_type = ? WHERE serial_type = ?"
    return driver.executeRows(sql) {
      setString(1, string_type)
      setString(2, string_type)
      setInt(3, serial_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateBothStrings(
    stream: Iterable<Input>,
    string_type: (Input) -> String,
    serial_type: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "UPDATE type SET string_type = ?, text_type = ? WHERE serial_type = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, string_type(entry))
        setString(2, string_type(entry))
        setInt(3, serial_type(entry))
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
  override fun <T : Any> findByMatchingStrings(`value`: String, mapper: (
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
  ) -> T): T {
    val sql = "SELECT * FROM type WHERE string_type = ? AND text_type = ?"
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
        getString(32),
        getBlob(33),
        getString(34),
        getString(35),
        getString(36),
        getString(37),
        getString(38),
        getString(39),
        getObject(40, LocalDate::class.java),
        getObject(41, LocalDate::class.java),
        getObject(42, LocalDate::class.java),
        getObject(43, LocalTime::class.java),
        getObject(44, LocalTime::class.java),
        getObject(45, LocalTime::class.java),
        getObject(46, OffsetTime::class.java),
        getObject(47, OffsetTime::class.java),
        getObject(48, OffsetTime::class.java),
        getObject(49, LocalDateTime::class.java),
        getObject(50, LocalDateTime::class.java),
        getObject(51, LocalDateTime::class.java),
        getObject(52, OffsetDateTime::class.java)?.toInstant(),
        getObject(53, OffsetDateTime::class.java).toInstant(),
        getObject(54, OffsetDateTime::class.java)?.toInstant(),
        getObject(55, UUID::class.java),
        getObject(56, UUID::class.java),
        getObject(57, UUID::class.java),
        getBytes(58),
        getBytes(59),
        getBytes(60),
        getArray(61)?.mapElements { getInt(2).takeUnless { wasNull() } },
        getArray(62).mapElements { getInt(2).takeUnless { wasNull() } },
        getArray(63)?.mapElements { getString(2) },
        getArray(64).mapElements { getString(2) },
        getArray(65)?.mapElements { getShort(2).takeUnless { wasNull() } },
        getArray(66).mapElements { getShort(2).takeUnless { wasNull() } },
        getArray(67)?.mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(68).mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(69)?.mapElements { getFloat(2).takeUnless { wasNull() } },
        getArray(70).mapElements { getFloat(2).takeUnless { wasNull() } },
        getArray(71)?.mapElements { getDouble(2).takeUnless { wasNull() } },
        getArray(72).mapElements { getDouble(2).takeUnless { wasNull() } },
        getArray(73)?.mapElements { getBigDecimal(2) },
        getArray(74).mapElements { getBigDecimal(2) },
        getArray(75)?.mapElements { getBoolean(2).takeUnless { wasNull() } },
        getArray(76).mapElements { getBoolean(2).takeUnless { wasNull() } },
        getArray(77)?.mapElements { getString(2) },
        getArray(78).mapElements { getString(2) },
        getArray(79)?.mapElements { getString(2) },
        getArray(80).mapElements { getString(2) },
        getArray(81)?.mapElements { getBytes(2) },
        getArray(82).mapElements { getBytes(2) },
        getArray(83)?.mapElements { getObject(2, UUID::class.java) },
        getArray(84).mapElements { getObject(2, UUID::class.java) },
        getArray(85)?.mapElements { getObject(2, LocalDate::class.java) },
        getArray(86).mapElements { getObject(2, LocalDate::class.java) },
        getArray(87)?.mapElements { getObject(2, LocalTime::class.java) },
        getArray(88).mapElements { getObject(2, LocalTime::class.java) },
        getArray(89)?.mapElements { getObject(2, OffsetTime::class.java) },
        getArray(90).mapElements { getObject(2, OffsetTime::class.java) },
        getArray(91)?.mapElements { getObject(2, LocalDateTime::class.java) },
        getArray(92).mapElements { getObject(2, LocalDateTime::class.java) },
        getArray(93)?.mapElements { getObject(2, OffsetDateTime::class.java)?.toInstant() },
        getArray(94).mapElements { getObject(2, OffsetDateTime::class.java)?.toInstant() },
        getArray(95)?.mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(96).mapElements { getLong(2).takeUnless { wasNull() } },
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, `value`)
      setString(2, `value`)
    }
  }

  private fun <T : Any, Return> filterByMatchingStrings(
    `value`: String,
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
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM type WHERE string_type = ? AND text_type = ?"
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
        getString(32),
        getBlob(33),
        getString(34),
        getString(35),
        getString(36),
        getString(37),
        getString(38),
        getString(39),
        getObject(40, LocalDate::class.java),
        getObject(41, LocalDate::class.java),
        getObject(42, LocalDate::class.java),
        getObject(43, LocalTime::class.java),
        getObject(44, LocalTime::class.java),
        getObject(45, LocalTime::class.java),
        getObject(46, OffsetTime::class.java),
        getObject(47, OffsetTime::class.java),
        getObject(48, OffsetTime::class.java),
        getObject(49, LocalDateTime::class.java),
        getObject(50, LocalDateTime::class.java),
        getObject(51, LocalDateTime::class.java),
        getObject(52, OffsetDateTime::class.java)?.toInstant(),
        getObject(53, OffsetDateTime::class.java).toInstant(),
        getObject(54, OffsetDateTime::class.java)?.toInstant(),
        getObject(55, UUID::class.java),
        getObject(56, UUID::class.java),
        getObject(57, UUID::class.java),
        getBytes(58),
        getBytes(59),
        getBytes(60),
        getArray(61)?.mapElements { getInt(2).takeUnless { wasNull() } },
        getArray(62).mapElements { getInt(2).takeUnless { wasNull() } },
        getArray(63)?.mapElements { getString(2) },
        getArray(64).mapElements { getString(2) },
        getArray(65)?.mapElements { getShort(2).takeUnless { wasNull() } },
        getArray(66).mapElements { getShort(2).takeUnless { wasNull() } },
        getArray(67)?.mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(68).mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(69)?.mapElements { getFloat(2).takeUnless { wasNull() } },
        getArray(70).mapElements { getFloat(2).takeUnless { wasNull() } },
        getArray(71)?.mapElements { getDouble(2).takeUnless { wasNull() } },
        getArray(72).mapElements { getDouble(2).takeUnless { wasNull() } },
        getArray(73)?.mapElements { getBigDecimal(2) },
        getArray(74).mapElements { getBigDecimal(2) },
        getArray(75)?.mapElements { getBoolean(2).takeUnless { wasNull() } },
        getArray(76).mapElements { getBoolean(2).takeUnless { wasNull() } },
        getArray(77)?.mapElements { getString(2) },
        getArray(78).mapElements { getString(2) },
        getArray(79)?.mapElements { getString(2) },
        getArray(80).mapElements { getString(2) },
        getArray(81)?.mapElements { getBytes(2) },
        getArray(82).mapElements { getBytes(2) },
        getArray(83)?.mapElements { getObject(2, UUID::class.java) },
        getArray(84).mapElements { getObject(2, UUID::class.java) },
        getArray(85)?.mapElements { getObject(2, LocalDate::class.java) },
        getArray(86).mapElements { getObject(2, LocalDate::class.java) },
        getArray(87)?.mapElements { getObject(2, LocalTime::class.java) },
        getArray(88).mapElements { getObject(2, LocalTime::class.java) },
        getArray(89)?.mapElements { getObject(2, OffsetTime::class.java) },
        getArray(90).mapElements { getObject(2, OffsetTime::class.java) },
        getArray(91)?.mapElements { getObject(2, LocalDateTime::class.java) },
        getArray(92).mapElements { getObject(2, LocalDateTime::class.java) },
        getArray(93)?.mapElements { getObject(2, OffsetDateTime::class.java)?.toInstant() },
        getArray(94).mapElements { getObject(2, OffsetDateTime::class.java)?.toInstant() },
        getArray(95)?.mapElements { getLong(2).takeUnless { wasNull() } },
        getArray(96).mapElements { getLong(2).takeUnless { wasNull() } },
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setString(1, `value`)
      setString(2, `value`)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> filterByMatchingStrings(`value`: String, mapper: (
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
  ) -> T): Many<T> = filterByMatchingStrings(`value`, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun updateJsonb(jsonb_type: String?, string_type: String): Int {
    val sql = "UPDATE type SET jsonb_type = ? WHERE string_type = ?"
    return driver.executeRows(sql) {
      setObject(1, jsonb_type, Types.OTHER)
      setString(2, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateJsonb(
    stream: Iterable<Input>,
    jsonb_type: (Input) -> String?,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "UPDATE type SET jsonb_type = ? WHERE string_type = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setObject(1, jsonb_type(entry), Types.OTHER)
        setString(2, string_type(entry))
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
  override fun updateJson(json_type: String?, string_type: String): Int {
    val sql = "UPDATE type SET json_type = ? WHERE string_type = ?"
    return driver.executeRows(sql) {
      setObject(1, json_type, Types.OTHER)
      setString(2, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateJson(
    stream: Iterable<Input>,
    json_type: (Input) -> String?,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray {
    val sql = "UPDATE type SET json_type = ? WHERE string_type = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setObject(1, json_type(entry), Types.OTHER)
        setString(2, string_type(entry))
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
  override fun updateIntTextArrays(
    int_array_type: Array<Int?>?,
    int_array_notnull_type: Array<Int?>,
    text_array_type: Array<String?>?,
    text_array_notnull_type: Array<String?>,
    string_type: String,
  ): Int {
    val sql = """
        |UPDATE type SET
        |  int_array_type = ?,
        |  int_array_notnull_type = ?,
        |  text_array_type = ?,
        |  text_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.executeRows(sql) {
      int_array_type?.let { setArray(1, it.toSqlArray(connection, "int4")) } ?: setNull(1, Types.ARRAY)
      setArray(2, int_array_notnull_type.toSqlArray(connection, "int4"))
      text_array_type?.let { setArray(3, it.toSqlArray(connection, "text")) } ?: setNull(3, Types.ARRAY)
      setArray(4, text_array_notnull_type.toSqlArray(connection, "text"))
      setString(5, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateIntTextArrays(
    stream: Iterable<Input>,
    int_array_type: (Input) -> Array<Int?>?,
    int_array_notnull_type: (Input) -> Array<Int?>,
    text_array_type: (Input) -> Array<String?>?,
    text_array_notnull_type: (Input) -> Array<String?>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |UPDATE type SET
        |  int_array_type = ?,
        |  int_array_notnull_type = ?,
        |  text_array_type = ?,
        |  text_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        int_array_type(entry)?.let { setArray(1, it.toSqlArray(connection, "int4")) } ?: setNull(1, Types.ARRAY)
        setArray(2, int_array_notnull_type(entry).toSqlArray(connection, "int4"))
        text_array_type(entry)?.let { setArray(3, it.toSqlArray(connection, "text")) } ?: setNull(3, Types.ARRAY)
        setArray(4, text_array_notnull_type(entry).toSqlArray(connection, "text"))
        setString(5, string_type(entry))
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
  override fun updateNumericArrays(
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
  ): Int {
    val sql = """
        |UPDATE type SET
        |  int2_array_type = ?,
        |  int2_array_notnull_type = ?,
        |  int8_array_type = ?,
        |  int8_array_notnull_type = ?,
        |  float4_array_type = ?,
        |  float4_array_notnull_type = ?,
        |  float8_array_type = ?,
        |  float8_array_notnull_type = ?,
        |  numeric_array_type = ?,
        |  numeric_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.executeRows(sql) {
      int2_array_type?.let { setArray(1, it.toSqlArray(connection, "int2")) } ?: setNull(1, Types.ARRAY)
      setArray(2, int2_array_notnull_type.toSqlArray(connection, "int2"))
      int8_array_type?.let { setArray(3, it.toSqlArray(connection, "int8")) } ?: setNull(3, Types.ARRAY)
      setArray(4, int8_array_notnull_type.toSqlArray(connection, "int8"))
      float4_array_type?.let { setArray(5, it.toSqlArray(connection, "float4")) } ?: setNull(5, Types.ARRAY)
      setArray(6, float4_array_notnull_type.toSqlArray(connection, "float4"))
      float8_array_type?.let { setArray(7, it.toSqlArray(connection, "float8")) } ?: setNull(7, Types.ARRAY)
      setArray(8, float8_array_notnull_type.toSqlArray(connection, "float8"))
      numeric_array_type?.let { setArray(9, it.toSqlArray(connection, "numeric")) } ?: setNull(9, Types.ARRAY)
      setArray(10, numeric_array_notnull_type.toSqlArray(connection, "numeric"))
      setString(11, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateNumericArrays(
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
  ): IntArray {
    val sql = """
        |UPDATE type SET
        |  int2_array_type = ?,
        |  int2_array_notnull_type = ?,
        |  int8_array_type = ?,
        |  int8_array_notnull_type = ?,
        |  float4_array_type = ?,
        |  float4_array_notnull_type = ?,
        |  float8_array_type = ?,
        |  float8_array_notnull_type = ?,
        |  numeric_array_type = ?,
        |  numeric_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        int2_array_type(entry)?.let { setArray(1, it.toSqlArray(connection, "int2")) } ?: setNull(1, Types.ARRAY)
        setArray(2, int2_array_notnull_type(entry).toSqlArray(connection, "int2"))
        int8_array_type(entry)?.let { setArray(3, it.toSqlArray(connection, "int8")) } ?: setNull(3, Types.ARRAY)
        setArray(4, int8_array_notnull_type(entry).toSqlArray(connection, "int8"))
        float4_array_type(entry)?.let { setArray(5, it.toSqlArray(connection, "float4")) } ?: setNull(5, Types.ARRAY)
        setArray(6, float4_array_notnull_type(entry).toSqlArray(connection, "float4"))
        float8_array_type(entry)?.let { setArray(7, it.toSqlArray(connection, "float8")) } ?: setNull(7, Types.ARRAY)
        setArray(8, float8_array_notnull_type(entry).toSqlArray(connection, "float8"))
        numeric_array_type(entry)?.let { setArray(9, it.toSqlArray(connection, "numeric")) } ?: setNull(9, Types.ARRAY)
        setArray(10, numeric_array_notnull_type(entry).toSqlArray(connection, "numeric"))
        setString(11, string_type(entry))
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
  override fun updateDateTimeArrays(
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
  ): Int {
    val sql = """
        |UPDATE type SET
        |  date_array_type = ?,
        |  date_array_notnull_type = ?,
        |  time_array_type = ?,
        |  time_array_notnull_type = ?,
        |  timetz_array_type = ?,
        |  timetz_array_notnull_type = ?,
        |  timestamp_array_type = ?,
        |  timestamp_array_notnull_type = ?,
        |  timestamptz_array_type = ?,
        |  timestamptz_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.executeRows(sql) {
      date_array_type?.let { setArray(1, it.toSqlArray(connection, "date")) } ?: setNull(1, Types.ARRAY)
      setArray(2, date_array_notnull_type.toSqlArray(connection, "date"))
      time_array_type?.let { setArray(3, it.toSqlArray(connection, "time")) } ?: setNull(3, Types.ARRAY)
      setArray(4, time_array_notnull_type.toSqlArray(connection, "time"))
      timetz_array_type?.let { setArray(5, it.toSqlArray(connection, "timetz")) } ?: setNull(5, Types.ARRAY)
      setArray(6, timetz_array_notnull_type.toSqlArray(connection, "timetz"))
      timestamp_array_type?.let { setArray(7, it.toSqlArray(connection, "timestamp")) } ?: setNull(7, Types.ARRAY)
      setArray(8, timestamp_array_notnull_type.toSqlArray(connection, "timestamp"))
      timestamptz_array_type?.let { setArray(9, it.toSqlArray(connection, "timestamptz")) } ?: setNull(9, Types.ARRAY)
      setArray(10, timestamptz_array_notnull_type.toSqlArray(connection, "timestamptz"))
      setString(11, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateDateTimeArrays(
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
  ): IntArray {
    val sql = """
        |UPDATE type SET
        |  date_array_type = ?,
        |  date_array_notnull_type = ?,
        |  time_array_type = ?,
        |  time_array_notnull_type = ?,
        |  timetz_array_type = ?,
        |  timetz_array_notnull_type = ?,
        |  timestamp_array_type = ?,
        |  timestamp_array_notnull_type = ?,
        |  timestamptz_array_type = ?,
        |  timestamptz_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        date_array_type(entry)?.let { setArray(1, it.toSqlArray(connection, "date")) } ?: setNull(1, Types.ARRAY)
        setArray(2, date_array_notnull_type(entry).toSqlArray(connection, "date"))
        time_array_type(entry)?.let { setArray(3, it.toSqlArray(connection, "time")) } ?: setNull(3, Types.ARRAY)
        setArray(4, time_array_notnull_type(entry).toSqlArray(connection, "time"))
        timetz_array_type(entry)?.let { setArray(5, it.toSqlArray(connection, "timetz")) } ?: setNull(5, Types.ARRAY)
        setArray(6, timetz_array_notnull_type(entry).toSqlArray(connection, "timetz"))
        timestamp_array_type(entry)?.let { setArray(7, it.toSqlArray(connection, "timestamp")) } ?: setNull(7, Types.ARRAY)
        setArray(8, timestamp_array_notnull_type(entry).toSqlArray(connection, "timestamp"))
        timestamptz_array_type(entry)?.let { setArray(9, it.toSqlArray(connection, "timestamptz")) } ?: setNull(9, Types.ARRAY)
        setArray(10, timestamptz_array_notnull_type(entry).toSqlArray(connection, "timestamptz"))
        setString(11, string_type(entry))
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
  override fun updateOtherArrays(
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
  ): Int {
    val sql = """
        |UPDATE type SET
        |  bool_array_type = ?,
        |  bool_array_notnull_type = ?,
        |  json_array_type = ?,
        |  json_array_notnull_type = ?,
        |  jsonb_array_type = ?,
        |  jsonb_array_notnull_type = ?,
        |  bytea_array_type = ?,
        |  bytea_array_notnull_type = ?,
        |  uuid_array_type = ?,
        |  uuid_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.executeRows(sql) {
      bool_array_type?.let { setArray(1, it.toSqlArray(connection, "bool")) } ?: setNull(1, Types.ARRAY)
      setArray(2, bool_array_notnull_type.toSqlArray(connection, "bool"))
      json_array_type?.let { setArray(3, it.toSqlArray(connection, "json")) } ?: setNull(3, Types.ARRAY)
      setArray(4, json_array_notnull_type.toSqlArray(connection, "json"))
      jsonb_array_type?.let { setArray(5, it.toSqlArray(connection, "jsonb")) } ?: setNull(5, Types.ARRAY)
      setArray(6, jsonb_array_notnull_type.toSqlArray(connection, "jsonb"))
      bytea_array_type?.let { setArray(7, it.toSqlArray(connection, "bytea")) } ?: setNull(7, Types.ARRAY)
      setArray(8, bytea_array_notnull_type.toSqlArray(connection, "bytea"))
      uuid_array_type?.let { setArray(9, it.toSqlArray(connection, "uuid")) } ?: setNull(9, Types.ARRAY)
      setArray(10, uuid_array_notnull_type.toSqlArray(connection, "uuid"))
      setString(11, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateOtherArrays(
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
  ): IntArray {
    val sql = """
        |UPDATE type SET
        |  bool_array_type = ?,
        |  bool_array_notnull_type = ?,
        |  json_array_type = ?,
        |  json_array_notnull_type = ?,
        |  jsonb_array_type = ?,
        |  jsonb_array_notnull_type = ?,
        |  bytea_array_type = ?,
        |  bytea_array_notnull_type = ?,
        |  uuid_array_type = ?,
        |  uuid_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        bool_array_type(entry)?.let { setArray(1, it.toSqlArray(connection, "bool")) } ?: setNull(1, Types.ARRAY)
        setArray(2, bool_array_notnull_type(entry).toSqlArray(connection, "bool"))
        json_array_type(entry)?.let { setArray(3, it.toSqlArray(connection, "json")) } ?: setNull(3, Types.ARRAY)
        setArray(4, json_array_notnull_type(entry).toSqlArray(connection, "json"))
        jsonb_array_type(entry)?.let { setArray(5, it.toSqlArray(connection, "jsonb")) } ?: setNull(5, Types.ARRAY)
        setArray(6, jsonb_array_notnull_type(entry).toSqlArray(connection, "jsonb"))
        bytea_array_type(entry)?.let { setArray(7, it.toSqlArray(connection, "bytea")) } ?: setNull(7, Types.ARRAY)
        setArray(8, bytea_array_notnull_type(entry).toSqlArray(connection, "bytea"))
        uuid_array_type(entry)?.let { setArray(9, it.toSqlArray(connection, "uuid")) } ?: setNull(9, Types.ARRAY)
        setArray(10, uuid_array_notnull_type(entry).toSqlArray(connection, "uuid"))
        setString(11, string_type(entry))
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
  override fun updateOidArray(
    oid_array_type: Array<Long?>?,
    oid_array_notnull_type: Array<Long?>,
    string_type: String,
  ): Int {
    val sql = """
        |UPDATE type SET
        |  oid_array_type = ?,
        |  oid_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.executeRows(sql) {
      oid_array_type?.let { setArray(1, it.toSqlArray(connection, "oid")) } ?: setNull(1, Types.ARRAY)
      setArray(2, oid_array_notnull_type.toSqlArray(connection, "oid"))
      setString(3, string_type)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> updateOidArray(
    stream: Iterable<Input>,
    oid_array_type: (Input) -> Array<Long?>?,
    oid_array_notnull_type: (Input) -> Array<Long?>,
    string_type: (Input) -> String,
    batchSize: Int,
  ): IntArray {
    val sql = """
        |UPDATE type SET
        |  oid_array_type = ?,
        |  oid_array_notnull_type = ?
        |WHERE string_type = ?
        """.trimMargin()
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        oid_array_type(entry)?.let { setArray(1, it.toSqlArray(connection, "oid")) } ?: setNull(1, Types.ARRAY)
        setArray(2, oid_array_notnull_type(entry).toSqlArray(connection, "oid"))
        setString(3, string_type(entry))
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
}
