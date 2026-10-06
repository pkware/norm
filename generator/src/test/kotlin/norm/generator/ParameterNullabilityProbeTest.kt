package norm.generator

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.cause
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement

/**
 * Runs the probe and the parameter inferrer against a real PostgreSQL schema, so every expectation is what
 * PostgreSQL's own parse of the statement implies.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class ParameterNullabilityProbeTest {

  private fun infer(sql: String): Map<Int, InferredParameter> = inferrer.inferParameterInfo(sql, probe.classify(sql))

  private fun notNull(sql: String): Map<Int, Boolean> = inferrer.resolveParameterNotNull(infer(sql), catalog)

  private fun parameter(sql: String, number: Int): InferredParameter = infer(sql).getValue(number)

  private fun assignment(column: String, table: String = "t", typeFromColumn: Boolean = true): InferredParameter {
    val target = ColumnReference("public", table, column)
    return InferredParameter(column, ParameterNullability.Inherit(target), identity = target.takeIf { typeFromColumn })
  }

  private fun comparedWith(column: String, assignedColumn: String, nullability: ParameterNullability? = null) =
    InferredParameter(
      column,
      nullability ?: ParameterNullability.Inherit(ColumnReference("public", "t", assignedColumn)),
      identity = ColumnReference("public", "t", column),
    )

  private fun nullableValue(column: String, table: String = "t", typeFromColumn: Boolean = true) = InferredParameter(
    column,
    ParameterNullability.Nullable,
    identity = ColumnReference("public", table, column).takeIf { typeFromColumn },
  )

  private fun elementValue(column: String) = InferredParameter(column, ParameterNullability.Nullable)

  private fun comparison(column: String, table: String = "t") =
    InferredParameter(column, ParameterNullability.NonNull, identity = ColumnReference("public", table, column))

  @Nested
  inner class IssueCases {
    @Test
    fun `a SET assignment after a subquery with its own WHERE inherits the column nullability`() {
      val sql = "UPDATE t SET a = (SELECT max(x) FROM u WHERE u.k = 1), b = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("b"))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true))
    }

    @Test
    fun `a coalesce assignment after a subquery with its own WHERE is always nullable`() {
      val sql = "UPDATE t SET a = (SELECT max(x) FROM u WHERE u.k = 1), c = coalesce(?, c) WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(nullableValue("c"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true))
    }

    @Test
    fun `a JOIN ON comparison in a SELECT does not inherit nullability`() {
      val sql = "SELECT * FROM a JOIN b ON b.x = ? WHERE a.id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(comparison("x", "b"))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id", "a"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to true, 2 to true))
    }

    @Test
    fun `only the SET assignment of an UPDATE with FROM and JOIN inherits nullability`() {
      val sql = "UPDATE t SET a = ? FROM u JOIN v ON v.x = ? WHERE t.id = u.id AND u.y = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("a"))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("x", "v"))
      assertThat(parameter(sql, 3)).isEqualTo(comparison("y", "u"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true, 3 to true))
    }

    @Test
    fun `comparisons in subqueries of a SELECT list and its WHERE are both non-inheriting`() {
      val sql = "SELECT (SELECT x FROM u WHERE u.id = ?) FROM t WHERE t.id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(comparison("id", "u"))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id", "t"))
    }

    @Test
    fun `a WITH query before an UPDATE does not move the SET boundary`() {
      val sql = "WITH x AS (SELECT 1 FROM u WHERE u.k = 1) UPDATE t SET b = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("b"))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id"))
    }

    @Test
    fun `an IS DISTINCT FROM in the SET clause does not end the SET clause`() {
      val sql = "UPDATE t SET flag = (b IS DISTINCT FROM c), d = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("d"))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id"))
    }
  }

  @Nested
  inner class SimpleStatements {
    @Test
    fun `SET parameters inherit nullability and WHERE parameters do not`() {
      val sql = "UPDATE users SET name = ?, bio = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("name", "users"))
      assertThat(parameter(sql, 2)).isEqualTo(assignment("bio", "users"))
      assertThat(parameter(sql, 3)).isEqualTo(comparison("id", "users"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to true, 2 to false, 3 to true))
    }

    @Test
    fun `SET parameters inherit nullability in a multi-line UPDATE without WHERE`() {
      val sql = "UPDATE users\nSET\n  name = ?,\n  bio = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("name", "users"))
      assertThat(parameter(sql, 2)).isEqualTo(assignment("bio", "users"))
    }

    @Test
    fun `the assigned column names a parameter written as email = ? in SET`() {
      val sql = "UPDATE users SET email = ?, name = ? WHERE id = ?"

      assertThat(infer(sql).mapValues { it.value.name }).isEqualTo(mapOf(1 to "email", 2 to "name", 3 to "id"))
    }

    @Test
    fun `an unquoted mixed-case UPDATE SET column and table fold to their PostgreSQL logical names`() {
      assertThat(parameter("UPDATE Users SET Bio = ?", 1)).isEqualTo(assignment("bio", "users"))
    }

    @Test
    fun `a table name containing a non-ASCII identifier character is the assignment table`() {
      assertThat(parameter("UPDATE t€ SET a = ?", 1)).isEqualTo(assignment("a", "t€"))
    }
  }

  @Nested
  inner class MergeAndOnConflict {
    @Test
    fun `a MERGE action assignment inherits and the WHEN AND comparison does not`() {
      val sql = "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED AND t.x = ? THEN UPDATE SET a = ? " +
        "WHEN NOT MATCHED THEN DO NOTHING"

      assertThat(parameter(sql, 1)).isEqualTo(comparison("x"))
      assertThat(parameter(sql, 2)).isEqualTo(assignment("a"))
    }

    @Test
    fun `an array constructor in a MERGE action does not turn the next WHEN into an assignment`() {
      val sql = "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED AND t.a > 0 THEN UPDATE SET arr = ARRAY[1, 2] " +
        "WHEN MATCHED AND t.x = ? THEN DELETE"

      assertThat(parameter(sql, 1)).isEqualTo(comparison("x"))
    }

    @Test
    fun `an ON CONFLICT DO UPDATE assignment names its column and table`() {
      val sql = "INSERT INTO t(id, a) VALUES (?, 1) ON CONFLICT (id) DO UPDATE SET a = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("id"))
      assertThat(parameter(sql, 2)).isEqualTo(assignment("a"))
    }

    @Test
    fun `a data-modifying CTE and the outer UPDATE each name their own table`() {
      val sql = "WITH w AS (UPDATE u SET c = ? RETURNING id) UPDATE t SET c = ? FROM w WHERE t.id = w.id"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("c", "u"))
      assertThat(parameter(sql, 2)).isEqualTo(assignment("c", "t"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true))
    }
  }

  @Nested
  inner class ValuesContainingTheParameter {
    @Test
    fun `a parameter inside a strict arithmetic assignment value inherits the column nullability`() {
      assertThat(parameter("UPDATE t SET a = ? + 1 WHERE id = ?", 1)).isEqualTo(assignment("a", typeFromColumn = false))
    }

    @Test
    fun `a comparison that is the whole boolean assignment value inherits the column nullability`() {
      assertThat(parameter("UPDATE t SET flag = (b = ?) WHERE id = ?", 1))
        .isEqualTo(comparedWith("b", assignedColumn = "flag"))
    }

    @Test
    fun `a comparison under a strict cast inherits the column nullability`() {
      assertThat(parameter("UPDATE t SET a = ((c = ?)::int) WHERE id = ?", 1))
        .isEqualTo(comparedWith("c", assignedColumn = "a"))
    }

    @Test
    fun `a comparison inside a CASE WHEN condition rejects null and the next assignment inherits`() {
      val sql = "UPDATE t SET a = CASE WHEN b = ? THEN 1 END, c = ? WHERE id = ?"

      assertThat(parameter(sql, 1))
        .isEqualTo(comparedWith("b", assignedColumn = "a", ParameterNullability.NonNull))
      assertThat(notNull(sql).getValue(1)).isTrue()
      assertThat(parameter(sql, 2)).isEqualTo(assignment("c"))
    }

    @Test
    fun `a comparison as the first coalesce argument makes the parameter always nullable`() {
      assertThat(parameter("UPDATE t SET flag = coalesce(b = ?, true) WHERE id = ?", 1))
        .isEqualTo(comparedWith("b", assignedColumn = "flag", ParameterNullability.Nullable))
    }

    @Test
    fun `a comparison as the last coalesce argument inherits the column nullability`() {
      assertThat(parameter("UPDATE t SET flag = coalesce(null, b = ?) WHERE id = ?", 1))
        .isEqualTo(comparedWith("b", assignedColumn = "flag"))
    }

    @Test
    fun `a coalesce assignment is always nullable and the next assignment inherits`() {
      val sql = "UPDATE t SET a = coalesce(?, a), b = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(nullableValue("a"))
      assertThat(parameter(sql, 2)).isEqualTo(assignment("b"))
    }

    @Test
    fun `the last coalesce argument inherits the column nullability`() {
      val sql = "UPDATE t SET a = coalesce(b, ?) WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("a"))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id"))
    }

    @Test
    fun `a coalesce on a varchar column that PostgreSQL wraps in a length coercion is always nullable`() {
      assertThat(
        parameter("UPDATE t SET vc = coalesce(?, vc) WHERE id = ?", 1),
      ).isEqualTo(nullableValue("vc"))
    }

    @Test
    fun `a coalesce under an operator is always nullable`() {
      val sql = "UPDATE t SET a = coalesce(?, 0) + 1, b = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(nullableValue("a", typeFromColumn = false))
      assertThat(parameter(sql, 2)).isEqualTo(assignment("b"))
    }

    @Test
    fun `a parameter passed to a non-strict function inherits the column nullability`() {
      val sql = "UPDATE t SET note = concat(?::text, 'x'), b = ? WHERE id = ?"

      assertThat(parameter(sql, 1))
        .isEqualTo(
          InferredParameter(
            "concat_param1",
            ParameterNullability.Inherit(ColumnReference("public", "t", "note")),
            null,
          ),
        )
      assertThat(parameter(sql, 2)).isEqualTo(assignment("b"))
    }

    @Test
    fun `a parameter passed to a strict function keeps the function argument name and the target column`() {
      val sql = "UPDATE t SET note = upper(?) WHERE id = ?"

      assertThat(parameter(sql, 1))
        .isEqualTo(
          InferredParameter("upper_param1", ParameterNullability.Inherit(ColumnReference("public", "t", "note")), null),
        )
    }

    @Test
    fun `a unicode-escaped quoted target inherits nullability`() {
      assertThat(parameter("UPDATE t SET U&\"a\" = ? WHERE id = ?", 1)).isEqualTo(assignment("a"))
    }

    @Test
    fun `a unicode-escaped quoted target is always nullable under coalesce`() {
      assertThat(
        parameter("UPDATE t SET U&\"a\" = coalesce(?, a) WHERE id = ?", 1),
      ).isEqualTo(nullableValue("a"))
    }

    @Test
    fun `commas in an array constructor do not make later parameters assignments`() {
      assertThat(parameter("UPDATE t SET tags = ARRAY['x', 'y'] WHERE id = ?", 1)).isEqualTo(comparison("id"))
      assertThat(parameter("UPDATE t SET arr = ARRAY[1, 2] WHERE id = ?", 1)).isEqualTo(comparison("id"))
      assertThat(
        parameter("UPDATE t SET arr = ARRAY[t.x, t.y] FROM u WHERE u.k = ?", 1),
      ).isEqualTo(comparison("k", "u"))
    }

    @Test
    fun `an assignment after an array constructor still inherits`() {
      val sql = "UPDATE t SET arr = ARRAY[1, 2], b = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("b"))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id"))
    }

    @Test
    fun `a composite field assignment names the composite column and is always nullable`() {
      val sql = "UPDATE t SET pair_col.f = ?, b = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(elementValue("pair_col"))
      assertThat(parameter(sql, 2)).isEqualTo(assignment("b"))
    }

    @Test
    fun `a collated parameter inherits the column nullability`() {
      assertThat(parameter("UPDATE t SET collated = ? COLLATE \"C\" WHERE id = ?", 1)).isEqualTo(assignment("collated"))
    }

    @Test
    fun `a parameter cast to varchar for a text column inherits the column nullability`() {
      val sql = "UPDATE t SET text_col = ?::varchar, text_required = ?::varchar WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("text_col", typeFromColumn = false))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true, 3 to true))
    }

    @Test
    fun `a parameter converted by a non-strict user cast function inherits the column nullability`() {
      val sql = "UPDATE t SET w = ?::text WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("w", typeFromColumn = false))
    }
  }

  @Nested
  inner class TargetColumnTypes {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
      "vc, vc_required",
      "amount, amount_required",
      "code, code_required",
      "moment, moment_required",
      "rank, rank_required",
      "mood, mood_required",
      "labels, labels_required",
    )
    fun `a parameter inherits the nullability of a column PostgreSQL coerces the value to`(
      nullableColumn: String,
      requiredColumn: String,
    ) {
      val sql = "UPDATE t SET $nullableColumn = ?, $requiredColumn = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment(nullableColumn))
      assertThat(parameter(sql, 2)).isEqualTo(assignment(requiredColumn))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true, 3 to true))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["vc", "amount", "code", "moment", "rank", "mood", "labels"])
    fun `a coalesce on a coerced column type is always nullable`(column: String) {
      val sql = "UPDATE t SET $column = coalesce(?, $column) WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(nullableValue(column))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["vc", "amount", "code", "moment", "rank", "mood", "labels"])
    fun `an ON CONFLICT assignment to a coerced column type inherits the nullability`(column: String) {
      val sql = "INSERT INTO t(id) VALUES (?) ON CONFLICT (id) DO UPDATE SET $column = ?"

      assertThat(parameter(sql, 2)).isEqualTo(assignment(column))
    }
  }

  @Nested
  inner class MoreThanOneHundredParameters {
    @ParameterizedTest(name = "{0} placeholders")
    @ValueSource(ints = [101])
    fun `the first parameter of a statement over the function argument limit inherits the column nullability`(
      count: Int,
    ) {
      val nullableSql = "UPDATE t SET a = ? WHERE id IN (${placeholders(count - 1)})"
      val requiredSql = "UPDATE t SET c = ? WHERE id IN (${placeholders(count - 1)})"

      assertThat(parameter(nullableSql, 1)).isEqualTo(assignment("a"))
      assertThat(notNull(nullableSql).getValue(1)).isFalse()
      assertThat(notNull(requiredSql).getValue(1)).isTrue()
    }

    @ParameterizedTest(name = "assignment after {0} placeholders")
    @ValueSource(ints = [99, 100, 200])
    fun `an assignment value in a later chunk keeps its original parameter number`(before: Int) {
      val nullableSql = "UPDATE t SET b = (SELECT max(x) FROM u WHERE u.k IN (${placeholders(before)})), a = ? " +
        "WHERE id = ?"
      val requiredSql = nullableSql.replace("a = ?", "c = ?")

      assertThat(parameter(nullableSql, before + 1)).isEqualTo(assignment("a"))
      assertThat(parameter(nullableSql, before + 2)).isEqualTo(comparison("id"))
      assertThat(notNull(nullableSql).getValue(before + 1)).isFalse()
      assertThat(notNull(requiredSql).getValue(before + 1)).isTrue()
      assertThat(infer(nullableSql).filterKeys { it <= before }.values.map { it.name }.toSet()).isEqualTo(setOf(null))
    }

    @Test
    fun `a coalesce value in a later chunk stays always nullable`() {
      val sql = "UPDATE t SET b = (SELECT max(x) FROM u WHERE u.k IN (${placeholders(150)})), c = coalesce(?, c)"

      assertThat(parameter(sql, 151)).isEqualTo(nullableValue("c"))
    }
  }

  @Nested
  inner class AssignmentValues {
    @ParameterizedTest(name = "{0}")
    @MethodSource("norm.generator.ParameterNullabilityProbeTest#inheritingCases")
    fun `a parameter written to a column inherits the column nullability`(
      assignmentTemplate: String,
      nullableColumn: String,
      requiredColumn: String,
    ) {
      val nullableSql = "UPDATE t SET ${assignmentTemplate.format(nullableColumn)} WHERE id = ?"
      val requiredSql = "UPDATE t SET ${assignmentTemplate.format(requiredColumn)} WHERE id = ?"

      assertThat(infer(nullableSql).getValue(1).nullability).isNotNull().isInstanceOf<ParameterNullability.Inherit>()
      assertThat(notNull(nullableSql).getValue(1)).isFalse()
      assertThat(notNull(requiredSql).getValue(1)).isTrue()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("norm.generator.ParameterNullabilityProbeTest#nullableCases")
    fun `a parameter that PostgreSQL accepts null for is nullable on every column`(
      assignmentTemplate: String,
      nullableColumn: String,
      requiredColumn: String,
    ) {
      val nullableSql = "UPDATE t SET ${assignmentTemplate.format(nullableColumn)} WHERE id = ?"
      val requiredSql = "UPDATE t SET ${assignmentTemplate.format(requiredColumn)} WHERE id = ?"

      assertThat(notNull(nullableSql).getValue(1)).isFalse()
      assertThat(notNull(requiredSql).getValue(1)).isFalse()
    }

    @Test
    fun `a CASE condition parameter rejects null`() {
      val sql = "UPDATE t SET a = CASE WHEN b = ? THEN 1 END WHERE id = ?"

      assertThat(notNull(sql).getValue(1)).isTrue()
    }

    @Test
    fun `a CASE branch parameter inherits the column nullability`() {
      val nullableSql = "UPDATE t SET a = CASE WHEN flag THEN ? ELSE 1 END WHERE id = ?"
      val requiredSql = "UPDATE t SET c = CASE WHEN flag THEN ? ELSE 1 END WHERE id = ?"

      assertThat(notNull(nullableSql).getValue(1)).isFalse()
      assertThat(notNull(requiredSql).getValue(1)).isTrue()
    }

    @Test
    fun `a subquery parameter in an assignment value does not inherit the column nullability`() {
      val sql = "UPDATE t SET a = (SELECT max(x) FROM u WHERE u.k = ?) WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(comparison("k", "u"))
      assertThat(notNull(sql).getValue(1)).isTrue()
    }

    @Test
    fun `a parameter in the operand of an IN sublink inherits the column nullability`() {
      val sql = "UPDATE t SET flag = (? IN (SELECT u.k FROM u WHERE u.id = ?)) WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("flag", typeFromColumn = false))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id", "u"))
    }

    @Test
    fun `GREATEST and NULLIF arguments inherit the column nullability`() {
      assertThat(notNull("UPDATE t SET a = greatest(?, a) WHERE id = ?").getValue(1)).isFalse()
      assertThat(notNull("UPDATE t SET c = greatest(?, c) WHERE id = ?").getValue(1)).isTrue()
      assertThat(notNull("UPDATE t SET a = nullif(?, 0) WHERE id = ?").getValue(1)).isFalse()
      assertThat(notNull("UPDATE t SET c = nullif(?, 0) WHERE id = ?").getValue(1)).isTrue()
    }

    @Test
    fun `a function that raises on null still inherits the column nullability`() {
      assertThat(notNull("UPDATE t SET arr = array_fill(1, ?) WHERE id = ?").getValue(1)).isFalse()
      assertThat(notNull("UPDATE t SET arr_required = array_fill(1, ?) WHERE id = ?").getValue(1)).isTrue()
    }

    @Test
    fun `array append and concat arguments inherit the column nullability`() {
      assertThat(notNull("UPDATE t SET arr = arr || ? WHERE id = ?").getValue(1)).isFalse()
      assertThat(notNull("UPDATE t SET arr_required = arr_required || ? WHERE id = ?").getValue(1)).isTrue()
      assertThat(notNull("UPDATE t SET note = concat(?::text, 'x') WHERE id = ?").getValue(1)).isFalse()
    }

    @Test
    fun `a coalesce argument that is not last accepts null and the last inherits`() {
      val sql = "UPDATE t SET c = coalesce(?, ?, 0) WHERE id = ?"
      val inherited = "UPDATE t SET c = coalesce(a, ?) WHERE id = ?"

      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to false, 3 to true))
      assertThat(notNull(inherited).getValue(1)).isTrue()
    }

    @Test
    fun `a coalesce in a NOT NULL domain value stays nullable`() {
      assertThat(notNull("UPDATE t SET nn = coalesce(?, 1) WHERE id = ?").getValue(1)).isFalse()
    }

    @Test
    fun `a parameter of a NOT NULL domain type rejects null, also through a domain over a NOT NULL domain`() {
      assertThat(notNull("UPDATE t SET nn = ? WHERE id = ?").getValue(1)).isTrue()
      assertThat(notNull("UPDATE t SET d2col = ? WHERE id = ?").getValue(1)).isTrue()
    }
  }

  @Nested
  inner class ConditionsAndSubscripts {
    @Test
    fun `a JOIN ON coalesce accepts null`() {
      val sql = "SELECT t.id FROM t JOIN u ON u.k = coalesce(?, u.k)"

      assertThat(notNull(sql).getValue(1)).isFalse()
      assertThat(parameter(sql, 1))
        .isEqualTo(
          InferredParameter("k", ParameterNullability.Nullable, identity = ColumnReference("public", "u", "k")),
        )
    }

    @Test
    fun `a WHERE comparison rejects null and an IS NULL operand accepts it`() {
      val sql = "SELECT * FROM t WHERE a = ? OR ?::int IS NULL"

      assertThat(notNull(sql)).isEqualTo(mapOf(1 to true, 2 to false))
    }

    @Test
    fun `an array constructor in a condition keeps its elements non-null`() {
      val sql = "SELECT * FROM t WHERE a = ANY(ARRAY[?::int, ?::int])"

      assertThat(infer(sql).values.all { it.nullability == ParameterNullability.NonNull }).isTrue()
      assertThat(probe.classify(sql).values.map { it.nullability }.toSet())
        .isEqualTo(setOf(ParameterNullability.NonNull))
    }

    @Test
    fun `an assigned subscript index rejects null on a nullable column`() {
      val sql = "UPDATE t SET arr[?] = 5 WHERE id = ?"

      assertThat(probe.classify(sql).getValue(1).nullability).isEqualTo(ParameterNullability.NonNull)
      assertThat(notNull(sql).getOrDefault(1, true)).isTrue()
    }

    @Test
    fun `an assigned array element accepts null even in a NOT NULL array`() {
      assertThat(notNull("UPDATE t SET arr_required[1] = ? WHERE id = ?").getValue(1)).isFalse()
    }

    @Test
    fun `an array constructor element accepts null in a NOT NULL array`() {
      assertThat(notNull("UPDATE t SET tags_required = ARRAY[?::text, 'x'] WHERE id = ?").getValue(1)).isFalse()
    }

    @Test
    fun `a row constructor element accepts null in a NOT NULL composite column`() {
      assertThat(notNull("UPDATE t SET pair_required = ROW(?::int, 1) WHERE id = ?").getValue(1)).isFalse()
    }
  }

  @Nested
  inner class CompositeFieldAssignments {
    @Test
    fun `a field of a NOT NULL composite column accepts null`() {
      val sql = "UPDATE t SET pair_required.f = ? WHERE id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(elementValue("pair_required"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true))
    }

    @Test
    fun `a field of a nullable composite column accepts null`() {
      assertThat(notNull("UPDATE t SET pair_col.f = ? WHERE id = ?").getValue(1)).isFalse()
    }

    @Test
    fun `a field whose type is a NOT NULL domain does not accept null`() {
      val sql = "UPDATE t SET np.f = ?, np.g = ? WHERE id = ?"

      assertThat(notNull(sql)).isEqualTo(mapOf(1 to true, 2 to false, 3 to true))
    }

    @Test
    fun `a field whose type is a domain over a NOT NULL domain does not accept null`() {
      assertThat(notNull("UPDATE t SET dp.f = ? WHERE id = ?").getValue(1)).isTrue()
    }

    @Test
    fun `a coalesce on a NOT NULL domain field stays nullable`() {
      assertThat(notNull("UPDATE t SET np.f = coalesce(?, 1) WHERE id = ?").getValue(1)).isFalse()
    }

    @Test
    fun `the generated type of a composite field parameter is the field type`() {
      val query = JdbcAnalyzer(connection).analyzeQuery(
        ParsedQuery("setField", Command.EXEC, "UPDATE t SET pair_col.f = ? WHERE id = ?", emptyList()),
        catalog,
      )

      assertThat(query.params.first().column!!.type.name).isEqualTo("int4")
    }

    @Test
    fun `the generated type of a parameter inside an expression is its own type`() {
      val query = JdbcAnalyzer(connection).analyzeQuery(
        ParsedQuery("setFlag", Command.EXEC, "UPDATE t SET flag = (b = ?) WHERE id = ?", emptyList()),
        catalog,
      )

      assertThat(query.params.first().column!!.type.name).isEqualTo("int4")
    }
  }

  @Nested
  inner class OperandsAndFreePositions {
    @Test
    fun `a constructor operand does not make its elements nullable in a NOT NULL column`() {
      assertThat(notNull("UPDATE t SET flag_required = 1 = ANY(ARRAY[?, 2]) WHERE id = ?").getValue(1)).isTrue()
      assertThat(notNull("UPDATE t SET c = (ARRAY[?, 2])[1] WHERE id = ?").getValue(1)).isTrue()
    }

    @Test
    fun `a constructor that is the written value makes its elements nullable in a NOT NULL column`() {
      assertThat(notNull("UPDATE t SET arr_required = ARRAY[?, 2] WHERE id = ?").getValue(1)).isFalse()
    }

    @Test
    fun `a coalesce in a SELECT list accepts null`() {
      assertThat(notNull("SELECT id, a = coalesce(?, a) AS m FROM t").getValue(1)).isFalse()
      assertThat(notNull("SELECT coalesce(?::int, a) FROM t").getValue(1)).isFalse()
    }

    @Test
    fun `a coalesce in an ORDER BY accepts null`() {
      assertThat(notNull("SELECT id FROM t ORDER BY a = coalesce(?, a)").getValue(1)).isFalse()
    }

    @Test
    fun `a NULL test in a SELECT list accepts null`() {
      assertThat(notNull("SELECT ?::int IS NULL").getValue(1)).isFalse()
    }
  }

  @Nested
  inner class ComparisonsInEveryPosition {
    private fun identity(table: String, column: String, schemaName: String = "public") =
      ColumnReference(schemaName, table, column)

    @Test
    fun `a column of a CTE names the parameter and gives it no identity`() {
      val sql = "WITH w AS (SELECT a AS renamed FROM t) SELECT * FROM w WHERE w.renamed = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("renamed")
      assertThat(parameter(sql, 1).identity).isNull()
    }

    @Test
    fun `a column of a subquery alias names the parameter and gives it no identity`() {
      val sql = "SELECT * FROM (SELECT a AS renamed FROM t) s WHERE s.renamed = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("renamed")
      assertThat(parameter(sql, 1).identity).isNull()
    }

    @Test
    fun `a JOIN USING column resolves to the base relation`() {
      val sql = "SELECT * FROM t JOIN u USING (a) WHERE a = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("a")
      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "a"))
    }

    @Test
    fun `a coalesce fallback compared with a column is nullable and takes the column identity`() {
      val sql = "SELECT * FROM t WHERE a = coalesce(?, a)"

      assertThat(parameter(sql, 1).name).isEqualTo("a")
      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "a"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false))
    }

    @Test
    fun `the compared column can be on the left and a pattern operator names its operand`() {
      assertThat(parameter("SELECT * FROM t WHERE ? = a", 1).name).isEqualTo("a")
      assertThat(parameter("SELECT * FROM t WHERE note LIKE ?", 1).name).isEqualTo("note")
      assertThat(parameter("SELECT * FROM t WHERE note LIKE ?", 1).identity).isEqualTo(identity("t", "note"))
    }

    @Test
    fun `a comparison in a SELECT list names the parameter and gives it no nullability`() {
      val sql = "SELECT a = ? FROM t"

      assertThat(parameter(sql, 1).name).isEqualTo("a")
      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "a"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to true))
    }

    @Test
    fun `a correlated comparison against an outer column resolves through the enclosing query`() {
      val sql = "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM u WHERE t.k = ?)"

      assertThat(parameter(sql, 1).name).isEqualTo("k")
      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "k"))
    }

    @Test
    fun `a comparison two query levels below its column resolves against that query's range table`() {
      val sql = "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM u WHERE EXISTS (SELECT 1 FROM v WHERE t.k = ?))"

      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "k"))
    }

    @Test
    fun `a subquery in an assignment value resolves its own range table`() {
      val sql = "UPDATE t SET a = (SELECT max(x) FROM u WHERE u.k = ?) WHERE id = ?"

      assertThat(parameter(sql, 1).identity).isEqualTo(identity("u", "k"))
      assertThat(parameter(sql, 2).identity).isEqualTo(identity("t", "id"))
    }

    @Test
    fun `a comparison in the FROM list of an UPDATE names the joined relation's column`() {
      val sql = "UPDATE t SET b = ? FROM u WHERE u.k = ?"

      assertThat(parameter(sql, 2).name).isEqualTo("k")
      assertThat(parameter(sql, 2).identity).isEqualTo(identity("u", "k"))
    }

    @Test
    fun `a MERGE action condition names the source relation's column`() {
      val sql = "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED AND s.x = ? THEN UPDATE SET a = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("x")
      assertThat(parameter(sql, 1).identity).isEqualTo(identity("s", "x"))
    }
  }

  @Nested
  inner class SystemColumnsLikeEscapeAndJoinEntries {
    private fun identity(table: String, column: String) = ColumnReference("public", table, column)

    @Test
    fun `a system column names the parameter and gives it no identity`() {
      val sql = "SELECT * FROM t WHERE ctid = ?"
      val column = JdbcAnalyzer(connection)
        .analyzeQuery(ParsedQuery("q", Command.MANY, sql, emptyList()), catalog).params.single().column!!

      assertThat(parameter(sql, 1).name).isEqualTo("ctid")
      assertThat(column.name).isEqualTo("ctid")
      assertThat(column.table).isNull()
      assertThat(column.originalName).isEqualTo("")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "SELECT * FROM t WHERE note LIKE ? ESCAPE '\\'",
        "SELECT * FROM t WHERE note NOT LIKE ? ESCAPE '\\'",
        "SELECT * FROM t WHERE note ILIKE ? ESCAPE '\\'",
      ],
    )
    fun `the pattern of LIKE with ESCAPE names the parameter after the compared column`(sql: String) {
      assertThat(parameter(sql, 1).name).isEqualTo("note")
      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "note"))
    }

    @Test
    fun `a like_escape of another schema does not keep the naming`() {
      val sql = "SELECT * FROM t WHERE note = public.like_escape(?, 'x')"

      assertThat(parameter(sql, 1).identity).isNull()
    }

    @Test
    fun `a LEFT JOIN USING column resolves to the left base relation`() {
      val sql = "SELECT * FROM t LEFT JOIN u USING (id) WHERE id = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("id")
      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "id"))
    }

    @Test
    fun `a chain of LEFT JOIN USING columns resolves to the first base relation`() {
      val sql = "SELECT * FROM t LEFT JOIN u USING (id) LEFT JOIN v USING (id) WHERE id = ?"

      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "id"))
    }

    @Test
    fun `a FULL JOIN USING column keeps its name and has no identity`() {
      val sql = "SELECT * FROM t FULL JOIN u USING (id) WHERE id = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("id")
      assertThat(parameter(sql, 1).identity).isNull()
    }

    @Test
    fun `a grouped column resolves through its grouping expression`() {
      val sql = "SELECT a FROM t GROUP BY a HAVING a = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("a")
      assertThat(parameter(sql, 1).identity).isEqualTo(identity("t", "a"))
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(
      delimiter = '|',
      value = [
        "WITH c AS (SELECT a FROM t) SELECT a FROM c GROUP BY a HAVING a = ?|a",
        "SELECT x.sa FROM (SELECT a AS sa FROM t) x GROUP BY x.sa HAVING x.sa = ?|sa",
        "SELECT id FROM t FULL JOIN u USING (id) GROUP BY id HAVING id = ?|id",
        "SELECT n FROM generate_series(1, 3) g(n) GROUP BY n HAVING n = ?|n",
      ],
    )
    fun `a grouped column of a CTE, subquery, merged join or function keeps its name and has no identity`(
      sql: String,
      name: String,
    ) {
      val column = JdbcAnalyzer(connection)
        .analyzeQuery(ParsedQuery("q", Command.MANY, sql, emptyList()), catalog).params.single().column!!

      assertThat(parameter(sql, 1).name).isEqualTo(name)
      assertThat(parameter(sql, 1).identity).isNull()
      assertThat(column.name).isEqualTo(name)
    }

    @Test
    fun `a grouped expression leaves the parameter unnamed`() {
      val arithmetic = "SELECT count(*) AS n FROM t GROUP BY a + 1 HAVING a + 1 = ?"
      val function = "SELECT count(*) AS n FROM t GROUP BY lower(note) HAVING lower(note) = ?"

      for (sql in listOf(arithmetic, function)) {
        val column = JdbcAnalyzer(connection)
          .analyzeQuery(ParsedQuery("q", Command.MANY, sql, emptyList()), catalog).params.single().column!!

        assertThat(parameter(sql, 1).name).isNull()
        assertThat(parameter(sql, 1).identity).isNull()
        assertThat(column.name).isEqualTo("p1")
      }
    }
  }

  @Nested
  inner class InsertValues {
    @Test
    fun `every row of a multi-row VALUES is named after its target column and inherits its nullability`() {
      val sql = "INSERT INTO t(a, c) VALUES (?, ?), (?, ?), (?, ?)"

      assertThat(infer(sql).mapValues { it.value.name })
        .isEqualTo(mapOf(1 to "a", 2 to "c", 3 to "a", 4 to "c", 5 to "a", 6 to "c"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true, 3 to false, 4 to true, 5 to false, 6 to true))
      assertThat(parameter(sql, 5).identity).isEqualTo(ColumnReference("public", "t", "a"))
    }

    @Test
    fun `an INSERT without a column list names each parameter after its column`() {
      val sql = "INSERT INTO v VALUES (?, ?)"

      assertThat(infer(sql).mapValues { it.value.name }).isEqualTo(mapOf(1 to "id", 2 to "x"))
    }

    @Test
    fun `a parameter inside a non-cast function keeps the function argument name and has no column identity`() {
      val sql = "INSERT INTO t(note, a) VALUES (upper(?), ?)"

      assertThat(parameter(sql, 1).name).isEqualTo("upper_param1")
      assertThat(parameter(sql, 1).identity).isNull()
      assertThat(parameter(sql, 2).identity).isEqualTo(ColumnReference("public", "t", "a"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to false))
    }

    @Test
    fun `an array element of an INSERT value has no identity and accepts null`() {
      val sql = "INSERT INTO t(arr_required) VALUES (ARRAY[?, 2])"

      assertThat(parameter(sql, 1).name).isEqualTo("arr_required")
      assertThat(parameter(sql, 1).identity).isNull()
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false))
    }
  }

  @Nested
  inner class ColumnNamesAsPostgreSqlSpellsThem {
    @Test
    fun `a quoted column name with an embedded quote, comma or parenthesis names the parameter`() {
      assertThat(parameter("SELECT * FROM q WHERE \"a\"\"b\" = ? AND \"a,b\" = ?", 1).name).isEqualTo("a\"b")
      assertThat(parameter("SELECT * FROM q WHERE \"a\"\"b\" = ? AND \"a,b\" = ?", 2).name).isEqualTo("a,b")
      assertThat(infer("INSERT INTO q(\"a,b\", \"c)d\") VALUES (?, ?)").mapValues { it.value.name })
        .isEqualTo(mapOf(1 to "a,b", 2 to "c)d"))
    }

    @Test
    fun `a column name containing a dollar sign names the parameter`() {
      assertThat(parameter("SELECT * FROM q WHERE my\$col = ?", 1).name).isEqualTo("my\$col")
    }

    @Test
    fun `a question mark in a string literal of the VALUES list is not a parameter`() {
      val sql = "INSERT INTO t(note, b) VALUES ('?', ?)"

      assertThat(infer(sql).mapValues { it.value.name }).isEqualTo(mapOf(1 to "b"))
    }

    @Test
    fun `unquoted names fold to their logical form`() {
      assertThat(parameter("INSERT INTO Users(Bio) VALUES (?)", 1)).isEqualTo(assignment("bio", "users"))
    }
  }

  @Nested
  inner class NoOpinionFromTheProbe {
    private fun analyzed(sql: String) =
      JdbcAnalyzer(limitedConnection).analyzeQuery(ParsedQuery("q", Command.EXEC, sql, emptyList()), catalog)

    @Test
    fun `a statement the probe cannot compile gets positional names and non-null parameters`() {
      val sql = "UPDATE t SET b = ? WHERE a = ?"

      assertThat(inferrer.inferParameterInfo(sql, limitedProbe.classify(sql))).isEmpty()
      assertThat(analyzed(sql).params.map { it.column!!.name to it.column!!.notNull })
        .isEqualTo(listOf("p1" to true, "p2" to true))
    }

    @Test
    fun `a statement the probe cannot compile keeps the function argument name`() {
      val sql = "UPDATE t SET note = upper(?) WHERE a = ?"

      assertThat(inferrer.inferParameterInfo(sql, limitedProbe.classify(sql)).mapValues { it.value.name })
        .isEqualTo(mapOf(1 to "upper_param1"))
      assertThat(analyzed(sql).params.map { it.column!!.name to it.column!!.notNull })
        .isEqualTo(listOf("upper_param1" to true, "p2" to true))
    }
  }

  @Nested
  inner class ColumnOverrides {
    private fun analyzedColumn(sql: String, parameter: Int, queryCatalog: Catalog = catalog): Column =
      JdbcAnalyzer(connection)
        .analyzeQuery(ParsedQuery("q", Command.EXEC, sql, emptyList()), queryCatalog)
        .params[parameter - 1].column!!

    private fun kotlinType(sql: String, parameter: Int, queryCatalog: Catalog = catalog): String =
      typeRepository.resolveColumnType(analyzedColumn(sql, parameter, queryCatalog)).toString()

    @Test
    fun `a parameter of another type than the column keeps its own Kotlin type`() {
      assertThat(kotlinType("UPDATE t SET a = abs(?) WHERE id = ?", 1)).isEqualTo("kotlin.Double?")
      assertThat(kotlinType("UPDATE t SET flag = ? IS DISTINCT FROM 1 WHERE id = ?", 1)).isEqualTo("kotlin.Int?")
    }

    @Test
    fun `an increment of the column's type keeps its own Kotlin type`() {
      assertThat(kotlinType("UPDATE t SET a = a + 1 + ? WHERE id = ?", 1)).isEqualTo("kotlin.Int?")
    }

    @Test
    fun `a coalesce fallback of a domain column keeps the domain override`() {
      assertThat(kotlinType("UPDATE t SET em = coalesce(?, em) WHERE id = ?", 1)).isEqualTo("com.example.Over_em?")
    }

    @Test
    fun `a coalesce fallback of a NOT NULL domain column keeps the domain and the override`() {
      assertThat(kotlinType("UPDATE t SET dom = coalesce(?, dom) WHERE id = ?", 1)).isEqualTo("p.D1?")
      assertThat(
        kotlinType("UPDATE t SET domo = coalesce(?, domo) WHERE id = ?", 1),
      ).isEqualTo("com.example.Over_domo?")
    }

    @Test
    fun `a MERGE or ON CONFLICT fallback of a NOT NULL domain column keeps the domain`() {
      val merge = "MERGE INTO t USING u ON t.id = u.id WHEN MATCHED THEN UPDATE SET dom = coalesce(?, t.dom)"
      val conflict = "INSERT INTO t(id) VALUES (?) ON CONFLICT (id) DO UPDATE SET dom = coalesce(?, t.dom)"

      assertThat(kotlinType(merge, 1)).isEqualTo("p.D1?")
      assertThat(kotlinType(conflict, 2)).isEqualTo("p.D1?")
    }

    @Test
    fun `an increment on a NOT NULL domain column has the plain type`() {
      assertThat(kotlinType("UPDATE t SET dom = ? + 1 WHERE id = ?", 1)).isEqualTo("kotlin.Int")
      assertThat(kotlinType("UPDATE t SET dom2 = ? * 2 WHERE id = ?", 1)).isEqualTo("kotlin.Int")
      assertThat(kotlinType("UPDATE t SET domo = ? + 1 WHERE id = ?", 1)).isEqualTo("kotlin.Int")
    }

    @Test
    fun `a non-null parameter under an assignment is named after its column and table`() {
      val expected =
        InferredParameter("dom", ParameterNullability.NonNull, identity = ColumnReference("public", "t", "dom"))

      assertThat(
        parameter("INSERT INTO t(id) VALUES (?) ON CONFLICT (id) DO UPDATE SET dom = ?", 2),
      ).isEqualTo(expected)
      assertThat(parameter("UPDATE t SET dom = (?) WHERE id = ?", 1)).isEqualTo(expected)
      assertThat(parameter("UPDATE t SET dom = ? WHERE id = ?", 1)).isEqualTo(expected)
    }

    @Test
    fun `a parameter compared with a column under an assignment is named after that column and keeps its override`() {
      val sql = "UPDATE t SET flag = a > ? WHERE id = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("a")
      assertThat(kotlinType(sql, 1)).isEqualTo("com.example.Over_a?")
    }

    @Test
    fun `a parameter compared with a column of another relation takes that relation`() {
      val sql = "UPDATE t SET flag = ? = u.k FROM u WHERE t.id = u.id"

      assertThat(parameter(sql, 1).name).isEqualTo("k")
    }

    @Test
    fun `a parameter compared with a column of another type is named after it and keeps its own type`() {
      val sql = "UPDATE t SET flag = a > ?::bigint WHERE id = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("a")
      assertThat(parameter(sql, 1).identity).isNull()
      assertThat(kotlinType(sql, 1)).isEqualTo("kotlin.Long?")
    }

    @Test
    fun `a parameter compared with a column of a same-named table in another schema takes that table's column`() {
      val sql = "UPDATE t SET flag = ou.k = ? FROM other.u ou WHERE t.id = ou.id AND t.id = ?"
      val column = analyzedColumn(sql, 1, catalogWithOther)

      assertThat(parameter(sql, 1).identity).isEqualTo(ColumnReference("other", "u", "k"))
      assertThat(column.table?.schema).isEqualTo("other")
      assertThat(column.originalName).isEqualTo("k")
      assertThat(kotlinType(sql, 1, catalogWithOther)).isEqualTo("kotlin.String?")
    }

    @Test
    fun `a function name beside a compared column keeps the column comment`() {
      val column = analyzedColumn("UPDATE t SET flag = (note = text(?)) WHERE id = ?", 1)

      assertThat(column.name).isEqualTo("text_param1")
      assertThat(column.originalName).isEqualTo("note")
      assertThat(column.comment).isEqualTo("the note")
    }

    @Test
    fun `a function name beside an assignment target drops the column comment`() {
      val column = analyzedColumn("UPDATE t SET note = upper(?) WHERE id = ?", 1)

      assertThat(column.name).isEqualTo("upper_param1")
      assertThat(column.comment).isEqualTo("")
    }

    @Test
    fun `an assignment target takes the column of its own schema when public has a same-named table`() {
      val sql = "UPDATE other.u SET k = ? WHERE id = ?"

      assertThat(parameter(sql, 1).identity?.schema).isEqualTo("other")
      assertThat(kotlinType(sql, 1, catalogWithOther)).isEqualTo("kotlin.String?")
    }

    @Test
    fun `a relation of a schema the catalog lacks carries no identity and no column of another schema`() {
      val compared = "UPDATE t SET flag = ou.k = ? FROM other.u ou WHERE t.id = ou.id AND t.id = ?"
      val assigned = "UPDATE other.u SET k = ? WHERE id = ?"

      assertThat(analyzedColumn(compared, 1).table).isNull()
      assertThat(analyzedColumn(compared, 1).originalName).isEqualTo("")
      assertThat(kotlinType(compared, 1)).isEqualTo("kotlin.String?")
      assertThat(analyzedColumn(assigned, 1).table).isNull()
      assertThat(kotlinType(assigned, 1)).isEqualTo("kotlin.String")
    }

    @Test
    fun `a varchar comparison keeps the column override although PostgreSQL types the parameter text`() {
      assertThat(kotlinType("UPDATE t SET flag = v = ? WHERE id = ?", 1)).isEqualTo("com.example.Over_v?")
      assertThat(kotlinType("UPDATE t SET flag = (v = ?) WHERE id = ?", 1)).isEqualTo("com.example.Over_v?")
      assertThat(kotlinType("UPDATE t SET a = CASE WHEN v = ? THEN 1 END WHERE id = ?", 1))
        .isEqualTo("com.example.Over_v")
    }

    @Test
    fun `a comparison with a domain column keeps the column override`() {
      assertThat(kotlinType("UPDATE t SET flag = em = ? WHERE id = ?", 1)).isEqualTo("com.example.Over_em?")
    }

    @Test
    fun `a CASE condition under an assignment finds the compared column`() {
      val sql = "UPDATE t SET b = CASE WHEN a > ? THEN 1 END WHERE id = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("a")
      assertThat(kotlinType(sql, 1)).isEqualTo("com.example.Over_a")
    }

    @Test
    fun `a CASE condition under an element or field assignment finds the compared column`() {
      val element = "UPDATE t SET arr[1] = CASE WHEN a > ? THEN 1 END WHERE id = ?"
      val field = "UPDATE t SET pair_col.f = CASE WHEN a > ? THEN 1 END WHERE id = ?"

      assertThat(parameter(element, 1).name).isEqualTo("a")
      assertThat(kotlinType(element, 1)).isEqualTo("com.example.Over_a")
      assertThat(parameter(field, 1).name).isEqualTo("a")
      assertThat(kotlinType(field, 1)).isEqualTo("com.example.Over_a")
    }

    @Test
    fun `an operand of an arithmetic operator keeps the assignment column name and no identity`() {
      val sql = "UPDATE t SET b = a + ? WHERE id = ?"

      assertThat(parameter(sql, 1).name).isEqualTo("b")
      assertThat(kotlinType(sql, 1)).isEqualTo("kotlin.Int?")
    }

    @Test
    fun `a parameter of the column's type keeps the column override`() {
      assertThat(kotlinType("UPDATE t SET a = ? WHERE id = ?", 1)).isEqualTo("com.example.Over_a?")
    }

    @Test
    fun `a column of a domain type keeps its override for its own value only`() {
      assertThat(kotlinType("UPDATE t SET em = ? WHERE id = ?", 1)).isEqualTo("com.example.Over_em?")
      assertThat(kotlinType("UPDATE t SET em = lower(?) WHERE id = ?", 1)).isEqualTo("kotlin.String?")
    }
  }

  @Nested
  inner class PgjdbcEscapes {
    @Test
    fun `a doubled question mark operator leaves the parameters numbered and classified`() {
      val sql = "UPDATE t SET a = ? WHERE j ?? 'k' AND id = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("a"))
      assertThat(notNull(sql).getValue(1)).isFalse()
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id"))
    }

    @Test
    fun `a result column query with a doubled question mark keeps its column nullability`() {
      val sql = "SELECT id, j ?? 'k' AS has_key FROM t WHERE id = ?"
      val query = JdbcAnalyzer(connection).analyzeQuery(ParsedQuery("q", Command.MANY, sql, emptyList()), catalog)

      assertThat(query.columns.map { it.name to it.notNull }).isEqualTo(listOf("id" to true, "has_key" to false))
    }
  }

  @Nested
  inner class MultipleStatements {
    private fun analyzed(sql: String) =
      JdbcAnalyzer(connection).analyzeQuery(ParsedQuery("q", Command.EXEC, sql, emptyList()), catalog)

    @Test
    fun `a second statement keeps its own parameters and the first keeps its classification`() {
      val sql = "UPDATE t SET a = ? WHERE id = ?; DELETE FROM u WHERE id = ?"
      val classified = probe.classify(sql)

      assertThat(classified.getValue(1).nullability).isNotNull().isInstanceOf<ParameterNullability.Inherit>()
      assertThat(classified.getValue(2).nullability).isEqualTo(ParameterNullability.NonNull)
      assertThat(classified.getValue(3).nullability).isEqualTo(ParameterNullability.NonNull)
      assertThat(analyzed(sql).params.map { it.column!!.notNull }).isEqualTo(listOf(false, true, true))
    }

    @Test
    fun `a utility statement after an UPDATE does not hide the assignment`() {
      val sql = "UPDATE t SET a = ? WHERE id = ?; NOTIFY x"

      assertThat(probe.classify(sql).getValue(1).nullability).isNotNull().isInstanceOf<ParameterNullability.Inherit>()
      assertThat(analyzed(sql).params.map { it.column!!.notNull }).isEqualTo(listOf(false, true))
    }

    @Test
    fun `an EXPLAIN ANALYZE UPDATE keeps its classification beside another EXPLAIN`() {
      val sql = "EXPLAIN ANALYZE UPDATE t SET a = ? WHERE id = ?; EXPLAIN SELECT 1"

      assertThat(probe.classify(sql).getValue(1).nullability).isNotNull().isInstanceOf<ParameterNullability.Inherit>()
    }

    @Test
    fun `an assignment in a later statement is numbered after the placeholders before it`() {
      val sql = "SELECT 1 WHERE ?::int = 1; UPDATE t SET a = ? WHERE id = ?"
      val classified = probe.classify(sql)

      assertThat(classified.getValue(1).nullability).isEqualTo(ParameterNullability.NonNull)
      assertThat(classified.getValue(2).nullability).isNotNull().isInstanceOf<ParameterNullability.Inherit>()
      assertThat(classified.getValue(2).name).isEqualTo("a")
      assertThat(classified.getValue(3).nullability).isEqualTo(ParameterNullability.NonNull)
    }

    @Test
    fun `two SELECT statements are analyzed with their own conditions`() {
      val sql = "SELECT * FROM t WHERE id = ?; SELECT * FROM u WHERE id = ?"

      assertThat(analyzed(sql).params.map { it.column!!.name to it.column!!.notNull })
        .isEqualTo(listOf("id" to true, "id" to true))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "UPDATE t SET note = 'a;b', a = ? WHERE id = ?",
        "UPDATE t SET note = \$\$a;b\$\$, a = ? WHERE id = ?",
        "UPDATE t SET note = concat(';', 'x'), a = ? WHERE id = ?",
        "UPDATE t SET note = 'x' /* ; */, a = ? WHERE id = ? -- ;",
        "UPDATE t SET note = 'x', a = ? WHERE id = ?;",
      ],
    )
    fun `a semicolon in a literal, a dollar quote, a comment, or at the end does not split`(sql: String) {
      val classified = probe.classify(sql)

      assertThat(classified.values.count { it.nullability is ParameterNullability.Inherit }).isEqualTo(1)
      assertThat(classified.keys.toList().sorted().last()).isEqualTo(classified.size)
    }

    @Test
    fun `a semicolon inside parentheses does not split`() {
      val sql = "CREATE RULE r AS ON UPDATE TO t DO ALSO (UPDATE u SET a = ?; DELETE FROM u WHERE id = ?)"

      assertThat(failingProbe.classify(sql)).isEmpty()
    }

    @Test
    fun `a failed PREPARE of a later preparable statement throws`() {
      assertFailure { failingProbe.classify("NOTIFY x; UPDATE t SET a = ? WHERE id = ?") }
        .isInstanceOf<IllegalStateException>()
    }
  }

  @Nested
  inner class ExplainOfAParenthesizedQuery {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "EXPLAIN (WITH w AS (UPDATE t SET a = ? WHERE id = ? RETURNING id) SELECT * FROM w)",
        "EXPLAIN ((WITH w AS (UPDATE t SET a = ? WHERE id = ? RETURNING id) SELECT * FROM w))",
        "EXPLAIN (ANALYZE) (WITH w AS (UPDATE t SET a = ? WHERE id = ? RETURNING id) SELECT * FROM w)",
        "EXPLAIN ANALYZE (WITH w AS (UPDATE t SET a = ? WHERE id = ? RETURNING id) SELECT * FROM w)",
      ],
    )
    fun `an EXPLAIN of a parenthesized query keeps the assignment classification`(sql: String) {
      assertThat(probe.classify(sql).getValue(1).nullability).isNotNull().isInstanceOf<ParameterNullability.Inherit>()
      assertThat(notNull(sql).getValue(1)).isFalse()
    }

    @Test
    fun `an option list that starts with a keyword is still an option list`() {
      val sql = "EXPLAIN (ANALYZE, VERBOSE) UPDATE t SET a = ? WHERE id = ?"

      assertThat(probe.classify(sql).getValue(1).nullability).isNotNull().isInstanceOf<ParameterNullability.Inherit>()
    }
  }

  @Nested
  inner class ProbeLifecycle {
    @Test
    fun `a statement without a placeholder is not probed`() {
      assertThat(failingProbe.classify("UPDATE t SET a = 1 WHERE id = 2")).isEmpty()
      assertFailure { failingProbe.classify("UPDATE t SET a = ? WHERE id = 2") }.isInstanceOf<IllegalStateException>()
      assertThat(probe.classify("UPDATE t SET a = ? WHERE id = 2")).isNotEmpty()
      assertThat(infer("TRUNCATE t")).isEmpty()
    }

    @Test
    fun `a failed PREPARE of a preparable statement throws with the cause and leaves the connection usable`() {
      val failure = assertFailure { failingProbe.classify("UPDATE t SET a = ? WHERE id = ?", "brokenQuery") }

      failure.isInstanceOf<IllegalStateException>()
        .hasMessage("Could not translate query 'brokenQuery' for parameter analysis.")
      failure.isInstanceOf<IllegalStateException>().cause().isNotNull().isInstanceOf<SQLException>()
      assertThat(parameter("UPDATE t SET b = ? WHERE id = ?", 1)).isEqualTo(assignment("b"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = ["SELECT", "VALUES", "TABLE", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "select", "(SELECT"],
    )
    fun `a failed PREPARE throws when the first significant token starts a preparable statement`(opening: String) {
      assertFailure { failingProbe.classify("/* note */ -- more\n $opening 1 WHERE ? = 1") }
        .isInstanceOf<IllegalStateException>()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["EXPLAIN", "DECLARE", "CREATE", "COPY", "FETCH", "explain", "(EXPLAIN"])
    fun `a failed PREPARE yields no classified parameters for any other first token`(opening: String) {
      assertThat(failingProbe.classify("/* note */ -- more\n $opening 1 WHERE ? = 1")).isEmpty()
    }

    @Test
    fun `an EXPLAIN of a SELECT keeps the classification of the wrapped statement`() {
      val sql = "EXPLAIN SELECT * FROM t WHERE id = ?"
      val query = JdbcAnalyzer(connection).analyzeQuery(ParsedQuery("q", Command.MANY, sql, emptyList()), catalog)

      assertThat(probe.classify(sql).getValue(1).nullability).isEqualTo(ParameterNullability.NonNull)
      assertThat(query.params.map { it.column!!.name to it.column!!.notNull }).isEqualTo(listOf("id" to true))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "EXPLAIN ANALYZE UPDATE t SET a = ? WHERE id = ?",
        "explain analyse verbose UPDATE t SET a = ? WHERE id = ?",
        "EXPLAIN VERBOSE UPDATE t SET a = ? WHERE id = ?",
        "EXPLAIN (ANALYZE, FORMAT JSON) UPDATE t SET a = ? WHERE id = ?",
        "/* c */ EXPLAIN -- c\n (ANALYZE true, SETTINGS true, FORMAT 'json') UPDATE t SET a = ? WHERE id = ?",
      ],
    )
    fun `an EXPLAIN of an UPDATE keeps the assignment classification`(sql: String) {
      assertThat(infer(sql).getValue(1)).isEqualTo(assignment("a"))
      assertThat(notNull(sql)).isEqualTo(mapOf(1 to false, 2 to true))
      assertThat(parameter(sql, 2)).isEqualTo(comparison("id"))
    }

    @Test
    fun `an EXPLAIN of a MERGE keeps the assignment classification`() {
      val sql = "EXPLAIN VERBOSE MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = ?"

      assertThat(parameter(sql, 1)).isEqualTo(assignment("a"))
    }

    @Test
    fun `an EXPLAIN of an UPDATE is analyzed end to end with a nullable parameter`() {
      val sql = "EXPLAIN ANALYZE UPDATE t SET a = ? WHERE id = ?"
      val query = JdbcAnalyzer(connection).analyzeQuery(ParsedQuery("q", Command.MANY, sql, emptyList()), catalog)

      assertThat(query.params.map { it.column!!.name to it.column!!.notNull }).isEqualTo(
        listOf(
          "a" to false,
          "id" to true,
        ),
      )
    }

    @Test
    fun `a failed PREPARE of the statement an EXPLAIN wraps throws when that statement is preparable`() {
      assertFailure { failingProbe.classify("EXPLAIN (ANALYZE) UPDATE t SET a = ? WHERE id = ?") }
        .isInstanceOf<IllegalStateException>()
    }

    @Test
    fun `the analyzer reports its own error for a statement PostgreSQL rejects before the probe runs`() {
      val sql = "UPDATE no_such_table SET a = ? WHERE id = ?"

      assertFailure {
        JdbcAnalyzer(connection).analyzeQuery(ParsedQuery("q", Command.EXEC, sql, emptyList()), catalog)
      }.isInstanceOf<SQLException>()
    }

    @Test
    fun `a statement PostgreSQL prepares but cannot compile into a function yields no assignments`() {
      assertThat(limitedProbe.classify("UPDATE t SET b = ? WHERE id = ?")).isEmpty()
      assertThat(parameter("UPDATE t SET b = ? WHERE id = ?", 1)).isEqualTo(assignment("b"))
    }

    @Test
    fun `a probe leaves no prepared statement and no temporary function behind`() {
      probe.classify("UPDATE t SET b = ? WHERE id = ?")
      probe.classify("UPDATE t SET a = ? WHERE id IN (${placeholders(TOO_MANY_FUNCTION_ARGUMENTS)})")
      runCatching { failingProbe.classify("UPDATE t SET a = ? WHERE id = ?") }
      limitedProbe.classify("UPDATE t SET b = ? WHERE id = ?")

      for (session in listOf(connection, limitedConnection)) {
        assertThat(scalarCount(session, "SELECT count(*) FROM pg_prepared_statements WHERE name LIKE 'norm%'"))
          .isEqualTo(0)
        assertThat(scalarCount(session, "SELECT count(*) FROM pg_proc WHERE pronamespace = pg_my_temp_schema()"))
          .isEqualTo(0)
      }
    }

    @Test
    fun `a classified parameter keeps its number when other placeholders are literals`() {
      val sql = "UPDATE t SET note = '?', col = ? WHERE id = ? AND note <> 'a?'"

      assertThat(probe.classify(sql).keys.toList()).containsExactly(1, 2)
    }

    @Test
    fun `the probe reports each classified parameter with its nullability`() {
      val classified = probe.classify("UPDATE t SET a = coalesce(?, a), b = ? WHERE id = ?")

      assertThat(classified.getValue(1).nullability).isEqualTo(ParameterNullability.Nullable)
      assertThat(classified.getValue(2).nullability).isNotNull().isInstanceOf<ParameterNullability.Inherit>()
    }
  }

  private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

  private fun scalarCount(session: Connection, sql: String): Int = session.createStatement().use { statement ->
    statement.executeQuery(sql).use { resultSet ->
      resultSet.next()
      resultSet.getInt(1)
    }
  }

  companion object {
    private const val TOO_MANY_FUNCTION_ARGUMENTS = 101
    private const val LIMITED_ROLE = "norm_probe_limited"

    private val SCHEMA = """
      CREATE SCHEMA other;
      CREATE TYPE other."Mood" AS ENUM ('happy', 'sad');
      CREATE DOMAIN posint AS int CHECK (VALUE > 0);
      CREATE TYPE pair AS (f int, g int);
      CREATE DOMAIN nnint AS int NOT NULL;
      CREATE TYPE nnpair AS (f nnint, g int);
      CREATE DOMAIN email AS text;
      CREATE DOMAIN d1 AS int NOT NULL;
      CREATE DOMAIN d2 AS d1;
      CREATE TYPE dpair AS (f d2);
      CREATE TYPE widget AS (v text);
      CREATE FUNCTION text_to_widget(text) RETURNS widget LANGUAGE sql CALLED ON NULL INPUT
        AS ${'$'}${'$'} SELECT ROW(${'$'}1)::widget ${'$'}${'$'};
      CREATE CAST (text AS widget) WITH FUNCTION text_to_widget(text) AS ASSIGNMENT;
      CREATE TABLE users (id int PRIMARY KEY, name text NOT NULL, bio text, email text);
      CREATE TABLE t (
        id int PRIMARY KEY, a int, b int, c int NOT NULL DEFAULT 0, d int, x int, y int, k int, col int,
        flag boolean, j jsonb, flag_required boolean NOT NULL DEFAULT false, note text, "set" int, "update" int,
        arr int[], arr_required int[] NOT NULL DEFAULT '{}', tags text[], tags_required text[] NOT NULL DEFAULT '{}',
        pair_col pair, pair_required pair NOT NULL DEFAULT ROW(1, 2), np nnpair NOT NULL DEFAULT ROW(1, 2),
        dp dpair NOT NULL DEFAULT ROW(1), nn nnint, d2col d2, em email, dom d1, dom2 d2, domo d1, v varchar(10),
        w widget, w_required widget NOT NULL DEFAULT ROW('x'),
        text_col text, text_required text NOT NULL DEFAULT '', collated text,
        vc varchar(10), vc_required varchar(10) NOT NULL DEFAULT '',
        amount numeric(8, 2), amount_required numeric(8, 2) NOT NULL DEFAULT 0,
        code char(4), code_required char(4) NOT NULL DEFAULT '',
        moment timestamp(3), moment_required timestamp(3) NOT NULL DEFAULT now(),
        rank posint, rank_required posint NOT NULL DEFAULT 1,
        mood other."Mood", mood_required other."Mood" NOT NULL DEFAULT 'happy',
        labels varchar(4)[], labels_required varchar(4)[] NOT NULL DEFAULT '{}'
      );
      COMMENT ON COLUMN t.note IS 'the note';
      CREATE TABLE "t€" (a int);
      CREATE FUNCTION public.like_escape(text, text) RETURNS text LANGUAGE sql AS ${'$'}${'$'} SELECT ${'$'}1 ${'$'}${'$'};
      CREATE TABLE q ("a""b" int NOT NULL, "a,b" int, "c)d" int, my${'$'}col int);
      CREATE TABLE u (id int PRIMARY KEY, k int, x int, y int, a int, c int);
      CREATE TABLE other.u (id int PRIMARY KEY, k text);
      CREATE TABLE v (id int PRIMARY KEY, x int);
      CREATE TABLE s (id int PRIMARY KEY, x int, a int);
      CREATE TABLE a (id int PRIMARY KEY, x int);
      CREATE TABLE b (id int PRIMARY KEY, x int);
      CREATE TABLE jobs (id int PRIMARY KEY, status text);
      CREATE TABLE queue (id int PRIMARY KEY, s int);
    """.trimIndent()

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_nullability_probe", inMemory = true)

    private lateinit var connection: Connection
    private lateinit var limitedConnection: Connection
    private lateinit var failingProbe: ParameterNullabilityProbe
    private lateinit var limitedProbe: ParameterNullabilityProbe
    private lateinit var probe: ParameterNullabilityProbe
    private lateinit var inferrer: SqlParameterInferrer
    private lateinit var catalog: Catalog
    private lateinit var catalogWithOther: Catalog
    private lateinit var typeRepository: TypeRepository

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
      connection.createStatement().use { it.execute(SCHEMA) }
      connection.createStatement().use {
        it.execute("CREATE ROLE $LIMITED_ROLE LOGIN PASSWORD '$LIMITED_ROLE'")
        it.execute("GRANT SELECT, UPDATE ON t TO $LIMITED_ROLE")
        it.execute("REVOKE TEMPORARY ON DATABASE ${container.databaseName} FROM PUBLIC")
      }
      val catalogLoader = PgCatalogLoader(connection)
      probe = ParameterNullabilityProbe(connection)
      limitedConnection = DriverManager.getConnection(container.jdbcUrl, LIMITED_ROLE, LIMITED_ROLE)
      limitedProbe = ParameterNullabilityProbe(limitedConnection)
      failingProbe = ParameterNullabilityProbe(rejectingPrepare(connection))
      inferrer = SqlParameterInferrer(catalogLoader.functionOverloads)
      catalog = JdbcAnalyzer(connection).buildCatalog()
      catalogWithOther = JdbcAnalyzer(connection).buildCatalog(listOf("public", "other"))
      val overrides = listOf("a", "flag", "em", "domo", "v").map {
        TypeMapping.ByColumn("t", it, "com.example.Over_$it", "com.example.Over_${it}Adapter")
      }
      typeRepository = TypeRepository("p", catalog, overrides)
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::limitedConnection.isInitialized) limitedConnection.close()
      if (::connection.isInitialized) connection.close()
    }

    /** Wraps [real] so that `PREPARE` statements fail, forcing the probe's translation error. */
    private fun rejectingPrepare(real: Connection): Connection = proxy<Connection> { method, arguments ->
      if (method.name == "createStatement") {
        val statement = invoke(real, method, arguments) as Statement
        proxy<Statement> { statementMethod, statementArguments ->
          val sql = statementArguments.firstOrNull() as? String
          if (statementMethod.name == "execute" && sql != null && sql.startsWith("PREPARE")) {
            throw SQLException("forced PREPARE failure")
          }
          invoke(statement, statementMethod, statementArguments)
        }
      } else {
        invoke(real, method, arguments)
      }
    }

    private inline fun <reified T> proxy(crossinline handler: (Method, Array<Any?>) -> Any?): T =
      Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
        handler(method, arguments ?: emptyArray())
      } as T

    private fun invoke(target: Any, method: Method, arguments: Array<Any?>): Any? = try {
      method.invoke(target, *arguments)
    } catch (exception: InvocationTargetException) {
      throw exception.targetException
    }

    @JvmStatic
    fun inheritingCases(): List<Arguments> = listOf(
      Arguments.of("%s = ?::text", "w", "w_required"),
      Arguments.of("%s = CASE WHEN b = 1 THEN ?::int END", "a", "c"),
      Arguments.of("%s = ? + 1", "a", "c"),
      Arguments.of("%s = (b = ?)::int", "a", "c"),
    )

    @JvmStatic
    fun nullableCases(): List<Arguments> = listOf(
      Arguments.of("%s = ?::int IS NULL", "flag", "flag_required"),
      Arguments.of("%s = ? IS DISTINCT FROM 1", "flag", "flag_required"),
    )
  }
}
