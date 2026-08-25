package norm.generator

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PgNodeTreeParser]'s raw-text scanning against synthetic `pg_node_tree` text
 * containing backslash-escaped characters.
 *
 * Postgres's node-tree writer (`outToken` in `outfuncs.c`) backslash-escapes `{`, `}`, `(`, `)`,
 * whitespace, and the backslash character itself when they appear inside a string/identifier
 * value (e.g. a column alias). A scanner that counts braces/parens without skipping these escaped
 * pairs miscounts, and — for a query using GROUPING SETS/CUBE/ROLLUP — silently defeats the whole
 * grouping-sets nullability fix (see [NodeTreeNullabilityAnalyzer]).
 */
class PgNodeTreeParserTest {

  private val parser = PgNodeTreeParser()

  @Nested
  inner class EscapeAwareBraceScanning {

    @Test
    fun `hasGroupingSets is not fooled by an escaped closing brace earlier in the text`() {
      // Without escape-awareness, the scanner treats the escaped `}` in `k\}x` as a real close
      // brace, drives braceDepth to 0, and stops scanning before ever reaching :groupingSets.
      val text = """{QUERY :foo k\}x :groupingSets ({GROUPINGSET :kind 1 :content (i 1) :location -1})}"""
      assertThat(parser.hasGroupingSets(text)).isTrue()
    }

    @Test
    fun `hasGroupingSets is not fooled by an escaped opening brace earlier in the text`() {
      // Without escape-awareness, the scanner treats the escaped `{` in `k\{x` as a real open
      // brace, permanently pushing braceDepth to 2 so the depth-1 marker match never fires again.
      val text = """{QUERY :foo k\{x :groupingSets ({GROUPINGSET :kind 1 :content (i 1) :location -1})}"""
      assertThat(parser.hasGroupingSets(text)).isTrue()
    }

    @Test
    fun `hasGroupingSets is not fooled by a backslash immediately followed by a brace`() {
      // An alias containing a literal backslash followed by a literal `}` (e.g. an identifier
      // "k\}m") is written as THREE consecutive backslashes then `}` — the escaped backslash
      // ("\\") immediately followed by the escaped brace ("\}"). A scanner that does not process
      // escapes strictly left-to-right, two characters at a time, can lose parity here and either
      // treat the final `}` as real or desynchronize entirely.
      val text = """{QUERY :foo k\\\}m :groupingSets ({GROUPINGSET :kind 1 :content (i 1) :location -1})}"""
      assertThat(parser.hasGroupingSets(text)).isTrue()
    }

    @Test
    fun `parseTargetList reads the full, unescaped resname of an entry whose alias contains an escaped brace`() {
      // Without escape-awareness, extractBalancedBraces closes the FIRST {TARGETENTRY ...} block
      // early at the escaped `}` inside its own :resname, truncating the block before ":resname"'s
      // real value ends — resultName comes back as "k\}" (missing the trailing "x") instead of
      // the correctly-bounded and unescaped "k}x". This is the same brace-counting bug
      // hasGroupingSets hits, just visible through a different accessor: the fields extracted
      // from a wrongly-bounded block are wrong, not just the block boundary.
      val text = """
        {QUERY :targetList (
          {TARGETENTRY :expr {CONST :constisnull false} :resno 1 :resname k\}x :resjunk false}
          {TARGETENTRY :expr {CONST :constisnull false} :resno 2 :resname y :resjunk false}
        )}
      """.trimIndent()
      val entries = parser.parseTargetList(text)
      assertThat(entries).hasSize(2)
      assertThat(entries[0].resultNumber).isEqualTo(1)
      assertThat(entries[0].resultName).isEqualTo("k}x")
      assertThat(entries[1].resultNumber).isEqualTo(2)
      assertThat(entries[1].resultName).isEqualTo("y")
    }
  }

  @Nested
  inner class FuncVariadicFieldExtraction {

    // parseFuncExpr's :funcvariadic extraction matches the FIRST occurrence anywhere in a node's
    // text, including inside nested blocks — see its KDoc for why that is only safe because the
    // outer FUNCEXPR's own :funcvariadic always precedes :args textually. These two tests use real
    // ev_action text (verified live on PostgreSQL 17: `upper(concat(VARIADIC arr))` and
    // `concat(VARIADIC ARRAY[upper(a)])`) covering both nesting directions, so a future PostgreSQL
    // format change that broke this invariant would fail a test here instead of silently flipping
    // a nullability verdict.

    @Test
    fun `a non-variadic outer FuncExpr wrapping a variadic inner one keeps both flags correct`() {
      // upper(concat(VARIADIC arr)): the OUTER upper(...) call is not variadic, but its own :args
      // contains a nested concat(VARIADIC ...) call that IS.
      val text = "{FUNCEXPR :funcid 871 :funcresulttype 25 :funcretset false :funcvariadic false " +
        ":funcformat 0 :funccollid 100 :inputcollid 100 :args " +
        "({FUNCEXPR :funcid 3058 :funcresulttype 25 :funcretset false :funcvariadic true " +
        ":funcformat 0 :funccollid 100 :inputcollid 100 :args " +
        "({VAR :varno 1 :varattno 2 :vartype 1009 :vartypmod -1 :varcollid 100 :varnullingrels (b) " +
        ":varlevelsup 0 :varnosyn 1 :varattnosyn 2 :location -1}) :location -1}) :location -1}"
      val outer = parser.parseExpression(text) as PgNodeExpression.FuncExpr
      assertThat(outer.isVariadic).isFalse()
      val inner = outer.arguments.single() as PgNodeExpression.FuncExpr
      assertThat(inner.isVariadic).isTrue()
    }

    @Test
    fun `a variadic outer FuncExpr wrapping a non-variadic inner one keeps both flags correct`() {
      // concat(VARIADIC ARRAY[upper(a)]): the reverse nesting — the OUTER concat(...) call IS
      // variadic, but its array literal argument contains a nested upper(...) call that is NOT.
      val text = "{FUNCEXPR :funcid 3058 :funcresulttype 25 :funcretset false :funcvariadic true " +
        ":funcformat 0 :funccollid 100 :inputcollid 100 :args " +
        "({ARRAYEXPR :array_typeid 1009 :array_collid 100 :element_typeid 25 :elements " +
        "({FUNCEXPR :funcid 871 :funcresulttype 25 :funcretset false :funcvariadic false " +
        ":funcformat 0 :funccollid 100 :inputcollid 100 :args " +
        "({VAR :varno 1 :varattno 1 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) " +
        ":varlevelsup 0 :varnosyn 1 :varattnosyn 1 :location -1}) :location -1}) :multidims false " +
        ":location -1}) :location -1}"
      val outer = parser.parseExpression(text) as PgNodeExpression.FuncExpr
      assertThat(outer.isVariadic).isTrue()
      val arrayArgument = outer.arguments.single() as PgNodeExpression.ArrayExpr
      val inner = arrayArgument.elements.single() as PgNodeExpression.FuncExpr
      assertThat(inner.isVariadic).isFalse()
    }

    @Test
    fun `a FUNCEXPR block missing funcvariadic entirely defaults to variadic, the safe direction`() {
      // Malformed/future-format input: :funcvariadic is simply absent. The safe default must be
      // `true` (variadic) — NodeTreeNullabilityAnalyzer.isNonNull treats a variadic call far more
      // conservatively than an ordinary one, so an unreadable flag must degrade toward nullable,
      // never toward trusting the ordinary-call safe-list.
      val text = "{FUNCEXPR :funcid 871 :funcresulttype 25 :funcretset false " +
        ":funcformat 0 :funccollid 100 :inputcollid 100 :args (" +
        "{CONST :consttype 25 :constisnull false}) :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.FuncExpr
      assertThat(result.isVariadic).isTrue()
    }
  }

  @Nested
  inner class ConstIsNullFieldExtraction {

    @Test
    fun `a real non-null CONST with a correctly-parsed constisnull field is reported non-null`() {
      // Positive case: the happy path must not be widened to nullable by the safe-default fix.
      val text = "{CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 " +
        ":constbyval false :constisnull false :location -1 :constvalue 5 [ 97 98 99 100 101 ]}"
      val result = parser.parseExpression(text) as PgNodeExpression.Const
      assertThat(result.isNull).isFalse()
    }

    @Test
    fun `a CONST block missing constisnull entirely defaults to null, the safe direction`() {
      // Malformed/future-format input: :constisnull is simply absent. The safe default must be
      // `true` (isNull) — never `false`, which would report a genuinely-unreadable CONST as
      // confidently non-null.
      val text = "{CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 " +
        ":constbyval false :location -1 :constvalue 4 [ 1 0 0 0 ]}"
      val result = parser.parseExpression(text) as PgNodeExpression.Const
      assertThat(result.isNull).isTrue()
    }
  }

  @Nested
  inner class GroupRteExpressionParsing {

    @Test
    fun `a PostgreSQL 18 GROUP RTE's groupexprs entry parses into the expression it describes`() {
      // Shape verified live against a real PostgreSQL 18 pg_rewrite.ev_action for
      // `SELECT count(*) + 0::bigint AS c, 0::bigint AS k FROM t GROUP BY ROLLUP((0::bigint))`: the
      // GROUP RTE (rtekind 9) is the SECOND :rtable entry, so its varno is 2, and its :groupexprs
      // holds one FUNCEXPR (the implicit int4->int8 cast of the literal 0) wrapping one CONST.
      val text = """
        {QUERY :rtable (
          {RANGETBLENTRY :rtekind 0 :relid 24819 :relkind r}
          {RANGETBLENTRY :eref {ALIAS :aliasname *GROUP* :colnames ("k")} :rtekind 9 :groupexprs (
            {FUNCEXPR :funcid 481 :funcresulttype 20 :funcretset false :funcvariadic false
             :funcformat 0 :funccollid 0 :inputcollid 0 :args (
               {CONST :consttype 20 :consttypmod -1 :constcollid 0 :constlen 8 :constbyval true
                :constisnull false :location -1 :constvalue 8 [ 0 0 0 0 0 0 0 0 ]}
             ) :location -1}
          )}
        )}
      """.trimIndent()
      val result = parser.parseGroupRteExpressions(text)
      assertThat(result).hasSize(1)
      val groupExpressions = result.getValue(2)
      assertThat(groupExpressions).hasSize(1)
      val functionCall = groupExpressions.single() as PgNodeExpression.FuncExpr
      assertThat(functionCall.functionOid).isEqualTo(481)
      val argument = functionCall.arguments.single() as PgNodeExpression.Const
      assertThat(argument.isNull).isFalse()
    }

    @Test
    fun `a PostgreSQL 16-shaped rtable with no GROUP RTE returns an empty map`() {
      // No :rtekind 9 entry anywhere — the shape every PostgreSQL 16/17 tree has, and the shape a
      // PostgreSQL 18 tree without GROUP BY has too.
      val text = """
        {QUERY :rtable (
          {RANGETBLENTRY :rtekind 0 :relid 24819 :relkind r}
          {RANGETBLENTRY :rtekind 1 :relid 0}
        )}
      """.trimIndent()
      assertThat(parser.parseGroupRteExpressions(text)).hasSize(0)
    }

    @Test
    fun `a truncated groupexprs list returns an empty map without throwing`() {
      // The :groupexprs opening parenthesis is present but its content is cut off mid-block —
      // extractOuterSectionContent's balanced-parenthesis scan can never find a matching close, so
      // this GROUP RTE contributes nothing rather than parsing a truncated fragment.
      val text = """
        {QUERY :rtable (
          {RANGETBLENTRY :eref {ALIAS :aliasname *GROUP* :colnames ("k")} :rtekind 9 :groupexprs (
            {FUNCEXPR :funcid 481 :args (
      """.trimIndent()
      assertThat(parser.parseGroupRteExpressions(text)).hasSize(0)
    }

    @Test
    fun `multiple groupexprs entries parse in declaration order`() {
      // Two grouping keys — a bare VAR (varattno 1 in the base table) and a CONST — must resolve
      // at list indices 0 and 1 respectively, matching their :groupexprs declaration order, so a
      // target-list Var with :varattno 2 (1-based) indexes the CONST, never the VAR.
      val text = """
        {QUERY :rtable (
          {RANGETBLENTRY :rtekind 0 :relid 24819 :relkind r}
          {RANGETBLENTRY :eref {ALIAS :aliasname *GROUP* :colnames ("a" "b")} :rtekind 9 :groupexprs (
            {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b)
             :varlevelsup 0 :varnosyn 1 :varattnosyn 2 :location -1}
            {CONST :consttype 20 :consttypmod -1 :constcollid 0 :constlen 8 :constbyval true
             :constisnull false :location -1 :constvalue 8 [ 5 0 0 0 0 0 0 0 ]}
          )}
        )}
      """.trimIndent()
      val result = parser.parseGroupRteExpressions(text)
      val groupExpressions = result.getValue(2)
      assertThat(groupExpressions).hasSize(2)
      val first = groupExpressions[0] as PgNodeExpression.Var
      assertThat(first.varno).isEqualTo(1)
      assertThat(first.varattno).isEqualTo(2)
      val second = groupExpressions[1] as PgNodeExpression.Const
      assertThat(second.isNull).isFalse()
    }
  }

  @Nested
  inner class ConstStructuralEquality {

    @Test
    fun `two CONST blocks with the same value but different source locations are structurally equal`() {
      // NodeTreeNullabilityAnalyzer's GROUPING SETS duplicate-key detection recognizes a
      // syntactically-repeated grouping key expression (e.g. `concat(a, '-')` written twice in
      // the same SELECT list — see QueryAnalysisTest.Grouping's "duplicate concat call that IS
      // the ROLLUP key" test) by comparing parsed PgNodeExpression subtrees with `==`. Postgres
      // assigns each occurrence of the SAME literal its OWN :location (a source-text byte
      // offset), so this equality must hold across TWO Consts whose :location values genuinely
      // differ — pinning this directly, rather than only through the end-to-end grouping-sets
      // test, so a future change adding a location-like field to Const (this project already
      // tried once, for prosqlbody's per-assignment parameter-trust check, and reverted it for
      // exactly this reason) fails a fast, obvious, unit-level test instead of a live-database one.
      val first = parser.parseExpression(
        "{CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 " +
          ":constbyval false :constisnull false :location 42 :constvalue 5 [ 20 0 0 0 45 ]}",
      )
      val second = parser.parseExpression(
        "{CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 " +
          ":constbyval false :constisnull false :location 137 :constvalue 5 [ 20 0 0 0 45 ]}",
      )
      assertThat(first).isEqualTo(second)
    }
  }
}
