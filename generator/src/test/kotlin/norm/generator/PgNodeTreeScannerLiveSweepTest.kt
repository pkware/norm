package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live-PostgreSQL proof for [PgNodeTreeScanner.findMarkerAtDepthOne]'s label/value classification
 * rule (see that method's KDoc): every `:`-leading token at a real node's own depth-1, outside any
 * `(...)` list, is the field label [findMarkerAtDepthOne] finds — not a value that merely starts
 * with or ends in the marker text.
 *
 * [depthOneItemsSweep] recomputes each node's depth-1 item boundaries independently of
 * [PgNodeTreeScanner] (its own token/block/list walk, escape-aware the same way the class-level
 * KDoc on [PgNodeTreeScanner] describes), so the comparison against [PgNodeTreeScanner.findMarkerAtDepthOne]
 * is not circular.
 */
@Testcontainers
class PgNodeTreeScannerLiveSweepTest {

  private val scanner = PgNodeTreeScanner()
  private val parser = PgNodeTreeParser()

  /**
   * Every `pg_catalog`/`information_schema` view's `_RETURN` rule is real, PostgreSQL-authored
   * `pg_node_tree` text with no user-supplied identifiers — nothing in this corpus is quoted the
   * way a user's `":cterecursive"` CTE name or `":resorigtbl"` column alias would be. This test's
   * precondition (no two consecutive depth-1 items both start with `:`) is exactly what that buys:
   * every `:`-leading token here is unambiguously a label, so this sweep alone cannot exercise the
   * value/label ambiguity the unit tests in [PgNodeTreeScannerTest] pin directly. What it does
   * prove is that the depth-one item walk itself — skipping whole `{...}` blocks and `(...)` lists,
   * including multi-token datums — never desyncs against real, large, deeply nested trees.
   */
  @Test
  fun `every colon-leading depth-one token in the pg_catalog and information_schema corpus is the match`() {
    val trees = fetchCorpusTrees()
    assertThat(trees.size).isGreaterThanOrEqualTo(50)

    var nodeCount = 0
    var labelCount = 0
    val consecutiveColonViolations = mutableListOf<String>()
    val mismatches = mutableListOf<String>()

    fun visit(nodeText: String) {
      if (!nodeText.startsWith("{")) return
      nodeCount++
      val items = depthOneItemsSweep(nodeText)
      for (i in items.indices) {
        val item = items[i]
        if (!item.isColonToken) continue
        labelCount++
        if (i > 0 && items[i - 1].isColonToken) {
          consecutiveColonViolations += "consecutive colon items '${items[i - 1].text}' then " +
            "'${item.text}' in node starting: ${nodeText.take(120)}"
        }
        val expected = item.startIndex + item.text.length + 1
        val actual = scanner.findMarkerAtDepthOne(nodeText, "${item.text} ")
        if (actual != expected) {
          mismatches += "label '${item.text}' expected index $expected but findMarkerAtDepthOne returned " +
            "$actual in node starting: ${nodeText.take(120)}"
        }
      }
      for (item in items) {
        when {
          item.text.startsWith("{") -> visit(item.text)
          item.text.startsWith("(") -> scanner.splitBraceBlocks(item.text.substring(1, item.text.length - 1))
            .forEach(::visit)
        }
      }
    }

    // pg_rewrite.ev_action is a *list* of actions (`({QUERY ...})`), even for a single-action
    // _RETURN rule — split the outer list before visiting each action's own {QUERY ...} node.
    trees.forEach { tree -> scanner.splitBraceBlocks(tree).forEach(::visit) }

    assertThat(consecutiveColonViolations).isEmpty()
    assertThat(mismatches).isEmpty()
    assertThat(nodeCount).isGreaterThan(0)
    assertThat(labelCount).isGreaterThan(0)
  }

  @Test
  fun `parseCteList reads a recursive CTE literally named colon-cterecursive as recursive`() {
    val viewSql = """
      WITH RECURSIVE ":cterecursive" AS (
        SELECT 1 AS n
        UNION ALL
        SELECT n + 1 FROM ":cterecursive" WHERE n < 3
      )
      SELECT n FROM ":cterecursive"
    """.trimIndent()
    val recursive = withProbeView(ddl = null, viewSql = viewSql) { nodeTree, _ ->
      parser.parseCteList(nodeTree).single().recursive
    }
    assertThat(recursive).isTrue()
  }

  @Test
  fun `fieldAtDepthOne reads a target entry's own resorigtbl even though its resname is quoted colon-resorigtbl`() {
    val (value, tableOid) = withProbeView(
      ddl = "CREATE TABLE t (id int NOT NULL)",
      viewSql = """SELECT id AS ":resorigtbl" FROM t""",
    ) { nodeTree, connection ->
      val targetListContent = requireNotNull(scanner.rawListAtDepthOne(nodeTree, ":targetList"))
      val targetEntryText = scanner.splitBraceBlocks(targetListContent).single()
      val value = scanner.fieldAtDepthOne(targetEntryText, ":resorigtbl")
      val oid = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT 't'::regclass::oid::text").use { resultSet ->
          resultSet.next()
          resultSet.getString(1)
        }
      }
      value to oid
    }
    assertThat(value).isEqualTo(FieldValue.Token(tableOid))
  }

  companion object {
    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = testPostgresContainer("norm_node_tree_scanner_live_sweep")

    private lateinit var connection: Connection
    private val schemaCounter = AtomicInteger()

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }

    /**
     * Fetches every `_RETURN` rule's `ev_action` text (`rw.ev_type = '1'`) for a view or
     * materialized view in `pg_catalog` or `information_schema` — a fixed, PostgreSQL-authored
     * corpus with no schema managed by this test's own connection, so it is stable across the
     * whole suite regardless of which schemas [withProbeView] creates and drops.
     */
    private fun fetchCorpusTrees(): List<String> = connection.createStatement().use { statement ->
      statement.executeQuery(
        """
          SELECT rw.ev_action::text AS node_tree
          FROM pg_catalog.pg_rewrite rw
          JOIN pg_catalog.pg_class c ON c.oid = rw.ev_class
          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname IN ('pg_catalog', 'information_schema') AND rw.ev_type = '1'
        """.trimIndent(),
      ).use { resultSet ->
        buildList { while (resultSet.next()) add(resultSet.getString("node_tree")) }
      }
    }

    /**
     * Creates a fresh, isolated schema, runs [ddl] (if any) and `CREATE VIEW probe_view AS
     * [viewSql]`, fetches `probe_view`'s own `ev_action` text, and hands both it and the live
     * [Connection] (still scoped to the probe schema) to [block] before dropping the schema.
     */
    private fun <T> withProbeView(
      ddl: String?,
      viewSql: String,
      block: (nodeTree: String, connection: Connection) -> T,
    ): T {
      val schemaName = "test_${schemaCounter.incrementAndGet()}"
      val probeConnection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
      return probeConnection.use { probe ->
        probe.createStatement().use { statement ->
          statement.execute("CREATE SCHEMA $schemaName")
          statement.execute("SET search_path TO $schemaName")
          if (ddl != null) statement.execute(ddl)
          statement.execute("CREATE VIEW probe_view AS $viewSql")
        }
        try {
          val nodeTree = probe.createStatement().use { statement ->
            statement.executeQuery("SELECT ev_action::text FROM pg_rewrite WHERE ev_class = 'probe_view'::regclass")
              .use { resultSet ->
                resultSet.next()
                resultSet.getString(1)
              }
          }
          block(nodeTree, probe)
        } finally {
          probe.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
      }
    }

    /** One depth-one item, as read by [depthOneItemsSweep]. */
    private data class DepthOneItem(val text: String, val startIndex: Int, val isColonToken: Boolean)

    /**
     * Independently walks [nodeText]'s (a full `{...}` block) own depth-one items — a token, a
     * whole `{...}` block, or a whole `(...)` list — the same shapes
     * [PgNodeTreeScanner.findMarkerAtDepthOne]'s KDoc describes, but computed here without calling
     * into [PgNodeTreeScanner] at all, so the sweep's expectation is not derived from the
     * production code it is checking.
     */
    private fun depthOneItemsSweep(nodeText: String): List<DepthOneItem> {
      val items = mutableListOf<DepthOneItem>()
      var index = 1
      val endIndex = nodeText.length - 1
      while (index < endIndex) {
        while (index < endIndex && nodeText[index].isWhitespace()) index++
        if (index >= endIndex) break
        when (nodeText[index]) {
          '{' -> {
            val block = requireNotNull(extractBalancedSpan(nodeText, index, '{', '}')) {
              "unbalanced { in corpus node at index $index: ${nodeText.take(200)}"
            }
            items += DepthOneItem(block, index, isColonToken = false)
            index += block.length
          }

          '(' -> {
            val list = requireNotNull(extractBalancedSpan(nodeText, index, '(', ')')) {
              "unbalanced ( in corpus node at index $index: ${nodeText.take(200)}"
            }
            items += DepthOneItem(list, index, isColonToken = false)
            index += list.length
          }

          else -> {
            val tokenStart = index
            while (index < endIndex) {
              val character = nodeText[index]
              if (character == '\\' && index + 1 < endIndex) {
                index += 2
                continue
              }
              if (character.isWhitespace() ||
                character == '{' ||
                character == '}' ||
                character == '(' ||
                character == ')'
              ) {
                break
              }
              index++
            }
            val token = nodeText.substring(tokenStart, index)
            items += DepthOneItem(token, tokenStart, isColonToken = token.startsWith(':'))
          }
        }
      }
      return items
    }

    /** Escape-aware balanced-delimiter span (delimiters included), starting at [startIndex]. */
    private fun extractBalancedSpan(text: String, startIndex: Int, open: Char, close: Char): String? {
      var depth = 0
      var index = startIndex
      while (index < text.length) {
        val character = text[index]
        if (character == '\\' && index + 1 < text.length) {
          index += 2
          continue
        }
        when (character) {
          open -> depth++
          close -> {
            depth--
            if (depth == 0) return text.substring(startIndex, index + 1)
          }
        }
        index++
      }
      return null
    }
  }
}
