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

  // JsonBehaviorType codes below are verified live (PostgreSQL 17 and 18): a JSON_VALUE/JSON_QUERY
  // `ON EMPTY ERROR`/`ON ERROR ERROR` clause emits `:btype 1` with `:expr <>` (absent); `ON EMPTY
  // EMPTY ARRAY`/`EMPTY OBJECT` emit `:btype 6`/`7` with `:expr {CONST ... :constisnull false
  // ...}` (Postgres's own internal `[]`/`{}` constant); `DEFAULT expr ON EMPTY`/`ON ERROR` emits
  // `:btype 8` with the user's own expression as `:expr`; and a bare `NULL ON EMPTY`/`ON ERROR`
  // emits `:btype 0`. JSON_EXISTS's own `ON ERROR` (no `ON EMPTY` field at all) emits `:btype 3`/
  // `4`/`5` for `TRUE`/`FALSE`/`UNKNOWN ON ERROR`, and `:btype 4` (`FALSE`) when no `ON ERROR`
  // clause is written at all. See PgNodeExpression's JSON_BEHAVIOR_* constants' own KDoc.

  private fun jsonExpr(
    op: Int,
    argument: PgNodeExpression = PgNodeExpression.Const(isNull = false),
    onEmpty: Int = PgNodeExpression.JSON_BEHAVIOR_NULL,
    onEmptyDefault: PgNodeExpression? = null,
    onError: Int = PgNodeExpression.JSON_BEHAVIOR_NULL,
    onErrorDefault: PgNodeExpression? = null,
  ) = PgNodeExpression.JsonExpr(
    op = op,
    argument = argument,
    onEmpty = onEmpty,
    onEmptyDefault = onEmptyDefault,
    onError = onError,
    onErrorDefault = onErrorDefault,
  )

  @Nested
  inner class JsonValueIsUnconditionallyNullable {

    // JSON_VALUE unwraps a path match to an SQL/JSON `null` value into a genuine SQL NULL. That
    // is a SUCCESSFUL match, not the "no match" (EMPTY)/"error" (ERROR) case the ON EMPTY/ON ERROR
    // clauses control, so no combination of those codes — however "safe" they looked for the
    // no-match/error case — can rule the successful-match-to-JSON-null case out. Verified live
    // (PostgreSQL 17 and 18): `JSON_VALUE('{"name": null}'::jsonb, '$.name' RETURNING TEXT ERROR
    // ON EMPTY ERROR ON ERROR) IS NULL` is `true`.

    @Test
    fun `ON EMPTY ERROR combined with ON ERROR ERROR is still nullable`() {
      val expression = jsonExpr(
        op = PgNodeExpression.JSON_VALUE_OP,
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_ERROR,
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `ON EMPTY EMPTY ARRAY with a non-null default is still nullable`() {
      val expression = jsonExpr(
        op = PgNodeExpression.JSON_VALUE_OP,
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_EMPTY_ARRAY,
        onEmptyDefault = PgNodeExpression.Const(isNull = false),
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `ON EMPTY DEFAULT with a non-null default expression is still nullable`() {
      val expression = jsonExpr(
        op = PgNodeExpression.JSON_VALUE_OP,
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_DEFAULT,
        onEmptyDefault = PgNodeExpression.Const(isNull = false),
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `a non-null context item does not make it non-null`() {
      val expression = jsonExpr(
        op = PgNodeExpression.JSON_VALUE_OP,
        argument = PgNodeExpression.Const(isNull = false),
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_ERROR,
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }
  }

  @Nested
  inner class JsonQueryEmptyErrorBehavior {

    // Unlike JSON_VALUE, JSON_QUERY never unwraps a matched JSON `null` into a genuine SQL NULL —
    // it returns the JSON text `null` instead (verified live: `JSON_QUERY('{"a": null}'::jsonb,
    // '$.a' EMPTY ARRAY ON EMPTY EMPTY OBJECT ON ERROR) IS NULL` is `false`). So, unlike
    // JSON_VALUE, its ON EMPTY/ON ERROR behavior codes remain a valid non-null signal — provided
    // the context item itself is also proven non-null (verified live: `JSON_QUERY(NULL::jsonb,
    // '$' EMPTY ARRAY ON EMPTY EMPTY OBJECT ON ERROR) IS NULL` is `true`).

    private fun jsonQuery(
      argument: PgNodeExpression = PgNodeExpression.Const(isNull = false),
      onEmpty: Int,
      onEmptyDefault: PgNodeExpression? = null,
      onError: Int,
      onErrorDefault: PgNodeExpression? = null,
    ) = jsonExpr(
      op = PgNodeExpression.JSON_QUERY_OP,
      argument = argument,
      onEmpty = onEmpty,
      onEmptyDefault = onEmptyDefault,
      onError = onError,
      onErrorDefault = onErrorDefault,
    )

    @Test
    fun `ON EMPTY NULL is nullable regardless of ON ERROR`() {
      val expression =
        jsonQuery(onEmpty = PgNodeExpression.JSON_BEHAVIOR_NULL, onError = PgNodeExpression.JSON_BEHAVIOR_ERROR)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `ON EMPTY ERROR combined with ON ERROR ERROR and a non-null context item is non-null`() {
      val expression =
        jsonQuery(onEmpty = PgNodeExpression.JSON_BEHAVIOR_ERROR, onError = PgNodeExpression.JSON_BEHAVIOR_ERROR)
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `a nullable context item makes it nullable even with safe ON EMPTY and ON ERROR`() {
      val expression = jsonQuery(
        argument = PgNodeExpression.Const(isNull = true),
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_ERROR,
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `ON EMPTY EMPTY ARRAY with the verified non-null internal constant is non-null`() {
      val expression = jsonQuery(
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_EMPTY_ARRAY,
        onEmptyDefault = PgNodeExpression.Const(isNull = false),
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `ON EMPTY DEFAULT with a non-null default expression is non-null`() {
      val expression = jsonQuery(
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_DEFAULT,
        onEmptyDefault = PgNodeExpression.Const(isNull = false),
        onError = PgNodeExpression.JSON_BEHAVIOR_ERROR,
      )
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `ON EMPTY DEFAULT with a null default expression is nullable`() {
      // DEFAULT null::jsonb ON EMPTY is legal PostgreSQL — the behavior code alone must not be
      // trusted; its own expression's nullability still has to be checked.
      val expression = jsonQuery(
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
        jsonQuery(onEmpty = unrecognizedFutureBehaviorCode, onError = PgNodeExpression.JSON_BEHAVIOR_ERROR)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `an unrecognized ON ERROR behavior code defaults to nullable, the safe direction`() {
      val unrecognizedFutureBehaviorCode = 42
      val expression =
        jsonQuery(onEmpty = PgNodeExpression.JSON_BEHAVIOR_ERROR, onError = unrecognizedFutureBehaviorCode)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }
  }

  @Nested
  inner class JsonExistsBehavior {

    // JSON_EXISTS has no ON EMPTY clause at all — only ON ERROR, whose codes are TRUE(3)/
    // FALSE(4)/ERROR(1)/UNKNOWN(5), never NULL(0)/EMPTY_ARRAY(6)/EMPTY_OBJECT(7)/DEFAULT(8) (all
    // rejected by Postgres's parser for JSON_EXISTS). Verified live: with no ON ERROR clause
    // written, Postgres materializes `:btype 4` (FALSE) — the SQL-standard default — so absence of
    // the clause is exactly as safe as writing FALSE/TRUE/ERROR explicitly. Only UNKNOWN ON ERROR,
    // or a NULL context item, produces a genuine SQL NULL (verified live:
    // `JSON_EXISTS(NULL::jsonb, '$.a') IS NULL` is `true`).

    private fun jsonExists(argument: PgNodeExpression = PgNodeExpression.Const(isNull = false), onError: Int) =
      jsonExpr(op = PgNodeExpression.JSON_EXISTS_OP, argument = argument, onError = onError)

    @Test
    fun `a non-null context item with the default FALSE ON ERROR behavior is non-null`() {
      val expression = jsonExists(onError = PgNodeExpression.JSON_BEHAVIOR_FALSE)
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `TRUE ON ERROR is non-null`() {
      val expression = jsonExists(onError = PgNodeExpression.JSON_BEHAVIOR_TRUE)
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `ERROR ON ERROR is non-null`() {
      val expression = jsonExists(onError = PgNodeExpression.JSON_BEHAVIOR_ERROR)
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `UNKNOWN ON ERROR is nullable`() {
      val expression = jsonExists(onError = PgNodeExpression.JSON_BEHAVIOR_UNKNOWN)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `a nullable context item is nullable even with the safe FALSE ON ERROR behavior`() {
      val expression =
        jsonExists(argument = PgNodeExpression.Const(isNull = true), onError = PgNodeExpression.JSON_BEHAVIOR_FALSE)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `an unrecognized ON ERROR behavior code defaults to nullable, the safe direction`() {
      val unrecognizedFutureBehaviorCode = 42
      val expression = jsonExists(onError = unrecognizedFutureBehaviorCode)
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }
  }
}
