package example

import java.sql.SQLException
import java.sql.Statement.EXECUTE_FAILED
import java.sql.Statement.SUCCESS_NO_INFO
import kotlin.Any
import kotlin.Array
import kotlin.Int
import kotlin.IntArray
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many
import norm.Transactable

public interface Queries : Transactable {
  /**
   * ```sql
   * SELECT * FROM users WHERE email = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getUserByEmail(email: Email, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM users WHERE email = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getUserByEmail(email: Email): Users = getUserByEmail(email, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE age > ?
   * ```
   */
  public fun <T : Any> listUsersByAge(age: PositiveInteger, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE age > ?
   * ```
   */
  public fun listUsersByAge(age: PositiveInteger): Many<Users> = listUsersByAge(age, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE zip_code = ?
   * ```
   */
  public fun <T : Any> getUsersByZipCode(zip_code: UsPostalCode, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE zip_code = ?
   * ```
   */
  public fun getUsersByZipCode(zip_code: UsPostalCode): Many<Users> = getUsersByZipCode(zip_code, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE current_mood = ?
   * ```
   */
  public fun <T : Any> getUsersByMood(current_mood: Mood, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE current_mood = ?
   * ```
   */
  public fun getUsersByMood(current_mood: Mood): Many<Users> = getUsersByMood(current_mood, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE placed_at > ?
   * ```
   */
  public fun <T : Any> getUsersPlacedAfter(placed_at: OrderPlacedAt, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE placed_at > ?
   * ```
   */
  public fun getUsersPlacedAfter(placed_at: OrderPlacedAt): Many<Users> = getUsersPlacedAfter(placed_at, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE external_ref = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getUserByExternalReference(external_ref: ExternalReference, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM users WHERE external_ref = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getUserByExternalReference(external_ref: ExternalReference): Users = getUserByExternalReference(external_ref, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE preferred_date = ?
   * ```
   */
  public fun <T : Any> getUsersByPreferredDate(preferred_date: PreferredDate, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE preferred_date = ?
   * ```
   */
  public fun getUsersByPreferredDate(preferred_date: PreferredDate): Many<Users> = getUsersByPreferredDate(preferred_date, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE opening_time = ?
   * ```
   */
  public fun <T : Any> getUsersByOpeningTime(opening_time: OpeningTime, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE opening_time = ?
   * ```
   */
  public fun getUsersByOpeningTime(opening_time: OpeningTime): Many<Users> = getUsersByOpeningTime(opening_time, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE meeting_time_tz = ?
   * ```
   */
  public fun <T : Any> getUsersByMeetingTimeTz(meeting_time_tz: MeetingTimeTz, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE meeting_time_tz = ?
   * ```
   */
  public fun getUsersByMeetingTimeTz(meeting_time_tz: MeetingTimeTz): Many<Users> = getUsersByMeetingTimeTz(meeting_time_tz, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE created_at_local = ?
   * ```
   */
  public fun <T : Any> getUsersByCreatedAtLocal(created_at_local: CreatedAtLocal, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE created_at_local = ?
   * ```
   */
  public fun getUsersByCreatedAtLocal(created_at_local: CreatedAtLocal): Many<Users> = getUsersByCreatedAtLocal(created_at_local, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE large_object_ref = ?
   * ```
   */
  public fun <T : Any> getUsersByLargeObjectRef(large_object_ref: LargeObjectRef, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE large_object_ref = ?
   * ```
   */
  public fun getUsersByLargeObjectRef(large_object_ref: LargeObjectRef): Many<Users> = getUsersByLargeObjectRef(large_object_ref, ::Users)

  /**
   * ```sql
   * SELECT * FROM users WHERE thumbnail = ?
   * ```
   */
  public fun <T : Any> getUsersByThumbnail(thumbnail: Thumbnail, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    placed_at: OrderPlacedAt,
    external_ref: ExternalReference?,
    preferred_date: PreferredDate?,
    opening_time: OpeningTime?,
    meeting_time_tz: MeetingTimeTz?,
    created_at_local: CreatedAtLocal?,
    large_object_ref: LargeObjectRef?,
    thumbnail: Thumbnail?,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM users WHERE thumbnail = ?
   * ```
   */
  public fun getUsersByThumbnail(thumbnail: Thumbnail): Many<Users> = getUsersByThumbnail(thumbnail, ::Users)

  /**
   * ```sql
   * INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
   * VALUES (?, ?, ?, ?, ?)
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
  public fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: (Input) -> Email,
    age: (Input) -> PositiveInteger?,
    zip_code: (Input) -> UsPostalCode?,
    current_mood: (Input) -> Mood,
    previous_mood: (Input) -> Mood?,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
   * VALUES (?, ?, ?, ?, ?)
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
  public fun <Input : Any> createUser(
    stream: Iterable<Input>,
    email: (Input) -> Email,
    age: (Input) -> PositiveInteger?,
    zip_code: (Input) -> UsPostalCode?,
    current_mood: (Input) -> Mood,
    previous_mood: (Input) -> Mood?,
  ): IntArray = createUser(stream, email, age, zip_code, current_mood, previous_mood, 100)

  /**
   * ```sql
   * INSERT INTO users (email, age, zip_code, current_mood, previous_mood)
   * VALUES (?, ?, ?, ?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun createUser(
    email: Email,
    age: PositiveInteger?,
    zip_code: UsPostalCode?,
    current_mood: Mood,
    previous_mood: Mood?,
  )

  /**
   * ```sql
   * UPDATE users
   * SET
   *   email = coalesce(?, users.email),
   *   age = coalesce(?, users.age),
   *   zip_code = coalesce(?, users.zip_code)
   * WHERE id = ?
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
  public fun <Input : Any> updateUser(
    stream: Iterable<Input>,
    email: (Input) -> Email?,
    age: (Input) -> PositiveInteger?,
    zipCode: (Input) -> UsPostalCode?,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE users
   * SET
   *   email = coalesce(?, users.email),
   *   age = coalesce(?, users.age),
   *   zip_code = coalesce(?, users.zip_code)
   * WHERE id = ?
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
  public fun <Input : Any> updateUser(
    stream: Iterable<Input>,
    email: (Input) -> Email?,
    age: (Input) -> PositiveInteger?,
    zipCode: (Input) -> UsPostalCode?,
    id: (Input) -> Int,
  ): IntArray = updateUser(stream, email, age, zipCode, id, 100)

  /**
   * ```sql
   * UPDATE users
   * SET
   *   email = coalesce(?, users.email),
   *   age = coalesce(?, users.age),
   *   zip_code = coalesce(?, users.zip_code)
   * WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun updateUser(
    email: Email?,
    age: PositiveInteger?,
    zipCode: UsPostalCode?,
    id: Int,
  )

  /**
   * ```sql
   * UPDATE users
   * SET
   *   current_mood = ?,
   *   previous_mood = ?
   * WHERE id = ?
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
  public fun <Input : Any> updateMood(
    stream: Iterable<Input>,
    current_mood: (Input) -> Mood,
    previous_mood: (Input) -> Mood?,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE users
   * SET
   *   current_mood = ?,
   *   previous_mood = ?
   * WHERE id = ?
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
  public fun <Input : Any> updateMood(
    stream: Iterable<Input>,
    current_mood: (Input) -> Mood,
    previous_mood: (Input) -> Mood?,
    id: (Input) -> Int,
  ): IntArray = updateMood(stream, current_mood, previous_mood, id, 100)

  /**
   * ```sql
   * UPDATE users
   * SET
   *   current_mood = ?,
   *   previous_mood = ?
   * WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun updateMood(
    current_mood: Mood,
    previous_mood: Mood?,
    id: Int,
  )

  /**
   * ```sql
   * UPDATE users
   * SET
   *   past_moods = ?,
   *   scores = ?
   * WHERE id = ?
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
  public fun <Input : Any> updateArrayColumns(
    stream: Iterable<Input>,
    past_moods: (Input) -> Array<Mood?>?,
    scores: (Input) -> Array<PositiveInteger?>?,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE users
   * SET
   *   past_moods = ?,
   *   scores = ?
   * WHERE id = ?
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
  public fun <Input : Any> updateArrayColumns(
    stream: Iterable<Input>,
    past_moods: (Input) -> Array<Mood?>?,
    scores: (Input) -> Array<PositiveInteger?>?,
    id: (Input) -> Int,
  ): IntArray = updateArrayColumns(stream, past_moods, scores, id, 100)

  /**
   * ```sql
   * UPDATE users
   * SET
   *   past_moods = ?,
   *   scores = ?
   * WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun updateArrayColumns(
    past_moods: Array<Mood?>?,
    scores: Array<PositiveInteger?>?,
    id: Int,
  )
}
