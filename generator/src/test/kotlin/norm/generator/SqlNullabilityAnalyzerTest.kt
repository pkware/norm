package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SqlNullabilityAnalyzerTest {

  /** Fake function overloads matching common PostgreSQL functions. */
  private val functionOverloads = mapOf(
    "upper" to listOf(FunctionOverload(listOf("str"), isStrict = true)),
    "lower" to listOf(FunctionOverload(listOf("str"), isStrict = true)),
    "substr" to listOf(FunctionOverload(listOf("str", "start"), isStrict = true)),
    "concat" to listOf(FunctionOverload(listOf("str1", "str2"), isStrict = false)),
    "digest" to listOf(FunctionOverload(listOf("data", "type"), isStrict = true)),
    "encode" to listOf(FunctionOverload(listOf("data", "format"), isStrict = true)),
    "hmac" to listOf(FunctionOverload(listOf("data", "key", "type"), isStrict = true)),
  )

  private val analyzer = SqlNullabilityAnalyzer(functionOverloads)

  @Nested
  inner class KnownNonNullConstructs {
    @Test
    fun `EXISTS is non-null`() {
      val result = analyzer.findNonNullAliases(
        $$"SELECT EXISTS(SELECT 1 FROM users WHERE id = $1) AS found",
      )
      assertThat(result).containsExactlyInAnyOrder("found")
    }

    @Test
    fun `COUNT star is non-null`() {
      val result = analyzer.findNonNullAliases("SELECT COUNT(*) AS total FROM users")
      assertThat(result).containsExactlyInAnyOrder("total")
    }

    @Test
    fun `COUNT with expression is non-null`() {
      val result = analyzer.findNonNullAliases("SELECT COUNT(name) AS total FROM users")
      assertThat(result).containsExactlyInAnyOrder("total")
    }

    @Test
    fun `COALESCE is non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT COALESCE(age, 0) AS val FROM users WHERE id = $1")
      assertThat(result).containsExactlyInAnyOrder("val")
    }
  }

  @Nested
  inner class StrictFunctionCalls {
    @Test
    fun `strict function with positional param is non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT upper($1) AS uppered")
      assertThat(result).containsExactlyInAnyOrder("uppered")
    }

    @Test
    fun `nested strict functions with positional params are non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT upper(lower($1)) AS result")
      assertThat(result).containsExactlyInAnyOrder("result")
    }

    @Test
    fun `strict function with string literal is non-null`() {
      val result = analyzer.findNonNullAliases("SELECT upper('hello') AS result")
      assertThat(result).containsExactlyInAnyOrder("result")
    }

    @Test
    fun `strict function with numeric literal is non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT substr($1, 5) AS result")
      assertThat(result).containsExactlyInAnyOrder("result")
    }

    @Test
    fun `nested strict functions like encode(digest()) are non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT encode(digest($1, $2), $3) AS encoded_hash")
      assertThat(result).containsExactlyInAnyOrder("encoded_hash")
    }
  }

  @Nested
  inner class NonStrictAndUnknownFunctions {
    @Test
    fun `non-strict function is not detected as non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT concat($1, $2) AS result")
      assertThat(result).isEmpty()
    }

    @Test
    fun `unknown function is not detected as non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT some_unknown_func($1) AS result")
      assertThat(result).isEmpty()
    }

    @Test
    fun `strict function with column reference is not detected as non-null`() {
      // Column references (not $N) can't be proven non-null by SQL text analysis alone
      val result = analyzer.findNonNullAliases($$"SELECT upper(text_col) AS uppered FROM users WHERE id = $1")
      assertThat(result).isEmpty()
    }

    @Test
    fun `strict function with mixed column and param args is not non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT substr(text_col, $1) AS result FROM users WHERE id = $2")
      assertThat(result).isEmpty()
    }
  }

  @Nested
  inner class MultipleAliases {
    @Test
    fun `multiple non-null aliases detected in one SELECT`() {
      val result = analyzer.findNonNullAliases(
        "SELECT COUNT(*) AS total, EXISTS(SELECT 1 FROM users) AS has_rows, name FROM users",
      )
      assertThat(result).containsExactlyInAnyOrder("total", "has_rows")
    }

    @Test
    fun `mix of non-null and nullable aliases`() {
      val result = analyzer.findNonNullAliases(
        $$"SELECT upper($1) AS uppered, concat($2, $3) AS joined, COALESCE(age, 0) AS safe_age FROM users",
      )
      assertThat(result).containsExactlyInAnyOrder("uppered", "safe_age")
    }
  }

  @Nested
  inner class EdgeCases {
    @Test
    fun `expression without alias is not detected`() {
      val result = analyzer.findNonNullAliases($$"SELECT upper($1)")
      assertThat(result).isEmpty()
    }

    @Test
    fun `SELECT star returns empty set`() {
      val result = analyzer.findNonNullAliases("SELECT * FROM users")
      assertThat(result).isEmpty()
    }

    @Test
    fun `SELECT without FROM`() {
      val result = analyzer.findNonNullAliases($$"SELECT COALESCE($1, 0) AS val")
      assertThat(result).containsExactlyInAnyOrder("val")
    }

    @Test
    fun `nested subquery in EXISTS does not confuse FROM detection`() {
      val result = analyzer.findNonNullAliases(
        $$"SELECT EXISTS(SELECT 1 FROM users WHERE active = true) AS has_active FROM settings WHERE id = $1",
      )
      assertThat(result).containsExactlyInAnyOrder("has_active")
    }

    @Test
    fun `no function overloads returns empty for all functions`() {
      val emptyAnalyzer = SqlNullabilityAnalyzer(emptyMap())
      val result = emptyAnalyzer.findNonNullAliases($$"SELECT upper($1) AS result")
      // upper is strict, but without overload data we can't know that
      assertThat(result).isEmpty()
    }

    @Test
    fun `arithmetic expression is not detected as non-null`() {
      val result = analyzer.findNonNullAliases($$"SELECT age + 1 AS incremented FROM users WHERE id = $1")
      assertThat(result).isEmpty()
    }

    @Test
    fun `non-SELECT SQL returns empty`() {
      val result = analyzer.findNonNullAliases($$"INSERT INTO users(name) VALUES ($1)")
      assertThat(result).isEmpty()
    }
  }
}
