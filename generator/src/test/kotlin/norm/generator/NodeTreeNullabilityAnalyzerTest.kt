@file:Suppress("ktlint:standard:max-line-length")

package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NodeTreeNullabilityAnalyzerTest {

  @Nested
  inner class ExtractColumnNullability {

    private val analyzer = NodeTreeNullabilityAnalyzer(
      isStrict = { false },
      hasNonNullInitialValue = { true },
      isSourceColumnNotNull = { _, _ -> true },
      isOuterJoinNullable = { it.isNotEmpty() },
    )

    @Test
    fun `LEFT JOIN — preserved side columns not nullable, nullable side columns nullable`() {
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias {ALIAS :aliasname d :colnames <>} :eref {ALIAS :aliasname d :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias {ALIAS :aliasname e :colnames <>} :eref {ALIAS :aliasname e :colnames ("id" "name" "department_id" "nickname")} :rtekind 0 :relid 16472 :inh true :relkind r :rellockmode 1 :perminfoindex 2 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias <> :eref {ALIAS :aliasname unnamed_join :colnames ("id" "name" "id" "name" "department_id" "nickname")} :rtekind 2 :jointype 1 :joinmergedcols 0 :joinaliasvars ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 1 :location -1} {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 2 :varattno 4 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 4 :location -1}) :joinleftcols (i 1 2) :joinrightcols (i 1 2 3 4) :join_using_alias <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 9) :insertedCols (b) :updatedCols (b)} {RTEPERMISSIONINFO :relid 16472 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 9 10 11) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({JOINEXPR :jointype 1 :isNatural false :larg {RANGETBLREF :rtindex 1} :rarg {RANGETBLREF :rtindex 2} :usingClause <> :join_using_alias <> :quals {OPEXPR :opno 96 :opfuncid 65 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1}) :location -1} :alias <> :rtindex 3}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :resno 1 :resname id :ressortgroupref 0 :resorigtbl 16461 :resorigcol 1 :resjunk false} {TARGETENTRY :expr {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :resno 2 :resname name :ressortgroupref 0 :resorigtbl 16461 :resorigcol 2 :resjunk false} {TARGETENTRY :expr {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} :resno 3 :resname employee_name :ressortgroupref 0 :resorigtbl 16472 :resorigcol 2 :resjunk false} {TARGETENTRY :expr {VAR :varno 2 :varattno 4 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 4 :location -1} :resno 4 :resname nickname :ressortgroupref 0 :resorigtbl 16472 :resorigcol 4 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val result = analyzer.extractColumnNullability(nodeTree)
      assertThat(result).containsExactly(false, false, true, true)
    }

    @Test
    fun `INNER JOIN — no columns nullable from joins`() {
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias {ALIAS :aliasname d :colnames <>} :eref {ALIAS :aliasname d :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias {ALIAS :aliasname e :colnames <>} :eref {ALIAS :aliasname e :colnames ("id" "name" "department_id" "nickname")} :rtekind 0 :relid 16472 :inh true :relkind r :rellockmode 1 :perminfoindex 2 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias <> :eref {ALIAS :aliasname unnamed_join :colnames ("id" "name" "id" "name" "department_id" "nickname")} :rtekind 2 :jointype 0 :joinmergedcols 0 :joinaliasvars ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 1 :location -1} {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 2 :varattno 4 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 4 :location -1}) :joinleftcols (i 1 2) :joinrightcols (i 1 2 3 4) :join_using_alias <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 9) :insertedCols (b) :updatedCols (b)} {RTEPERMISSIONINFO :relid 16472 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 9 10) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({JOINEXPR :jointype 0 :isNatural false :larg {RANGETBLREF :rtindex 1} :rarg {RANGETBLREF :rtindex 2} :usingClause <> :join_using_alias <> :quals {OPEXPR :opno 96 :opfuncid 65 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1}) :location -1} :alias <> :rtindex 3}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :resno 1 :resname name :ressortgroupref 0 :resorigtbl 16461 :resorigcol 2 :resjunk false} {TARGETENTRY :expr {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} :resno 2 :resname employee_name :ressortgroupref 0 :resorigtbl 16472 :resorigcol 2 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val result = analyzer.extractColumnNullability(nodeTree)
      assertThat(result).containsExactly(false, false)
    }

    @Test
    fun `single table — no columns nullable from joins`() {
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname department :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 9) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({RANGETBLREF :rtindex 1}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :resno 1 :resname id :ressortgroupref 0 :resorigtbl 16461 :resorigcol 1 :resjunk false} {TARGETENTRY :expr {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :resno 2 :resname name :ressortgroupref 0 :resorigtbl 16461 :resorigcol 2 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val result = analyzer.extractColumnNullability(nodeTree)
      assertThat(result).containsExactly(false, false)
    }

    @Test
    fun `aggregate expression — AGGREF has no varnullingrels, returns false`() {
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs true :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname department :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({RANGETBLREF :rtindex 1}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {AGGREF :aggfnoid 2803 :aggtype 20 :aggcollid 0 :inputcollid 0 :aggtranstype 0 :aggargtypes <> :aggdirectargs <> :args <> :aggorder <> :aggdistinct <> :aggfilter <> :aggstar true :aggvariadic false :aggkind n :aggpresorted false :agglevelsup 0 :aggsplit 0 :aggno -1 :aggtransno -1 :location -1} :resno 1 :resname total :ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val result = analyzer.extractColumnNullability(nodeTree)
      assertThat(result).containsExactly(false)
    }

    @Test
    fun `missing targetList returns empty list`() {
      val result = analyzer.extractColumnNullability("not a valid node tree")
      assertThat(result).isEmpty()
    }

    @Test
    fun `mixed columns and aggregates in LEFT JOIN`() {
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs true :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE true :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias {ALIAS :aliasname d :colnames <>} :eref {ALIAS :aliasname d :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias {ALIAS :aliasname e :colnames <>} :eref {ALIAS :aliasname e :colnames ("id" "name" "department_id" "nickname")} :rtekind 0 :relid 16472 :inh true :relkind r :rellockmode 1 :perminfoindex 2 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias <> :eref {ALIAS :aliasname unnamed_join :colnames ("id" "name" "id" "name" "department_id" "nickname")} :rtekind 2 :jointype 1 :joinmergedcols 0 :joinaliasvars ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 1 :location -1} {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 2 :varattno 4 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 4 :location -1}) :joinleftcols (i 1 2) :joinrightcols (i 1 2 3 4) :join_using_alias <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias <> :eref {ALIAS :aliasname *GROUP* :colnames ("name")} :rtekind 9 :groupexprs ({VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1}) :lateral false :inFromCl false :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 9) :insertedCols (b) :updatedCols (b)} {RTEPERMISSIONINFO :relid 16472 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 10) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({JOINEXPR :jointype 1 :isNatural false :larg {RANGETBLREF :rtindex 1} :rarg {RANGETBLREF :rtindex 2} :usingClause <> :join_using_alias <> :quals {OPEXPR :opno 96 :opfuncid 65 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1}) :location -1} :alias <> :rtindex 3}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {VAR :varno 4 :varattno 1 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 4 :varattnosyn 1 :location -1} :resno 1 :resname name :ressortgroupref 1 :resorigtbl 16461 :resorigcol 2 :resjunk false} {TARGETENTRY :expr {AGGREF :aggfnoid 2147 :aggtype 20 :aggcollid 0 :inputcollid 0 :aggtranstype 0 :aggargtypes (o 23) :aggdirectargs <> :args ({TARGETENTRY :expr {VAR :varno 2 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 1 :location -1} :resno 1 :resname <> :ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false}) :aggorder <> :aggdistinct <> :aggfilter <> :aggstar false :aggvariadic false :aggkind n :aggpresorted false :agglevelsup 0 :aggsplit 0 :aggno -1 :aggtransno -1 :location -1} :resno 2 :resname emp_count :ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause ({SORTGROUPCLAUSE :tleSortGroupRef 1 :eqop 98 :sortop 664 :reverse_sort false :nulls_first false :hashable true}) :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val result = analyzer.extractColumnNullability(nodeTree)
      assertThat(result).containsExactly(false, false)
    }
  }

  @Nested
  inner class IsNonNull {

    private val strictFunctions = mutableSetOf<Int>()
    private val strictButNullableFunctions = mutableSetOf<Int>()
    private val nonNullAggregates = mutableSetOf<Int>()
    private val notNullColumns = mutableSetOf<Pair<Int, Int>>() // (varno, varattno) NOT NULL columns

    private val analyzer = NodeTreeNullabilityAnalyzer(
      isStrict = { oid -> oid in strictFunctions },
      hasNonNullInitialValue = { oid -> oid in nonNullAggregates },
      isSourceColumnNotNull = { varno, varattno -> (varno to varattno) in notNullColumns },
      isOuterJoinNullable = { nullingRelations -> nullingRelations.isNotEmpty() },
      isStrictButNullable = { oid -> oid in strictButNullableFunctions },
    )

    private fun isNonNull(expression: PgNodeExpression): Boolean = analyzer.isNonNull(expression)

    @Test
    fun `CONST non-null returns true`() {
      assertThat(isNonNull(PgNodeExpression.Const(isNull = false))).isTrue()
    }

    @Test
    fun `CONST null returns false`() {
      assertThat(isNonNull(PgNodeExpression.Const(isNull = true))).isFalse()
    }

    @Test
    fun `FuncExpr strict with all non-null args is non-null`() {
      strictFunctions.add(871)
      val expr = PgNodeExpression.FuncExpr(871, listOf(PgNodeExpression.Const(isNull = false)))
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `FuncExpr strict with nullable arg is nullable`() {
      strictFunctions.add(871)
      val expr = PgNodeExpression.FuncExpr(
        871,
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `FuncExpr non-strict with non-null arg is nullable`() {
      val expr = PgNodeExpression.FuncExpr(999, listOf(PgNodeExpression.Const(isNull = false)))
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `OpExpr strict with all non-null args is non-null`() {
      strictFunctions.add(177)
      val expr = PgNodeExpression.OpExpr(
        177,
        listOf(
          PgNodeExpression.Const(isNull = false),
          PgNodeExpression.Const(isNull = false),
        ),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `OpExpr strict with nullable arg is nullable`() {
      strictFunctions.add(177)
      val expr = PgNodeExpression.OpExpr(
        177,
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
          PgNodeExpression.Const(isNull = false),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `OpExpr non-strict is nullable`() {
      val expr = PgNodeExpression.OpExpr(999, listOf(PgNodeExpression.Const(isNull = false)))
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `ScalarArrayOpExpr strict with non-null args is non-null`() {
      strictFunctions.add(65)
      val expr = PgNodeExpression.ScalarArrayOpExpr(65, listOf(PgNodeExpression.Const(isNull = false)))
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `ScalarArrayOpExpr with nullable arg is nullable`() {
      strictFunctions.add(65)
      val expr = PgNodeExpression.ScalarArrayOpExpr(
        65,
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `CoalesceExpr with one non-null arg is non-null`() {
      val expr = PgNodeExpression.CoalesceExpr(
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
          PgNodeExpression.Const(isNull = false),
        ),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `CoalesceExpr all nullable is nullable`() {
      val expr = PgNodeExpression.CoalesceExpr(
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
          PgNodeExpression.Var(varno = 1, varattno = 3, nullingRelations = emptySet()),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `CoalesceExpr first arg non-null is non-null`() {
      val expr = PgNodeExpression.CoalesceExpr(listOf(PgNodeExpression.Const(isNull = false)))
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `NullIfExpr is always nullable`() {
      val expr = PgNodeExpression.NullIfExpr(
        listOf(
          PgNodeExpression.Const(isNull = false),
          PgNodeExpression.Const(isNull = false),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `MinMaxExpr all non-null args is non-null`() {
      val expr = PgNodeExpression.MinMaxExpr(
        listOf(
          PgNodeExpression.Const(isNull = false),
          PgNodeExpression.Const(isNull = false),
        ),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `MinMaxExpr with nullable arg is nullable`() {
      val expr = PgNodeExpression.MinMaxExpr(
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
          PgNodeExpression.Const(isNull = false),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `Aggref with non-null initial value is non-null`() {
      nonNullAggregates.add(2803)
      assertThat(isNonNull(PgNodeExpression.Aggref(2803, emptyList()))).isTrue()
    }

    @Test
    fun `Aggref with null initial value is nullable`() {
      assertThat(isNonNull(PgNodeExpression.Aggref(2110, emptyList()))).isFalse()
    }

    @Test
    fun `Aggref AVG is nullable`() {
      assertThat(isNonNull(PgNodeExpression.Aggref(2101, emptyList()))).isFalse()
    }

    @Test
    fun `WindowFunc with empty args is non-null — ranking function`() {
      assertThat(isNonNull(PgNodeExpression.WindowFunc(windowFunctionOid = 3100, arguments = emptyList()))).isTrue()
    }

    @Test
    fun `WindowFunc with arguments is nullable — LAG and LEAD return null at window boundaries`() {
      strictFunctions.add(3111)
      strictButNullableFunctions.add(3111)
      val expr = PgNodeExpression.WindowFunc(3111, listOf(PgNodeExpression.Const(isNull = false)))
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `WindowFunc with nullable arg is nullable`() {
      strictFunctions.add(3111)
      val expr = PgNodeExpression.WindowFunc(
        3111,
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `SubLink EXISTS is non-null`() {
      assertThat(isNonNull(PgNodeExpression.SubLink(PgNodeExpression.SUBLINK_TYPE_EXISTS))).isTrue()
    }

    @Test
    fun `SubLink ARRAY is non-null`() {
      assertThat(isNonNull(PgNodeExpression.SubLink(PgNodeExpression.SUBLINK_TYPE_ARRAY))).isTrue()
    }

    @Test
    fun `SubLink ANY is nullable`() {
      assertThat(isNonNull(PgNodeExpression.SubLink(2))).isFalse()
    }

    @Test
    fun `SubLink scalar subquery is nullable`() {
      assertThat(isNonNull(PgNodeExpression.SubLink(4))).isFalse()
    }

    @Test
    fun `CaseExpr all WHEN and ELSE non-null is non-null`() {
      val expr = PgNodeExpression.CaseExpr(
        resultExpressions = listOf(PgNodeExpression.Const(isNull = false)),
        defaultResult = PgNodeExpression.Const(isNull = false),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `CaseExpr one WHEN nullable is nullable`() {
      val expr = PgNodeExpression.CaseExpr(
        resultExpressions = listOf(
          PgNodeExpression.Const(isNull = false),
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
        ),
        defaultResult = PgNodeExpression.Const(isNull = false),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `CaseExpr without ELSE is nullable`() {
      val expr = PgNodeExpression.CaseExpr(
        resultExpressions = listOf(PgNodeExpression.Const(isNull = false)),
        defaultResult = null,
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `CaseExpr ELSE is COALESCE with non-null fallback is non-null`() {
      val expr = PgNodeExpression.CaseExpr(
        resultExpressions = listOf(PgNodeExpression.Const(isNull = false)),
        defaultResult = PgNodeExpression.CoalesceExpr(
          listOf(
            PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
            PgNodeExpression.Const(isNull = false),
          ),
        ),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `BoolExpr AND with all non-null args is non-null`() {
      val expr = PgNodeExpression.BoolExpr(
        listOf(
          PgNodeExpression.NullTest(PgNodeExpression.Const(isNull = false)),
          PgNodeExpression.NullTest(PgNodeExpression.Const(isNull = false)),
        ),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `BoolExpr OR with nullable arg is nullable`() {
      val expr = PgNodeExpression.BoolExpr(
        listOf(
          PgNodeExpression.NullTest(PgNodeExpression.Const(isNull = false)),
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `BoolExpr NOT with nullable arg is nullable`() {
      val expr = PgNodeExpression.BoolExpr(
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `RelabelType passthrough non-null inner is non-null`() {
      assertThat(isNonNull(PgNodeExpression.RelabelType(PgNodeExpression.Const(isNull = false)))).isTrue()
    }

    @Test
    fun `RelabelType passthrough nullable inner is nullable`() {
      assertThat(
        isNonNull(
          PgNodeExpression.RelabelType(
            PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
          ),
        ),
      ).isFalse()
    }

    @Test
    fun `CoerceViaIo passthrough nullable inner is nullable`() {
      assertThat(
        isNonNull(
          PgNodeExpression.CoerceViaIo(
            PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
          ),
        ),
      ).isFalse()
    }

    @Test
    fun `ArrayCoerceExpr passthrough non-null inner is non-null`() {
      assertThat(isNonNull(PgNodeExpression.ArrayCoerceExpr(PgNodeExpression.Const(isNull = false)))).isTrue()
    }

    @Test
    fun `CollateExpr passthrough nullable inner is nullable`() {
      assertThat(
        isNonNull(
          PgNodeExpression.CollateExpr(
            PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
          ),
        ),
      ).isFalse()
    }

    @Test
    fun `CoerceToDomain passthrough non-null inner is non-null`() {
      assertThat(isNonNull(PgNodeExpression.CoerceToDomain(PgNodeExpression.Const(isNull = false)))).isTrue()
    }

    @Test
    fun `SqlValueFunction is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.SqlValueFunction(0))).isTrue()
    }

    @Test
    fun `NullTest is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.NullTest(PgNodeExpression.Const(isNull = true)))).isTrue()
    }

    @Test
    fun `BooleanTest is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.BooleanTest(PgNodeExpression.Const(isNull = true)))).isTrue()
    }

    @Test
    fun `DistinctExpr is always non-null`() {
      val expr = PgNodeExpression.DistinctExpr(listOf(PgNodeExpression.Const(isNull = false)))
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `ArrayExpr is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.ArrayExpr(emptyList()))).isTrue()
    }

    @Test
    fun `RowExpr is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.RowExpr(emptyList()))).isTrue()
    }

    @Test
    fun `NextValExpr is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.NextValExpr(12345))).isTrue()
    }

    @Test
    fun `GroupingFunc is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.GroupingFunc(emptyList()))).isTrue()
    }

    @Test
    fun `FieldSelect is always nullable — safe default for composite field access`() {
      assertThat(isNonNull(PgNodeExpression.FieldSelect(PgNodeExpression.Const(isNull = false), 1))).isFalse()
    }

    @Test
    fun `JsonIsPredicate is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.JsonIsPredicate(PgNodeExpression.Const(isNull = false)))).isTrue()
    }

    @Test
    fun `JsonConstructorExpr is always non-null`() {
      assertThat(isNonNull(PgNodeExpression.JsonConstructorExpr(emptyList()))).isTrue()
    }

    @Test
    fun `JsonExpr JSON_EXISTS is non-null`() {
      val expr = PgNodeExpression.JsonExpr(
        op = PgNodeExpression.JSON_EXISTS_OP,
        argument = PgNodeExpression.Const(isNull = false),
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_NULL,
        onEmptyDefault = null,
        onError = PgNodeExpression.JSON_BEHAVIOR_NULL,
        onErrorDefault = null,
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `JsonExpr JSON_VALUE with default NULL on empty is nullable`() {
      val expr = PgNodeExpression.JsonExpr(
        op = PgNodeExpression.JSON_VALUE_OP,
        argument = PgNodeExpression.Const(isNull = false),
        onEmpty = PgNodeExpression.JSON_BEHAVIOR_NULL,
        onEmptyDefault = null,
        onError = PgNodeExpression.JSON_BEHAVIOR_NULL,
        onErrorDefault = null,
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `JsonExpr JSON_VALUE with both defaults non-null is non-null`() {
      val expr = PgNodeExpression.JsonExpr(
        op = PgNodeExpression.JSON_VALUE_OP,
        argument = PgNodeExpression.Const(isNull = false),
        onEmpty = 2, // non-null behavior type
        onEmptyDefault = PgNodeExpression.Const(isNull = false),
        onError = 2,
        onErrorDefault = PgNodeExpression.Const(isNull = false),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `JsonExpr JSON_VALUE with one null default is nullable`() {
      val expr = PgNodeExpression.JsonExpr(
        op = PgNodeExpression.JSON_VALUE_OP,
        argument = PgNodeExpression.Const(isNull = false),
        onEmpty = 2,
        onEmptyDefault = PgNodeExpression.Const(isNull = false),
        onError = PgNodeExpression.JSON_BEHAVIOR_NULL, // this makes it nullable
        onErrorDefault = null,
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `XmlExpr XMLELEMENT is non-null`() {
      assertThat(isNonNull(PgNodeExpression.XmlExpr(PgNodeExpression.XML_IS_XMLELEMENT, emptyList()))).isTrue()
    }

    @Test
    fun `XmlExpr XMLPARSE with non-null arg is non-null`() {
      val expr = PgNodeExpression.XmlExpr(
        PgNodeExpression.XML_IS_XMLPARSE,
        listOf(PgNodeExpression.Const(isNull = false)),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `XmlExpr XMLPARSE with nullable arg is nullable`() {
      val expr = PgNodeExpression.XmlExpr(
        PgNodeExpression.XML_IS_XMLPARSE,
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `XmlExpr XMLSERIALIZE with nullable arg is nullable`() {
      val expr = PgNodeExpression.XmlExpr(
        PgNodeExpression.XML_IS_XMLSERIALIZE,
        listOf(
          PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `Var NOT NULL column with no outer join is non-null`() {
      notNullColumns.add(1 to 1)
      assertThat(isNonNull(PgNodeExpression.Var(varno = 1, varattno = 1, nullingRelations = emptySet()))).isTrue()
    }

    @Test
    fun `Var nullable column is nullable`() {
      assertThat(isNonNull(PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()))).isFalse()
    }

    @Test
    fun `Var outer-join-nullable is nullable even if column is NOT NULL`() {
      notNullColumns.add(2 to 1)
      assertThat(isNonNull(PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = setOf(3)))).isFalse()
    }

    @Test
    fun `COALESCE outer-join column with non-null fallback is non-null`() {
      notNullColumns.add(2 to 1)
      val expr = PgNodeExpression.CoalesceExpr(
        listOf(
          PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = setOf(3)), // outer-join nullable
          PgNodeExpression.Const(isNull = false),
        ),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `strict function wrapping outer-join column is nullable`() {
      strictFunctions.add(871)
      notNullColumns.add(2 to 1)
      val expr = PgNodeExpression.FuncExpr(
        871,
        listOf(
          PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = setOf(3)), // outer-join nullable
        ),
      )
      assertThat(isNonNull(expr)).isFalse()
    }

    @Test
    fun `nested strict wrapping COALESCE with fallback is non-null`() {
      strictFunctions.add(871)
      val expr = PgNodeExpression.FuncExpr(
        871,
        listOf(
          PgNodeExpression.CoalesceExpr(
            listOf(
              PgNodeExpression.Var(varno = 1, varattno = 2, nullingRelations = emptySet()),
              PgNodeExpression.Const(isNull = false),
            ),
          ),
        ),
      )
      assertThat(isNonNull(expr)).isTrue()
    }

    @Test
    fun `Unknown is always nullable — safe default`() {
      assertThat(isNonNull(PgNodeExpression.Unknown("WEIRD"))).isFalse()
    }

    @Test
    fun `recursion depth guard returns false at depth 0`() {
      val expr = PgNodeExpression.RelabelType(PgNodeExpression.Const(isNull = false))
      assertThat(analyzer.isNonNull(expr, depth = 0)).isFalse()
    }
  }

  @Nested
  inner class ExtractOuterJoinNullability {

    @Test
    fun `VAR with nulling relations is outer-join nullable`() {
      // LEFT JOIN: columns 1-2 come from the preserved side (varnullingrels empty → false),
      // columns 3-4 come from the nullable side (varnullingrels (b 3) → true).
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias {ALIAS :aliasname d :colnames <>} :eref {ALIAS :aliasname d :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias {ALIAS :aliasname e :colnames <>} :eref {ALIAS :aliasname e :colnames ("id" "name" "department_id" "nickname")} :rtekind 0 :relid 16472 :inh true :relkind r :rellockmode 1 :perminfoindex 2 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias <> :eref {ALIAS :aliasname unnamed_join :colnames ("id" "name" "id" "name" "department_id" "nickname")} :rtekind 2 :jointype 1 :joinmergedcols 0 :joinaliasvars ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 1 :location -1} {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 2 :varattno 4 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 4 :location -1}) :joinleftcols (i 1 2) :joinrightcols (i 1 2 3 4) :join_using_alias <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 9) :insertedCols (b) :updatedCols (b)} {RTEPERMISSIONINFO :relid 16472 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 9 10 11) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({JOINEXPR :jointype 1 :isNatural false :larg {RANGETBLREF :rtindex 1} :rarg {RANGETBLREF :rtindex 2} :usingClause <> :join_using_alias <> :quals {OPEXPR :opno 96 :opfuncid 65 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1}) :location -1} :alias <> :rtindex 3}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} :resno 1 :resname id :ressortgroupref 0 :resorigtbl 16461 :resorigcol 1 :resjunk false} {TARGETENTRY :expr {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :resno 2 :resname name :ressortgroupref 0 :resorigtbl 16461 :resorigcol 2 :resjunk false} {TARGETENTRY :expr {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} :resno 3 :resname employee_name :ressortgroupref 0 :resorigtbl 16472 :resorigcol 2 :resjunk false} {TARGETENTRY :expr {VAR :varno 2 :varattno 4 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b 3) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 4 :location -1} :resno 4 :resname nickname :ressortgroupref 0 :resorigtbl 16472 :resorigcol 4 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val result = NodeTreeNullabilityAnalyzer.extractOuterJoinNullability(nodeTree)
      assertThat(result).containsExactly(false, false, true, true)
    }

    @Test
    fun `VAR with empty nulling relations is not outer-join nullable`() {
      // INNER JOIN: all columns have varnullingrels (b) — empty bitmapset, so none are nullable.
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs false :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias {ALIAS :aliasname d :colnames <>} :eref {ALIAS :aliasname d :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias {ALIAS :aliasname e :colnames <>} :eref {ALIAS :aliasname e :colnames ("id" "name" "department_id" "nickname")} :rtekind 0 :relid 16472 :inh true :relkind r :rellockmode 1 :perminfoindex 2 :tablesample <> :lateral false :inFromCl true :securityQuals <>} {RANGETBLENTRY :alias <> :eref {ALIAS :aliasname unnamed_join :colnames ("id" "name" "id" "name" "department_id" "nickname")} :rtekind 2 :jointype 0 :joinmergedcols 0 :joinaliasvars ({VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1} {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 1 :location -1} {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} {VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 2 :varattno 4 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 4 :location -1}) :joinleftcols (i 1 2) :joinrightcols (i 1 2 3 4) :join_using_alias <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 8 9) :insertedCols (b) :updatedCols (b)} {RTEPERMISSIONINFO :relid 16472 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b 9 10) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({JOINEXPR :jointype 0 :isNatural false :larg {RANGETBLREF :rtindex 1} :rarg {RANGETBLREF :rtindex 2} :usingClause <> :join_using_alias <> :quals {OPEXPR :opno 96 :opfuncid 65 :opresulttype 16 :opretset false :opcollid 0 :inputcollid 0 :args ({VAR :varno 2 :varattno 3 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 3 :location -1} {VAR :varno 1 :varattno 1 :vartype 23 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1}) :location -1} :alias <> :rtindex 3}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {VAR :varno 1 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 2 :location -1} :resno 1 :resname name :ressortgroupref 0 :resorigtbl 16461 :resorigcol 2 :resjunk false} {TARGETENTRY :expr {VAR :varno 2 :varattno 2 :vartype 25 :vartypmod -1 :varcollid 100 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 2 :varattnosyn 2 :location -1} :resno 2 :resname employee_name :ressortgroupref 0 :resorigtbl 16472 :resorigcol 2 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val result = NodeTreeNullabilityAnalyzer.extractOuterJoinNullability(nodeTree)
      assertThat(result).containsExactly(false, false)
    }

    @Test
    fun `non-VAR expression (AGGREF) is never outer-join nullable`() {
      // COUNT(*) produces an AGGREF node — not a VAR, so extractOuterJoinNullability returns false.
      val nodeTree = """({QUERY :commandType 1 :querySource 0 :canSetTag true :utilityStmt <> :resultRelation 0 :hasAggs true :hasWindowFuncs false :hasTargetSRFs false :hasSubLinks false :hasDistinctOn false :hasRecursive false :hasModifyingCTE false :hasForUpdate false :hasRowSecurity false :hasGroupRTE false :isReturn false :cteList <> :rtable ({RANGETBLENTRY :alias <> :eref {ALIAS :aliasname department :colnames ("id" "name")} :rtekind 0 :relid 16461 :inh true :relkind r :rellockmode 1 :perminfoindex 1 :tablesample <> :lateral false :inFromCl true :securityQuals <>}) :rteperminfos ({RTEPERMISSIONINFO :relid 16461 :inh true :requiredPerms 2 :checkAsUser 0 :selectedCols (b) :insertedCols (b) :updatedCols (b)}) :jointree {FROMEXPR :fromlist ({RANGETBLREF :rtindex 1}) :quals <>} :mergeActionList <> :mergeTargetRelation 0 :mergeJoinCondition <> :targetList ({TARGETENTRY :expr {AGGREF :aggfnoid 2803 :aggtype 20 :aggcollid 0 :inputcollid 0 :aggtranstype 0 :aggargtypes <> :aggdirectargs <> :args <> :aggorder <> :aggdistinct <> :aggfilter <> :aggstar true :aggvariadic false :aggkind n :aggpresorted false :agglevelsup 0 :aggsplit 0 :aggno -1 :aggtransno -1 :location -1} :resno 1 :resname total :ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false}) :override 0 :onConflict <> :returningOldAlias <> :returningNewAlias <> :returningList <> :groupClause <> :groupDistinct false :groupingSets <> :havingQual <> :windowClause <> :distinctClause <> :sortClause <> :limitOffset <> :limitCount <> :limitOption 0 :rowMarks <> :setOperations <> :constraintDeps <> :withCheckOptions <> :stmt_location -1 :stmt_len -1})"""
      val result = NodeTreeNullabilityAnalyzer.extractOuterJoinNullability(nodeTree)
      assertThat(result).containsExactly(false)
    }
  }
}
