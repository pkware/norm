package example

import java.math.BigDecimal
import java.sql.SQLException
import java.time.OffsetDateTime
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many
import norm.Query
import norm.inputValue

public interface Queries {
  /**
   * User-defined query with the same name as a synthetic one (should take priority)
   */
  public fun <T : Any> findAllAuthor(mapper: (id: Int, name: String) -> T): Many<T>

  /**
   * User-defined query with the same name as a synthetic one (should take priority)
   */
  public fun findAllAuthor(): Many<FindAllAuthor> = findAllAuthor(::FindAllAuthor)

  public fun <T : Any> findAllAuthorDynamically(mapper: (id: Int, name: String) -> T): Query<T>

  public fun findAllAuthorDynamically(): Query<FindAllAuthor> = findAllAuthorDynamically(::FindAllAuthor)

  /**
   * A normal user query that doesn't conflict
   */
  @Throws(SQLException::class)
  public fun <T : Any> getAuthorByName(name: String, mapper: (
    id: Int,
    name: String,
    bio: String?,
    created_at: OffsetDateTime,
  ) -> T): T

  /**
   * A normal user query that doesn't conflict
   */
  @Throws(SQLException::class)
  public fun getAuthorByName(name: String): Author = getAuthorByName(name, ::Author)

  @Throws(SQLException::class)
  public fun <T : Any> insertAuditLog(message: String, mapper: (logged_at: OffsetDateTime) -> T): T

  @Throws(SQLException::class)
  public fun insertAuditLog(message: String): OffsetDateTime = insertAuditLog(message, ::inputValue)

  public fun <T : Any> findAllAuditLog(mapper: (message: String, logged_at: OffsetDateTime) -> T): Many<T>

  public fun findAllAuditLog(): Many<AuditLog> = findAllAuditLog(::AuditLog)

  public fun <T : Any> findAllAuditLogDynamically(mapper: (message: String, logged_at: OffsetDateTime) -> T): Query<T>

  public fun findAllAuditLogDynamically(): Query<AuditLog> = findAllAuditLogDynamically(::AuditLog)

  @Throws(SQLException::class)
  public fun <T> countAuditLog(mapper: (count: Long?) -> T): T

  @Throws(SQLException::class)
  public fun countAuditLog(): Long? = countAuditLog(::inputValue)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllAuditLog(): Int

  @Throws(SQLException::class)
  public fun <T : Any> insertAuthor(
    name: String,
    bio: String?,
    mapper: (id: Int, created_at: OffsetDateTime) -> T,
  ): T

  @Throws(SQLException::class)
  public fun insertAuthor(name: String, bio: String?): InsertAuthor = insertAuthor(name, bio, ::InsertAuthor)

  public fun <T : Any> findAuthorById(id: Int, mapper: (
    id: Int,
    name: String,
    bio: String?,
    created_at: OffsetDateTime,
  ) -> T): Many<T>

  public fun findAuthorById(id: Int): Many<Author> = findAuthorById(id, ::Author)

  @Throws(SQLException::class)
  public fun <T> existsAuthorById(id: Int, mapper: (exists: Boolean?) -> T): T

  @Throws(SQLException::class)
  public fun existsAuthorById(id: Int): Boolean? = existsAuthorById(id, ::inputValue)

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
  public fun <Input : Any> deleteAuthorById(
    stream: Iterable<Input>,
    id: Input.() -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [deleteAuthorById] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> deleteAuthorById(stream: Iterable<Input>, id: Input.() -> Int): IntArray = deleteAuthorById(stream, id, 100)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAuthorById(id: Int): Int

  @Throws(SQLException::class)
  public fun <T> countAuthor(mapper: (count: Long?) -> T): T

  @Throws(SQLException::class)
  public fun countAuthor(): Long? = countAuthor(::inputValue)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllAuthor(): Int

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
  public fun <Input : Any> insertOrderItem(
    stream: Iterable<Input>,
    order_id: Input.() -> Int,
    item_id: Input.() -> Int,
    quantity: Input.() -> Int,
    price: Input.() -> BigDecimal,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [insertOrderItem] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertOrderItem(
    stream: Iterable<Input>,
    order_id: Input.() -> Int,
    item_id: Input.() -> Int,
    quantity: Input.() -> Int,
    price: Input.() -> BigDecimal,
  ): IntArray = insertOrderItem(stream, order_id, item_id, quantity, price, 100)

  /**
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun insertOrderItem(
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  )

  public fun <T : Any> findOrderItemById(
    order_id: Int,
    item_id: Int,
    mapper: (
      order_id: Int,
      item_id: Int,
      quantity: Int,
      price: BigDecimal,
    ) -> T,
  ): Many<T>

  public fun findOrderItemById(order_id: Int, item_id: Int): Many<OrderItem> = findOrderItemById(order_id, item_id, ::OrderItem)

  @Throws(SQLException::class)
  public fun <T> existsOrderItemById(
    order_id: Int,
    item_id: Int,
    mapper: (exists: Boolean?) -> T,
  ): T

  @Throws(SQLException::class)
  public fun existsOrderItemById(order_id: Int, item_id: Int): Boolean? = existsOrderItemById(order_id, item_id, ::inputValue)

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
  public fun <Input : Any> deleteOrderItemById(
    stream: Iterable<Input>,
    order_id: Input.() -> Int,
    item_id: Input.() -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [deleteOrderItemById] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> deleteOrderItemById(
    stream: Iterable<Input>,
    order_id: Input.() -> Int,
    item_id: Input.() -> Int,
  ): IntArray = deleteOrderItemById(stream, order_id, item_id, 100)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteOrderItemById(order_id: Int, item_id: Int): Int

  public fun <T : Any> findAllOrderItem(mapper: (
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  ) -> T): Many<T>

  public fun findAllOrderItem(): Many<OrderItem> = findAllOrderItem(::OrderItem)

  public fun <T : Any> findAllOrderItemDynamically(mapper: (
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  ) -> T): Query<T>

  public fun findAllOrderItemDynamically(): Query<OrderItem> = findAllOrderItemDynamically(::OrderItem)

  @Throws(SQLException::class)
  public fun <T> countOrderItem(mapper: (count: Long?) -> T): T

  @Throws(SQLException::class)
  public fun countOrderItem(): Long? = countOrderItem(::inputValue)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllOrderItem(): Int

  @Throws(SQLException::class)
  public fun <T : Any> insertProduct(
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    mapper: (id: Int, total: BigDecimal?) -> T,
  ): T

  @Throws(SQLException::class)
  public fun insertProduct(
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
  ): InsertProduct = insertProduct(name, price, tax, ::InsertProduct)

  public fun <T : Any> findProductById(id: Int, mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Many<T>

  public fun findProductById(id: Int): Many<Product> = findProductById(id, ::Product)

  @Throws(SQLException::class)
  public fun <T> existsProductById(id: Int, mapper: (exists: Boolean?) -> T): T

  @Throws(SQLException::class)
  public fun existsProductById(id: Int): Boolean? = existsProductById(id, ::inputValue)

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
  public fun <Input : Any> deleteProductById(
    stream: Iterable<Input>,
    id: Input.() -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [deleteProductById] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> deleteProductById(stream: Iterable<Input>, id: Input.() -> Int): IntArray = deleteProductById(stream, id, 100)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteProductById(id: Int): Int

  public fun <T : Any> findAllProduct(mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Many<T>

  public fun findAllProduct(): Many<Product> = findAllProduct(::Product)

  public fun <T : Any> findAllProductDynamically(mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Query<T>

  public fun findAllProductDynamically(): Query<Product> = findAllProductDynamically(::Product)

  @Throws(SQLException::class)
  public fun <T> countProduct(mapper: (count: Long?) -> T): T

  @Throws(SQLException::class)
  public fun countProduct(): Long? = countProduct(::inputValue)

  /**
   * Norm: Executes a SQL statement and returns the number of rows updated.
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllProduct(): Int
}
