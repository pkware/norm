package example.typemappings

import com.example.CustomMood
import com.example.JsonData
import com.example.UserPreferences
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
   * SELECT * FROM users WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getUserById(id: Int, mapper: (
    id: Int,
    email: Email,
    age: PositiveInteger?,
    current_mood: CustomMood,
    metadata: JsonData,
    preferences: UserPreferences,
    past_moods: Array<CustomMood?>?,
    tag_list: Array<JsonData?>?,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM users WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getUserById(id: Int): Users = getUserById(id, ::Users)

  /**
   * ```sql
   * INSERT INTO users (email, age, current_mood, metadata, preferences)
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
    current_mood: (Input) -> CustomMood,
    metadata: (Input) -> JsonData,
    preferences: (Input) -> UserPreferences,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO users (email, age, current_mood, metadata, preferences)
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
    current_mood: (Input) -> CustomMood,
    metadata: (Input) -> JsonData,
    preferences: (Input) -> UserPreferences,
  ): IntArray = createUser(stream, email, age, current_mood, metadata, preferences, 100)

  /**
   * ```sql
   * INSERT INTO users (email, age, current_mood, metadata, preferences)
   * VALUES (?, ?, ?, ?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun createUser(
    email: Email,
    age: PositiveInteger?,
    current_mood: CustomMood,
    metadata: JsonData,
    preferences: UserPreferences,
  )

  /**
   * ```sql
   * UPDATE users SET past_moods = ?, tag_list = ? WHERE id = ?
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
  public fun <Input : Any> updatePastMoods(
    stream: Iterable<Input>,
    past_moods: (Input) -> Array<CustomMood?>?,
    tag_list: (Input) -> Array<JsonData?>?,
    id: (Input) -> Int,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * UPDATE users SET past_moods = ?, tag_list = ? WHERE id = ?
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
  public fun <Input : Any> updatePastMoods(
    stream: Iterable<Input>,
    past_moods: (Input) -> Array<CustomMood?>?,
    tag_list: (Input) -> Array<JsonData?>?,
    id: (Input) -> Int,
  ): IntArray = updatePastMoods(stream, past_moods, tag_list, id, 100)

  /**
   * ```sql
   * UPDATE users SET past_moods = ?, tag_list = ? WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun updatePastMoods(
    past_moods: Array<CustomMood?>?,
    tag_list: Array<JsonData?>?,
    id: Int,
  )

  /**
   * ```sql
   * SELECT * FROM documents WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> getDocumentById(id: Int, mapper: (
    id: Int,
    payload: JsonData,
    doc: JsonDocument?,
  ) -> T): T

  /**
   * ```sql
   * SELECT * FROM documents WHERE id = ?
   * ```
   */
  @Throws(SQLException::class)
  public fun getDocumentById(id: Int): Documents = getDocumentById(id, ::Documents)

  /**
   * ```sql
   * INSERT INTO documents (payload, doc) VALUES (?, ?)
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
  public fun <Input : Any> createDocument(
    stream: Iterable<Input>,
    payload: (Input) -> JsonData,
    doc: (Input) -> JsonDocument?,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO documents (payload, doc) VALUES (?, ?)
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
  public fun <Input : Any> createDocument(
    stream: Iterable<Input>,
    payload: (Input) -> JsonData,
    doc: (Input) -> JsonDocument?,
  ): IntArray = createDocument(stream, payload, doc, 100)

  /**
   * ```sql
   * INSERT INTO documents (payload, doc) VALUES (?, ?)
   * ```
   */
  @Throws(SQLException::class)
  public fun createDocument(payload: JsonData, doc: JsonDocument?)

  /**
   * ```sql
   * UPDATE users SET preferences = ? WHERE id = ?
   * RETURNING id, preferences AS old_preferences
   * ```
   */
  public fun <T : Any> updatePreferences(
    preferences: UserPreferences,
    id: Int,
    mapper: (id: Int, old_preferences: UserPreferences) -> T,
  ): Many<T>

  /**
   * ```sql
   * UPDATE users SET preferences = ? WHERE id = ?
   * RETURNING id, preferences AS old_preferences
   * ```
   */
  public fun updatePreferences(preferences: UserPreferences, id: Int): Many<UpdatePreferences> = updatePreferences(preferences, id, ::UpdatePreferences)

  /**
   * Reproduces #212: an INSERT ... SELECT ... RETURNING statement whose RETURNING clause aliases
   * "preferences", a column with a configured column-level type override (users.preferences ->
   * com.example.UserPreferences). Before the fix, parseSelectItems read the INSERT's own SELECT
   * source list instead of the RETURNING clause, so the RETURNING alias's second item borrowed
   * "age" as its originalName instead of "preferences" — missing the (users, preferences) override
   * key entirely and falling back to the type-level jsonb override (com.example.JsonData) instead.
   *
   * ```sql
   * INSERT INTO users (email, age, current_mood, metadata, preferences)
   * SELECT email, age, current_mood, metadata, preferences FROM users WHERE id = ?
   * RETURNING id, preferences AS duplicated_preferences
   * ```
   */
  public fun <T : Any> duplicateUserReturningAliasedPreferences(p1: Int, mapper: (id: Int, duplicated_preferences: UserPreferences) -> T): Many<T>

  /**
   * Reproduces #212: an INSERT ... SELECT ... RETURNING statement whose RETURNING clause aliases
   * "preferences", a column with a configured column-level type override (users.preferences ->
   * com.example.UserPreferences). Before the fix, parseSelectItems read the INSERT's own SELECT
   * source list instead of the RETURNING clause, so the RETURNING alias's second item borrowed
   * "age" as its originalName instead of "preferences" — missing the (users, preferences) override
   * key entirely and falling back to the type-level jsonb override (com.example.JsonData) instead.
   *
   * ```sql
   * INSERT INTO users (email, age, current_mood, metadata, preferences)
   * SELECT email, age, current_mood, metadata, preferences FROM users WHERE id = ?
   * RETURNING id, preferences AS duplicated_preferences
   * ```
   */
  public fun duplicateUserReturningAliasedPreferences(p1: Int): Many<DuplicateUserReturningAliasedPreferences> = duplicateUserReturningAliasedPreferences(p1, ::DuplicateUserReturningAliasedPreferences)
}
