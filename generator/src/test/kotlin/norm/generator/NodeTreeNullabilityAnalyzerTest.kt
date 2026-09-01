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
    isSourceColumnNotNull: (varno: Int, varattno: Int) -> Boolean = { _, _ -> false },
    isSubLinkSubqueryColumnNotNull: (subselectBlock: String) -> Boolean = { false },
    isAlwaysNonNull: (Int) -> Boolean = { false },
    isNonNullIffFirstArgumentNonNull: (Int) -> Boolean = { false },
    isFoldableToConst: (Int) -> Boolean = { false },
  ) = NodeTreeNullabilityAnalyzer(
    isStrict = isStrict,
    hasNonNullInitialValue = { false },
    isSourceColumnNotNull = isSourceColumnNotNull,
    isOuterJoinNullable = { it.isNotEmpty() },
    isAlwaysNonNull = isAlwaysNonNull,
    isNeverNullForNonNullInput = isNeverNullForNonNullInput,
    isFoldableToConst = isFoldableToConst,
    isNonNullIffFirstArgumentNonNull = isNonNullIffFirstArgumentNonNull,
    isSubLinkSubqueryColumnNotNull = isSubLinkSubqueryColumnNotNull,
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

  @Nested
  inner class JsonConstructorExprEvaluation {

    @Test
    fun `a JSON_SERIALIZE-shaped node with an empty argument list is nullable, not vacuously non-null`() {
      // Without the arguments.isNotEmpty() guard, `arguments.all(recurse)` is vacuously true over an
      // empty list — the same unsound shape as the original `JsonConstructorExpr -> true` bug. A
      // malformed `:args` parses down to an empty list rather than throwing, so this is reachable.
      val expression = PgNodeExpression.JsonConstructorExpr(
        type = PgNodeExpression.JSON_CONSTRUCTOR_TYPE_SERIALIZE,
        arguments = emptyList(),
      )
      assertThat(analyzer().isNonNull(expression)).isFalse()
    }
  }

  @Nested
  inner class SubLinks {

    // See issue #239's own live-verified repro (PostgreSQL 17): `a = ANY (SELECT v FROM u)` is
    // NULL, not FALSE, when u.v is nullable and the subquery yields a NULL row with no match —
    // three-valued logic, not the two-valued logic a naive "outer operand is non-null" check
    // assumes. Proving ANY_SUBLINK non-null therefore requires ALL of: a non-null outer operand,
    // a strict-and-total comparison operator, and a provably non-null single-column subquery
    // result — see NodeTreeNullabilityAnalyzer's SubLink branch for the full rule.

    private val comparisonOperatorOid = 100

    private fun anySubLink(
      outerOperand: PgNodeExpression? = PgNodeExpression.Const(isNull = false),
      subselectBlock: String? = "{QUERY :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 1} :resno 1})}",
      testExpressionOperatorOid: Int? = comparisonOperatorOid,
    ) = PgNodeExpression.SubLink(
      subLinkType = PgNodeExpression.SUBLINK_TYPE_ANY,
      outerOperand = outerOperand,
      subselectBlock = subselectBlock,
      testExpressionOperatorOid = testExpressionOperatorOid,
    )

    private fun allSubLink(
      outerOperand: PgNodeExpression? = PgNodeExpression.Const(isNull = false),
      subselectBlock: String? = "{QUERY :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 1} :resno 1})}",
      testExpressionOperatorOid: Int? = comparisonOperatorOid,
    ) = PgNodeExpression.SubLink(
      subLinkType = PgNodeExpression.SUBLINK_TYPE_ALL,
      outerOperand = outerOperand,
      subselectBlock = subselectBlock,
      testExpressionOperatorOid = testExpressionOperatorOid,
    )

    private fun safeAnalyzer(isSubLinkSubqueryColumnNotNull: (String) -> Boolean = { true }) = analyzer(
      isStrict = { it == comparisonOperatorOid },
      isNeverNullForNonNullInput = { it == comparisonOperatorOid },
      isSubLinkSubqueryColumnNotNull = isSubLinkSubqueryColumnNotNull,
    )

    @Test
    fun `EXISTS sublink is non-null`() {
      val expression = PgNodeExpression.SubLink(subLinkType = PgNodeExpression.SUBLINK_TYPE_EXISTS)
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `ARRAY sublink is non-null`() {
      val expression = PgNodeExpression.SubLink(subLinkType = PgNodeExpression.SUBLINK_TYPE_ARRAY)
      assertThat(analyzer().isNonNull(expression)).isTrue()
    }

    @Test
    fun `ROWCOMPARE sublink is nullable even when all three ANY-style conditions are satisfied`() {
      // An EMPTY subquery yields NULL for ROWCOMPARE, unlike ANY/ALL, so no combination of the three
      // conditions can rescue it — it is deliberately excluded from isNonNull's SubLink proof.
      val expression = PgNodeExpression.SubLink(
        subLinkType = PgNodeExpression.SUBLINK_TYPE_ROWCOMPARE,
        outerOperand = PgNodeExpression.Const(isNull = false),
        subselectBlock = "{QUERY :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 1} :resno 1})}",
        testExpressionOperatorOid = comparisonOperatorOid,
      )
      assertThat(safeAnalyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `a scalar EXPR sublink is nullable`() {
      // subLinkType 4 (EXPR_SUBLINK, an ordinary scalar subquery like `(SELECT max(id) FROM t)`)
      // is not one of the three cases isNonNull's SubLink branch special-cases (EXISTS, ARRAY,
      // ANY), so it always falls through to nullable regardless of anything else about it.
      val expression = PgNodeExpression.SubLink(subLinkType = 4)
      assertThat(safeAnalyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `ANY sublink is non-null when all three conditions are satisfied`() {
      assertThat(safeAnalyzer().isNonNull(anySubLink())).isTrue()
    }

    @Test
    fun `ANY sublink is nullable when the outer operand is nullable`() {
      val expression = anySubLink(outerOperand = PgNodeExpression.Const(isNull = true))
      assertThat(safeAnalyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `ANY sublink is nullable when the subquery column is nullable`() {
      val expression = anySubLink()
      assertThat(safeAnalyzer(isSubLinkSubqueryColumnNotNull = { false }).isNonNull(expression)).isFalse()
    }

    @Test
    fun `ANY sublink is nullable when subselectBlock is null`() {
      val expression = anySubLink(subselectBlock = null)
      assertThat(safeAnalyzer().isNonNull(expression)).isFalse()
    }

    @Test
    fun `ANY sublink is nullable when testExpressionOperatorOid is null, the row-comparison form`() {
      val expression = anySubLink(testExpressionOperatorOid = null)
      val result = analyzer(isStrict = {
        true
      }, isNeverNullForNonNullInput = { true }, isSubLinkSubqueryColumnNotNull = { true })
        .isNonNull(expression)
      assertThat(result).isFalse()
    }

    @Test
    fun `ANY sublink is nullable when the comparison operator is not strict`() {
      val expression = anySubLink()
      val result = analyzer(isStrict = {
        false
      }, isNeverNullForNonNullInput = { true }, isSubLinkSubqueryColumnNotNull = { true })
        .isNonNull(expression)
      assertThat(result).isFalse()
    }

    @Test
    fun `ANY sublink is nullable when the comparison operator is strict but not proven total on non-null input`() {
      val expression = anySubLink()
      val result = analyzer(isStrict = {
        true
      }, isNeverNullForNonNullInput = { false }, isSubLinkSubqueryColumnNotNull = { true })
        .isNonNull(expression)
      assertThat(result).isFalse()
    }

    @Test
    fun `ALL sublink is non-null when all three conditions are satisfied`() {
      assertThat(safeAnalyzer().isNonNull(allSubLink())).isTrue()
    }

    @Test
    fun `a Var with levelsUp 1 is nullable even when isSourceColumnNotNull returns true`() {
      // A Var with levelsUp greater than 0 indexes an ENCLOSING query's range table (see
      // PgNodeExpression.Var.levelsUp's own KDoc) — this block's isSourceColumnNotNull says
      // nothing trustworthy about it, so it must stay nullable regardless of what that callback
      // returns. This guard is what makes the ANY_SUBLINK subquery-column-nullability leg sound
      // for a correlated subselect target list, e.g. `id IN (SELECT t.a FROM w)`.
      val expression = PgNodeExpression.Var(varno = 1, varattno = 1, nullingRelations = emptySet(), levelsUp = 1)
      val result = analyzer(isSourceColumnNotNull = { _, _ -> true }).isNonNull(expression)
      assertThat(result).isFalse()
    }
  }

  /**
   * Unit tests for [NodeTreeNullabilityAnalyzer.isSafeFromGroupingSetNullExtension]'s issue-#240
   * "leg A" additions: node kinds whose [NodeTreeNullabilityAnalyzer.isNonNull] rule already ignores
   * argument nullability entirely, so a deeper subexpression being null-extended cannot change the
   * result — see that method's own KDoc for the full soundness argument for each leg. Each of these
   * synthetic trees would be reported UNSAFE by the pre-#240 gate (no `Aggref`/`GroupingFunc`/
   * `WindowFunc` descendant to satisfy [NodeTreeNullabilityAnalyzer.containsDominatingConstruct]),
   * which is exactly what each leg here exists to fix.
   */
  @Nested
  inner class GroupingSetNullExtensionLegA {

    private val someFunctionOid = 200
    private val varArgument = PgNodeExpression.Var(varno = 1, varattno = 1, nullingRelations = emptySet())

    @Test
    fun `self-match guard makes a grouping key itself unsafe even though it would otherwise qualify for a leg`() {
      // Regression guard for the bug the plan's self-match guard fixes: without it, a FuncExpr on
      // isAlwaysNonNull's list would be rescued by that (already-shipped) leg even when the call
      // itself IS the grouping key being null-extended wholesale.
      val key = PgNodeExpression.FuncExpr(functionOid = someFunctionOid, arguments = listOf(varArgument))
      val safe = analyzer(isAlwaysNonNull = { it == someFunctionOid })
        .isSafeFromGroupingSetNullExtension(key, groupingKeyExpressions = setOf(key))
      assertThat(safe).isFalse()
    }

    @Test
    fun `a non-VARIADIC FuncExpr with isNonNullIffFirstArgumentNonNull is safe when its first argument is safe`() {
      // Models concat_ws(',', a, b): the later arguments (here, two bare Vars) do not matter to
      // this leg at all — only the first (separator) argument's own safety does.
      val expression = PgNodeExpression.FuncExpr(
        functionOid = someFunctionOid,
        arguments = listOf(PgNodeExpression.Const(isNull = false), varArgument, varArgument),
      )
      val safe = analyzer(isNonNullIffFirstArgumentNonNull = { it == someFunctionOid })
        .isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isTrue()
    }

    @Test
    fun `a non-VARIADIC FuncExpr with isNonNullIffFirstArgumentNonNull is unsafe when its first argument is the key`() {
      // Models concat_ws(concat(a, b), 'x', 'y') under a key of concat(a, b): the separator is
      // itself null-extendable, so the leg must recurse into it rather than assume any first
      // argument is automatically safe.
      val separator = PgNodeExpression.FuncExpr(functionOid = 999, arguments = listOf(varArgument))
      val expression = PgNodeExpression.FuncExpr(
        functionOid = someFunctionOid,
        arguments = listOf(separator, PgNodeExpression.Const(isNull = false)),
      )
      val safe = analyzer(isNonNullIffFirstArgumentNonNull = { it == someFunctionOid })
        .isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = setOf(separator))
      assertThat(safe).isFalse()
    }

    @Test
    fun `a VARIADIC FuncExpr with isNonNullIffFirstArgumentNonNull ignores this leg even for a safe first argument`() {
      // Models concat_ws(',', VARIADIC arr) under GROUP BY ROLLUP(arr): in the VARIADIC form, the
      // array itself (the LAST argument, not the first) is a single value that can be
      // null-extended, and concat_ws(',', VARIADIC arr) really is NULL when arr is NULL (verified
      // live) regardless of the separator. Without the isVariadic guard, this leg would wrongly
      // treat the safe literal separator as proof the whole call is safe.
      val arrayArgument = PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = emptySet())
      val expression = PgNodeExpression.FuncExpr(
        functionOid = someFunctionOid,
        arguments = listOf(PgNodeExpression.Const(isNull = false), arrayArgument),
        isVariadic = true,
      )
      val safe = analyzer(isNonNullIffFirstArgumentNonNull = { it == someFunctionOid })
        .isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = setOf(arrayArgument))
      assertThat(safe).isFalse()
    }

    @Test
    fun `XmlExpr XML_IS_XMLELEMENT is unconditionally safe regardless of its arguments`() {
      val expression =
        PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLELEMENT, arguments = listOf(varArgument))
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isTrue()
    }

    @Test
    fun `XmlExpr XML_IS_XMLFOREST is NOT covered by this leg — a null field can null the whole forest`() {
      val expression =
        PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLFOREST, arguments = listOf(varArgument))
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isFalse()
    }

    @Test
    fun `XmlExpr XML_IS_XMLPI is NOT covered by this leg — a null content expression nulls the result`() {
      val expression = PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLPI, arguments = listOf(varArgument))
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isFalse()
    }

    @Test
    fun `XmlExpr XML_IS_XMLCONCAT is not covered by this leg and falls through to the generic rule`() {
      val expression =
        PgNodeExpression.XmlExpr(op = PgNodeExpression.XML_IS_XMLCONCAT, arguments = listOf(varArgument))
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isFalse()
    }

    @Test
    fun `JsonConstructorExpr OBJECT is unconditionally safe regardless of its arguments`() {
      val expression = PgNodeExpression.JsonConstructorExpr(
        type = PgNodeExpression.JSON_CONSTRUCTOR_TYPE_OBJECT,
        arguments = listOf(varArgument),
      )
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isTrue()
    }

    @Test
    fun `JsonConstructorExpr ARRAY is unconditionally safe regardless of its arguments`() {
      val expression = PgNodeExpression.JsonConstructorExpr(
        type = PgNodeExpression.JSON_CONSTRUCTOR_TYPE_ARRAY,
        arguments = listOf(varArgument),
      )
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isTrue()
    }

    @Test
    fun `JsonConstructorExpr SCALAR is not covered by this leg and depends on its argument`() {
      val expression = PgNodeExpression.JsonConstructorExpr(
        type = PgNodeExpression.JSON_CONSTRUCTOR_TYPE_SCALAR,
        arguments = listOf(varArgument),
      )
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isFalse()
    }

    @Test
    fun `SqlValueFunction is unconditionally safe`() {
      val expression = PgNodeExpression.SqlValueFunction(operation = 0)
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isTrue()
    }

    @Test
    fun `NextValExpr is unconditionally safe`() {
      val expression = PgNodeExpression.NextValExpr(sequenceOid = 1)
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isTrue()
    }
  }

  /**
   * Unit tests for [NodeTreeNullabilityAnalyzer.isSafeFromGroupingSetNullExtension]'s issue-#240 "leg
   * B" addition — the private `immuneByNoGroupingKeyMatch` helper, exercised here only through the
   * public [NodeTreeNullabilityAnalyzer.isSafeFromGroupingSetNullExtension] entry point since it has
   * no test-visible seam of its own. Each test isolates one of its three required conditions by
   * constructing a case where the OTHER two conditions hold (and the generic aggregate/window-domination
   * rule also cannot rescue the case on its own, since none of these trees contains an
   * `Aggref`/`GroupingFunc`/`WindowFunc`), so only the condition under test can flip the answer.
   */
  @Nested
  inner class GroupingSetNullExtensionLegB {

    @Test
    fun `a Var-free, non-matching FuncExpr with no dominating aggregate is safe`() {
      // Models now(): a zero-argument, non-foldable, not-always-non-null FuncExpr with no aggregate
      // descendant — unsafe under every other leg, safe only via this one.
      val expression = PgNodeExpression.FuncExpr(functionOid = 1234, arguments = emptyList())
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isTrue()
    }

    @Test
    fun `a Var anywhere in the subtree defeats immunity — condition 1`() {
      val expression = PgNodeExpression.FuncExpr(
        functionOid = 1234,
        arguments = listOf(PgNodeExpression.Var(varno = 1, varattno = 1, nullingRelations = emptySet())),
      )
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isFalse()
    }

    @Test
    fun `a SubLink anywhere in the subtree defeats immunity even though it is itself Var-free — condition 2`() {
      // The SubLink's own outerOperand is Var-free, but its unparsed subselect could hide a
      // correlated Var this parser cannot see — see immuneByNoGroupingKeyMatch's own KDoc for why
      // SubLink specifically (not just JsonExpr/Unknown) must be named as lossy here.
      val subLink = PgNodeExpression.SubLink(
        subLinkType = PgNodeExpression.SUBLINK_TYPE_EXISTS,
        outerOperand = PgNodeExpression.Const(isNull = false),
      )
      val expression = PgNodeExpression.FuncExpr(functionOid = 1234, arguments = listOf(subLink))
      val safe = analyzer().isSafeFromGroupingSetNullExtension(expression, groupingKeyExpressions = emptySet())
      assertThat(safe).isFalse()
    }

    @Test
    fun `a structural match against a grouping key subexpression defeats immunity — condition 3`() {
      val keySubexpression = PgNodeExpression.FuncExpr(functionOid = 5678, arguments = emptyList())
      val expression = PgNodeExpression.FuncExpr(functionOid = 1234, arguments = listOf(keySubexpression))
      val safe = analyzer().isSafeFromGroupingSetNullExtension(
        expression,
        groupingKeyExpressions = setOf(keySubexpression),
      )
      assertThat(safe).isFalse()
    }
  }
}
