package norm.generator

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Tests for [resolveComputedExpressions], which needs no database connection. */
class ComputedExpressionResolverTest {

  private fun computedColumn(name: String, table: Identifier? = null) =
    Column(name = name, notNull = true, type = Identifier(name = "int4"), table = table)

  @Nested
  inner class ExpressionResolved {

    @Test
    fun `a computed expression carries its text`() {
      val resolved = resolveComputedExpressions("SELECT LENGTH(a) AS a_len FROM t", listOf(computedColumn("a_len")))

      assertThat(resolved.single().computedExpression).isEqualTo("LENGTH(a)")
    }

    @Test
    fun `a comment and extra whitespace inside the expression are stripped and collapsed`() {
      val queryText = "SELECT a   +  /* note */\n  b -- trailing\n  AS total FROM t"

      val resolved = resolveComputedExpressions(queryText, listOf(computedColumn("total")))

      assertThat(resolved.single().computedExpression).isEqualTo("a + b")
    }

    @Test
    fun `each column receives the expression at its own position`() {
      val queryText = "SELECT LENGTH(a) AS a_len, UPPER(b) AS b_upper FROM t"

      val resolved = resolveComputedExpressions(queryText, listOf(computedColumn("a_len"), computedColumn("b_upper")))

      assertThat(resolved.map { it.computedExpression }).isEqualTo(listOf("LENGTH(a)", "UPPER(b)"))
    }

    @Test
    fun `columns other than computedExpression are returned unchanged`() {
      val column = Column(
        name = "a_len",
        notNull = true,
        type = Identifier(name = "int4"),
        comment = "kept",
        provenanceExpression = "LENGTH(a)",
      )

      val resolved = resolveComputedExpressions("SELECT LENGTH(a) AS a_len FROM t", listOf(column))

      assertThat(resolved.single()).isEqualTo(column.copy(computedExpression = "LENGTH(a)"))
    }
  }

  @Nested
  inner class ExpressionSuppressed {

    @Test
    fun `a select-item count that disagrees with the column count yields no expressions`() {
      val queryText = "SELECT LENGTH(a) AS a_len, UPPER(b) AS b_upper, LOWER(c) AS c_lower FROM t"

      val resolved = resolveComputedExpressions(queryText, listOf(computedColumn("a_len"), computedColumn("c_lower")))

      assertThat(resolved.map { it.computedExpression }).isEqualTo(listOf(null, null))
    }

    @Test
    fun `a top-level set operation yields no expressions`() {
      val queryText = "SELECT UPPER(x) AS u FROM t UNION SELECT LOWER(x) FROM t"

      val resolved = resolveComputedExpressions(queryText, listOf(computedColumn("u")))

      assertThat(resolved.single().computedExpression).isNull()
    }

    @Test
    fun `a star item yields no expression`() {
      val resolved = resolveComputedExpressions("SELECT * FROM c", listOf(computedColumn("u")))

      assertThat(resolved.single().computedExpression).isNull()
    }

    @Test
    fun `a column with a source table yields no expression even when its select item is computed`() {
      val column = computedColumn("n", table = Identifier(schema = "public", name = "t"))

      val resolved = resolveComputedExpressions("SELECT count(*) AS n FROM t", listOf(column))

      assertThat(resolved.single().computedExpression).isNull()
    }

    @Test
    fun `a bare column reference yields no expression`() {
      val resolved = resolveComputedExpressions("SELECT id FROM t", listOf(computedColumn("id")))

      assertThat(resolved.single().computedExpression).isNull()
    }

    @Test
    fun `empty query text yields no expressions`() {
      val resolved = resolveComputedExpressions("", listOf(computedColumn("a"), computedColumn("b")))

      assertThat(resolved.map { it.computedExpression }).containsOnly(null)
    }
  }
}
