package norm.generator

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NodeTreeNullabilityAnalyzer.isNonNull] against directly-constructed
 * [PgNodeExpression] values, covering behavior this class computes that is not already exercised
 * through the full JDBC-backed golden-file scenarios in `QueryAnalysisTest`.
 */
class NodeTreeNullabilityAnalyzerTest {

  private fun analyzer(
    isStrict: (Int) -> Boolean = { false },
    isNeverNullForNonNullInput: (Int) -> Boolean = { false },
  ) = NodeTreeNullabilityAnalyzer(
    isStrict = isStrict,
    hasNonNullInitialValue = { false },
    isSourceColumnNotNull = { _, _ -> false },
    isOuterJoinNullable = { it.isNotEmpty() },
    isNeverNullForNonNullInput = isNeverNullForNonNullInput,
  )

  @Nested
  inner class JsonValueAndJsonQueryEmptyErrorBehavior {

    // JsonBehaviorType codes below are verified live (PostgreSQL 17 and 18): a JSON_VALUE/JSON_QUERY
    // `ON EMPTY ERROR`/`ON ERROR ERROR` clause emits `:btype 1` with `:expr <>` (absent); `ON EMPTY
    // EMPTY ARRAY`/`EMPTY OBJECT` emit `:btype 6`/`7` with `:expr {CONST ... :constisnull false
    // ...}` (Postgres's own internal `[]`/`{}` constant); `DEFAULT expr ON EMPTY`/`ON ERROR` emits
    // `:btype 8` with the user's own expression as `:expr`; and a bare `NULL ON EMPTY`/`ON ERROR`
    // emits `:btype 0`. See PgNodeExpression's JSON_BEHAVIOR_* constants' own KDoc.

    private fun jsonValue(
      onEmpty: Int,
      onEmptyDefault: PgNodeExpression? = null,
      onError: Int,
      onErrorDefault: PgNodeExpression? = null,
    ) = PgNodeExpression.JsonExpr(
      op = PgNodeExpression.JSON_VALUE_OP,
      argument = PgNodeExpression.Const(isNull = false),
      onEmpty = onEmpty,
      onEmptyDefault = onEmptyDefault,
      onError = onError,
      onErrorDefault = onErrorDefault,
    )

    @Test
    fun `ON EMPTY NULL is nullable regardless of ON ERROR`() {
      val expression =
        jsonValue(onEmpty = PgNodeExpression.JSON_BEHAVIOR_NULL, onError = PgNodeExpression.JSON_BEHAVIOR_ERROR)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `ON EMPTY ERROR combined with ON ERROR ERROR is non-null`() {
      // Positive case: the two behavior codes this fix keeps trusting must still be trusted.
      val expression =
        jsonValue(onEmpty = PgNodeExpression.JSON_BEHAVIOR_ERROR, onError = PgNodeExpression.JSON_BEHAVIOR_ERROR)
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `ON EMPTY EMPTY ARRAY with the verified non-null internal constant is non-null`() {
      val expression = jsonValue(
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_EMPTY_ARRAY,
        onEmptyDefault = PgNodeExpression.Const(isNull = false),
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `ON EMPTY DEFAULT with a non-null default expression is non-null`() {
      val expression = jsonValue(
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_DEFAULT,
        onEmptyDefault = PgNodeExpression.Const(isNull = false),
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `ON EMPTY DEFAULT with a null default expression is nullable`() {
      // DEFAULT null::int ON EMPTY is legal PostgreSQL — the behavior code alone must not be
      // trusted; its own expression's nullability still has to be checked.
      val expression = jsonValue(
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_DEFAULT,
        onEmptyDefault = PgNodeExpression.Const(isNull = true),
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `an unrecognized ON EMPTY behavior code defaults to nullable, the safe direction`() {
      // This is the deny-list bug: a hypothetical future JsonBehaviorType Postgres has not added
      // yet (modeled here as an arbitrary out-of-range code) must NOT be treated as non-null
      // merely because it is not JSON_BEHAVIOR_NULL. ON ERROR is pinned to the verified-safe
      // ERROR code so this test isolates the ON EMPTY allow-list check specifically.
      val unrecognizedFutureBehaviorCode = 42
      val expression =
        jsonValue(onEmpty = unrecognizedFutureBehaviorCode, onError = PgNodeExpression.JSON_BEHAVIOR_ERROR)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `an unrecognized ON ERROR behavior code defaults to nullable, the safe direction`() {
      val unrecognizedFutureBehaviorCode = 42
      val expression =
        jsonValue(onEmpty = PgNodeExpression.JSON_BEHAVIOR_ERROR, onError = unrecognizedFutureBehaviorCode)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }
  }
}
