package example.crud

import java.math.BigDecimal
import java.sql.SQLException
import java.sql.Statement.EXECUTE_FAILED
import java.sql.Statement.SUCCESS_NO_INFO
import java.time.Instant
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.collections.Iterable
import kotlin.collections.List
import kotlin.jvm.Throws
import norm.Many
import norm.Query
import norm.Transactable
import norm.inputValue

public interface Queries : Transactable {
  /**
   * User-defined query with the same name as a synthetic one (should take priority)
   *
   * ```sql
   * SELECT id, name FROM author ORDER BY name
   * ```
   */
  public fun <T : Any> findAllAuthor(mapper: (id: Int, name: String) -> T): Many<T>

  /**
   * User-defined query with the same name as a synthetic one (should take priority)
   *
   * ```sql
   * SELECT id, name FROM author ORDER BY name
   * ```
   */
  public fun findAllAuthor(): Many<FindAllAuthor> = findAllAuthor(::FindAllAuthor)

  public fun <T : Any> findAllAuthorDynamically(mapper: (id: Int, name: String) -> T): Query<T>

  public fun findAllAuthorDynamically(): Query<FindAllAuthor> = findAllAuthorDynamically(::FindAllAuthor)

  /**
   * A normal user query that doesn't conflict
   *
   * ```sql
   * SELECT * FROM author WHERE name = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getAuthorByName(name: String, mapper: (
    id: Int,
    name: String,
    bio: String?,
    created_at: Instant,
  ) -> T): T

  /**
   * A normal user query that doesn't conflict
   *
   * ```sql
   * SELECT * FROM author WHERE name = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getAuthorByName(name: String): Author = getAuthorByName(name, ::Author)

  /**
   * ```sql
   * INSERT INTO audit_log (message) VALUES (?) RETURNING logged_at
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertAuditLog(message: String, mapper: (logged_at: Instant) -> T): T

  /**
   * ```sql
   * INSERT INTO audit_log (message) VALUES (?) RETURNING logged_at
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertAuditLog(
    stream: Iterable<Input>,
    message: (Input) -> String,
    mapper: (logged_at: Instant) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO audit_log (message) VALUES (?) RETURNING logged_at
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertAuditLog(stream: Iterable<Input>, message: (Input) -> String): List<Instant> = insertAuditLog(stream, message, ::inputValue, 100)

  /**
   * ```sql
   * INSERT INTO audit_log (message) VALUES (?) RETURNING logged_at
   * ```
   */
  @Throws(SQLException::class)
  public fun insertAuditLog(message: String): Instant = insertAuditLog(message, ::inputValue)

  /**
   * ```sql
   * SELECT * FROM audit_log
   * ```
   */
  public fun <T : Any> findAllAuditLog(mapper: (message: String, logged_at: Instant) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM audit_log
   * ```
   */
  public fun findAllAuditLog(): Many<AuditLog> = findAllAuditLog(::AuditLog)

  public fun <T : Any> findAllAuditLogDynamically(mapper: (message: String, logged_at: Instant) -> T): Query<T>

  public fun findAllAuditLogDynamically(): Query<AuditLog> = findAllAuditLogDynamically(::AuditLog)

  /**
   * ```sql
   * SELECT COUNT(*) FROM audit_log
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> countAuditLog(mapper: (count: Long) -> T): T

  /**
   * ```sql
   * SELECT COUNT(*) FROM audit_log
   * ```
   */
  @Throws(SQLException::class)
  public fun countAuditLog(): Long = countAuditLog(::inputValue)

  /**
   * ```sql
   * DELETE FROM audit_log
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllAuditLog(): Int

  /**
   * ```sql
   * INSERT INTO author (name, bio) VALUES (?, ?) RETURNING id, created_at
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertAuthor(
    name: String,
    bio: String?,
    mapper: (id: Int, created_at: Instant) -> T,
  ): T

  /**
   * ```sql
   * INSERT INTO author (name, bio) VALUES (?, ?) RETURNING id, created_at
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertAuthor(
    stream: Iterable<Input>,
    name: (Input) -> String,
    bio: (Input) -> String?,
    mapper: (id: Int, created_at: Instant) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO author (name, bio) VALUES (?, ?) RETURNING id, created_at
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertAuthor(
    stream: Iterable<Input>,
    name: (Input) -> String,
    bio: (Input) -> String?,
  ): List<InsertAuthor> = insertAuthor(stream, name, bio, ::InsertAuthor, 100)

  /**
   * ```sql
   * INSERT INTO author (name, bio) VALUES (?, ?) RETURNING id, created_at
   * ```
   */
  @Throws(SQLException::class)
  public fun insertAuthor(name: String, bio: String?): InsertAuthor = insertAuthor(name, bio, ::InsertAuthor)

  /**
   * ```sql
   * SELECT * FROM author WHERE id = ?
   * ```
   */
  public fun <T : Any> findAuthorById(id: Int, mapper: (
    id: Int,
    name: String,
    bio: String?,
    created_at: Instant,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM author WHERE id = ?
   * ```
   */
  public fun findAuthorById(id: Int): Many<Author> = findAuthorById(id, ::Author)

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM author WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> existsAuthorById(id: Int, mapper: (exists: Boolean) -> T): T

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM author WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun existsAuthorById(id: Int): Boolean = existsAuthorById(id, ::inputValue)

  /**
   * ```sql
   * DELETE FROM author WHERE id = ?
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
  public fun <Input : Any> deleteAuthorById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * DELETE FROM author WHERE id = ?
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
  public fun <Input : Any> deleteAuthorById(stream: Iterable<Input>, id: (Input) -> Int): IntArray = deleteAuthorById(stream, id, 100)

  /**
   * ```sql
   * DELETE FROM author WHERE id = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAuthorById(id: Int): Int

  /**
   * ```sql
   * SELECT COUNT(*) FROM author
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> countAuthor(mapper: (count: Long) -> T): T

  /**
   * ```sql
   * SELECT COUNT(*) FROM author
   * ```
   */
  @Throws(SQLException::class)
  public fun countAuthor(): Long = countAuthor(::inputValue)

  /**
   * ```sql
   * DELETE FROM author
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllAuthor(): Int

  /**
   * ```sql
   * INSERT INTO order_item (order_id, item_id, quantity, price) VALUES (?, ?, ?, ?)
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
  public fun <Input : Any> insertOrderItem(
    stream: Iterable<Input>,
    order_id: (Input) -> Int,
    item_id: (Input) -> Int,
    quantity: (Input) -> Int,
    price: (Input) -> BigDecimal,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO order_item (order_id, item_id, quantity, price) VALUES (?, ?, ?, ?)
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
  public fun <Input : Any> insertOrderItem(
    stream: Iterable<Input>,
    order_id: (Input) -> Int,
    item_id: (Input) -> Int,
    quantity: (Input) -> Int,
    price: (Input) -> BigDecimal,
  ): IntArray = insertOrderItem(stream, order_id, item_id, quantity, price, 100)

  /**
   * ```sql
   * INSERT INTO order_item (order_id, item_id, quantity, price) VALUES (?, ?, ?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun insertOrderItem(
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  )

  /**
   * ```sql
   * SELECT * FROM order_item WHERE order_id = ? AND item_id = ?
   * ```
   */
  public fun <T : Any> findOrderItemByOrderIdAndItemId(
    order_id: Int,
    item_id: Int,
    mapper: (
      order_id: Int,
      item_id: Int,
      quantity: Int,
      price: BigDecimal,
    ) -> T,
  ): Many<T>

  /**
   * ```sql
   * SELECT * FROM order_item WHERE order_id = ? AND item_id = ?
   * ```
   */
  public fun findOrderItemByOrderIdAndItemId(order_id: Int, item_id: Int): Many<OrderItem> = findOrderItemByOrderIdAndItemId(order_id, item_id, ::OrderItem)

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM order_item WHERE order_id = ? AND item_id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> existsOrderItemByOrderIdAndItemId(
    order_id: Int,
    item_id: Int,
    mapper: (exists: Boolean) -> T,
  ): T

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM order_item WHERE order_id = ? AND item_id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun existsOrderItemByOrderIdAndItemId(order_id: Int, item_id: Int): Boolean = existsOrderItemByOrderIdAndItemId(order_id, item_id, ::inputValue)

  /**
   * ```sql
   * DELETE FROM order_item WHERE order_id = ? AND item_id = ?
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
  public fun <Input : Any> deleteOrderItemByOrderIdAndItemId(
    stream: Iterable<Input>,
    order_id: (Input) -> Int,
    item_id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * DELETE FROM order_item WHERE order_id = ? AND item_id = ?
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
  public fun <Input : Any> deleteOrderItemByOrderIdAndItemId(
    stream: Iterable<Input>,
    order_id: (Input) -> Int,
    item_id: (Input) -> Int,
  ): IntArray = deleteOrderItemByOrderIdAndItemId(stream, order_id, item_id, 100)

  /**
   * ```sql
   * DELETE FROM order_item WHERE order_id = ? AND item_id = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteOrderItemByOrderIdAndItemId(order_id: Int, item_id: Int): Int

  /**
   * ```sql
   * SELECT * FROM order_item
   * ```
   */
  public fun <T : Any> findAllOrderItem(mapper: (
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM order_item
   * ```
   */
  public fun findAllOrderItem(): Many<OrderItem> = findAllOrderItem(::OrderItem)

  public fun <T : Any> findAllOrderItemDynamically(mapper: (
    order_id: Int,
    item_id: Int,
    quantity: Int,
    price: BigDecimal,
  ) -> T): Query<T>

  public fun findAllOrderItemDynamically(): Query<OrderItem> = findAllOrderItemDynamically(::OrderItem)

  /**
   * ```sql
   * SELECT COUNT(*) FROM order_item
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> countOrderItem(mapper: (count: Long) -> T): T

  /**
   * ```sql
   * SELECT COUNT(*) FROM order_item
   * ```
   */
  @Throws(SQLException::class)
  public fun countOrderItem(): Long = countOrderItem(::inputValue)

  /**
   * ```sql
   * DELETE FROM order_item
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllOrderItem(): Int

  /**
   * ```sql
   * INSERT INTO product (name, price, tax) VALUES (?, ?, ?) RETURNING id, total
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertProduct(
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    mapper: (id: Int, total: BigDecimal) -> T,
  ): T

  /**
   * ```sql
   * INSERT INTO product (name, price, tax) VALUES (?, ?, ?) RETURNING id, total
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertProduct(
    stream: Iterable<Input>,
    name: (Input) -> String,
    price: (Input) -> BigDecimal,
    tax: (Input) -> BigDecimal,
    mapper: (id: Int, total: BigDecimal) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO product (name, price, tax) VALUES (?, ?, ?) RETURNING id, total
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertProduct(
    stream: Iterable<Input>,
    name: (Input) -> String,
    price: (Input) -> BigDecimal,
    tax: (Input) -> BigDecimal,
  ): List<InsertProduct> = insertProduct(stream, name, price, tax, ::InsertProduct, 100)

  /**
   * ```sql
   * INSERT INTO product (name, price, tax) VALUES (?, ?, ?) RETURNING id, total
   * ```
   */
  @Throws(SQLException::class)
  public fun insertProduct(
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
  ): InsertProduct = insertProduct(name, price, tax, ::InsertProduct)

  /**
   * ```sql
   * SELECT * FROM product WHERE id = ?
   * ```
   */
  public fun <T : Any> findProductById(id: Int, mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM product WHERE id = ?
   * ```
   */
  public fun findProductById(id: Int): Many<Product> = findProductById(id, ::Product)

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM product WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> existsProductById(id: Int, mapper: (exists: Boolean) -> T): T

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM product WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun existsProductById(id: Int): Boolean = existsProductById(id, ::inputValue)

  /**
   * ```sql
   * DELETE FROM product WHERE id = ?
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
  public fun <Input : Any> deleteProductById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * DELETE FROM product WHERE id = ?
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
  public fun <Input : Any> deleteProductById(stream: Iterable<Input>, id: (Input) -> Int): IntArray = deleteProductById(stream, id, 100)

  /**
   * ```sql
   * DELETE FROM product WHERE id = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteProductById(id: Int): Int

  /**
   * ```sql
   * SELECT * FROM product
   * ```
   */
  public fun <T : Any> findAllProduct(mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM product
   * ```
   */
  public fun findAllProduct(): Many<Product> = findAllProduct(::Product)

  public fun <T : Any> findAllProductDynamically(mapper: (
    id: Int,
    name: String,
    price: BigDecimal,
    tax: BigDecimal,
    total: BigDecimal?,
  ) -> T): Query<T>

  public fun findAllProductDynamically(): Query<Product> = findAllProductDynamically(::Product)

  /**
   * ```sql
   * SELECT COUNT(*) FROM product
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> countProduct(mapper: (count: Long) -> T): T

  /**
   * ```sql
   * SELECT COUNT(*) FROM product
   * ```
   */
  @Throws(SQLException::class)
  public fun countProduct(): Long = countProduct(::inputValue)

  /**
   * ```sql
   * DELETE FROM product
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllProduct(): Int
}
