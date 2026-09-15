package example.crud

import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.Unit
import kotlin.collections.Iterable
import kotlin.collections.List
import kotlin.jvm.Throws
import norm.ColumnValue
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.Query
import norm.RealTransactable
import norm.combineExecBatchResults
import norm.readGeneratedKeys

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, Return> findAllAuthor(mapper: (id: Int, name: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT id, name FROM author ORDER BY name"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> findAllAuthor(mapper: (id: Int, name: String) -> T): Many<T> = findAllAuthor(mapper, driver::queryMany)

  override fun <T : Any> findAllAuthorDynamically(mapper: (id: Int, name: String) -> T): Query<T> = findAllAuthor(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun <T : Any> getAuthorByName(name: String, mapper: (
    id: Int,
    name: String,
    bio: String?,
    created_at: Instant,
  ) -> T): T {
    val sql = "SELECT * FROM author WHERE name = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
        getObject(4, OffsetDateTime::class.java).toInstant(),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, name)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> insertAuditLog(
    message: String,
    logged_at: ColumnValue<Instant>,
    mapper: (logged_at: Instant) -> T,
  ): T {
    val logged_atPlaceholder = if (logged_at is ColumnValue.Set) "?" else "DEFAULT"
    val sql = "INSERT INTO audit_log (message, logged_at) VALUES (?, " + logged_atPlaceholder + ") RETURNING logged_at"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, OffsetDateTime::class.java).toInstant(),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, message)
      var nextParameterIndex = 1
      if (logged_at is ColumnValue.Set) {
        nextParameterIndex += 1
        val logged_atIndex = nextParameterIndex
        setObject(logged_atIndex, OffsetDateTime.ofInstant(logged_at.value, ZoneOffset.UTC))
      }
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any, T : Any> insertAuditLog(
    stream: Iterable<Input>,
    message: (Input) -> String,
    logged_at: ((Input) -> Instant)?,
    mapper: (logged_at: Instant) -> T,
    batchSize: Int,
  ): List<T> {
    val logged_atPlaceholder = if (logged_at != null) "?" else "DEFAULT"
    val sql = "INSERT INTO audit_log (message, logged_at) VALUES (?, " + logged_atPlaceholder + ")"
    val columnNames = arrayOf("logged_at")
    var nextParameterIndex = 1
    val logged_atIndex: Int? = if (logged_at != null) { nextParameterIndex += 1; nextParameterIndex } else null
    return driver.executeBatchWithGeneratedKeys(sql, columnNames) {
      val rowReader: ResultSet.() -> T = {
        mapper(
          getObject(1, OffsetDateTime::class.java).toInstant(),
        )
      }
      val results = mutableListOf<T>()
      var batchCount = 0
      for (entry in stream) {
        setString(1, message(entry))
        if (logged_atIndex != null) {
          setObject(logged_atIndex, OffsetDateTime.ofInstant(logged_at!!(entry), ZoneOffset.UTC))
        }
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        executeBatch()
        generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
      }
      results
    }
  }

  private fun <T : Any, Return> findAllAuditLog(mapper: (message: String, logged_at: Instant) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM audit_log"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getObject(2, OffsetDateTime::class.java).toInstant(),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> findAllAuditLog(mapper: (message: String, logged_at: Instant) -> T): Many<T> = findAllAuditLog(mapper, driver::queryMany)

  override fun <T : Any> findAllAuditLogDynamically(mapper: (message: String, logged_at: Instant) -> T): Query<T> = findAllAuditLog(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun <T : Any> countAuditLog(mapper: (count: Long) -> T): T {
    val sql = "SELECT COUNT(*) FROM audit_log"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getLong(1),
      )
    }
    return driver.queryOne(sql, rowReader)
  }

  @Throws(SQLException::class)
  override fun deleteAllAuditLog(): Int {
    val sql = "DELETE FROM audit_log"
    return driver.executeRows(sql)
  }

  @Throws(SQLException::class)
  override fun <T : Any> insertAuthor(
    name: String,
    bio: String?,
    created_at: ColumnValue<Instant>,
    mapper: (id: Int, created_at: Instant) -> T,
  ): T {
    val created_atPlaceholder = if (created_at is ColumnValue.Set) "?" else "DEFAULT"
    val sql = "INSERT INTO author (name, bio, created_at) VALUES (?, ?, " + created_atPlaceholder + ") RETURNING id, created_at"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getObject(2, OffsetDateTime::class.java).toInstant(),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, name)
      setString(2, bio)
      var nextParameterIndex = 2
      if (created_at is ColumnValue.Set) {
        nextParameterIndex += 1
        val created_atIndex = nextParameterIndex
        setObject(created_atIndex, OffsetDateTime.ofInstant(created_at.value, ZoneOffset.UTC))
      }
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any, T : Any> insertAuthor(
    stream: Iterable<Input>,
    name: (Input) -> String,
    bio: (Input) -> String?,
    created_at: ((Input) -> Instant)?,
    mapper: (id: Int, created_at: Instant) -> T,
    batchSize: Int,
  ): List<T> {
    val created_atPlaceholder = if (created_at != null) "?" else "DEFAULT"
    val sql = "INSERT INTO author (name, bio, created_at) VALUES (?, ?, " + created_atPlaceholder + ")"
    val columnNames = arrayOf("id", "created_at")
    var nextParameterIndex = 2
    val created_atIndex: Int? = if (created_at != null) { nextParameterIndex += 1; nextParameterIndex } else null
    return driver.executeBatchWithGeneratedKeys(sql, columnNames) {
      val rowReader: ResultSet.() -> T = {
        mapper(
          getInt(1),
          getObject(2, OffsetDateTime::class.java).toInstant(),
        )
      }
      val results = mutableListOf<T>()
      var batchCount = 0
      for (entry in stream) {
        setString(1, name(entry))
        setString(2, bio(entry))
        if (created_atIndex != null) {
          setObject(created_atIndex, OffsetDateTime.ofInstant(created_at!!(entry), ZoneOffset.UTC))
        }
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        executeBatch()
        generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
      }
      results
    }
  }

  private fun <T : Any, Return> findAuthorById(
    id: Int,
    mapper: (
      id: Int,
      name: String,
      bio: String?,
      created_at: Instant,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM author WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
        getObject(4, OffsetDateTime::class.java).toInstant(),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> findAuthorById(id: Int, mapper: (
    id: Int,
    name: String,
    bio: String?,
    created_at: Instant,
  ) -> T): Many<T> = findAuthorById(id, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun <T : Any> existsAuthorById(id: Int, mapper: (exists: Boolean) -> T): T {
    val sql = "SELECT EXISTS(SELECT 1 FROM author WHERE id = ?)"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBoolean(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun deleteAuthorById(id: Int): Int {
    val sql = "DELETE FROM author WHERE id = ?"
    return driver.executeRows(sql) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> deleteAuthorById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "DELETE FROM author WHERE id = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, id(entry))
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
  override fun <T : Any> countAuthor(mapper: (count: Long) -> T): T {
    val sql = "SELECT COUNT(*) FROM author"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getLong(1),
      )
    }
    return driver.queryOne(sql, rowReader)
  }

  @Throws(SQLException::class)
  override fun deleteAllAuthor(): Int {
    val sql = "DELETE FROM author"
    return driver.executeRows(sql)
  }

  @Throws(SQLException::class)
  override fun <T : Any> insertDocument(
    title: String,
    metadata: String?,
    mapper: (id: Int) -> T,
  ): T {
    val sql = "INSERT INTO document (title, metadata) VALUES (?, ?) RETURNING id"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, title)
      setObject(2, metadata, Types.OTHER)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any, T : Any> insertDocument(
    stream: Iterable<Input>,
    title: (Input) -> String,
    metadata: (Input) -> String?,
    mapper: (id: Int) -> T,
    batchSize: Int,
  ): List<T> {
    val sql = "INSERT INTO document (title, metadata) VALUES (?, ?)"
    val columnNames = arrayOf("id")
    return driver.executeBatchWithGeneratedKeys(sql, columnNames) {
      val rowReader: ResultSet.() -> T = {
        mapper(
          getInt(1),
        )
      }
      val results = mutableListOf<T>()
      var batchCount = 0
      for (entry in stream) {
        setString(1, title(entry))
        setObject(2, metadata(entry), Types.OTHER)
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        executeBatch()
        generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
      }
      results
    }
  }

  private fun <T : Any, Return> findDocumentById(
    id: Int,
    mapper: (
      id: Int,
      title: String,
      metadata: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM document WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> findDocumentById(id: Int, mapper: (
    id: Int,
    title: String,
    metadata: String?,
  ) -> T): Many<T> = findDocumentById(id, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun <T : Any> existsDocumentById(id: Int, mapper: (exists: Boolean) -> T): T {
    val sql = "SELECT EXISTS(SELECT 1 FROM document WHERE id = ?)"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBoolean(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun deleteDocumentById(id: Int): Int {
    val sql = "DELETE FROM document WHERE id = ?"
    return driver.executeRows(sql) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> deleteDocumentById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "DELETE FROM document WHERE id = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, id(entry))
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

  private fun <T : Any, Return> findAllDocument(mapper: (
    id: Int,
    title: String,
    metadata: String?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM document"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> findAllDocument(mapper: (
    id: Int,
    title: String,
    metadata: String?,
  ) -> T): Many<T> = findAllDocument(mapper, driver::queryMany)

  override fun <T : Any> findAllDocumentDynamically(mapper: (
    id: Int,
    title: String,
    metadata: String?,
  ) -> T): Query<T> = findAllDocument(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun <T : Any> countDocument(mapper: (count: Long) -> T): T {
    val sql = "SELECT COUNT(*) FROM document"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getLong(1),
      )
    }
    return driver.queryOne(sql, rowReader)
  }

  @Throws(SQLException::class)
  override fun deleteAllDocument(): Int {
    val sql = "DELETE FROM document"
    return driver.executeRows(sql)
  }

  @Throws(SQLException::class)
  override fun insertOrderItem(
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  ) {
    val sql = "INSERT INTO order_item (order_id, item_id, quantity, price) VALUES (?, ?, ?, ?)"
    driver.execute(sql) {
      setInt(1, order_id)
      setInt(2, item_id)
      setInt(3, quantity)
      setBigDecimal(4, price)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> insertOrderItem(
    stream: Iterable<Input>,
    order_id: (Input) -> Int,
    item_id: (Input) -> Int,
    quantity: (Input) -> Int,
    price: (Input) -> BigDecimal,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO order_item (order_id, item_id, quantity, price) VALUES (?, ?, ?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, order_id(entry))
        setInt(2, item_id(entry))
        setInt(3, quantity(entry))
        setBigDecimal(4, price(entry))
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

  private fun <T : Any, Return> findOrderItemByOrderIdAndItemId(
    order_id: Int,
    item_id: Int,
    mapper: (
      order_id: Int,
      item_id: Int,
      quantity: Int,
      price: BigDecimal,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM order_item WHERE order_id = ? AND item_id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getInt(2),
        getInt(3),
        getBigDecimal(4),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, order_id)
      setInt(2, item_id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> findOrderItemByOrderIdAndItemId(
    order_id: Int,
    item_id: Int,
    mapper: (
      order_id: Int,
      item_id: Int,
      quantity: Int,
      price: BigDecimal,
    ) -> T,
  ): Many<T> = findOrderItemByOrderIdAndItemId(order_id, item_id, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun <T : Any> existsOrderItemByOrderIdAndItemId(
    order_id: Int,
    item_id: Int,
    mapper: (exists: Boolean) -> T,
  ): T {
    val sql = "SELECT EXISTS(SELECT 1 FROM order_item WHERE order_id = ? AND item_id = ?)"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBoolean(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, order_id)
      setInt(2, item_id)
    }
  }

  @Throws(SQLException::class)
  override fun deleteOrderItemByOrderIdAndItemId(order_id: Int, item_id: Int): Int {
    val sql = "DELETE FROM order_item WHERE order_id = ? AND item_id = ?"
    return driver.executeRows(sql) {
      setInt(1, order_id)
      setInt(2, item_id)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> deleteOrderItemByOrderIdAndItemId(
    stream: Iterable<Input>,
    order_id: (Input) -> Int,
    item_id: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "DELETE FROM order_item WHERE order_id = ? AND item_id = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, order_id(entry))
        setInt(2, item_id(entry))
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

  private fun <T : Any, Return> findAllOrderItem(mapper: (
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM order_item"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getInt(2),
        getInt(3),
        getBigDecimal(4),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> findAllOrderItem(mapper: (
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  ) -> T): Many<T> = findAllOrderItem(mapper, driver::queryMany)

  override fun <T : Any> findAllOrderItemDynamically(mapper: (
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  ) -> T): Query<T> = findAllOrderItem(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun <T : Any> countOrderItem(mapper: (count: Long) -> T): T {
    val sql = "SELECT COUNT(*) FROM order_item"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getLong(1),
      )
    }
    return driver.queryOne(sql, rowReader)
  }

  @Throws(SQLException::class)
  override fun deleteAllOrderItem(): Int {
    val sql = "DELETE FROM order_item"
    return driver.executeRows(sql)
  }

  @Throws(SQLException::class)
  override fun <T : Any> insertPreference(
    theme: ColumnValue<String>,
    note: ColumnValue<String?>,
    mapper: (
      id: Int,
      theme: String,
      note: String?,
    ) -> T,
  ): T {
    val themePlaceholder = if (theme is ColumnValue.Set) "?" else "DEFAULT"
    val notePlaceholder = if (note is ColumnValue.Set) "?" else "DEFAULT"
    val sql = "INSERT INTO preference (theme, note) VALUES (" + themePlaceholder + ", " + notePlaceholder + ") RETURNING id, theme, note"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
      )
    }
    return driver.queryOne(sql, rowReader) {
      var nextParameterIndex = 0
      if (theme is ColumnValue.Set) {
        nextParameterIndex += 1
        val themeIndex = nextParameterIndex
        setString(themeIndex, theme.value)
      }
      if (note is ColumnValue.Set) {
        nextParameterIndex += 1
        val noteIndex = nextParameterIndex
        setString(noteIndex, note.value)
      }
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any, T : Any> insertPreference(
    stream: Iterable<Input>,
    theme: ((Input) -> String)?,
    note: ((Input) -> String?)?,
    mapper: (
      id: Int,
      theme: String,
      note: String?,
    ) -> T,
    batchSize: Int,
  ): List<T> {
    val themePlaceholder = if (theme != null) "?" else "DEFAULT"
    val notePlaceholder = if (note != null) "?" else "DEFAULT"
    val sql = "INSERT INTO preference (theme, note) VALUES (" + themePlaceholder + ", " + notePlaceholder + ")"
    val columnNames = arrayOf("id", "theme", "note")
    var nextParameterIndex = 0
    val themeIndex: Int? = if (theme != null) { nextParameterIndex += 1; nextParameterIndex } else null
    val noteIndex: Int? = if (note != null) { nextParameterIndex += 1; nextParameterIndex } else null
    return driver.executeBatchWithGeneratedKeys(sql, columnNames) {
      val rowReader: ResultSet.() -> T = {
        mapper(
          getInt(1),
          getString(2),
          getString(3),
        )
      }
      val results = mutableListOf<T>()
      var batchCount = 0
      for (entry in stream) {
        if (themeIndex != null) {
          setString(themeIndex, theme!!(entry))
        }
        if (noteIndex != null) {
          setString(noteIndex, note!!(entry))
        }
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        executeBatch()
        generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
      }
      results
    }
  }

  private fun <T : Any, Return> findPreferenceById(
    id: Int,
    mapper: (
      id: Int,
      theme: String,
      note: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM preference WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> findPreferenceById(id: Int, mapper: (
    id: Int,
    theme: String,
    note: String?,
  ) -> T): Many<T> = findPreferenceById(id, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun <T : Any> existsPreferenceById(id: Int, mapper: (exists: Boolean) -> T): T {
    val sql = "SELECT EXISTS(SELECT 1 FROM preference WHERE id = ?)"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBoolean(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun deletePreferenceById(id: Int): Int {
    val sql = "DELETE FROM preference WHERE id = ?"
    return driver.executeRows(sql) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> deletePreferenceById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "DELETE FROM preference WHERE id = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, id(entry))
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

  private fun <T : Any, Return> findAllPreference(mapper: (
    id: Int,
    theme: String,
    note: String?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM preference"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> findAllPreference(mapper: (
    id: Int,
    theme: String,
    note: String?,
  ) -> T): Many<T> = findAllPreference(mapper, driver::queryMany)

  override fun <T : Any> findAllPreferenceDynamically(mapper: (
    id: Int,
    theme: String,
    note: String?,
  ) -> T): Query<T> = findAllPreference(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun <T : Any> countPreference(mapper: (count: Long) -> T): T {
    val sql = "SELECT COUNT(*) FROM preference"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getLong(1),
      )
    }
    return driver.queryOne(sql, rowReader)
  }

  @Throws(SQLException::class)
  override fun deleteAllPreference(): Int {
    val sql = "DELETE FROM preference"
    return driver.executeRows(sql)
  }

  @Throws(SQLException::class)
  override fun <T : Any> insertProduct(
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    mapper: (id: Int, total: BigDecimal?) -> T,
  ): T {
    val sql = "INSERT INTO product (name, price, tax) VALUES (?, ?, ?) RETURNING id, total"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getBigDecimal(2),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, name)
      setBigDecimal(2, price)
      setBigDecimal(3, tax)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any, T : Any> insertProduct(
    stream: Iterable<Input>,
    name: (Input) -> String,
    price: (Input) -> BigDecimal,
    tax: (Input) -> BigDecimal,
    mapper: (id: Int, total: BigDecimal?) -> T,
    batchSize: Int,
  ): List<T> {
    val sql = "INSERT INTO product (name, price, tax) VALUES (?, ?, ?)"
    val columnNames = arrayOf("id", "total")
    return driver.executeBatchWithGeneratedKeys(sql, columnNames) {
      val rowReader: ResultSet.() -> T = {
        mapper(
          getInt(1),
          getBigDecimal(2),
        )
      }
      val results = mutableListOf<T>()
      var batchCount = 0
      for (entry in stream) {
        setString(1, name(entry))
        setBigDecimal(2, price(entry))
        setBigDecimal(3, tax(entry))
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        executeBatch()
        generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
      }
      results
    }
  }

  private fun <T : Any, Return> findProductById(
    id: Int,
    mapper: (
      id: Int,
      name: String,
      price: BigDecimal,
      tax: BigDecimal,
      total: BigDecimal?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM product WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getBigDecimal(3),
        getBigDecimal(4),
        getBigDecimal(5),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> findProductById(id: Int, mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Many<T> = findProductById(id, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun <T : Any> existsProductById(id: Int, mapper: (exists: Boolean) -> T): T {
    val sql = "SELECT EXISTS(SELECT 1 FROM product WHERE id = ?)"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBoolean(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun deleteProductById(id: Int): Int {
    val sql = "DELETE FROM product WHERE id = ?"
    return driver.executeRows(sql) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> deleteProductById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "DELETE FROM product WHERE id = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, id(entry))
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

  private fun <T : Any, Return> findAllProduct(mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM product"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getBigDecimal(3),
        getBigDecimal(4),
        getBigDecimal(5),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> findAllProduct(mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Many<T> = findAllProduct(mapper, driver::queryMany)

  override fun <T : Any> findAllProductDynamically(mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Query<T> = findAllProduct(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun <T : Any> countProduct(mapper: (count: Long) -> T): T {
    val sql = "SELECT COUNT(*) FROM product"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getLong(1),
      )
    }
    return driver.queryOne(sql, rowReader)
  }

  @Throws(SQLException::class)
  override fun deleteAllProduct(): Int {
    val sql = "DELETE FROM product"
    return driver.executeRows(sql)
  }

  @Throws(SQLException::class)
  override fun <T : Any> insertQuotedColumns(
    Foo: String,
    `My Col`: String?,
    Select: String?,
    mapper: (id: Int) -> T,
  ): T {
    val sql = "INSERT INTO quoted_columns (\"Foo\", \"My Col\", \"Select\") VALUES (?, ?, ?) RETURNING id"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, Foo)
      setString(2, `My Col`)
      setString(3, Select)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any, T : Any> insertQuotedColumns(
    stream: Iterable<Input>,
    Foo: (Input) -> String,
    `My Col`: (Input) -> String?,
    Select: (Input) -> String?,
    mapper: (id: Int) -> T,
    batchSize: Int,
  ): List<T> {
    val sql = "INSERT INTO quoted_columns (\"Foo\", \"My Col\", \"Select\") VALUES (?, ?, ?)"
    val columnNames = arrayOf("id")
    return driver.executeBatchWithGeneratedKeys(sql, columnNames) {
      val rowReader: ResultSet.() -> T = {
        mapper(
          getInt(1),
        )
      }
      val results = mutableListOf<T>()
      var batchCount = 0
      for (entry in stream) {
        setString(1, Foo(entry))
        setString(2, `My Col`(entry))
        setString(3, Select(entry))
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        executeBatch()
        generatedKeys.use { readGeneratedKeys(it, rowReader, results) }
      }
      results
    }
  }

  private fun <T : Any, Return> findQuotedColumnsById(
    id: Int,
    mapper: (
      id: Int,
      Foo: String,
      `My Col`: String?,
      Select: String?,
    ) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = "SELECT * FROM quoted_columns WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
        getString(4),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setInt(1, id)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> findQuotedColumnsById(id: Int, mapper: (
    id: Int,
    Foo: String,
    `My Col`: String?,
    Select: String?,
  ) -> T): Many<T> = findQuotedColumnsById(id, mapper, driver::queryMany)

  @Throws(SQLException::class)
  override fun <T : Any> existsQuotedColumnsById(id: Int, mapper: (exists: Boolean) -> T): T {
    val sql = "SELECT EXISTS(SELECT 1 FROM quoted_columns WHERE id = ?)"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getBoolean(1),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun deleteQuotedColumnsById(id: Int): Int {
    val sql = "DELETE FROM quoted_columns WHERE id = ?"
    return driver.executeRows(sql) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> deleteQuotedColumnsById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray {
    val sql = "DELETE FROM quoted_columns WHERE id = ?"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setInt(1, id(entry))
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

  private fun <T : Any, Return> findAllQuotedColumns(mapper: (
    id: Int,
    Foo: String,
    `My Col`: String?,
    Select: String?,
  ) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM quoted_columns"
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

  override fun <T : Any> findAllQuotedColumns(mapper: (
    id: Int,
    Foo: String,
    `My Col`: String?,
    Select: String?,
  ) -> T): Many<T> = findAllQuotedColumns(mapper, driver::queryMany)

  override fun <T : Any> findAllQuotedColumnsDynamically(mapper: (
    id: Int,
    Foo: String,
    `My Col`: String?,
    Select: String?,
  ) -> T): Query<T> = findAllQuotedColumns(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }

  @Throws(SQLException::class)
  override fun <T : Any> countQuotedColumns(mapper: (count: Long) -> T): T {
    val sql = "SELECT COUNT(*) FROM quoted_columns"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getLong(1),
      )
    }
    return driver.queryOne(sql, rowReader)
  }

  @Throws(SQLException::class)
  override fun deleteAllQuotedColumns(): Int {
    val sql = "DELETE FROM quoted_columns"
    return driver.executeRows(sql)
  }
}
