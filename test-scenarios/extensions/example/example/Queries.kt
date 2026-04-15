package example

import java.sql.SQLException
import java.sql.Statement.EXECUTE_FAILED
import java.sql.Statement.SUCCESS_NO_INFO
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
import norm.inputValue

public interface Queries {
  /**
   * Query using pgcrypto for password hashing
   *
   * ```sql
   * INSERT INTO user_credentials (username, password_hash, nullable_password_hash)
   * VALUES (?, crypt(?, gen_salt('bf')), crypt(?, gen_salt('bf')))
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
    username: Input.() -> String,
    crypt_param1: Input.() -> String,
    crypt2_param1: Input.() -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * Query using pgcrypto for password hashing
   *
   * ```sql
   * INSERT INTO user_credentials (username, password_hash, nullable_password_hash)
   * VALUES (?, crypt(?, gen_salt('bf')), crypt(?, gen_salt('bf')))
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
    username: Input.() -> String,
    crypt_param1: Input.() -> String,
    crypt2_param1: Input.() -> String?,
  ): IntArray = createUser(stream, username, crypt_param1, crypt2_param1, 100)

  /**
   * Query using pgcrypto for password hashing
   *
   * ```sql
   * INSERT INTO user_credentials (username, password_hash, nullable_password_hash)
   * VALUES (?, crypt(?, gen_salt('bf')), crypt(?, gen_salt('bf')))
   * ```
   */
  @Throws(SQLException::class)
  public fun createUser(
    username: String,
    crypt_param1: String,
    crypt2_param1: String?,
  )

  /**
   * Query using pgcrypto for password verification
   *
   * ```sql
   * SELECT EXISTS(
   *   SELECT 1 FROM user_credentials
   *   WHERE username = ?
   *   AND password_hash = crypt(?, password_hash)
   * ) AS valid
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> verifyPassword(
    username: String,
    crypt_param1: String,
    mapper: (valid: Boolean) -> T,
  ): T

  /**
   * Query using pgcrypto for password verification
   *
   * ```sql
   * SELECT EXISTS(
   *   SELECT 1 FROM user_credentials
   *   WHERE username = ?
   *   AND password_hash = crypt(?, password_hash)
   * ) AS valid
   * ```
   */
  @Throws(SQLException::class)
  public fun verifyPassword(username: String, crypt_param1: String): Boolean = verifyPassword(username, crypt_param1, ::inputValue)

  /**
   * Query using settings table (for potential tablefunc pivot)
   *
   * ```sql
   * SELECT setting_key, setting_value
   * FROM user_settings
   * WHERE user_id = ?
   * ```
   */
  public fun <T : Any> getUserSettings(user_id: Int, mapper: (setting_key: String, setting_value: String?) -> T): Many<T>

  /**
   * Query using settings table (for potential tablefunc pivot)
   *
   * ```sql
   * SELECT setting_key, setting_value
   * FROM user_settings
   * WHERE user_id = ?
   * ```
   */
  public fun getUserSettings(user_id: Int): Many<GetUserSettings> = getUserSettings(user_id, ::GetUserSettings)

  /**
   * ```sql
   * INSERT INTO user_settings (user_id, setting_key, setting_value)
   * VALUES (?, ?, ?)
   * ON CONFLICT (user_id, setting_key)
   * DO UPDATE SET setting_value = EXCLUDED.setting_value
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
  public fun <Input : Any> setSetting(
    stream: Iterable<Input>,
    user_id: Input.() -> Int,
    setting_key: Input.() -> String,
    setting_value: Input.() -> String?,
    batchSize: Int,
  ): IntArray

  /**
   * ```sql
   * INSERT INTO user_settings (user_id, setting_key, setting_value)
   * VALUES (?, ?, ?)
   * ON CONFLICT (user_id, setting_key)
   * DO UPDATE SET setting_value = EXCLUDED.setting_value
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
  public fun <Input : Any> setSetting(
    stream: Iterable<Input>,
    user_id: Input.() -> Int,
    setting_key: Input.() -> String,
    setting_value: Input.() -> String?,
  ): IntArray = setSetting(stream, user_id, setting_key, setting_value, 100)

  /**
   * ```sql
   * INSERT INTO user_settings (user_id, setting_key, setting_value)
   * VALUES (?, ?, ?)
   * ON CONFLICT (user_id, setting_key)
   * DO UPDATE SET setting_value = EXCLUDED.setting_value
   * ```
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
   *
   * ```sql
   * SELECT digest(?, ?) AS hash
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> computeDigest(
    digest_param1: String,
    digest_param2: String,
    mapper: (hash: ByteArray) -> T,
  ): T

  /**
   * Test: Simple digest function with 2 parameters returning bytea
   * Function: digest(data text, algorithm text) → bytea
   * Expected: Parameters (String, String), Return ByteArray
   *
   * ```sql
   * SELECT digest(?, ?) AS hash
   * ```
   */
  @Throws(SQLException::class)
  public fun computeDigest(digest_param1: String, digest_param2: String): ByteArray = computeDigest(digest_param1, digest_param2, ::inputValue)

  /**
   * Test: HMAC function with 3 parameters (text, bytea, text) returning bytea
   * Function: hmac(data text, key bytea, algorithm text) → bytea
   * Expected: Parameters (String, ByteArray, String), Return ByteArray
   *
   * ```sql
   * SELECT hmac(?, ?, ?) AS signature
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> computeHmac(
    hmac_param1: String,
    hmac_param2: String,
    hmac_param3: String,
    mapper: (signature: ByteArray) -> T,
  ): T

  /**
   * Test: HMAC function with 3 parameters (text, bytea, text) returning bytea
   * Function: hmac(data text, key bytea, algorithm text) → bytea
   * Expected: Parameters (String, ByteArray, String), Return ByteArray
   *
   * ```sql
   * SELECT hmac(?, ?, ?) AS signature
   * ```
   */
  @Throws(SQLException::class)
  public fun computeHmac(
    hmac_param1: String,
    hmac_param2: String,
    hmac_param3: String,
  ): ByteArray = computeHmac(hmac_param1, hmac_param2, hmac_param3, ::inputValue)

  /**
   * Test: Nested function calls - encode(digest(...))
   * Functions: digest(text, text) → bytea, encode(bytea, text) → text
   * Expected: Parameters (String, String, String), Return String
   *
   * ```sql
   * SELECT encode(digest(?, ?), ?) AS encoded_hash
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> computeEncodedHash(
    digest_param1: String,
    digest_param2: String,
    encode_param2: String,
    mapper: (encoded_hash: String) -> T,
  ): T

  /**
   * Test: Nested function calls - encode(digest(...))
   * Functions: digest(text, text) → bytea, encode(bytea, text) → text
   * Expected: Parameters (String, String, String), Return String
   *
   * ```sql
   * SELECT encode(digest(?, ?), ?) AS encoded_hash
   * ```
   */
  @Throws(SQLException::class)
  public fun computeEncodedHash(
    digest_param1: String,
    digest_param2: String,
    encode_param2: String,
  ): String = computeEncodedHash(digest_param1, digest_param2, encode_param2, ::inputValue)

  /**
   * Test: decode function - reverse of encode
   * Function: decode(data text, format text) → bytea
   * Expected: Parameters (String, String), Return ByteArray
   *
   * ```sql
   * SELECT decode(?, ?) AS decoded
   * ```
   */
  @Throws(SQLException::class)
  public fun <T : Any> decodeData(
    decode_param1: String,
    decode_param2: String,
    mapper: (decoded: ByteArray) -> T,
  ): T

  /**
   * Test: decode function - reverse of encode
   * Function: decode(data text, format text) → bytea
   * Expected: Parameters (String, String), Return ByteArray
   *
   * ```sql
   * SELECT decode(?, ?) AS decoded
   * ```
   */
  @Throws(SQLException::class)
  public fun decodeData(decode_param1: String, decode_param2: String): ByteArray = decodeData(decode_param1, decode_param2, ::inputValue)

  /**
   * Test: Set-returning function normal_rand with 3 numeric parameters
   * Function: normal_rand(num_rows int, mean float8, stddev float8) → setof float8
   * Expected: Parameters (Int, Double, Double), Return Many<Double>
   *
   * ```sql
   * SELECT * FROM normal_rand(?, ?, ?)
   * ```
   */
  public fun <T> generateRandomNumbers(
    normal_rand_param1: Int,
    normal_rand_param2: Double,
    normal_rand_param3: Double,
    mapper: (normal_rand: Double?) -> T,
  ): Many<T>

  /**
   * Test: Set-returning function normal_rand with 3 numeric parameters
   * Function: normal_rand(num_rows int, mean float8, stddev float8) → setof float8
   * Expected: Parameters (Int, Double, Double), Return Many<Double>
   *
   * ```sql
   * SELECT * FROM normal_rand(?, ?, ?)
   * ```
   */
  public fun generateRandomNumbers(
    normal_rand_param1: Int,
    normal_rand_param2: Double,
    normal_rand_param3: Double,
  ): Many<Double?> = generateRandomNumbers(normal_rand_param1, normal_rand_param2, normal_rand_param3, ::inputValue)

  /**
   * Test: crosstab with single parameter and explicit column definitions
   * Function: crosstab(sql text) → setof record
   * Expected: Parameters (String), Return Many with structured result
   *
   * ```sql
   * SELECT user_id, setting1, setting2
   * FROM crosstab(?) AS ct(user_id int, setting1 text, setting2 text)
   * ```
   */
  public fun <T : Any> getUserSettingsPivot(crosstab_param1: String, mapper: (
    user_id: Int?,
    setting1: String?,
    setting2: String?,
  ) -> T): Many<T>

  /**
   * Test: crosstab with single parameter and explicit column definitions
   * Function: crosstab(sql text) → setof record
   * Expected: Parameters (String), Return Many with structured result
   *
   * ```sql
   * SELECT user_id, setting1, setting2
   * FROM crosstab(?) AS ct(user_id int, setting1 text, setting2 text)
   * ```
   */
  public fun getUserSettingsPivot(crosstab_param1: String): Many<GetUserSettingsPivot> = getUserSettingsPivot(crosstab_param1, ::GetUserSettingsPivot)

  /**
   * Test: crosstab with 2 parameters - source and category SQLs
   * Function: crosstab(source_sql text, category_sql text) → setof record
   * Expected: Parameters (String, String), Return Many with structured result
   *
   * ```sql
   * SELECT row_name, category1, category2, category3
   * FROM crosstab(?, ?) AS ct(row_name text, category1 int, category2 int, category3 int)
   * ```
   */
  public fun <T : Any> getUserSettingsByCategory(
    crosstab_param1: String,
    crosstab_param2: String,
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
   *
   * ```sql
   * SELECT row_name, category1, category2, category3
   * FROM crosstab(?, ?) AS ct(row_name text, category1 int, category2 int, category3 int)
   * ```
   */
  public fun getUserSettingsByCategory(crosstab_param1: String, crosstab_param2: String): Many<GetUserSettingsByCategory> = getUserSettingsByCategory(crosstab_param1, crosstab_param2, ::GetUserSettingsByCategory)
}
