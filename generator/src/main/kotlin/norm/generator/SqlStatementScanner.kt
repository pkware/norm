package norm.generator

/**
 * Splits [sql] at each `;` outside literals, comments, and parentheses, as the JDBC driver does, and drops the parts
 * that hold nothing but whitespace and comments.
 *
 * @return the parts in order, each with the text it had in [sql].
 */
internal fun splitStatements(sql: String): List<String> {
  val statements = mutableListOf<String>()
  val cursor = SqlTokenCursor(sql, 0)
  var start = 0
  var hasContent = false
  while (true) {
    val span = cursor.advanceSignificant()
    val isSeparator = span is SqlSpan.Char && sql[span.index] == ';' && cursor.depth == 0
    if (span == null || isSeparator) {
      if (hasContent) statements += sql.substring(start, span?.index ?: sql.length)
      if (span == null) return statements
      start = span.index + 1
      hasContent = false
    } else {
      hasContent = true
    }
  }
}

/**
 * Returns the index where the statement that a leading `EXPLAIN` wraps starts, or `0` when [sql] does not start with
 * `EXPLAIN`.
 *
 * After `EXPLAIN`, `ExplainStmt` in `gram.y` takes the legacy words `ANALYZE` or `ANALYSE` and `VERBOSE`, or one
 * parenthesized option list. A `(` followed by `(`, `SELECT`, `VALUES`, `TABLE`, or `WITH` opens the wrapped query,
 * because no option has one of those names. The options hold no placeholder, so the parameter numbers of the wrapped
 * statement are those of [sql].
 */
internal fun explainedStatementStart(sql: String): Int {
  val cursor = SqlTokenCursor(sql, 0)

  fun SqlSpan.word(): String? = (this as? SqlSpan.Word)?.let { sql.substring(it.from, it.to).uppercase() }

  val explain = cursor.advanceSignificant()
  if (explain?.word() != "EXPLAIN") return 0
  var end = (explain as SqlSpan.Word).to
  val next = cursor.advanceSignificant() ?: return end
  if (next is SqlSpan.Char && sql[next.index] == '(') {
    var span = cursor.advanceSignificant() ?: return sql.length
    val startsQuery = (span is SqlSpan.Char && sql[span.index] == '(') || span.word() in QUERY_KEYWORDS
    if (startsQuery) return next.index
    var depth = 1
    while (true) {
      if (span is SqlSpan.Char && sql[span.index] == '(') depth++
      if (span is SqlSpan.Char && sql[span.index] == ')') depth--
      if (depth == 0) return (span as SqlSpan.Char).index + 1
      span = cursor.advanceSignificant() ?: return sql.length
    }
  }
  var word = next
  if (word.word() in ANALYZE_KEYWORDS) {
    end = (word as SqlSpan.Word).to
    word = cursor.advanceSignificant() ?: return end
  }
  if (word.word() == "VERBOSE") end = (word as SqlSpan.Word).to
  return end
}

/**
 * Tells whether the first significant token of [sql] opens a statement that SQL `PREPARE` accepts. SQL `PREPARE`
 * takes fewer statements than the protocol-level prepare of the JDBC driver, which also takes `EXPLAIN` and other
 * utility statements.
 */
internal fun startsPreparableStatement(sql: String): Boolean {
  val cursor = SqlTokenCursor(sql, 0)
  while (true) {
    when (val span = cursor.advanceSignificant() ?: return false) {
      is SqlSpan.Word -> return sql.substring(span.from, span.to).uppercase() in PREPARABLE_KEYWORDS
      is SqlSpan.Opaque -> return false
      is SqlSpan.Char -> if (sql[span.index] != '(') return false
    }
  }
}

private val ANALYZE_KEYWORDS = setOf("ANALYZE", "ANALYSE")

/**
 * Lists the words after `EXPLAIN (` that open a query. `utility_option_name` in `gram.y` takes a `NonReservedWord`,
 * `analyze_keyword`, or `FORMAT_LA`, which excludes the reserved `SELECT`, `TABLE`, and `WITH`. It takes the
 * column-name keyword `VALUES`, but no `EXPLAIN` option has that name.
 */
private val QUERY_KEYWORDS = setOf("SELECT", "VALUES", "TABLE", "WITH")

/** Lists the words that start a statement SQL `PREPARE` accepts, including `WITH`, which starts a query. */
private val PREPARABLE_KEYWORDS = setOf("SELECT", "VALUES", "TABLE", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE")
