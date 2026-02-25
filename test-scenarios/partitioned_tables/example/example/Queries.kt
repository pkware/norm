package example

import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many

public interface Queries {
  /**
   * Star projection against a partitioned table.
   */
  @Throws(SQLException::class)
  public fun <T : Any> getEventById(
    id: UUID,
    created_at: OffsetDateTime,
    mapper: (
      id: UUID,
      created_at: OffsetDateTime,
      category: String,
      payload: String?,
    ) -> T,
  ): T

  /**
   * Star projection against a partitioned table.
   */
  @Throws(SQLException::class)
  public fun getEventById(id: UUID, created_at: OffsetDateTime): Event = getEventById(id, created_at, ::Event)

  public fun <T : Any> listEventsByCategory(category: String, mapper: (
    id: UUID,
    created_at: OffsetDateTime,
    category: String,
  ) -> T): Many<T>

  public fun listEventsByCategory(category: String): Many<ListEventsByCategory> = listEventsByCategory(category, ::ListEventsByCategory)

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
  public fun <Input : Any> addEvent(
    stream: Iterable<Input>,
    category: Input.() -> String,
    payload: Input.() -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [addEvent] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> addEvent(
    stream: Iterable<Input>,
    category: Input.() -> String,
    payload: Input.() -> String?,
  ): IntArray = addEvent(stream, category, payload, 100)

  /**
   * Norm: Executes a SQL statement.
   *
   * @param category Event category.
   * @param payload Event payload. Null when the event carries no extra data.
   */
  @Throws(SQLException::class)
  public fun addEvent(category: String, payload: String?)
}
