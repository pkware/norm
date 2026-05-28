package example

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.Unit
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.RealTransactable
import norm.combineExecBatchResults

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  @Throws(SQLException::class)
  override fun <T : Any> getEventById(
    id: UUID,
    created_at: Instant,
    mapper: (
      id: UUID,
      created_at: Instant,
      category: String,
      payload: String?,
    ) -> T,
  ): T {
    val sql = "SELECT * FROM event WHERE id = ? AND created_at = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getObject(2, OffsetDateTime::class.java).toInstant(),
        getString(3),
        getString(4),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setObject(1, id)
      setObject(2, OffsetDateTime.ofInstant(created_at, ZoneOffset.UTC))
    }
  }

  private fun <T : Any, Return> listEventsByCategory(
    category: String,
    mapper: (
      id: UUID,
      created_at: Instant,
      category: String,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT id, created_at, category FROM event WHERE category = ? ORDER BY created_at DESC"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, UUID::class.java),
        getObject(2, OffsetDateTime::class.java).toInstant(),
        getString(3),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setString(1, category)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> listEventsByCategory(category: String, mapper: (
    id: UUID,
    created_at: Instant,
    category: String,
  ) -> T): Many<T> = listEventsByCategory(category, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun addEvent(category: String, payload: String?) {
    val sql = "INSERT INTO event (category, payload) VALUES (?, ?::jsonb)"
    driver.execute(sql) {
      setString(1, category)
      setString(2, payload)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> addEvent(
    stream: Iterable<Input>,
    category: Input.() -> String,
    payload: Input.() -> String?,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO event (category, payload) VALUES (?, ?::jsonb)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.category())
        setString(2, entry.payload())
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
}
