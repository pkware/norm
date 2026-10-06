package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlParameterInferrerTest {

  /** Fake overloads. The argument names of `crypt` and `gen_salt` are illustrative, because pgcrypto declares none. */
  private val functionOverloads = mapOf(
    "crypt" to listOf(FunctionOverload(listOf("password", "salt"), isStrict = true)),
    "gen_salt" to listOf(FunctionOverload(listOf("type"), isStrict = true)),
    "digest" to listOf(FunctionOverload(emptyList(), isStrict = true)),
    "encode" to listOf(FunctionOverload(emptyList(), isStrict = true)),
    "hmac" to listOf(FunctionOverload(emptyList(), isStrict = true)),
    "upper" to listOf(FunctionOverload(listOf("str"), isStrict = true)),
    // PostgreSQL's pg_proc for the 3-arg overload has proargnames = {string,pattern,replacement}.
    "regexp_replace" to listOf(FunctionOverload(listOf("string", "pattern", "replacement"), isStrict = true)),
    "concat" to listOf(FunctionOverload(emptyList(), isStrict = true)),
  )

  private val inferrer = SqlParameterInferrer(functionOverloads)

  private fun infer(sql: String, classified: Map<Int, InferredParameter> = emptyMap()) =
    inferrer.inferParameterInfo(sql, classified)

  @Nested
  inner class FunctionArgInference {
    @Test
    fun `infers formal argument names from pg_proc`() {
      val result = infer(
        "INSERT INTO users(password_hash) VALUES (crypt(?, gen_salt('bf')))",
      )
      // crypt has formal names ["password", "salt"], so first ? → "password"
      assertThat(result.getValue(1).name).isEqualTo("password")
    }

    @Test
    fun `falls back to funcName_paramN when pg_proc has no arg names`() {
      // digest has no named args (emptyList())
      val result = infer("SELECT digest(?, ?) AS hash")
      assertThat(result.getValue(1).name).isEqualTo("digest_param1")
      assertThat(result.getValue(2).name).isEqualTo("digest_param2")
    }

    @Test
    fun `repeated function calls get numeric suffix`() {
      val result = infer(
        "SELECT digest(?, ?) AS h1, digest(?, ?) AS h2",
      )
      assertThat(result.getValue(1).name).isEqualTo("digest_param1")
      assertThat(result.getValue(2).name).isEqualTo("digest_param2")
      assertThat(result.getValue(3).name).isEqualTo("digest2_param1")
      assertThat(result.getValue(4).name).isEqualTo("digest2_param2")
    }

    @Test
    fun `nested function calls resolve innermost first`() {
      val result = infer(
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
      val result = infer("SELECT unknown_func(?, ?) AS result")
      assertThat(result).isEmpty()
    }

    @Test
    fun `resolves the formal argument name even when a later argument's literal contains an unbalanced parenthesis`() {
      // findMatchingCloseParenthesis skips the "(" inside the string literal '\(' as part of the
      // string token, so extractFunctionCalls's own paren search finds a balanced close for this
      // call and the parameter is named from pg_proc.
      // "string" is the correct name per Norm's own rule (see "infers formal argument names from
      // pg_proc" above): a pg_proc formal argument name always wins over a generic fallback, and
      // regexp_replace(string, pattern, replacement) is regexp_replace's real 3-argument
      // signature, so the first ? is "string" here for the same reason the first ? in
      // crypt(?, gen_salt('bf')) is "password" above.
      val result = infer(
        """SELECT id FROM p WHERE name = regexp_replace(?, '\(', '')""",
      )
      assertThat(result.getValue(1).name).isEqualTo("string")
    }
  }

  @Nested
  inner class ClassifiedParametersFromTheProbe {
    private val usersName = ColumnReference("public", "users", "name")

    @Test
    fun `a parameter without a function call is returned as the probe classified it`() {
      val classified = mapOf(1 to InferredParameter("bio", ParameterNullability.NonNull))

      assertThat(infer("SELECT * FROM users WHERE bio = ?", classified)).isEqualTo(classified)
    }

    @Test
    fun `a function argument name replaces the column name but keeps the nullability and identity`() {
      val classified = mapOf(1 to InferredParameter("name", ParameterNullability.Inherit(usersName), usersName))

      assertThat(infer("UPDATE users SET name = upper(?)", classified).getValue(1)).isEqualTo(
        InferredParameter("str", ParameterNullability.Inherit(usersName), usersName),
      )
    }

    @Test
    fun `a function argument name beats a column name from a comparison`() {
      val classified = mapOf(1 to InferredParameter("name", ParameterNullability.NonNull))

      assertThat(infer("SELECT * FROM users WHERE name = upper(?)", classified).getValue(1))
        .isEqualTo(InferredParameter("str", ParameterNullability.NonNull))
    }

    @Test
    fun `a function argument name for a parameter the probe did not classify has no opinion on null`() {
      assertThat(infer("SELECT digest(?, ?)")).isEqualTo(
        mapOf(1 to InferredParameter("digest_param1", null), 2 to InferredParameter("digest_param2", null)),
      )
    }

    @Test
    fun `a parameter the probe did not classify and no function names is absent`() {
      assertThat(infer("UPDATE users SET name = ? WHERE id = ?")).isEmpty()
    }
  }

  @Nested
  inner class ResolveNullability {

    private val catalog = Catalog(
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

    private fun inherit(column: String, table: String = "users") =
      InferredParameter(column, ParameterNullability.Inherit(ColumnReference("public", table, column)))

    @Test
    fun `WHERE parameter is always non-nullable`() {
      val inferredParams = mapOf(1 to InferredParameter("id", ParameterNullability.NonNull))
      val result = inferrer.resolveParameterNotNull(inferredParams, catalog)
      assertThat(result.getValue(1)).isTrue() // notNull = true
    }

    @Test
    fun `a parameter that accepts null is nullable`() {
      val result = inferrer.resolveParameterNotNull(
        mapOf(1 to InferredParameter("id", ParameterNullability.Nullable)),
        catalog,
      )
      assertThat(result.getValue(1)).isFalse()
    }

    @Test
    fun `a parameter with no opinion on null is non-nullable`() {
      val result = inferrer.resolveParameterNotNull(mapOf(1 to InferredParameter("id", null)), catalog)
      assertThat(result.getValue(1)).isTrue()
    }

    @Test
    fun `INSERT parameter for NOT NULL column is non-nullable`() {
      val result = inferrer.resolveParameterNotNull(mapOf(1 to inherit("name")), catalog)
      assertThat(result.getValue(1)).isTrue()
    }

    @Test
    fun `INSERT parameter for nullable column is nullable`() {
      val result = inferrer.resolveParameterNotNull(mapOf(1 to inherit("bio")), catalog)
      assertThat(result.getValue(1)).isFalse() // notNull = false → parameter is nullable
    }

    @Test
    fun `column not found in catalog defaults to non-nullable`() {
      val result = inferrer.resolveParameterNotNull(mapOf(1 to inherit("unknown_col")), catalog)
      assertThat(result.getValue(1)).isTrue()
    }

    @Test
    fun `table not found in catalog defaults to non-nullable`() {
      val result = inferrer.resolveParameterNotNull(mapOf(1 to inherit("col", "nonexistent")), catalog)
      assertThat(result.getValue(1)).isTrue()
    }

    @Test
    fun `the inherited column is used for the catalog lookup instead of the display name`() {
      val parameter =
        InferredParameter("password", ParameterNullability.Inherit(ColumnReference("public", "users", "bio")))
      val result = inferrer.resolveParameterNotNull(mapOf(1 to parameter), catalog)
      // bio is nullable, so the parameter should be nullable
      assertThat(result.getValue(1)).isFalse()
    }
  }

  @Nested
  inner class FunctionCallStartTest {

    @Test
    fun `captures the whole dollar-containing function name, not just the run after the dollar sign`() {
      // FUNCTION_CALL_START must match "my$fn" as one run, giving
      // SqlParameterInferrer.extractFunctionCalls the whole function name. In
      // PostgreSQL 18.4, "my$fn" is a legal unquoted function name (CREATE FUNCTION "my$fn"(...)
      // and the unquoted call my$fn(...) resolve to the same function).
      val match = FUNCTION_CALL_START.find("SELECT my\$fn(?)")
      assertThat(match!!.groupValues[1]).isEqualTo("my\$fn")
    }

    @Test
    fun `captures a function name continuing with a non-ASCII character`() {
      // "\w+" is ASCII-only, so it also excludes any ">= 0x80" character. In PostgreSQL 18.4, an
      // unquoted function named "fn€" is legal and callable unquoted.
      val match = FUNCTION_CALL_START.find("SELECT fn€(?)")
      assertThat(match!!.groupValues[1]).isEqualTo("fn€")
    }

    @Test
    fun `does not capture a digit as the start of a function name`() {
      // In PostgreSQL 18.4, "2fn(...)" is rejected outright ("trailing junk after numeric
      // literal") -- a digit may never start an identifier. The regex's own leading-character
      // restriction means a match beginning with "2" is impossible; the only match found here
      // starts at "f".
      val match = FUNCTION_CALL_START.find("SELECT 2fn(?)")
      assertThat(match!!.groupValues[1]).isEqualTo("fn")
    }
  }

  @Nested
  inner class LiteralQuestionMarksAreNotPlaceholders {
    @Test
    fun `a question mark inside a function argument's string literal is not counted as its placeholder`() {
      val result = infer("SELECT concat('?', ?)")
      assertThat(result.size).isEqualTo(1)
      assertThat(result.getValue(1).name).isEqualTo("concat_param2")
    }

    @Test
    fun `a question mark inside a function argument's string literal does not spuriously name a second parameter`() {
      val result = infer("SELECT crypt(?, '?')")
      assertThat(result.size).isEqualTo(1)
      assertThat(result.getValue(1).name).isEqualTo("password")
    }

    @Test
    fun `a function call with no real placeholder does not bump the repeated-call suffix counter`() {
      // digest('?') has no real placeholder in its argument list, so it must not be counted as a
      // call at all -- otherwise the second, real digest(?, ?) call is wrongly numbered as the
      // second call and its parameters get the "digest2_" suffix.
      val result = infer("SELECT digest('?'), digest(?, ?)")
      assertThat(result.size).isEqualTo(2)
      assertThat(result.getValue(1).name).isEqualTo("digest_param1")
      assertThat(result.getValue(2).name).isEqualTo("digest_param2")
    }
  }
}
