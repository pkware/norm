package norm.generator

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
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
  inner class SubLinkParsing {

    // Shapes verified live against a real PostgreSQL 17 ev_action for `a = ANY (SELECT v FROM u)`
    // (see issue #239's own repro) — a SUBLINK's :testexpr is a single top-level OPEXPR whose
    // :opfuncid is the comparison operator's function OID, and :subselect holds the subquery's own
    // {QUERY ...} block. These synthetic texts reproduce that shape at a level a unit test can
    // construct directly, without depending on a live server.

    private val anyOpexprTestexpr = "{OPEXPR :opno 98 :opfuncid 67 :args " +
      "({VAR :varno 1 :varattno 2 :varlevelsup 0 :location -1} " +
      "{PARAM :paramkind 2 :paramid 1 :location -1}) :location -1}"

    @Test
    fun `subselect block is captured verbatim, including a nested brace and a backslash-escaped closing brace`() {
      // The :resname k\}x below contains a backslash-escaped, literal `}` — see the class-level
      // note on backslash escaping. A non-escape-aware balanced-brace scan would close the
      // enclosing TARGETENTRY (and therefore the whole subselect QUERY block) too early, right at
      // that escaped brace, truncating everything after it (":resjunk false}) :setOperations <>}").
      val subselectBlock = """{QUERY :targetList (""" +
        """{TARGETENTRY :expr {CONST :constisnull false} :resno 1 :resname k\}x :resjunk false}""" +
        """) :setOperations <>}"""
      val text = "{SUBLINK :subLinkType 2 :subLinkId 0 :testexpr $anyOpexprTestexpr :operName (\"=\") " +
        ":subselect $subselectBlock :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.SubLink
      assertThat(result.subselectBlock).isEqualTo(subselectBlock)
    }

    @Test
    fun `testexpr operator OID is captured from a single OPEXPR testexpr`() {
      val text = "{SUBLINK :subLinkType 2 :subLinkId 0 :testexpr $anyOpexprTestexpr :operName (\"=\") " +
        ":subselect {QUERY :targetList (" +
        "{TARGETENTRY :expr {VAR :varno 1 :varattno 1 :varlevelsup 0 :location -1} :resno 1 :resname v " +
        ":resjunk false}) :setOperations <>} :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.SubLink
      assertThat(result.testExpressionOperatorOid).isEqualTo(67)
    }

    @Test
    fun `testexpr operator OID is null for a BOOLEXPR testexpr, the multi-column IN row-comparison form`() {
      // (a, b) IN (SELECT p, q FROM w): :testexpr combines one OPEXPR per column under a BOOLEXPR,
      // which has no single top-level :opfuncid of its own.
      val rowComparisonTestexpr = "{BOOLEXPR :boolop and :args (" +
        "{OPEXPR :opno 96 :opfuncid 65 :args (" +
        "{VAR :varno 1 :varattno 1 :varlevelsup 0 :location -1} {PARAM :paramkind 2 :paramid 1 :location -1}" +
        ") :location -1} " +
        "{OPEXPR :opno 96 :opfuncid 65 :args (" +
        "{VAR :varno 1 :varattno 2 :varlevelsup 0 :location -1} {PARAM :paramkind 2 :paramid 2 :location -1}" +
        ") :location -1}) :location -1}"
      val text = "{SUBLINK :subLinkType 2 :subLinkId 0 :testexpr $rowComparisonTestexpr :operName (\"=\") " +
        ":subselect {QUERY :targetList (" +
        "{TARGETENTRY :expr {VAR :varno 1 :varattno 1 :varlevelsup 0 :location -1} :resno 1 :resname p " +
        ":resjunk false}) :setOperations <>} :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.SubLink
      assertThat(result.testExpressionOperatorOid).isNull()
    }

    @Test
    fun `an ANY sublink with no subselect yields null, not a crash`() {
      val text = "{SUBLINK :subLinkType 2 :subLinkId 0 :testexpr $anyOpexprTestexpr :operName (\"=\") :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.SubLink
      assertThat(result.subselectBlock).isNull()
    }

    @Test
    fun `a ROWCOMPARE sublink's testexpr yields no outer operand and no operator OID`() {
      // A ROWCOMPAREEXPR testexpr carries its operands under :largs/:rargs, not :args, and is not a
      // node type this parser's `when` dispatch recognizes, so it parses to Unknown. Both fields stay
      // null even though parseSubLink now extracts :testexpr unconditionally. Fixture is verbatim from
      // a live PostgreSQL 18.4 ev_action dump, including the "o" OID-list type tags and the absence of
      // any :location field on ROWCOMPAREEXPR itself.
      val rowCompareTestexpr = "{ROWCOMPAREEXPR :cmptype 1 :opnos (o 97 97) :opfamilies (o 1976 1976) " +
        ":inputcollids (o 0 0) :largs (" +
        "{VAR :varno 1 :varattno 1 :varlevelsup 0 :location -1} " +
        "{VAR :varno 1 :varattno 2 :varlevelsup 0 :location -1}) " +
        ":rargs ({PARAM :paramkind 2 :paramid 1 :location -1} {PARAM :paramkind 2 :paramid 2 :location -1})}"
      val text = "{SUBLINK :subLinkType 3 :subLinkId 0 :testexpr $rowCompareTestexpr :operName <> " +
        ":subselect {QUERY :targetList (" +
        "{TARGETENTRY :expr {VAR :varno 1 :varattno 1 :varlevelsup 0 :location -1} :resno 1 :resname v " +
        ":resjunk false}) :setOperations <>} :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.SubLink
      assertThat(result.outerOperand).isNull()
      assertThat(result.testExpressionOperatorOid).isNull()
    }
  }

  @Nested
  inner class DepthAwareFieldExtraction {

    // extractFieldExpression must find a field at brace depth 1 of the node it is given, not the
    // first textual occurrence of that field name anywhere in the text — several node types have
    // an earlier-serialized field whose OWN value can legally contain ANOTHER node of the same
    // type, carrying the same field name, nested deeper. A first-match indexOf scan finds the
    // NESTED (wrong) occurrence whenever the earlier field's value textually precedes the node's
    // OWN later field of that name, silently proving the wrong subtree.

    @Test
    fun `a SUBLINK's testexpr containing a nested SUBLINK does not shadow the outer subselect`() {
      // Live-verified repro (PostgreSQL 17 and 18): `SELECT EXISTS (SELECT v FROM u) = ANY
      // (SELECT b FROM x) FROM t` — the outer ANY_SUBLINK's :testexpr (an OPEXPR whose first
      // argument is the nested EXISTS sublink) precedes its own :subselect in SUBLINK's field
      // order, and the nested EXISTS sublink has its OWN :subselect (the `u` query) textually
      // inside that :testexpr, before the outer sublink's real :subselect (the `x` query) ever
      // appears. A naive first-match scan for ":subselect {" returns the INNER (u) block.
      val innerSubselect = "{QUERY :targetList (" +
        "{TARGETENTRY :expr {VAR :varno 900 :varattno 1 :varlevelsup 0 :location -1} :resno 1 " +
        ":resname inner_col :resjunk false}) :setOperations <>}"
      val innerSublink = "{SUBLINK :subLinkType 0 :subLinkId 1 :testexpr <> :operName <> " +
        ":subselect $innerSubselect :location -1}"
      val outerSubselect = "{QUERY :targetList (" +
        "{TARGETENTRY :expr {VAR :varno 901 :varattno 1 :varlevelsup 0 :location -1} :resno 1 " +
        ":resname outer_col :resjunk false}) :setOperations <>}"
      val outerTestexpr = "{OPEXPR :opno 91 :opfuncid 60 :args ($innerSublink " +
        "{PARAM :paramkind 2 :paramid 1 :location -1}) :location -1}"
      val outerText = "{SUBLINK :subLinkType 2 :subLinkId 0 :testexpr $outerTestexpr :operName (\"=\") " +
        ":subselect $outerSubselect :location -1}"
      val result = parser.parseExpression(outerText) as PgNodeExpression.SubLink
      assertThat(result.subselectBlock).isEqualTo(outerSubselect)
    }

    @Test
    fun `a CASEWHEN's own result is not shadowed by a nested CASEEXPR inside its condition`() {
      val innerCaseWhen = "{CASEWHEN :expr {CONST :constisnull false} " +
        ":result {VAR :varno 100 :varattno 1 :varlevelsup 0 :location -1} :location -1}"
      val innerCaseExpr = "{CASEEXPR :casetype 23 :arg <> :args ($innerCaseWhen) " +
        ":defresult {VAR :varno 101 :varattno 1 :varlevelsup 0 :location -1} :location -1}"
      val outerCaseWhen = "{CASEWHEN :expr $innerCaseExpr " +
        ":result {VAR :varno 200 :varattno 1 :varlevelsup 0 :location -1} :location -1}"
      val outerText = "{CASEEXPR :casetype 23 :arg <> :args ($outerCaseWhen) " +
        ":defresult {VAR :varno 201 :varattno 1 :varlevelsup 0 :location -1} :location -1}"
      val result = parser.parseExpression(outerText) as PgNodeExpression.CaseExpr
      val resultVar = result.resultExpressions.single() as PgNodeExpression.Var
      assertThat(resultVar.varno).isEqualTo(200)
      val defaultVar = result.defaultResult as PgNodeExpression.Var
      assertThat(defaultVar.varno).isEqualTo(201)
    }

    @Test
    fun `a JSONEXPR's own on_empty and on_error are not shadowed by a nested JSONEXPR argument`() {
      val innerOnEmpty = "{JSONBEHAVIOR :btype 8 :expr {VAR :varno 900 :varattno 1 :varlevelsup 0 " +
        ":location -1} :location -1}"
      val innerOnError = "{JSONBEHAVIOR :btype 8 :expr {VAR :varno 901 :varattno 1 :varlevelsup 0 " +
        ":location -1} :location -1}"
      val innerJsonExpr = "{JSONEXPR :op 2 :formatted_expr " +
        "{VAR :varno 800 :varattno 1 :varlevelsup 0 :location -1} :on_empty $innerOnEmpty " +
        ":on_error $innerOnError :location -1}"
      val outerOnEmpty = "{JSONBEHAVIOR :btype 8 :expr {VAR :varno 902 :varattno 1 :varlevelsup 0 " +
        ":location -1} :location -1}"
      val outerOnError = "{JSONBEHAVIOR :btype 8 :expr {VAR :varno 903 :varattno 1 :varlevelsup 0 " +
        ":location -1} :location -1}"
      val outerText = "{JSONEXPR :op 2 :formatted_expr $innerJsonExpr :on_empty $outerOnEmpty " +
        ":on_error $outerOnError :location -1}"
      val result = parser.parseExpression(outerText) as PgNodeExpression.JsonExpr
      val onEmptyVar = result.onEmptyDefault as PgNodeExpression.Var
      assertThat(onEmptyVar.varno).isEqualTo(902)
      val onErrorVar = result.onErrorDefault as PgNodeExpression.Var
      assertThat(onErrorVar.varno).isEqualTo(903)
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

  @Nested
  inner class CteRangeTableEntryParsing {

    @Test
    fun `an explicit ctelevelsup greater than 0 is captured, distinguishing an enclosing CTE from a local one`() {
      // ColumnNullabilityAnalyzer.analyzeQueryBlockNullability depends on the exact value, not just
      // the field's presence, to tell a local `WITH` apart from a shadowed enclosing one.
      val text = """
        {QUERY :rtable (
          {RANGETBLENTRY :eref {ALIAS :aliasname c :colnames ("v")} :rtekind 6 :ctename c
           :ctelevelsup 1 :self_reference false}
        )}
      """.trimIndent()
      val result = parser.parseCteRangeTableEntries(text)
      assertThat(result).hasSize(1)
      val reference = result.getValue(1)
      assertThat(reference.name).isEqualTo("c")
      assertThat(reference.ctelevelsup).isEqualTo(1)
    }

    @Test
    fun `a CTE range table entry missing ctelevelsup entirely defaults to 0, a local (same-level) reference`() {
      // Malformed or future-format input. The safe default is `0`, the "declares its own WITH" case
      // resolved from the block's own CTE list rather than an enclosing one.
      val text = """
        {QUERY :rtable (
          {RANGETBLENTRY :eref {ALIAS :aliasname c :colnames ("v")} :rtekind 6 :ctename c
           :self_reference false}
        )}
      """.trimIndent()
      val result = parser.parseCteRangeTableEntries(text)
      assertThat(result).hasSize(1)
      val reference = result.getValue(1)
      assertThat(reference.name).isEqualTo("c")
      assertThat(reference.ctelevelsup).isEqualTo(0)
    }
  }

  @Nested
  inner class JsonConstructorExprParsing {

    @Test
    fun `type and args are read from a JSON_OBJECT-shaped node, func stays null`() {
      val text = "{JSONCONSTRUCTOREXPR :type 1 :args (" +
        "{CONST :consttype 705 :constisnull false :location -1} " +
        "{CONST :consttype 23 :constisnull false :location -1}) :func <> :coercion <> " +
        ":returning {JSONRETURNING :format {JSONFORMAT :format_type 1 :encoding 0 :location -1} " +
        ":typid 114 :typmod -1} :absent_on_null false :unique false :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.JsonConstructorExpr
      assertThat(result.type).isEqualTo(PgNodeExpression.JSON_CONSTRUCTOR_TYPE_OBJECT)
      assertThat(result.arguments).hasSize(2)
      assertThat(result.function).isNull()
    }

    @Test
    fun `args is empty and func holds the underlying AGGREF for a JSON_OBJECTAGG-shaped node`() {
      // :args is `<>` (empty) and the real aggregate lives under :func instead — see
      // PgNodeExpression.JsonConstructorExpr.arguments.
      val text = "{JSONCONSTRUCTOREXPR :type 3 :args <> :func " +
        "{AGGREF :aggfnoid 3197 :aggtype 114 :aggcollid 0 :inputcollid 100 :aggtranstype 0 " +
        ":aggargtypes (o 25 23) :aggdirectargs <> :args (" +
        "{TARGETENTRY :expr {CONST :consttype 25 :constisnull false :location -1} :resno 1 " +
        ":resname <> :ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false}) " +
        ":aggorder <> :aggdistinct <> :aggfilter <> :aggstar false :aggvariadic false :aggkind n " +
        ":aggpresorted false :agglevelsup 0 :aggsplit 0 :aggno -1 :aggtransno -1 :location -1} " +
        ":coercion <> :returning {JSONRETURNING :format {JSONFORMAT :format_type 1 :encoding 0 " +
        ":location -1} :typid 114 :typmod -1} :absent_on_null false :unique false :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.JsonConstructorExpr
      assertThat(result.type).isEqualTo(PgNodeExpression.JSON_CONSTRUCTOR_TYPE_OBJECTAGG)
      assertThat(result.arguments).hasSize(0)
      val function = result.function as PgNodeExpression.Aggref
      assertThat(function.aggregateFunctionOid).isEqualTo(3197)
      assertThat(function.arguments).hasSize(1)
    }

    @Test
    fun `a JSONVALUEEXPR argument transparently unwraps to its formatted_expr, not Unknown`() {
      // JSON()'s single argument is always wrapped in a JSONVALUEEXPR, so without unwrapping it would
      // parse to Unknown and always evaluate nullable regardless of the column's NOT NULL constraint.
      val text = "{JSONCONSTRUCTOREXPR :type 5 :args (" +
        "{JSONVALUEEXPR :raw_expr {VAR :varno 1 :varattno 1 :varlevelsup 0 :location -1} " +
        ":formatted_expr {COERCEVIAIO :arg {VAR :varno 1 :varattno 1 :varlevelsup 0 :location -1} " +
        ":resulttype 114 :resultcollid 0 :coerceformat 1 :location -1} " +
        ":format {JSONFORMAT :format_type 0 :encoding 0 :location -1}}) :func <> :coercion <> " +
        ":returning {JSONRETURNING :format {JSONFORMAT :format_type 1 :encoding 0 :location -1} " +
        ":typid 114 :typmod -1} :absent_on_null false :unique false :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.JsonConstructorExpr
      assertThat(result.type).isEqualTo(PgNodeExpression.JSON_CONSTRUCTOR_TYPE_PARSE)
      assertThat(result.arguments).hasSize(1)
      val coercion = result.arguments.single() as PgNodeExpression.CoerceViaIo
      val innerVar = coercion.argument as PgNodeExpression.Var
      assertThat(innerVar.varno).isEqualTo(1)
      assertThat(innerVar.varattno).isEqualTo(1)
    }

    @Test
    fun `a JSONVALUEEXPR missing formatted_expr degrades to Unknown rather than throwing`() {
      val text = "{JSONVALUEEXPR :raw_expr {CONST :consttype 25 :constisnull false :location -1} " +
        ":format {JSONFORMAT :format_type 0 :encoding 0 :location -1}}"
      val result = parser.parseExpression(text) as PgNodeExpression.Unknown
      assertThat(result.nodeType).isEqualTo("PARSE_ERROR")
    }
  }

  @Nested
  inner class AggrefArgumentExtraction {

    // Both fixtures are VERBATIM `{AGGREF ...}` blocks from a live PostgreSQL 18.4 ev_action dump.
    // extractArgListSection's prior non-depth-aware `indexOf(":args (")` mis-parsed both, attributing
    // a nested node's argument list to the aggregate. No isNonNull answer moved (that branch never
    // reads Aggref.arguments), but the arguments also feed containsVarOutsideRelation and
    // GroupRteSubstitution, where a Var the wrong list hides becomes a silent wrong-NOT-NULL.

    @Test
    fun `an AGGREF with a FILTER clause does not attribute the filter's own OPEXPR args to the aggregate`() {
      // The aggregate's own `:args` is `<>` and precedes `:aggfilter`, whose separate `{OPEXPR ...}`
      // carries its own nested `:args (...)` — the list a plain scan matches instead.
      val text = "{AGGREF :aggfnoid 2803 :aggtype 20 :aggcollid 0 :inputcollid 0 :aggtranstype 0 " +
        ":aggargtypes <> :aggdirectargs <> :args <> :aggorder <> :aggdistinct <> :aggfilter " +
        "{OPEXPR :opno 96 :opfuncid 65 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 " +
        ":args ({VAR :varno 1 :varattno 2 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) " +
        ":varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} " +
        "{CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true " +
        ":constisnull false :location -1 :constvalue 4 [ 1 0 0 0 0 0 0 0 ]}) :location -1} " +
        ":aggstar true :aggvariadic false :aggkind n :aggpresorted false :agglevelsup 0 :aggsplit 0 " +
        ":aggno -1 :aggtransno -1 :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.Aggref
      assertThat(result.aggregateFunctionOid).isEqualTo(2803)
      assertThat(result.arguments).hasSize(0)
    }

    @Test
    fun `an AGGREF with WITHIN GROUP does not attribute aggdirectargs' nested args to the aggregate`() {
      // `:aggdirectargs` (the `0.5`, wrapped in a numeric-cast FUNCEXPR with its own nested `:args`)
      // is serialized BEFORE the aggregate's real `:args` — so a plain scan returns just the literal.
      val text = "{AGGREF :aggfnoid 3974 :aggtype 701 :aggcollid 0 :inputcollid 0 :aggtranstype 0 " +
        ":aggargtypes (o 701 701) :aggdirectargs ({FUNCEXPR :funcid 1746 :funcresulttype 701 " +
        ":funcretset false :funcvariadic false :funcformat 2 :funccollid 0 :inputcollid 0 " +
        ":args ({CONST :consttype 1700 :consttypmod -1 :constcollid 0 :constlen -1 :constbyval false " +
        ":constisnull false :location -1 :constvalue 8 [ 32 0 0 0 255 128 136 19 ]}) :location -1}) " +
        ":args ({TARGETENTRY :expr {FUNCEXPR :funcid 1746 :funcresulttype 701 :funcretset false " +
        ":funcvariadic false :funcformat 2 :funccollid 0 :inputcollid 0 :args ({VAR :varno 1 " +
        ":varattno 1 :vartype 1700 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 " +
        ":varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1}) :location -1} :resno 1 " +
        ":resname <> :ressortgroupref 1 :resorigtbl 0 :resorigcol 0 :resjunk false}) " +
        ":aggorder ({SORTGROUPCLAUSE :tleSortGroupRef 1 :eqop 670 :sortop 672 :reverse_sort false " +
        ":nulls_first false :hashable true}) :aggdistinct <> :aggfilter <> :aggstar false " +
        ":aggvariadic false :aggkind o :aggpresorted false :agglevelsup 0 :aggsplit 0 :aggno -1 " +
        ":aggtransno -1 :location -1}"
      val result = parser.parseExpression(text) as PgNodeExpression.Aggref
      assertThat(result.aggregateFunctionOid).isEqualTo(3974)
      assertThat(result.arguments).hasSize(1)
      val argumentFunction = result.arguments.single() as PgNodeExpression.FuncExpr
      assertThat(argumentFunction.functionOid).isEqualTo(1746)
      val innerVar = argumentFunction.arguments.single() as PgNodeExpression.Var
      assertThat(innerVar.varno).isEqualTo(1)
      assertThat(innerVar.varattno).isEqualTo(1)
    }
  }
}
