package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.key
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlParameterInferrerTest {

  /** Fake overloads matching pgcrypto and common functions. */
  private val functionOverloads = mapOf(
    "crypt" to listOf(FunctionOverload(listOf("password", "salt"), isStrict = true)),
    "gen_salt" to listOf(FunctionOverload(listOf("type"), isStrict = true)),
    "digest" to listOf(FunctionOverload(emptyList(), isStrict = true)),
    "encode" to listOf(FunctionOverload(emptyList(), isStrict = true)),
    "hmac" to listOf(FunctionOverload(emptyList(), isStrict = true)),
    "upper" to listOf(FunctionOverload(listOf("str"), isStrict = true)),
  )

  private val inferrer = SqlParameterInferrer(functionOverloads)

  @Nested
  inner class InsertInference {
    @Test
    fun `infers column names from INSERT`() {
      val result = inferrer.inferParameterInfo("INSERT INTO users(name, email) VALUES (?, ?)")
      assertThat(
        result,
      ).key(1).isEqualTo(InferredParameter("name", "users", inheritsNullability = true, columnName = "name"))
      assertThat(
        result,
      ).key(2).isEqualTo(InferredParameter("email", "users", inheritsNullability = true, columnName = "email"))
    }

    @Test
    fun `INSERT parameters inherit nullability`() {
      val result = inferrer.inferParameterInfo("INSERT INTO users(name) VALUES (?)")
      assertThat(result.getValue(1).inheritsNullability).isTrue()
    }

    @Test
    fun `function arg names override INSERT column names but preserve columnName for nullability`() {
      val result = inferrer.inferParameterInfo(
        "INSERT INTO user_credentials(username, password_hash) VALUES (?, crypt(?, gen_salt('bf')))",
      )
      assertThat(result.getValue(1).name).isEqualTo("username")
      assertThat(result.getValue(1).columnName).isEqualTo("username")
      // ? inside crypt() — gets the pg_proc formal name "password", but columnName stays as "password_hash"
      assertThat(result.getValue(2).name).isEqualTo("password")
      assertThat(result.getValue(2).columnName).isEqualTo("password_hash")
    }

    @Test
    fun `INSERT with nested function calls maps all params to correct columns`() {
      val result = inferrer.inferParameterInfo(
        "INSERT INTO user_credentials(username, password_hash, nullable_password_hash) " +
          "VALUES (?, crypt(?, gen_salt('bf')), crypt(?, gen_salt('bf')))",
      )
      // All three params should be found
      assertThat(result.getValue(1).name).isEqualTo("username")
      assertThat(result.getValue(1).columnName).isEqualTo("username")
      // First crypt() maps to password_hash column
      assertThat(result.getValue(2).name).isEqualTo("password")
      assertThat(result.getValue(2).columnName).isEqualTo("password_hash")
      // Second crypt() maps to nullable_password_hash column
      assertThat(result.getValue(3).name).isEqualTo("password")
      assertThat(result.getValue(3).columnName).isEqualTo("nullable_password_hash")
    }
  }

  @Nested
  inner class UpdateInference {
    @Test
    fun `SET parameters inherit nullability`() {
      val result = inferrer.inferParameterInfo("UPDATE users SET name = ? WHERE id = ?")
      assertThat(result.getValue(1).inheritsNullability).isTrue()
      assertThat(result.getValue(1).tableName).isEqualTo("users")
    }

    @Test
    fun `WHERE parameters do not inherit nullability`() {
      val result = inferrer.inferParameterInfo("UPDATE users SET name = ? WHERE id = ?")
      assertThat(result.getValue(2).inheritsNullability).isFalse()
    }

    @Test
    fun `SET parameters inherit nullability in multi-line UPDATE`() {
      val sql = "UPDATE users\nSET\n  name = ?,\n  bio = ?\nWHERE id = ?"
      val result = inferrer.inferParameterInfo(sql)
      assertThat(result.getValue(1).inheritsNullability).isTrue()
      assertThat(result.getValue(2).inheritsNullability).isTrue()
      assertThat(result.getValue(3).inheritsNullability).isFalse()
    }

    @Test
    fun `SET parameters inherit nullability with no WHERE clause`() {
      val result = inferrer.inferParameterInfo("UPDATE users SET bio = ?")
      assertThat(result.getValue(1).inheritsNullability).isTrue()
    }

    @Test
    fun `infers column names from SET and WHERE`() {
      val result = inferrer.inferParameterInfo("UPDATE users SET email = ?, name = ? WHERE id = ?")
      assertThat(result.getValue(1).name).isEqualTo("email")
      assertThat(result.getValue(2).name).isEqualTo("name")
      assertThat(result.getValue(3).name).isEqualTo("id")
    }
  }

  @Nested
  inner class DeleteInference {
    @Test
    fun `infers table and column from DELETE WHERE`() {
      val result = inferrer.inferParameterInfo("DELETE FROM users WHERE id = ?")
      assertThat(result.getValue(1).name).isEqualTo("id")
      assertThat(result.getValue(1).tableName).isEqualTo("users")
      assertThat(result.getValue(1).inheritsNullability).isFalse()
    }
  }

  @Nested
  inner class FunctionArgInference {
    @Test
    fun `infers formal argument names from pg_proc`() {
      val result = inferrer.inferParameterInfo(
        "INSERT INTO users(password_hash) VALUES (crypt(?, gen_salt('bf')))",
      )
      // crypt has formal names ["password", "salt"], so first ? → "password"
      assertThat(result.getValue(1).name).isEqualTo("password")
    }

    @Test
    fun `falls back to funcName_paramN when pg_proc has no arg names`() {
      // digest has no named args (emptyList())
      val result = inferrer.inferParameterInfo("SELECT digest(?, ?) AS hash")
      assertThat(result.getValue(1).name).isEqualTo("digest_param1")
      assertThat(result.getValue(2).name).isEqualTo("digest_param2")
    }

    @Test
    fun `repeated function calls get numeric suffix`() {
      val result = inferrer.inferParameterInfo(
        "SELECT digest(?, ?) AS h1, digest(?, ?) AS h2",
      )
      assertThat(result.getValue(1).name).isEqualTo("digest_param1")
      assertThat(result.getValue(2).name).isEqualTo("digest_param2")
      assertThat(result.getValue(3).name).isEqualTo("digest2_param1")
      assertThat(result.getValue(4).name).isEqualTo("digest2_param2")
    }

    @Test
    fun `nested function calls resolve innermost first`() {
      val result = inferrer.inferParameterInfo(
        "SELECT encode(digest(?, ?), ?) AS encoded_hash",
      )
      // First two ? are in digest() (innermost match wins)
      assertThat(result.getValue(1).name).isEqualTo("digest_param1")
      assertThat(result.getValue(2).name).isEqualTo("digest_param2")
      // Third ? is in encode()
      assertThat(result.getValue(3).name).isEqualTo("encode_param2")
    }

    @Test
    fun `unknown function does not contribute names`() {
      val result = inferrer.inferParameterInfo("SELECT unknown_func(?, ?) AS result")
      assertThat(result).isEmpty()
    }
  }

  @Nested
  inner class SelectInference {
    @Test
    fun `SELECT with WHERE infers column name`() {
      val result = inferrer.inferParameterInfo("SELECT * FROM users WHERE id = ?")
      assertThat(result.getValue(1).name).isEqualTo("id")
      assertThat(result.getValue(1).inheritsNullability).isFalse()
    }

    @Test
    fun `SELECT without table has null tableName`() {
      val result = inferrer.inferParameterInfo("SELECT upper(?) AS result")
      assertThat(result.getValue(1).tableName).isNull()
    }
  }

  @Nested
  inner class ResolveNullability {

    private val catalog = Catalog(
      defaultSchema = "public",
      schemas = listOf(
        Schema(
          name = "public",
          tables = listOf(
            Table(
              rel = Identifier(name = "users"),
              columns = listOf(
                Column(name = "id", notNull = true, type = Identifier(name = "int4")),
                Column(name = "name", notNull = true, type = Identifier(name = "text")),
                Column(name = "bio", notNull = false, type = Identifier(name = "text")),
              ),
            ),
          ),
        ),
      ),
    )

    @Test
    fun `WHERE parameter is always non-nullable`() {
      val inferredParams = mapOf(1 to InferredParameter("id", "users", inheritsNullability = false))
      val result = inferrer.resolveParameterNotNull(inferredParams, catalog)
      assertThat(result.getValue(1)).isTrue() // notNull = true
    }

    @Test
    fun `INSERT parameter for NOT NULL column is non-nullable`() {
      val inferredParams = mapOf(1 to InferredParameter("name", "users", inheritsNullability = true))
      val result = inferrer.resolveParameterNotNull(inferredParams, catalog)
      assertThat(result.getValue(1)).isTrue()
    }

    @Test
    fun `INSERT parameter for nullable column is nullable`() {
      val inferredParams = mapOf(1 to InferredParameter("bio", "users", inheritsNullability = true))
      val result = inferrer.resolveParameterNotNull(inferredParams, catalog)
      assertThat(result.getValue(1)).isFalse() // notNull = false → parameter is nullable
    }

    @Test
    fun `column not found in catalog defaults to non-nullable`() {
      val inferredParams = mapOf(1 to InferredParameter("unknown_col", "users", inheritsNullability = true))
      val result = inferrer.resolveParameterNotNull(inferredParams, catalog)
      assertThat(result.getValue(1)).isTrue()
    }

    @Test
    fun `table not found in catalog defaults to non-nullable`() {
      val inferredParams = mapOf(1 to InferredParameter("col", "nonexistent", inheritsNullability = true))
      val result = inferrer.resolveParameterNotNull(inferredParams, catalog)
      assertThat(result.getValue(1)).isTrue()
    }

    @Test
    fun `columnName is used for catalog lookup instead of display name`() {
      // Simulates INSERT into nullable column where function arg name replaces column name
      val inferredParams = mapOf(
        1 to InferredParameter("password", "users", inheritsNullability = true, columnName = "bio"),
      )
      val result = inferrer.resolveParameterNotNull(inferredParams, catalog)
      // bio is nullable, so the parameter should be nullable
      assertThat(result.getValue(1)).isFalse()
    }
  }
}
