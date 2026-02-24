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
        "SELECT EXISTS(SELECT 1 FROM users WHERE id = ?) AS found",
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
      val result = analyzer.findNonNullAliases("SELECT COALESCE(age, 0) AS val FROM users WHERE id = ?")
      assertThat(result).containsExactlyInAnyOrder("val")
    }
  }

  @Nested
  inner class StrictFunctionCalls {
    @Test
    fun `strict function with non-null param is non-null`() {
      val sql = "SELECT upper(?) AS uppered"
      val result = analyzer.findNonNullAliases(sql, mapOf(1 to true))
      assertThat(result).containsExactlyInAnyOrder("uppered")
    }

    @Test
    fun `strict function with nullable param is nullable`() {
      // Without parameter nullability info, ? is assumed nullable
      val result = analyzer.findNonNullAliases("SELECT upper(?) AS uppered")
      assertThat(result).isEmpty()
    }

    @Test
    fun `nested strict functions with non-null params are non-null`() {
      val sql = "SELECT upper(lower(?)) AS result"
      val result = analyzer.findNonNullAliases(sql, mapOf(1 to true))
      assertThat(result).containsExactlyInAnyOrder("result")
    }

    @Test
    fun `nested strict functions with nullable params are nullable`() {
      val result = analyzer.findNonNullAliases("SELECT upper(lower(?)) AS result")
      assertThat(result).isEmpty()
    }

    @Test
    fun `strict function with string literal is non-null`() {
      val result = analyzer.findNonNullAliases("SELECT upper('hello') AS result")
      assertThat(result).containsExactlyInAnyOrder("result")
    }

    @Test
    fun `strict function with non-null param and literal is non-null`() {
      val sql = "SELECT substr(?, 5) AS result"
      val result = analyzer.findNonNullAliases(sql, mapOf(1 to true))
      assertThat(result).containsExactlyInAnyOrder("result")
    }

    @Test
    fun `strict function with nullable param and literal is nullable`() {
      val result = analyzer.findNonNullAliases("SELECT substr(?, 5) AS result")
      assertThat(result).isEmpty()
    }

    @Test
    fun `nested strict functions with only literals are non-null`() {
      val result = analyzer.findNonNullAliases("SELECT upper(lower('hello')) AS result")
      assertThat(result).containsExactlyInAnyOrder("result")
    }

    @Test
    fun `nested strict functions with non-null params are non-null (encode digest)`() {
      val sql = "SELECT encode(digest(?, ?), ?) AS encoded_hash"
      val result = analyzer.findNonNullAliases(sql, mapOf(1 to true, 2 to true, 3 to true))
      assertThat(result).containsExactlyInAnyOrder("encoded_hash")
    }

    @Test
    fun `nested strict functions with nullable params are nullable (encode digest)`() {
      val result = analyzer.findNonNullAliases("SELECT encode(digest(?, ?), ?) AS encoded_hash")
      assertThat(result).isEmpty()
    }

    @Test
    fun `strict function non-null when one param is non-null but other is nullable`() {
      val sql = "SELECT substr(?, ?) AS result"
      // First param is non-null, second is nullable
      val result = analyzer.findNonNullAliases(sql, mapOf(1 to true))
      assertThat(result).isEmpty()
    }
  }

  @Nested
  inner class NonStrictAndUnknownFunctions {
    @Test
    fun `non-strict function is not detected as non-null`() {
      val result = analyzer.findNonNullAliases("SELECT concat(?, ?) AS result")
      assertThat(result).isEmpty()
    }

    @Test
    fun `non-strict function with non-null params is still nullable`() {
      val sql = "SELECT concat(?, ?) AS result"
      val result = analyzer.findNonNullAliases(sql, mapOf(1 to true, 2 to true))
      assertThat(result).isEmpty()
    }

    @Test
    fun `unknown function is not detected as non-null`() {
      val result = analyzer.findNonNullAliases("SELECT some_unknown_func(?) AS result")
      assertThat(result).isEmpty()
    }

    @Test
    fun `strict function with column reference is not detected as non-null`() {
      // Column references (not ?) can't be proven non-null by SQL text analysis alone
      val result = analyzer.findNonNullAliases("SELECT upper(text_col) AS uppered FROM users WHERE id = ?")
      assertThat(result).isEmpty()
    }

    @Test
    fun `strict function with mixed column and param args is not non-null`() {
      val result = analyzer.findNonNullAliases("SELECT substr(text_col, ?) AS result FROM users WHERE id = ?")
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
    fun `mix of non-null and nullable aliases without parameter info`() {
      val result = analyzer.findNonNullAliases(
        "SELECT upper(?) AS uppered, concat(?, ?) AS joined, COALESCE(age, 0) AS safe_age FROM users",
      )
      // upper(?) is nullable because ? nullability is unknown; COALESCE is always non-null
      assertThat(result).containsExactlyInAnyOrder("safe_age")
    }

    @Test
    fun `mix of non-null and nullable aliases with parameter info`() {
      val sql = "SELECT upper(?) AS uppered, concat(?, ?) AS joined, COALESCE(age, 0) AS safe_age FROM users"
      // All params non-null: upper(?) becomes non-null, concat is still nullable (non-strict)
      val result = analyzer.findNonNullAliases(sql, mapOf(1 to true, 2 to true, 3 to true))
      assertThat(result).containsExactlyInAnyOrder("uppered", "safe_age")
    }

    @Test
    fun `duplicate expressions track positions independently`() {
      // Both upper(?) calls have the same expression text — the second must not
      // reuse the first's char offset when looking up ? positions.
      val sql = "SELECT upper(?) AS first_up, upper(?) AS second_up FROM users"
      // Only param 2 is non-null, so only the second upper(?) should be non-null
      val result = analyzer.findNonNullAliases(sql, mapOf(2 to true))
      assertThat(result).containsExactlyInAnyOrder("second_up")
    }
  }

  @Nested
  inner class EdgeCases {
    @Test
    fun `expression without alias is not detected`() {
      val result = analyzer.findNonNullAliases("SELECT upper(?)")
      assertThat(result).isEmpty()
    }

    @Test
    fun `SELECT star returns empty set`() {
      val result = analyzer.findNonNullAliases("SELECT * FROM users")
      assertThat(result).isEmpty()
    }

    @Test
    fun `SELECT without FROM`() {
      val result = analyzer.findNonNullAliases("SELECT COALESCE(?, 0) AS val")
      assertThat(result).containsExactlyInAnyOrder("val")
    }

    @Test
    fun `nested subquery in EXISTS does not confuse FROM detection`() {
      val result = analyzer.findNonNullAliases(
        "SELECT EXISTS(SELECT 1 FROM users WHERE active = true) AS has_active FROM settings WHERE id = ?",
      )
      assertThat(result).containsExactlyInAnyOrder("has_active")
    }

    @Test
    fun `no function overloads returns empty for all functions`() {
      val emptyAnalyzer = SqlNullabilityAnalyzer(emptyMap())
      val result = emptyAnalyzer.findNonNullAliases("SELECT upper(?) AS result")
      // upper is strict, but without overload data we can't know that
      assertThat(result).isEmpty()
    }

    @Test
    fun `arithmetic expression is not detected as non-null`() {
      val result = analyzer.findNonNullAliases("SELECT age + 1 AS incremented FROM users WHERE id = ?")
      assertThat(result).isEmpty()
    }

    @Test
    fun `non-SELECT SQL returns empty`() {
      val result = analyzer.findNonNullAliases("INSERT INTO users(name) VALUES (?)")
      assertThat(result).isEmpty()
    }
  }
}
