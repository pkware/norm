package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Double
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.Many
import norm.Transacter
import norm.inputValue

public interface Queries : Transacter {
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
  public fun <Input : Any> createUser(
    stream: Iterable<Input>,
    username: Input.() -> String,
    crypt: Input.() -> String,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [createUser] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> createUser(
    stream: Iterable<Input>,
    username: Input.() -> String,
    crypt: Input.() -> String,
  ): IntArray = createUser(stream, username, crypt, 100)

  /**
   * Query using pgcrypto for password hashing
   *
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun createUser(username: String, crypt: String)

  /**
   * Query using pgcrypto for password verification
   */
  @Throws(SQLException::class)
  public fun <T : Any> verifyPassword(
    username: String,
    crypt: String,
    mapper: (valid: Boolean) -> T,
  ): T

  /**
   * Query using pgcrypto for password verification
   */
  @Throws(SQLException::class)
  public fun verifyPassword(username: String, crypt: String): Boolean = verifyPassword(username, crypt, ::inputValue)

  /**
   * Query using settings table (for potential tablefunc pivot)
   */
  public fun <T : Any> getUserSettings(user_id: Int, mapper: (setting_key: String, setting_value: String?) -> T): Many<T>

  /**
   * Query using settings table (for potential tablefunc pivot)
   */
  public fun getUserSettings(user_id: Int): Many<GetUserSettings> = getUserSettings(user_id, ::GetUserSettings)

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
  public fun <Input : Any> setSetting(
    stream: Iterable<Input>,
    user_id: Input.() -> Int,
    setting_key: Input.() -> String,
    setting_value: Input.() -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * Norm: Invokes [setSetting] with a batch size of 100.
   */
  @Throws(SQLException::class)
  public fun <Input : Any> setSetting(
    stream: Iterable<Input>,
    user_id: Input.() -> Int,
    setting_key: Input.() -> String,
    setting_value: Input.() -> String?,
  ): IntArray = setSetting(stream, user_id, setting_key, setting_value, 100)

  /**
   * Norm: Executes a SQL statement.
   */
  @Throws(SQLException::class)
  public fun setSetting(
    user_id: Int,
    setting_key: String,
    setting_value: String?,
  )

  /**
   * Test: Simple digest function with 2 parameters returning bytea
   * Function: digest(data text, algorithm text) → bytea
   * Expected: Parameters (String, String), Return ByteArray
   */
  @Throws(SQLException::class)
  public fun <T : Any> computeDigest(
    digest: String,
    digest2: String,
    mapper: (hash: ByteArray) -> T,
  ): T

  /**
   * Test: Simple digest function with 2 parameters returning bytea
   * Function: digest(data text, algorithm text) → bytea
   * Expected: Parameters (String, String), Return ByteArray
   */
  @Throws(SQLException::class)
  public fun computeDigest(digest: String, digest2: String): ByteArray = computeDigest(digest, digest2, ::inputValue)

  /**
   * Test: HMAC function with 3 parameters (text, bytea, text) returning bytea
   * Function: hmac(data text, key bytea, algorithm text) → bytea
   * Expected: Parameters (String, ByteArray, String), Return ByteArray
   */
  @Throws(SQLException::class)
  public fun <T : Any> computeHmac(
    hmac: String,
    hmac2: String,
    hmac3: String,
    mapper: (signature: ByteArray) -> T,
  ): T

  /**
   * Test: HMAC function with 3 parameters (text, bytea, text) returning bytea
   * Function: hmac(data text, key bytea, algorithm text) → bytea
   * Expected: Parameters (String, ByteArray, String), Return ByteArray
   */
  @Throws(SQLException::class)
  public fun computeHmac(
    hmac: String,
    hmac2: String,
    hmac3: String,
  ): ByteArray = computeHmac(hmac, hmac2, hmac3, ::inputValue)

  /**
   * Test: Nested function calls - encode(digest(...))
   * Functions: digest(text, text) → bytea, encode(bytea, text) → text
   * Expected: Parameters (String, String, String), Return String
   */
  @Throws(SQLException::class)
  public fun <T : Any> computeEncodedHash(
    digest: String,
    digest2: String,
    encode: String,
    mapper: (encoded_hash: String) -> T,
  ): T

  /**
   * Test: Nested function calls - encode(digest(...))
   * Functions: digest(text, text) → bytea, encode(bytea, text) → text
   * Expected: Parameters (String, String, String), Return String
   */
  @Throws(SQLException::class)
  public fun computeEncodedHash(
    digest: String,
    digest2: String,
    encode: String,
  ): String = computeEncodedHash(digest, digest2, encode, ::inputValue)

  /**
   * Test: decode function - reverse of encode
   * Function: decode(data text, format text) → bytea
   * Expected: Parameters (String, String), Return ByteArray
   */
  @Throws(SQLException::class)
  public fun <T : Any> decodeData(
    decode: String,
    decode2: String,
    mapper: (decoded: ByteArray) -> T,
  ): T

  /**
   * Test: decode function - reverse of encode
   * Function: decode(data text, format text) → bytea
   * Expected: Parameters (String, String), Return ByteArray
   */
  @Throws(SQLException::class)
  public fun decodeData(decode: String, decode2: String): ByteArray = decodeData(decode, decode2, ::inputValue)

  /**
   * Test: Set-returning function normal_rand with 3 numeric parameters
   * Function: normal_rand(num_rows int, mean float8, stddev float8) → setof float8
   * Expected: Parameters (Int, Double, Double), Return Many<Double>
   */
  public fun <T> generateRandomNumbers(
    normal_rand: Int,
    normal_rand2: Double,
    normal_rand3: Double,
    mapper: (normal_rand: Double?) -> T,
  ): Many<T>

  /**
   * Test: Set-returning function normal_rand with 3 numeric parameters
   * Function: normal_rand(num_rows int, mean float8, stddev float8) → setof float8
   * Expected: Parameters (Int, Double, Double), Return Many<Double>
   */
  public fun generateRandomNumbers(
    normal_rand: Int,
    normal_rand2: Double,
    normal_rand3: Double,
  ): Many<Double?> = generateRandomNumbers(normal_rand, normal_rand2, normal_rand3, ::inputValue)

  /**
   * Test: crosstab with single parameter and explicit column definitions
   * Function: crosstab(sql text) → setof record
   * Expected: Parameters (String), Return Many with structured result
   */
  public fun <T : Any> getUserSettingsPivot(crosstab: String, mapper: (
    user_id: Int?,
    setting1: String?,
    setting2: String?,
  ) -> T): Many<T>

  /**
   * Test: crosstab with single parameter and explicit column definitions
   * Function: crosstab(sql text) → setof record
   * Expected: Parameters (String), Return Many with structured result
   */
  public fun getUserSettingsPivot(crosstab: String): Many<GetUserSettingsPivot> = getUserSettingsPivot(crosstab, ::GetUserSettingsPivot)

  /**
   * Test: crosstab with 2 parameters - source and category SQLs
   * Function: crosstab(source_sql text, category_sql text) → setof record
   * Expected: Parameters (String, String), Return Many with structured result
   */
  public fun <T : Any> getUserSettingsByCategory(
    crosstab: String,
    crosstab2: String,
    mapper: (
      row_name: String?,
      category1: Int?,
      category2: Int?,
      category3: Int?,
    ) -> T,
  ): Many<T>

  /**
   * Test: crosstab with 2 parameters - source and category SQLs
   * Function: crosstab(source_sql text, category_sql text) → setof record
   * Expected: Parameters (String, String), Return Many with structured result
   */
  public fun getUserSettingsByCategory(crosstab: String, crosstab2: String): Many<GetUserSettingsByCategory> = getUserSettingsByCategory(crosstab, crosstab2, ::GetUserSettingsByCategory)
}
