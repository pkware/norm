package example.crud

import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
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
  override fun <T : Any> insertAuditLog(message: String, mapper: (logged_at: Instant) -> T): T {
    val sql = "INSERT INTO audit_log (message) VALUES (?) RETURNING logged_at"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getObject(1, OffsetDateTime::class.java).toInstant(),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, message)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any, T : Any> insertAuditLog(
    stream: Iterable<Input>,
    message: (Input) -> String,
    mapper: (logged_at: Instant) -> T,
    batchSize: Int,
  ): List<T> {
    val sql = "INSERT INTO audit_log (message) VALUES (?)"
    val columnNames = arrayOf("logged_at")
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
    mapper: (id: Int, created_at: Instant) -> T,
  ): T {
    val sql = "INSERT INTO author (name, bio) VALUES (?, ?) RETURNING id, created_at"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getObject(2, OffsetDateTime::class.java).toInstant(),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setString(1, name)
      setString(2, bio)
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any, T : Any> insertAuthor(
    stream: Iterable<Input>,
    name: (Input) -> String,
    bio: (Input) -> String?,
    mapper: (id: Int, created_at: Instant) -> T,
    batchSize: Int,
  ): List<T> {
    val sql = "INSERT INTO author (name, bio) VALUES (?, ?)"
    val columnNames = arrayOf("id", "created_at")
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
  override fun <T : Any> insertProduct(
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    mapper: (id: Int, total: BigDecimal) -> T,
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
    mapper: (id: Int, total: BigDecimal) -> T,
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
}
