package norm.generator.libpgquery

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import norm.generator.JsonValue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ParseTreeTest {

  private fun parse(sql: String) = ParseResult(LibPgQuery.parse(sql))

  private fun statement(sql: String): StatementNode = checkNotNull(parse(sql).firstStatement)

  private fun targets(sql: String): List<ResultTarget> = checkNotNull(statement(sql).select).targets

  private fun target(sql: String): ResultTarget = targets(sql).single()

  @Nested
  inner class ParseResultViews {

    @Test
    fun `statements lists each statement of the text in order`() {
      val statements = parse("SELECT 1; DELETE FROM t").statements

      assertThat(statements).hasSize(2)
      assertThat(statements[0].select).isNotNull()
      assertThat(statements[1].select).isNull()
    }

    @Test
    fun `statements is empty for text with no statement`() {
      assertThat(parse("").statements).isEmpty()
    }

    @Test
    fun `firstStatement is the first statement`() {
      val result = parse("SELECT 1; CALL p(1)")

      assertThat(checkNotNull(result.firstStatement).select).isNotNull()
      assertThat(checkNotNull(result.firstStatement).call).isNull()
    }

    @Test
    fun `firstStatement is null for text with no statement`() {
      assertThat(parse("").firstStatement).isNull()
    }

    @Test
    fun `outputStatement skips a statement that returns no result set`() {
      val output = checkNotNull(parse("CALL p(1); SELECT 1 AS a; SELECT 2 AS b").outputStatement)

      assertThat(output.targets.single().alias).isEqualTo("a")
    }

    @Test
    fun `outputStatement is null when no statement returns a result set`() {
      assertThat(parse("DELETE FROM t").outputStatement).isNull()
    }

    @Test
    fun `resultTargetAt finds the target that starts at the location`() {
      val result = parse("SELECT a, b AS c FROM t")

      assertThat(result.resultTargetAt(7)?.alias).isNull()
      assertThat(result.resultTargetAt(10)?.alias).isEqualTo("c")
    }

    @Test
    fun `resultTargetAt is null when no target starts at the location`() {
      assertThat(parse("SELECT a FROM t").resultTargetAt(8)).isNull()
    }

    @Test
    fun `withoutItemValueAndLocations makes texts of different length equal when only the item value differs`() {
      val short = parse("SELECT 1 AS a FROM t").withoutItemValueAndLocations(7)
      val long = parse("SELECT  'a longer value' AS a FROM t").withoutItemValueAndLocations(8)

      assertThat(short).isEqualTo(long)
    }

    @Test
    fun `withoutItemValueAndLocations keeps a difference outside the item value`() {
      val aliasA = parse("SELECT 1 AS a FROM t").withoutItemValueAndLocations(7)
      val aliasB = parse("SELECT 1 AS b FROM t").withoutItemValueAndLocations(7)

      assertThat(aliasA).isNotEqualTo(aliasB)
    }

    @Test
    fun `withoutItemValueAndLocations leaves no location field`() {
      val stripped = parse("SELECT a FROM t WHERE a = 1").withoutItemValueAndLocations(7)

      assertThat(containsKey(stripped, "location")).isFalse()
    }

    private fun containsKey(value: JsonValue, key: String): Boolean = when (value) {
      is JsonValue.JsonObject -> key in value.fields || value.fields.values.any { containsKey(it, key) }
      is JsonValue.JsonArray -> value.items.any { containsKey(it, key) }
      else -> false
    }
  }

  @Nested
  inner class StatementNodeViews {

    @Test
    fun `select is present for SELECT, VALUES, TABLE and a set operation`() {
      for (sql in listOf("SELECT 1", "VALUES (1)", "TABLE t", "SELECT 1 UNION SELECT 2")) {
        assertThat(statement(sql).select).isNotNull()
      }
    }

    @Test
    fun `select is null for a statement of another kind`() {
      assertThat(statement("DELETE FROM t").select).isNull()
    }

    @Test
    fun `explain is present for EXPLAIN`() {
      assertThat(statement("EXPLAIN SELECT 1").explain).isNotNull()
      assertThat(statement("SELECT 1").explain).isNull()
    }

    @Test
    fun `call is present for CALL`() {
      assertThat(statement("CALL p(1)").call).isNotNull()
      assertThat(statement("SELECT 1").call).isNull()
    }

    @Test
    fun `commonTableExpressions lists the CTEs of the statement in order`() {
      val expressions = statement(
        "WITH x AS (SELECT 1 AS a), y AS (SELECT 2 AS b) SELECT a FROM x",
      ).commonTableExpressions

      assertThat(expressions.map { it.name }).isEqualTo(listOf("x", "y"))
    }

    @Test
    fun `commonTableExpressions omits the CTEs of a nested WITH`() {
      val sql = "WITH o AS (WITH i AS (SELECT 1 AS a) SELECT a FROM i) SELECT a FROM o"
      val outer = statement(sql).commonTableExpressions.single()

      assertThat(outer.name).isEqualTo("o")
      assertThat(outer.query.commonTableExpressions.single().name).isEqualTo("i")
    }

    @Test
    fun `commonTableExpressions is empty without a WITH clause`() {
      assertThat(statement("SELECT 1").commonTableExpressions).isEmpty()
    }

    @Test
    fun `commonTableExpressions reads the WITH clause of a data-modifying statement`() {
      val sql = "WITH x AS (SELECT 1 AS a) INSERT INTO t SELECT a FROM x"

      assertThat(statement(sql).commonTableExpressions.map { it.name }).isEqualTo(listOf("x"))
    }

    @Test
    fun `returningTargets holds the RETURNING items of INSERT, UPDATE, DELETE and MERGE`() {
      val statements = listOf(
        "INSERT INTO t (a) VALUES (1) RETURNING a, b AS c",
        "UPDATE t SET a = 1 RETURNING a, b AS c",
        "DELETE FROM t RETURNING a, b AS c",
        "MERGE INTO t USING s ON t.a = s.a WHEN MATCHED THEN DELETE RETURNING a, b AS c",
      )
      for (sql in statements) {
        assertThat(statement(sql).returningTargets?.map { it.alias }).isEqualTo(listOf(null, "c"))
      }
    }

    @Test
    fun `returningTargets is null without a RETURNING list`() {
      assertThat(statement("INSERT INTO t (a) VALUES (1)").returningTargets).isNull()
      assertThat(statement("SELECT 1").returningTargets).isNull()
    }

    @Test
    fun `outputStatement of a SELECT holds its targets`() {
      val output = checkNotNull(statement("SELECT a, b AS c FROM t").outputStatement)

      assertThat(output.targets.map { it.alias }).isEqualTo(listOf(null, "c"))
      assertThat(output.isSetOperation).isFalse()
    }

    @Test
    fun `outputStatement of VALUES has no targets`() {
      val output = checkNotNull(statement("VALUES (1, 2)").outputStatement)

      assertThat(output.targets).isEmpty()
      assertThat(output.isSetOperation).isFalse()
    }

    @Test
    fun `outputStatement of TABLE has the star target that the parser synthesizes`() {
      val output = checkNotNull(statement("TABLE t").outputStatement)

      assertThat(output.targets.single().isStar).isTrue()
      assertThat(output.targets.single().location).isEqualTo(-1)
    }

    @Test
    fun `outputStatement of a set operation holds the targets of the leftmost branch`() {
      val output = checkNotNull(statement("SELECT 1 AS a UNION SELECT 2 AS b").outputStatement)

      assertThat(output.targets.map { it.alias }).isEqualTo(listOf("a"))
      assertThat(output.isSetOperation).isTrue()
    }

    @Test
    fun `outputStatement of EXPLAIN has no targets`() {
      val output = checkNotNull(statement("EXPLAIN SELECT 1 AS a").outputStatement)

      assertThat(output.targets).isEmpty()
      assertThat(output.isSetOperation).isFalse()
    }

    @Test
    fun `outputStatement of a data-modifying statement holds its RETURNING targets`() {
      val output = checkNotNull(statement("INSERT INTO t (a) VALUES (1) RETURNING a AS x").outputStatement)

      assertThat(output.targets.map { it.alias }).isEqualTo(listOf("x"))
      assertThat(output.isSetOperation).isFalse()
    }

    @Test
    fun `outputStatement is null when the statement returns no result set`() {
      assertThat(statement("INSERT INTO t (a) VALUES (1)").outputStatement).isNull()
      assertThat(statement("CALL p(1)").outputStatement).isNull()
    }

    @Test
    fun `outputStatement holds the statement it was read from`() {
      val statement = statement("SELECT 1")

      assertThat(checkNotNull(statement.outputStatement).node.node).isSameInstanceAs(statement.node)
    }
  }

  @Nested
  inner class SelectStatementViews {

    private fun select(sql: String): SelectStatement = checkNotNull(statement(sql).select)

    @Test
    fun `isSetOperation is true for UNION, INTERSECT and EXCEPT`() {
      for (operator in listOf("UNION", "UNION ALL", "INTERSECT", "EXCEPT")) {
        assertThat(select("SELECT 1 $operator SELECT 2").isSetOperation).isTrue()
      }
    }

    @Test
    fun `isSetOperation is false for SELECT, VALUES and TABLE`() {
      for (sql in listOf("SELECT 1", "VALUES (1)", "TABLE t")) {
        assertThat(select(sql).isSetOperation).isFalse()
      }
    }

    @Test
    fun `leftmostLeaf of a plain SELECT is itself`() {
      val select = select("SELECT 1")

      assertThat(checkNotNull(select.leftmostLeaf).node).isSameInstanceAs(select.node)
    }

    @Test
    fun `leftmostLeaf descends the left branch of nested set operations`() {
      val leaf = checkNotNull(select("SELECT 1 AS a UNION SELECT 2 AS b EXCEPT SELECT 3 AS c").leftmostLeaf)

      assertThat(leaf.targets.single().alias).isEqualTo("a")
    }

    @Test
    fun `hasValuesLists is true only for VALUES`() {
      assertThat(select("VALUES (1)").hasValuesLists).isTrue()
      assertThat(select("SELECT 1").hasValuesLists).isFalse()
      assertThat(select("TABLE t").hasValuesLists).isFalse()
    }

    @Test
    fun `targets lists the select list in order`() {
      val targets = select("SELECT a, b AS c FROM t").targets

      assertThat(targets.map { it.alias }).isEqualTo(listOf(null, "c"))
    }

    @Test
    fun `targets is empty for VALUES and a set operation node`() {
      assertThat(select("VALUES (1)").targets).isEmpty()
      assertThat(select("SELECT 1 UNION SELECT 2").targets).isEmpty()
    }

    @Test
    fun `targets of TABLE is the star that the parser synthesizes`() {
      assertThat(select("TABLE t").targets.single().isStar).isTrue()
    }

    @Test
    fun `intoRelation is present for SELECT INTO`() {
      assertThat(select("SELECT 1 INTO s.t").intoRelation).isNotNull()
    }

    @Test
    fun `intoRelation is null without an INTO clause`() {
      assertThat(select("SELECT 1").intoRelation).isNull()
    }

    @Test
    fun `intoRelation of a set operation is on its leftmost leaf`() {
      val select = select("SELECT 1 INTO t UNION SELECT 2")

      assertThat(select.intoRelation).isNull()
      assertThat(select.leftmostLeaf?.intoRelation).isNotNull()
    }
  }

  @Nested
  inner class ResultTargetViews {

    @Test
    fun `location is where the item starts`() {
      assertThat(targets("SELECT a, b AS c FROM t").map { it.location }).isEqualTo(listOf(7, 10))
    }

    @Test
    fun `alias is folded when unquoted and unquoted when quoted`() {
      assertThat(target("SELECT 1 AS Foo").alias).isEqualTo("foo")
      assertThat(target("SELECT 1 AS \"Foo\"\"Bar\"").alias).isEqualTo("Foo\"Bar")
    }

    @Test
    fun `alias is null without one`() {
      assertThat(target("SELECT a FROM t").alias).isNull()
    }

    @Test
    fun `alias reads an implicit alias`() {
      assertThat(target("SELECT 1 x").alias).isEqualTo("x")
    }

    @Test
    fun `value is the expression node`() {
      assertThat(checkNotNull(target("SELECT 1").value).fields.keys).isEqualTo(setOf("A_Const"))
    }

    @Test
    fun `isStar is true for star, table star and a composite expansion`() {
      assertThat(target("SELECT * FROM t").isStar).isTrue()
      assertThat(target("SELECT t.* FROM t").isStar).isTrue()
      assertThat(target("SELECT (f(x)).*").isStar).isTrue()
    }

    @Test
    fun `isStar is false for a column and a computed expression`() {
      assertThat(target("SELECT a FROM t").isStar).isFalse()
      assertThat(target("SELECT a * 2 FROM t").isStar).isFalse()
    }

    @Test
    fun `columnReferenceNames holds one name for an unqualified column`() {
      assertThat(target("SELECT Name FROM t").columnReferenceNames).isEqualTo(listOf("name"))
    }

    @Test
    fun `columnReferenceNames holds the table and column of a qualified column`() {
      assertThat(target("SELECT \"My Table\".name FROM t").columnReferenceNames)
        .isEqualTo(listOf("My Table", "name"))
    }

    @Test
    fun `columnReferenceNames is null for a computed expression`() {
      assertThat(target("SELECT a + 1 FROM t").columnReferenceNames).isNull()
    }

    @Test
    fun `columnReferenceNames is null for a reference of three parts`() {
      assertThat(target("SELECT s.t.a FROM s.t").columnReferenceNames).isNull()
    }

    @Test
    fun `columnReferenceNames is null for a star`() {
      assertThat(target("SELECT t.* FROM t").columnReferenceNames).isNull()
    }

    @Test
    fun `hasConstantValueAt is true for a lone literal at the location`() {
      assertThat(target("SELECT 1 AS a").hasConstantValueAt(7)).isTrue()
    }

    @Test
    fun `hasConstantValueAt is false for another location, a column and an expression`() {
      assertThat(target("SELECT 1 AS a").hasConstantValueAt(8)).isFalse()
      assertThat(target("SELECT a").hasConstantValueAt(7)).isFalse()
      assertThat(target("SELECT 1 + 2").hasConstantValueAt(7)).isFalse()
    }

    @Test
    fun `largestLocation is the greatest location inside the item`() {
      assertThat(target("SELECT a + bb FROM t").largestLocation).isEqualTo(11)
    }

    @Test
    fun `largestLocation of a lone column is its own location`() {
      assertThat(target("SELECT a FROM t").largestLocation).isEqualTo(7)
    }
  }

  @Nested
  inner class CommonTableExpressionViews {

    @Test
    fun `name is the folded name and a quoted name keeps its case`() {
      val sql = "WITH MyCte AS (SELECT 1), \"MyCte\" AS (SELECT 2) SELECT 1"

      assertThat(statement(sql).commonTableExpressions.map { it.name }).isEqualTo(listOf("mycte", "MyCte"))
    }

    @Test
    fun `query is the body statement`() {
      val expressions = statement("WITH x AS (SELECT 1 AS a), y AS (DELETE FROM t RETURNING b) SELECT 1")
        .commonTableExpressions

      assertThat(checkNotNull(expressions[0].query.select).targets.single().alias).isEqualTo("a")
      assertThat(expressions[1].query.returningTargets?.map { it.columnReferenceNames })
        .isEqualTo(listOf(listOf("b")))
    }
  }

  @Nested
  inner class ExplainStatementViews {

    private fun optionCount(sql: String): Int = checkNotNull(statement(sql).explain).optionCount

    @Test
    fun `optionCount is zero for plain EXPLAIN`() {
      assertThat(optionCount("EXPLAIN SELECT 1")).isEqualTo(0)
    }

    @Test
    fun `optionCount counts a parenthesized option list`() {
      assertThat(optionCount("EXPLAIN (ANALYZE, VERBOSE) SELECT 1")).isEqualTo(2)
    }

    @Test
    fun `optionCount counts each legacy word`() {
      assertThat(optionCount("EXPLAIN ANALYZE VERBOSE SELECT 1")).isEqualTo(2)
    }
  }

  @Nested
  inner class IntoRelationViews {

    private fun relation(sql: String): IntoRelation = checkNotNull(checkNotNull(statement(sql).select).intoRelation)

    @Test
    fun `location is where the table name starts`() {
      assertThat(relation("SELECT 1 INTO s.t").location).isEqualTo(14)
    }

    @Test
    fun `partCount is one for an unqualified name`() {
      assertThat(relation("SELECT 1 INTO t").partCount).isEqualTo(1)
    }

    @Test
    fun `partCount is two for a schema-qualified name`() {
      assertThat(relation("SELECT 1 INTO s.t").partCount).isEqualTo(2)
    }

    @Test
    fun `partCount is three for a database-qualified name`() {
      assertThat(relation("SELECT 1 INTO d.s.t").partCount).isEqualTo(3)
    }
  }

  @Nested
  inner class CallStatementViews {

    private fun call(sql: String): CallStatement = checkNotNull(statement(sql).call)

    @Test
    fun `procedureName is the name of an unqualified procedure`() {
      assertThat(call("CALL p(1)").procedureName).isEqualTo("p")
    }

    @Test
    fun `procedureName is null for a schema-qualified procedure`() {
      assertThat(call("CALL s.p(1)").procedureName).isNull()
    }

    @Test
    fun `procedureName reads a quoted name without quotes`() {
      assertThat(call("CALL \"My Proc\"(1)").procedureName).isEqualTo("My Proc")
    }
  }

  @Nested
  inner class OutputStatementViews {

    @Test
    fun `targets of a nested WITH statement are those of the main query`() {
      val sql = "WITH o AS (WITH i AS (SELECT 1 AS a) SELECT a FROM i) SELECT a AS z FROM o"
      val output = checkNotNull(statement(sql).outputStatement)

      assertThat(output.targets.map { it.alias }).isEqualTo(listOf("z"))
      assertThat(output.targets.single().columnReferenceNames).isEqualTo(listOf("a"))
    }
  }
}
