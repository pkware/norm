package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live-database pins for [resolveNodeTreeProvenanceExpression] — Route D's text-extraction and
 * cross-validation half, given a [NodeTreeColumnProvenance] [NodeTreeProvenanceResolver] already
 * resolved (see [NodeTreeProvenanceResolverTest] for that half).
 *
 * Ports the shape corpus from the deleted `SqlCteOutputExpressionTest` (the retired text-only
 * whitelist's own live-verified test suite): a shape the whitelist resolved must resolve to the
 * SAME string here; a shape it declined that Route D now proves correct gets its expectation
 * updated (each such update is called out below, and was independently confirmed against
 * `verify-pg18` before being written); a shape that must stay `null` (an ambiguous body, an
 * unverifiable auto-generated alias, a niladic keyword reference, ...) still does. A handful of the
 * original shapes described SQL PostgreSQL itself rejects outright (an alias that is a string
 * literal, a second bare word trailing a completed alias, a DML statement targeting a CTE's own
 * name as if it were a real relation, an unquoted qualifier that cannot address a differently-cased
 * quoted CTE name, ...) — those can never reach this function from a query Norm's own JDBC analysis
 * step would have accepted in the first place, so they are simply dropped rather than ported; no
 * `@Test` for any of them exists anywhere in this file.
 */
@Testcontainers
class NodeTreeProvenanceExpressionTest {

  @Nested
  inner class BasicSingleCteResolution {

    @Test
    fun `an unqualified reference into a CTE-wrapped DML RETURNING expression resolves`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH deleted_parent AS (
          DELETE FROM parent WHERE id = 1 RETURNING id, name, UPPER(description) AS description_upper
        )
        SELECT id, name, description_upper FROM deleted_parent
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 2)).isEqualTo("UPPER(description)")
    }

    @Test
    fun `a qualified reference to the single CTE resolves`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH deleted_parent AS (
          DELETE FROM parent WHERE id = 1 RETURNING id, name, UPPER(description) AS description_upper
        )
        SELECT id, name, deleted_parent.description_upper FROM deleted_parent
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 2)).isEqualTo("UPPER(description)")
    }

    @Test
    fun `an unqualified reference to the single CTE resolves`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux FROM parent)
        SELECT ux FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(name)")
    }

    @Test
    fun `a CTE body whose own nested WITH precedes the RETURNING or SELECT resolves correctly`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH deleted_parent AS (
          WITH inner_cte AS (SELECT 1 AS ignored)
          SELECT UPPER(description) AS description_upper FROM parent
        )
        SELECT description_upper FROM deleted_parent
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(description)")
    }

    @Test
    fun `a CTE body wrapped in redundant parentheses still resolves`() {
      // #238: `parseOutputItemsWithAlias` previously found no top-level SELECT once the sliced
      // body text started with an extra, unmatched "(", since that put the whole rest of the text
      // at paren depth ONE.
      val ddl = "CREATE TABLE parent (id INT, name TEXT)"
      val sql = """
        WITH a AS (
          (SELECT id, UPPER(name) AS ux FROM parent)
        )
        SELECT ux FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(name)")
    }

    @Test
    fun `no WITH clause at all resolves to nothing`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = "SELECT UPPER(description) AS description_upper FROM parent"

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }
  }

  @Nested
  inner class DataModifyingMainQueries {

    @Test
    fun `MERGE RETURNING resolves a CTE source column's expression`() {
      // #238: MERGE ... RETURNING is PostgreSQL 17+, so this shape has no test-scenarios coverage
      // (scenarios have no version gate) -- this test is that coverage instead, end to end from a
      // MERGE main query through to the extracted expression text.
      assumeTrue(pgVersion.substringBefore('.').toInt() >= 17, "MERGE RETURNING requires PostgreSQL 17+")
      val ddl = """
        CREATE TABLE parent (id INT, description TEXT);
        CREATE TABLE child (parent_id INT, name TEXT)
      """.trimIndent()
      val sql = """
        WITH desc_source AS (
          SELECT id, UPPER(description) AS description_upper FROM parent
        )
        MERGE INTO child USING desc_source ON child.parent_id = desc_source.id
        WHEN MATCHED THEN UPDATE SET name = desc_source.description_upper
        WHEN NOT MATCHED THEN INSERT (parent_id, name) VALUES (desc_source.id, desc_source.description_upper)
        RETURNING desc_source.id AS source_parent_id, desc_source.description_upper AS merged_description
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 1)).isEqualTo("UPPER(description)")
    }
  }

  @Nested
  inner class WhitelistPreconditionsRouteDNoLongerNeeds {

    // These shapes were previously null ONLY because the retired text-only whitelist required
    // "exactly one declared CTE" and "the main query's FROM is exactly that CTE's bare name" as
    // proxies for "this reference can only mean the CTE" -- proxies needed because pure text has no
    // other way to rule out a same-named schema table or an ambiguous multi-source FROM. Route D
    // needs neither proxy: it starts from the outer query's own parsed Var, which PostgreSQL has
    // ALREADY resolved unambiguously by the time the node tree exists. Each expectation below was
    // confirmed against verify-pg18 before being written.

    @Test
    fun `a second, unreferenced sibling CTE no longer blocks resolution of the one actually used`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux FROM parent), b AS (SELECT LOWER(name) AS ux FROM parent)
        SELECT ux FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(name)")
    }

    @Test
    fun `the CTE named alongside another comma-separated FROM source still resolves`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT); CREATE TABLE other_table (dummy INT)"
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux FROM parent)
        SELECT ux FROM a, other_table
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(name)")
    }

    @Test
    fun `a CTE declared with an explicit column list still resolves through the body's own resname`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a(id, name, description_upper) AS (
          DELETE FROM parent WHERE id = 1 RETURNING id, name, UPPER(description) AS description_upper
        )
        SELECT id, name, description_upper FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 2)).isEqualTo("UPPER(description)")
    }

    @Test
    fun `a body item's implicit (no-AS) alias now resolves, via findTrailingImplicitAlias`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (SELECT LOWER(name) ux FROM parent)
        SELECT ux FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("LOWER(name)")
    }

    @Test
    fun `a syntactically WITH RECURSIVE CTE that never actually self-references resolves normally`() {
      // Verified live: PostgreSQL's own ":cterecursive" flag is false here -- a CTE declared under
      // WITH RECURSIVE with NO self-reference in its body genuinely runs once, exactly like an
      // ordinary CTE, and PostgreSQL itself knows that. The retired whitelist bailed on the SQL
      // TEXT alone ("WITH RECURSIVE" present anywhere), overly conservative for this shape; Route D
      // reads the authoritative flag instead.
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH RECURSIVE a AS (
          SELECT UPPER(description) AS description_upper FROM parent WHERE id = 1
        )
        SELECT description_upper FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(description)")
    }

    @Test
    fun `an escaped-quote body alias no longer blocks a later item's own implicit alias from resolving`() {
      // The exact #229 P0 repro shape: item 1 has an escaped-quote explicit alias "zz\"q", item 2 an
      // IMPLICIT alias "zz". The retired whitelist could not support an implicit alias at all, so it
      // punted on this whole body once it saw one -- Route D's findTrailingImplicitAlias resolves it.
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "zz""q", LOWER(b) zz FROM t)
        SELECT zz FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("LOWER(b)")
    }
  }

  @Nested
  inner class ImplicitAliasOnBareColumn {

    @Test
    fun `a bare column's own implicit alias no longer fuses into the next item, killing the whole body`() {
      // #238 P2: "description dx" (a bare column immediately followed by its own implicit alias,
      // no AS) used to fuse into ONE unverifiable segment once stripCommentsAndWhitespace deleted
      // the separating space, so this position had no verifiable name at all -- and that alone
      // failed NodeTreeProvenanceExpression's all-positions cross-validation gate for the WHOLE
      // body, silencing "ux"'s own provenance too even though its own alias was perfectly fine.
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (SELECT description dx, UPPER(name) AS ux FROM parent)
        SELECT dx, ux FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 1)).isEqualTo("UPPER(name)")
    }
  }

  @Nested
  inner class CommentAndWhitespaceNormalization {

    @Test
    fun `a line comment inside the body's item list is stripped from the returned expression`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (
          SELECT id, -- LOWER(name) AS ux
          UPPER(name) AS ux FROM parent
        )
        SELECT ux FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(name)")
    }

    @Test
    fun `a comment right after an opening parenthesis leaves no cosmetic whitespace behind`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(/* x */a) AS ux FROM t)
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a comment right before a closing parenthesis leaves no cosmetic whitespace behind`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a/* x */) AS ux FROM t)
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a trailing line comment after the alias no longer silences resolution`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (
          SELECT UPPER(a) AS ux -- comment
          FROM t
        )
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a trailing block comment after the alias no longer silences resolution`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux /* comment */ FROM t)
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a block comment between AS and the alias no longer silences resolution`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS /* comment */ ux FROM t)
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a block comment between the expression and AS resolves correctly`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) /* comment */ AS ux FROM t)
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a comment containing a comma right after the alias does not leak into the alias or split early`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT, id INT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux /* note, with a comma */, id FROM t)
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a quoted alias with no separating space after AS resolves`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS"ux" FROM t)
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a no-space quoted alias combined with a trailing comment also resolves`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS"ux" /* comment */ FROM t)
        SELECT ux FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }
  }

  @Nested
  inner class ShapesThatMustStayNull {

    @Test
    fun `a genuinely self-referencing recursive CTE resolves to nothing`() {
      val ddl = "CREATE TABLE t1 (id INT, name TEXT)"
      val sql = """
        WITH RECURSIVE r AS (
          SELECT id, UPPER(name) AS un FROM t1 WHERE id = 1
          UNION ALL
          SELECT t1.id, UPPER(t1.name) FROM t1 JOIN r ON t1.id = r.id + 1
        )
        SELECT un FROM r
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 0)).isNull()
    }

    @Test
    fun `a set operation in the CTE body resolves to nothing, not the first branch's value`() {
      val ddl = "CREATE TABLE t1 (name TEXT); CREATE TABLE t2 (name TEXT)"
      val sql = """
        WITH a AS (
          SELECT UPPER(name) AS ux FROM t1
          UNION
          SELECT LOWER(name) AS ux FROM t2
        )
        SELECT ux FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `a top-level UNION ALL in the main query resolves to nothing, not the first branch's value`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT); CREATE TABLE other (b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM c UNION ALL SELECT b FROM other
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `a top-level INTERSECT in the main query resolves to nothing`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT); CREATE TABLE other (b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM c INTERSECT SELECT b FROM other
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `a top-level EXCEPT in the main query resolves to nothing`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT); CREATE TABLE other (b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM c EXCEPT SELECT b FROM other
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `the main query's FROM naming an unrelated relation resolves to nothing`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT); CREATE TABLE some_view (ux TEXT)"
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux FROM parent)
        SELECT ux FROM some_view
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `a body item with no alias at all resolves to nothing, its auto-generated resname is unverifiable`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (SELECT LOWER(name) FROM parent)
        SELECT lower FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `a matched body item that is itself a bare column reference resolves to nothing, a chained pass-through`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (SELECT description AS description_upper FROM parent)
        SELECT description_upper FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `a matched body item that is a bare column with an implicit alias also resolves to nothing`() {
      // #238 P3: the explicit-AS form of this exact pass-through (the test right above) already
      // resolved to nothing -- matchedItem.selectItem.columnName is non-null for "description AS
      // description_upper" because parseColumnReference runs on the alias-stripped expression
      // "description". For the IMPLICIT-alias spelling below, extractAlias finds no top-level AS at
      // all, so selectItem.columnName is null for the FULL unsplit text "description dx" (it isn't a
      // bare column/table dotted pair) even though the item is exactly the same kind of unchanged
      // pass-through once its own trailing alias token is split off. The two spellings must agree.
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (SELECT description dx FROM parent)
        SELECT dx FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `a quoted CTE name referenced unquoted resolves to nothing, a different relation in PostgreSQL`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT); CREATE TABLE foo (ux TEXT)"
      val sql = """
        WITH "Foo" AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM foo
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }
  }

  @Nested
  inner class ChainedCtes {

    @Test
    fun `a chain of three plain CTEs resolves the ORIGINAL computed expression, not nothing`() {
      // #238 P1: previously resolved to nothing from the third hop on -- see
      // NodeTreeProvenanceResolverTest.ChainedCtes for why.
      val ddl = "CREATE TABLE t (d TEXT)"
      val sql = """
        WITH c1 AS (SELECT UPPER(d) AS d FROM t), c2 AS (SELECT d FROM c1), c3 AS (SELECT d FROM c2)
        SELECT d FROM c3
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(d)")
    }

    @Test
    fun `a chain of four plain CTEs resolves the ORIGINAL computed expression`() {
      val ddl = "CREATE TABLE t (d TEXT)"
      val sql = """
        WITH c1 AS (SELECT UPPER(d) AS d FROM t), c2 AS (SELECT d FROM c1), c3 AS (SELECT d FROM c2),
             c4 AS (SELECT d FROM c3)
        SELECT d FROM c4
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(d)")
    }

    @Test
    fun `a chain of five plain CTEs resolves the ORIGINAL computed expression`() {
      val ddl = "CREATE TABLE t (d TEXT)"
      val sql = """
        WITH c1 AS (SELECT UPPER(d) AS d FROM t), c2 AS (SELECT d FROM c1), c3 AS (SELECT d FROM c2),
             c4 AS (SELECT d FROM c3), c5 AS (SELECT d FROM c4)
        SELECT d FROM c5
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(d)")
    }
  }

  @Nested
  inner class NestedCteShadowing {

    @Test
    fun `a sibling chain that hops through a CTE with its own shadowing nested WITH emits the OUTER sibling's value`() {
      // #238 P0 repro (verified against live PostgreSQL 18.4). With the row ('alpha', 'zeta') this
      // query actually returns "ALPHA" (UPPER(name), from the OUTER "c") -- never LOWER(description)
      // from "e"'s own inner, shadowing "c", which "e"'s body does not even read from ("e" selects
      // from "d", a plain pass-through of the OUTER "c"). See
      // NodeTreeProvenanceResolverTest.NestedCteShadowing for the full explanation of the scope bug
      // this reproduces.
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH c AS (SELECT id, UPPER(name) AS ux FROM parent),
             d AS (SELECT id, ux FROM c),
             e AS (WITH c AS (SELECT id, LOWER(description) AS ux FROM parent) SELECT id, ux FROM d)
        SELECT id, ux FROM e
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 1)).isEqualTo("UPPER(name)")
    }

    @Test
    fun `a nested WITH that shadows an outer CTE name resolves against the INNER, correctly-scoped body`() {
      // #238: the name "c" is declared twice -- an outer one, and, inside "d"'s own body, a
      // shadowing inner one with a DIFFERENT body. NodeTreeProvenanceResolver already walks to the
      // INNER "c" correctly (it tracks each hop's own :ctelevelsup); resolveNodeTreeProvenanceExpression
      // now replays that SAME hop path (["d", "c"], not a bare "c") against the SQL text, so it lands
      // on the INNER "c"'s own body -- LOWER(description) -- never the OUTER one's UPPER(name).
      val ddl = "CREATE TABLE parent (name TEXT, description TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(name) AS ux FROM parent),
             d AS (WITH c AS (SELECT LOWER(description) AS ux FROM parent) SELECT ux, 1 AS n FROM c)
        SELECT ux, n FROM d
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 0)).isEqualTo("LOWER(description)")
      // The second column never enters either "c" at all -- it resolves against "d"'s own body
      // position 2, a plain literal -- so it is unaffected by the "c"/"c" name collision and still
      // resolves normally.
      assertThat(resolvedExpression(ddl, sql, columnIndex = 1)).isEqualTo("1")
    }

    @Test
    fun `sibling CTEs where the second one shadows the first's name resolve against the INNER body`() {
      val ddl = "CREATE TABLE t (x TEXT, y TEXT)"
      val sql = """
        WITH a AS (SELECT UPPER(x) AS ux FROM t),
             b AS (WITH a AS (SELECT LOWER(y) AS ux FROM t) SELECT ux FROM a)
        SELECT ux FROM b
      """.trimIndent()

      // Proves the INNER shadowing declaration wins, not the outer one: LOWER(y), never UPPER(x).
      assertThat(resolvedExpression(ddl, sql)).isEqualTo("LOWER(y)")
    }

    @Test
    fun `a nested WITH with no name collision resolves through both levels to the real expression`() {
      // Non-adversarial control, proving the fix is a genuine scope-aware resolution rather than a
      // blanket refusal whenever a nested WITH is present at all.
      val ddl = "CREATE TABLE t (y TEXT)"
      val sql = """
        WITH d AS (WITH e AS (SELECT LOWER(y) AS uy FROM t) SELECT uy FROM e)
        SELECT uy FROM d
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("LOWER(y)")
    }
  }

  @Nested
  inner class UniquenessGate {

    @Test
    fun `two body items folding to the same name resolve to nothing, ambiguous within the body itself`() {
      // An unqualified "SELECT ux FROM a" cannot be used here at all -- PostgreSQL itself refuses to
      // parse it ("column reference \"ux\" is ambiguous"), since the CTE's own two output columns
      // fold to the identical name. "SELECT *" sidesteps that: PostgreSQL expands it into one bare
      // Var per attribute POSITIONALLY, with no by-name lookup involved, so the outer query itself
      // stays valid even though the body's own two names collide.
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH a AS (SELECT UPPER(name) AS ux, LOWER(name) AS "ux" FROM parent)
        SELECT * FROM a
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 0)).isNull()
      assertThat(resolvedExpression(ddl, sql, columnIndex = 1)).isNull()
    }

    @Test
    fun `without the uniqueness gate, a duplicated resname would let a shifted match through`() {
      // Regression-shaped proof that the uniqueness check is load-bearing, not redundant with the
      // all-position check: body positions 1 and 3 both fold to "same", and position 2 sits between
      // them with a DIFFERENT, correctly-matching alias "mid". A per-position check alone (comparing
      // text position i against resname position i) already can't be fooled by this SPECIFIC swap --
      // position 1 and 3's own text/resname still line up index-for-index -- but this is exactly the
      // shape the uniqueness gate exists to reject regardless: with "same" appearing twice, nothing
      // here can tell purely from folded names alone whether position 1's or position 3's OWN text
      // is truly bound to position 1, so the whole body remains provably ambiguous, and the uniqueness
      // gate is what turns "duplicated names exist" into an outright refusal rather than trusting
      // either occurrence. Uses "SELECT *" for the same by-name-ambiguity reason as the test above.
      val ddl = "CREATE TABLE t (a TEXT, b TEXT, c TEXT)"
      val sql = """
        WITH x AS (SELECT UPPER(a) AS same, LOWER(b) AS mid, UPPER(c) AS same FROM t)
        SELECT * FROM x
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql, columnIndex = 0)).isNull()
      assertThat(resolvedExpression(ddl, sql, columnIndex = 2)).isNull()
    }
  }

  @Nested
  inner class AllPositionCheckGate {

    @Test
    fun `a text-resname mismatch at a position OTHER than the requested one still blocks resolution`() {
      // Proves the all-position check is load-bearing, distinct from the uniqueness check above: a
      // mismatch confined to a position the caller never asked about must still block resolution of
      // the position it DID ask about, because a text/resname disagreement anywhere in the body is
      // exactly the symptom a paired lexer mis-split/mis-merge would leave behind (see this class's
      // own KDoc's discussion of NodeTreeProvenanceExpression's cross-validation).
      //
      // This needs a hand-crafted node tree, not a live round trip: under CORRECT parsing, the text
      // this file's own parseOutputItemsWithAlias computes for a position ALWAYS agrees with
      // PostgreSQL's own real `:resname` for it (whatever alias you write becomes the resname
      // verbatim) -- there is no way to make a genuinely valid, live-executable query disagree with
      // itself, only a designed one exercising the code path a bug WOULD trigger. Synthetic
      // `pg_node_tree` fixtures are an established pattern for exactly this in this codebase already
      // (see PgNodeTreeParserTest).
      val nodeTree =
        "{QUERY :cteList ({COMMONTABLEEXPR :ctename c :ctequery {QUERY :targetList " +
          "({TARGETENTRY :expr {CONST :constvalue 1} :resno 1 :resname requested " +
          ":ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false} " +
          "{TARGETENTRY :expr {CONST :constvalue 2} :resno 2 :resname mismatch_here " +
          ":ressortgroupref 0 :resorigtbl 0 :resorigcol 0 :resjunk false})}})}"
      // The SQL text's own position 2 is aliased "totally_different" -- a real, correctly-formed
      // alias -- but the (hand-crafted) node tree says PostgreSQL's real resname there is
      // "mismatch_here" instead, simulating exactly what a text mis-parse would look like. Position
      // 1, the one actually being requested, is entirely unaffected and correctly matches on its
      // own.
      val sql = """
        WITH c AS (SELECT UPPER(a) AS requested, LOWER(b) AS totally_different FROM t)
        SELECT requested FROM c
      """.trimIndent()
      val provenance = NodeTreeColumnProvenance("c", bodyPosition = 1)

      assertThat(resolveNodeTreeProvenanceExpression(sql, nodeTree, provenance)).isNull()
    }
  }

  @Nested
  inner class QuotedAndCaseFoldedCteNames {

    @Test
    fun `a quoted CTE name referenced with the identical quoted form resolves`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH "Foo" AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM "Foo"
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a quoted CTE name containing a space referenced quoted resolves`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH "My Cte" AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM "My Cte"
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `an unquoted CTE name referenced in a different case still resolves, via folding`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH foo AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM FOO
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }
  }

  @Nested
  inner class NonAsciiCaseFolding {

    @Test
    fun `an unquoted non-ASCII outer reference folds ASCII-only, matching the differently-cased alias`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "ü", LOWER(b) AS "Ü" FROM t)
        SELECT Ü FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("LOWER(b)")
    }

    @Test
    fun `the reverse non-ASCII arrangement resolves through the other alias`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "Ü", LOWER(b) AS "ü" FROM t)
        SELECT Ü FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `an unquoted uppercase outer reference still folds to match an unquoted lowercase alias`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS ux FROM t)
        SELECT UX FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }
  }

  @Nested
  inner class EscapedQuotesInBodyAliases {

    @Test
    fun `an escaped double-quote alias does not cross-match an unrelated, explicitly-aliased name`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "zz""q", LOWER(b) AS zz FROM t)
        SELECT zz FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("LOWER(b)")
    }

    @Test
    fun `a body alias with an escaped double-quote resolves when the outer reference matches exactly`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS "He""llo" FROM t)
        SELECT "He""llo" FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `a CTE name with an escaped embedded double-quote resolves by its real, unescaped name`() {
      // #238: parseSingleCteDefinition's quoted-name scan previously stopped at the FIRST '"',
      // truncating rawName to `"He"` -- fold-comparing that against the node tree's real ctename
      // `He"llo` never matched, so this resolved to nothing.
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH "He""llo" AS (SELECT UPPER(a) AS ux FROM t)
        SELECT ux FROM "He""llo"
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }
  }

  @Nested
  inner class ReservedWordsAndNiladicKeywords {

    @Test
    fun `an unquoted bare reference to the niladic keyword user resolves to nothing, not the aliased column`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(name) AS user FROM parent)
        SELECT user FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `an unquoted bare reference to the literal true resolves to nothing, a boolean value not a column`() {
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS true FROM t)
        SELECT true FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `an unquoted bare reference to current_date resolves to nothing`() {
      val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(name) AS current_date FROM parent)
        SELECT current_date FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isNull()
    }

    @Test
    fun `a quoted reference to the word user is a legitimate column reference, never punted`() {
      // Contrast case: quoting suppresses the niladic-keyword reading entirely -- a quoted "user" is
      // an ordinary column reference, unlike the bare, unquoted keyword above.
      val ddl = "CREATE TABLE t (a TEXT, b TEXT)"
      val sql = """
        WITH c AS (SELECT UPPER(a) AS"user" FROM t)
        SELECT "user" FROM c
      """.trimIndent()

      assertThat(resolvedExpression(ddl, sql)).isEqualTo("UPPER(a)")
    }

    @Test
    fun `every reserved word PostgreSQL itself accepts as a bare outer reference resolves to nothing`() {
      // Sweeps the FULL live-fetched reserved-keyword set -- never a fixed subset -- through the
      // identical CTE-wrapped shape the named tests above exercise individually. A reserved word
      // that PostgreSQL itself refuses to parse as a bare, unaliased value at all (e.g. "select",
      // "join" -- not niladic keywords, and not usable as a bare expression at all: verified live
      // that even a QUOTED body alias changes nothing here, since the rejection is always the OUTER
      // bare reference, never the alias) can never reach TypeRepository from a real query in the
      // first place, since JdbcAnalyzer's own PreparedStatement.prepareStatement would already have
      // rejected it -- those words are simply skipped here, the same "never reachable" reasoning
      // applied throughout this file. Every word PostgreSQL DOES accept as a bare reference must
      // still resolve to nothing.
      //
      // The skip is proven CORRECT, not merely present: [expectedReachableWords] independently
      // determines -- via a SEPARATE live query the resolution pipeline never touches -- exactly
      // which words PostgreSQL's own grammar allows as a bare, FROM-less `SELECT word`. If the main
      // loop's own skip decisions ever drifted from that independent answer (attempting a word
      // PostgreSQL cannot actually parse bare, or skipping one it can), this equality fails; a bare
      // `isGreaterThan(0)` could not catch either kind of drift.
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val reservedWords = connection.createStatement().use { statement ->
          statement.executeQuery("SELECT word FROM pg_get_keywords() WHERE catcode IN ('R', 'T')").use { resultSet ->
            buildSet { while (resultSet.next()) add(resultSet.getString(1)) }
          }
        }
        assertThat(reservedWords).isNotEmpty()
        val expectedReachableWords = reservedWords.filter { word -> isParseableAsBareSelectItem(connection, word) }

        var attempted = 0
        for (word in reservedWords) {
          val ddl = "CREATE TABLE parent (id INT, name TEXT, description TEXT)"
          val sql = """
            WITH c AS (SELECT UPPER(name) AS "$word" FROM parent)
            SELECT $word FROM c
          """.trimIndent()

          val expression = try {
            resolvedExpression(ddl, sql)
          } catch (_: SQLException) {
            // Not usable as a bare, unaliased value at all -- unreachable from a real query, skip.
            continue
          }
          attempted++
          assertThat(expression).isNull()
        }
        // Every word PostgreSQL's own grammar allows bare was attempted (and asserted null above);
        // none was skipped that should not have been, and none was silently attempted despite being
        // unparseable.
        assertThat(attempted).isEqualTo(expectedReachableWords.size)
      }
    }
  }

  @Nested
  inner class SentinelSubstitutionNeverLeaksIntoGeneratedText {

    @Test
    fun `a parameterised query still emits the developer's own question mark, never the sentinel literal`() {
      // The node tree passed to resolveNodeTreeProvenanceExpression is built from SENTINEL-
      // SUBSTITUTED SQL (a real '?' is not valid standalone PostgreSQL syntax, so the temp probe
      // function cannot be created from the ORIGINAL text directly) -- exactly as
      // ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody does in production. The
      // ORIGINAL text, question mark intact, is what must come back.
      val ddl = "CREATE TABLE parent (description TEXT)"
      val substitutedForNodeTree = """
        WITH c AS (SELECT UPPER(description) || '' AS d FROM parent)
        SELECT d FROM c
      """.trimIndent()
      val original = """
        WITH c AS (SELECT UPPER(description) || ? AS d FROM parent)
        SELECT d FROM c
      """.trimIndent()

      val nodeTree = nodeTreeFor(ddl, substitutedForNodeTree)
      val provenance = NodeTreeProvenanceResolver().resolveColumnProvenance(nodeTree).single()
        ?: error("expected a resolvable provenance for this shape")

      val expression = resolveNodeTreeProvenanceExpression(original, nodeTree, provenance)

      assertThat(expression).isEqualTo("UPPER(description) || ?")
    }
  }

  private fun resolvedExpression(
    @Language("PostgreSQL") ddl: String,
    @Language("PostgreSQL") sql: String,
    columnIndex: Int = 0,
  ): String? {
    val nodeTree = nodeTreeFor(ddl, sql)
    val provenance = NodeTreeProvenanceResolver().resolveColumnProvenance(nodeTree).getOrNull(columnIndex)
      ?: return null
    return resolveNodeTreeProvenanceExpression(sql, nodeTree, provenance)
  }

  /**
   * Whether PostgreSQL's own grammar accepts [word] as a bare, FROM-less `SELECT word` at all --
   * independent of [resolvedExpression]'s own CTE-wrapped probe, so it cannot share a bug with
   * whatever it is being used to check. Only a reserved word with its own niladic-keyword-shaped
   * grammar production (`user`, `current_date`, `true`, ...) parses this way; an ordinary reserved
   * word (`table`, `select`, ...) has no bare-expression production at all, in ANY context -- verified
   * live that neither quoting the alias it is bound to, nor parenthesizing the bare reference itself,
   * changes that.
   */
  private fun isParseableAsBareSelectItem(connection: Connection, word: String): Boolean = try {
    connection.createStatement().use { it.execute("SELECT $word") }
    true
  } catch (_: SQLException) {
    false
  }

  /**
   * Builds the SAME `prosqlbody` node tree [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody]
   * does: a temporary, zero-argument `BEGIN ATOMIC ... END` SQL-standard function. See
   * [NodeTreeProvenanceResolverTest]'s identical helper for why no parameter-substitution logic is
   * needed here — every caller in this file either has no `?` at all, or (in
   * [SentinelSubstitutionNeverLeaksIntoGeneratedText]) builds its own already-substituted variant by
   * hand specifically to prove the substitution never reaches the returned text.
   */
  private fun nodeTreeFor(@Language("PostgreSQL") ddl: String, @Language("PostgreSQL") sql: String): String {
    val schemaName = "test_${schemaCounter.incrementAndGet()}"
    val functionName = "probe_${UUID.randomUUID().toString().replace("-", "")}"
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("CREATE SCHEMA $schemaName")
        statement.execute("SET search_path TO $schemaName")
        statement.execute(ddl)
        statement.execute(
          "CREATE FUNCTION pg_temp.$functionName() RETURNS SETOF record LANGUAGE sql " +
            "BEGIN ATOMIC $sql\n; END",
        )
      }
      try {
        return connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT prosqlbody::text FROM pg_proc " +
              "WHERE proname = '$functionName' AND pronamespace = pg_my_temp_schema()",
          ).use { resultSet ->
            check(resultSet.next()) { "No pg_proc row found for probe function $functionName" }
            resultSet.getString(1)
          }
        }
      } finally {
        connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
      }
    }
  }

  companion object {
    private val schemaCounter = AtomicInteger(0)

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_provenance_expression_test", inMemory = true)
  }
}
