package norm.generator

import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.Statement
import java.util.concurrent.atomic.AtomicInteger

/**
 * One window function's expected result-column nullability, against the fixed one-row-per-group
 * shape `SELECT id, <window function> OVER(...) AS alias FROM t` -- [schema] defaults to a single
 * `NOT NULL` `id` column, overridden only by the two `LAG`/`LEAD` cases that need a nullable
 * `fallback` argument column too.
 */
internal data class WindowFunctionCase(
  val description: String,
  val schema: String = "CREATE TABLE t (id INT NOT NULL)",
  val sql: String,
  val expectedNotNull: Boolean,
) {
  override fun toString() = description
}

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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `mix of nullable and NOT NULL columns`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, bio TEXT)",
        "SELECT id, bio FROM t",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `LEFT JOIN marks right side as nullable`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d LEFT JOIN e ON e.d_id = d.id")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `RIGHT JOIN marks left side as nullable`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d RIGHT JOIN e ON e.d_id = d.id")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `FULL OUTER JOIN marks both sides as nullable`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d FULL JOIN e ON e.d_id = d.id")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `CROSS JOIN preserves schema nullability`() {
      val query = analyzeWithSchema(schema, "SELECT d.id, e.id FROM d CROSS JOIN e")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `LATERAL JOIN preserves nullability`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT d.id, sub.id FROM d, LATERAL (SELECT e.id FROM e WHERE e.d_id = d.id LIMIT 1) sub",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `SELECT DISTINCT preserves schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT DISTINCT id FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `SELECT DISTINCT ON preserves schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT)",
        "SELECT DISTINCT ON (id) id, name FROM t ORDER BY id, name",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `TABLESAMPLE preserves schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT)",
        "SELECT id, name FROM t TABLESAMPLE BERNOULLI (50)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `ORDER BY and LIMIT preserve schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id FROM t ORDER BY id LIMIT 10",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `FOR UPDATE preserves schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id FROM t FOR UPDATE",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `TABLE shorthand preserves schema nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "TABLE t",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `VALUES expression is conservatively nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE dummy (id INT NOT NULL)",
        "VALUES (1, 'a'), (2, 'b')",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `COALESCE on LEFT JOIN nullable column with non-null fallback is non-null`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT d.id, coalesce(e.label, 'none') AS safe_label FROM d LEFT JOIN e ON e.d_id = d.id",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `nested strict function wrapping COALESCE with fallback on outer-join column is non-null`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT upper(coalesce(e.label, 'default')) AS result FROM d LEFT JOIN e ON e.d_id = d.id",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `COALESCE on LEFT JOIN nullable column with all nullable fallbacks is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE d (id INT NOT NULL); CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, a TEXT, b TEXT)",
        "SELECT coalesce(e.a, e.b) AS result FROM d LEFT JOIN e ON e.d_id = d.id",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CASE WHEN with ELSE, all branches non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 0 THEN 'yes' ELSE 'no' END AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `CASE WHEN with ELSE NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 0 THEN 'yes' ELSE NULL END AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CASE WHEN multiple branches, one THEN is nullable column`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, bio TEXT)",
        "SELECT CASE WHEN id > 0 THEN bio WHEN id = 0 THEN 'zero' ELSE 'neg' END AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CASE WHEN multiple branches, all non-null with ELSE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 10 THEN 'big' WHEN id > 0 THEN 'small' ELSE 'none' END AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `simple CASE without ELSE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE id WHEN 1 THEN 'one' WHEN 2 THEN 'two' END AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `simple CASE with ELSE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE id WHEN 1 THEN 'one' ELSE 'other' END AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `nested CASE — inner has no ELSE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT CASE WHEN id > 0 THEN (CASE WHEN id > 10 THEN 'big' END) ELSE 'neg' END AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `simple CASE with nullable test expression`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (status TEXT)",
        "SELECT CASE status WHEN 'active' THEN 1 WHEN 'inactive' THEN 2 ELSE 0 END AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `nullable argument with non-null fallback`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (bio TEXT)",
        "SELECT coalesce(bio, 'none') AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `all nullable arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT, b TEXT)",
        "SELECT coalesce(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `three arguments — first two nullable, last non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT, b TEXT, c TEXT NOT NULL)",
        "SELECT coalesce(a, b, c) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `single nullable argument`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT)",
        "SELECT coalesce(a) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `NULLIF on nullable input`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT)",
        "SELECT nullif(val, 0) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `GREATEST with nullable argument`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT)",
        "SELECT greatest(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `LEAST with all non-null arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL)",
        "SELECT least(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `GREATEST with three arguments, mixed nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT, c INT NOT NULL)",
        "SELECT greatest(a, b, c) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `strict function on nullable argument`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT)",
        "SELECT upper(name) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `non-strict function (concat) with nullable arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT, b TEXT)",
        "SELECT concat(a, b) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `concat_ws with nullable arguments`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT, b TEXT)",
        "SELECT concat_ws(', ', a, b) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `concat_ws with a nullable separator is nullable`() {
      // concat_ws is non-null iff its FIRST argument (the separator) is non-null — a null
      // separator poisons the whole result even though later arguments are individually
      // null-tolerant. Verified live on PostgreSQL 16, 17, and 18: concat_ws(NULL, 'x', 'y') IS
      // NULL. This is the pre-existing bug (present before grouping-sets work ever touched this
      // file): concat_ws was wrongly on the unconditional always-non-null list.
      val query = analyzeWithSchema(
        "CREATE TABLE t3 (s TEXT)",
        "SELECT concat_ws(s, 'x', 'y') AS k FROM t3",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    // VARIADIC passes the array argument itself as one value, not exploded into elements —
    // isAlwaysNonNull's "regardless of any argument" and isNonNullIffFirstArgumentNonNull's "only
    // the first argument matters" both assume the ordinary calling form and are unsound for
    // VARIADIC: concat(VARIADIC arr) and concat_ws(',', VARIADIC arr) are both NULL when arr
    // itself is NULL. Verified live on PostgreSQL 16, 17, and 18.

    @Test
    fun `concat with VARIADIC over a nullable array is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t4 (arr TEXT[])",
        "SELECT concat(VARIADIC arr) AS c FROM t4",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `concat_ws with VARIADIC over a nullable array is nullable even with a literal separator`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t4 (arr TEXT[])",
        "SELECT concat_ws(',', VARIADIC arr) AS w FROM t4",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `concat with VARIADIC over a NOT NULL array is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t4 (arr TEXT[] NOT NULL)",
        "SELECT concat(VARIADIC arr) AS c FROM t4",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `concat_ws with VARIADIC over a NOT NULL array is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t4 (arr TEXT[] NOT NULL)",
        "SELECT concat_ws(',', VARIADIC arr) AS w FROM t4",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `concat_ws with VARIADIC over an array literal containing a NULL element is still non-null`() {
      // Verified live on PostgreSQL 16, 17, and 18: concat_ws(',', VARIADIC ARRAY['a', NULL]) is
      // 'a', never NULL — the ARRAY[] constructor itself is never NULL even when an element is,
      // and that (not "every element non-null") is the condition that matters here.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT concat_ws(',', VARIADIC ARRAY['a', NULL]) AS w FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `multi-arg strict function with mixed nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (s TEXT)",
        "SELECT substring(s, 1, 3) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `NOW() is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT now() AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `nested function calls on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT upper(lower(name)) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `nested function calls on nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT)",
        "SELECT upper(lower(name)) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `length on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT length(name) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `abs on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT abs(val) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `date_trunc on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (ts TIMESTAMP NOT NULL)",
        "SELECT date_trunc('day', ts) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `extract on non-null`() {
      // Uses TIME, not TIMESTAMP: extract(text, time) is total on every non-null input (no
      // infinite representation for TIME), whereas extract(text, timestamp) is NOT — see
      // `extract over an infinite timestamp is nullable even for a field that is not epoch-like`
      // below for the counterexample that removed the (text, timestamp) signature from the
      // safe list. This test previously used a TIMESTAMP column; changed to TIME so it continues
      // to exercise a genuinely total signature rather than one now correctly excluded.
      val query = analyzeWithSchema(
        "CREATE TABLE t (tm TIME NOT NULL)",
        "SELECT extract(HOUR FROM tm) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    /**
     * P0-2 audit finding (issue #226 follow-up, discovered while re-auditing every safe-listed
     * entry, not one of the verifier's originally measured defects): `extract`/`date_part` over
     * `date`, `interval`, `timestamp`, or `timestamp with time zone` is NOT total, unlike
     * `date_trunc` over the same types. All four base types support an `'infinity'` value, and
     * `extract`/`date_part` special-case only a FEW fields (`epoch`, `year`, `century`, ...) to
     * return an infinite result for infinite input — every OTHER field (`hour`, `month`, `day`,
     * ...) silently returns `null`, with no error, even though the input is a well-typed non-null
     * value. Verified against real PostgreSQL 18.4:
     * `extract(hour FROM 'infinity'::timestamp)` returns `null`, and
     * `extract(month FROM 'infinity'::interval)` and `extract(day FROM 'infinity'::date)` do too.
     * Because the safe-list is keyed by the function's OID (one per `(name, argument types)`
     * signature), not by the field-name string literal passed at a given call site, the whole
     * `(text, timestamp)` signature must be excluded — the mechanism cannot tell "this particular
     * call always passes `'hour'`" from "this call could pass any field name". This test pins the
     * newly-nullable outcome for the exact field (`HOUR`) that exposed the gap.
     */
    @Test
    fun `extract over an infinite timestamp is nullable even for a field that is not epoch-like`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (ts TIMESTAMP NOT NULL)",
        "SELECT extract(HOUR FROM ts) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * Issue #226 root cause: `proisstrict` only guarantees NULL-in => NULL-out, never the
     * converse. `substring(text FROM pattern)` is STRICT, yet returns `null` on a non-matching
     * pattern even though every input is non-null. `substring` shares its `proname` with the
     * TOTAL `substring(text, int, int)` overload, so the safe-list excludes the name wholesale
     * (see [PgCatalogLoader.neverNullForNonNullInputOids]) rather than trying to key it by
     * argument signature.
     */
    @Test
    fun `substring with a non-matching regex reports nullable over a NOT NULL source column`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (s TEXT NOT NULL)",
        "SELECT substring(s FROM '(nomatch)') AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * Issue #226: `regexp_match` is STRICT but returns `null` (not an empty array) when the
     * pattern does not match, even over a non-null input string.
     */
    @Test
    fun `regexp_match with a non-matching pattern reports nullable over a NOT NULL source column`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (s TEXT NOT NULL)",
        "SELECT regexp_match(s, 'nomatch') AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * Issue #226: `array_length` is STRICT but returns `null` (not `0`) for an empty array, even
     * though the array literal itself is a non-null value.
     */
    @Test
    fun `array_length of an empty array reports nullable over a NOT NULL source column`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT array_length(ARRAY[]::text[], 1) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * Closes the class of unsoundness issue #226 fixes for user code: a STRICT function is no
     * longer inferred non-null just because it is strict. Only functions on the
     * [PgCatalogLoader.neverNullForNonNullInputOids] safe-list — which is restricted to
     * `pg_catalog` — get that inference; a user-defined STRICT function in `public` does not,
     * because Norm cannot prove it is total on non-null input.
     */
    @Test
    fun `user-defined STRICT function in public is not inferred non-null`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (name TEXT NOT NULL);
        CREATE FUNCTION double_up(x TEXT) RETURNS TEXT LANGUAGE sql STRICT AS $$ SELECT x || x $$
        """.trimIndent(),
        "SELECT double_up(name) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * A user-defined function sharing a safe-listed name (`upper`) outside `pg_catalog` must not
     * inherit the safe-list entry — the entry is scoped by `pronamespace = 'pg_catalog'`, not by
     * name alone. Takes two arguments to force PostgreSQL to resolve the call to this function
     * rather than the single-argument `pg_catalog.upper`.
     */
    @Test
    fun `user-defined function named upper outside pg_catalog does not inherit the safe-list entry`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (name TEXT NOT NULL);
        CREATE FUNCTION upper(x TEXT, y TEXT) RETURNS TEXT LANGUAGE sql STRICT AS $$ SELECT x || y $$
        """.trimIndent(),
        "SELECT upper(name, 'suffix') AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * P0-2 measured defect: `upper(anyrange)` shares `proname = 'upper'` with the total
     * `upper(text)`, but is STRICT and returns `null` for a non-null, well-typed, UNBOUNDED range
     * — `SELECT upper(int4range '[1,)')` returns `null` with no error. Verified against real
     * PostgreSQL 18.4. Before this fix, keying the safe list by name alone reported this NOT NULL.
     */
    @Test
    fun `upper of an unbounded NOT NULL int4range reports nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (r INT4RANGE NOT NULL)",
        "SELECT upper(r) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * P0-2 measured defect: same as the `upper` test above, but for `lower(anyrange)` over an
     * EMPTY (rather than unbounded) range — `SELECT lower(int4range 'empty')` returns `null` with
     * no error. Verified against real PostgreSQL 18.4.
     */
    @Test
    fun `lower of an empty NOT NULL int4range reports nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (r INT4RANGE NOT NULL)",
        "SELECT lower(r) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * P0-2 measured defect: `lower(anymultirange)` shares `proname = 'lower'` with the total
     * `lower(text)`, but is STRICT and returns `null` for a non-null, well-typed, EMPTY
     * multirange — `SELECT lower(int4multirange '{}')` returns `null` with no error. Verified
     * against real PostgreSQL 18.4.
     */
    @Test
    fun `lower of an empty NOT NULL int4multirange reports nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (m INT4MULTIRANGE NOT NULL)",
        "SELECT lower(m) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * P0-2 measured defect: `to_char(timestamp, text)` is STRICT but returns `null` for a
     * non-null, well-typed, EMPTY format string — `SELECT to_char(now(), '')` returns `null` with
     * no error. Verified against real PostgreSQL 18.4. `to_char` is now dropped from the safe
     * list entirely (see [NeverNullSafeLists.NEVER_NULL_FUNCTION_SIGNATURES]) rather than narrowed,
     * since the empty-format behavior is a property of every overload's shared formatting engine,
     * not of the first argument's type.
     */
    @Test
    fun `to_char with a NOT NULL empty format string reports nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (ts TIMESTAMP NOT NULL, fmt TEXT NOT NULL)",
        "SELECT to_char(ts, fmt) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `arithmetic on nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT)",
        "SELECT a + b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `comparison returning boolean — both non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL)",
        "SELECT a > b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `comparison with nullable operand`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT)",
        "SELECT a > b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `LIKE on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name LIKE '%test%' AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `ILIKE on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name ILIKE '%test%' AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `BETWEEN on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT val BETWEEN 1 AND 10 AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `string concatenation on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT a || b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `string concatenation on nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT)",
        "SELECT a || b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `unary minus on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT -val AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `NOT on non-null boolean`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT NOT flag AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `NOT on nullable boolean`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN)",
        "SELECT NOT flag AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * Regression guard for the operator blanket rule this fix replaces with signature keying:
     * `path_add` (the `+` operator's `path` overload) returns `null`, not an error, when either
     * operand is a CLOSED path — even though both operands are non-null and well-typed. A
     * symbol-only safe-list (every `pg_catalog` overload of `+` is safe) cannot see this, because
     * `+` is also the totally-safe `int4 + int4`. See
     * [NeverNullSafeLists.NEVER_NULL_OPERATOR_SIGNATURES]'s KDoc.
     */
    @Test
    fun `path plus path is nullable, not non-null, despite both operands being NOT NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (p1 PATH NOT NULL, p2 PATH NOT NULL)",
        "SELECT p1 + p2 AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `cast nullable preserves nullability`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT)",
        "SELECT val::TEXT AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CoerceViaIO — text to json on non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (data TEXT NOT NULL)",
        "SELECT data::json AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `type literal`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT '2024-01-01'::DATE AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    /**
     * Regression guard for the cast blanket rule this fix replaces with signature keying:
     * `jsonb::int4` returns `null`, not an error, on the JSON literal `null` — even though the
     * source column is non-null and well-typed. A blanket "every `pg_catalog` castfunc is safe"
     * rule cannot see this. See [NeverNullSafeLists.NEVER_NULL_CAST_SIGNATURES]'s KDoc.
     */
    @Test
    fun `jsonb cast to int4 is nullable, not non-null, despite the source column being NOT NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (data JSONB NOT NULL)",
        "SELECT data::int4 AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `IS NOT NULL is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id IS NOT NULL AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `IS TRUE is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT flag IS TRUE AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `IS FALSE is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT flag IS FALSE AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `IS UNKNOWN is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN)",
        "SELECT flag IS UNKNOWN AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `IS DISTINCT FROM is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT, b INT)",
        "SELECT a IS DISTINCT FROM b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `IS NOT DISTINCT FROM is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT, b INT)",
        "SELECT a IS NOT DISTINCT FROM b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `= ANY(ARRAY) is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id = ANY(ARRAY[1, 2, 3]) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `AND on non-null booleans`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a BOOLEAN NOT NULL, b BOOLEAN NOT NULL)",
        "SELECT a AND b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `OR on non-null booleans`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a BOOLEAN NOT NULL, b BOOLEAN NOT NULL)",
        "SELECT a OR b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `AND with nullable boolean`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a BOOLEAN, b BOOLEAN NOT NULL)",
        "SELECT a AND b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `OR with nullable boolean`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a BOOLEAN, b BOOLEAN NOT NULL)",
        "SELECT a OR b AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `integer literal is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT 42 AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `string literal is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT 'hello' AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `current_timestamp is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT current_timestamp AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `current_time is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT current_time AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `localtime is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT localtime AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `localtimestamp is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT localtimestamp AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `current_user is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT current_user AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `session_user is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT session_user AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `COUNT column is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT count(id) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `COUNT DISTINCT is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT count(DISTINCT id) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `SUM is nullable — empty group returns NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT sum(val) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `AVG is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT avg(val) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MAX is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT max(val) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MIN is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL)",
        "SELECT min(val) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `string_agg is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT string_agg(name, ', ') AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `string_agg DISTINCT with ORDER BY and LEFT JOIN is nullable`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE data_center (
          id UUID PRIMARY KEY NOT NULL DEFAULT gen_random_uuid(),
          name VARCHAR(200) NOT NULL
        );
        CREATE TABLE organization (
          id UUID PRIMARY KEY NOT NULL DEFAULT gen_random_uuid(),
          name VARCHAR(255) NOT NULL,
          salesforce_account_id VARCHAR(255) NOT NULL
        );
        CREATE TABLE site (
          id UUID PRIMARY KEY NOT NULL DEFAULT gen_random_uuid(),
          tenant_id UUID NOT NULL REFERENCES organization(id),
          data_center_id UUID NOT NULL REFERENCES data_center(id),
          name VARCHAR(200) NOT NULL
        );
        """.trimIndent(),
        """
        SELECT dc.id, dc.name,
               COUNT(DISTINCT site.id) AS site_count,
               string_agg(
                 DISTINCT organization.name || ' (' || organization.salesforce_account_id || ')',
                 ', ' ORDER BY organization.name || ' (' || organization.salesforce_account_id || ')'
               ) AS tenants
        FROM data_center dc
        LEFT JOIN site ON site.data_center_id = dc.id
        LEFT JOIN organization ON organization.id = site.tenant_id
        GROUP BY dc.id
        ORDER BY dc.name
        """.trimIndent(),
      )
      assertThat(query.columns.map { "${it.name}: notNull=${it.notNull}" }).containsExactly(
        "id: notNull=true",
        "name: notNull=true",
        "site_count: notNull=true",
        "tenants: notNull=false",
      )
    }

    @Test
    fun `bool_and is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT bool_and(flag) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `bool_or is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (flag BOOLEAN NOT NULL)",
        "SELECT bool_or(flag) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `array_agg is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT array_agg(id) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `COUNT with FILTER is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, active BOOLEAN NOT NULL)",
        "SELECT count(*) FILTER (WHERE active) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `SUM with FILTER is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (val INT NOT NULL, active BOOLEAN NOT NULL)",
        "SELECT sum(val) FILTER (WHERE active) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    /**
     * Mirrors test-scenarios/nested_joins_embeds's `getBookWithReviewCount` query
     * (`COUNT(r.id)::int AS review_count` over a `LEFT JOIN`), which that scenario's own golden
     * comparison test skips (it is in `NormPluginTest.EMBED_SCENARIOS`). `COUNT` is already
     * non-null via its non-null initial value; the `::int` cast wraps that in a FuncExpr calling
     * `pg_catalog.int4(int8)`, which must be on [PgCatalogLoader.neverNullForNonNullInputOids] via
     * the `pg_cast` cast-function rule, or this column would regress to nullable.
     */
    @Test
    fun `COUNT over a LEFT JOIN cast to int stays non-null`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE b (id INT NOT NULL);
        CREATE TABLE r (id INT NOT NULL, b_id INT NOT NULL)
        """.trimIndent(),
        "SELECT COUNT(r.id)::int AS review_count FROM b LEFT JOIN r ON r.b_id = b.id GROUP BY b.id",
      )
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `GROUP BY on nullable column — key stays nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (category TEXT)",
        "SELECT category, count(*) AS cnt FROM t GROUP BY category",
      )
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `GROUP BY on expression`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT upper(name) AS uname, count(*) AS cnt FROM t GROUP BY upper(name)",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `GROUP BY CUBE — grouping keys become nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT a, b, count(*) AS cnt FROM t GROUP BY CUBE(a, b)",
      )
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `GROUP BY ROLLUP — grouping keys become nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT a, b, count(*) AS cnt FROM t GROUP BY ROLLUP(a, b)",
      )
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `GROUP BY GROUPING SETS — grouping keys become nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT a, b, count(*) AS cnt FROM t GROUP BY GROUPING SETS((a), (b), ())",
      )
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `GROUPING function is always non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT a, grouping(a) AS grp, count(*) AS cnt FROM t GROUP BY ROLLUP(a)",
      )
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
    }

    // These exercise the expression-grouping-key rule directly, WITHOUT a proving WHERE clause.
    // A bare-Var grouping key (e.g. GROUP BY ROLLUP(a)) also resolves via ordinary Var evaluation
    // on PostgreSQL 18 because the *GROUP* RTE masks it, but on PostgreSQL 16/17 the target-list
    // Var for that key sits at the same varno/varattno as the base table column, so this rule is
    // needed even for bare keys there. An EXPRESSION grouping key (e.g. lower(a)) never produces a
    // {VAR } target-list entry at all — only walking the typed expression tree
    // (NodeTreeNullabilityAnalyzer.isSafeFromGroupingSetNullExtension) catches these on every
    // PostgreSQL version.

    @Test
    fun `expression ROLLUP grouping key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT lower(a) FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `non-strict COALESCE ROLLUP grouping key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT coalesce(a, 'z') FROM t GROUP BY ROLLUP(coalesce(a, 'z'))",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `expression derived from an expression ROLLUP grouping key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT upper(lower(a)) FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `function over an expression ROLLUP grouping key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT length(lower(a)) FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CUBE over two expression keys — both become nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT lower(a), upper(b) FROM t GROUP BY CUBE(lower(a), upper(b))",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `GROUPING SETS with an expression key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT lower(a) FROM t GROUP BY GROUPING SETS ((lower(a)), ())",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `aggregates and GROUPING stay non-null alongside a nullable expression key`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT lower(a) AS x, grouping(a) AS grp, count(*) AS cnt, count(a) AS cnt_a FROM t GROUP BY ROLLUP(a)",
      )
      assertThat(query.columns).hasSize(4)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
      assertThat(query.columns[3].notNull).isTrue()
    }

    @Test
    fun `subquery expression ROLLUP grouping key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT y FROM (SELECT lower(a) AS y FROM t GROUP BY ROLLUP(lower(a))) s",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CTE expression ROLLUP grouping key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "WITH c AS (SELECT lower(a) AS y FROM t GROUP BY ROLLUP(lower(a))) SELECT y FROM c",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    // A CaseExpr's :arg (test expression) and each WHEN's :expr (condition) are ordinary
    // expressions that can carry the only Var in the whole CASE — but neither is evaluated by
    // isNonNull, since a CASE's own nullability never depends on them. Without also walking them
    // in isSafeFromGroupingSetNullExtension, a Var living only in a test expression or WHEN
    // condition is invisible to the grouping-sets override, and a CASE whose results/default are
    // all constants is wrongly reported non-null even though Postgres null-extends it for the
    // summary row.

    @Test
    fun `CASE WHEN grouping key with the only Var in the WHEN condition is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT CASE WHEN a = 'x' THEN 1 ELSE 2 END AS c FROM t GROUP BY ROLLUP(CASE WHEN a = 'x' THEN 1 ELSE 2 END)",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `shorthand CASE grouping key with the only Var in the test expression is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT CASE a WHEN 'x' THEN 1 ELSE 2 END AS c FROM t GROUP BY ROLLUP(CASE a WHEN 'x' THEN 1 ELSE 2 END)",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `constant-only CASE column under grouping sets is an accepted over-widening`() {
      // CASE WHEN true THEN 1 ELSE 2 END is never actually null-extended by Postgres (its leaves
      // are all Const, which the planner never matches to a grouping key), but
      // isSafeFromGroupingSetNullExtension requires an Aggref/GroupingFunc descendant to prove
      // safety and this expression has none, so it widens to nullable — an accepted, deliberate
      // cost documented on isSafeFromGroupingSetNullExtension's KDoc, not a bug to chase.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT CASE WHEN true THEN 1 ELSE 2 END AS constant_case, a FROM t GROUP BY ROLLUP(a)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
    }

    // JSON_EXISTS/JSON_VALUE/JSON_QUERY do not retain their PASSING clause's values (see
    // PgNodeExpression.JsonExpr's KDoc), so a Var living only there is invisible to
    // isSafeFromGroupingSetNullExtension's walk. Unlike a CaseExpr's dropped children, this one is
    // not recovered by parsing more — it is closed by hardcoding JsonExpr as always UNSAFE,
    // matching Unknown, regardless of an Aggref elsewhere in the tree.

    @Test
    fun `JSON_EXISTS grouping key with the only Var in the PASSING clause is nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "JSON_EXISTS PASSING requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, a INT NOT NULL)",
        """
        SELECT JSON_EXISTS(jsonb '{"a":1}', '${'$'}.a ? (@ == ${'$'}v)' PASSING a AS v) AS e
        FROM t
        GROUP BY ROLLUP(JSON_EXISTS(jsonb '{"a":1}', '${'$'}.a ? (@ == ${'$'}v)' PASSING a AS v))
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `JSON_VALUE with defaults and the only Var in the PASSING clause is nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "JSON_VALUE PASSING requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, a INT NOT NULL)",
        """
        SELECT JSON_VALUE(
          jsonb '{"a":1}', '${'$'}.a ? (@ == ${'$'}v)' PASSING a AS v DEFAULT 0 ON EMPTY DEFAULT 0 ON ERROR
        ) AS c
        FROM t
        GROUP BY ROLLUP(
          JSON_VALUE(jsonb '{"a":1}', '${'$'}.a ? (@ == ${'$'}v)' PASSING a AS v DEFAULT 0 ON EMPTY DEFAULT 0 ON ERROR)
        )
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    // A `}`, `{`, or `\` in a column alias is backslash-escaped by Postgres in the raw node-tree
    // text (e.g. an alias `k}x` is written as `:resname k\}x`). PgNodeTreeParser's brace-counting
    // scanners must skip escaped pairs, or an escaped brace derails brace-depth tracking and
    // hasGroupingSets silently returns false for the WHOLE query block — not just the offending
    // column, since the scan that finds `:groupingSets` runs once over the entire node tree.

    @Test
    fun `a closing brace in the grouping key's own alias does not defeat the fix`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        """SELECT lower(a) AS "k}x" FROM t GROUP BY ROLLUP(lower(a))""",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `an opening brace in a CUBE key's alias does not defeat the fix`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL, b TEXT NOT NULL)",
        """SELECT lower(a) AS "k{x", upper(b) AS m FROM t GROUP BY CUBE(lower(a), upper(b))""",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `a backslash immediately followed by a brace in a GROUPING SETS key's alias does not defeat the fix`() {
      // A literal backslash followed immediately by a literal `}` (identifier `k\}m`) is written
      // as the escaped-backslash pair immediately followed by the escaped-brace pair — three
      // consecutive raw backslashes then `}`. A scanner that loses parity here (rather than
      // consuming exactly two raw characters per escape, strictly left to right) can still
      // miscount even with some escape-awareness.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        """SELECT lower(a) AS "k\}m" FROM t GROUP BY GROUPING SETS ((lower(a)), ())""",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a brace in one column's alias does not poison a different column's nullability`() {
      // The exact reported repro shape: the brace-bearing alias sits on the AGGREGATE column, and
      // the grouping key with no alias issue at all is a separate column — proving the derailment
      // is block-wide (hasGroupingSets), not confined to whichever column carries the brace.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        """SELECT count(*) AS "n}", lower(a) AS k FROM t GROUP BY ROLLUP(lower(a))""",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    // A grouping key need not contain a Var at all. Postgres's grouping-set null-extension is a
    // structural match against the grouping key's own (stable, non-folded) subexpression — a Const
    // key still gets null-extended, and so does any larger expression built on top of one, even
    // though a lone Const is itself immune (the planner never matches a bare Const). This is why
    // isEffectivelyNonNull needs BOTH the sortGroupRef-based key check AND
    // isSafeFromGroupingSetNullExtension's aggregate-domination walk — neither alone is sufficient.

    @Test
    fun `Var-free Const grouping key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT 'ALL'::text AS scope, count(*) AS n FROM t GROUP BY ROLLUP(scope)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `all-Const composite grouping key is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT 'a'::text || 'b'::text AS c, count(*) AS n FROM t GROUP BY ROLLUP('a'::text || 'b'::text)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `expression derived from a Var-free grouping key is nullable`() {
      // date_trunc('month', current_date) is the grouping key; the target-list entry adds
      // "+ interval '1 day'" on top, so its own ressortgroupref is 0 — it does not textually match
      // the key, only structurally once the interval addition is stripped away by Postgres's own
      // subexpression matching. Only isSafeFromGroupingSetNullExtension's tree walk (not the
      // sortGroupRef-based key check) can catch this. current_date is STABLE, not IMMUTABLE, so
      // this key is never constant-folded away — the whole point of this case.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        """
        SELECT date_trunc('month', current_date) + interval '1 day' AS d, count(*) AS n
        FROM t
        GROUP BY ROLLUP(date_trunc('month', current_date))
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `a cast chain over a Const with an Aggref sibling is nullable — mixed operator case`() {
      // count(*)::text || ('2026-01-01'::text::timestamptz)::text: the '::text::timestamptz' cast
      // chain is a real, non-folded coercion function call (unlike a single literal cast, which
      // resolves directly to a Const), so it is a stable, matchable subexpression in its own right
      // — even though its only leaf is a Const. Verified live: PG16 returns NULL for this column in
      // the ROLLUP summary row; PG18 does not (a genuine cross-version behavioral divergence, not
      // an analysis difference). Since the generated Kotlin type must be correct on every supported
      // Postgres version, this column must be nullable — which is an accepted over-widening on
      // PG18, not something to "fix" in the PG18 direction.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        """
        SELECT count(*)::text || ('2026-01-01'::text::timestamptz)::text AS mixed, count(*) AS n
        FROM t
        GROUP BY ROLLUP(('2026-01-01'::text::timestamptz))
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    // The following must NOT widen: each has an Aggref/GroupingFunc dominating every part of its
    // expression tree (modulo Consts), so isSafeFromGroupingSetNullExtension proves it immune to
    // null-extension, and none of them is itself a grouping key.

    @Test
    fun `count concatenated with a Const stays non-null alongside a nullable expression key`() {
      // count(*)::text (not bare count(*)) so the || resolves to the plain text||text operator,
      // which is on the isNeverNullForNonNullInput safe-list — the point under test is the
      // grouping-sets safety walk, not general operator-safelist coverage. 'B' (not 'A') on the
      // concatenation side deliberately differs from the 'A' grouping key: on PostgreSQL 18, the
      // GROUP RTE mechanism structurally substitutes a GROUP-RTE Var for any target-list
      // subexpression that EXACTLY matches the grouping key — reusing 'A'::text here would trigger
      // that substitution and make `d` genuinely nullable, verified live, which is a different,
      // legitimate case, not this one.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT count(*)::text || 'B'::text AS d, 'A'::text AS k FROM t GROUP BY ROLLUP('A'::text)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `a bare Const tag that is not the grouping key stays non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT 'ALL'::text AS tag, lower(a) AS k, count(*) AS n FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `count plus a literal stays non-null alongside a nullable expression key`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT count(*) + 1 AS n, lower(a) AS k FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `count with a FILTER clause stays non-null alongside a nullable expression key`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT count(*) FILTER (WHERE a = 'zzz') AS f, lower(a) AS k FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    // isSafeFromGroupingSetNullExtension's aggregate-domination rule alone over-widens: an implicit
    // int4->int8 literal like `0::bigint` is a FuncExpr over a Const, not a folded Const, so
    // `count(*) + 0::bigint` has no Aggref descendant on ITS OWN unless the whole subtree is
    // examined together — the domination check finds count(*)'s Aggref for the OUTER `+`, but the
    // literal side still needs to be proven safe independently via constant-folding
    // (isSafeFromGroupingSetNullExtension's foldsToConst leg), not via any aggregate. Without that
    // leg, ordinary aggregate arithmetic like `coalesce(count(*), 0)` was wrongly nullable.

    @Test
    fun `aggregate arithmetic over IMMUTABLE-folding literals stays non-null alongside a nullable key`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        """
        SELECT
          coalesce(count(*), 0) AS c1,
          greatest(count(*), 1) AS c2,
          count(*) + length('ab') AS c3,
          lower(a) AS k
        FROM t
        GROUP BY ROLLUP(lower(a))
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(4)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
      assertThat(query.columns[3].notNull).isFalse()
    }

    @Test
    fun `count plus an implicit widening cast of a literal stays non-null while a different literal is the key`() {
      // The aggregate side deliberately uses a DIFFERENT literal (5, not 0) than the key (0): on
      // PostgreSQL 18, using the SAME literal on both sides makes the GROUP RTE mechanism
      // structurally substitute a nullingrels-flagged Var for the aggregate side's matching
      // subexpression too — verified live this substitution does NOT actually make that combined
      // value null at runtime (count(*) + 0::bigint is never null even in the summary row), but our
      // analyzer cannot see through that PG18-only substitution artifact to know that, so it
      // conservatively (and, for this exact same-literal case, needlessly) reports nullable. Using
      // a different literal avoids the substitution entirely and tests the real feature under test
      // — aggregate arithmetic over an implicit-cast literal — without hitting that artifact.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT count(*) + 5::bigint AS c, 0::bigint AS k FROM t GROUP BY ROLLUP((0::bigint))",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `count plus a literal stays non-null while an output-column-alias Const key is nullable`() {
      // "one" in GROUP BY ROLLUP(one) refers back to the output column alias — Postgres allows
      // referencing a target-list alias in GROUP BY. cp does not itself match the key (its own
      // ressortgroupref is 0) and must be proven safe via the Aggref it contains, exactly like the
      // simpler count(*)+1 cases elsewhere in this file — this is a second, independent example to
      // confirm behavior does not depend on which PostgreSQL version substitutes the key. cp uses a
      // different literal (2, not 1) than the key for the same reason as the test above — same
      // literal on both sides hits a PG18-only GROUP RTE substitution artifact this analyzer cannot
      // see through, unrelated to the aggregate-arithmetic feature under test here.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT 1 AS one, count(*) + 2 AS cp FROM t GROUP BY ROLLUP(one)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `ROW_NUMBER and a plain count window function stay non-null under a nullable ROLLUP key`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t2 (b TEXT NOT NULL, c INT NOT NULL)",
        "SELECT row_number() OVER () AS rn, count(*) OVER () AS w FROM t2 GROUP BY ROLLUP(b)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `aggregate division, GROUPING plus a literal, aggregate CASE, and LAG with a default all stay non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        """
        SELECT
          count(*) / greatest(count(a), 1) AS division,
          grouping(lower(a)) + length('ab') AS grouping_plus,
          CASE WHEN count(*) > 1 THEN count(*) ELSE 0::bigint END AS aggregate_case,
          lag(count(*), 1, 0::bigint) OVER () AS lag_with_default,
          lower(a) AS k
        FROM t
        GROUP BY ROLLUP(lower(a))
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(5)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
      assertThat(query.columns[3].notNull).isTrue()
      assertThat(query.columns[4].notNull).isFalse()
    }

    @Test
    fun `xmlelement wrapping a cast aggregate stays non-null alongside a nullable expression key`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT xmlelement(name x, count(*)::text) AS elem, lower(a) AS k FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `an IMMUTABLE function over a Const stays non-null as a non-key subexpression`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT count(*)::text || lower('X'::text) AS d, lower(a) AS k FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `a window function whose own argument is not dominated by an aggregate stays nullable`() {
      // Regression guard for the WindowFunc special case: it must NOT get Aggref's blanket safety.
      // first_value(b)'s argument is a bare Var — evaluated over already-grouped, null-extended
      // rows — so it is genuinely nullable even though b is NOT NULL. Verified live: all three
      // ROLLUP(b) rows return NULL for this column on both PG16 and PG18.
      val query = analyzeWithSchema(
        "CREATE TABLE t2 (b TEXT NOT NULL, c INT NOT NULL)",
        "SELECT first_value(b) OVER (ORDER BY grouping(b) DESC) AS fv FROM t2 GROUP BY ROLLUP(b)",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a folded-constant expression that IS the grouping key is still nullable via rule (b)`() {
      // Proves the foldsToConst leg did not break the sortGroupRef-based key check: lower('X'::text)
      // folds to a Const (lower is IMMUTABLE), which would make it "safe" under foldsToConst alone —
      // but it is also the literal grouping key, so rule (b) must still force it nullable. Verified
      // live: NULL in the ROLLUP summary row.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT lower('X'::text) AS k, count(*) AS n FROM t GROUP BY ROLLUP(lower('X'::text))",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `concat stays non-null under a nullable ROLLUP key because it is on the always-non-null list`() {
      // concat is non-strict — it renders a null argument as an empty string — so null-extending
      // one of its arguments cannot make the result null. Verified live on PG16, 17, and 18: never
      // NULL for the plain ROLLUP case, for a LEFT JOIN that null-extends one argument, and for an
      // empty input table.
      val query = analyzeWithSchema(
        "CREATE TABLE t2 (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT concat(a, '-', b) AS label, count(*) AS n FROM t2 GROUP BY ROLLUP(a, b)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `concat_ws with a null-extended separator argument is nullable — the P0 regression`() {
      // concat_ws's non-nullness depends on its separator (first argument) specifically, not on
      // whether SOME argument is non-null — unlike concat, it must NOT get the always-non-null
      // short-circuit. Here the separator IS the ROLLUP key, so it is null-extended for the
      // summary row, and concat_ws(NULL, 'x', 'y') really is NULL there (verified live on
      // PostgreSQL 16, 17, and 18). Before this fix, concat_ws's presence on the always-non-null
      // list made this wrongly non-null.
      val query = analyzeWithSchema(
        "CREATE TABLE t2 (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT concat_ws(a, 'x', 'y') AS k, count(*) AS n FROM t2 GROUP BY ROLLUP(a)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `concat_ws with a literal separator over a null-extended later argument is an accepted over-widening`() {
      // Verified live on PostgreSQL 16, 17, and 18: concat_ws(',', a, b) is NEVER null under
      // GROUP BY ROLLUP(a, b), including the fully null-extended summary row — a non-null literal
      // separator makes concat_ws total regardless of the other (even null-extended) arguments.
      // This analyzer still reports it nullable: the grouping-sets safety gate's generic rule
      // requires an Aggref/GroupingFunc/WindowFunc descendant to prove safety, and this expression
      // has none — concat_ws gets no special "only the separator argument matters" reasoning built
      // into the gate (that would need the same fragile structural-identity matching the
      // grouping-sets rewrite deliberately avoids elsewhere). This is a known, accepted
      // over-widening (the safe direction), not a bug to chase in this round.
      val query = analyzeWithSchema(
        "CREATE TABLE t2 (a TEXT NOT NULL, b TEXT NOT NULL)",
        "SELECT concat_ws(',', a, b) AS k, count(*) AS n FROM t2 GROUP BY ROLLUP(a, b)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `concat with VARIADIC over a null-extended NOT NULL array ROLLUP key is nullable`() {
      // arr is NOT NULL by schema, but IS the ROLLUP key, so it is null-extended for the summary
      // row, and concat(VARIADIC arr) really is NULL there — verified live on PostgreSQL 16, 17,
      // and 18. Before this fix, concat's VARIADIC form was wrongly reported non-null via the
      // isAlwaysNonNull short-circuit, which is unconditional and does not (and cannot) inspect
      // arguments at all.
      val query = analyzeWithSchema(
        "CREATE TABLE t4 (arr TEXT[] NOT NULL)",
        "SELECT concat(VARIADIC arr) AS c, count(*) AS n FROM t4 GROUP BY ROLLUP(arr)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `duplicate concat call that IS the ROLLUP key is nullable in both occurrences`() {
      // GROUP BY ROLLUP(concat(a, '-')) makes concat(a, '-') itself the grouping key. PostgreSQL's
      // grouping-set null-extension (setrefs.c) assigns :ressortgroupref to only ONE of the two
      // syntactically-identical target-list entries (verified live: the first), but its
      // null-extension matching is STRUCTURAL, not ressortgroupref-keyed — it null-extends every
      // target-list entry that structurally equals the grouping key, including the duplicate with
      // ressortgroupref 0. Verified live (PostgreSQL 16 and 17): both x and y are NULL in the
      // ROLLUP summary row, alongside 'p-'/'p-' and 'q-'/'q-' in the per-group rows. Before this
      // fix, the isAlwaysNonNull short-circuit for concat wrongly reported y non-null: that
      // short-circuit only proves concat tolerates a null ARGUMENT (it renders one as an empty
      // string), which says nothing about the case at hand, where the entire concat(...) call is
      // itself null-extended wholesale and never evaluated at all.
      val query = analyzeWithSchema(
        "CREATE TABLE gs_t (a TEXT NOT NULL, b INT NOT NULL)",
        "SELECT concat(a, '-') AS x, concat(a, '-') AS y, count(*) AS c FROM gs_t GROUP BY ROLLUP(concat(a, '-'))",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `concat over a bare-Var ROLLUP key stays non-null, unlike the identical-expression-as-key case above`() {
      // GROUP BY ROLLUP(a): the grouping key is the bare column a, NOT concat(a, '-') itself, so
      // concat(a, '-') is never a candidate for whole-expression substitution — only the nested a
      // reference can be null-extended, and concat tolerates that (renders it as an empty string).
      // Verified live (PostgreSQL 16, 17, and 18): 'p-'/'q-' per group, '-' (never NULL) in the
      // ROLLUP summary row. This is the positive control the fix for the case above must not
      // over-correct: concat's isAlwaysNonNull short-circuit must still apply here.
      val query = analyzeWithSchema(
        "CREATE TABLE gs_t (a TEXT NOT NULL, b INT NOT NULL)",
        "SELECT concat(a, '-') AS z, count(*) AS c FROM gs_t GROUP BY ROLLUP(a)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `duplicate bare Const grouping key stays non-null in the un-ref'd occurrence`() {
      // GROUP BY ROLLUP('ALL') matches the FIRST 'ALL'::text occurrence (l1) by ressortgroupref.
      // Verified live on EVERY supported version (16, 17, and 18): l1 is NULL, l2 stays 'ALL', in
      // the ROLLUP summary row — PostgreSQL's structural null-extension (setrefs.c) explicitly
      // refuses to match a bare Const node at all (see isSafeFromGroupingSetNullExtension's
      // KDoc), so the un-ref'd duplicate l2 is genuinely never NULL on any version. This pins the
      // asymmetry groupingKeyExpressions' Const/foldsToConst exclusion depends on — including l2
      // in that set would wrongly force it nullable.
      //
      // Unconditional across every supported version: the GroupRteSubstitution fix restores the
      // PostgreSQL 16/17 tree shape on PostgreSQL 18 too — both l1 and l2 arrive at
      // groupingKeyExpressions as the genuine bare Const '"ALL"' PostgreSQL 16/17 always showed
      // directly, not a Var referencing the synthesized "*GROUP*" RTE, so the Const-vs-key
      // distinction this exclusion depends on is available on every version, not lost to PG18's
      // pre-resolution.
      val query = analyzeWithSchema(
        "CREATE TABLE gs_t (a TEXT NOT NULL, b INT NOT NULL)",
        "SELECT 'ALL'::text AS l1, 'ALL'::text AS l2, count(*) AS c FROM gs_t GROUP BY ROLLUP('ALL'::text)",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `duplicate IMMUTABLE-folding call stays non-null in the un-ref'd occurrence`() {
      // upper('a') is IMMUTABLE over a literal argument, so PostgreSQL's early constant folding
      // (eval_const_expressions, well before setrefs.c's null-extension substitution) reduces
      // BOTH occurrences to a bare Const('A') before the substitution pass ever runs. Verified
      // live on EVERY supported version (16, 17, and 18): u1 is NULL, u2 stays 'A', in the ROLLUP
      // summary row — u2 is genuinely never NULL on any version (unlike a STABLE call over the
      // same shape, which does NOT fold and IS null-extended in both occurrences — see the
      // date_trunc case below).
      //
      // Unconditional across every supported version — same reasoning as the bare-Const case
      // above: the GroupRteSubstitution fix resolves both u1 and u2's target-list Var back to the
      // real upper('a') FuncExpr before groupingKeyExpressions ever runs, so both fold to the same
      // bare Const('A') on PostgreSQL 18 exactly as they always did on 16/17.
      val query = analyzeWithSchema(
        "CREATE TABLE gs_t (a TEXT NOT NULL, b INT NOT NULL)",
        "SELECT upper('a') AS u1, upper('a') AS u2, count(*) AS c FROM gs_t GROUP BY ROLLUP(upper('a'))",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `duplicate STABLE call is null-extended in BOTH occurrences — the sharpest proof matching is structural`() {
      // date_trunc('month', current_date) is STABLE, not IMMUTABLE, so — unlike upper('a') above —
      // it survives constant folding and remains a genuine, matchable subexpression when setrefs.c's
      // null-extension substitution runs. Since that substitution matches STRUCTURALLY rather than
      // by ressortgroupref, it null-extends d1 (the ref'd occurrence) AND d2 (the un-ref'd
      // duplicate) alike — this is the same mechanism, applied to a non-Const, non-folding
      // expression, that the concat bug this fix addresses exploited. Verified live (PostgreSQL 16
      // and 17): BOTH d1 and d2 are NULL in the ROLLUP summary row.
      //
      // PostgreSQL 18's own RUNTIME actually diverges here (verified live): only d1 (the entry
      // PostgreSQL assigned :ressortgroupref to) is NULL in the summary row; d2 keeps its real
      // value. Norm deliberately does NOT chase that: after GroupRteSubstitution, d2's target-list
      // Var resolves to the SAME structural expression as d1's — indistinguishable from the
      // ressortgroupref-carrying occurrence once resolved — so d2 is reported nullable on every
      // version, including 18. This is a safe, accepted over-widening (matches the true PG16/17
      // runtime, and is never wrong in the PG18-confident-NOT-NULL direction), not an oversight.
      val query = analyzeWithSchema(
        "CREATE TABLE gs_t (a TEXT NOT NULL, b INT NOT NULL)",
        """
        SELECT date_trunc('month', current_date) AS d1, date_trunc('month', current_date) AS d2, count(*) AS c
        FROM gs_t
        GROUP BY ROLLUP(date_trunc('month', current_date))
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `duplicate VOLATILE call is null-extended in both occurrences — matching is structural, not volatility`() {
      // random() is VOLATILE, yet it is still matched and null-extended in both occurrences —
      // proving the null-extension substitution is a pure structural-equality check
      // (PostgreSQL's equal()), never a volatility check that would otherwise justify treating a
      // VOLATILE call as immune to whole-expression substitution. Verified live (PostgreSQL 16 and
      // 17): both r1 and r2 are NULL in the ROLLUP summary row, and in the per-group rows, r1 and
      // r2 hold the SAME value within a row (the executor evaluates the target list once per row,
      // not once per occurrence).
      val query = analyzeWithSchema(
        "CREATE TABLE gs_t (a TEXT NOT NULL, b INT NOT NULL)",
        "SELECT random() AS r1, random() AS r2, count(*) AS c FROM gs_t GROUP BY ROLLUP(random())",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `aggregate domination — count(*) + 1 over a nullable ROLLUP key is never null`() {
      // count(*) never returns null (it has a non-null initial transition value), and the OpExpr
      // wrapping it (+ 1) is on the isNeverNullForNonNullInput safe-list, so n is provably
      // non-null regardless of a itself being nullable in the ROLLUP summary row. Verified live
      // (PostgreSQL 16 and 17): n is 3 in the summary row, never NULL.
      val query = analyzeWithSchema(
        "CREATE TABLE gs_t (a TEXT NOT NULL, b INT NOT NULL)",
        "SELECT count(*) + 1 AS n FROM gs_t GROUP BY ROLLUP(a)",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    // GitHub issue #241: on PostgreSQL 18, an RTE_GROUP range-table entry (:rtekind 9, alias
    // *GROUP*) rewrites EVERY target-list occurrence of a grouping-key expression — not merely the
    // one entry PostgreSQL assigns :ressortgroupref to — into a bare Var referencing that RTE. Prior
    // to the GroupRteSubstitution fix, the aggregate side of these two queries (c below) fell
    // through to "source column not found" for that buried Var and was wrongly reported nullable on
    // PostgreSQL 18 whenever the SAME literal/alias appeared on both the aggregate and the key side
    // — exactly what these two tests use, unlike the pre-existing tests elsewhere in this class that
    // deliberately used a DIFFERENT literal to sidestep the bug. These are the issue's own repro
    // shapes and must be unconditionally correct on every supported PostgreSQL version.

    @Test
    fun `issue 241 — the aggregate side stays non-null when the ROLLUP key reuses the identical literal`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT count(*) + 0::bigint AS c, 0::bigint AS k FROM t GROUP BY ROLLUP((0::bigint))",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `issue 241 — the aggregate side stays non-null when the ROLLUP key reuses the identical output alias`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT 1 AS one, count(*) + 1 AS cp FROM t GROUP BY ROLLUP(one)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `a plain GROUP BY on a constant-folding key is non-null — no ROLLUP, still a GROUP RTE on PostgreSQL 18`() {
      // A GROUP RTE is created for a PLAIN GROUP BY too, not only GROUPING SETS/CUBE/ROLLUP
      // (verified live). hasGroupingSets is false here — parseGroupRteMap alone cannot resolve this
      // key, because its groupexprs entry is a FUNCEXPR/CONST, not a bare VAR (see that method's
      // KDoc) — so this exercises the substitution fix on the code path GROUPING SETS tests never
      // touch.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT 0::bigint AS k, count(*) AS n FROM t GROUP BY 0::bigint",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `plain GROUP BY on the nullable side of a LEFT JOIN is nullable — the wrong-NOT-NULL regression`() {
      // Before the GroupRteSubstitution fix, this was a CONFIDENTLY WRONG NOT NULL on PostgreSQL 18,
      // not merely an over-widening: the target-list Var wrapping the GROUP RTE reference carries an
      // EMPTY :varnullingrels (PostgreSQL does not propagate the outer join's nulling relations onto
      // it), while parseGroupRteMap's coarser VAR-only resolution maps it back to the base column by
      // (varno, varattno) alone and discards the GROUP RTE's OWN :groupexprs entry — the one that
      // actually carries the correct, non-empty nulling relations from the LEFT JOIN. x is NOT NULL
      // by schema, but the join can still leave it absent for an unmatched t row.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, a TEXT NOT NULL);
        CREATE TABLE u (id INT PRIMARY KEY, x TEXT NOT NULL)
        """.trimIndent(),
        "SELECT b.x, count(*) AS n FROM t LEFT JOIN u b ON b.id = t.id GROUP BY b.x",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `ROLLUP on the nullable side of a LEFT JOIN is nullable — same wrong-NOT-NULL regression under ROLLUP`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, a TEXT NOT NULL);
        CREATE TABLE u (id INT PRIMARY KEY, x TEXT NOT NULL)
        """.trimIndent(),
        "SELECT b.x, count(*) AS n FROM t LEFT JOIN u b ON b.id = t.id GROUP BY ROLLUP(b.x)",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `a second ROLLUP key at varattno 2 resolves its own groupexprs entry, not the first key's`() {
      // Two grouping keys means the GROUP RTE's :groupexprs has two entries: index 0 for u.x (the
      // FIRST key, join-nullable), index 1 for the literal 5::bigint (the SECOND key, always
      // non-null). c's aggregate arithmetic reuses the literal 5, so PostgreSQL rewrites THAT
      // occurrence to a Var whose :varattno is 2 (1-based) — resolving it correctly requires
      // picking groupexprs[1] (the Const), not groupexprs[0] (u.x). A duplicate-of-a-raw-Var
      // detection shortcut (matching k1's own un-substituted Var structurally) cannot rescue this
      // case the way it does for a bare duplicate key — c's Var sits inside an OpExpr, a
      // structurally DIFFERENT shape from k1's bare Var, so only correct varattno-to-list-index
      // resolution can prove c non-null. The columns[0] (c) assertion is the ONLY one that
      // discriminates an off-by-one or reversed-order bug in either the parser or the substitution:
      // such a bug would resolve c's buried Var to u.x instead of the Const — genuinely nullable via
      // the LEFT JOIN — and wrongly report c nullable instead of non-null. columns[1] (k1) is NOT a
      // discriminator despite also referencing u.x: k1 IS the grouping key itself (its own
      // :ressortgroupref is 1, in groupingSortGroupRefs), so isEffectivelyNonNull's first condition
      // (NodeTreeNullabilityAnalyzer.kt's groupingSortGroupRefs check) forces it nullable before its
      // substituted expression is ever consulted — an off-by-one bug could resolve k1 to the WRONG
      // groupexprs entry and it would still come out nullable regardless. columns[1]/[2] are kept as
      // controls (proving both keys are still correctly nullable), not because they catch this bug.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, a TEXT NOT NULL);
        CREATE TABLE u (id INT PRIMARY KEY, x TEXT NOT NULL)
        """.trimIndent(),
        """
        SELECT count(*) + 5::bigint AS c, u.x AS k1, 5::bigint AS k2
        FROM t LEFT JOIN u ON u.id = t.id
        GROUP BY ROLLUP(u.x, 5::bigint)
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isFalse()
    }

    // Negative controls: the substitution fix must not accidentally rescue any of these from the
    // nullability GROUP BY ROLLUP genuinely imposes on them — each is a case the fix touches (a
    // grouping-key expression that appears more than once, or in more than one shape) but that must
    // stay exactly as nullable after the fix as before it.

    @Test
    fun `duplicate bare Var grouping key stays nullable in both occurrences`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT a AS k1, a AS k2, count(*) AS c FROM t GROUP BY ROLLUP(a)",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `duplicate lower(a) expression key stays nullable in both occurrences`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT lower(a) AS k1, lower(a) AS k2, count(*) AS c FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `the key column and a derived expression over it both stay nullable`() {
      // k is the grouping key itself (sortGroupRef match); d is a DIFFERENT expression built on top
      // of the same key (upper(lower(a)), not lower(a) itself) — d's own sortGroupRef is 0 and it
      // only matches structurally, deep inside upper(...), which only isSafeFromGroupingSetNullExtension's
      // tree walk over the SUBSTITUTED expression can catch.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL)",
        "SELECT lower(a) AS k, upper(lower(a)) AS d, count(*) AS c FROM t GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  internal inner class WindowFunctions {

    @Test
    fun `a plain source column stays non-null alongside a window function`() {
      // Not itself a window-function nullability fact -- "id" here is NOT NULL by its own
      // column definition and untouched by the window function beside it. Kept as a sanity
      // check that a window function's presence never widens an unrelated column's own answer.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT id, row_number() OVER() AS rn FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("windowFunctionCases")
    fun `computes the correct nullability for a window function's own result column`(testCase: WindowFunctionCase) {
      val query = analyzeWithSchema(testCase.schema, testCase.sql)
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[1].notNull).isEqualTo(testCase.expectedNotNull)
    }

    fun windowFunctionCases(): List<WindowFunctionCase> = listOf(
      WindowFunctionCase(
        description = "ROW_NUMBER is non-null",
        sql = "SELECT id, row_number() OVER() AS rn FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "RANK is non-null",
        sql = "SELECT id, rank() OVER(ORDER BY id) AS rnk FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "DENSE_RANK is non-null",
        sql = "SELECT id, dense_rank() OVER(ORDER BY id) AS drnk FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "NTILE is non-null",
        sql = "SELECT id, ntile(4) OVER(ORDER BY id) AS bucket FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "PERCENT_RANK is non-null",
        sql = "SELECT id, percent_rank() OVER(ORDER BY id) AS pr FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "CUME_DIST is non-null",
        sql = "SELECT id, cume_dist() OVER(ORDER BY id) AS cd FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "COUNT OVER is non-null",
        sql = "SELECT id, count(*) OVER() AS cnt FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "SUM OVER is nullable",
        sql = "SELECT id, sum(id) OVER() AS total FROM t",
        expectedNotNull = false,
      ),
      WindowFunctionCase(
        description = "LAG without default is nullable",
        sql = "SELECT id, lag(id) OVER(ORDER BY id) AS prev FROM t",
        expectedNotNull = false,
      ),
      WindowFunctionCase(
        description = "LAG with default is non-null",
        sql = "SELECT id, lag(id, 1, 0) OVER(ORDER BY id) AS prev FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "LAG with nullable default argument is nullable",
        schema = "CREATE TABLE t (id INT NOT NULL, fallback INT)",
        sql = "SELECT id, lag(id, 1, fallback) OVER(ORDER BY id) AS prev FROM t",
        expectedNotNull = false,
      ),
      WindowFunctionCase(
        description = "LEAD without default is nullable",
        sql = "SELECT id, lead(id) OVER(ORDER BY id) AS nxt FROM t",
        expectedNotNull = false,
      ),
      WindowFunctionCase(
        description = "LEAD with default is non-null",
        sql = "SELECT id, lead(id, 1, 0) OVER(ORDER BY id) AS nxt FROM t",
        expectedNotNull = true,
      ),
      WindowFunctionCase(
        description = "LEAD with nullable default argument is nullable",
        schema = "CREATE TABLE t (id INT NOT NULL, fallback INT)",
        sql = "SELECT id, lead(id, 1, fallback) OVER(ORDER BY id) AS nxt FROM t",
        expectedNotNull = false,
      ),
      WindowFunctionCase(
        description = "FIRST_VALUE is nullable",
        sql = "SELECT id, first_value(id) OVER(ORDER BY id) AS fv FROM t",
        expectedNotNull = false,
      ),
      WindowFunctionCase(
        description = "LAST_VALUE is nullable",
        sql = "SELECT id, last_value(id) OVER(ORDER BY id) AS lv FROM t",
        expectedNotNull = false,
      ),
      WindowFunctionCase(
        description = "NTH_VALUE is nullable",
        sql = "SELECT id, nth_value(id, 2) OVER(ORDER BY id) AS nv FROM t",
        expectedNotNull = false,
      ),
    )
  }

  @Nested
  inner class Subqueries {

    @Test
    fun `EXISTS subquery is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT exists(SELECT 1 FROM t) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `scalar subquery is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT (SELECT max(id) FROM t) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `IN subquery with non-null outer operand is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL); CREATE TABLE t2 (id INT NOT NULL)",
        "SELECT id IN (SELECT id FROM t2) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `IN subquery with nullable outer operand is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (category TEXT); CREATE TABLE t2 (category TEXT NOT NULL)",
        "SELECT category IN (SELECT category FROM t2) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `ANY sublink over a subquery with a nullable column is nullable even with a non-null outer operand`() {
      // Issue #239's own repro, verified live against PostgreSQL 17: `a = ANY (SELECT v FROM u)` is
      // NULL, not FALSE, when u.v is nullable, u has a row whose v IS NULL, and no row matches —
      // three-valued logic. Before this fix, the old ANY branch only checked the outer operand's
      // own nullability and reported this NOT NULL, which is wrong.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, a TEXT NOT NULL); CREATE TABLE u (v TEXT)",
        "SELECT a = ANY (SELECT v FROM u) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `ANY sublink whose outer operand is itself a SUBLINK does not shadow the outer subselect`() {
      // Live-verified repro (PostgreSQL 17 and 18): `SELECT EXISTS (SELECT v FROM u) = ANY (SELECT
      // b FROM x) FROM t` returns NULL when x.b is nullable, u.v is NOT NULL, and no row of x
      // matches — the same three-valued ANY_SUBLINK logic as the issue's own repro. This pins the
      // P0 fix specifically: the outer ANY_SUBLINK's :testexpr contains the nested EXISTS
      // sublink's own :subselect (over u), which textually precedes the outer sublink's OWN
      // :subselect (over x). Before extractFieldExpression became depth-one-aware, a raw
      // first-match scan for ":subselect {" found u's block (NOT NULL) instead of x's (nullable),
      // reporting this NOT NULL — the control below (u nullable too) would have flipped the wrong
      // way if that bug were still present, proving it reads x.b, not u.v.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL); CREATE TABLE u (v BOOLEAN NOT NULL); CREATE TABLE x (b BOOLEAN)",
        "SELECT EXISTS (SELECT v FROM u) = ANY (SELECT b FROM x) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `multi-column IN subquery is nullable even when every column is NOT NULL`() {
      // (a, b) IN (SELECT p, q FROM w)'s :testexpr is a BOOLEXPR combining one OPEXPR per column,
      // which has no single top-level operator OID this rule can trust — see
      // PgNodeExpression.SubLink.testExpressionOperatorOid's KDoc. Pinned deliberately, not left to
      // fall out of the single-column case by accident.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL); CREATE TABLE w (p INT NOT NULL, q INT NOT NULL)",
        "SELECT (a, b) IN (SELECT p, q FROM w) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `ANY sublink over a subquery with a LEFT JOIN-nullable column is nullable`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, code TEXT NOT NULL);
        CREATE TABLE d (id INT NOT NULL, code TEXT NOT NULL);
        CREATE TABLE e (id INT NOT NULL, d_id INT NOT NULL, code TEXT NOT NULL)
        """.trimIndent(),
        "SELECT code = ANY (SELECT e.code FROM d LEFT JOIN e ON e.d_id = d.id) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `ANY sublink over a UNION subquery is nullable, the conservative set-operation bail-out`() {
      // Every branch's own column is individually NOT NULL, but the union across branches is not
      // traced — the same conservative bail-out buildSubqueryColumnNotNull already applies to a
      // FROM-clause subquery RTE (see that method's KDoc) applies here too.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, a TEXT NOT NULL);
        CREATE TABLE u1 (v TEXT NOT NULL);
        CREATE TABLE u2 (v TEXT NOT NULL)
        """.trimIndent(),
        "SELECT a = ANY (SELECT v FROM u1 UNION SELECT v FROM u2) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `correlated ANY sublink whose subselect target list is an outer reference is nullable`() {
      // SELECT t.a FROM u's target-list Var refers to the OUTER query's t (levelsUp 1), not u's
      // own range table — resolving it against u's own schema (as the pre-fix code would have,
      // absent the levelsUp guard) risks a varno collision: u's OWN varno-1/attno-2 column
      // (dummy2, NOT NULL below) is a completely different column that happens to share the same
      // (varno, varattno) pair as t.a purely because each query block numbers its range table
      // independently starting from 1. The correct answer is "not proven" (nullable), regardless
      // of what u's own schema says.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, a INT NOT NULL);
        CREATE TABLE u (dummy1 INT, dummy2 INT NOT NULL)
        """.trimIndent(),
        "SELECT a = ANY (SELECT t.a FROM u) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `LATERAL derived table column over a nullable outer column is nullable, fixing a real collision`() {
      // s's own subselect body (`SELECT t.a AS x FROM u`) has ONE local range-table entry, u, at
      // local varno 1. t.a is an outer reference (varlevelsup 1) whose :varno ALSO happens to be
      // 1, because it indexes the OUTER query's OWN rtable position for t — each query block
      // numbers its range table independently starting from 1, so this collision is not contrived,
      // it is the general case. Before the levelsUp guard, isSourceColumnNotNull for this Var
      // resolved against the SUBQUERY's own range table (u), i.e. against u.junk's NOT NULL
      // constraint, which has nothing to do with t.a. PostgreSQL's real answer here is NULL, since
      // t.a is nullable — pinning that the guard fixes this, not merely widens it in this case.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT); CREATE TABLE u (junk TEXT NOT NULL)",
        "SELECT s.x FROM t CROSS JOIN LATERAL (SELECT t.a AS x FROM u) s",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `LATERAL derived table column referencing a NOT NULL outer column is still conservatively nullable`() {
      // Identical shape to the test above, except t.a is NOT NULL here — PostgreSQL's real answer
      // is therefore NEVER NULL (t.a can never be null, so s.x can't either). The blunt levelsUp
      // guard cannot tell this case apart from the one above: resolving an outer-reference Var
      // against the INNER block's own range table (u) is unsound in BOTH directions — it could
      // read a NOT NULL column that has nothing to do with the real (nullable) source, as above,
      // or, as here, it could just as easily have collided with a NULLABLE column of u and
      // under-reported a genuinely NOT NULL outer column. Correctly distinguishing this case would
      // require resolving levelsUp > 0 Vars against the ENCLOSING query's own range table, which
      // this analyzer's architecture does not support (see the levelsUp guard's own KDoc) — nested
      // blocks are analyzed eagerly into memoized maps with no live handle back to an enclosing
      // scope, and the varlevelsup chain has its own traps (set-operation branches the analyzer
      // never enters as their own level; CTE bodies not in the chain at all) where mis-counting
      // would resolve a Var against the WRONG table entirely, strictly worse than this widening.
      // main's earlier NOT NULL answer for this exact shape was accidental — a coincidence of
      // t.a's own NOT NULL constraint matching what u.junk's collision happened to resolve to, not
      // a real proof — not a real signal this analyzer intentionally computed.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a TEXT NOT NULL); CREATE TABLE u (junk TEXT NOT NULL)",
        "SELECT s.x FROM t CROSS JOIN LATERAL (SELECT t.a AS x FROM u) s",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `NOT IN subquery with a nullable inner column is nullable`() {
      // `a NOT IN (subquery)` does NOT compile to an ALL_SUBLINK — verified live (PostgreSQL 17)
      // against pg_rewrite.ev_action: it is a BOOLEXPR :boolop not wrapping an ordinary
      // ANY_SUBLINK (subLinkType 2, operName "="), i.e. `NOT (a = ANY (subquery))`. This test pins
      // that the BoolExpr branch's `expression.arguments.all(recurse)` correctly propagates the
      // wrapped ANY_SUBLINK's own nullability (NULL NOT is NULL) rather than the wrapping NOT
      // somehow making it non-null: u.v is nullable, so the inner ANY_SUBLINK is nullable, so the
      // whole NOT IN expression is nullable too.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, a TEXT NOT NULL); CREATE TABLE u (v TEXT)",
        "SELECT a NOT IN (SELECT v FROM u) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `ANY sublink is nullable when the comparison operator is not on the never-null safe-list`() {
      // jsonb has a real, STRICT `=` operator, but it is not on NeverNullSafeLists.
      // NEVER_NULL_OPERATOR_SIGNATURES — isStrict alone is not sufficient proof (see
      // isNeverNullForNonNullInput's own KDoc), so even with a non-null outer operand and a
      // provably non-null single-column subquery result, this must stay nullable.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, a JSONB NOT NULL); CREATE TABLE u (v JSONB NOT NULL)",
        "SELECT a = ANY (SELECT v FROM u) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `two levels of nested ANY sublinks resolve to a precise NOT NULL, well within the depth budget`() {
      // Two levels of ANY_SUBLINK nesting terminate and resolve correctly. Note this alone does
      // NOT pin the depth guard's necessity — a sublink's :subselect is always a genuine substring
      // of its parent's own text (see PgNodeTreeParser), so this recursion is provably bounded by
      // input length regardless of any explicit budget, and two levels resolve identically with
      // the budget removed entirely. See the next test for a case that actually depends on the
      // budget's value.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, a BOOLEAN NOT NULL);
        CREATE TABLE u (v BOOLEAN NOT NULL);
        CREATE TABLE x (w BOOLEAN NOT NULL)
        """.trimIndent(),
        "SELECT a = ANY (SELECT (v = ANY (SELECT w FROM x)) FROM u) AS result FROM t",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `four levels of nested ANY sublinks exceed the depth budget and degrade to nullable`() {
      // Every level here uses a NOT NULL boolean column and the safe-listed, strict, total boolean
      // `=` operator, so the semantically correct answer — and the answer this analyzer WOULD give
      // with a larger (or no) budget, verified by temporarily raising SUBLINK_ANALYSIS_DEPTH_BUDGET
      // during development — is NOT NULL, all the way out. With the budget at 3, the 4th (innermost)
      // ANY_SUBLINK's own callback is invoked at depth 0 — isSubLinkSubqueryColumnNotNull's `depth
      // <= 0` guard fires before it even looks at u4, forcing that innermost sublink nullable, which
      // cascades outward through every enclosing level. This is the case the two-level test above
      // cannot exercise: it depends on the budget's actual VALUE, not merely on recursion
      // terminating — raising the budget (or removing it) flips this test's expected answer.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, a BOOLEAN NOT NULL);
        CREATE TABLE u1 (v1 BOOLEAN NOT NULL);
        CREATE TABLE u2 (v2 BOOLEAN NOT NULL);
        CREATE TABLE u3 (v3 BOOLEAN NOT NULL);
        CREATE TABLE u4 (v4 BOOLEAN NOT NULL)
        """.trimIndent(),
        """
        SELECT a = ANY (
          SELECT (v1 = ANY (
            SELECT (v2 = ANY (
              SELECT (v3 = ANY (
                SELECT v4 FROM u4
              )) FROM u3
            )) FROM u2
          )) FROM u1
        ) AS result FROM t
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `subquery in FROM`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "SELECT * FROM (SELECT id, name FROM t) sub",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `set-returning function in FROM`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT * FROM generate_series(1, 10) AS x",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `correlated scalar subquery`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL); CREATE TABLE t2 (id INT NOT NULL, t_id INT NOT NULL, val INT NOT NULL)",
        "SELECT id, (SELECT sum(val) FROM t2 WHERE t2.t_id = t.id) AS total FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `a recursive CTE column that only becomes nullable through later iterations is reported nullable`() {
      // Verified live on PostgreSQL: this query produces rows 1|1, 1|<NULL>, <NULL>|<NULL> — "n"
      // IS genuinely nullable, even though the seed term's own "n" (1) is NOT NULL. Analyzing the
      // recursive term ("SELECT m, NULL::int FROM r WHERE n < 5") only ONCE against the SEED's
      // nullability (n=NOT NULL, m=NOT NULL) misses this: the recursive term's own "n" column reads
      // "m" from the PREVIOUS iteration, which becomes nullable starting at the second iteration
      // (its own second column is a literal NULL) — a fixpoint over the whole recursion is required
      // to see that "m" (and therefore "n") widens to nullable.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        """
        WITH RECURSIVE r(n, m) AS (
          SELECT 1, 1
          UNION ALL
          SELECT m, NULL::int FROM r WHERE n < 5
        )
        SELECT n, m FROM r
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `CTE named recursive_cte is not misread as RECURSIVE keyword`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "WITH recursive_cte AS (SELECT id, name FROM t) SELECT * FROM recursive_cte",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `chained data-modifying CTEs referencing earlier CTE with trailing no-RETURNING CTE`() {
      // Reproduces #202: a later data-modifying CTE ("inserted") references an earlier CTE
      // ("input"), and a trailing CTE ("logged") has no RETURNING clause at all.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE t2 (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE log_table (id SERIAL NOT NULL, message TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH input AS (
          SELECT id, name FROM t WHERE id = -1
        ),
        inserted AS (
          INSERT INTO t2(name) SELECT name FROM input RETURNING *
        ),
        logged AS (
          INSERT INTO log_table(message) SELECT 'logged' FROM inserted ON CONFLICT DO NOTHING
        )
        SELECT * FROM inserted
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `forward-referencing data-modifying CTE under WITH RECURSIVE`() {
      // Reproduces #203: "ins" is a data-modifying CTE whose body references "later", a CTE
      // declared AFTER it in the same WITH RECURSIVE clause. A prefix built only from preceding
      // CTE definitions omits "later" and fails to prepare; the probe must use the full WITH
      // clause instead.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        """
        WITH RECURSIVE ins AS (
          INSERT INTO t (name) SELECT lbl FROM later RETURNING id
        ),
        later AS (
          SELECT 'q'::TEXT AS lbl
        )
        SELECT id FROM ins
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `forward-referencing data-modifying CTE alongside a sibling that references it`() {
      // Reproduces the mixed case that motivated using the FULL WITH clause as the probe prefix
      // (rather than just extending the preceding-definitions prefix to include later CTEs):
      // "ins" references the later-declared "later", while "uses" references "ins" itself. The
      // full WITH clause resolves both directions at once.
      val query = analyzeWithSchema(
        "CREATE TABLE t2 (id SERIAL NOT NULL, name TEXT NOT NULL)",
        """
        WITH RECURSIVE
          ins AS (INSERT INTO t2(name) SELECT lbl FROM later RETURNING id),
          uses AS (SELECT id FROM ins),
          later AS (SELECT 'q'::TEXT AS lbl)
        SELECT id FROM uses
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `forward-referencing data-modifying CTE returns correct nullability for a nullable column`() {
      // Guards against a fix that merely avoids the "views must not contain data-modifying
      // statements in WITH" crash without actually consulting real column metadata: "bio" is
      // nullable in the schema, so RETURNING bio must report nullable even though the CTE
      // that supplies the forward reference ("later") is itself resolvable.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL, bio TEXT)",
        """
        WITH RECURSIVE ins AS (
          INSERT INTO t (name, bio) SELECT lbl, NULL FROM later RETURNING id, bio
        ),
        later AS (
          SELECT 'q'::TEXT AS lbl
        )
        SELECT id, bio FROM ins
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `plain WITH does not let a later CTE shadow a base table referenced by an earlier body`() {
      // Regression guard: the full-WITH-clause probe prefix must not be tried FIRST for a
      // non-recursive WITH clause. "src" is both a base table AND the name of a CTE declared
      // AFTER "upd". A plain WITH clause resolves "upd"'s reference to "src" against the base
      // table (a later CTE never shadows for an earlier body) — verified against real Postgres
      // by inserting distinguishable rows: the query returns the base table's value, not the
      // CTE's, so "c" must report the base table src.name's nullability (nullable).
      val query = analyzeWithSchema(
        """
        CREATE TABLE other (name TEXT NOT NULL);
        CREATE TABLE src (name TEXT);
        CREATE TABLE t (id SERIAL PRIMARY KEY, name TEXT)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = src.name FROM src RETURNING src.name AS c
        ),
        src AS (SELECT name FROM other)
        SELECT c FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `WITH RECURSIVE genuinely lets a later CTE shadow a base table of the same name`() {
      // Companion to the plain-WITH test above, same shape but WITH RECURSIVE: Postgres makes
      // every CTE name in a WITH RECURSIVE clause visible to every other CTE's body (this is
      // what permits forward and mutual references), so "src" in "upd" resolves to the sibling
      // CTE "src" (backed by "other", whose name column is NOT NULL) instead of the base table
      // "src" (nullable name) — verified against real Postgres by inserting distinguishable
      // rows: the query returns the CTE's value, not the base table's, so "c" must report the
      // CTE's column nullability (non-null).
      val query = analyzeWithSchema(
        """
        CREATE TABLE other (name TEXT NOT NULL);
        CREATE TABLE src (name TEXT);
        CREATE TABLE t (id SERIAL PRIMARY KEY, name TEXT)
        """.trimIndent(),
        """
        WITH RECURSIVE upd AS (
          UPDATE t SET name = src.name FROM src RETURNING src.name AS c
        ),
        src AS (SELECT name FROM other)
        SELECT c FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `data-modifying CTE body with its own nested WITH still resolves sibling shadowing correctly`() {
      // "upd"'s body carries its own nested WITH ("helper") and a FROM clause, so
      // convertDmlCteBodyToSelect converts it to a real join-preserving SELECT — reattaching
      // "helper" verbatim in front of "SELECT src.name AS c FROM t, src, helper" — which then
      // goes through the same node-tree analysis as any other CTE, resolving "src" against the
      // sibling CTE (declared before "upd", so it shadows the base table normally) rather than a
      // metadata probe that would be blind to this either way. Verified against real Postgres by
      // inserting a NULL row into "other": the sibling CTE "src" wins, so "c" must be nullable.
      val query = analyzeWithSchema(
        """
        CREATE TABLE other (name TEXT);
        CREATE TABLE src (name TEXT NOT NULL);
        CREATE TABLE t (id SERIAL PRIMARY KEY, name TEXT)
        """.trimIndent(),
        """
        WITH src AS (SELECT name FROM other),
        upd AS (
          WITH helper AS (SELECT 1 AS one)
          UPDATE t SET name = src.name FROM src, helper RETURNING src.name AS c
        )
        SELECT c FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `WITH RECURSIVE variant of nested-WITH shadowing also resolves the sibling CTE`() {
      // Same shape as the test above, but WITH RECURSIVE. Verified against real Postgres
      // (inserting a NULL row into "other"): the sibling CTE "src" wins here too — RECURSIVE
      // does not change which "src" a body sharing scope with a PRECEDING sibling resolves to,
      // only whether FOLLOWING siblings are visible — so "c" must be nullable, same as the
      // plain-WITH case.
      val query = analyzeWithSchema(
        """
        CREATE TABLE other (name TEXT);
        CREATE TABLE src (name TEXT NOT NULL);
        CREATE TABLE t (id SERIAL PRIMARY KEY, name TEXT)
        """.trimIndent(),
        """
        WITH RECURSIVE src AS (SELECT name FROM other),
        upd AS (
          WITH helper AS (SELECT 1 AS one)
          UPDATE t SET name = src.name FROM src, helper RETURNING src.name AS c
        )
        SELECT c FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `no-RETURNING data-modifying CTE with its own nested WITH at a non-zero index`() {
      // "logged" is the SECOND CTE (index > 0, so the bare-body candidate that commit d4f8d7c
      // relied on is never tried, by design) and has no RETURNING clause, so the true-scope
      // probe ("SELECT * FROM logged") itself fails to prepare. This exercises the further
      // fallback: "<full clause> SELECT 1" confirms the WITH clause is otherwise sound, so a
      // norm_stub is returned for "logged" instead of aborting generation.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE log_table (id SERIAL NOT NULL, message TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH seed AS (
          SELECT 'hello'::TEXT AS message
        ),
        logged AS (
          WITH helper AS (SELECT 1 AS one)
          INSERT INTO log_table(message) SELECT message FROM seed, helper ON CONFLICT DO NOTHING
        )
        SELECT id, name FROM t
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `quoted mixed-case CTE name is preserved verbatim in the true-scope probe`() {
      // Proves CteDefinition.rawName (not the quote-stripped .name) is used for the
      // true-scope probe: "MyIns"'s body has its own nested WITH, forcing the true-scope
      // fallback ("SELECT * FROM <rawName>"). If the quote-stripped name were used instead,
      // "FROM MyIns" (unquoted) would fold to lowercase and fail to find the quoted,
      // mixed-case relation "MyIns" — falling through to the no-RETURNING fallback and
      // fabricating a single unrelated "norm_stub" column, which would then make the outer
      // query's reference to "id" fail to resolve against the stubbed CTE, aborting generation
      // entirely instead of merely losing nullability precision.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        """
        WITH "Later" AS (
          SELECT 'x'::TEXT AS lbl
        ),
        "MyIns" AS (
          WITH helper AS (SELECT 1 AS one)
          INSERT INTO t(name) SELECT lbl FROM "Later", helper RETURNING id
        )
        SELECT id FROM "MyIns"
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      // Verified directly against the driver: PostgreSQL's target-list origin tracking traces
      // "id" all the way through "SELECT * FROM \"MyIns\"" back to t.id, so isNullable reports
      // NOT NULL precisely here (tableName came back as "t", not unknown) — a bare column
      // RETURNING with no intervening expression preserves lineage. "MyIns" is a plain INSERT
      // (no FROM/USING/MERGE join in the OUTER statement, only inside its own SELECT source),
      // so it never reaches convertDmlCteBodyToSelect's join-preserving conversion and stays on
      // the probe/stub path — which is fine specifically because INSERT's RETURNING sees only
      // the just-inserted row: there is no outer join here for the probe to be blind to. The
      // probe/stub path's fundamental blind spot to outer-join null extension (documented on
      // PgCatalogLoader.buildSelectStub) does not apply to this test.
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `UPDATE FROM LEFT JOIN RETURNING joined column inside a data-modifying CTE`() {
      // A metadata probe (PreparedStatement.getMetaData().isNullable) reports base-table
      // attnotnull — b.val is NOT NULL in the schema — and is blind to the LEFT JOIN
      // null-extending it at runtime. Verified against real Postgres by inserting an "a" row
      // with no matching "b" row: the query returns v = NULL, so this MUST be nullable. Before
      // convertDmlCteBodyToSelect existed, the probe/stub path reported this NOT NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = 'x' FROM a LEFT JOIN b ON b.k = a.k RETURNING b.val AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `UPDATE FROM LEFT JOIN RETURNING joined column, body with its own nested WITH`() {
      // Same shape as the test above, but "upd"'s body carries its own nested WITH ("helper"),
      // exercising convertDmlCteBodyToSelect's nested-WITH reattachment path (WITH helper AS
      // (...) SELECT b.val AS v FROM t, a LEFT JOIN b ON ..., helper) rather than the plain
      // conversion path. Verified against real Postgres the same way: v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH upd AS (
          WITH helper AS (SELECT 1 AS one)
          UPDATE t SET name = 'x' FROM a LEFT JOIN b ON b.k = a.k, helper RETURNING b.val AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `sibling CTE body with its own nested WITH keeps its LEFT JOIN nullability alongside a DML CTE`() {
      // Issue #205's exact repro. "j"'s body starts with its own nested WITH ("inner_cte"), so
      // isNonDataModifyingCteBody must classify it by "inner_cte"'s own main statement (a plain
      // SELECT) rather than stubbing "j" outright — a stub is built from
      // PreparedStatement.getMetaData().isNullable, which reflects base-table attnotnull and is
      // blind to the LEFT JOIN inside "j". Before the fix, "j" fell into the stub branch, whose
      // hasOuterJoin safety net (PgCatalogLoader.kt's forceAllNullable) then forced EVERY column
      // of "j" nullable — so "did", which is genuinely NOT NULL, was wrongly reported nullable;
      // "label" also came back nullable, but only because it was forced along with everything
      // else, not because the stub actually saw the LEFT JOIN. "did" comes from the LEFT JOIN's
      // preserved side (d), so it stays NOT NULL; "label" comes from the null-extended side (u),
      // so it must be nullable. Verified against real Postgres by inserting a "d" row with no
      // matching "u" row: the query returns did = <value>, label = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE d (id INT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL);
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH j AS (
          WITH inner_cte AS (SELECT 1)
          SELECT d.id AS did, u.label FROM d LEFT JOIN u ON u.id = d.id
        ),
        ins AS (
          INSERT INTO t (name) VALUES ('x') RETURNING id
        )
        SELECT did, label FROM j
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `data-modifying CTE body with its own nested WITH still takes the DML path alongside a sibling CTE`() {
      // Regression guard for the isNonDataModifyingCteBody classification change: a body shaped
      // "WITH helper AS (...) UPDATE ... FROM ... RETURNING ..." must still be recognized as
      // data-modifying (and go through convertDmlCteBodyToSelect's join-preserving conversion)
      // even with an unrelated sibling CTE present — not be misclassified as verbatim-safe by
      // the new nested-WITH handling. If it were misclassified, the embedded UPDATE would still
      // be present when this SQL is used to CREATE VIEW, which PostgreSQL rejects, and the whole
      // analysis would fall back to asserting every column NOT NULL — masking the LEFT JOIN's
      // real nullability. Verified against real Postgres by inserting an "a" row with no
      // matching "b" row: the query returns v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH seed AS (
          SELECT 1 AS one
        ),
        upd AS (
          WITH helper AS (SELECT 1 AS one)
          UPDATE t SET name = 'x' FROM a LEFT JOIN b ON b.k = a.k, helper RETURNING b.val AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `parenthesized nested-WITH CTE body keeps its LEFT JOIN nullability, alongside a data-modifying CTE`() {
      // Same shape as issue #205's repro, but "j"'s body is additionally wrapped in its own
      // parentheses (PostgreSQL accepts this: confirmed directly against a real server that
      // "j AS ((WITH inner_cte AS (...) SELECT ...))" parses and returns did = 1, label = NULL
      // for a "d" row with no matching "u" row). isNonDataModifyingCteBody must skip the extra
      // leading "(" the same way it already does for a plain (non-nested-WITH) parenthesized
      // body, then classify by the nested WITH's own main statement. A sibling data-modifying
      // CTE ("ins") is required here — a query with no DML at all never reaches
      // transformForViewCreation/isNonDataModifyingCteBody, since the direct CREATE VIEW
      // fast path in queryColumnNullability already succeeds for it.
      val query = analyzeWithSchema(
        """
        CREATE TABLE d (id INT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL);
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH j AS (
          (WITH inner_cte AS (SELECT 1)
          SELECT d.id AS did, u.label FROM d LEFT JOIN u ON u.id = d.id)
        ),
        ins AS (
          INSERT INTO t (name) VALUES ('x') RETURNING id
        )
        SELECT did, label FROM j
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `DELETE USING LEFT JOIN RETURNING joined column inside a data-modifying CTE`() {
      // Same defect as the UPDATE case, for DELETE ... USING. Verified against real Postgres
      // (inserting an "a" row with no matching "b" row): the query returns v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH del AS (
          DELETE FROM t USING a LEFT JOIN b ON b.k = a.k RETURNING b.val AS v
        )
        SELECT v FROM del
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `self-join LEFT JOIN RETURNING kills the rejected getTableName heuristic`() {
      // This is the shape that rules out a metadata heuristic considered and rejected in favor
      // of structural conversion: "t2" is an alias for the TARGET table "t" itself, sitting on
      // the nullable side of a LEFT JOIN. ResultSetMetaData.getTableName() reports the base
      // relation "t" for t2.name — indistinguishable, by name alone, from the actual DML target
      // "t" — so a heuristic keyed on "does getTableName() match the target table name" would
      // conclude t2.name is NOT the join side and keep it fabricated NOT NULL. Structural
      // conversion sidesteps this entirely: it operates on the real join structure via aliases,
      // not on relation names. Verified against real Postgres (inserting an "a" row with no
      // matching "k"): the query returns v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, k INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE a (k INT NOT NULL)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = t.name
          FROM a LEFT JOIN t t2 ON t2.k = a.k
          WHERE t.id = 1
          RETURNING t2.name AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE WHEN NOT MATCHED BY SOURCE THEN DELETE RETURNING source column inside a CTE`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // WHEN NOT MATCHED BY SOURCE fires for TARGET rows with no matching source row — the
      // shape convertMergeToSelect models as a LEFT JOIN. Verified against real Postgres: a
      // target row with no matching source row returns s.name = NULL through this RETURNING.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE s (id INT NOT NULL, name TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO t
          USING s ON t.id = s.id
          WHEN NOT MATCHED BY SOURCE THEN DELETE
          RETURNING s.name
        )
        SELECT name FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE without WHEN NOT MATCHED BY SOURCE keeps source column NOT NULL`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "MERGE RETURNING requires PostgreSQL 17+")
      // No "WHEN NOT MATCHED BY SOURCE" clause: convertMergeToSelect models this as a plain
      // (inner) join, since every row RETURNING can see has a genuine source match. Verified
      // against real Postgres: s.name is never NULL through this RETURNING.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE s (id INT NOT NULL, name TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO t
          USING s ON t.id = s.id
          WHEN MATCHED THEN UPDATE SET name = s.name
          RETURNING s.name
        )
        SELECT name FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `RETURNING star on UPDATE with a joined relation returns every relation's columns`() {
      // Verified against real Postgres (via psql \gdesc on the actual UPDATE): "RETURNING *" on
      // UPDATE ... FROM is NOT limited to the target table's columns — it expands to every
      // relation in the statement's scope, target AND joined, identically to a plain "SELECT *"
      // over the same FROM list (t.id, t.name, a.id, a.label — 4 columns, not 2). This is why
      // convertDmlToSelect passes RETURNING clauses through verbatim rather than qualifying a
      // bare "*" to the target alone (which would have produced the WRONG column count here).
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL, label TEXT)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = 'x' FROM a WHERE t.id = a.id RETURNING *
        )
        SELECT * FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(4)
      assertThat(query.columns[0].notNull).isTrue() // t.id
      assertThat(query.columns[1].notNull).isTrue() // t.name
      assertThat(query.columns[2].notNull).isTrue() // a.id
      assertThat(query.columns[3].notNull).isFalse() // a.label
    }

    @Test
    fun `target-table NOT NULL column stays NOT NULL alongside an outer-joined nullable column`() {
      // Guard against over-nullability: converting the CTE body to a join-preserving SELECT
      // must not make EVERYTHING nullable just because a join is present — only the columns
      // actually reached through the LEFT JOIN's nullable side. "t.id" comes from the target
      // row, which always exists (UPDATE only touches rows that exist), so it must stay NOT
      // NULL even though "v" (from the LEFT JOIN) is nullable. Verified against real Postgres.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = 'x' FROM a LEFT JOIN b ON b.k = a.k RETURNING t.id, b.val AS v
        )
        SELECT id, v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `LEFT JOIN RETURNING joined column survives an unbalanced parenthesis inside a string literal`() {
      // Regression guard: findTopLevelKeyword previously counted parens inside string literals,
      // so the "(" inside '\(' hid the real FROM from convertDmlToSelect, silently falling back
      // to the metadata probe/stub path — which is blind to the LEFT JOIN and fabricates NOT
      // NULL. Verified against real Postgres (inserting an "a" row with no matching "b" row):
      // the query returns v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = regexp_replace(name, '\(', '')
          FROM a LEFT JOIN b ON b.k = a.k
          RETURNING b.val AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE RETURNING source column survives an unbalanced parenthesis inside a string literal`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // Same defect as above, for MERGE: the "(" inside a SET expression's string literal must
      // not hide the real WHEN NOT MATCHED BY SOURCE clause from convertMergeToSelect. Verified
      // against real Postgres: a target row with no matching source row returns sname = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE mt (tid INT PRIMARY KEY, tname TEXT NOT NULL);
        CREATE TABLE ms (sid INT NOT NULL, sname TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO mt
          USING ms ON mt.tid = ms.sid
          WHEN MATCHED THEN UPDATE SET tname = ms.sname || '('
          WHEN NOT MATCHED BY SOURCE THEN DELETE
          RETURNING ms.sname
        )
        SELECT sname FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a string literal containing the word FROM with no real FROM clause does not abort generation`() {
      // REGRESSION guard vs main: before the lexer fix, an unvalidated conversion could replace
      // PostgreSQL's own parse with garbled text derived from misreading "from" inside a string
      // literal as if it introduced a real FROM clause — aborting generation entirely on SQL
      // PostgreSQL accepts fine. Verified against real Postgres: id = 1 (NOT NULL, as expected
      // for a SERIAL primary key) — there is no join here at all, real or otherwise.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        """
        WITH upd AS (
          UPDATE t SET name = 'copied from source' WHERE id = 1 RETURNING id
        )
        SELECT id FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `a line comment containing FROM between SET and the real FROM clause does not abort generation`() {
      // The comment must sit BETWEEN "SET ..." and the real "FROM" — a comment before "UPDATE"
      // is already skipped by the leading-whitespace/comment handling every DML-recognition
      // check starts with, on both old and new code, so it would not exercise this bug (that
      // shape doesn't demonstrate anything). This one forces findTopLevelKeyword to scan THROUGH
      // the comment while searching for the real FROM. Verified against real Postgres (inserting
      // an "a" row with no matching "b" row): the query returns v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = 'x' -- copied FROM elsewhere
          FROM a LEFT JOIN b ON b.k = a.k RETURNING b.val AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a block comment containing FROM between SET and the real FROM clause does not abort generation`() {
      // Same reasoning as the line-comment variant above. Verified against real Postgres
      // (inserting an "a" row with no matching "b" row): the query returns v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = 'x' /* set FROM the caller */
          FROM a LEFT JOIN b ON b.k = a.k RETURNING b.val AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a string literal containing the word from alongside a real FROM LEFT JOIN is still nullable`() {
      // Combines the two failure modes: the SET expression's literal contains "from" (which must
      // not be mistaken for, or hide, anything), and there IS a real FROM with a LEFT JOIN right
      // after it. Verified against real Postgres (inserting an "a" row with no matching "b"
      // row): the query returns v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH upd AS (
          UPDATE t SET name = 'from a' FROM a LEFT JOIN b ON b.k = a.k RETURNING b.val AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE WHEN NOT MATCHED BY SOURCE RETURNING star has correct column order, names, and nullability`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // Verified against real Postgres (\gdesc plus executing the query): "RETURNING *" expands
      // SOURCE-first — sid, sname, tid, tname — regardless of which WHEN clauses are present,
      // and for a target row with no matching source row the actual returned values are
      // [NULL, NULL, 1, 'target-row']. convertMergeToSelect must emit "FROM source RIGHT JOIN
      // target" (source first) to match — a target-first conversion would report the nullability
      // for the WRONG columns even though the metadata (names/types) could look plausible.
      val query = analyzeWithSchema(
        """
        CREATE TABLE mt (tid INT PRIMARY KEY, tname TEXT NOT NULL);
        CREATE TABLE ms (sid INT NOT NULL, sname TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO mt
          USING ms ON mt.tid = ms.sid
          WHEN NOT MATCHED BY SOURCE THEN DELETE
          RETURNING *
        )
        SELECT * FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(4)
      assertThat(query.columns.map { it.name }).containsExactly("sid", "sname", "tid", "tname")
      assertThat(query.columns[0].notNull).isFalse() // sid — from the null-extended source side
      assertThat(query.columns[1].notNull).isFalse() // sname — from the null-extended source side
      assertThat(query.columns[2].notNull).isTrue() // tid — target row always present
      assertThat(query.columns[3].notNull).isTrue() // tname — target row always present
    }

    @Test
    fun `literal text matching the WHEN NOT MATCHED BY SOURCE phrase does not trigger the LEFT JOIN model`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "MERGE RETURNING requires PostgreSQL 17+")
      // hasWhenNotMatchedBySourceClause must not misfire on a SET expression's string literal
      // that happens to contain the phrase "when not matched by source ". Verified against real
      // Postgres: with no genuine WHEN NOT MATCHED BY SOURCE clause, ms.sname is never NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE mt (tid INT PRIMARY KEY, tname TEXT NOT NULL);
        CREATE TABLE ms (sid INT NOT NULL, sname TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO mt
          USING ms ON mt.tid = ms.sid
          WHEN MATCHED THEN UPDATE SET tname = 'when not matched by source '
          RETURNING ms.sname
        )
        SELECT sname FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `data-modifying CTE preceded by a sibling CTE containing a closing parenthesis in a literal`() {
      // End-to-end companion to the SqlUtilsTest paren-in-literal coverage: the FIRST CTE's body
      // contains a ')' inside a string literal, which (before the lexer fix) corrupted
      // findMatchingCloseParenthesis's body-boundary detection for that CTE — parseCteClause
      // then stopped after that ONE (corrupted) definition, treating "upd" as part of the
      // garbled MAIN QUERY text instead of a second CTE. "upd" has a LEFT JOIN specifically so
      // this is visible: the garbled-query fallback (the top-level no-join-structure DML path,
      // "assume every column non-null" before issue #207's fix) happens to give the RIGHT answer
      // for a plain INSERT (as in the SqlUtilsTest e2e companion above), but gives the WRONG
      // answer here, where the true answer is nullable. Verified against real Postgres (inserting
      // an "a" row with no matching "b"
      // row): the query returns v = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT);
        CREATE TABLE a (k INT NOT NULL);
        CREATE TABLE b (k INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH note AS (
          SELECT 'closing )'::TEXT AS msg
        ),
        upd AS (
          UPDATE t SET name = 'x' FROM a LEFT JOIN b ON b.k = a.k RETURNING b.val AS v
        )
        SELECT v FROM upd
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `sibling CTE with closing paren in a literal still generates correctly for a plain INSERT`() {
      // Companion to the LEFT JOIN variant above and to the SqlUtilsTest unit coverage: proves
      // the fix for a shape with NO join at all, where the pre-fix bug's corruption happened to
      // be masked by the "assume non-null" fallback rather than causing a visibly wrong answer.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        """
        WITH note AS (
          SELECT 'closing )'::TEXT AS msg
        ),
        ins AS (
          INSERT INTO t(name) SELECT msg FROM note RETURNING id
        )
        SELECT id FROM ins
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `LEFT JOIN RETURNING joined column survives a SET-clause column named valid_from`() {
      // Regression guard: the "_" in "valid_from" did not count as an identifier character in
      // findTopLevelKeyword's word-boundary check, so "valid_from" matched the keyword "FROM"
      // at its own position — before the real "FROM a LEFT JOIN b" clause — corrupting
      // conversion (which then failed validation) and falling back to the metadata probe/stub,
      // which is blind to the LEFT JOIN and fabricated NOT NULL. Verified against real Postgres
      // (inserting an "a" row with no matching "b" row): the query returns bval = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, valid_from TEXT);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH u AS (
          UPDATE t SET valid_from='x' FROM a LEFT JOIN b ON b.id=a.id WHERE t.id=a.id
          RETURNING t.id, b.bval
        )
        SELECT id, bval FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `SET-clause column named returning_note no longer aborts generation`() {
      // REGRESSION guard vs main: before the word-boundary fix, "returning_note" matched
      // "RETURNING" as a keyword, making returningIndex point INSIDE the SET clause — earlier
      // than the join clause start computed from the (correctly found) later FROM — and
      // buildSelectFromDml's substring(joinClauseStart, returningIndex) threw
      // StringIndexOutOfBoundsException, aborting generation on SQL PostgreSQL itself accepts
      // fine. Verified against real Postgres: id = 1 (NOT NULL, as expected for a plain UPDATE
      // with no outer join at all).
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, returning_note TEXT);
        CREATE TABLE a (id INT NOT NULL)
        """.trimIndent(),
        """
        WITH u AS (
          UPDATE t SET returning_note = 'x' FROM a WHERE t.id = a.id RETURNING t.id
        )
        SELECT id FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `stub path forces every column nullable when RETURNING OLD-col accompanies a real LEFT JOIN`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // PostgreSQL 18's RETURNING OLD.col forces this body onto the stub path: the structural
      // conversion builds a plain SELECT where "OLD" is not a valid range variable, so it fails
      // to prepare and validatedConversion correctly rejects it. Before the stub-path safety
      // net, the metadata probe reported the UNRELATED sibling column b.bval as NOT NULL (blind
      // to the real LEFT JOIN elsewhere in the same body) even though oldname's own OLD-based
      // imprecision was already an accepted limitation. Verified against real Postgres (inserting
      // an "a" row with no matching "b" row): oldname = 'orig' (the target row always exists for
      // a plain UPDATE, so OLD.name is never actually null here), bval = NULL. The safety net
      // deliberately over-approximates — marking every stub column nullable once any outer join
      // is detected in the body, not just the ones actually reached through it — so oldname is
      // ALSO reported nullable here even though its true answer is NOT NULL: safe-direction
      // imprecision, not a regression, and a documented tradeoff (see PgCatalogLoader's
      // buildSelectStub and tryPrepareStub KDoc).
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH u AS (
          UPDATE t SET name = 'x' FROM a LEFT JOIN b ON b.id = a.id WHERE t.id = a.id
          RETURNING OLD.name AS oldname, b.bval
        )
        SELECT oldname, bval FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `MERGE detects WHEN NOT MATCHED BY SOURCE despite a comment abutting NOT and MATCHED`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      // skipOptionalKeyword previously required LITERAL whitespace immediately after each
      // keyword, so a comment directly abutting NOT and MATCHED with no surrounding whitespace
      // broke clause detection entirely, choosing a plain JOIN and fabricating NOT NULL for the
      // source column. Verified against real Postgres: id = 1 (NOT NULL, target row), sval =
      // NULL (nullable, no matching source row).
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE src (id INT NOT NULL, sval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO tgt
          USING src ON tgt.id = src.id
          WHEN NOT/*c*/MATCHED BY SOURCE THEN DELETE
          RETURNING tgt.id, src.sval
        )
        SELECT id, sval FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `INSERT with a LEFT JOIN in its own SELECT source reports NOT NULL, not fabricated nullable`() {
      // REGRESSION guard: the stub-path safety net previously fired for ANY body with a
      // detectable outer join, INSERT included — but an INSERT's RETURNING sees only the row
      // just inserted, and nothing in its own SELECT source (however joined) can null-extend
      // it. Verified against real Postgres: INSERT INTO b(id, bval) SELECT a.id, 'v' FROM a
      // LEFT JOIN b2 ON b2.id = a.id RETURNING id, bval returns id=1, bval='v' — both non-null —
      // despite the LEFT JOIN in its source. See the companion test below for the UPDATE shape,
      // where the net must still fire.
      val query = analyzeWithSchema(
        """
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL);
        CREATE TABLE b2 (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH u AS (
          INSERT INTO b (id, bval) SELECT a.id, 'v' FROM a LEFT JOIN b2 ON b2.id = a.id
          RETURNING id, bval
        )
        SELECT id, bval FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `UPDATE with a LEFT JOIN still reports nullable — the safety net must keep working`() {
      // Companion to the INSERT test above: confirms excluding INSERT from the safety net did
      // NOT also (over-broadly) exclude UPDATE, which genuinely needs it. Verified against real
      // Postgres (inserting an "a" row with no matching "b" row): the query returns bval = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH u AS (
          UPDATE t SET name = 'x' FROM a LEFT JOIN b ON b.id = a.id WHERE t.id = a.id
          RETURNING t.id, b.bval
        )
        SELECT id, bval FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `LEFT JOIN RETURNING joined column survives a SET-clause column named with two dollar signs`() {
      // Regression guard: the "$" between "b" and "c" in "a$b$c" was misread as opening a
      // "$b$"-tagged dollar-quote, swallowing the rest of the statement — including the real
      // "FROM a LEFT JOIN b" — as unterminated string content. Conversion then failed (or
      // produced garbage), falling back to the metadata probe/stub, which is blind to the LEFT
      // JOIN. Verified against real Postgres (inserting an "a" row with no matching "b" row):
      // the query returns bval = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, a${'$'}b${'$'}c TEXT);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH u AS (
          UPDATE t SET a${'$'}b${'$'}c = 'x' FROM a LEFT JOIN b ON b.id = a.id WHERE t.id = a.id
          RETURNING t.id, b.bval
        )
        SELECT id, bval FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `MERGE with a LEFT JOIN nested in its USING subquery reports the joined column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // Regression guard: hasOuterJoin previously scanned only paren depth 0, so a LEFT JOIN
      // nested inside the USING subquery went undetected — merge_action() in RETURNING already
      // forces this body onto the stub path (it isn't valid outside MERGE's own RETURNING, so
      // conversion to a plain SELECT fails to prepare and is rejected), and the stub then
      // fabricated NOT NULL for the joined column. Verified against real Postgres (sx has no row
      // matching src): act = 'UPDATE', id = 1, xval = NULL. The safety net's over-approximation
      // also demotes "id" (the target's PK, always present for a MATCHED row) to nullable here —
      // an accepted, documented tradeoff, since the stub cannot isolate which columns are
      // actually reached through the nested join (see buildSelectStub's KDoc).
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE src (sid INT NOT NULL);
        CREATE TABLE sx (sid INT NOT NULL, xval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO tgt
          USING (SELECT src.sid, sx.xval FROM src LEFT JOIN sx ON sx.sid = src.sid) s ON s.sid = tgt.id
          WHEN MATCHED THEN UPDATE SET tval = 'z'
          RETURNING merge_action() AS act, tgt.id, s.xval
        )
        SELECT act, id, xval FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `DELETE RETURNING OLD-col alongside an unrelated column no longer drags it into nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // FIX 1 regression guard: at e4679ff, forceAllNullable applied to the WHOLE stub once ANY
      // RETURNING item referenced OLD/NEW — so "id" (never touched by OLD/NEW at all) was
      // fabricated nullable purely because it shared a RETURNING list with "oldname". Verified
      // against real Postgres: DELETE FROM t WHERE id = 1 RETURNING OLD.name, t.id returns
      // oldname = 'orig' and id = 1 — BOTH genuinely NOT NULL for this exact row, but "id" is the
      // one this fix must stop fabricating nullable for; "oldname" itself is still forced
      // nullable (over-approximating in the safe direction, unchanged) since knowing OLD is
      // genuinely never-null for a DELETE specifically would require statement-kind-aware logic
      // this fix does not add — see oldOrNewReturningColumns's KDoc.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH d AS (
          DELETE FROM t WHERE id = 1 RETURNING OLD.name AS oldname, t.id
        )
        SELECT oldname, id FROM d
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `UPDATE RETURNING OLD-col alongside the target's own column stays NOT NULL for the target column`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Same regression as above, UPDATE form. Verified against real Postgres: UPDATE t SET name
      // = 'x' WHERE id = 1 RETURNING OLD.name AS oldname, t.id returns oldname = 'orig', id = 1 —
      // both genuinely NOT NULL (an UPDATE's target row always exists), but only "id" (untouched
      // by OLD/NEW) is asserted NOT NULL here; "oldname" is still forced nullable by design.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH u AS (
          UPDATE t SET name = 'x' WHERE id = 1 RETURNING OLD.name AS oldname, t.id
        )
        SELECT oldname, id FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `INSERT ON CONFLICT RETURNING OLD-col alongside an unrelated column stays NOT NULL for it`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Same regression, INSERT ON CONFLICT form. Verified against real Postgres: INSERT INTO t
      // (id, bval) VALUES (1, 'new') ON CONFLICT (id) DO UPDATE SET bval = 'updated' RETURNING
      // OLD.bval, t.id returns id = 1 (NOT NULL — the conflicting row's id, always present),
      // bval potentially NULL (a fresh insert has no OLD row) — asserting only on "id" here.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, bval TEXT)",
        """
        WITH u AS (
          INSERT INTO t (id, bval) VALUES (1, 'new')
          ON CONFLICT (id) DO UPDATE SET bval = 'updated'
          RETURNING OLD.bval AS oldbval, t.id
        )
        SELECT oldbval, id FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `DELETE USING RETURNING OLD-col alongside the target's own column stays NOT NULL for it`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Same regression, DELETE ... USING form. Verified against real Postgres: DELETE FROM t
      // USING a WHERE t.id = a.id RETURNING OLD.name AS oldname, t.id returns oldname = 'orig',
      // id = 1 — both genuinely NOT NULL — asserting only on "id" here (untouched by OLD/NEW).
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL)
        """.trimIndent(),
        """
        WITH d AS (
          DELETE FROM t USING a WHERE t.id = a.id RETURNING OLD.name AS oldname, t.id
        )
        SELECT oldname, id FROM d
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `RETURNING star alongside an OLD reference reports id and name NOT NULL via prosqlbody`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Before the prosqlbody cutover, a star item recognized alongside an OLD/NEW reference fell
      // back to forcing EVERY column nullable — an accepted tradeoff for the old text-based
      // machinery, since a star's real expansion width is unknown to it without asking PostgreSQL.
      // prosqlbody reads "id"/"name" as plain Vars against a real DELETE target row (never
      // rewritten into a stub, no OLD/NEW tagging on either), so it correctly reports both NOT
      // NULL per the schema's own constraint. "oldname" stays nullable regardless — this
      // analyzer's OLD-forcing rule is deliberately blanket (see PgNodeExpression.Var.returningType's
      // KDoc), over-conservative for a plain DELETE specifically (OLD always exists there) but
      // still safe. Verified live: DELETE FROM t WHERE id = 1 RETURNING OLD.name, t.* returns
      // oldname = 'orig', id = 1, name = 'orig' — all genuinely NOT NULL.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH d AS (
          DELETE FROM t WHERE id = 1 RETURNING OLD.name AS oldname, t.*
        )
        SELECT oldname, id, name FROM d
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `RETURNING WITH declared alias for OLD-NEW reports the NEW-tagged column NOT NULL via prosqlbody`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // PostgreSQL 18's RETURNING WITH (OLD AS o, NEW AS n) prologue declares custom names for the
      // pseudo-relations; PgNodeTreeParser.parseVar reads :varreturningtype directly off the
      // Var node regardless of which alias the SQL text used, so "o"/"n" need no special
      // recognition of their own the way the old text-based oldOrNewReturningColumns needed.
      // prosqlbody's NEW-tagged Var for "n.name" is a plain, ordinary reference for an UPDATE
      // (forceNewNullable only applies to DELETE and a MERGE with a DELETE action — see
      // NodeTreeNullabilityAnalyzer's own KDoc) — since "name" is declared NOT NULL, it correctly
      // reports newname NOT NULL. "oldname" stays nullable regardless — this analyzer's OLD-forcing
      // rule is deliberately blanket, over-conservative for a plain UPDATE specifically (OLD always
      // exists there) but still safe. Verified live: UPDATE t SET name = 'x' WHERE id = 1
      // RETURNING WITH (OLD AS o, NEW AS n) o.name AS oldname, n.name AS newname, t.id returns
      // oldname = 'orig', newname = 'x', id = 1 — all genuinely NOT NULL.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH u AS (
          UPDATE t SET name = 'x' WHERE id = 1
          RETURNING WITH (OLD AS o, NEW AS n) o.name AS oldname, n.name AS newname, t.id
        )
        SELECT oldname, newname, id FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `MERGE fed by a sibling CTE with an internal LEFT JOIN forces the joined column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // FIX 3: "pre" (a sibling CTE, not part of "m"'s own body text) contains a LEFT JOIN whose
      // null-extension is entirely invisible to any scan of "m"'s own text — "m" itself has no
      // join at all. merge_action() in RETURNING forces this body onto the stub path (not valid
      // outside MERGE's own RETURNING, so the structural conversion fails to prepare and is
      // rejected); before this fix, the stub's base-table attnotnull fabricated "bval" as NOT
      // NULL despite the real LEFT JOIN living in "pre". Verified against real Postgres (an "a"
      // row with no matching "b" row): act = 'UPDATE', bval = NULL, id = 1 (the target's own PK,
      // always present for a MATCHED row — also demoted to nullable here, an accepted tradeoff,
      // same as the existing nested-USING-subquery LEFT JOIN test above).
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH pre AS (
          SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id
        ),
        m AS (
          MERGE INTO tgt USING pre ON pre.id = tgt.id
          WHEN MATCHED THEN UPDATE SET tval = 'z'
          RETURNING merge_action() AS act, pre.bval, tgt.id
        )
        SELECT act, bval, id FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `MERGE fed by a double-quoted sibling reference still forces the joined column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // Issue #208, Gap 1: quoting an otherwise unremarkable lowercase sibling name ("pre")
      // used to defeat referencesAnyName entirely, since its underlying scan skipped
      // double-quoted identifiers as an opaque lexical token by design. referencesAnyName now
      // scans a quoted identifier's own contents instead. Verified against real Postgres 18 (an
      // "a" row with no matching "b" row, target "tgt" row id = 1): act = 'UPDATE', bval = NULL,
      // id = 1.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH pre AS (
          SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id
        ),
        m AS (
          MERGE INTO tgt USING "pre" ON "pre".id = tgt.id
          WHEN MATCHED THEN UPDATE SET tval = 'z'
          RETURNING merge_action() AS act, "pre".bval, tgt.id
        )
        SELECT act, bval, id FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `MERGE with a ROLLUP source forces the grouped column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // Issue #208, Gap 2: a ROLLUP supertotal row makes the grouped column NULL by definition,
      // matched into the target only via a COALESCE in the ON condition -- no LEFT/RIGHT/FULL
      // JOIN keyword and no WHEN NOT MATCHED BY SOURCE clause appears anywhere in the body for
      // the pre-existing detectors to find. hasGroupingSetConstruct now recognizes ROLLUP/CUBE/
      // GROUPING SETS as a third null-extending construct. Verified against real Postgres 18
      // (an "a" row with id = 1, tgt rows id = 1 and id = 2): the id = 1 row of the source
      // matches tgt id = 1 (sid = 1, not the supertotal), and the ROLLUP supertotal row (s.id =
      // NULL) matches tgt id = 2 via COALESCE(s.id, 2) = 2 -- two result rows, merge_action =
      // 'UPDATE' for both, {sid = 1, id = 1} and {sid = NULL, id = 2} (MERGE's own output order
      // across rows is unspecified; only the values were confirmed, not this ordering).
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO tgt USING (SELECT id, count(*) AS c FROM a WHERE id = 1 GROUP BY ROLLUP(id)) s
            ON tgt.id = COALESCE(s.id, 2)
          WHEN MATCHED THEN UPDATE SET tval = 'z'
          RETURNING merge_action(), s.id AS sid, tgt.id
        )
        SELECT * FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `MERGE fed by a transitive sibling chain forces the joined column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // Issue #208, Gap 3: "m" references "mid", which has no join of its own -- the LEFT JOIN
      // lives in "j", which is "mid"'s sibling, not "m"'s. The pre-existing one-level-deep
      // sibling-danger check stopped at "mid" and never saw "j". computeDangerousSiblingNames now
      // computes the danger set as a fixpoint over the whole WITH clause, so "mid" (which
      // references "j") joins the dangerous set first, then "m" (which references "mid") joins
      // next. Verified against real Postgres 18 (an "a" row with no matching "b" row): act =
      // 'UPDATE', bval = NULL, id = 1.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH j AS (SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id),
             mid AS (SELECT id, bval FROM j),
             m AS (MERGE INTO tgt USING mid ON mid.id = tgt.id
                   WHEN MATCHED THEN UPDATE SET tval = 'z'
                   RETURNING merge_action() AS act, mid.bval, tgt.id)
        SELECT * FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `MERGE fed by a CTE source reports the passed-through column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // Verified against real Postgres 18 (an "a" row with no matching "b" row): the INSERT
      // inserts one row into ins_target (id = 1, val = 'v', both columns genuinely NOT NULL for
      // the row that WAS inserted), and the MERGE returns act = 'UPDATE', mergedval = 'v', id = 1
      // — "mergedval" is, in fact, never NULL here. But PgCatalogLoader.mergeAbsentVarnos only
      // attributes a MERGE's join to a source relation that is itself a plain base table (an
      // :rtable entry with rtekind 0) — a CTE reference has no real relation OID or name EXPLAIN's
      // plan can be correlated against (it shows a "CTE Name", not a "Relation Name"), so this
      // shape falls back to reporting every column nullable rather than guessing. Accepted,
      // safe-direction cost for a MERGE fed by a CTE rather than a plain table, not a regression.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL);
        CREATE TABLE ins_target (id INT NOT NULL, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO ins_target (id, val) SELECT a.id, 'v' FROM a LEFT JOIN b ON b.id = a.id
          RETURNING id, val
        ),
        m AS (
          MERGE INTO tgt USING ins ON ins.id = tgt.id
          WHEN MATCHED THEN UPDATE SET tval = 'z'
          RETURNING merge_action() AS act, ins.val AS mergedval, tgt.id
        )
        SELECT * FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `MERGE fed by an INSERT ON CONFLICT sibling that RETURNS OLD-col forces the passed-through column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Issue #208 P1 follow-up: the seed's "!isInsertBody" exclusion (added for the precision
      // guard test above) is correct for an INSERT's ORDINARY target-column RETURNING, but an
      // INSERT's RETURNING can also read OLD./NEW., whose own conditional existence attnotnull
      // cannot see regardless of statement kind -- excluding EVERY INSERT body from the seed,
      // rather than only excluding hasNullExtendingConstruct's own-join trigger, silently dropped
      // this danger sign. computeDangerousSiblingNames now seeds separately on
      // oldOrNewReturningColumns (which also understands a RETURNING WITH (OLD AS alias, ...)
      // prologue), regardless of isInsertBody. "ins" is an INSERT ... ON CONFLICT DO UPDATE
      // RETURNING OLD.val -- OLD is NULL exactly when the row was freshly inserted (no prior
      // conflict) -- and "m" merely passes ins.oldval through. Verified against real Postgres 18
      // (an "a" row with no matching "b" row, no pre-existing "it2" row so the INSERT always
      // takes the fresh-insert branch): act = 'UPDATE', ov = NULL, id = 1.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL);
        CREATE TABLE it2 (id INT PRIMARY KEY, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO it2 (id, val) SELECT a.id, 'v' FROM a LEFT JOIN b ON b.id = a.id
          ON CONFLICT (id) DO UPDATE SET val = 'w'
          RETURNING id, OLD.val AS oldval
        ),
        m AS (
          MERGE INTO tgt USING ins ON ins.id = tgt.id
          WHEN MATCHED THEN UPDATE SET tval = 'z'
          RETURNING merge_action() AS act, ins.oldval AS ov, tgt.id
        )
        SELECT * FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `MERGE fed by an INSERT sibling using the RETURNING WITH OLD-alias prologue forces the column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Same danger sign as the unqualified-OLD sibling test above, but via PostgreSQL 18's
      // `RETURNING WITH (OLD AS alias, ...)` prologue instead of a bare `OLD.col` reference --
      // computeDangerousSiblingNames seeds on oldOrNewReturningColumns specifically because it
      // (unlike a bare referencesOldOrNew call) already understands this prologue, so an aliased
      // reference must trip the same seed. "ins" is an INSERT ... ON CONFLICT DO UPDATE
      // RETURNING WITH (OLD AS o) id, o.val AS oldval -- OLD is NULL exactly when the row was
      // freshly inserted -- and "m" merely passes ins.oldval through. Verified against real
      // Postgres 18 (an "a" row with no pre-existing "it2" row, so the INSERT always takes the
      // fresh-insert branch): act = 'UPDATE', ov = NULL, id = 1.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE it2 (id INT PRIMARY KEY, val TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO it2 (id, val) SELECT a.id, 'v' FROM a
          ON CONFLICT (id) DO UPDATE SET val = 'w'
          RETURNING WITH (OLD AS o) id, o.val AS oldval
        ),
        m AS (
          MERGE INTO tgt USING ins ON ins.id = tgt.id
          WHEN MATCHED THEN UPDATE SET tval = 'z'
          RETURNING merge_action() AS act, ins.oldval AS ov, tgt.id
        )
        SELECT * FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `forward-referencing MERGE under WITH RECURSIVE fed by a later sibling with a LEFT JOIN is nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // FIX 3 / genuine NEW-harm regression: "m" (declared FIRST) forward-references "pre"
      // (declared AFTER it) under WITH RECURSIVE, which makes every sibling name visible to every
      // other body regardless of declaration order. At e4679ff, this shape silently typed "bval"
      // NOT NULL — the referencesAnyName sibling check (and its only trigger point) did not exist
      // yet, so the stub path had nothing to force it nullable with, despite "pre"'s own LEFT
      // JOIN null-extending it exactly as in the plain-WITH sibling test above. Verified against
      // real Postgres (an "a" row with no matching "b" row): act = 'UPDATE', bval = NULL, id = 1.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH RECURSIVE
          m AS (
            MERGE INTO tgt USING pre ON pre.id = tgt.id
            WHEN MATCHED THEN UPDATE SET tval = 'z'
            RETURNING merge_action() AS act, pre.bval, tgt.id
          ),
          pre AS (
            SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id
          )
        SELECT act, bval, id FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `DELETE RETURNING OLD and NEW both report nullable — NEW is always null for a deleted row`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // PostgreSQL 18's RETURNING OLD/NEW: for a DELETE, OLD is the deleted row (always present)
      // and NEW does not exist (always NULL). Neither the join-preserving conversion (OLD/NEW
      // are not valid range variables outside RETURNING, so the converted SELECT fails to
      // prepare) nor plain metadata (which reflects base-table attnotnull, oblivious to OLD/NEW's
      // conditional existence) can see this — the safety net now forces both nullable whenever a
      // body's RETURNING references OLD./NEW., regardless of join structure. Verified against
      // real Postgres: OLD.name = 'orig', NEW.name = NULL.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        """
        WITH d AS (
          DELETE FROM t WHERE id = 1 RETURNING OLD.name, NEW.name
        )
        SELECT * FROM d
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `INSERT ON CONFLICT RETURNING OLD-col is nullable even though INSERT skips the join-based net`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // NOT independently demonstrative of Fix 4: checked directly against the driver
      // (PreparedStatement.getMetaData()), PostgreSQL's OWN metadata already reports
      // OLD.bval as nullable for this exact shape, with no forceAllNullable involved — so this
      // body would pass even without referencesOldOrNew. What this DOES confirm (per the
      // coordinator's request) is that the two forceAllNullable triggers are independent: this
      // body IS an INSERT (per isInsertBody, excluded from the join-based trigger) with no join
      // at all, and still correctly ends up nullable — proving isInsertBody's exclusion doesn't
      // also (incorrectly) suppress the OLD/NEW trigger. The DELETE test below, where raw
      // PostgreSQL metadata IS wrong without the fix, is the demonstrative case for Fix 4.
      // Ground truth for OLD.bval's real nullability: with an existing row (a genuine conflict),
      // OLD.bval = 'orig'; with no conflict (a fresh insert), OLD.bval = NULL — so across
      // possible executions the column is genuinely nullable, not merely over-approximated.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, bval TEXT)",
        """
        WITH u AS (
          INSERT INTO t (id, bval) VALUES (1, 'new')
          ON CONFLICT (id) DO UPDATE SET bval = 'updated'
          RETURNING OLD.bval
        )
        SELECT bval FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `data-modifying CTE without RETURNING, unrelated outer SELECT`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH inserted AS (
          INSERT INTO t(name) VALUES ('test') ON CONFLICT DO NOTHING
        )
        SELECT id, label FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `chained data-modifying CTE followed by SELECT CTE with LEFT JOIN referencing it`() {
      // Regression guard: a non-DML CTE body must be kept verbatim (not stubbed) when the
      // query is transformed for view creation, because a stub built from base-table
      // `attnotnull` cannot reproduce nullability induced by a LEFT JOIN inside the CTE body.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO t(name) VALUES ('x') RETURNING id
        ),
        j AS (
          SELECT ins.id AS iid, u.label FROM ins LEFT JOIN u ON u.id = ins.id
        )
        SELECT iid, label FROM j
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `data-modifying CTE alongside unrelated SELECT CTE with LEFT JOIN`() {
      // Same regression guard as above, without chaining: the SELECT CTE with the LEFT JOIN
      // does not reference the data-modifying CTE at all, but the presence of DML anywhere
      // in the query still triggers the view-creation transform for the whole statement.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE d (id INT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO t(name) VALUES ('x') RETURNING id
        ),
        j AS (
          SELECT d.id AS did, u.label FROM d LEFT JOIN u ON u.id = d.id
        )
        SELECT did, label FROM j
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `parenthesized SELECT CTE body with LEFT JOIN referencing chained DML CTE`() {
      // Regression guard: a CTE body may itself be parenthesized (e.g. `AS ((SELECT ...))`).
      // The leading-keyword check must skip past the extra `(` rather than misclassifying
      // this SELECT body as data-modifying and stubbing away its LEFT JOIN.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO t(name) VALUES ('x') RETURNING id
        ),
        j AS (
          (SELECT ins.id AS iid, u.label FROM ins LEFT JOIN u ON u.id = ins.id)
        )
        SELECT iid, label FROM j
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `parenthesized SELECT CTE body with leading block comment before the parenthesis`() {
      // Same regression guard as above, with a block comment between "AS (" and the extra "(".
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO t(name) VALUES ('x') RETURNING id
        ),
        j AS (/* c */ (SELECT ins.id AS iid, u.label FROM ins LEFT JOIN u ON u.id = ins.id))
        SELECT iid, label FROM j
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `parenthesized UNION ALL CTE body remains non-null when both branches are non-null`() {
      // Regression guard: a parenthesized UNION ALL body must also be kept verbatim (not
      // stubbed as data-modifying), so its true non-null result is preserved.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO t(name) VALUES ('x') RETURNING id
        ),
        j AS (
          (SELECT ins.id AS iid FROM ins) UNION ALL (SELECT id FROM u)
        )
        SELECT iid FROM j
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `SELECT CTE body with nested block comment before it is kept verbatim`() {
      // Regression guard: Postgres block comments nest (`/* a /* b */ */` is one comment), so
      // the leading-keyword check must skip past the whole nested comment rather than stopping
      // at the first "*/" and misclassifying this SELECT body as data-modifying.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL);
        CREATE TABLE u (id INT NOT NULL, label TEXT NOT NULL)
        """.trimIndent(),
        """
        WITH ins AS (
          INSERT INTO t(name) VALUES ('x') RETURNING id
        ),
        j AS (/* a /* b */ */ SELECT ins.id AS iid, u.label FROM ins LEFT JOIN u ON u.id = ins.id)
        SELECT iid, label FROM j
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `WITH RECURSIVE followed by data-modifying CTE referencing it`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, val INT NOT NULL)",
        """
        WITH RECURSIVE counter AS (
          SELECT 1 AS n
          UNION ALL
          SELECT n + 1 FROM counter WHERE n < 3
        ),
        inserted AS (
          INSERT INTO t(val) SELECT n FROM counter RETURNING *
        )
        SELECT * FROM inserted
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `RETURNING item with no usable name is resolved by the outer query`() {
      // A RETURNING item that is neither a plain column reference nor a simple cast (here,
      // string concatenation) has no name of its own, so PostgreSQL reports it as the literal
      // "?column?" (verified directly against real Postgres via psql \gdesc) — not a valid bare
      // identifier at all. Before the fix, tryPrepareStub emitted it unquoted ("AS ?column?"),
      // a syntax error in the stub SELECT, which failed CREATE VIEW the same way #204's mixed-
      // case alias did. Deliberately concatenates the nullable "name" column (rather than a
      // literal like "RETURNING 1", which is always non-null and would pass either way, masking
      // the bug the same way "id" did in the test above) so the CREATE-VIEW failure's
      // top-level analyzeUnconvertibleDml fallback (queryColumnNullability's last resort when
      // analyzeViaTemporaryView on the transformed SQL itself throws) is observable — before
      // issue #207's fix, that fallback wrongly asserted NOT NULL here regardless of truth.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT)",
        """
        WITH ins AS (
          INSERT INTO t (name) VALUES (NULL) RETURNING name || 'x'
        )
        SELECT * FROM ins
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].name).isEqualTo("?column?")
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `quoted mixed-case RETURNING alias in a data-modifying CTE body is resolved by the outer query`() {
      // Reproduces #204: "ins"'s body is a plain INSERT, so it never reaches
      // convertDmlCteBodyToSelect (whose join-preserving conversion is limited to
      // UPDATE/DELETE/MERGE) and stays on the tryPrepareStub path.
      // ResultSetMetaData.getColumnName reports the RETURNING alias exactly as declared,
      // "myId", but before the fix tryPrepareStub emitted it unquoted ("AS myId"), which
      // PostgreSQL folds to lowercase "myid" when building the stub SELECT used for
      // CREATE VIEW. The outer query's quoted reference to ins."myId" then fails to resolve
      // against the stub ("column ins.myId does not exist" — verified directly against real
      // Postgres via psql). Inside queryColumnNullability, that SQLException is caught and
      // degraded to analyzeUnconvertibleDml's fallback — before issue #207's fix, that fallback
      // asserted EVERY column NOT NULL regardless of truth — so "name" is nullable in the schema
      // (no NOT NULL constraint), but before the fix this test wrongly reports it NOT NULL. Deliberately uses a nullable
      // source column (not id, which is NOT NULL and would pass either way, masking the bug)
      // so the wrong fallback is actually observable as an assertion failure.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT)",
        """
        WITH ins AS (
          INSERT INTO t (name) VALUES (NULL) RETURNING name AS "myId"
        )
        SELECT ins."myId" FROM ins
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].name).isEqualTo("myId")
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `RETURNING alias with an embedded double quote in a data-modifying CTE body is resolved by the outer query`() {
      // Same failure mode as the test above, but the alias itself contains a literal double
      // quote (written "my""Id" in SQL, an escaped quote inside a quoted identifier, so the
      // real column name is my"Id). tryPrepareStub must double the embedded quote when
      // re-quoting the alias for the stub SELECT ("AS \"my\"\"Id\""); emitting only a single
      // doubled quote or none at all would produce invalid SQL or fold/mismatch the name, and
      // the outer query's reference would fail to resolve the same way as #204 — degrading to
      // the same wrong-NOT-NULL fallback described above.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT)",
        """
        WITH ins AS (
          INSERT INTO t (name) VALUES (NULL) RETURNING name AS "my""Id"
        )
        SELECT ins."my""Id" FROM ins
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].name).isEqualTo("my\"Id")
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `UPDATE RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "UPDATE t SET name = 'x' WHERE id = -1 RETURNING *",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `DELETE RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "DELETE FROM t WHERE id = -1 RETURNING *",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `INSERT RETURNING specific columns`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        "INSERT INTO t(name) VALUES ('test') RETURNING id, name",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `INSERT SELECT RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        "INSERT INTO t(name) SELECT name FROM t WHERE FALSE RETURNING *",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `UPDATE FROM INNER JOIN RETURNING`() {
      // prosqlbody reads the RAW :targetList assignment directly — "nickname" is assigned
      // `d.id::TEXT`, a cast over a NOT NULL column reached through a plain (non-outer) JOIN — so
      // it correctly reports "nickname" NOT NULL. Verified live: this exact UPDATE returns
      // `nickname = '1'`, never NULL.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL, nickname TEXT); CREATE TABLE d (id INT NOT NULL, t_id INT NOT NULL)",
        """
        UPDATE t SET nickname = d.id::TEXT
        FROM d WHERE t.id = d.t_id AND t.id = -1
        RETURNING t.*
        """.trimIndent(),
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `INSERT with DEFAULT for non-null column`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL DEFAULT 'unnamed')",
        "INSERT INTO t DEFAULT VALUES RETURNING *",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `MERGE RETURNING with a non-table source reports every column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "MERGE RETURNING requires PostgreSQL 17+")
      // RETURNING * on a MERGE expands to BOTH relations' columns — verified live: this exact
      // statement returns 4 columns (s.id, s.name, t.id, t.name), all genuinely NOT NULL here
      // (the source is a fixed-literal derived table, never actually absent or null). But
      // PgCatalogLoader.mergeAbsentVarnos only attributes a MERGE's join to a source relation
      // that is itself a plain base table (an :rtable entry with rtekind 0) — a subquery/VALUES
      // source has no real relation OID or name EXPLAIN's plan can be correlated against, so this
      // shape falls back to reporting every column nullable rather than guessing. This is the
      // accepted, safe-direction cost for an uncommon shape (a MERGE source that isn't a plain
      // table), not a regression: the fallback is exactly as safe as "cannot determine" always is.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL)",
        """
        MERGE INTO t
        USING (SELECT -1 AS id, 'test' AS name) s ON t.id = s.id
        WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)
        RETURNING *
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(4)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isFalse()
      assertThat(query.columns[3].notNull).isFalse()
    }

    @Test
    fun `top-level MERGE RETURNING merge_action() does not abort generation`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // FIX 1 [P0]: convertDmlToSelect splices merge_action() into a plain SELECT verbatim, where
      // it is not valid PostgreSQL (merge_action() only works inside MERGE's own RETURNING) — so
      // the converted SELECT fails to prepare. At d1153f3, Phase 2 (the TOP-LEVEL, non-CTE
      // conversion path) had no validation gate at all: the bad SELECT reached CREATE VIEW, and
      // the resulting SQLException was thrown from INSIDE queryColumnNullability's own catch
      // block, escaping uncaught and aborting the whole build on SQL PostgreSQL itself accepts
      // fine. Verified against real Postgres (a matched row, no WHEN NOT MATCHED branch — every
      // returned row genuinely has a target AND source row present): act = 'UPDATE', aval = 'a1',
      // id = 1 — all three genuinely NOT NULL.
      //
      // "aval" and "id" are NOT NULL via honestly-read ResultSetMetaData (a simple column
      // reference tracing to its source column's attnotnull). "act" (a bare function call) reports
      // `columnNullableUnknown`; probeUnknownColumnNullability is attempted but cannot resolve it:
      // merge_action() is only valid inside a real MERGE's own RETURNING list, so the plain
      // `SELECT merge_action() AS act, a.aval, tgt.id FROM tgt` probe this builds fails to prepare
      // (both because merge_action() itself is invalid there, and because "a" isn't in that
      // probe's FROM list at all) — the probe returns `null` and analyzeUnconvertibleDml falls
      // back to its own nullable default for "act": nullability analysis must be correct or
      // silent, and this file cannot PROVE merge_action() is non-null here even though it always
      // is in practice.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL, aval TEXT NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING a ON a.id = tgt.id
        WHEN MATCHED THEN UPDATE SET tval = 'z'
        RETURNING merge_action() AS act, a.aval, tgt.id
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `top-level MERGE RETURNING merge_action-comma-star does not abort generation`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // The four plain column references (both "id"s, "aval", "tval") are NOT NULL via
      // honestly-read ResultSetMetaData. "merge_action" (a bare function call,
      // `columnNullableUnknown`) cannot be proven: star-expansion in probeUnknownColumnNullability
      // only knows the MERGE's own target ("tgt"), not its "USING a" source, so the expanded item
      // count (3: merge_action() + tgt's own 2 columns) doesn't match the real 5-column result —
      // the probe correctly bails rather than trust a mapping it can't verify, falling back to
      // "act"'s nullable default.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL, aval TEXT NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING a ON a.id = tgt.id
        WHEN MATCHED THEN UPDATE SET tval = 'z'
        RETURNING merge_action(), *
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(5)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isTrue()
      assertThat(query.columns[3].notNull).isTrue()
      assertThat(query.columns[4].notNull).isTrue()
    }

    @Test
    fun `top-level MERGE RETURNING OLD-col does not abort generation`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Same FIX 1 crash, OLD-reference variant (also not valid outside RETURNING, same failure
      // mode as merge_action()) — rejected conversion falls through to analyzeUnconvertibleDml.
      // Verified against real Postgres (matched-row-only MERGE, so every returned row's OLD is the
      // pre-existing target row, always present): OLD.tval = 'x', genuinely NOT NULL for this
      // exact shape. Before issue #207's fix, this happened to be reported correctly (NOT NULL)
      // only by coincidence, via the old fallback that asserted every column NOT NULL
      // unconditionally. analyzeUnconvertibleDml now applies the SAME per-column OLD/NEW forcing
      // the CTE-body stub path always has (see `UPDATE RETURNING OLD-col alongside the target's
      // own column stays NOT NULL for the target column` above for the CTE-wrapped precedent),
      // which forces every OLD/NEW-referencing column nullable BY DESIGN regardless of whether a
      // specific MERGE shape happens to make it always present — an accepted, deliberate loss of
      // precision in the safe direction, not a regression.
      //
      // This assertion CANNOT be restored to main's `isTrue()`: this column's own nullability is
      // driven entirely by the per-column OLD/NEW forcing above, never by
      // `metadata.isNullable`/`columnNullableUnknown` (there is no non-OLD/NEW column here at
      // all), so it is untouched by, and independent of, the `columnNullableUnknown` handling fix
      // (see the `merge_action()` tests above). Reverting it to NOT NULL would mean removing the
      // OLD/NEW forcing for THIS shape specifically while keeping it for `WHEN NOT MATCHED THEN
      // INSERT ... RETURNING OLD.tval` (the "freshly-inserted row" test below), which the text scan
      // this predicate runs on cannot distinguish — both are `RETURNING OLD.tval` on a single-item
      // list; only the `WHEN` branches differ, and no static scan tells them apart. Keeping the
      // over-approximation for both is the same accepted tradeoff already documented above.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL, aval TEXT NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING a ON a.id = tgt.id
        WHEN MATCHED THEN UPDATE SET tval = 'z'
        RETURNING OLD.tval
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE INTO with WHEN NOT MATCHED THEN INSERT RETURNING OLD-col is nullable for a freshly-inserted row`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Issue #207 shape 1: a freshly INSERTed row via MERGE has no prior row, so OLD.tval is
      // genuinely NULL — before the fix, the top-level no-join-structure fallback
      // (analyzeUnconvertibleDml's predecessor) asserted it NOT NULL unconditionally. Verified
      // against real Postgres (a is INSERT INTO a VALUES (1, 'a1'), tgt starts empty, so a.id has
      // no matching tgt row and WHEN NOT MATCHED fires): oldv = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL, aval TEXT NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING a ON a.id = tgt.id
        WHEN NOT MATCHED THEN INSERT (id, tval) VALUES (a.id, a.aval)
        RETURNING OLD.tval AS oldv
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE INTO with WHEN MATCHED THEN DELETE RETURNING NEW-col is nullable for a deleted row`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING NEW requires PostgreSQL 18+")
      // Issue #207 shape 2: a deleted row has no resulting row, so NEW.tval is genuinely NULL —
      // same fallback, same fix. Verified against real Postgres (tgt has a matching row for a):
      // NEW.tval = NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL, aval TEXT NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING a ON a.id = tgt.id
        WHEN MATCHED THEN DELETE
        RETURNING NEW.tval
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `INSERT ON CONFLICT DO UPDATE RETURNING OLD-col is nullable for a freshly-inserted row`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Issue #207 shape 3: a fresh INSERT (no conflicting row) has no prior row, so OLD.tval is
      // genuinely NULL — a plain top-level INSERT ... ON CONFLICT with no CTE and no MERGE at all,
      // the simplest possible top-level DML shape this fix covers. Verified against real Postgres
      // (tgt starts empty, so the INSERT hits no conflict): OLD.tval = NULL.
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL)",
        """
        INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y'
        RETURNING OLD.tval
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `top-level MERGE RETURNING merge_action() alongside a LEFT JOIN in USING forces every column nullable`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "merge_action() requires PostgreSQL 17+")
      // Issue #207 shape 4: merge_action() forces Phase 2's conversion to be rejected (not valid
      // outside MERGE's own RETURNING), so this falls to analyzeUnconvertibleDml — which now
      // detects the LEFT JOIN nested in the USING subquery via the same null-extending-construct
      // trigger the CTE-body stub path already has, forcing EVERY column nullable, "act" included
      // — the same accepted over-approximation the CTE-wrapped equivalent test above (`MERGE with
      // a LEFT JOIN nested in its USING subquery reports the joined column nullable`) documents,
      // now reached for a bare top-level statement instead of one wrapped in a CTE. Verified
      // against real Postgres (b has no row matching a): act = 'UPDATE', bval = NULL (genuinely
      // nullable — the LEFT JOIN's real effect).
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL, aval TEXT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING (SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id) s ON s.id = tgt.id
        WHEN MATCHED THEN UPDATE SET tval = 'z'
        RETURNING merge_action() AS act, s.bval
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `top-level DELETE RETURNING reports a nullable column as nullable, not NOT NULL`() {
      // Issue #207 shape 5: the everyday, no-OLD-NEW-MERGE case — a plain top-level DELETE with no
      // FROM/USING clause has no join structure for convertDmlToSelect to convert, so it goes
      // straight to analyzeUnconvertibleDml, which now reads real ResultSetMetaData.isNullable
      // instead of discarding it. "note" has no NOT NULL constraint, so it is genuinely nullable;
      // "id" and "name" are declared NOT NULL and stay that way — this is not "mark everything
      // nullable", only "consult the metadata this fallback had all along".
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL, note TEXT)",
        "DELETE FROM t WHERE id = ? RETURNING id, name, note",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `top-level UPDATE RETURNING reports a nullable column as nullable, not NOT NULL`() {
      // UPDATE equivalent of the DELETE shape above — a plain top-level UPDATE with no FROM
      // clause has no join structure to convert either, so it hits the same fallback.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL, note TEXT)",
        "UPDATE t SET name = ? WHERE id = ? RETURNING id, name, note",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `top-level UPDATE with a LEFT JOIN only inside a WHERE subquery does not force RETURNING nullable`() {
      // The null-extending-construct arm used to scan the WHOLE statement's text for an outer
      // join, not just the clause whose join structure can actually reach RETURNING — so a LEFT
      // JOIN sitting inside an unrelated `WHERE ... IN (subquery)` (which only narrows which rows
      // the UPDATE touches, and cannot null-extend anything in RETURNING) fabricated nullable for
      // both columns. Verified against real Postgres 18 (a matching row exists so the WHERE
      // filter passes; t.id is even the PRIMARY KEY): id and name are genuinely NOT NULL.
      // dmlSourceClauseRegion returns null here (no top-level FROM clause on this UPDATE at all),
      // so the join arm is forced OFF rather than scanning the WHERE subquery's own LEFT JOIN.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL)
        """.trimIndent(),
        """
        UPDATE t SET name = 'x'
        WHERE t.id IN (SELECT a.id FROM a LEFT JOIN b ON b.id = a.id)
        RETURNING t.id, t.name
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `top-level DELETE with a LEFT JOIN only inside a WHERE subquery does not force RETURNING nullable`() {
      // DELETE equivalent of the UPDATE case above — a LEFT JOIN inside a `WHERE ... IN
      // (subquery)` cannot null-extend a plain DELETE's RETURNING list either, and this DELETE
      // has no top-level USING clause at all for dmlSourceClauseRegion to scope to.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL)
        """.trimIndent(),
        """
        DELETE FROM t
        WHERE t.id IN (SELECT a.id FROM a LEFT JOIN b ON b.id = a.id)
        RETURNING t.id, t.name
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `top-level UPDATE FROM with OLD-col still finds the real LEFT JOIN in its scoped region`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // A bare `UPDATE ... FROM a LEFT JOIN b ... RETURNING t.name, b.bval` converts and validates
      // successfully via the join-aware node-tree analyzer, never reaching
      // analyzeUnconvertibleDml at all — so it cannot exercise the scoped fallback path by itself.
      // Adding a RETURNING OLD.col forces the structural conversion to be rejected (OLD is not a
      // valid range variable in the converted SELECT), landing on analyzeUnconvertibleDml — the
      // top-level counterpart of `stub path forces every column nullable when RETURNING OLD-col
      // accompanies a real LEFT JOIN` above. dmlSourceClauseRegion scopes the join scan to this
      // UPDATE's own FROM ... WHERE region, which DOES contain the real LEFT JOIN, so bval (and,
      // by the same accepted over-approximation as the CTE case, oldname and name too) are forced
      // nullable — proving the scoped region still finds a join that genuinely belongs to the
      // source clause, not merely refusing to force anything at all.
      // prosqlbody reads the RAW :targetList assignment for "name" directly (a literal 'x',
      // untouched by either the OLD-forcing rule or the LEFT JOIN), so it correctly isolates
      // "name" as NOT NULL, "oldname" as nullable (the blanket OLD-forcing rule), and "bval" as
      // nullable (the genuine LEFT JOIN). Verified live: this exact UPDATE returns `name = 'x'`,
      // never NULL, for a matching row.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        UPDATE t SET name = 'x' FROM a LEFT JOIN b ON b.id = a.id WHERE t.id = a.id
        RETURNING OLD.name AS oldname, t.name, b.bval
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `top-level DELETE USING with OLD-col still finds the real LEFT JOIN in its scoped region`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // DELETE USING equivalent of the UPDATE FROM case above. "t.name" is DELETE's own target
      // column — read directly, never assigned — so prosqlbody correctly reports it NOT NULL
      // (the schema's own constraint). Verified live: this exact DELETE returns `name = 'orig'`,
      // never NULL.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL);
        CREATE TABLE a (id INT NOT NULL);
        CREATE TABLE b (id INT NOT NULL, bval TEXT NOT NULL)
        """.trimIndent(),
        """
        DELETE FROM t USING a LEFT JOIN b ON b.id = a.id WHERE t.id = a.id
        RETURNING OLD.name AS oldname, t.name, b.bval
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `INSERT RETURNING a literal and a bare integer constant reports both NOT NULL`() {
      // R2 regression: a literal or constant expression RETURNING item reports
      // ResultSetMetaData.columnNullableUnknown (PostgreSQL cannot describe a literal's
      // nullability any more precisely than that) — a literal is never NULL, so
      // analyzeUnconvertibleDml must treat that as NOT NULL. Issue #226's probe actually confirms
      // this exactly, rather than merely defaulting to it: it builds `SELECT id, name, 'lit'::TEXT
      // AS lbl, 1 AS one FROM t` and reads its real per-column nullability via the same node-tree
      // analyzer a plain SELECT already uses, independently reporting both literal columns NOT
      // NULL. "name" has no NOT NULL constraint and is left untouched by this rule (the probe's
      // own answer for it is irrelevant), since it traces to a real column whose base-table
      // attnotnull IS already known (columnNoNulls), not unknown.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL PRIMARY KEY, name TEXT)",
        "INSERT INTO t (name) VALUES (?) RETURNING id, name, 'lit'::TEXT AS lbl, 1 AS one",
      )
      assertThat(query.columns).hasSize(4)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isTrue()
      assertThat(query.columns[3].notNull).isTrue()
    }
  }

  /**
   * Issue #226: `PgCatalogLoader.analyzeUnconvertibleDml` treats
   * `ResultSetMetaData.columnNullableUnknown` as NOT NULL — correct for a literal/constant
   * (`1 AS one`), but wrong for an expression built over a genuinely nullable source column
   * (`lower(note)`, where `note` has no NOT NULL constraint). These tests exercise
   * `PgCatalogLoader.probeUnknownColumnNullability`, the supplementary probe that resolves such a
   * column's real nullability instead of defaulting, and the gates that fall back to today's NOT
   * NULL default when the probe cannot be trusted.
   */
  @Nested
  inner class UnknownColumnNullabilityProbe {

    @Test
    fun `DELETE RETURNING an expression over a nullable column reports nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, note TEXT)",
        "DELETE FROM t WHERE id = ? RETURNING lower(note)",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `UPDATE RETURNING a scalar subquery alongside a real column reports each exactly`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE b (id INT PRIMARY KEY, bval TEXT NOT NULL)
        """.trimIndent(),
        "UPDATE t SET name = 'x' WHERE id = 5 RETURNING id, (SELECT bval FROM b WHERE b.id = 999) AS sub",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `DELETE RETURNING an expression over a NOT NULL column reports NOT NULL, proving exactness`() {
      // The probe must not blanket-flip every columnNullableUnknown column to nullable — only a
      // genuinely nullable source column should surface as nullable through it.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL)",
        "DELETE FROM t WHERE id = ? RETURNING lower(name)",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `DELETE RETURNING COALESCE over a nullable column reports NOT NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, note TEXT)",
        "DELETE FROM t WHERE id = ? RETURNING COALESCE(note, 'x')",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `a star item in RETURNING is expanded so the probe still runs and reports the real answer`() {
      // "note" has no NOT NULL constraint, so lower(note) genuinely CAN be NULL — a star item must
      // not prevent the probe from proving that: probeUnknownColumnNullability expands "*" against
      // "t"'s own catalog columns (id, note) before counting items, so the 2-item RETURNING list
      // ("*", "lower(note)") correctly resolves to the real 3-column count and the probe runs.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, note TEXT)",
        "DELETE FROM t WHERE id = ? RETURNING *, lower(note)",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `a star item in RETURNING over a NOT NULL column still resolves NOT NULL, proving exactness`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL)",
        "DELETE FROM t WHERE id = ? RETURNING *, lower(name)",
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[2].notNull).isTrue()
    }

    @Test
    fun `a trailing line comment on the RETURNING list does not swallow the probe's FROM clause`() {
      // Regression: probeUnknownColumnNullability used to compose "SELECT $returningText FROM
      // $target" on a single line. A trailing "--" comment with nothing after it on that same
      // line (no line break of its own to stop at) swallowed " FROM t" into the comment, making
      // the probe fail to prepare and silently degrade to today's NOT NULL default instead of the
      // real (nullable) answer.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, note TEXT)",
        "DELETE FROM t WHERE id = ? RETURNING id, lower(note) AS n -- lowercased",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `top-level and CTE-wrapped forms of the same RETURNING expression agree`() {
      val ddl = "CREATE TABLE t (id INT PRIMARY KEY, note TEXT)"
      val topLevel = analyzeWithSchema(ddl, "DELETE FROM t WHERE id = ? RETURNING lower(note)")
      val cteWrapped = analyzeWithSchema(
        ddl,
        "WITH d AS (DELETE FROM t WHERE id = ? RETURNING lower(note)) SELECT * FROM d",
      )
      assertThat(topLevel.columns).hasSize(1)
      assertThat(cteWrapped.columns).hasSize(1)
      assertThat(cteWrapped.columns[0].notNull).isEqualTo(topLevel.columns[0].notNull)
    }
  }

  /**
   * Issue #228: [PgCatalogLoader.probeUnknownColumnNullability]'s bare `SELECT <returning> FROM
   * <target>` probe (issue #226) evaluates a `RETURNING` expression against the UNMODIFIED target
   * table, so it cannot see a value the statement's OWN `SET` clause assigns — `UPDATE t SET note
   * = 'x' RETURNING lower(note)` widened to nullable even though `note` can only ever be `'x'` in
   * this result, because `note` has no `NOT NULL` constraint in the catalog. These tests exercise
   * [PgCatalogLoader.buildUpdateSetAwareFromClause], which wraps the probe's target in a derived
   * table carrying the `SET`-assigned expressions, plus every bail condition that must keep the
   * bare (pre-#228) column instead.
   */
  @Nested
  inner class SetAssignmentAwareProbe {

    private val schema =
      "CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL, note TEXT, other TEXT, data JSONB)"

    @Test
    fun `assigning a non-null literal to a nullable column reports NOT NULL`() {
      val query = analyzeWithSchema(schema, "UPDATE t SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `a parameter placeholder assignment reports nullable, since the caller can bind NULL`() {
      val query = analyzeWithSchema(schema, "UPDATE t SET note = ? WHERE id = 1 RETURNING lower(note) AS n")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `an assignment referencing the column's own old value reports nullable`() {
      val query = analyzeWithSchema(schema, "UPDATE t SET note = note || 'x' RETURNING lower(note) AS n")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `RETURNING through the target's own alias resolves against the substituted value`() {
      val query = analyzeWithSchema(schema, "UPDATE t AS u SET note = 'x' RETURNING lower(u.note) AS n")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `a row-form assignment reports NOT NULL, since PostgreSQL attributes it to one column cleanly`() {
      // PostgreSQL's own parser decomposes `(note, other) = ('x', 'y')` into two ordinary,
      // independent :targetList entries (resno matching each column, one CONST apiece) — verified
      // live in the raw prosqlbody text — so prosqlbody attributes 'x' to "note" exactly as
      // cleanly as a plain `SET note = 'x'` would. Verified live: this exact UPDATE returns
      // `n = 'x'`, never NULL.
      val query = analyzeWithSchema(
        schema,
        "UPDATE t SET (note, other) = ('x', 'y') WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `an assignment to DEFAULT reports nullable, since the default expression is not resolved`() {
      val query = analyzeWithSchema(schema, "UPDATE t SET note = DEFAULT WHERE id = 1 RETURNING lower(note) AS n")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `control - assigning a non-null literal to an already NOT NULL column stays NOT NULL`() {
      val query = analyzeWithSchema(schema, "UPDATE t SET name = 'x' WHERE id = 1 RETURNING lower(name) AS n")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `an unassigned nullable column keeps reporting nullable`() {
      val query = analyzeWithSchema(schema, "UPDATE t SET name = 'x' WHERE id = 1 RETURNING lower(note) AS n")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `an assignment expression the analyzer can reason through still reports NOT NULL`() {
      val query = analyzeWithSchema(schema, "UPDATE t SET note = COALESCE(note, 'x') RETURNING lower(note) AS n")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `a system column the SET-aware derived table can't carry doesn't collapse a sibling column's real answer`() {
      // The wrapped `FROM (SELECT ... FROM t) AS t` derived table has no `ctid` — PostgreSQL fails
      // to prepare `SELECT ..., ctid FROM (SELECT ... FROM t) AS t` with "column \"ctid\" does not
      // exist". `ctid` itself is reported NOT NULL directly by `ResultSetMetaData` on the RAW
      // `UPDATE ... RETURNING` statement — it never goes through the probe at all, at HEAD or
      // here — so it stays NOT NULL regardless. What the wrapped probe's failure must NOT be
      // allowed to do is collapse the SEPARATE, otherwise-resolvable `lower(note)` column's own
      // answer down to the probe's NOT NULL default: without the bare-`FROM t` retry, the whole
      // combined probe (covering every `RETURNING` item at once) fails to prepare because of
      // `ctid` alone, silently breaking `lower(note)`'s nullability too even though nothing about
      // `ctid` bears on it.
      val query = analyzeWithSchema(schema, "UPDATE t SET note = note || 'x' RETURNING lower(note) AS n, ctid")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `an untyped literal assigned to a jsonb column reports nullable rather than failing the whole probe`() {
      // Splicing the untyped literal `'{"a":1}'` into the derived table's column list degrades it
      // to `text` there, so `data -> 'zzz'` (the `->` jsonb operator) fails to prepare against the
      // wrapped probe. The bare-target retry sees the real `jsonb` column instead and succeeds.
      val query = analyzeWithSchema(schema, "UPDATE t SET data = '{\"a\":1}' RETURNING data -> 'zzz' AS v")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a trailing comment after DEFAULT still bails on splicing it into the derived table`() {
      val query = analyzeWithSchema(
        schema,
        "UPDATE t SET note = DEFAULT /* reset */ WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a leading comment before DEFAULT still bails on splicing it into the derived table`() {
      val query = analyzeWithSchema(
        schema,
        "UPDATE t SET note = /* x */ DEFAULT WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }
  }

  /**
   * A verifier found that [SetAssignmentAwareProbe]'s substitution splices the `SET` right-hand
   * side into the derived table's column list VERBATIM, with no cast to the column's own declared
   * type. An untyped literal (`'empty'`) spliced bare is typed `text` by PostgreSQL inside the
   * derived table — a DIFFERENT type than the real column's — which can resolve a `RETURNING`
   * function call against a completely different, sometimes safe-listed, overload than the real
   * statement would ever use: `lower(text)` is safe-listed, `lower(anyrange)` is not, and
   * `UPDATE t SET r = 'empty' RETURNING lower(r)` resolves the LATTER against the real column but
   * (pre-fix) the FORMER against the bare-text derived table, silently reporting NOT NULL for a
   * value that is actually `NULL` at runtime. [PgCatalogLoader.buildUpdateSetAwareFromClause] now
   * casts every substituted expression to the column's own declared type — via
   * [PgCatalogLoader.lookupDeclaredColumnTypes]'s `format_type(atttypid, atttypmod)` — so the
   * derived table's column type is identical to the real one and resolves the identical overload.
   */
  @Nested
  inner class SetAssignmentAwareProbeDeclaredTypeCast {

    @Test
    fun `an untyped range literal assigned to an INT4RANGE column reports nullable, not NOT NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, r INT4RANGE)",
        "UPDATE t SET r = 'empty' WHERE id = 1 RETURNING lower(r) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `an untyped range literal assigned to an INT4RANGE column reports nullable for upper() too`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, r INT4RANGE)",
        "UPDATE t SET r = '[1,)' WHERE id = 1 RETURNING upper(r) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `an untyped multirange literal assigned to an INT4MULTIRANGE column reports nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, m INT4MULTIRANGE)",
        "UPDATE t SET m = '{}' WHERE id = 1 RETURNING lower(m) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `control - a non-null text literal assigned to a nullable TEXT column still reports NOT NULL`() {
      // The declared type of a plain TEXT column is "text" with no typmod, exactly what the
      // untyped literal already defaulted to before this fix — so adding the cast must not change
      // this answer. Same case as SetAssignmentAwareProbe's own first test, re-asserted here next
      // to the cast-introducing fix as an explicit before/after control.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, note TEXT)",
        "UPDATE t SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `control - an assignment the analyzer can reason through via COALESCE still reports NOT NULL`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, note TEXT)",
        "UPDATE t SET note = COALESCE(note, 'x') RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `a literal assigned to a VARCHAR(5) column still substitutes and reports NOT NULL`() {
      // Proves format_type carried the column's typmod (length 5) rather than the substitution
      // silently failing to build (which would fall back to the bare, unsubstituted `note` column
      // — nullable in the schema — and report NULLABLE here instead). If the cast's type text were
      // invalid SQL, or if the typmod were dropped in a way PostgreSQL rejected, the derived
      // table's `PREPARE` would fail and the whole combined probe would fall back to the bare
      // target, changing this answer.
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT PRIMARY KEY, code VARCHAR(5))",
        "UPDATE t SET code = 'ab' WHERE id = 1 RETURNING upper(code) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `an untyped range literal assigned to a DOMAIN over INT4RANGE reports nullable`() {
      // A domain routes through CoerceToDomain, not a cast function, when the substituted
      // expression is cast to the domain's own declared type (format_type reports the domain's
      // own name, e.g. "r_domain", not its base type "int4range"). CoerceToDomain recurses
      // unconditionally into its argument (see NodeTreeNullabilityAnalyzer.isNonNull), and
      // PostgreSQL strips a domain down to its base type for function-overload resolution, so
      // `lower()` here resolves the same anyrange overload it would against the real
      // domain-typed column — reproducing the same wrong-overload bug this fix closes, but through
      // CoerceToDomain instead of a cast function.
      val query = analyzeWithSchema(
        "CREATE DOMAIN r_domain AS INT4RANGE; CREATE TABLE t (id INT PRIMARY KEY, r r_domain)",
        "UPDATE t SET r = 'empty' WHERE id = 1 RETURNING lower(r) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }
  }

  /**
   * Issue #228 follow-up: a verifier found that [SetAssignmentAwareProbe]'s substitution ignored
   * anything that rewrites the tuple BETWEEN the `SET` clause and `RETURNING` — `RETURNING` always
   * sees the FINAL, post-trigger, post-rule tuple, never the raw `SET` expression, so a `BEFORE`
   * row trigger, an `INSTEAD OF` trigger, a rewrite rule, or a foreign data wrapper's own write path
   * can each substitute something else entirely for a value
   * [PgCatalogLoader.buildUpdateSetAwareFromClause] would otherwise splice in as provably non-null.
   * These tests exercise [PgCatalogLoader.targetRelationMayRewriteTupleBeforeReturning], the
   * catalog-based bail that closes each of those gaps, plus the negative case (a statement-level or
   * `AFTER` trigger) that proves the bail is targeted rather than a blanket "any trigger" check.
   */
  @Nested
  inner class SetAssignmentAwareProbeCatalogBail {

    @Test
    fun `a BEFORE UPDATE row trigger that nulls the column reports nullable`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, note TEXT);
        CREATE FUNCTION nuller() RETURNS trigger AS $$ BEGIN NEW.note := NULL; RETURN NEW; END $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg BEFORE UPDATE ON t FOR EACH ROW EXECUTE FUNCTION nuller();
        """.trimIndent(),
        "UPDATE t SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `an INSTEAD OF UPDATE trigger on a view over the target reports nullable`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, note TEXT);
        CREATE VIEW v AS SELECT id, note FROM t;
        CREATE FUNCTION v_instead_of_update() RETURNS trigger AS $$
        BEGIN
          UPDATE t SET note = NEW.note WHERE id = OLD.id;
          RETURN NEW;
        END $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_instead_of_update INSTEAD OF UPDATE ON v
          FOR EACH ROW EXECUTE FUNCTION v_instead_of_update();
        """.trimIndent(),
        "UPDATE v SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a BEFORE INSERT row trigger on ANY partition bails, even updated through the parent`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, note TEXT) PARTITION BY RANGE (id);
        CREATE TABLE t_p1 PARTITION OF t FOR VALUES FROM (0) TO (100);
        CREATE TABLE t_p2 PARTITION OF t FOR VALUES FROM (100) TO (200);
        CREATE FUNCTION nuller() RETURNS trigger AS $$ BEGIN NEW.note := NULL; RETURN NEW; END $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg BEFORE INSERT ON t_p2 FOR EACH ROW EXECUTE FUNCTION nuller();
        """.trimIndent(),
        "UPDATE t SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `a DO INSTEAD rule on the target reports nullable`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, note TEXT);
        CREATE TABLE t_shadow (id INT PRIMARY KEY, note TEXT);
        CREATE RULE r_instead AS ON UPDATE TO t
          DO INSTEAD (UPDATE t_shadow SET note = NULL WHERE id = OLD.id RETURNING id, note);
        """.trimIndent(),
        "UPDATE t SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    // postgres_fdw's loopback connection caching races under JUnit5's default concurrent
    // execution mode when multiple tests concurrently CREATE/DROP a SERVER + FOREIGN TABLE
    // against the SAME shared container — observed as a spurious "server ... does not exist"
    // surfacing from an unrelated concurrent test's DROP SERVER, not from this test's own logic.
    // @ResourceLock serializes every postgres_fdw-loopback test in this class against each other
    // (but not against unrelated tests elsewhere), matching the existing pattern in
    // gradle-plugin's TestProject.COMPOSITE_BUILD_RESOURCE_LOCK for a different shared resource.
    @Test
    @ResourceLock("postgres_fdw_loopback")
    fun `a foreign table target reports nullable, since an FDW can produce any tuple`() {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
          statement.execute("CREATE SCHEMA $schemaName")
          statement.execute("SET search_path TO $schemaName")
          statement.execute("CREATE TABLE base_for_fdw (id INT PRIMARY KEY, note TEXT)")
          statement.execute("CREATE EXTENSION IF NOT EXISTS postgres_fdw")
          statement.execute(
            "CREATE SERVER loopback_$schemaName FOREIGN DATA WRAPPER postgres_fdw " +
              "OPTIONS (dbname '${container.databaseName}', host '127.0.0.1', port '5432')",
          )
          statement.execute(
            "CREATE USER MAPPING FOR CURRENT_USER SERVER loopback_$schemaName " +
              "OPTIONS (user '${container.username}', password '${container.password}')",
          )
          statement.execute(
            "CREATE FOREIGN TABLE ft (id INT, note TEXT) SERVER loopback_$schemaName " +
              "OPTIONS (schema_name '$schemaName', table_name 'base_for_fdw')",
          )
        }
        try {
          val analyzer = JdbcAnalyzer(connection)
          val catalog = analyzer.buildCatalog(listOf(schemaName))
          val parsedQuery = ParsedQuery(
            name = "test",
            command = ":one",
            sql = "UPDATE ft SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
            comments = emptyList(),
          )
          val query = analyzer.analyzeQuery(parsedQuery, catalog)
          assertThat(query.columns).hasSize(1)
          assertThat(query.columns[0].notNull).isFalse()
        } finally {
          connection.createStatement().use { statement ->
            statement.execute("DROP SERVER loopback_$schemaName CASCADE")
            statement.execute("DROP SCHEMA $schemaName CASCADE")
          }
        }
      }
    }

    @Test
    @ResourceLock("postgres_fdw_loopback")
    fun `a foreign partition of a partitioned target reports nullable`() {
      // P0-1 regression: targetRelationMayRewriteTupleBeforeReturning previously checked
      // relkind only for the ROOT relation ("p", a partitioned table — relkind 'p'), never for
      // its descendants. A partition that is itself a FOREIGN TABLE (relkind 'f') was therefore
      // invisible, and an FDW's own write path can produce any tuple it likes, independent of
      // this statement's SET clause. Verified against real PostgreSQL 18.4 (via a postgres_fdw
      // loopback with a BEFORE UPDATE row trigger on the remote table nulling "note"): before the
      // fix this reported NOT NULL; the actual RETURNING value is NULL.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
          statement.execute("CREATE SCHEMA $schemaName")
          statement.execute("SET search_path TO $schemaName")
          statement.execute("CREATE TABLE remote_p2 (id INT, k INT, note TEXT)")
          statement.execute("INSERT INTO remote_p2 VALUES (1, 150, 'y')")
          statement.execute(
            "CREATE FUNCTION nuller_p2() RETURNS trigger AS \$\$ BEGIN NEW.note := NULL; RETURN NEW; END \$\$ " +
              "LANGUAGE plpgsql",
          )
          statement.execute("CREATE TRIGGER trg BEFORE UPDATE ON remote_p2 FOR EACH ROW EXECUTE FUNCTION nuller_p2()")
          statement.execute("CREATE EXTENSION IF NOT EXISTS postgres_fdw")
          statement.execute(
            "CREATE SERVER loopback_$schemaName FOREIGN DATA WRAPPER postgres_fdw " +
              "OPTIONS (dbname '${container.databaseName}', host '127.0.0.1', port '5432')",
          )
          statement.execute(
            "CREATE USER MAPPING FOR CURRENT_USER SERVER loopback_$schemaName " +
              "OPTIONS (user '${container.username}', password '${container.password}')",
          )
          statement.execute("CREATE TABLE p (id INT, k INT NOT NULL, note TEXT) PARTITION BY RANGE (k)")
          statement.execute("CREATE TABLE p1 PARTITION OF p FOR VALUES FROM (0) TO (100)")
          statement.execute(
            "CREATE FOREIGN TABLE p2 PARTITION OF p FOR VALUES FROM (100) TO (200) " +
              "SERVER loopback_$schemaName OPTIONS (schema_name '$schemaName', table_name 'remote_p2')",
          )
        }
        try {
          val analyzer = JdbcAnalyzer(connection)
          val catalog = analyzer.buildCatalog(listOf(schemaName))
          val parsedQuery = ParsedQuery(
            name = "test",
            command = ":one",
            sql = "UPDATE p SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
            comments = emptyList(),
          )
          val query = analyzer.analyzeQuery(parsedQuery, catalog)
          assertThat(query.columns).hasSize(1)
          assertThat(query.columns[0].notNull).isFalse()
        } finally {
          connection.createStatement().use { statement ->
            statement.execute("DROP SERVER loopback_$schemaName CASCADE")
            statement.execute("DROP SCHEMA $schemaName CASCADE")
          }
        }
      }
    }

    @Test
    @ResourceLock("postgres_fdw_loopback")
    fun `a foreign inheritance child of a plain (non-partitioned) target reports nullable`() {
      // Same P0-1 regression as the foreign-partition test above, but for plain multiple-
      // inheritance (INHERITS) rather than declarative partitioning: the root relation ("parent2")
      // is an ordinary table (relkind 'r'), but its inheritance child ("child2") is a FOREIGN
      // TABLE. Verified against real PostgreSQL 18.4 that "child2 (...) INHERITS (parent2) SERVER
      // ..." is valid syntax and that the child's remote BEFORE UPDATE trigger nulls the returned
      // value.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { statement ->
          statement.execute("CREATE SCHEMA $schemaName")
          statement.execute("SET search_path TO $schemaName")
          statement.execute("CREATE TABLE remote_child (id INT, note TEXT)")
          statement.execute("INSERT INTO remote_child VALUES (1, 'y')")
          statement.execute(
            "CREATE FUNCTION nuller_child() RETURNS trigger AS \$\$ BEGIN NEW.note := NULL; RETURN NEW; END \$\$ " +
              "LANGUAGE plpgsql",
          )
          statement.execute(
            "CREATE TRIGGER trg2 BEFORE UPDATE ON remote_child FOR EACH ROW EXECUTE FUNCTION nuller_child()",
          )
          statement.execute("CREATE EXTENSION IF NOT EXISTS postgres_fdw")
          statement.execute(
            "CREATE SERVER loopback_$schemaName FOREIGN DATA WRAPPER postgres_fdw " +
              "OPTIONS (dbname '${container.databaseName}', host '127.0.0.1', port '5432')",
          )
          statement.execute(
            "CREATE USER MAPPING FOR CURRENT_USER SERVER loopback_$schemaName " +
              "OPTIONS (user '${container.username}', password '${container.password}')",
          )
          statement.execute("CREATE TABLE parent2 (id INT, note TEXT)")
          statement.execute(
            "CREATE FOREIGN TABLE child2 (id INT, note TEXT) INHERITS (parent2) " +
              "SERVER loopback_$schemaName OPTIONS (schema_name '$schemaName', table_name 'remote_child')",
          )
        }
        try {
          val analyzer = JdbcAnalyzer(connection)
          val catalog = analyzer.buildCatalog(listOf(schemaName))
          val parsedQuery = ParsedQuery(
            name = "test",
            command = ":one",
            sql = "UPDATE parent2 SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
            comments = emptyList(),
          )
          val query = analyzer.analyzeQuery(parsedQuery, catalog)
          assertThat(query.columns).hasSize(1)
          assertThat(query.columns[0].notNull).isFalse()
        } finally {
          connection.createStatement().use { statement ->
            statement.execute("DROP SERVER loopback_$schemaName CASCADE")
            statement.execute("DROP SCHEMA $schemaName CASCADE")
          }
        }
      }
    }

    @Test
    fun `a statement-level BEFORE trigger and an AFTER row trigger do NOT bail`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id INT PRIMARY KEY, note TEXT);
        CREATE FUNCTION noop() RETURNS trigger AS $$ BEGIN RETURN NEW; END $$ LANGUAGE plpgsql;
        CREATE FUNCTION stmt_noop() RETURNS trigger AS $$ BEGIN RETURN NULL; END $$ LANGUAGE plpgsql;
        CREATE TRIGGER trg_stmt BEFORE UPDATE ON t FOR EACH STATEMENT EXECUTE FUNCTION stmt_noop();
        CREATE TRIGGER trg_after AFTER UPDATE ON t FOR EACH ROW EXECUTE FUNCTION noop();
        """.trimIndent(),
        "UPDATE t SET note = 'x' WHERE id = 1 RETURNING lower(note) AS n",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }
  }

  /**
   * Regression guard belonging to the same fix as [DmlReturning]'s three `merge_action()`/`OLD`
   * abort guards, but exercising a data-modifying CTE body specifically, hence the `WITH` wrapper
   * the other three tests intentionally omit.
   *
   * NOTE on coverage of PgCatalogLoader's item-count-vs-real-column-count cross-check
   * (`oldOrNewColumns.isNotEmpty() && oldOrNewAnalysis.itemCount != totalColumnCount`): this
   * class's `an ALIASED star reaches the item-count cross-check directly` test below DOES reach
   * this branch, reliably. `oldOrNewReturningColumns` (unlike `parseSelectItems`) never
   * alias-strips an item before checking `isStarItem` against it — so ANY star carrying an
   * alias, explicit (`tgt.* AS whatever`) or implicit (`tgt.* whatever`), is simply left
   * unrecognized there: `isStarItem`'s own comment/whitespace/parenthesis normalization has no
   * concept of an `AS` keyword or an implicit alias to look past, so the alias text survives
   * normalization and the result never ends in `.*`. That unrecognized star's real expansion still
   * shows up in the real column count, mismatching the assumed item count, which is exactly what
   * this cross-check exists to catch — its outcome (forcing every column nullable) is the SAME
   * safe over-approximation a RECOGNIZED star produces via the `forcedColumns = null` path, just
   * reached through the sibling branch instead.
   *
   * This is deliberately NOT "fixed" by alias-stripping inside `oldOrNewReturningColumns`: doing
   * so would only change WHICH branch produces the answer, never the answer itself (both branches
   * force every column nullable), so there is no functional reason to add alias-awareness to this
   * specific call site — and doing so would silently delete this branch's only current test
   * coverage. This note makes no claim that this branch is otherwise unreachable in general — only
   * that the test named above demonstrably reaches it today, via this specific alias-carrying
   * shape.
   *
   * What the star-shape tests in this class (aside from the aliased-star one) still prove — see
   * `an OLD reference without a star still forces only the referencing column, not the whole body`
   * below — is that the PER-COLUMN forcing mechanism (`forcedColumns` non-`null` AND
   * itemCount-matching) survives when no star is involved at all, distinguishing it from the
   * whole-body `forceAllNullable` fallback every star-plus-`OLD`/`NEW` test in this class
   * exercises (via one of the two possible routes: `forcedColumns == null`, or the item-count
   * cross-check).
   */
  @Nested
  inner class OldOrNewStarFailSafe {

    @Test
    fun `parenthesized star-plus-OLD in a CTE body forces every column, not just the wrong one`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // FIX 2: "(tgt.*)" is a parenthesized star — isStarItem's regex previously only recognized
      // a bare "*"/"tbl.*", so this shape's real 2-column expansion (id, tval) was miscounted as
      // a single RETURNING item, shifting "oldv" (the genuinely OLD-dependent column) off its
      // real index and forcing the WRONG column (tval) nullable instead of the real OLD-dependent
      // column. isStarItem now recognizes the parenthesized form, so oldOrNewReturningColumns
      // reports the mapping as KNOWN unreliable (forcedColumns = null) — the caller falls back to
      // forcing EVERY column nullable, same accepted over-approximation as the star-plus-OLD test
      // elsewhere in this file, not an attempt at precisely isolating "oldv" alone. Verified
      // against real Postgres (a fresh insert via ON CONFLICT — no prior row): id = 99, tval =
      // 'x' (both genuinely NOT NULL — the just-inserted row's own columns), oldv = NULL
      // (genuinely nullable) — but the safety net over-approximates all three to nullable here.
      // prosqlbody reports "id" NOT NULL directly off its PRIMARY KEY catalog constraint — true
      // regardless of which INSERT/ON-CONFLICT branch actually ran. "tval" stays nullable: this
      // analyzer does not (yet) trace a CTE-nested INSERT's own :targetList/onConflict assignment
      // the way it does for a TOP-LEVEL one (see PgCatalogLoader.analyzeNodeTree's targetListByResno
      // KDoc), so it falls back to tval's own (nullable) catalog constraint — safe, though not as
      // precise as the live-verified 'x' this comment already documents. "oldv" stays nullable via
      // the blanket OLD-forcing rule.
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT)",
        """
        WITH u AS (
          INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y'
          RETURNING (tgt.*), OLD.tval AS oldv
        )
        SELECT * FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `an ALIASED star reaches the item-count cross-check directly`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Verified against real PostgreSQL 18.4: "RETURNING tgt.* AS whatever, OLD.id AS oldv" is
      // valid syntax, returning 3 real columns for a 2-column "tgt" (tgt.id, tgt.tval, oldv).
      // Unlike parseSelectItems, oldOrNewReturningColumns never alias-strips an item before
      // checking isStarItem against it, so "tgt.* AS whatever" as a whole does not end in ".*"
      // and is NOT recognized as a star here — hasRecognizedStarItem is false. oldOrNewColumnIndices
      // still correctly identifies item 2 ("OLD.id AS oldv") as the OLD-referencing item, so
      // oldOrNewReturningColumns returns forcedColumns = {2} (NON-null) with itemCount = 2. Since
      // the REAL column count is 3 (the star's own 2-column expansion was never counted),
      // PgCatalogLoader's "oldOrNewColumns.isNotEmpty() && itemCount != totalColumnCount" check
      // (2 != 3) fires and forces EVERY column nullable — the item-count cross-check itself, not
      // the "forcedColumns == null" branch the other star-plus-OLD tests in this class exercise.
      // The outcome is the same safe over-approximation either way, which is exactly why this
      // gap in oldOrNewReturningColumns's alias-awareness is not itself a bug: the cross-check
      // makes the missed recognition harmless. Verified against real Postgres (a fresh insert via
      // ON CONFLICT — no prior row): id = 99, tval = 'x' (both genuinely NOT NULL), oldv = NULL
      // (genuinely nullable) — but the safety net over-approximates all three to nullable here.
      // Same reasoning as the parenthesized-star test above: id NOT NULL via its PRIMARY KEY
      // catalog constraint, tval nullable (CTE-nested INSERT assignment tracing not implemented —
      // safe, not maximally precise), oldv nullable via the blanket OLD-forcing rule.
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT)",
        """
        WITH u AS (
          INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y'
          RETURNING tgt.* AS whatever, OLD.id AS oldv
        )
        SELECT * FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `star with whitespace around the dot is recognized by isStarItem and forces every column`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // CORRECTED (was: "not recognized by isStarItem's text heuristic"): #212's fix taught
      // isStarItem to collapse whitespace around a qualifying dot, so "tgt . *" IS now recognized
      // — verified via SqlUtilsTest's "recognizes a star item with whitespace around the
      // qualifying dot". That means oldOrNewReturningColumns itself now returns forcedColumns =
      // null directly (a RECOGNIZED star coincides with an OLD/NEW reference — see its KDoc), so
      // PgCatalogLoader's forceNullableColumn short-circuits on "oldOrNewColumns == null" and
      // NEVER reaches the itemCount-vs-real-column-count cross-check below it — this test no
      // longer exercises that cross-check at all (see the class KDoc). Kept as a regression guard
      // for the observable end-to-end nullability outcome (which happens to be UNCHANGED here,
      // because "tgt" has two columns and the pre-fix itemCount already mismatched the real
      // column count regardless of recognition — see `an OLD reference forces every column
      // nullable when a star recognition change loses per-column precision` for a case where
      // recognizing a star DOES change the observable outcome), not as cross-check coverage.
      // Verified against real Postgres (a fresh insert via ON CONFLICT — no prior row, so OLD
      // does not exist): id = 99, tval = 'y' (both genuinely NOT NULL), oldv = NULL (genuinely
      // nullable).
      // Same reasoning as the two star-plus-OLD tests above.
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT)",
        """
        WITH u AS (
          INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y'
          RETURNING tgt . *, OLD.tval AS oldv
        )
        SELECT * FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(3)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isFalse()
    }

    @Test
    fun `an OLD reference forces every column nullable when a star recognition change loses per-column precision`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Pins a REAL, INTENTIONAL nullability outcome change from teaching isStarItem to
      // recognize "tgt . *" — not merely a different code path reaching the same answer, unlike
      // the sibling tests above. "tgt" here has exactly ONE column, so "tgt.*"'s real expansion
      // (1 column) plus "oldv" (1 column) happens to equal the assumed item count (2) — before
      // isStarItem recognized "tgt . *", oldOrNewReturningColumns computed forcedColumns = {2}
      // (only "oldv") with NO itemCount mismatch to trigger the cross-check, so PgCatalogLoader's
      // per-column forcing applied PRECISELY: "id" (from tgt.*'s expansion) was governed by its
      // real attnotnull (a PRIMARY KEY column — genuinely NOT NULL), and only "oldv" was forced.
      // Now that "tgt . *" IS recognized, oldOrNewReturningColumns returns forcedColumns = null
      // directly, and PgCatalogLoader forces EVERY column nullable — "id" included, even though
      // it is genuinely NOT NULL. The direction is SAFE (over-nullable beats a fabricated NOT
      // NULL elsewhere), which is why it is kept rather than reverted, but it is a real loss of
      // precision for this specific shape, not merely a different route to an unchanged answer.
      // Verified against real Postgres (a fresh insert via ON CONFLICT — no prior row, so OLD
      // does not exist): id = 99 (genuinely NOT NULL), oldv = NULL (genuinely nullable).
      // prosqlbody reports "id" NOT NULL directly off its PRIMARY KEY catalog constraint (the
      // precision the pre-fix production code path happened to have here too), "oldv" nullable
      // via the blanket OLD-forcing rule.
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY)",
        """
        WITH u AS (
          INSERT INTO tgt VALUES (99) ON CONFLICT (id) DO UPDATE SET id = 99
          RETURNING tgt . *, OLD.id AS oldv
        )
        SELECT * FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `the same precision loss extends to one of the newly-normalized star spellings`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Same outcome change as the test above, for one of the THREE spellings isStarItem was
      // fixed to additionally normalize (a trailing comment sitting OUTSIDE a wrapping
      // parenthesis) — confirming the precision loss extends to those spellings too, exactly as
      // expected: any input that flips from "unrecognized" to "recognized" on a one-column
      // relation loses this same per-column precision. Verified against real Postgres 18.4: "(tgt
      // .*) -- c" is valid syntax, id = 99 (genuinely NOT NULL), oldv = NULL (genuinely nullable).
      // Same reasoning as the test above.
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY)",
        "WITH u AS (\n" +
          "  INSERT INTO tgt VALUES (99) ON CONFLICT (id) DO UPDATE SET id = 99\n" +
          "  RETURNING (tgt.*) -- c\n" +
          "  , OLD.id AS oldv\n" +
          ")\n" +
          "SELECT * FROM u",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `an untracked bracket can no longer cancel out a star's split error and defeat the cross-check`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // FIX 1: splitAtTopLevel previously did not track "[...]", so "ARRAY[1, 2]"'s internal comma
      // split it into two items — which, in THIS exact list, numerically canceled out the "tgt . *"
      // star's own split error: 4 real columns (id, tval, oldv, arr), and a broken split
      // ["tgt . *", "OLD.tval AS oldv", "ARRAY[1", "2] AS arr"] that ALSO produced 4 items,
      // defeating oldOrNewReturningColumns's real-column-count cross-check entirely and forcing
      // the WRONG column (the second half of "tgt.*"'s expansion, i.e. tval) instead of "oldv".
      // CORRECTED (was: "the safety net over-approximates ... because real column count 4 vs. the
      // CORRECTLY split item count 3"): #212's later fix taught isStarItem to recognize "tgt . *"
      // (whitespace around the dot), so oldOrNewReturningColumns now returns forcedColumns = null
      // directly for THIS list — a RECOGNIZED star coincides with an OLD/NEW reference — and
      // PgCatalogLoader's forceNullableColumn short-circuits on that null before ever comparing
      // item count (3) against real column count (4). The item-count cross-check this test
      // originally exercised is therefore NOT reached here anymore either; see the class KDoc.
      // The observable outcome (all four forced nullable) is UNCHANGED here regardless, because
      // the pre-fix itemCount already mismatched the real column count on its own — see `an OLD
      // reference forces every column nullable when a star recognition change loses per-column
      // precision` for a case where recognizing a star DOES change the observable outcome.
      // Verified against real Postgres (a fresh insert via ON CONFLICT — no prior row): id = 99,
      // tval = 'x', arr = {1,2} (all genuinely NOT NULL), oldv = NULL (genuinely nullable) — the
      // safety net still over-approximates all four to nullable, same accepted tradeoff as the
      // other star-plus-OLD tests in this file, just reached via a different branch than before.
      // prosqlbody: id NOT NULL (PRIMARY KEY catalog constraint), tval nullable (CTE-nested
      // assignment tracing not implemented, safe not maximal), oldv nullable (blanket OLD-forcing),
      // arr NOT NULL — ARRAY[1, 2] is a genuine array-literal constructor, never itself NULL.
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT)",
        """
        WITH u AS (
          INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y'
          RETURNING tgt . *, OLD.tval AS oldv, ARRAY[1, 2] AS arr
        )
        SELECT * FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(4)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
      assertThat(query.columns[2].notNull).isFalse()
      assertThat(query.columns[3].notNull).isTrue()
    }

    @Test
    fun `an untracked bracket alone, with no star at all, no longer corrupts the item count`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // NOT independently demonstrative of FIX 1: confirmed (via the established git-stash
      // before/after technique) that this exact shape ALREADY passed at 94b5a2d — the untracked
      // "[...]" corrupted the split into 3 items ("ARRAY[1", "2] AS arr", "OLD.tval AS oldv")
      // against 2 real columns, and that MISMATCH (3 != 2) was already caught by the existing
      // real-column-count cross-check, which forced every column nullable — coincidentally
      // correct for "oldv" (genuinely nullable) even before this fix. What this DOES confirm is
      // that FIX 1 doesn't regress this shape: after tracking "[...]", the split is the CORRECT 2
      // items, oldOrNewReturningColumns identifies "oldv" (not "arr") as the OLD-referencing item
      // via precise per-column mapping rather than the coarser "force everything" fallback — a
      // structural improvement even though it happens to produce the same observable nullability
      // here. It does NOT prove "arr" keeps its true NOT NULL status either way: the stub path's
      // own metadata probe reports a computed `ARRAY[]` expression's nullability as
      // unknown/nullable regardless of forceNullableColumn, a separate, pre-existing imprecision
      // of the metadata-probe stub itself, not of this fix. Verified against real Postgres (a
      // fresh insert via ON CONFLICT): arr = {1,2} (genuinely NOT NULL), oldv = NULL (genuinely
      // nullable — no prior row).
      // prosqlbody structurally recognizes ARRAY[1, 2] as a genuine array-literal constructor,
      // never itself NULL — "oldv" stays nullable via the blanket OLD-forcing rule.
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT)",
        """
        WITH u AS (
          INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y'
          RETURNING ARRAY[1, 2] AS arr, OLD.tval AS oldv
        )
        SELECT * FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `an OLD reference without a star still forces only the referencing column, not the whole body`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING OLD requires PostgreSQL 18+")
      // Restores coverage lost when #212 taught isStarItem to recognize "tgt . *": every OTHER
      // test in this class now involves a star, so every one of them resolves via
      // oldOrNewReturningColumns's forcedColumns = null (a RECOGNIZED star coincides with an
      // OLD/NEW reference) and PgCatalogLoader's whole-body forceAllNullable fallback — leaving
      // NOTHING in this class asserting on the DISTINCT per-column forcing mechanism
      // (forcedColumns non-null, containing only the specific OLD/NEW-referencing item's index).
      // With no star at all here, itemCount trivially matches the real column count, so
      // oldOrNewMappingUnreliable is false and PgCatalogLoader's
      // "oldOrNewColumns.orEmpty().contains(columnIndex)" line is what decides each column's
      // fate. If that per-column check were ever replaced by forcing the whole body nullable
      // whenever ANY OLD/NEW reference is present, "id" below would flip from NOT NULL to
      // nullable and this assertion would fail. Verified against real Postgres (a fresh insert
      // via ON CONFLICT — no prior row, so OLD does not exist): id = 99 (genuinely NOT NULL, the
      // just-inserted row's own column), oldv = NULL (genuinely nullable).
      val query = analyzeWithSchema(
        "CREATE TABLE tgt (id INT PRIMARY KEY, tval TEXT)",
        """
        WITH u AS (
          INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y'
          RETURNING id, OLD.tval AS oldv
        )
        SELECT * FROM u
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `INTERSECT is conservatively nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name FROM t INTERSECT SELECT name FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `EXCEPT is conservatively nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL)",
        "SELECT name FROM t EXCEPT SELECT name FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isFalse()
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
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `question mark inside string literal is preserved`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "SELECT id FROM t WHERE name = 'what?' OR id = ?",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `question mark inside comment is preserved`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "SELECT id FROM t -- why?\nWHERE id = ?",
      )
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `block comment before SELECT`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "/* block comment */ SELECT id FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `multiple comments before SELECT`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "-- line 1\n-- line 2\n/* block */\nSELECT id FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `comment before CTE`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)",
        "-- setup\nWITH cte AS (SELECT id, name FROM t) SELECT * FROM cte",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `comment before INSERT RETURNING`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id SERIAL NOT NULL, name TEXT NOT NULL)",
        "-- cleanup\nINSERT INTO t(name) VALUES ('test') RETURNING *",
      )
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }
  }

  @Nested
  inner class JsonOperations {

    @Test
    fun `JSON_VALUE with default NULL behavior`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "JSON_VALUE requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_VALUE(data, '\$.name') AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `JSON_VALUE with DEFAULT on empty and error is still nullable`() {
      // JSON_VALUE unwraps a path match to a JSON `null` value into a genuine SQL NULL — a
      // successful match, not the "no match"/"error" case ON EMPTY/ON ERROR control. Verified
      // live: `JSON_VALUE('{"name": null}'::jsonb, '$.name' RETURNING TEXT DEFAULT 'N/A' ON EMPTY
      // DEFAULT 'ERR' ON ERROR) IS NULL` is `true`.
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "JSON_VALUE requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_VALUE(data, '\$.name' RETURNING TEXT DEFAULT 'N/A' ON EMPTY DEFAULT 'ERR' ON ERROR) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `JSON_QUERY with DEFAULT on empty and error is non-null for a non-null context item`() {
      // Unlike JSON_VALUE, JSON_QUERY never unwraps a matched JSON `null` into a genuine SQL NULL
      // — it returns the JSON text `null` instead. Verified live: `JSON_QUERY('{"a": null}'::jsonb,
      // '$.a' EMPTY ARRAY ON EMPTY EMPTY OBJECT ON ERROR) IS NULL` is `false`, so a proven non-null
      // context item combined with safe ON EMPTY/ON ERROR behavior is a genuinely non-null column.
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "JSON_QUERY requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_QUERY(data, '\$.items' EMPTY ARRAY ON EMPTY EMPTY ARRAY ON ERROR) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `JSON_EXISTS is non-null`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "JSON_EXISTS requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_EXISTS(data, '\$.name') AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `JSON_QUERY with default NULL behavior`() {
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "JSON_QUERY requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        "CREATE TABLE t (data jsonb NOT NULL)",
        "SELECT JSON_QUERY(data, '\$.items') AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE WHEN NOT MATCHED BY SOURCE RETURNING JSON_QUERY over the source column is nullable`() {
      // The source Var (jsrc.doc) is buried inside a JsonExpr argument, not read directly — this
      // is the shape containsVarOutsideRelation must recurse into a JsonExpr to see. Verified
      // live: for the deleted (source-absent) row, this whole expression is NULL despite doc
      // being NOT NULL and both ON EMPTY/ON ERROR defaults being non-null constants — the source
      // row itself never existed, so there was nothing for JSON_QUERY to evaluate against.
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, note TEXT NOT NULL);
        CREATE TABLE jsrc (id INT PRIMARY KEY, doc JSONB NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING jsrc ON tgt.id = jsrc.id
        WHEN NOT MATCHED BY SOURCE THEN DELETE
        RETURNING JSON_QUERY(jsrc.doc, '${'$'}.a' DEFAULT '1'::jsonb ON EMPTY DEFAULT '1'::jsonb ON ERROR) AS result
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE WHEN NOT MATCHED BY SOURCE RETURNING JSON_EXISTS over the source column is nullable`() {
      // Same JsonExpr-hides-a-Var shape as the JSON_QUERY case above, for the JSON_EXISTS_OP
      // variant of JsonExpr. Verified live: NULL, not `false`, for the source-absent row — the
      // source row itself is absent, so there is nothing for JSON_EXISTS to test against.
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, note TEXT NOT NULL);
        CREATE TABLE jsrc (id INT PRIMARY KEY, doc JSONB NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING jsrc ON tgt.id = jsrc.id
        WHEN NOT MATCHED BY SOURCE THEN DELETE
        RETURNING JSON_EXISTS(jsrc.doc, '${'$'}.a') AS result
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE WHEN NOT MATCHED BY SOURCE RETURNING JSON_VALUE over the source column is nullable`() {
      // Same JsonExpr-hides-a-Var shape, for the JSON_VALUE_OP variant. Verified live: NULL for
      // the source-absent row despite both ON EMPTY/ON ERROR defaults being non-null constants.
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "WHEN NOT MATCHED BY SOURCE requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, note TEXT NOT NULL);
        CREATE TABLE jsrc (id INT PRIMARY KEY, doc JSONB NOT NULL)
        """.trimIndent(),
        """
        MERGE INTO tgt USING jsrc ON tgt.id = jsrc.id
        WHEN NOT MATCHED BY SOURCE THEN DELETE
        RETURNING JSON_VALUE(jsrc.doc, '${'$'}.a' DEFAULT '1' ON EMPTY DEFAULT '1' ON ERROR) AS result
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `MERGE without WHEN NOT MATCHED BY SOURCE keeps a JSON_EXISTS over the source column NOT NULL`() {
      // Positive control, same schema as the JsonExpr-hides-a-Var cases above but WHEN MATCHED
      // only (no WHEN NOT MATCHED BY SOURCE): every row RETURNING can see has a genuine source
      // match, so mergeAbsentVarnos' EXPLAIN resolution should still land on NOT NULL even once
      // containsVarOutsideRelation recurses into the JsonExpr and triggers that resolution.
      // JSON_EXISTS (not JSON_VALUE, which is unconditionally nullable — see evaluateJsonExpr's
      // JSON_VALUE_OP branch) directly over the NOT NULL jsonb column, at a path proven present by
      // the fixture data, is the shape that can actually resolve NOT NULL — `to_jsonb(text)` was
      // deliberately avoided here since it is not itself on any never-null-for-non-null-input
      // safe-list and would make this control fail for a reason unrelated to containsVarOutsideRelation.
      // Verified live: `IS NULL` is `false` for the matched row.
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "MERGE RETURNING requires PostgreSQL 17+")
      val query = analyzeWithSchema(
        """
        CREATE TABLE tgt (id INT PRIMARY KEY, note TEXT NOT NULL);
        CREATE TABLE jsrc (id INT PRIMARY KEY, doc JSONB NOT NULL)
        """.trimIndent(),
        """
        WITH m AS (
          MERGE INTO tgt USING jsrc ON tgt.id = jsrc.id
          WHEN MATCHED THEN UPDATE SET note = 'updated'
          RETURNING JSON_EXISTS(jsrc.doc, '${'$'}.a') AS result
        )
        SELECT result FROM m
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `XMLFOREST is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (name TEXT NOT NULL, val INT NOT NULL)",
        "SELECT xmlforest(name, val) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `XMLCONCAT is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (a xml NOT NULL, b xml NOT NULL)",
        "SELECT xmlconcat(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `array subscript is nullable`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (arr INT[] NOT NULL)",
        "SELECT arr[1] AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `UNNEST in FROM`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (arr INT[] NOT NULL)",
        "SELECT * FROM unnest(ARRAY[1, 2, 3]) AS x",
      )
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `ARRAY subquery is non-null`() {
      val query = analyzeWithSchema(
        "CREATE TABLE t (id INT NOT NULL)",
        "SELECT array(SELECT id FROM t) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
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
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `ROW constructor correctly reports NOT NULL via the prosqlbody fallback`() {
      // A bare, uncast ROW(...) produces an anonymous "record"-typed column, which PostgreSQL
      // refuses to expose on a VIEW ("column result has pseudo-type record") — this SQL has no
      // DML at all, but CREATE VIEW's failure routes it through queryColumnNullability's
      // fallback regardless, since that fallback cannot distinguish "CREATE VIEW failed because
      // of DML" from "CREATE VIEW failed for an unrelated reason". Before the prosqlbody cutover,
      // that fallback was a text-based probe with no way to recognize a RowExpr's own shape, so it
      // reported nullable rather than assert a proof it did not actually perform, even though a
      // constructed ROW(...) value is, in fact, never itself NULL. The fallback is prosqlbody now:
      // it is NOT subject to CREATE VIEW's pseudo-type rejection, and RowExpr is a node type its
      // structural analysis already recognizes as never itself NULL. Verified live: `SELECT
      // ROW(a, b) AS result FROM t` returns `result = (1,2)` for a matching row, never NULL.
      val query = analyzeWithSchema(
        "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL)",
        "SELECT ROW(a, b) AS result FROM t",
      )
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `ROW constructor with a LEFT JOIN correctly reports NOT NULL via the prosqlbody fallback`() {
      // This SQL has no DML at all, but CREATE VIEW rejects a bare ROW(...)'s anonymous "record"
      // pseudo-type regardless, so it still reaches queryColumnNullability's fallback — prosqlbody
      // now, in production. Before the cutover, that fallback's text-based null-extending-construct
      // scan found the LEFT JOIN and forced EVERY column nullable, including "t.a", which is
      // declared NOT NULL and is never actually null-extended by this join (t is the LEFT side,
      // not u) — an accepted, safe-direction imprecision at the time (a metadata-only answer that
      // instead trusted the join structure would have been unsafe for the mirror case, `SELECT u.x
      // FROM t LEFT JOIN u`, where `u.x` genuinely CAN be null-extended). prosqlbody reads the same
      // :varnullingrels this whole cutover is built on, so it resolves BOTH correctly without that
      // tradeoff: verified live, both columns are genuinely NOT NULL for a matching row.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (a INT NOT NULL, b INT NOT NULL);
        CREATE TABLE u (a INT NOT NULL)
        """.trimIndent(),
        "SELECT t.a, ROW(t.a, t.b) AS result FROM t LEFT JOIN u ON u.a = t.a",
      )
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
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
    fun `a view column backed by two same-named source columns is nullable if either source is nullable`() {
      // "val" is SELECTed only from "b" (nullable), but the JOIN condition ALSO references "a.val"
      // (NOT NULL) — PostgreSQL's pg_depend records a normal dependency for EVERY source column the
      // view's definition touches anywhere, not just the ones that feed a specific output column, so
      // matching those dependency rows to a view column purely BY NAME (as this loader does) sees
      // both "a.val" and "b.val" as candidate sources for the single output column "val". Verified
      // live: this exact schema produces two pg_depend rows for "v.val" — (b.val, notNull=false) and
      // (a.val, notNull=true). loadViewColumnNullability must apply the same any-nullable-source-wins
      // reduction the relid-keyed viewColumnNotNullByRelidAndAttnum already does, not let the NOT
      // NULL row win regardless of arrival order.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE a (id INT NOT NULL, val TEXT NOT NULL);
            CREATE TABLE b (id INT NOT NULL, val TEXT);
            CREATE VIEW v AS SELECT b.val FROM a JOIN b ON a.val = b.val
            """.trimIndent(),
          )
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val nonNull = catalogLoader.loadViewColumnNullability(schemaName)
          assertThat(nonNull.contains("v.val")).isFalse()
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `a view column backed by two same-named NOT NULL source columns stays NOT NULL`() {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute(
            """
            CREATE TABLE a (id INT NOT NULL, val TEXT NOT NULL);
            CREATE TABLE b (id INT NOT NULL, val TEXT NOT NULL);
            CREATE VIEW v AS SELECT b.val FROM a JOIN b ON a.val = b.val
            """.trimIndent(),
          )
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val nonNull = catalogLoader.loadViewColumnNullability(schemaName)
          assertThat(nonNull.contains("v.val")).isTrue()
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
  inner class ProsqlbodyProbe {

    @Test
    fun `RETURNING OLD is nullable while RETURNING NEW is not, for the same NOT NULL column`() {
      // PostgreSQL 18's RETURNING WITH (OLD AS o, NEW AS n) marks both the OLD and NEW Vars
      // byte-identical except :varreturningtype (verified live) — including an EMPTY
      // :varnullingrels on both — so nothing but that field distinguishes them. This MERGE's WHEN
      // NOT MATCHED THEN INSERT action has no OLD row to read at all (the target row didn't exist
      // before this statement ran), so o.name must be nullable regardless of "name"'s own NOT NULL
      // constraint, while n.name — a fresh value just inserted into that same NOT NULL column, not
      // outer-join-nulled — is genuinely proven non-null. Verified against real Postgres: querying
      // the function's own result shows o.name = NULL, n.name = 'new-name' for the inserted row.
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 18, "RETURNING WITH (OLD AS o, NEW AS n) requires PG 18+")
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute("CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL)")
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val result = ColumnNullabilityAnalyzer(catalogLoader).queryColumnNullabilityViaProsqlbody(
            """
            MERGE INTO t USING (VALUES (1, 'new-name')) AS s(id, name) ON t.id = s.id
            WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)
            RETURNING WITH (OLD AS o, NEW AS n) o.name, n.name
            """.trimIndent(),
          )
          assertThat(result).isEqualTo(listOf(true, false))
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `a trailing line comment does not swallow the probe function's terminator`() {
      // A trailing "--" comment with nothing after it is an ordinary, syntactically COMPLETE SQL
      // statement on its own — the same query text a "queries.sql" file could legitimately end
      // with. The probe wraps it as "BEGIN ATOMIC <sql>; END": without a newline separating <sql>
      // from "; END", the trailing line comment extends over the appended terminator too, and the
      // whole CREATE FUNCTION statement fails with a syntax error instead of probing anything.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute("CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)")
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val result = ColumnNullabilityAnalyzer(catalogLoader).queryColumnNullabilityViaProsqlbody(
            """
            SELECT id, name FROM t
            -- only active rows
            """.trimIndent(),
          )
          assertThat(result).isEqualTo(listOf(false, false))
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `a trailing terminated block comment never needed the newline fix`() {
      // Unlike a "--" line comment, a "/* ... */" block comment closes itself before the appended
      // "; END" regardless of a newline — verified live. Pinned here so a future change to the
      // terminator-safety fix cannot silently start relying on this shape needing help it doesn't.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute("CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)")
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val result = ColumnNullabilityAnalyzer(catalogLoader).queryColumnNullabilityViaProsqlbody(
            "SELECT id, name FROM t /* only active rows */",
          )
          assertThat(result).isEqualTo(listOf(false, false))
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `an unterminated block comment fails the probe safely instead of throwing`() {
      // Never reachable through the real pipeline — a SQL statement with an unterminated block
      // comment cannot itself be prepared for column metadata before queryColumnNullability is
      // ever invoked, so this input never reaches this method in practice. Pinned anyway as a
      // direct-call safety net: the probe's own SQLException catch must degrade to `null`, not
      // propagate or misbehave, for input this malformed.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute("CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)")
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val result = ColumnNullabilityAnalyzer(catalogLoader).queryColumnNullabilityViaProsqlbody(
            """
            SELECT id, name FROM t
            /* unterminated
            """.trimIndent(),
          )
          assertThat(result).isNull()
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    @Test
    fun `a body ending inside an unterminated dollar-quoted string fails the probe safely instead of throwing`() {
      // Never reachable through the real pipeline, for the same reason as the unterminated block
      // comment above — pinned as a direct-call safety net only.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use {
          it.execute("CREATE SCHEMA $schemaName")
          it.execute("SET search_path TO $schemaName")
          it.execute("CREATE TABLE t (id INT NOT NULL, name TEXT NOT NULL)")
        }
        try {
          val catalogLoader = PgCatalogLoader(connection)
          val result = ColumnNullabilityAnalyzer(catalogLoader).queryColumnNullabilityViaProsqlbody(
            "SELECT id, name FROM t WHERE name = \$\$unterminated",
          )
          assertThat(result).isNull()
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
      // Resolved by NAME from pg_catalog, independent of functionStrictnessByOid's own SQL (which
      // is a direct passthrough of pg_proc.proisstrict), so this test verifies the CONTENT of the
      // loaded map for two specific, known-in-advance oids rather than merely that some entries
      // happen to be true and the always-non-null set happens to be non-empty — the bare
      // `isNotEmpty()` this replaces would stay green even if strictness were loaded backwards.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val strictness = catalogLoader.functionStrictnessByOid
        val oidsByPronameAndArgtypes = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.proname, p.oid::integer AS oid,
              COALESCE(
                (SELECT array_agg(t.typname::text ORDER BY u.ordinality)
                 FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
                 JOIN pg_catalog.pg_type t ON t.oid = u.type_oid),
                ARRAY[]::text[]
              ) AS argtypes
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'pg_catalog' AND p.proname IN ('upper', 'concat')
            """.trimIndent(),
          ).use { rs ->
            buildMap {
              while (rs.next()) {
                @Suppress("UNCHECKED_CAST")
                val argumentTypes = (rs.getArray("argtypes").array as Array<String>).toList()
                put("${rs.getString("proname")}(${argumentTypes.joinToString(",")})", rs.getInt("oid"))
              }
            }
          }
        }
        val upperTextOid = requireNotNull(oidsByPronameAndArgtypes["upper(text)"])
        val concatOid = requireNotNull(oidsByPronameAndArgtypes["concat(any)"])
        assertThat(strictness[upperTextOid]).isEqualTo(true)
        assertThat(strictness[concatOid]).isEqualTo(false)
      }
    }

    @Test
    fun `aggregateHasNonNullInitialValue-listed aggregates are non-null over empty input, unlike sum and avg`() {
      // The mis-named predecessor of this test only asserted that BOTH the true and false subsets
      // of aggregateHasNonNullInitialValue were non-empty — a property satisfied even by a WRONG
      // classification, as long as at least one aggregate landed on each side. This version runs
      // every aggregate this map claims is non-null-initial over a genuinely EMPTY input (`...
      // WHERE false`) and asserts the result really is non-null — the ground truth the map's name
      // claims — then spot-checks that sum/avg/max/min, which the map correctly excludes, really
      // do return null over the same empty input.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val nonNullInitialOids = catalogLoader.aggregateHasNonNullInitialValue.filterValues { it }.keys
        assertThat(nonNullInitialOids.isNotEmpty()).isTrue()

        val signaturesByOid = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.oid::integer AS oid, p.proname,
              COALESCE(
                (SELECT array_agg(t.typname::text ORDER BY u.ordinality)
                 FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
                 JOIN pg_catalog.pg_type t ON t.oid = u.type_oid),
                ARRAY[]::text[]
              ) AS argtypes
            FROM pg_catalog.pg_proc p
            """.trimIndent(),
          ).use { rs ->
            buildMap {
              while (rs.next()) {
                @Suppress("UNCHECKED_CAST")
                val argumentTypes = (rs.getArray("argtypes").array as Array<String>).toList()
                put(rs.getInt("oid"), rs.getString("proname") to argumentTypes)
              }
            }
          }
        }

        val failures = mutableListOf<String>()
        connection.createStatement().use { stmt ->
          for (oid in nonNullInitialOids) {
            val (name, argumentTypes) = requireNotNull(signaturesByOid[oid]) { "No pg_proc row for aggregate oid $oid" }
            if (aggregateOverEmptyInputIsNull(stmt, name, argumentTypes)) {
              failures += "$name(${argumentTypes.joinToString(",")}) returned NULL over empty input"
            }
          }
        }
        assertThat(failures).isEqualTo(emptyList<String>())

        connection.createStatement().use { stmt ->
          assertThat(aggregateOverEmptyInputIsNull(stmt, "sum", listOf("int4"))).isTrue()
          assertThat(aggregateOverEmptyInputIsNull(stmt, "avg", listOf("numeric"))).isTrue()
          assertThat(aggregateOverEmptyInputIsNull(stmt, "max", listOf("int4"))).isTrue()
          assertThat(aggregateOverEmptyInputIsNull(stmt, "min", listOf("int4"))).isTrue()
        }
      }
    }

    @Test
    fun `immutableFunctionOids includes an immutable example and excludes stable, volatile, and set-returning ones`() {
      // Deliberately does NOT compare immutableFunctionOids against a live re-query of
      // `provolatile = 'i' AND NOT proretset AND prokind IN ('f', 'w')` — PgCatalogLoader.loadImmutableFunctionOids
      // runs EXACTLY that predicate (see PgCatalogLoader.kt), so an `isEqualTo` against the same
      // predicate here could only ever detect broken plumbing (a query that fails to run at all),
      // never a WRONG predicate: a mutation to the production SQL would move both sides of the
      // comparison together and the test would stay green. The named-function assertions below are
      // this test's only teeth, so they are widened past the original two (STABLE, VOLATILE) to
      // also cover the SET-RETURNING dimension of the predicate, which nothing here previously
      // exercised with a concrete example at all: `unnest(anyarray)` is itself IMMUTABLE
      // (`provolatile = 'i'`) but set-returning (`proretset = true`), so it must be excluded by the
      // `NOT proretset` conjunct specifically, not by volatility.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val immutableOids = catalogLoader.immutableFunctionOids
        assertThat(immutableOids.isNotEmpty()).isTrue()

        val exampleOids = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.oid::integer AS oid, p.proname,
              COALESCE(
                (SELECT array_agg(t.typname::text ORDER BY u.ordinality)
                 FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
                 JOIN pg_catalog.pg_type t ON t.oid = u.type_oid),
                ARRAY[]::text[]
              ) AS argtypes
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'pg_catalog' AND p.proname IN ('abs', 'date_trunc', 'random', 'unnest')
            """.trimIndent(),
          ).use { rs ->
            buildMap {
              while (rs.next()) {
                @Suppress("UNCHECKED_CAST")
                val argumentTypes = (rs.getArray("argtypes").array as Array<String>).toList()
                put("${rs.getString("proname")}(${argumentTypes.joinToString(",")})", rs.getInt("oid"))
              }
            }
          }
        }
        // abs(int4) is IMMUTABLE and non-set-returning: it MUST be present.
        val absImmutableOid = requireNotNull(exampleOids["abs(int4)"])
        assertThat(immutableOids.contains(absImmutableOid)).isTrue()
        // date_trunc(text, timestamptz) is STABLE (its result depends on the session timezone).
        val dateTruncStableOid = requireNotNull(exampleOids["date_trunc(text,timestamptz)"])
        assertThat(immutableOids.contains(dateTruncStableOid)).isFalse()
        // random() is VOLATILE.
        val randomVolatileOid = requireNotNull(exampleOids["random()"])
        assertThat(immutableOids.contains(randomVolatileOid)).isFalse()
        // unnest(anyarray) is IMMUTABLE but SET-RETURNING — excluded by NOT proretset, not by volatility.
        val unnestSetReturningOid = requireNotNull(exampleOids["unnest(anyarray)"])
        assertThat(immutableOids.contains(unnestSetReturningOid)).isFalse()
      }
    }

    @Test
    fun `neverNullForNonNullInputOids and lagLeadWithDefaultOids contain no VARIADIC pg_proc rows`() {
      // PgCatalogLoader.neverNullForNonNullInputOids's KDoc claims NO function on that list is
      // VARIADIC ("verified live on PostgreSQL 16, 17, and 18") — unlike alwaysNonNullFunctionOids
      // and nonNullIffFirstArgumentNonNullFunctionOids, whose sole entries (concat/concat_ws) are
      // DELIBERATELY, DOCUMENTED-ly VARIADIC ("any") and are excluded from this check for exactly
      // that reason (see PgNodeExpression.FuncExpr.isVariadic's KDoc and the two properties' own
      // KDoc for why the VARIADIC calling form is handled separately rather than trusted here).
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val oidsToCheck = catalogLoader.neverNullForNonNullInputOids + catalogLoader.lagLeadWithDefaultOids
        assertThat(oidsToCheck.isNotEmpty()).isTrue()
        val variadicOids = connection.createStatement().use { stmt ->
          stmt.executeQuery("SELECT oid::integer FROM pg_catalog.pg_proc WHERE provariadic != 0").use { rs ->
            buildSet { while (rs.next()) add(rs.getInt(1)) }
          }
        }
        assertThat(oidsToCheck.intersect(variadicOids)).isEqualTo(emptySet())
      }
    }

    @Test
    fun `pg_proc rows for concat and concat_ws match a known snapshot`() {
      // The catalog-drift analogue, for alwaysNonNullFunctionOids/nonNullIffFirstArgumentNonNullFunctionOids,
      // of `pg_operator rows for safe-listed symbols match a known snapshot`/`pg_proc rows for
      // safe-listed function names match a known snapshot` above: enumerates EVERY pg_catalog
      // overload of concat/concat_ws (not just the strictness-filtered ones production actually
      // loads), so a future PostgreSQL version adding a new overload of either name — e.g. a
      // second, STRICT concat overload that would need excluding from alwaysNonNullFunctionOids
      // the same way concat_ws already is — fails loudly here instead of silently riding along.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val actualSignatures = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.proname,
              COALESCE(
                (SELECT array_agg(t.typname::text ORDER BY u.ordinality)
                 FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
                 JOIN pg_catalog.pg_type t ON t.oid = u.type_oid),
                ARRAY[]::text[]
              ) AS argtypes
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'pg_catalog' AND p.proname IN ('concat', 'concat_ws')
            """.trimIndent(),
          ).use { rs ->
            buildSet {
              while (rs.next()) {
                @Suppress("UNCHECKED_CAST")
                val argumentTypes = (rs.getArray("argtypes").array as Array<String>).toList()
                add("${rs.getString("proname")}(${argumentTypes.joinToString(",")})")
              }
            }
          }
        }
        assertThat(actualSignatures).isEqualTo(setOf("concat(any)", "concat_ws(text,any)"))
      }
    }

    @Test
    fun `pg_proc rows for lag and lead match a known snapshot`() {
      // The catalog-drift analogue, for lagLeadWithDefaultOids, of the snapshot tests above:
      // enumerates EVERY window-function overload of lag/lead (1-, 2-, and 3-argument alike), so a
      // future PostgreSQL version adding a 4th overload fails loudly here, forcing a human to
      // decide whether it belongs on lagLeadWithDefaultOids before it silently would (or
      // wouldn't).
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val actualSignatures = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.proname,
              COALESCE(
                (SELECT array_agg(t.typname::text ORDER BY u.ordinality)
                 FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
                 JOIN pg_catalog.pg_type t ON t.oid = u.type_oid),
                ARRAY[]::text[]
              ) AS argtypes
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'pg_catalog' AND p.proname IN ('lag', 'lead') AND p.prokind = 'w'
            """.trimIndent(),
          ).use { rs ->
            buildSet {
              while (rs.next()) {
                @Suppress("UNCHECKED_CAST")
                val argumentTypes = (rs.getArray("argtypes").array as Array<String>).toList()
                add("${rs.getString("proname")}(${argumentTypes.joinToString(",")})")
              }
            }
          }
        }
        assertThat(actualSignatures).isEqualTo(
          setOf(
            "lag(anyelement)",
            "lag(anyelement,int4)",
            "lag(anycompatible,int4,anycompatible)",
            "lead(anyelement)",
            "lead(anyelement,int4)",
            "lead(anycompatible,int4,anycompatible)",
          ),
        )
      }
    }

    @Test
    fun `pg_aggregate rows match a known snapshot`() {
      // The catalog-drift analogue, for aggregateHasNonNullInitialValue, of the pg_proc/pg_cast
      // snapshot tests above. aggregateHasNonNullInitialValue has no name restriction at all (see
      // its loader) — it classifies EVERY pg_aggregate row in the whole catalog — so its drift
      // surface is the entire catalog, not a curated name list. KNOWN_AGGREGATE_SNAPSHOT below was
      // captured on PostgreSQL 16 (identical to 17); PostgreSQL 18 added exactly four new
      // castfunc-unrelated overloads (max/min of bytea and record), none of which have a non-null
      // initial value, so they are listed as version-gated additions rather than folded into the
      // fixed snapshot, mirroring the cast/function snapshot tests' split above.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val actualSignatures = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.proname,
              COALESCE(
                (SELECT array_agg(t.typname::text ORDER BY u.ordinality)
                 FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
                 JOIN pg_catalog.pg_type t ON t.oid = u.type_oid),
                ARRAY[]::text[]
              ) AS argtypes
            FROM pg_catalog.pg_aggregate a
            JOIN pg_catalog.pg_proc p ON p.oid = a.aggfnoid
            """.trimIndent(),
          ).use { rs ->
            buildSet {
              while (rs.next()) {
                @Suppress("UNCHECKED_CAST")
                val argumentTypes = (rs.getArray("argtypes").array as Array<String>).toList()
                add("${rs.getString("proname")}(${argumentTypes.joinToString(",")})")
              }
            }
          }
        }
        val knownAggregateSnapshot = setOf(
          "any_value(anyelement)", "array_agg(anyarray)", "array_agg(anynonarray)",
          "avg(float4)", "avg(float8)", "avg(int2)", "avg(int4)", "avg(int8)", "avg(interval)", "avg(numeric)",
          "bit_and(bit)", "bit_and(int2)", "bit_and(int4)", "bit_and(int8)",
          "bit_or(bit)", "bit_or(int2)", "bit_or(int4)", "bit_or(int8)",
          "bit_xor(bit)", "bit_xor(int2)", "bit_xor(int4)", "bit_xor(int8)",
          "bool_and(bool)", "bool_or(bool)",
          "corr(float8,float8)",
          "count()", "count(any)",
          "covar_pop(float8,float8)", "covar_samp(float8,float8)",
          "cume_dist(any)", "dense_rank(any)", "every(bool)",
          "json_agg(anyelement)", "json_agg_strict(anyelement)",
          "json_object_agg(any,any)", "json_object_agg_strict(any,any)",
          "json_object_agg_unique(any,any)", "json_object_agg_unique_strict(any,any)",
          "jsonb_agg(anyelement)", "jsonb_agg_strict(anyelement)",
          "jsonb_object_agg(any,any)", "jsonb_object_agg_strict(any,any)",
          "jsonb_object_agg_unique(any,any)", "jsonb_object_agg_unique_strict(any,any)",
          "max(anyarray)", "max(anyenum)", "max(bpchar)", "max(date)", "max(float4)", "max(float8)",
          "max(inet)", "max(int2)", "max(int4)", "max(int8)", "max(interval)", "max(money)",
          "max(numeric)", "max(oid)", "max(pg_lsn)", "max(text)", "max(tid)", "max(time)",
          "max(timestamp)", "max(timestamptz)", "max(timetz)", "max(xid8)",
          "min(anyarray)", "min(anyenum)", "min(bpchar)", "min(date)", "min(float4)", "min(float8)",
          "min(inet)", "min(int2)", "min(int4)", "min(int8)", "min(interval)", "min(money)",
          "min(numeric)", "min(oid)", "min(pg_lsn)", "min(text)", "min(tid)", "min(time)",
          "min(timestamp)", "min(timestamptz)", "min(timetz)", "min(xid8)",
          "mode(anyelement)", "percent_rank(any)",
          "percentile_cont(_float8,float8)", "percentile_cont(_float8,interval)",
          "percentile_cont(float8,float8)", "percentile_cont(float8,interval)",
          "percentile_disc(_float8,anyelement)", "percentile_disc(float8,anyelement)",
          "range_agg(anymultirange)", "range_agg(anyrange)",
          "range_intersect_agg(anymultirange)", "range_intersect_agg(anyrange)",
          "rank(any)",
          "regr_avgx(float8,float8)", "regr_avgy(float8,float8)", "regr_count(float8,float8)",
          "regr_intercept(float8,float8)", "regr_r2(float8,float8)", "regr_slope(float8,float8)",
          "regr_sxx(float8,float8)", "regr_sxy(float8,float8)", "regr_syy(float8,float8)",
          "stddev(float4)", "stddev(float8)", "stddev(int2)", "stddev(int4)", "stddev(int8)", "stddev(numeric)",
          "stddev_pop(float4)", "stddev_pop(float8)", "stddev_pop(int2)", "stddev_pop(int4)",
          "stddev_pop(int8)", "stddev_pop(numeric)",
          "stddev_samp(float4)", "stddev_samp(float8)", "stddev_samp(int2)", "stddev_samp(int4)",
          "stddev_samp(int8)", "stddev_samp(numeric)",
          "string_agg(bytea,bytea)", "string_agg(text,text)",
          "sum(float4)", "sum(float8)", "sum(int2)", "sum(int4)", "sum(int8)", "sum(interval)",
          "sum(money)", "sum(numeric)",
          "var_pop(float4)", "var_pop(float8)", "var_pop(int2)", "var_pop(int4)", "var_pop(int8)", "var_pop(numeric)",
          "var_samp(float4)", "var_samp(float8)", "var_samp(int2)", "var_samp(int4)", "var_samp(int8)",
          "var_samp(numeric)",
          "variance(float4)", "variance(float8)", "variance(int2)", "variance(int4)", "variance(int8)",
          "variance(numeric)",
          "xmlagg(xml)",
        )
        val versionGatedAdditions = setOf("max(bytea)", "max(record)", "min(bytea)", "min(record)")
        val expectedSignatures = knownAggregateSnapshot + actualSignatures.intersect(versionGatedAdditions)
        assertThat(actualSignatures).isEqualTo(expectedSignatures)
      }
    }

    @Test
    fun `neverNullForNonNullInput OIDs are loaded and exclude JSON path operators`() {
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val safeListed = catalogLoader.neverNullForNonNullInputOids
        assertThat(safeListed.isNotEmpty()).isTrue()

        // "->" and "->>" (JSON path extraction) are STRICT but not TOTAL — they return null on a
        // missing key/path even though every input is non-null — so they must be absent from the
        // safe list despite satisfying isStrict.
        val jsonPathOperatorOids = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT o.oprcode::integer AS oid FROM pg_catalog.pg_operator o
            JOIN pg_catalog.pg_namespace n ON n.oid = o.oprnamespace
            WHERE n.nspname = 'pg_catalog' AND o.oprname IN ('->', '->>')
            """.trimIndent(),
          ).use { rs ->
            buildSet { while (rs.next()) add(rs.getInt("oid")) }
          }
        }
        assertThat(jsonPathOperatorOids.isNotEmpty()).isTrue()
        assertThat(safeListed.intersect(jsonPathOperatorOids)).hasSize(0)
      }
    }

    @Test
    fun `pg_operator rows for safe-listed symbols match a known snapshot`() {
      // Enumerates every pg_catalog pg_operator row matching a symbol that appears in
      // NEVER_NULL_OPERATOR_SIGNATURES (not just the safe-listed signatures — every overload of
      // that symbol, regardless of operand types), grouped by symbol, as a count. A PostgreSQL
      // version bump that adds or removes an overload of one of these symbols changes a count
      // here, forcing a human to look at the new overload and decide whether it is total before
      // adding its (symbol, left type, right type) triple to NEVER_NULL_OPERATOR_SIGNATURES —
      // exactly the gap a symbol-only safe-list left open for `path + path`.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val symbols = NeverNullSafeLists.NEVER_NULL_OPERATOR_SIGNATURES.map { it.symbol }.toSortedSet()
          .joinToString(", ") { "'$it'" }
        val counts = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT o.oprname, count(*) AS operator_count FROM pg_catalog.pg_operator o
            JOIN pg_catalog.pg_namespace n ON n.oid = o.oprnamespace
            WHERE n.nspname = 'pg_catalog' AND o.oprname IN ($symbols)
            GROUP BY o.oprname
            """.trimIndent(),
          ).use { rs ->
            buildMap { while (rs.next()) put(rs.getString("oprname"), rs.getInt("operator_count")) }
          }
        }
        assertThat(counts).isEqualTo(
          mapOf(
            "!~" to 3, "!~*" to 3, "!~~" to 4, "!~~*" to 3,
            "%" to 4, "&&" to 10, "*" to 32, "+" to 50, "-" to 47, "/" to 25,
            "<" to 58, "<=" to 58, "<>" to 59, "<@" to 20, "=" to 63,
            ">" to 58, ">=" to 58, "@>" to 17, "||" to 11,
            "~" to 10, "~*" to 3, "~~" to 4, "~~*" to 3,
          ),
        )
      }
    }

    @Test
    fun `pg_proc rows for safe-listed function names match a known snapshot`() {
      // Enumerates EVERY pg_catalog overload for every function NAME appearing in
      // NEVER_NULL_FUNCTION_SIGNATURES — not just the safe-listed signatures — so a PostgreSQL
      // version bump that adds a NEW overload of one of these names (exactly how the
      // anyrange/anymultirange overloads of lower/upper slipped in, silently widening a
      // name-keyed safe list to an untotal overload) fails loudly here, forcing a human to look
      // at the new overload and decide whether it is total before adding it to
      // NEVER_NULL_FUNCTION_SIGNATURES.
      //
      // KNOWN_SIGNATURE_SNAPSHOT below includes `reverse(bytea)`, which only exists starting in
      // PostgreSQL 18 — see the identically-shaped `pg_cast rows for safe-listed source types`
      // test's KDoc immediately below for why comparing the live server directly against a fixed
      // snapshot containing a version-18-only entry would fail on 16/17, and why splitting the
      // expectation into a safe-listed portion (derived from what actually resolves HERE) and a
      // non-safe-listed portion (pinned to the fixed snapshot, since `lower`/`upper`'s
      // anyrange/anymultirange overloads and `trunc`'s macaddr/macaddr8 overloads are not
      // version-sensitive across 16/17/18) keeps this an exact equality without reintroducing that
      // failure.
      //
      // Deriving the safe-listed portion of the expectation from `actualSignatures` itself (rather
      // than from the safe list directly) has one absorbed gap: a safe-listed signature
      // DISAPPEARING from the live server — a real regression, not a version difference — would
      // vanish from `actualSignatures.intersect(safeListedSignatures)` on the actual side AND from
      // the same computation on the expected side, so the two stay equal and the test passes
      // silently. `versionGatedSafeListedSignatures` below closes that gap for every safe-listed
      // signature except the one entry actually known to be version-gated (`reverse(bytea)`,
      // PostgreSQL 18+): every other safe-listed signature is asserted present in
      // `actualSignatures` directly, so its disappearance is a loud failure here, not an absorbed
      // one. A future safe-listed signature that becomes version-gated on some future PostgreSQL
      // release would need adding to `versionGatedSafeListedSignatures` by hand, exactly the way
      // `reverse(bytea)` already is — this does not reintroduce the original brittleness (a fixed
      // snapshot compared directly against the live server), since every OTHER signature's
      // presence is still checked live, not against a fixed count.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val names = NeverNullSafeLists.NEVER_NULL_FUNCTION_SIGNATURES.map { it.name }.toSortedSet()
        val namesLiteral = names.joinToString(", ") { "'$it'" }
        val actualSignatures = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.proname,
              COALESCE(
                (SELECT array_agg(t.typname::text ORDER BY u.ordinality)
                 FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
                 JOIN pg_catalog.pg_type t ON t.oid = u.type_oid),
                ARRAY[]::text[]
              ) AS argtypes
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'pg_catalog' AND p.proname IN ($namesLiteral)
            """.trimIndent(),
          ).use { rs ->
            buildSet {
              while (rs.next()) {
                @Suppress("UNCHECKED_CAST")
                val argumentTypes = (rs.getArray("argtypes").array as Array<String>).toList()
                add("${rs.getString("proname")}(${argumentTypes.joinToString(",")})")
              }
            }
          }
        }
        val knownSignatureSnapshot = setOf(
          "abs(float4)", "abs(float8)", "abs(int2)", "abs(int4)", "abs(int8)", "abs(numeric)",
          "array_to_string(anyarray,text)", "array_to_string(anyarray,text,text)",
          "ascii(text)",
          "btrim(bytea,bytea)", "btrim(text)", "btrim(text,text)",
          "cardinality(anyarray)",
          "ceil(float8)", "ceil(numeric)",
          "ceiling(float8)", "ceiling(numeric)",
          "char_length(bpchar)", "char_length(text)",
          "chr(int4)",
          "date_part(text,date)", "date_part(text,interval)", "date_part(text,time)",
          "date_part(text,timestamp)", "date_part(text,timestamptz)", "date_part(text,timetz)",
          "date_trunc(text,interval)", "date_trunc(text,timestamp)", "date_trunc(text,timestamptz)",
          "date_trunc(text,timestamptz,text)",
          "decode(text,text)",
          "div(numeric,numeric)",
          "encode(bytea,text)",
          "extract(text,date)", "extract(text,interval)", "extract(text,time)",
          "extract(text,timestamp)", "extract(text,timestamptz)", "extract(text,timetz)",
          "floor(float8)", "floor(numeric)",
          "initcap(text)",
          "left(text,int4)",
          "length(bit)", "length(bpchar)", "length(bytea)", "length(bytea,name)", "length(lseg)",
          "length(path)", "length(text)", "length(tsvector)",
          "lower(anymultirange)", "lower(anyrange)", "lower(text)",
          "lpad(text,int4)", "lpad(text,int4,text)",
          "ltrim(bytea,bytea)", "ltrim(text)", "ltrim(text,text)",
          "md5(bytea)", "md5(text)",
          "mod(int2,int2)", "mod(int4,int4)", "mod(int8,int8)", "mod(numeric,numeric)",
          "now()",
          "ntile(int4)",
          "octet_length(bit)", "octet_length(bpchar)", "octet_length(bytea)", "octet_length(text)",
          "position(bit,bit)", "position(bytea,bytea)", "position(text,text)",
          "power(float8,float8)", "power(numeric,numeric)",
          "repeat(text,int4)",
          "replace(text,text,text)",
          "reverse(bytea)", "reverse(text)",
          "right(text,int4)",
          "round(float8)", "round(numeric)", "round(numeric,int4)",
          "rpad(text,int4)", "rpad(text,int4,text)",
          "rtrim(bytea,bytea)", "rtrim(text)", "rtrim(text,text)",
          "sign(float8)", "sign(numeric)",
          "split_part(text,text,int4)",
          "sqrt(float8)", "sqrt(numeric)",
          "strpos(text,text)",
          "substr(bytea,int4)", "substr(bytea,int4,int4)", "substr(text,int4)", "substr(text,int4,int4)",
          "translate(text,text,text)",
          "trunc(float8)", "trunc(macaddr)", "trunc(macaddr8)", "trunc(numeric)", "trunc(numeric,int4)",
          "upper(anymultirange)", "upper(anyrange)", "upper(text)",
        )
        val safeListedSignatures = NeverNullSafeLists.NEVER_NULL_FUNCTION_SIGNATURES
          .map { "${it.name}(${it.argumentTypeNames.joinToString(",")})" }
          .toSet()
        val knownNonSafeListedSignatures = knownSignatureSnapshot - safeListedSignatures
        // Every safe-listed signature other than the one known version-gated entry must actually
        // resolve here — see this test's KDoc for why this guard exists.
        val versionGatedSafeListedSignatures = setOf("reverse(bytea)")
        val alwaysExpectedSafeListedSignatures = safeListedSignatures - versionGatedSafeListedSignatures
        assertThat(actualSignatures).containsAtLeast(*alwaysExpectedSafeListedSignatures.toTypedArray())
        val expectedSignatures = knownNonSafeListedSignatures + actualSignatures.intersect(safeListedSignatures)
        assertThat(actualSignatures).isEqualTo(expectedSignatures)
      }
    }

    @Test
    fun `pg_cast rows for safe-listed source types match a known snapshot`() {
      // Enumerates EVERY castfunc-backed pg_catalog cast FROM any source type appearing in
      // NEVER_NULL_CAST_SIGNATURES — not just the safe-listed (source, target) pairs — so a
      // PostgreSQL version bump that adds a NEW castfunc-backed target for one of these source
      // types fails loudly here, forcing a human to look at the new cast and decide whether it is
      // total before adding it to NEVER_NULL_CAST_SIGNATURES. This is the cast analogue of the
      // `lower`/`upper` overload-drift risk the function and operator snapshot tests above guard
      // against: the previous blanket rule ("every pg_catalog castfunc is safe") could never have
      // been caught by a test like this, since it had no explicit pair list to diff against.
      //
      // KNOWN_CAST_PAIR_SNAPSHOT below was captured against PostgreSQL 18, which is a STRICT
      // SUPERSET of 16 and 17 for these source types: PostgreSQL 18 added six castfunc-backed
      // int2/int4/int8 <-> bytea casts (all six safe-listed) that simply do not exist in pg_cast
      // on 16/17. Asserting the live server's actual pairs equal that fixed snapshot directly (as
      // a prior version of this test did) therefore fails on 16/17 even though nothing about those
      // six pairs is wrong there — they just do not resolve on THIS connected server, exactly the
      // way PgCatalogLoader.loadNeverNullForNonNullInputOids's live catalog lookup finds no row
      // for them either.
      //
      // The fix keeps the assertion an exact equality (never weakened to a subset check) by
      // splitting expectedPairs into two halves: the portion of KNOWN_CAST_PAIR_SNAPSHOT that is
      // NOT itself safe-listed (reg*/oid/char/xml/name — pairs this test enumerates for visibility
      // but NEVER_NULL_CAST_SIGNATURES has no opinion on) stays pinned to the fixed snapshot, since
      // that portion is not version-sensitive across 16/17/18 (verified directly against all
      // three). The safe-listed portion is instead derived from THIS live server — whichever of
      // NEVER_NULL_CAST_SIGNATURES's pairs actually appear in actualPairs — so it silently
      // shrinks on a server where a safe-listed pair does not yet resolve, without ever silently
      // growing: a genuinely NEW overload for one of these source types still shows up in
      // actualPairs but not in expectedPairs (it's neither a known non-safe-listed extra nor a
      // safe-listed pair with a matching row in actualPairs) unless a human adds it to one of the
      // two sets, so drift is still caught exactly the way it was before this fix.
      //
      // Deriving the safe-listed portion of expectedPairs from actualPairs itself has one absorbed
      // gap, the cast analogue of the one described in `pg_proc rows for safe-listed function
      // names match a known snapshot`'s KDoc above: a safe-listed pair DISAPPEARING from the live
      // server for a real (non-version) reason would vanish from both sides of the comparison and
      // pass silently. `versionGatedSafeListedPairs` below closes that gap for every safe-listed
      // pair except the six actually known to be version-gated (the int2/int4/int8 <-> bytea
      // pairs, PostgreSQL 18+, named just above): every other safe-listed pair is asserted present
      // in `actualPairs` directly, so its disappearance is a loud failure here, not an absorbed
      // one.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val sourceTypes = NeverNullSafeLists.NEVER_NULL_CAST_SIGNATURES.map { it.sourceTypeName }.toSortedSet()
        val sourceTypesLiteral = sourceTypes.joinToString(", ") { "'$it'" }
        val actualPairs = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT st.typname AS source_type, tt.typname AS target_type
            FROM pg_catalog.pg_cast c
            JOIN pg_catalog.pg_type st ON st.oid = c.castsource
            JOIN pg_catalog.pg_type tt ON tt.oid = c.casttarget
            JOIN pg_catalog.pg_proc p ON p.oid = c.castfunc
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE c.castfunc != 0 AND n.nspname = 'pg_catalog' AND st.typname IN ($sourceTypesLiteral)
            """.trimIndent(),
          ).use { rs ->
            buildSet {
              while (rs.next()) {
                add("${rs.getString("source_type")}->${rs.getString("target_type")}")
              }
            }
          }
        }
        val knownCastPairSnapshot = setOf(
          "bit->bit", "bit->int4", "bit->int8",
          "bool->bpchar", "bool->int4", "bool->text", "bool->varchar",
          "bpchar->bpchar", "bpchar->char", "bpchar->name", "bpchar->text",
          "bpchar->varchar", "bpchar->xml", "bytea->int2", "bytea->int4",
          "bytea->int8", "date->timestamp", "date->timestamptz", "float4->float8",
          "float4->int2", "float4->int4", "float4->int8", "float4->numeric",
          "float8->float4", "float8->int2", "float8->int4", "float8->int8",
          "float8->numeric", "int2->bytea", "int2->float4", "int2->float8",
          "int2->int4", "int2->int8", "int2->numeric", "int2->oid",
          "int2->regclass", "int2->regcollation", "int2->regconfig", "int2->regdictionary",
          "int2->regnamespace", "int2->regoper", "int2->regoperator", "int2->regproc",
          "int2->regprocedure", "int2->regrole", "int2->regtype", "int4->bit",
          "int4->bool", "int4->bytea", "int4->char", "int4->float4",
          "int4->float8", "int4->int2", "int4->int8", "int4->money",
          "int4->numeric", "int8->bit", "int8->bytea", "int8->float4",
          "int8->float8", "int8->int2", "int8->int4", "int8->money",
          "int8->numeric", "int8->oid", "int8->regclass", "int8->regcollation",
          "int8->regconfig", "int8->regdictionary", "int8->regnamespace", "int8->regoper",
          "int8->regoperator", "int8->regproc", "int8->regprocedure", "int8->regrole",
          "int8->regtype", "interval->interval", "interval->time", "money->numeric",
          "numeric->float4", "numeric->float8", "numeric->int2", "numeric->int4",
          "numeric->int8", "numeric->money", "numeric->numeric", "time->interval",
          "time->time", "time->timetz", "timestamp->date", "timestamp->time",
          "timestamp->timestamp", "timestamp->timestamptz", "timestamptz->date", "timestamptz->time",
          "timestamptz->timestamp", "timestamptz->timestamptz", "timestamptz->timetz", "timetz->time",
          "timetz->timetz", "varchar->char", "varchar->name", "varchar->regclass",
          "varchar->varchar", "varchar->xml",
        )
        val safeListedPairs = NeverNullSafeLists.NEVER_NULL_CAST_SIGNATURES
          .map { "${it.sourceTypeName}->${it.targetTypeName}" }
          .toSet()
        val knownNonSafeListedPairs = knownCastPairSnapshot - safeListedPairs
        // Every safe-listed pair other than the six known version-gated ones must actually
        // resolve here — see this test's KDoc for why this guard exists.
        val versionGatedSafeListedPairs = setOf(
          "int2->bytea",
          "int4->bytea",
          "int8->bytea",
          "bytea->int2",
          "bytea->int4",
          "bytea->int8",
        )
        val alwaysExpectedSafeListedPairs = safeListedPairs - versionGatedSafeListedPairs
        assertThat(actualPairs).containsAtLeast(*alwaysExpectedSafeListedPairs.toTypedArray())
        val expectedPairs = knownNonSafeListedPairs + actualPairs.intersect(safeListedPairs)
        assertThat(actualPairs).isEqualTo(expectedPairs)
      }
    }

    @Test
    fun `lagLeadWithDefault OIDs equal exactly the 3-argument lag and lead window overloads`() {
      // The real exact + behavioral check for this property now lives in SafeListSweepTest's
      // `lagLeadWithDefaultOids-listed 3-argument lag and lead fill window boundaries from a
      // non-null default` test. This one is kept, rather than deleted as redundant, for
      // consistency with every sibling test in THIS class (each PgCatalogLoader-loaded property
      // gets its own small, cheap, exact-by-name OID check here) — but upgraded from a bare
      // `isNotEmpty()` to genuine exact membership, resolved by NAME from pg_catalog independently
      // of PgCatalogLoader.loadLagLeadWithDefaultOids's own `pronargs = 3` predicate, so a mutation
      // that widens or narrows that predicate (e.g. to the 2-argument overloads) is caught by the
      // resulting set INEQUALITY here too, not just in the heavier live sweep.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val expectedOids = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.oid::integer AS oid
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'pg_catalog' AND p.proname IN ('lag', 'lead') AND p.pronargs = 3 AND p.prokind = 'w'
            """.trimIndent(),
          ).use { rs -> buildSet { while (rs.next()) add(rs.getInt("oid")) } }
        }
        assertThat(expectedOids.isNotEmpty()).isTrue()
        assertThat(catalogLoader.lagLeadWithDefaultOids).isEqualTo(expectedOids)
      }
    }

    @Test
    fun `alwaysNonNull OIDs equal exactly the concat overloads, and exclude concat_ws`() {
      // concat_ws is deliberately excluded: it is non-null only when its separator (first
      // argument) is non-null, not unconditionally — see alwaysNonNullFunctionOids's KDoc and
      // nonNullIffFirstArgumentNonNullFunctionOids below. Both halves are resolved by NAME from
      // pg_catalog, restricted to that namespace for the same reason as the concat_ws sibling test
      // above: this class's tests run in PARALLEL against the same shared container, and
      // `user-defined concat and concat_ws in another schema do not ride onto either safe-list`
      // concurrently creates and drops schema-scoped `concat`/`concat_ws` functions that an
      // un-namespaced query could intermittently pick up.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val concatOids = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.oid::integer AS oid
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE p.proname = 'concat' AND n.nspname = 'pg_catalog'
            """.trimIndent(),
          ).use { rs -> buildSet { while (rs.next()) add(rs.getInt("oid")) } }
        }
        assertThat(concatOids.isNotEmpty()).isTrue()
        assertThat(catalogLoader.alwaysNonNullFunctionOids).isEqualTo(concatOids)

        val concatWsOids = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.oid::integer AS oid
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE p.proname = 'concat_ws' AND n.nspname = 'pg_catalog'
            """.trimIndent(),
          ).use { rs -> buildSet { while (rs.next()) add(rs.getInt("oid")) } }
        }
        assertThat(catalogLoader.alwaysNonNullFunctionOids.intersect(concatWsOids)).isEqualTo(emptySet())
      }
    }

    @Test
    fun `nonNullIffFirstArgumentNonNull OIDs equal exactly the concat_ws overloads`() {
      // `isNotEmpty()` alone is the exact `size >= 2` shape that let the original concat_ws bug
      // ship: it stays green under a mutation that puts `concat` (or any other function) on this
      // list ALONGSIDE or INSTEAD OF `concat_ws`, since the set is still non-empty either way. The
      // OIDs resolved here are independent of PgCatalogLoader.loadNonNullIffFirstArgumentNonNullFunctionOids's
      // own SQL — matched by NAME alone, not by re-asserting its `NOT proisstrict` predicate — so a
      // mutation that widens or narrows the production predicate to a different name (or an
      // additional one) is caught by the resulting set INEQUALITY, not absorbed by both sides
      // moving together. Restricted to `pg_catalog` (unlike the sibling `alwaysNonNull` test's
      // intersect-only check, which does not need it): tests in this class run in PARALLEL, each on
      // its own connection but against the SAME shared container, and `user-defined concat and
      // concat_ws in another schema do not ride onto either safe-list` concurrently creates and
      // drops a schema-scoped `concat_ws` — an un-namespaced query here would intermittently pick up
      // that shadow OID too and fail this exact-equality assertion on a false positive.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val catalogLoader = PgCatalogLoader(connection)
        val concatWsOids = connection.createStatement().use { stmt ->
          stmt.executeQuery(
            """
            SELECT p.oid::integer AS oid
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE p.proname = 'concat_ws' AND n.nspname = 'pg_catalog'
            """.trimIndent(),
          ).use { rs -> buildSet { while (rs.next()) add(rs.getInt("oid")) } }
        }
        assertThat(concatWsOids.isNotEmpty()).isTrue()
        assertThat(catalogLoader.nonNullIffFirstArgumentNonNullFunctionOids).isEqualTo(concatWsOids)
      }
    }

    @Test
    fun `user-defined concat and concat_ws in another schema do not ride onto either safe-list`() {
      // Verified live: without a pronamespace restriction, a user-defined function sharing the
      // name `concat`/`concat_ws` (even with completely different, always-NULL behavior) gets
      // picked up by these lists' `proname`-only predicate and would be wrongly trusted as
      // non-null.
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        connection.createStatement().use { stmt ->
          stmt.execute("CREATE SCHEMA $schemaName")
          stmt.execute(
            """
            CREATE FUNCTION $schemaName.concat(text, text) RETURNS text AS ${'$'}fn${'$'}
              SELECT NULL::text
            ${'$'}fn${'$'} LANGUAGE sql CALLED ON NULL INPUT;
            CREATE FUNCTION $schemaName.concat_ws(text, text) RETURNS text AS ${'$'}fn${'$'}
              SELECT NULL::text
            ${'$'}fn${'$'} LANGUAGE sql CALLED ON NULL INPUT;
            """.trimIndent(),
          )
        }
        try {
          val shadowConcatOid = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT '$schemaName.concat(text,text)'::regprocedure::oid AS oid").use { rs ->
              rs.next()
              rs.getInt("oid")
            }
          }
          val shadowConcatWsOid = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT '$schemaName.concat_ws(text,text)'::regprocedure::oid AS oid").use { rs ->
              rs.next()
              rs.getInt("oid")
            }
          }
          val catalogLoader = PgCatalogLoader(connection)
          assertThat(catalogLoader.alwaysNonNullFunctionOids.contains(shadowConcatOid)).isFalse()
          assertThat(catalogLoader.nonNullIffFirstArgumentNonNullFunctionOids.contains(shadowConcatWsOid)).isFalse()
        } finally {
          connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
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
  inner class WhereClauseNarrowing {

    private val schema = "CREATE TABLE t (id BIGINT PRIMARY KEY, a TEXT, b TEXT, flag BOOLEAN)"

    // A DML-to-SELECT conversion (see PgCatalogLoader.transformForViewCreation /
    // SqlUtils.convertDmlToSelect) drops the SET clause but keeps the original WHERE predicate.
    // A qual that looks like it proves a RETURNING column non-null may really be testing a value
    // the statement is about to overwrite, so qual narrowing must be suppressed entirely whenever
    // the analyzed SQL passed through that conversion.
    private val dmlSchema = """
      CREATE TABLE t (id INT NOT NULL, a TEXT);
      CREATE TABLE u (id INT NOT NULL, val TEXT)
    """.trimIndent()

    @Test
    fun `WHERE a IS NOT NULL AND b IS NOT NULL narrows both columns`() {
      val query = analyzeWithSchema(schema, "SELECT a, b FROM t WHERE a IS NOT NULL AND b IS NOT NULL")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isTrue()
      assertThat(query.columns[1].notNull).isTrue()
    }

    @Test
    fun `WHERE with strict equality operator narrows`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE a = 'x'")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `WHERE narrows through a strict function chain`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE upper(a) = 'X'")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `bare boolean column conjunct narrows itself`() {
      val query = analyzeWithSchema(schema, "SELECT flag FROM t WHERE flag")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `WHERE a = ANY narrows the scalar operand`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE a = ANY(ARRAY['x'])")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `subquery RTE narrowed by its own WHERE clause`() {
      val query = analyzeWithSchema(schema, "SELECT x FROM (SELECT a AS x FROM t WHERE a IS NOT NULL) s")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `CTE narrowed by its own WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        "WITH c AS (SELECT a FROM t WHERE a IS NOT NULL) SELECT a FROM c",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `narrowing survives an aggregate-free GROUP BY`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE a IS NOT NULL GROUP BY a")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isTrue()
    }

    @Test
    fun `OR does not narrow either side`() {
      val query = analyzeWithSchema(schema, "SELECT a, b FROM t WHERE a IS NOT NULL OR b IS NOT NULL")
      assertThat(query.columns).hasSize(2)
      assertThat(query.columns[0].notNull).isFalse()
      assertThat(query.columns[1].notNull).isFalse()
    }

    @Test
    fun `NOT does not narrow`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE NOT (a IS NULL)")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `IS NULL does not narrow`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE a IS NULL")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `coalesce does not narrow its argument`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE coalesce(a, 'x') = 'y'")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `greatest (MinMaxExpr) does not narrow its argument`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE greatest(a, 'x') = 'y'")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `IS DISTINCT FROM does not narrow`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE a IS DISTINCT FROM 'x'")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `ALL's scalar operand is not proven`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t WHERE a <> ALL(ARRAY['x'])")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `join ON clause does not narrow the nullable side`() {
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id BIGINT PRIMARY KEY);
        CREATE TABLE u (id BIGINT PRIMARY KEY, val TEXT)
        """.trimIndent(),
        "SELECT u.val FROM t LEFT JOIN u ON u.val IS NOT NULL",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `outer join nullability wins over a proving WHERE clause`() {
      // Deliberately conservative: u.val is proven non-null by WHERE for surviving rows, but the
      // LEFT JOIN can still null-extend the whole row (including u.val) before WHERE is applied to
      // rows where the join found no match — the outer-join veto in isNonNull's Var branch always
      // wins over WHERE-clause narrowing.
      val query = analyzeWithSchema(
        """
        CREATE TABLE t (id BIGINT PRIMARY KEY);
        CREATE TABLE u (id BIGINT PRIMARY KEY, val TEXT)
        """.trimIndent(),
        "SELECT u.val FROM t LEFT JOIN u ON u.id = t.id WHERE u.val IS NOT NULL",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `HAVING does not narrow`() {
      val query = analyzeWithSchema(schema, "SELECT a FROM t GROUP BY a HAVING a IS NOT NULL")
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `GROUPING SETS grouping key stays nullable despite a proving WHERE clause`() {
      // The `()` grouping set emits a null row for `a` regardless of what WHERE proved about the
      // rows that fed the aggregation — this is the regression test for the single worst failure
      // mode of this change.
      //
      // This test and the eight further down this class (see the comment above the first of
      // those eight) pin the same thing: a GROUPING SETS/ROLLUP grouping key stays nullable even
      // under a WHERE clause that proves the underlying column non-null.
      val query = analyzeWithSchema(
        schema,
        "SELECT a FROM t WHERE a IS NOT NULL GROUP BY GROUPING SETS ((a), ())",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `LATERAL outer reference does not collide with a local varno-varattno pair`() {
      // o.marker is an outer reference inside the LATERAL subquery: varno 1, varattno 3,
      // varlevelsup 1. inner_t.other is locally varno 1, varattno 3 in that same subquery block.
      // Without the levelsUp filter, the outer reference would be misread as proving
      // inner_t.other non-null.
      val query = analyzeWithSchema(
        """
        CREATE TABLE outer_t (id BIGINT, ref TEXT, marker TEXT);
        CREATE TABLE inner_t (id BIGINT, val TEXT, other TEXT)
        """.trimIndent(),
        "SELECT s.other FROM outer_t o, LATERAL (SELECT i.other FROM inner_t i WHERE i.val = o.marker) s",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `UPDATE FROM RETURNING does not narrow a column WHERE tests but SET does not touch`() {
      val query = analyzeWithSchema(
        dmlSchema,
        "UPDATE t SET a = u.val FROM u WHERE u.id = t.id AND t.a = 'x' RETURNING t.a",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `UPDATE FROM RETURNING does not narrow a column SET is about to null out`() {
      val query = analyzeWithSchema(
        dmlSchema,
        "UPDATE t SET a = NULL FROM u WHERE u.id = t.id AND t.a IS NOT NULL RETURNING t.a",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `UPDATE FROM RETURNING an expression over a SET-nulled column does not narrow`() {
      val query = analyzeWithSchema(
        dmlSchema,
        "UPDATE t SET a = NULL FROM u WHERE u.id = t.id AND t.a IS NOT NULL RETURNING lower(t.a)",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `WITH modifying CTE RETURNING does not narrow a column SET is about to null out`() {
      val query = analyzeWithSchema(
        dmlSchema,
        """
        WITH c AS (
          UPDATE t SET a = NULL FROM u WHERE u.id = t.id AND t.a IS NOT NULL RETURNING t.a
        )
        SELECT a FROM c
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    // This test and the eight below it (including "GROUPING SETS grouping key stays nullable
    // despite a proving WHERE clause" earlier in this class) pin the `hasGroupingSets` early
    // return in NodeTreeNullabilityAnalyzer.isEffectivelyNonNull: removing it makes exactly these
    // nine fail, and no other test in this class — confirmed empirically on PostgreSQL 16 and 18.
    // That is the reason to keep them.
    //
    // The three `!hasGroupingSets` qual-narrowing suppressions in ColumnNullabilityAnalyzer (lines
    // 293, 664, 752) that these tests were originally named for cannot be exercised by any query.
    // Verified live: under GROUPING SETS, PostgreSQL rejects the primary-key functional-dependency
    // shortcut that plain GROUP BY allows, so every non-aggregated column in a grouping-sets
    // target list must be, or textually match, the grouping key — forcing it nullable via
    // `isEffectivelyNonNull`'s `isGroupingKey` check before `isNonNull`, and therefore before qual
    // narrowing, is ever reached. This holds identically at the top-level, CTE-body, and
    // subquery-RTE sites, because `isSafeFromGroupingSetNullExtension` keys on `PgNodeExpression`
    // shape, never on what a `Var`'s `varno` resolves to. Both halves of the suppression (qual
    // narrowing and `groupRteMap = emptyMap()`) are dead for the same reason.
    //
    // The one thing that would revive the suppression: extending `Aggref`/`GroupingFunc`
    // evaluation in `isNonNull` to consult its own arguments' nullability (e.g. a future MIN/MAX
    // non-empty-source heuristic) without correspondingly narrowing
    // `isSafeFromGroupingSetNullExtension`'s blanket-safe treatment of those node types. These nine
    // tests stay in place as scaffolding for that: if `isEffectivelyNonNull`'s `hasGroupingSets`
    // early return is ever narrowed or removed, at least these nine will fail immediately.

    @Test
    fun `subquery ROLLUP grouping key stays nullable despite a proving WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT x FROM (SELECT a AS x FROM t WHERE a IS NOT NULL GROUP BY ROLLUP(a)) s",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `subquery GROUPING SETS grouping key stays nullable despite a proving WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT x FROM (SELECT a AS x FROM t WHERE a IS NOT NULL GROUP BY GROUPING SETS ((a), ())) s",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CTE ROLLUP grouping key stays nullable despite a proving WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        "WITH c AS (SELECT a FROM t WHERE a IS NOT NULL GROUP BY ROLLUP(a)) SELECT a FROM c",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CTE GROUPING SETS grouping key stays nullable despite a proving WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        "WITH c AS (SELECT a FROM t WHERE a IS NOT NULL GROUP BY GROUPING SETS ((a), ())) SELECT a FROM c",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    // These four EXPRESSION-grouping-key tests pin the same thing as the four bare-Var tests
    // above (see the comment there for the full explanation). Here the grouping key is an
    // EXPRESSION (e.g. `lower(a)`) rather than a bare column, but the target-list entry for
    // `lower(a)` structurally IS the grouping key, so it gets the ROLLUP's own `:ressortgroupref`
    // and is forced nullable by `isGroupingKey` before `isNonNull` is ever reached — exactly as
    // for the bare-Var cluster.

    @Test
    fun `expression ROLLUP grouping key stays nullable despite a proving WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT lower(a) FROM t WHERE a IS NOT NULL GROUP BY ROLLUP(lower(a))",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `concatenation ROLLUP grouping key stays nullable despite a proving WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        "SELECT a || 'z' FROM t WHERE a IS NOT NULL GROUP BY ROLLUP(a || 'z')",
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `subquery expression ROLLUP grouping key stays nullable despite a proving WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        """
        SELECT y FROM (
          SELECT lower(a) AS y FROM t WHERE a IS NOT NULL GROUP BY ROLLUP(lower(a))
        ) s
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
    }

    @Test
    fun `CTE expression ROLLUP grouping key stays nullable despite a proving WHERE clause`() {
      val query = analyzeWithSchema(
        schema,
        """
        WITH c AS (
          SELECT lower(a) AS y FROM t WHERE a IS NOT NULL GROUP BY ROLLUP(lower(a))
        )
        SELECT y FROM c
        """.trimIndent(),
      )
      assertThat(query.columns).hasSize(1)
      assertThat(query.columns[0].notNull).isFalse()
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
    fun `parseWhereQuals returns null for malformed input`() {
      val parser = PgNodeTreeParser()
      assertThat(parser.parseWhereQuals("malformed")).isEqualTo(null)
    }

    @Test
    fun `parseWhereQuals returns null when quals is empty`() {
      val parser = PgNodeTreeParser()
      val nodeTree = "{QUERY :jointree {FROMEXPR :fromlist ({RANGETBLREF :rtindex 1}) :quals <>}}"
      assertThat(parser.parseWhereQuals(nodeTree)).isEqualTo(null)
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

    @Test
    fun `a strict, never-null-for-non-null-input function is not trusted for a VARIADIC call`() {
      // Guards the exact failure mode that produced the concat/concat_ws P0s: a curated
      // safe-list's guarantee, verified only for the ordinary calling convention, silently
      // applied to VARIADIC too. No real function on NEVER_NULL_FUNCTION_SIGNATURES resolves to
      // a provariadic <> 0 function on PostgreSQL 16, 17, or 18 (verified live), so this stubs
      // isStrict/isNeverNullForNonNullInput to simulate one being added in variadic form — the
      // only way to reach this path without editing the safe-list itself. The ArrayExpr argument
      // contains a NULL element; isNonNull's own ArrayExpr branch is unconditionally true
      // regardless of that (an array container is never NULL merely because an element is),
      // so if this leg were reached the NULL element would be invisible.
      val analyzer = NodeTreeNullabilityAnalyzer(
        isStrict = { true },
        hasNonNullInitialValue = { false },
        isSourceColumnNotNull = { _, _ -> true },
        isOuterJoinNullable = { false },
        isNeverNullForNonNullInput = { true },
      )
      val variadicCall = PgNodeExpression.FuncExpr(
        functionOid = 1,
        arguments = listOf(
          PgNodeExpression.ArrayExpr(
            elements = listOf(PgNodeExpression.Const(isNull = false), PgNodeExpression.Const(isNull = true)),
          ),
        ),
        isVariadic = true,
      )
      assertThat(analyzer.isNonNull(variadicCall)).isFalse()
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

  /**
   * Ground truth for [PgCatalogLoader.aggregateHasNonNullInitialValue]: calls the aggregate named
   * [aggregateName], with arguments typed [argumentTypeNames], over a genuinely EMPTY input (`...
   * WHERE false`, not merely an aggregate over no matching group), and reports whether the result
   * is `null`. `count` with zero declared arguments is `count(*)` — the only ordinary-call-syntax
   * exception PostgreSQL's aggregate grammar needs here, mirroring
   * [SafeListSweepTest.functionCallExpression]'s `ntile`/`extract`/`position` special-casing for
   * the same reason: `count()` alone is a syntax error.
   *
   * A declared `any` argument type (e.g. `count`'s one-argument overload, `count(any)`) is the
   * PSEUDO-type PostgreSQL uses to mean "accepts a value of any concrete type" — it has no literal
   * form of its own, so `NULL::any` is a syntax error the same way `NULL::anyelement` would be.
   * Instantiated here to a concrete `int4` column instead, the same way
   * [SafeListSweepTest.concreteInstantiationsFor] instantiates `anyelement` to `int4` for the same
   * reason.
   */
  private fun aggregateOverEmptyInputIsNull(
    stmt: Statement,
    aggregateName: String,
    argumentTypeNames: List<String>,
  ): Boolean {
    val concreteArgumentTypeNames = argumentTypeNames.map { typeName -> if (typeName == "any") "int4" else typeName }
    val columnAliases = concreteArgumentTypeNames.indices.map { index -> "c$index" }
    val emptySourceColumns = if (concreteArgumentTypeNames.isEmpty()) {
      "1"
    } else {
      concreteArgumentTypeNames.zip(columnAliases).joinToString(", ") { (typeName, alias) ->
        "NULL::$typeName AS $alias"
      }
    }
    val aggregateCall = if (aggregateName == "count" && argumentTypeNames.isEmpty()) {
      "count(*)"
    } else {
      "$aggregateName(${columnAliases.joinToString(", ")})"
    }
    val sql = "SELECT ($aggregateCall) IS NULL FROM (SELECT $emptySourceColumns WHERE false) empty_input"
    return stmt.executeQuery(sql).use { rs ->
      rs.next()
      rs.getBoolean(1)
    }
  }

  companion object {
    private val schemaCounter = AtomicInteger(0)

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_test", inMemory = true)
  }
}
