@file:Suppress("ktlint:standard:max-line-length")

package norm.generator

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PgNodeTreeParserTest {

  private val parser = PgNodeTreeParser()

  @Nested
  inner class ParseExpression {

    @Test
    fun `VAR node with empty nullingrels`() {
      val text = """{VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isEqualTo(PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()))
    }

    @Test
    fun `VAR node with nulling relations`() {
      val text = """{VAR :varno 2 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 1 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isEqualTo(PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = setOf(3)))
    }

    @Test
    fun `CONST non-null`() {
      val text = """{CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 :constbyval false :constisnull false :location -1 :constvalue 4 [ 0 0 0 0 ]}"""
      val result = parser.parseExpression(text)
      assertThat(result).isEqualTo(PgNodeExpression.Const(isNull = false))
    }

    @Test
    fun `CONST null`() {
      val text = """{CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 :constbyval false :constisnull true :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isEqualTo(PgNodeExpression.Const(isNull = true))
    }

    @Test
    fun `unknown node type`() {
      val text = """{WEIRDNODE :field 42}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.Unknown::class)
      assertThat((result as PgNodeExpression.Unknown).nodeType).isEqualTo("WEIRDNODE")
    }

    @Test
    fun `FUNCEXPR with arguments`() {
      // upper(col) — funcid 871, one VAR argument
      val text = """{FUNCEXPR :funcid 871 :funcresulttype 25 :funcretset false :funcvariadic false :funcformat 0 :funccollid 100 :inputcollid 100 :args ({VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1}) :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isEqualTo(
        PgNodeExpression.FuncExpr(
          functionOid = 871,
          arguments = listOf(PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet())),
        ),
      )
    }

    @Test
    fun `AGGREF — COUNT star with empty args`() {
      val text = """{AGGREF :aggfnoid 2803 :aggtype 20 :aggcollid 0 :inputcollid 0 :aggtranstype 0 :aggargtypes <> :aggdirectargs <> :args <> :aggorder <> :aggdistinct <> :aggfilter <> :aggstar true :aggvariadic false :aggkind n :aggpresorted false :agglevelsup 0 :aggsplit 0 :aggno -1 :aggtransno -1 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isEqualTo(
        PgNodeExpression.Aggref(aggregateFunctionOid = 2803, arguments = emptyList()),
      )
    }

    @Test
    fun `AGGREF — COUNT col with TARGETENTRY-wrapped arg`() {
      // COUNT(id) — aggfnoid 2147, arg is a VAR wrapped in TARGETENTRY
      val text = """{AGGREF :aggfnoid 2147 :aggtype 20 :aggcollid 0 :inputcollid 0 :aggtranstype 0 :aggargtypes (o 23) :aggdirectargs <> :args ({TARGETENTRY :expr {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :resno 1 :resname <> :ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false}) :aggorder <> :aggdistinct <> :aggfilter <> :aggstar false :aggvariadic false :aggkind n :aggpresorted false :agglevelsup 0 :aggsplit 0 :aggno -1 :aggtransno -1 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.Aggref::class)
      val aggref = result as PgNodeExpression.Aggref
      assertThat(aggref.aggregateFunctionOid).isEqualTo(2147)
      assertThat(aggref.arguments).hasSize(1)
      assertThat(aggref.arguments[0]).isInstanceOf(PgNodeExpression.Var::class)
    }

    @Test
    fun `COALESCEEXPR with two arguments`() {
      val text = """{COALESCEEXPR :coalescetype 25 :coalescecollid 100 :args ({VAR :varno 1 :varattno 4 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 4 :location -1} {CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 :constbyval false :constisnull false :location -1 :constvalue 8 [ 32 0 0 0 97 110 111 110 ]}) :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.CoalesceExpr::class)
      val coalesce = result as PgNodeExpression.CoalesceExpr
      assertThat(coalesce.arguments).hasSize(2)
      assertThat(coalesce.arguments[0]).isInstanceOf(PgNodeExpression.Var::class)
      assertThat(coalesce.arguments[1]).isInstanceOf(PgNodeExpression.Const::class)
    }

    @Test
    fun `OPEXPR — addition operator`() {
      val text = """{OPEXPR :opno 551 :opfuncid 177 :opresulttype 23 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 1 0 0 0 ]}) :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.OpExpr::class)
      val op = result as PgNodeExpression.OpExpr
      assertThat(op.operatorFunctionOid).isEqualTo(177)
      assertThat(op.arguments).hasSize(2)
    }

    @Test
    fun `NULLIFEXPR with two arguments`() {
      val text = """{NULLIFEXPR :opno 98 :opfuncid 67 :opresulttype 25 :opretset false :opcollid 100 :inputcollid 100 :args ({VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} {CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 :constbyval false :constisnull false :location -1 :constvalue 8 [ 32 0 0 0 116 101 115 116 ]}) :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.NullIfExpr::class)
      assertThat((result as PgNodeExpression.NullIfExpr).arguments).hasSize(2)
    }

    @Test
    fun `SCALARARRAYOPEXPR with array literal argument`() {
      // id = ANY(ARRAY[1,2,3])
      val text = """{SCALARARRAYOPEXPR :opno 96 :opfuncid 65 :useOr true :inputcollid 0 :args ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {ARRAYEXPR :array_typeid 1007 :array_collid 0 :element_typeid 23 :elements ({CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 1 0 0 0 ]}) :multidims false :location -1}) :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.ScalarArrayOpExpr::class)
      val saoe = result as PgNodeExpression.ScalarArrayOpExpr
      assertThat(saoe.operatorFunctionOid).isEqualTo(65)
      assertThat(saoe.arguments).hasSize(2)
    }

    @Test
    fun `WINDOWFUNC with arguments`() {
      // SUM(id) OVER() — winfnoid for window version of sum
      val text = """{WINDOWFUNC :winfnoid 3111 :wintype 20 :wincollid 0 :inputcollid 0 :args ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1}) :aggfilter <> :runCondition <> :winref 1 :winstar false :winagg true :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.WindowFunc::class)
      val wf = result as PgNodeExpression.WindowFunc
      assertThat(wf.windowFunctionOid).isEqualTo(3111)
      assertThat(wf.arguments).hasSize(1)
    }

    @Test
    fun `WINDOWFUNC with no arguments — ranking function`() {
      // ROW_NUMBER() OVER() — winfnoid 3100
      val text = """{WINDOWFUNC :winfnoid 3100 :wintype 20 :wincollid 0 :inputcollid 0 :args <> :aggfilter <> :runCondition <> :winref 1 :winstar false :winagg false :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.WindowFunc::class)
      assertThat((result as PgNodeExpression.WindowFunc).arguments).isEmpty()
    }

    @Test
    fun `MINMAXEXPR with arguments`() {
      // GREATEST(a, b)
      val text = """{MINMAXEXPR :minmaxtype 23 :minmaxcollid 0 :inputcollid 0 :op 1 :args ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 0 0 0 0 ]}) :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.MinMaxExpr::class)
      assertThat((result as PgNodeExpression.MinMaxExpr).arguments).hasSize(2)
    }

    @Test
    fun `SUBLINK EXISTS`() {
      val text = """{SUBLINK :subLinkType 0 :subLinkId 0 :testexpr <> :operName <> :subselect {QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable <> :rteperminfos <> :jointree {FROMEXPR :fromlist <> :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 1 0 0 0 ]} :resno 1 :resname <> :ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1} :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.SubLink::class)
      assertThat((result as PgNodeExpression.SubLink).subLinkType).isEqualTo(PgNodeExpression.SUBLINK_TYPE_EXISTS)
    }

    @Test
    fun `CASEEXPR with ELSE`() {
      // CASE WHEN id > 0 THEN 'yes' ELSE 'no' END
      val text = """{CASEEXPR :casetype 25 :casecollid 100 :arg <> :args ({CASEWHEN :expr {OPEXPR :opno 521 :opfuncid 147 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 0 0 0 0 ]}) :location -1} :result {CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 :constbyval false :constisnull false :location -1 :constvalue 8 [ 32 0 0 0 121 101 115 0 ]} :location -1}) :defresult {CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 :constbyval false :constisnull false :location -1 :constvalue 7 [ 28 0 0 0 110 111 0 ]} :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.CaseExpr::class)
      val caseExpr = result as PgNodeExpression.CaseExpr
      assertThat(caseExpr.resultExpressions).hasSize(1)
      assertThat(caseExpr.resultExpressions[0]).isInstanceOf(PgNodeExpression.Const::class)
      assertThat(caseExpr.defaultResult).isNotNull().isInstanceOf(PgNodeExpression.Const::class)
    }

    @Test
    fun `CASEEXPR without ELSE — defaultResult is null`() {
      // CASE WHEN id > 0 THEN 'yes' END
      val text = """{CASEEXPR :casetype 25 :casecollid 100 :arg <> :args ({CASEWHEN :expr {OPEXPR :opno 521 :opfuncid 147 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 0 0 0 0 ]}) :location -1} :result {CONST :consttype 25 :consttypmod -1 :constcollid 100 :constlen -1 :constbyval false :constisnull false :location -1 :constvalue 8 [ 32 0 0 0 121 101 115 0 ]} :location -1}) :defresult <> :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.CaseExpr::class)
      val caseExpr = result as PgNodeExpression.CaseExpr
      assertThat(caseExpr.resultExpressions).hasSize(1)
      assertThat(caseExpr.defaultResult).isNull()
    }

    @Test
    fun `BOOLEXPR AND`() {
      val text = """{BOOLEXPR :boolop and :args ({OPEXPR :opno 521 :opfuncid 147 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 0 0 0 0 ]}) :location -1} {OPEXPR :opno 521 :opfuncid 147 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 1 :varattno 2 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} {CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 0 0 0 0 ]}) :location -1}) :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.BoolExpr::class)
      assertThat((result as PgNodeExpression.BoolExpr).arguments).hasSize(2)
    }

    @Test
    fun `RELABELTYPE wraps inner expression`() {
      val text = """{RELABELTYPE :arg {VAR :varno 1 :varattno 1 :vartype 20 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :resulttype 20 :resulttypmod -1 :resultcollid 0 :relabelformat 2 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.RelabelType::class)
      assertThat((result as PgNodeExpression.RelabelType).argument).isInstanceOf(PgNodeExpression.Var::class)
    }

    @Test
    fun `SQLVALUEFUNCTION returns operation code`() {
      val text = """{SQLVALUEFUNCTION :op 0 :type 1082 :typmod -1 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.SqlValueFunction::class)
      assertThat((result as PgNodeExpression.SqlValueFunction).operation).isEqualTo(0)
    }

    @Test
    fun `NULLTEST wraps inner expression`() {
      val text = """{NULLTEST :arg {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :nulltesttype 1 :argisrow false :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.NullTest::class)
      assertThat((result as PgNodeExpression.NullTest).argument).isInstanceOf(PgNodeExpression.Var::class)
    }

    @Test
    fun `BOOLEANTEST wraps inner expression`() {
      val text = """{BOOLEANTEST :arg {VAR :varno 1 :varattno 1 :vartype 16 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :booltesttype 0 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.BooleanTest::class)
      assertThat((result as PgNodeExpression.BooleanTest).argument).isInstanceOf(PgNodeExpression.Var::class)
    }

    @Test
    fun `FIELDSELECT extracts field number and argument`() {
      val text = """{FIELDSELECT :arg {VAR :varno 1 :varattno 1 :vartype 2249 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :fieldnum 2 :resulttype 25 :resulttypmod -1 :resultcollid 100}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.FieldSelect::class)
      val fs = result as PgNodeExpression.FieldSelect
      assertThat(fs.fieldNumber).isEqualTo(2)
      assertThat(fs.argument).isInstanceOf(PgNodeExpression.Var::class)
    }

    @Test
    fun `JSONEXPR JSON_VALUE with default NULL on empty and error behaviors`() {
      // JSON_VALUE(json_col, '$.name') — op=0 (JSON_VALUE_OP), both on_empty_behavior and on_error_behavior present with btype=0 (JSON_BEHAVIOR_NULL)
      val text = """{JSONEXPR :op 0 :raw_expr <> :formatted_expr {VAR :varno 1 :varattno 2 :vartype 3802 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :path_spec <> :returning <> :passing_values <> :passing_names <> :on_empty_behavior {JSONBEHAVIOR :btype 0 :default_expr <> :location -1} :on_error_behavior {JSONBEHAVIOR :btype 0 :default_expr <> :location -1} :use_io_coercion false :use_error_to_null false :omit_quotes false :collation 0 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.JsonExpr::class)
      val jsonExpr = result as PgNodeExpression.JsonExpr
      assertThat(jsonExpr.op).isEqualTo(PgNodeExpression.JSON_VALUE_OP)
      assertThat(jsonExpr.argument).isInstanceOf(PgNodeExpression.Var::class)
      assertThat(jsonExpr.onEmpty).isEqualTo(PgNodeExpression.JSON_BEHAVIOR_NULL)
      assertThat(jsonExpr.onError).isEqualTo(PgNodeExpression.JSON_BEHAVIOR_NULL)
      assertThat(jsonExpr.onEmptyDefault).isNull()
      assertThat(jsonExpr.onErrorDefault).isNull()
    }

    @Test
    fun `JSONEXPR with no behavior blocks uses default JSON_BEHAVIOR_NULL`() {
      // When :on_empty_behavior and :on_error_behavior are absent, extractJsonBehaviorType returns JSON_BEHAVIOR_NULL
      val text = """{JSONEXPR :op 0 :raw_expr <> :formatted_expr {VAR :varno 1 :varattno 1 :vartype 3802 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :path_spec <> :returning <> :passing_values <> :passing_names <> :use_io_coercion false :use_error_to_null false :omit_quotes false :collation 0 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.JsonExpr::class)
      val jsonExpr = result as PgNodeExpression.JsonExpr
      assertThat(jsonExpr.onEmpty).isEqualTo(PgNodeExpression.JSON_BEHAVIOR_NULL)
      assertThat(jsonExpr.onError).isEqualTo(PgNodeExpression.JSON_BEHAVIOR_NULL)
    }

    @Test
    fun `XMLEXPR extracts op and combines named and regular args`() {
      val text = """{XMLEXPR :op 1 :name foo :named_args ({VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1}) :arg_names <> :args <> :xmloption 0 :indent false :type 142 :typmod -1 :location -1}"""
      val result = parser.parseExpression(text)
      assertThat(result).isInstanceOf(PgNodeExpression.XmlExpr::class)
      val xml = result as PgNodeExpression.XmlExpr
      assertThat(xml.op).isEqualTo(1) // XML_IS_XMLELEMENT
      assertThat(xml.arguments).hasSize(1)
      assertThat(xml.arguments[0]).isInstanceOf(PgNodeExpression.Var::class)
    }
  }

  @Nested
  inner class ParseTargetList {

    @Test
    fun `extracts target entries from full node tree`() {
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname t :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 9) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({RANGETBLREF :rtindex 1}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :resno 1 :resname id :ressortgroupref 0 :resorigtbl 16461 :resorigcol 1 :resjunk false} {TARGETENTRY :expr {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :resno 2 :resname name :ressortgroupref 0 :resorigtbl 16461 :resorigcol 2 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val entries = parser.parseTargetList(nodeTree)
      assertThat(entries).hasSize(2)
      assertThat(entries[0].resultNumber).isEqualTo(1)
      assertThat(entries[0].resultName).isEqualTo("id")
      assertThat(entries[0].isJunk).isFalse()
      assertThat(entries[0].expression).isInstanceOf(PgNodeExpression.Var::class)
      assertThat(entries[1].resultNumber).isEqualTo(2)
      assertThat(entries[1].resultName).isEqualTo("name")
    }

    @Test
    fun `includes junk entries — callers must filter`() {
      // One non-junk (resno 1) and one junk (resjunk true) entry
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname t :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 9) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({RANGETBLREF :rtindex 1}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :resno 1 :resname id :ressortgroupref 0 :resorigtbl 16461 :resorigcol 1 :resjunk false} {TARGETENTRY :expr {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :resno 2 :resname name :ressortgroupref 1 :resorigtbl 16461 :resorigcol 2 :resjunk true}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val entries = parser.parseTargetList(nodeTree)
      assertThat(entries).hasSize(2) // both entries returned, including junk
      assertThat(entries.filter { !it.isJunk }).hasSize(1)
      assertThat(entries.filter { !it.isJunk }[0].resultNumber).isEqualTo(1)
    }

    @Test
    fun `returns empty list for malformed input`() {
      val result = parser.parseTargetList("not valid")
      assertThat(result).isEmpty()
    }
  }

  @Nested
  inner class ParseRangeTable {

    private fun wrapInQuery(rtable: String) =
      """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable $rtable :rteperminfos <> :jointree {FROMEXPR :fromlist <> :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList <> :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""

    @Test
    fun `returns varno to relid for base table entries`() {
      val nodeTree =
        wrapInQuery(
          "({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname t :colnames ()} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>})",
        )
      val result = parser.parseRangeTable(nodeTree)
      assertThat(result).isEqualTo(mapOf(1 to 16461))
    }

    @Test
    fun `skips non-base entries and preserves correct varno for subsequent base entries`() {
      val nodeTree = wrapInQuery(
        "({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname t1 :colnames ()} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>} " +
          "{RANGETBLENTRY :alias <> :eref {ALIAS :aliasname sub :colnames ()} :rtekind 1 :relid 0 :lateral false :inFromCl true :securityQuals <>} " +
          "{RANGETBLENTRY :alias <> :eref {ALIAS :aliasname t2 :colnames ()} :rtekind 0 :relid 16487 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>})",
      )
      val result = parser.parseRangeTable(nodeTree)
      assertThat(result[1]).isEqualTo(16461) // first entry
      assertThat(result[2]).isNull() // second entry (rtekind 1, not base table)
      assertThat(result[3]).isEqualTo(16487) // third entry — varno 3, not varno 2
    }

    @Test
    fun `returns empty map when all entries are non-base`() {
      val nodeTree =
        wrapInQuery(
          "({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname j :colnames ()} :rtekind 3 :lateral false :inFromCl false :securityQuals <>})",
        )
      val result = parser.parseRangeTable(nodeTree)
      assertThat(result).isEmpty()
    }

    @Test
    fun `returns empty map for malformed input`() {
      assertThat(parser.parseRangeTable("not valid")).isEmpty()
    }
  }

  @Nested
  inner class ParseGroupRteMap {

    private fun wrapInQuery(rtable: String) =
      """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs true :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE true :isReturn false :cteList <> :rtable $rtable :rteperminfos <> :jointree {FROMEXPR :fromlist <> :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList <> :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""

    @Test
    fun `maps group RTE attributes to base table columns`() {
      // GROUP BY on a single column: rtekind 9 with one groupexpr pointing to varno=1 attno=2
      val nodeTree = wrapInQuery(
        "({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname t :colnames (\"id\" \"name\")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>} " +
          "{RANGETBLENTRY :alias <> :eref {ALIAS :aliasname *GROUP* :colnames (\"name\")} :rtekind 9 :groupexprs ({VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1}) :lateral false :inFromCl false :securityQuals <>})",
      )
      val result = parser.parseGroupRteMap(nodeTree)
      // varno=2 (the *GROUP* RTE), varattno=1 (first groupexpr) → base varno=1, varattno=2
      assertThat(result).isEqualTo(mapOf((2 to 1) to (1 to 2)))
    }

    @Test
    fun `maps multiple group expressions`() {
      // GROUP BY on two columns
      val nodeTree = wrapInQuery(
        "({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname t :colnames (\"id\" \"name\" \"dept\")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>} " +
          "{RANGETBLENTRY :alias <> :eref {ALIAS :aliasname *GROUP* :colnames (\"name\" \"dept\")} :rtekind 9 :groupexprs ({VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} {VAR :varno 1 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 3 :location -1}) :lateral false :inFromCl false :securityQuals <>})",
      )
      val result = parser.parseGroupRteMap(nodeTree)
      assertThat(result).isEqualTo(
        mapOf(
          (2 to 1) to (1 to 2), // first groupexpr: name
          (2 to 2) to (1 to 3), // second groupexpr: dept
        ),
      )
    }

    @Test
    fun `returns empty map when no GROUP RTEs present`() {
      val nodeTree = wrapInQuery(
        "({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname t :colnames ()} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>})",
      )
      assertThat(parser.parseGroupRteMap(nodeTree)).isEmpty()
    }

    @Test
    fun `returns empty map for malformed input`() {
      assertThat(parser.parseGroupRteMap("not valid")).isEmpty()
    }
  }

  @Nested
  inner class HasGroupingSets {

    @Test
    fun `returns true when groupingSets has content`() {
      // GROUPING SETS produces a non-empty :groupingSets field with GROUPINGSET nodes.
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs true :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE true :isReturn false :cteList <> :rtable <> :rteperminfos <> :jointree {FROMEXPR :fromlist <> :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList <> :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets ({GROUPINGSET :kind 0 :content (i 1) :location -1} {GROUPINGSET :kind 0 :content (i 2) :location -1}) :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      assertThat(parser.hasGroupingSets(nodeTree)).isTrue()
    }

    @Test
    fun `returns false for plain GROUP BY`() {
      // Plain GROUP BY has :groupingSets <> (empty).
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs true :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE true :isReturn false :cteList <> :rtable <> :rteperminfos <> :jointree {FROMEXPR :fromlist <> :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList <> :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause ({SORTGROUPCLAUSE :tleSortGroupRef 1 :eqop 98 :sortop 664 :reverse_sort false :nulls_first false :hashable true}) :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      assertThat(parser.hasGroupingSets(nodeTree)).isFalse()
    }

    @Test
    fun `returns false for malformed input`() {
      assertThat(parser.hasGroupingSets("not valid")).isFalse()
    }
  }
}
