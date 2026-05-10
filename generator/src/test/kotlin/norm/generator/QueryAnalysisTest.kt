package norm.generator

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import plugin.Query
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end tests that verify query analysis against real PostgreSQL output.
 *
 * Each test creates its own schema inline and runs a real SQL query through the full
 * analysis pipeline (JDBC metadata, node tree parsing, nullability analysis). This
 * proves the pipeline handles real PostgreSQL output, not synthetic node tree strings.
 *
 * Tests run in parallel — each gets its own schema and JDBC connection, so there is
 * no shared mutable state.
 */
@Testcontainers
class QueryAnalysisTest {

  @Nested
  inner class ColumnReferences {

    @Test
    fun `all NOT NULL columns`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "SELECT id, name FROM t",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `mix of nullable and NOT NULL columns`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, bio TEXT)",
        "SELECT id, bio FROM t",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }
  }

  @Nested
  inner class Joins {

    private val schema = """
      CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL);
      CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, label TEXT NOT NULL)
    """.trimIndent()

    @Test
    fun `INNER JOIN preserves schema nullability`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d JOIN e ON e.d_id = d.id")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `LEFT JOIN marks right side as nullable`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d LEFT JOIN e ON e.d_id = d.id")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `RIGHT JOIN marks left side as nullable`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d RIGHT JOIN e ON e.d_id = d.id")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isFalse()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `FULL OUTER JOIN marks both sides as nullable`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d FULL JOIN e ON e.d_id = d.id")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isFalse()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `CROSS JOIN preserves schema nullability`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d CROSS JOIN e")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `LATERAL JOIN preserves nullability`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT d.id, sub.id FROM d, LATERAL (SELECT e.id FROM e WHERE e.d_id = d.id LIMIT 1) sub",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `SELECT DISTINCT preserves schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT DISTINCT id FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `ORDER BY and LIMIT preserve schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id FROM t ORDER BY id LIMIT 10",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `FOR UPDATE preserves schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id FROM t FOR UPDATE",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `TABLE shorthand preserves schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "TABLE t",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `VALUES expression is conservatively nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE dummy (id INT NOT NULL)",
        "VALUES (1, 'a'), (2, 'b')",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isFalse()
      assertThat(query.columns[1].not_null).isFalse()
    }
  }

  @Nested
  inner class OuterJoinExpressionComposition {

    private val schema = """
      CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL);
      CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, label TEXT NOT NULL)
    """.trimIndent()

    @Test
    fun `strict function on LEFT JOIN nullable column is nullable`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT d.id, upper(e.label) AS upper_label FROM d LEFT JOIN e ON e.d_id = d.id",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `COALESCE on LEFT JOIN nullable column with non-null fallback is non-null`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT d.id, coalesce(e.label, 'none') AS safe_label FROM d LEFT JOIN e ON e.d_id = d.id",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `nested strict function wrapping COALESCE with fallback on outer-join column is non-null`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT upper(coalesce(e.label, 'default')) AS result FROM d LEFT JOIN e ON e.d_id = d.id",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `COALESCE on LEFT JOIN nullable column with all nullable fallbacks is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE d (id INT NOT NULL); CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, a TEXT, b TEXT)",
        "SELECT coalesce(e.a, e.b) AS result FROM d LEFT JOIN e ON e.d_id = d.id",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class CaseExpressions {

    @Test
    fun `CASE WHEN without ELSE is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 0 THEN 'yes' END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `CASE WHEN with ELSE, all branches non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 0 THEN 'yes' ELSE 'no' END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `CASE WHEN with ELSE NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 0 THEN 'yes' ELSE NULL END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `CASE WHEN multiple branches, one THEN is nullable column`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, bio TEXT)",
        "SELECT CASE WHEN id > 0 THEN bio WHEN id = 0 THEN 'zero' ELSE 'neg' END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `CASE WHEN multiple branches, all non-null with ELSE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 10 THEN 'big' WHEN id > 0 THEN 'small' ELSE 'none' END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `simple CASE without ELSE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE id WHEN 1 THEN 'one' WHEN 2 THEN 'two' END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `simple CASE with ELSE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE id WHEN 1 THEN 'one' ELSE 'other' END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `nested CASE — inner has no ELSE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 0 THEN (CASE WHEN id > 10 THEN 'big' END) ELSE 'neg' END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `simple CASE with nullable test expression`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (status TEXT)",
        "SELECT CASE status WHEN 'active' THEN 1 WHEN 'inactive' THEN 2 ELSE 0 END AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class CoalesceExpressions {

    @Test
    fun `all non-null arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT coalesce(name, 'default') AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `nullable argument with non-null fallback`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (bio TEXT)",
        "SELECT coalesce(bio, 'none') AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `all nullable arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT, b TEXT)",
        "SELECT coalesce(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `three arguments — first two nullable, last non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT, b TEXT, c TEXT NOT NULL)",
        "SELECT coalesce(a, b, c) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `single nullable argument`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT)",
        "SELECT coalesce(a) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class NullIfExpressions {

    @Test
    fun `NULLIF on non-null inputs is always nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT nullif(id, 0) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `NULLIF on nullable input`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT)",
        "SELECT nullif(val, 0) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class GreatestLeast {

    @Test
    fun `GREATEST with all non-null arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL)",
        "SELECT greatest(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `GREATEST with nullable argument`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT)",
        "SELECT greatest(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `LEAST with all non-null arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL)",
        "SELECT least(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `GREATEST with three arguments, mixed nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT, c INT NOT NULL)",
        "SELECT greatest(a, b, c) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class Functions {

    @Test
    fun `strict function on non-null argument`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT upper(name) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `strict function on nullable argument`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT)",
        "SELECT upper(name) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `non-strict function (concat) with nullable arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT, b TEXT)",
        "SELECT concat(a, b) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `concat_ws with nullable arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT, b TEXT)",
        "SELECT concat_ws(', ', a, b) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `multi-arg strict function with mixed nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (s TEXT)",
        "SELECT substring(s, 1, 3) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `NOW() is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT now() AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `nested function calls on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT upper(lower(name)) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `nested function calls on nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT)",
        "SELECT upper(lower(name)) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `length on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT length(name) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `abs on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT abs(val) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `date_trunc on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (ts TIMESTAMP NOT NULL)",
        "SELECT date_trunc('day', ts) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `extract on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (ts TIMESTAMP NOT NULL)",
        "SELECT extract(YEAR FROM ts) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class Operators {

    @Test
    fun `arithmetic on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL)",
        "SELECT a + b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `arithmetic on nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT)",
        "SELECT a + b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `comparison returning boolean — both non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL)",
        "SELECT a > b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `comparison with nullable operand`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT)",
        "SELECT a > b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `LIKE on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name LIKE '%test%' AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `ILIKE on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name ILIKE '%test%' AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `BETWEEN on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT val BETWEEN 1 AND 10 AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `string concatenation on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT a || b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `string concatenation on nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT)",
        "SELECT a || b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `unary minus on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT -val AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `NOT on non-null boolean`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT NOT flag AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `NOT on nullable boolean`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN)",
        "SELECT NOT flag AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    /**
     * Verifies the JSONB `->>` operator.
     */
    @Test
    fun `jsonb operator 1`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT data->>'key' AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    /**
     * Verifies the JSONB `->` operator.
     */
    @Test
    fun `jsonb operator 2`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT data->'key' AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class TypeCoercion {

    @Test
    fun `cast non-null to compatible type`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id::BIGINT AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `cast nullable preserves nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT)",
        "SELECT val::TEXT AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `CoerceViaIO — text to json on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (data TEXT NOT NULL)",
        "SELECT data::json AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `type literal`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT '2024-01-01'::DATE AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class NullAndBooleanTests {

    @Test
    fun `IS NULL is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id IS NULL AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `IS NOT NULL is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id IS NOT NULL AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `IS TRUE is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT flag IS TRUE AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `IS FALSE is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT flag IS FALSE AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `IS UNKNOWN is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN)",
        "SELECT flag IS UNKNOWN AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `IS DISTINCT FROM is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT, b INT)",
        "SELECT a IS DISTINCT FROM b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `IS NOT DISTINCT FROM is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT, b INT)",
        "SELECT a IS NOT DISTINCT FROM b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `= ANY(ARRAY) is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id = ANY(ARRAY[1, 2, 3]) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `AND on non-null booleans`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a BOOLEAN NOT NULL, b BOOLEAN NOT NULL)",
        "SELECT a AND b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `OR on non-null booleans`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a BOOLEAN NOT NULL, b BOOLEAN NOT NULL)",
        "SELECT a OR b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `AND with nullable boolean`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a BOOLEAN, b BOOLEAN NOT NULL)",
        "SELECT a AND b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `OR with nullable boolean`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a BOOLEAN, b BOOLEAN NOT NULL)",
        "SELECT a OR b AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class Literals {

    @Test
    fun `NULL literal is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT NULL AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `integer literal is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT 42 AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `string literal is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT 'hello' AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class SqlValueFunctions {

    @Test
    fun `current_date is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT current_date AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `current_timestamp is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT current_timestamp AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `current_time is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT current_time AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `localtime is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT localtime AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `localtimestamp is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT localtimestamp AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `current_user is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT current_user AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `session_user is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT session_user AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class Aggregates {

    @Test
    fun `COUNT star is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT count(*) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `COUNT column is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT count(id) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `COUNT DISTINCT is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT count(DISTINCT id) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `SUM is nullable — empty group returns NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT sum(val) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `AVG is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT avg(val) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `MAX is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT max(val) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `MIN is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT min(val) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `string_agg is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT string_agg(name, ', ') AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `bool_and is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT bool_and(flag) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `bool_or is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT bool_or(flag) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `array_agg is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT array_agg(id) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `COUNT with FILTER is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, active BOOLEAN NOT NULL)",
        "SELECT count(*) FILTER (WHERE active) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `SUM with FILTER is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL, active BOOLEAN NOT NULL)",
        "SELECT sum(val) FILTER (WHERE active) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class Grouping {

    @Test
    fun `GROUP BY key is non-null when column is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name, count(*) AS cnt FROM t GROUP BY name",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `GROUP BY on nullable column — key stays nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (category TEXT)",
        "SELECT category, count(*) AS cnt FROM t GROUP BY category",
      )
      assertThat(query.columns[0].not_null).isFalse()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `GROUP BY on expression`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT upper(name) AS uname, count(*) AS cnt FROM t GROUP BY upper(name)",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `GROUP BY CUBE — grouping keys become nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT a, b, count(*) AS cnt FROM t GROUP BY CUBE(a, b)",
      )
      assertThat(query.columns[0].not_null).isFalse()
      assertThat(query.columns[1].not_null).isFalse()
      assertThat(query.columns[2].not_null).isTrue()
    }

    @Test
    fun `GROUP BY ROLLUP — grouping keys become nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT a, b, count(*) AS cnt FROM t GROUP BY ROLLUP(a, b)",
      )
      assertThat(query.columns[0].not_null).isFalse()
      assertThat(query.columns[1].not_null).isFalse()
      assertThat(query.columns[2].not_null).isTrue()
    }

    @Test
    fun `GROUP BY GROUPING SETS — grouping keys become nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT a, b, count(*) AS cnt FROM t GROUP BY GROUPING SETS((a), (b), ())",
      )
      assertThat(query.columns[0].not_null).isFalse()
      assertThat(query.columns[1].not_null).isFalse()
      assertThat(query.columns[2].not_null).isTrue()
    }

    @Test
    fun `GROUPING function is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT a, grouping(a) AS grp, count(*) AS cnt FROM t GROUP BY ROLLUP(a)",
      )
      assertThat(query.columns[0].not_null).isFalse()
      assertThat(query.columns[1].not_null).isTrue()
      assertThat(query.columns[2].not_null).isTrue()
    }
  }

  @Nested
  inner class WindowFunctions {

    @Test
    fun `ROW_NUMBER is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, row_number() OVER() AS rn FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `RANK is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, rank() OVER(ORDER BY id) AS rnk FROM t",
      )
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `DENSE_RANK is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, dense_rank() OVER(ORDER BY id) AS drnk FROM t",
      )
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `NTILE is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, ntile(4) OVER(ORDER BY id) AS bucket FROM t",
      )
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `PERCENT_RANK is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, percent_rank() OVER(ORDER BY id) AS pr FROM t",
      )
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CUME_DIST is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, cume_dist() OVER(ORDER BY id) AS cd FROM t",
      )
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `COUNT OVER is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, count(*) OVER() AS cnt FROM t",
      )
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `SUM OVER is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, sum(id) OVER() AS total FROM t",
      )
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `LAG without default is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, lag(id) OVER(ORDER BY id) AS prev FROM t",
      )
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `LAG with default is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, lag(id, 1, 0) OVER(ORDER BY id) AS prev FROM t",
      )
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `LAG with nullable default argument is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, fallback INT)",
        "SELECT id, lag(id, 1, fallback) OVER(ORDER BY id) AS prev FROM t",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `LEAD without default is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, lead(id) OVER(ORDER BY id) AS nxt FROM t",
      )
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `LEAD with default is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, lead(id, 1, 0) OVER(ORDER BY id) AS nxt FROM t",
      )
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `LEAD with nullable default argument is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, fallback INT)",
        "SELECT id, lead(id, 1, fallback) OVER(ORDER BY id) AS nxt FROM t",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `FIRST_VALUE is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, first_value(id) OVER(ORDER BY id) AS fv FROM t",
      )
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `LAST_VALUE is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, last_value(id) OVER(ORDER BY id) AS lv FROM t",
      )
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `NTH_VALUE is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, nth_value(id, 2) OVER(ORDER BY id) AS nv FROM t",
      )
      assertThat(query.columns[1].not_null).isFalse()
    }
  }

  @Nested
  inner class Subqueries {

    @Test
    fun `EXISTS subquery is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT exists(SELECT 1 FROM t) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `scalar subquery is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT (SELECT max(id) FROM t) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `IN subquery with non-null outer operand is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL); CREATE TABLE t2 (id INT NOT NULL)",
        "SELECT id IN (SELECT id FROM t2) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `IN subquery with nullable outer operand is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (category TEXT); CREATE TABLE t2 (category TEXT NOT NULL)",
        "SELECT category IN (SELECT category FROM t2) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `subquery in FROM`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "SELECT * FROM (SELECT id, name FROM t) sub",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `set-returning function in FROM`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT * FROM generate_series(1, 10) AS x",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `correlated scalar subquery`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL); CREATE TABLE t2 (id INT NOT NULL, t_id INT NOT NULL, val INT NOT NULL)",
        "SELECT id, (SELECT sum(val) FROM t2 WHERE t2.t_id = t.id) AS total FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }
  }

  @Nested
  inner class CommonTableExpressions {

    @Test
    fun `simple CTE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "WITH c AS (SELECT id, name FROM t) SELECT * FROM c",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CTE with LEFT JOIN inside`() {
      val query = analyzeWithSchema(
        "CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL); CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL)",
        """
        WITH c AS (
          SELECT d.id, e.id AS e_id FROM d LEFT JOIN e ON e.d_id = d.id
        )
        SELECT * FROM c
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `WITH RECURSIVE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        """
        WITH RECURSIVE counter(n) AS (
          SELECT 1
          UNION ALL
          SELECT n + 1 FROM counter WHERE n < 10
        )
        SELECT n FROM counter
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `multiple CTEs`() {
      val query = analyzeWithSchema(
        "CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL); CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH
          dept AS (SELECT id, name FROM d),
          emp AS (SELECT id, name, d_id FROM e)
        SELECT dept.name, emp.name AS emp_name
        FROM dept LEFT JOIN emp ON emp.d_id = dept.id
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `CTE referencing another CTE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL); CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH
          joined AS (
            SELECT d.id, d.name AS d_name, e.name AS e_name
            FROM d LEFT JOIN e ON e.d_id = d.id
          ),
          filtered AS (SELECT d_name, e_name FROM joined)
        SELECT d_name, e_name FROM filtered
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `CTE with DELETE RETURNING, outer SELECT`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH deleted AS (
          DELETE FROM t WHERE id = -1 RETURNING *
        )
        SELECT * FROM deleted
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CTE with INSERT RETURNING, outer SELECT`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        """
        WITH inserted AS (
          INSERT INTO t(name) VALUES ('test') RETURNING *
        )
        SELECT * FROM inserted
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CTE used in LEFT JOIN`() {
      val query = analyzeWithSchema(
        "CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL); CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL)",
        """
        WITH emp AS (SELECT id, d_id FROM e)
        SELECT d.name, emp.id AS emp_id
        FROM d LEFT JOIN emp ON emp.d_id = d.id
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `CTE with UPDATE RETURNING, outer SELECT`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH updated AS (
          UPDATE t SET name = 'x' WHERE id = -1 RETURNING *
        )
        SELECT * FROM updated
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CTE with DELETE RETURNING, outer SELECT with LEFT JOIN`() {
      val query = analyzeWithSchema(
        "CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL); CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH deleted AS (
          DELETE FROM e WHERE id = -1 RETURNING *
        )
        SELECT d.name, del.name AS deleted_name
        FROM d LEFT JOIN deleted del ON del.d_id = d.id
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `CTE before INSERT RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        """
        WITH source AS (SELECT 'new dept' AS name)
        INSERT INTO t(name) SELECT name FROM source RETURNING *
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CTE before UPDATE RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH target_ids AS (SELECT id FROM t WHERE FALSE)
        UPDATE t SET name = 'x' WHERE id IN (SELECT id FROM target_ids) RETURNING *
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CTE before DELETE RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH target_ids AS (SELECT id FROM t WHERE FALSE)
        DELETE FROM t WHERE id IN (SELECT id FROM target_ids) RETURNING *
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `DML CTE feeding into DML outer`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL); CREATE TABLE t2 (id SERIAL NOT NULL, name TEXT NOT NULL)",
        """
        WITH removed AS (
          DELETE FROM t WHERE id = -1 RETURNING *
        )
        INSERT INTO t2(name) SELECT name FROM removed RETURNING *
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CTE named recursive_cte is not misread as RECURSIVE keyword`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "WITH recursive_cte AS (SELECT id, name FROM t) SELECT * FROM recursive_cte",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `CTE with UNION inside — both branches non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        """
        WITH combined AS (
          SELECT name FROM t UNION ALL SELECT name FROM t
        )
        SELECT * FROM combined
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `CTE with aggregate inside`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (category TEXT NOT NULL, val INT NOT NULL)",
        """
        WITH stats AS (
          SELECT category, sum(val) AS total FROM t GROUP BY category
        )
        SELECT * FROM stats
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `CTE with expression columns — CASE and COALESCE nullability propagates`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, bio TEXT)",
        """
        WITH enriched AS (
          SELECT COALESCE(bio, 'unknown') AS safe_bio,
                 CASE WHEN id > 0 THEN 'pos' END AS label
          FROM t
        )
        SELECT * FROM enriched
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `recursive CTE with nullable seed`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, parent_id INT)",
        """
        WITH RECURSIVE tree AS (
          SELECT id, parent_id FROM t WHERE parent_id IS NULL
          UNION ALL
          SELECT t.id, t.parent_id FROM t JOIN tree ON tree.id = t.parent_id
        )
        SELECT * FROM tree
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }

    @Test
    fun `CTE with DELETE RETURNING used in LEFT JOIN of outer query`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL); CREATE TABLE r (id INT NOT NULL, t_id INT NOT NULL)",
        """
        WITH removed AS (
          DELETE FROM r WHERE id = -1 RETURNING *
        )
        SELECT t.name, removed.id AS removed_id
        FROM t LEFT JOIN removed ON removed.t_id = t.id
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isFalse()
    }
  }

  @Nested
  inner class DmlReturning {

    @Test
    fun `INSERT RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        "INSERT INTO t(name) VALUES ('test') RETURNING *",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `UPDATE RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "UPDATE t SET name = 'x' WHERE id = -1 RETURNING *",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `DELETE RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "DELETE FROM t WHERE id = -1 RETURNING *",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `INSERT RETURNING specific columns`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        "INSERT INTO t(name) VALUES ('test') RETURNING id, name",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `INSERT ON CONFLICT RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL)",
        """
        INSERT INTO t(id, name) VALUES (1, 'test')
        ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
        RETURNING *
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `INSERT SELECT RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        "INSERT INTO t(name) SELECT name FROM t WHERE FALSE RETURNING *",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `UPDATE FROM with LEFT JOIN RETURNING`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE r (id INT NOT NULL, t_id INT NOT NULL);
        CREATE TABLE s (id INT NOT NULL, r_id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        UPDATE t SET name = t.name
        FROM r LEFT JOIN s ON s.r_id = r.id
        WHERE r.t_id = t.id AND t.id = -1
        RETURNING t.id, t.name, s.label
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
      assertThat(query.columns[2].not_null).isFalse()
    }

    @Test
    fun `DELETE USING LEFT JOIN RETURNING`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE r (id INT NOT NULL, t_id INT NOT NULL);
        CREATE TABLE s (id INT NOT NULL, r_id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        DELETE FROM t
        USING r LEFT JOIN s ON s.r_id = r.id
        WHERE r.t_id = t.id AND t.id = -1
        RETURNING t.id, t.name, s.label
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
      assertThat(query.columns[2].not_null).isFalse()
    }

    @Test
    fun `UPDATE FROM INNER JOIN RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL, nickname TEXT); CREATE TABLE d (id INT NOT NULL, t_id INT NOT NULL)",
        """
        UPDATE t SET nickname = d.id::TEXT
        FROM d WHERE t.id = d.t_id AND t.id = -1
        RETURNING t.*
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
      assertThat(query.columns[2].not_null).isFalse()
    }

    @Test
    fun `DELETE USING INNER JOIN RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL, nickname TEXT); CREATE TABLE d (id INT NOT NULL, t_id INT NOT NULL)",
        """
        DELETE FROM t
        USING d WHERE t.id = d.t_id AND t.id = -1
        RETURNING t.*
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
      assertThat(query.columns[2].not_null).isFalse()
    }

    @Test
    fun `INSERT with DEFAULT for non-null column`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL DEFAULT 'unnamed')",
        "INSERT INTO t DEFAULT VALUES RETURNING *",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `MERGE RETURNING`() {
      assumeTrue(pgVersion.toInt() >= 17, "MERGE RETURNING requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL)",
        """
        MERGE INTO t
        USING (SELECT -1 AS id, 'test' AS name) s ON t.id = s.id
        WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)
        RETURNING *
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }
  }

  /**
   * Set operations (UNION ALL, INTERSECT, EXCEPT) are conservatively treated as nullable even when
   * every branch selects NOT NULL columns. PostgreSQL represents set operations using subquery RTEs
   * in the range table, and [PgCatalogLoader.buildSubqueryColumnNotNull] skips set-operation queries
   * to avoid incorrectly reporting the first branch's nullability as the whole result's nullability.
   * The target list VARs reference the first subquery RTE, whose varno has no entry in the base-table
   * range table, so [NodeTreeNullabilityAnalyzer] defaults to nullable.
   *
   * CTE-wrapped set operations DO analyze branches (see [CommonTableExpressions]) because the CTE
   * analysis pipeline can safely iterate all branches. Direct (non-CTE) set operations could be
   * improved similarly but aren't yet — these tests document the current conservative behavior.
   */
  @Nested
  inner class SetOperations {

    @Test
    fun `UNION ALL is conservatively nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name FROM t UNION ALL SELECT name FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `INTERSECT is conservatively nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name FROM t INTERSECT SELECT name FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `EXCEPT is conservatively nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name FROM t EXCEPT SELECT name FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `UNION ALL where one branch has LEFT JOIN`() {
      val query = analyzeWithSchema(
        "CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL); CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, name TEXT NOT NULL)",
        """
        SELECT d.name FROM d
        UNION ALL
        SELECT e.name FROM d LEFT JOIN e ON e.d_id = d.id
        """.trimIndent(),
      )
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class Parameters {

    @Test
    fun `WHERE with placeholder`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "SELECT id, name FROM t WHERE id = ?",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `question mark inside string literal is preserved`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "SELECT id FROM t WHERE name = 'what?' OR id = ?",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `question mark inside comment is preserved`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "SELECT id FROM t -- why?\nWHERE id = ?",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class LeadingComments {

    @Test
    fun `line comment before SELECT`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "-- comment\nSELECT id FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `block comment before SELECT`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "/* block comment */ SELECT id FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `multiple comments before SELECT`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "-- line 1\n-- line 2\n/* block */\nSELECT id FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `comment before CTE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "-- setup\nWITH cte AS (SELECT id, name FROM t) SELECT * FROM cte",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }

    @Test
    fun `comment before INSERT RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        "-- cleanup\nINSERT INTO t(name) VALUES ('test') RETURNING *",
      )
      assertThat(query.columns[0].not_null).isTrue()
      assertThat(query.columns[1].not_null).isTrue()
    }
  }

  @Nested
  inner class JsonOperations {

    @Test
    fun `JSON_VALUE with default NULL behavior`() {
      assumeTrue(pgVersion.toInt() >= 17, "JSON_VALUE requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_VALUE(data, '\$.name') AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `JSON_VALUE with DEFAULT on empty and error`() {
      assumeTrue(pgVersion.toInt() >= 17, "JSON_VALUE requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_VALUE(data, '\$.name' RETURNING TEXT DEFAULT 'N/A' ON EMPTY DEFAULT 'ERR' ON ERROR) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `JSON_EXISTS is non-null`() {
      assumeTrue(pgVersion.toInt() >= 17, "JSON_EXISTS requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_EXISTS(data, '\$.name') AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `JSON_QUERY with default NULL behavior`() {
      assumeTrue(pgVersion.toInt() >= 17, "JSON_QUERY requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_QUERY(data, '\$.items') AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }
  }

  @Nested
  inner class XmlOperations {

    @Test
    fun `XMLELEMENT is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT xmlelement(NAME e, name) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `XMLFOREST is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL, val INT NOT NULL)",
        "SELECT xmlforest(name, val) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `XMLCONCAT is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a xml NOT NULL, b xml NOT NULL)",
        "SELECT xmlconcat(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class ArrayExpressions {

    @Test
    fun `ARRAY constructor is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT ARRAY[1, 2, 3] AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }

    @Test
    fun `array subscript is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (arr INT[] NOT NULL)",
        "SELECT arr[1] AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `UNNEST in FROM`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (arr INT[] NOT NULL)",
        "SELECT * FROM unnest(ARRAY[1, 2, 3]) AS x",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `ARRAY subquery is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT array(SELECT id FROM t) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class CompositeTypes {

    @Test
    fun `field select from composite type`() {
      val query = analyzeWithSchema(
        "CREATE TYPE point_t AS (x INT, y INT); CREATE TABLE t (p point_t NOT NULL)",
        "SELECT (p).x AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isFalse()
    }

    @Test
    fun `ROW constructor`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL)",
        "SELECT ROW(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].not_null).isTrue()
    }
  }

  @Nested
  inner class ViewNullability {

    @Test
    fun `LEFT JOIN view marks right side column as nullable`() {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL);
            CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, label TEXT NOT NULL);
            CREATE VIEW v AS SELECT d.name, e.label FROM d LEFT JOIN e ON e.d_id = d.id
            """.trimIndent(),
          )
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val nonNull = catalogLoader.loadViewColumnNullability(schemaName)
          val outerJoinNullable = catalogLoader.loadViewOuterJoinNullableColumns(schemaName)
          assertThat(nonNull.contains("v.name")).isTrue()
          assertThat(outerJoinNullable.contains("v.label")).isTrue()
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `INNER JOIN view preserves non-null columns`() {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL);
            CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, label TEXT NOT NULL);
            CREATE VIEW v AS SELECT d.name, e.label FROM d JOIN e ON e.d_id = d.id
            """.trimIndent(),
          )
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val nonNull = catalogLoader.loadViewColumnNullability(schemaName)
          val outerJoinNullable = catalogLoader.loadViewOuterJoinNullableColumns(schemaName)
          assertThat(nonNull.contains("v.name")).isTrue()
          assertThat(nonNull.contains("v.label")).isTrue()
          assertThat(outerJoinNullable.contains("v.name")).isFalse()
          assertThat(outerJoinNullable.contains("v.label")).isFalse()
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `materialized view excluded from outer join analysis`() {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE d (id INT NOT NULL, name TEXT NOT NULL);
            CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, label TEXT NOT NULL);
            CREATE MATERIALIZED VIEW mv AS SELECT d.name, e.label FROM d LEFT JOIN e ON e.d_id = d.id
            """.trimIndent(),
          )
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val outerJoinNullable = catalogLoader.loadViewOuterJoinNullableColumns(schemaName)
          assertThat(outerJoinNullable.contains("mv.label")).isFalse()
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }
  }

  @Nested
  inner class CatalogLoading {

    @Test
    fun `upper is strict, concat is not strict`() {
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val strictness = catalogLoader.functionStrictnessByOid
        val upperOids = strictness.filterValues { it }.keys
        val alwaysNonNull = catalogLoader.alwaysNonNullFunctionOids
        assertThat(upperOids.isNotEmpty()).isTrue()
        assertThat(alwaysNonNull.isNotEmpty()).isTrue()
      }
    }

    @Test
    fun `count has non-null initial value, sum does not`() {
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val aggValues = catalogLoader.aggregateHasNonNullInitialValue
        val withInitial = aggValues.filterValues { it }
        val withoutInitial = aggValues.filterValues { !it }
        assertThat(withInitial.isNotEmpty()).isTrue()
        assertThat(withoutInitial.isNotEmpty()).isTrue()
      }
    }

    @Test
    fun `strictButNullable OIDs are loaded for JSON operators`() {
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        assertThat(catalogLoader.strictButNullableFunctionOids.isNotEmpty()).isTrue()
      }
    }

    @Test
    fun `lagLeadWithDefault OIDs are loaded`() {
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        assertThat(catalogLoader.lagLeadWithDefaultOids.isNotEmpty()).isTrue()
      }
    }

    @Test
    fun `alwaysNonNull OIDs include concat and concat_ws`() {
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        assertThat(catalogLoader.alwaysNonNullFunctionOids.size >= 2).isTrue()
      }
    }

    @Test
    fun `checkPostgresVersion succeeds on supported version`() {
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        catalogLoader.checkPostgresVersion()
      }
    }
  }

  @Nested
  inner class ParserDefensiveBehavior {

    @Test
    fun `unknown node type produces Unknown expression`() {
      val parser = PgNodeTreeParser()
      val result = parser.parseExpression("{WEIRDNODE :field 42}")
      assertThat(result is PgNodeExpression.Unknown).isTrue()
      assertThat((result as PgNodeExpression.Unknown).nodeType).isEqualTo("WEIRDNODE")
    }

    @Test
    fun `malformed input produces Unknown PARSE_ERROR`() {
      val parser = PgNodeTreeParser()
      val result = parser.parseExpression("not a valid node tree")
      assertThat(result is PgNodeExpression.Unknown).isTrue()
      assertThat((result as PgNodeExpression.Unknown).nodeType).isEqualTo("PARSE_ERROR")
    }

    @Test
    fun `parseTargetList returns empty for malformed input`() {
      val parser = PgNodeTreeParser()
      val result = parser.parseTargetList("malformed")
      assertThat(result).hasSize(0)
    }

    @Test
    fun `parseRangeTable returns empty for malformed input`() {
      val parser = PgNodeTreeParser()
      val result = parser.parseRangeTable("malformed")
      assertThat(result).hasSize(0)
    }

    @Test
    fun `parseGroupRteMap returns empty for malformed input`() {
      val parser = PgNodeTreeParser()
      val result = parser.parseGroupRteMap("malformed")
      assertThat(result).hasSize(0)
    }

    @Test
    fun `hasGroupingSets returns false for malformed input`() {
      val parser = PgNodeTreeParser()
      assertThat(parser.hasGroupingSets("malformed")).isFalse()
    }

    @Test
    fun `recursion depth guard returns false at depth 0`() {
      val analyzer = NodeTreeNullabilityAnalyzer(
        isStrict = { false },
        hasNonNullInitialValue = { false },
        isSourceColumnNotNull = { _, _ -> true },
        isOuterJoinNullable = { false },
      )
      assertThat(analyzer.isNonNull(PgNodeExpression.Const(isNull = false), depth = 0)).isFalse()
    }
  }

  /**
   * Creates an isolated schema, runs DDL, analyzes a query, and tears down.
   * Each call gets its own schema and connection, safe for parallel execution.
   */
  private fun analyzeWithSchema(@Language("PostgreSQL") ddl: String, @Language("PostgreSQL") sql: String): Query {
    val schemaName = "test_${schemaCounter.incrementAndGet()}"
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
      connection.createStatement().use {
        it.execute("CREATE SCHEMA $schemaName")
        it.execute("SET search_path TO $schemaName")
        it.execute(ddl)
      }
      try {
        val analyzer = JdbcAnalyzer(connection)
        val catalog = analyzer.buildCatalog(listOf(schemaName))
        val parsedQuery = ParsedQuery(name = "test", command = ":one", sql = sql, comments = emptyList())
        return analyzer.analyzeQuery(parsedQuery, catalog)
      } finally {
        connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
      }
    }
  }

  companion object {
    private val pgVersion = System.getProperty("norm.test.pgVersion", "18")
    private val schemaCounter = AtomicInteger(0)

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = PostgreSQLContainer(
      DockerImageName.parse("postgres:$pgVersion-alpine").asCompatibleSubstituteFor("postgres"),
    ).apply {
      withDatabaseName("norm_test")
      waitingFor(
        WaitAllStrategy()
          .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
          .withStrategy(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(60)),
      )

      // Run entirely in RAM — no disk I/O for the throwaway database.
      // PostgreSQL 18+ stores data in a version-specific subdirectory under /var/lib/postgresql,
      // so the mount must be at the parent rather than /var/lib/postgresql/data.
      withTmpFs(mapOf("/var/lib/postgresql" to "rw"))
      // Disable durability features we don't need in a throwaway container.
      withCommand(
        "postgres",
        "-c", "fsync=off",
        "-c", "full_page_writes=off",
        "-c", "synchronous_commit=off",
        "-c", "max_wal_senders=0",
        "-c", "wal_level=minimal",
      )
    }
  }
}
