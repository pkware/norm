package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * Unit tests for [substituteGroupRteVars] against synthetic [PgNodeExpression] trees — no live
 * database or `pg_node_tree` text parsing involved, since the substitution logic itself operates
 * entirely on the already-parsed [PgNodeExpression] hierarchy.
 */
class GroupRteSubstitutionTest {

  private val baseColumn = PgNodeExpression.Var(varno = 1, varattno = 1, nullingRelations = emptySet())

  @Test
  fun `a Var buried inside an Aggref argument is substituted — synthetic input, not a shape PG18 produces`() {
    // safetyWalkChildren (NodeTreeNullabilityAnalyzer's other child-walk) treats Aggref as
    // childless, but this substitution needs a branch for Aggref regardless (the when is
    // exhaustive). Unlike the OpExpr case this file also covers, no live PG18 tree puts a
    // GROUP-RTE Var inside an Aggref's own arguments -- PostgreSQL never rewrites aggregate
    // arguments; they're evaluated pre-grouping, before any GROUP RTE substitution could apply
    // (see substituteGroupRteVars's own KDoc). This input is synthetic, constructed to prove the
    // traversal itself is correct and defensive, not to reproduce a real node-tree shape.
    val groupRteVar = PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = emptySet())
    val aggref = PgNodeExpression.Aggref(aggregateFunctionOid = 2147, arguments = listOf(groupRteVar))
    val groupExpressionsByVarno = mapOf(2 to listOf(baseColumn))

    val result = substituteGroupRteVars(aggref, groupExpressionsByVarno) as PgNodeExpression.Aggref

    assertThat(result.arguments.single()).isEqualTo(baseColumn)
  }

  @Test
  fun `a Var with levelsUp greater than 0 is left unchanged`() {
    // levelsUp > 0 means this Var refers to an outer query level's range table, which
    // groupExpressionsByVarno does not describe — substituting against it would resolve against
    // the wrong query level entirely, if the varno happened to collide.
    val outerVar = PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = emptySet(), levelsUp = 1)
    val groupExpressionsByVarno = mapOf(2 to listOf(baseColumn))

    val result = substituteGroupRteVars(outerVar, groupExpressionsByVarno)

    assertThat(result).isEqualTo(outerVar)
  }

  @Test
  fun `a Var with an out-of-range varattno is left unchanged`() {
    val groupRteVar = PgNodeExpression.Var(varno = 2, varattno = 5, nullingRelations = emptySet())
    val groupExpressionsByVarno = mapOf(2 to listOf(baseColumn)) // only index 0 (varattno 1) exists

    val result = substituteGroupRteVars(groupRteVar, groupExpressionsByVarno)

    assertThat(result).isEqualTo(groupRteVar)
  }

  @Test
  fun `a Var whose resolved group expression is Unknown is left unchanged`() {
    // An Unknown resolution means the groupexprs entry is either a parse failure or an unmodelled
    // node type. Substituting it in would replace a Var that PgNodeTreeParser.parseGroupRteMap's
    // coarser, Var-only fallback might still be able to resolve — so this Var must be left exactly
    // as parsed, not swapped for Unknown.
    val groupRteVar = PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = emptySet())
    val groupExpressionsByVarno = mapOf(2 to listOf<PgNodeExpression>(PgNodeExpression.Unknown("XMLTABLE")))

    val result = substituteGroupRteVars(groupRteVar, groupExpressionsByVarno)

    assertThat(result).isEqualTo(groupRteVar)
  }

  @Test
  fun `substitution is single-pass — a resolved expression's own GROUP-RTE-shaped Var is not re-rewritten`() {
    // The resolved groupexprs entry for varno 2 is itself a Var that would match varno 3's entry if
    // recursed into again. A correct single-pass substitution returns it verbatim; a buggy
    // multi-pass implementation would keep rewriting until it reached baseColumn instead.
    val innerGroupRteShapedVar = PgNodeExpression.Var(varno = 3, varattno = 1, nullingRelations = emptySet())
    val outerGroupRteVar = PgNodeExpression.Var(varno = 2, varattno = 1, nullingRelations = emptySet())
    val groupExpressionsByVarno = mapOf(
      2 to listOf<PgNodeExpression>(innerGroupRteShapedVar),
      3 to listOf(baseColumn),
    )

    val result = substituteGroupRteVars(outerGroupRteVar, groupExpressionsByVarno)

    assertThat(result).isEqualTo(innerGroupRteShapedVar)
  }

  @Test
  fun `nothing is inherited from the replaced Var — the resolved expression's own fields win`() {
    // In PostgreSQL 18, `SELECT b.x, count(*) FROM t LEFT JOIN u b ON b.id = t.id GROUP BY b.x`
    // — the target-list Var referencing the GROUP RTE has empty :varnullingrels, while the GROUP
    // RTE's own :groupexprs entry carries the real, non-empty nulling relations from the LEFT
    // JOIN. The wrapper Var's empty nullingRelations must not leak into the result.
    val wrapperVar = PgNodeExpression.Var(varno = 4, varattno = 1, nullingRelations = emptySet())
    val resolvedJoinColumn = PgNodeExpression.Var(varno = 2, varattno = 2, nullingRelations = setOf(3))
    val groupExpressionsByVarno = mapOf(4 to listOf<PgNodeExpression>(resolvedJoinColumn))

    val result = substituteGroupRteVars(wrapperVar, groupExpressionsByVarno) as PgNodeExpression.Var

    assertThat(result.nullingRelations).isEqualTo(setOf(3))
    assertThat(result.varno).isEqualTo(2)
    assertThat(result.varattno).isEqualTo(2)
  }

  @Test
  fun `a Var with no matching varno in the map is left unchanged`() {
    val ordinaryVar = PgNodeExpression.Var(varno = 1, varattno = 1, nullingRelations = emptySet())

    val result = substituteGroupRteVars(ordinaryVar, emptyMap())

    assertThat(result).isSameInstanceAs(ordinaryVar)
  }

  @Test
  fun `a Const is returned unchanged since it has no children to walk`() {
    val const = PgNodeExpression.Const(isNull = false)

    val result = substituteGroupRteVars(const, mapOf(2 to listOf(baseColumn)))

    assertThat(result).isSameInstanceAs(const)
    assertThat((result as PgNodeExpression.Const).isNull).isFalse()
  }
}
