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
import norm.ColumnValue
import norm.Many
import norm.Query
import norm.Transactable
import norm.inputValue

public interface Queries : Transactable {
  /**
   * Lists authors by name, overriding the synthesized CRUD query of the same name.
   *
   * ```sql
   * SELECT id, name FROM author ORDER BY name
   * ```
   */
  public fun <T : Any> findAllAuthor(mapper: (id: Int, name: String) -> T): Many<T>

  /**
   * Lists authors by name, overriding the synthesized CRUD query of the same name.
   *
   * ```sql
   * SELECT id, name FROM author ORDER BY name
   * ```
   */
  public fun findAllAuthor(): Many<FindAllAuthor> = findAllAuthor(::FindAllAuthor)

  public fun <T : Any> findAllAuthorDynamically(mapper: (id: Int, name: String) -> T): Query<T>

  public fun findAllAuthorDynamically(): Query<FindAllAuthor> = findAllAuthorDynamically(::FindAllAuthor)

  /**
   * Returns an author by name.
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
   * Returns an author by name.
   *
   * ```sql
   * SELECT * FROM author WHERE name = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getAuthorByName(name: String): Author = getAuthorByName(name, ::Author)

  /**
   * ```sql
   * INSERT INTO audit_log (message, logged_at) VALUES (?, ?) RETURNING logged_at
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertAuditLog(
    message: String,
    logged_at: ColumnValue<Instant> = ColumnValue.Default,
    mapper: (logged_at: Instant) -> T,
  ): T

  /**
   * ```sql
   * INSERT INTO audit_log (message, logged_at) VALUES (?, ?) RETURNING logged_at
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertAuditLog(
    stream: Iterable<Input>,
    message: (Input) -> String,
    logged_at: ((Input) -> Instant)? = null,
    mapper: (logged_at: Instant) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO audit_log (message, logged_at) VALUES (?, ?) RETURNING logged_at
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertAuditLog(
    stream: Iterable<Input>,
    message: (Input) -> String,
    logged_at: ((Input) -> Instant)? = null,
  ): List<Instant> = insertAuditLog(stream, message, logged_at, ::inputValue, 100)

  /**
   * ```sql
   * INSERT INTO audit_log (message, logged_at) VALUES (?, ?) RETURNING logged_at
   * ```
   */
  @Throws(SQLException::class)
  public fun insertAuditLog(message: String, logged_at: ColumnValue<Instant> = ColumnValue.Default): Instant = insertAuditLog(message, logged_at, ::inputValue)

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
   * INSERT INTO author (name, bio, created_at) VALUES (?, ?, ?) RETURNING id, created_at
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertAuthor(
    name: String,
    bio: String?,
    created_at: ColumnValue<Instant> = ColumnValue.Default,
    mapper: (id: Int, created_at: Instant) -> T,
  ): T

  /**
   * ```sql
   * INSERT INTO author (name, bio, created_at) VALUES (?, ?, ?) RETURNING id, created_at
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertAuthor(
    stream: Iterable<Input>,
    name: (Input) -> String,
    bio: (Input) -> String?,
    created_at: ((Input) -> Instant)? = null,
    mapper: (id: Int, created_at: Instant) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO author (name, bio, created_at) VALUES (?, ?, ?) RETURNING id, created_at
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
    created_at: ((Input) -> Instant)? = null,
  ): List<InsertAuthor> = insertAuthor(stream, name, bio, created_at, ::InsertAuthor, 100)

  /**
   * ```sql
   * INSERT INTO author (name, bio, created_at) VALUES (?, ?, ?) RETURNING id, created_at
   * ```
   */
  @Throws(SQLException::class)
  public fun insertAuthor(
    name: String,
    bio: String?,
    created_at: ColumnValue<Instant> = ColumnValue.Default,
  ): InsertAuthor = insertAuthor(name, bio, created_at, ::InsertAuthor)

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
   * INSERT INTO document (title, metadata) VALUES (?, ?) RETURNING id
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertDocument(
    title: String,
    metadata: String?,
    mapper: (id: Int) -> T,
  ): T

  /**
   * ```sql
   * INSERT INTO document (title, metadata) VALUES (?, ?) RETURNING id
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertDocument(
    stream: Iterable<Input>,
    title: (Input) -> String,
    metadata: (Input) -> String?,
    mapper: (id: Int) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO document (title, metadata) VALUES (?, ?) RETURNING id
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertDocument(
    stream: Iterable<Input>,
    title: (Input) -> String,
    metadata: (Input) -> String?,
  ): List<Int> = insertDocument(stream, title, metadata, ::inputValue, 100)

  /**
   * ```sql
   * INSERT INTO document (title, metadata) VALUES (?, ?) RETURNING id
   * ```
   */
  @Throws(SQLException::class)
  public fun insertDocument(title: String, metadata: String?): Int = insertDocument(title, metadata, ::inputValue)

  /**
   * ```sql
   * SELECT * FROM document WHERE id = ?
   * ```
   */
  public fun <T : Any> findDocumentById(id: Int, mapper: (
    id: Int,
    title: String,
    metadata: String?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM document WHERE id = ?
   * ```
   */
  public fun findDocumentById(id: Int): Many<Document> = findDocumentById(id, ::Document)

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM document WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> existsDocumentById(id: Int, mapper: (exists: Boolean) -> T): T

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM document WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun existsDocumentById(id: Int): Boolean = existsDocumentById(id, ::inputValue)

  /**
   * ```sql
   * DELETE FROM document WHERE id = ?
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
  public fun <Input : Any> deleteDocumentById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * DELETE FROM document WHERE id = ?
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
  public fun <Input : Any> deleteDocumentById(stream: Iterable<Input>, id: (Input) -> Int): IntArray = deleteDocumentById(stream, id, 100)

  /**
   * ```sql
   * DELETE FROM document WHERE id = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteDocumentById(id: Int): Int

  /**
   * ```sql
   * SELECT * FROM document
   * ```
   */
  public fun <T : Any> findAllDocument(mapper: (
    id: Int,
    title: String,
    metadata: String?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM document
   * ```
   */
  public fun findAllDocument(): Many<Document> = findAllDocument(::Document)

  public fun <T : Any> findAllDocumentDynamically(mapper: (
    id: Int,
    title: String,
    metadata: String?,
  ) -> T): Query<T>

  public fun findAllDocumentDynamically(): Query<Document> = findAllDocumentDynamically(::Document)

  /**
   * ```sql
   * SELECT COUNT(*) FROM document
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> countDocument(mapper: (count: Long) -> T): T

  /**
   * ```sql
   * SELECT COUNT(*) FROM document
   * ```
   */
  @Throws(SQLException::class)
  public fun countDocument(): Long = countDocument(::inputValue)

  /**
   * ```sql
   * DELETE FROM document
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllDocument(): Int

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
   * INSERT INTO preference (theme, note) VALUES (?, ?) RETURNING id, theme, note
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertPreference(
    theme: ColumnValue<String> = ColumnValue.Default,
    note: ColumnValue<String?> = ColumnValue.Default,
    mapper: (
      id: Int,
      theme: String,
      note: String?,
    ) -> T,
  ): T

  /**
   * ```sql
   * INSERT INTO preference (theme, note) VALUES (?, ?) RETURNING id, theme, note
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertPreference(
    stream: Iterable<Input>,
    theme: ((Input) -> String)? = null,
    note: ((Input) -> String?)? = null,
    mapper: (
      id: Int,
      theme: String,
      note: String?,
    ) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO preference (theme, note) VALUES (?, ?) RETURNING id, theme, note
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertPreference(
    stream: Iterable<Input>,
    theme: ((Input) -> String)? = null,
    note: ((Input) -> String?)? = null,
  ): List<Preference> = insertPreference(stream, theme, note, ::Preference, 100)

  /**
   * ```sql
   * INSERT INTO preference (theme, note) VALUES (?, ?) RETURNING id, theme, note
   * ```
   */
  @Throws(SQLException::class)
  public fun insertPreference(theme: ColumnValue<String> = ColumnValue.Default, note: ColumnValue<String?> = ColumnValue.Default): Preference = insertPreference(theme, note, ::Preference)

  /**
   * ```sql
   * SELECT * FROM preference WHERE id = ?
   * ```
   */
  public fun <T : Any> findPreferenceById(id: Int, mapper: (
    id: Int,
    theme: String,
    note: String?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM preference WHERE id = ?
   * ```
   */
  public fun findPreferenceById(id: Int): Many<Preference> = findPreferenceById(id, ::Preference)

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM preference WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> existsPreferenceById(id: Int, mapper: (exists: Boolean) -> T): T

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM preference WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun existsPreferenceById(id: Int): Boolean = existsPreferenceById(id, ::inputValue)

  /**
   * ```sql
   * DELETE FROM preference WHERE id = ?
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
  public fun <Input : Any> deletePreferenceById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * DELETE FROM preference WHERE id = ?
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
  public fun <Input : Any> deletePreferenceById(stream: Iterable<Input>, id: (Input) -> Int): IntArray = deletePreferenceById(stream, id, 100)

  /**
   * ```sql
   * DELETE FROM preference WHERE id = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deletePreferenceById(id: Int): Int

  /**
   * ```sql
   * SELECT * FROM preference
   * ```
   */
  public fun <T : Any> findAllPreference(mapper: (
    id: Int,
    theme: String,
    note: String?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM preference
   * ```
   */
  public fun findAllPreference(): Many<Preference> = findAllPreference(::Preference)

  public fun <T : Any> findAllPreferenceDynamically(mapper: (
    id: Int,
    theme: String,
    note: String?,
  ) -> T): Query<T>

  public fun findAllPreferenceDynamically(): Query<Preference> = findAllPreferenceDynamically(::Preference)

  /**
   * ```sql
   * SELECT COUNT(*) FROM preference
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> countPreference(mapper: (count: Long) -> T): T

  /**
   * ```sql
   * SELECT COUNT(*) FROM preference
   * ```
   */
  @Throws(SQLException::class)
  public fun countPreference(): Long = countPreference(::inputValue)

  /**
   * ```sql
   * DELETE FROM preference
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllPreference(): Int

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
    mapper: (id: Int, total: BigDecimal?) -> T,
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
    mapper: (id: Int, total: BigDecimal?) -> T,
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

  /**
   * ```sql
   * INSERT INTO quoted_columns ("Foo", "My Col", "Select") VALUES (?, ?, ?) RETURNING id
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertQuotedColumns(
    Foo: String,
    `My Col`: String?,
    Select: String?,
    mapper: (id: Int) -> T,
  ): T

  /**
   * ```sql
   * INSERT INTO quoted_columns ("Foo", "My Col", "Select") VALUES (?, ?, ?) RETURNING id
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertQuotedColumns(
    stream: Iterable<Input>,
    Foo: (Input) -> String,
    `My Col`: (Input) -> String?,
    Select: (Input) -> String?,
    mapper: (id: Int) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO quoted_columns ("Foo", "My Col", "Select") VALUES (?, ?, ?) RETURNING id
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertQuotedColumns(
    stream: Iterable<Input>,
    Foo: (Input) -> String,
    `My Col`: (Input) -> String?,
    Select: (Input) -> String?,
  ): List<Int> = insertQuotedColumns(stream, Foo, `My Col`, Select, ::inputValue, 100)

  /**
   * ```sql
   * INSERT INTO quoted_columns ("Foo", "My Col", "Select") VALUES (?, ?, ?) RETURNING id
   * ```
   */
  @Throws(SQLException::class)
  public fun insertQuotedColumns(
    Foo: String,
    `My Col`: String?,
    Select: String?,
  ): Int = insertQuotedColumns(Foo, `My Col`, Select, ::inputValue)

  /**
   * ```sql
   * SELECT * FROM quoted_columns WHERE id = ?
   * ```
   */
  public fun <T : Any> findQuotedColumnsById(id: Int, mapper: (
    id: Int,
    Foo: String,
    `My Col`: String?,
    Select: String?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM quoted_columns WHERE id = ?
   * ```
   */
  public fun findQuotedColumnsById(id: Int): Many<QuotedColumns> = findQuotedColumnsById(id, ::QuotedColumns)

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM quoted_columns WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> existsQuotedColumnsById(id: Int, mapper: (exists: Boolean) -> T): T

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM quoted_columns WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun existsQuotedColumnsById(id: Int): Boolean = existsQuotedColumnsById(id, ::inputValue)

  /**
   * ```sql
   * DELETE FROM quoted_columns WHERE id = ?
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
  public fun <Input : Any> deleteQuotedColumnsById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * DELETE FROM quoted_columns WHERE id = ?
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
  public fun <Input : Any> deleteQuotedColumnsById(stream: Iterable<Input>, id: (Input) -> Int): IntArray = deleteQuotedColumnsById(stream, id, 100)

  /**
   * ```sql
   * DELETE FROM quoted_columns WHERE id = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteQuotedColumnsById(id: Int): Int

  /**
   * ```sql
   * SELECT * FROM quoted_columns
   * ```
   */
  public fun <T : Any> findAllQuotedColumns(mapper: (
    id: Int,
    Foo: String,
    `My Col`: String?,
    Select: String?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM quoted_columns
   * ```
   */
  public fun findAllQuotedColumns(): Many<QuotedColumns> = findAllQuotedColumns(::QuotedColumns)

  public fun <T : Any> findAllQuotedColumnsDynamically(mapper: (
    id: Int,
    Foo: String,
    `My Col`: String?,
    Select: String?,
  ) -> T): Query<T>

  public fun findAllQuotedColumnsDynamically(): Query<QuotedColumns> = findAllQuotedColumnsDynamically(::QuotedColumns)

  /**
   * ```sql
   * SELECT COUNT(*) FROM quoted_columns
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> countQuotedColumns(mapper: (count: Long) -> T): T

  /**
   * ```sql
   * SELECT COUNT(*) FROM quoted_columns
   * ```
   */
  @Throws(SQLException::class)
  public fun countQuotedColumns(): Long = countQuotedColumns(::inputValue)

  /**
   * ```sql
   * DELETE FROM quoted_columns
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllQuotedColumns(): Int

  /**
   * ```sql
   * INSERT INTO tag_group (required_tags, optional_tags) VALUES (?, ?) RETURNING id
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> insertTagGroup(
    required_tags: IntSet,
    optional_tags: IntSet?,
    mapper: (id: Int) -> T,
  ): T

  /**
   * ```sql
   * INSERT INTO tag_group (required_tags, optional_tags) VALUES (?, ?) RETURNING id
   * ```
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any, T : Any> insertTagGroup(
    stream: Iterable<Input>,
    required_tags: (Input) -> IntSet,
    optional_tags: (Input) -> IntSet?,
    mapper: (id: Int) -> T,
    batchSize: Int,
  ): List<T>

  /**
   * ```sql
   * INSERT INTO tag_group (required_tags, optional_tags) VALUES (?, ?) RETURNING id
   * ```
   *
   * Uses a batch size of 100.
   *
   * @return A list containing the generated values for each inserted row, in insertion order.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> insertTagGroup(
    stream: Iterable<Input>,
    required_tags: (Input) -> IntSet,
    optional_tags: (Input) -> IntSet?,
  ): List<Int> = insertTagGroup(stream, required_tags, optional_tags, ::inputValue, 100)

  /**
   * ```sql
   * INSERT INTO tag_group (required_tags, optional_tags) VALUES (?, ?) RETURNING id
   * ```
   */
  @Throws(SQLException::class)
  public fun insertTagGroup(required_tags: IntSet, optional_tags: IntSet?): Int = insertTagGroup(required_tags, optional_tags, ::inputValue)

  /**
   * ```sql
   * SELECT * FROM tag_group WHERE id = ?
   * ```
   */
  public fun <T : Any> findTagGroupById(id: Int, mapper: (
    id: Int,
    required_tags: IntSet,
    optional_tags: IntSet?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM tag_group WHERE id = ?
   * ```
   */
  public fun findTagGroupById(id: Int): Many<TagGroup> = findTagGroupById(id, ::TagGroup)

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM tag_group WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> existsTagGroupById(id: Int, mapper: (exists: Boolean) -> T): T

  /**
   * ```sql
   * SELECT EXISTS(SELECT 1 FROM tag_group WHERE id = ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun existsTagGroupById(id: Int): Boolean = existsTagGroupById(id, ::inputValue)

  /**
   * ```sql
   * DELETE FROM tag_group WHERE id = ?
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
  public fun <Input : Any> deleteTagGroupById(
    stream: Iterable<Input>,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * DELETE FROM tag_group WHERE id = ?
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
  public fun <Input : Any> deleteTagGroupById(stream: Iterable<Input>, id: (Input) -> Int): IntArray = deleteTagGroupById(stream, id, 100)

  /**
   * ```sql
   * DELETE FROM tag_group WHERE id = ?
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteTagGroupById(id: Int): Int

  /**
   * ```sql
   * SELECT * FROM tag_group
   * ```
   */
  public fun <T : Any> findAllTagGroup(mapper: (
    id: Int,
    required_tags: IntSet,
    optional_tags: IntSet?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM tag_group
   * ```
   */
  public fun findAllTagGroup(): Many<TagGroup> = findAllTagGroup(::TagGroup)

  public fun <T : Any> findAllTagGroupDynamically(mapper: (
    id: Int,
    required_tags: IntSet,
    optional_tags: IntSet?,
  ) -> T): Query<T>

  public fun findAllTagGroupDynamically(): Query<TagGroup> = findAllTagGroupDynamically(::TagGroup)

  /**
   * ```sql
   * SELECT COUNT(*) FROM tag_group
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> countTagGroup(mapper: (count: Long) -> T): T

  /**
   * ```sql
   * SELECT COUNT(*) FROM tag_group
   * ```
   */
  @Throws(SQLException::class)
  public fun countTagGroup(): Long = countTagGroup(::inputValue)

  /**
   * ```sql
   * DELETE FROM tag_group
   * ```
   *
   * @return The number of rows updated.
   */
  @Throws(SQLException::class)
  public fun deleteAllTagGroup(): Int
}
