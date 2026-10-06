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
  val explain = cursor.advanceSignificant()
  if (explain?.word(sql) != "EXPLAIN") return 0
  var end = (explain as SqlSpan.Word).to
  val next = cursor.advanceSignificant() ?: return end
  if (next is SqlSpan.Char && sql[next.index] == '(') {
    var span = cursor.advanceSignificant() ?: return sql.length
    val startsQuery = (span is SqlSpan.Char && sql[span.index] == '(') || span.word(sql) in QUERY_KEYWORDS
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
  if (word.word(sql) in ANALYZE_KEYWORDS) {
    end = (word as SqlSpan.Word).to
    word = cursor.advanceSignificant() ?: return end
  }
  if (word.word(sql) == "VERBOSE") end = (word as SqlSpan.Word).to
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

/**
 * Returns [sql] once without each `INTO` clause, in order.
 *
 * In a statement that `PREPARE` accepts, `gram.y` writes `INTO` after `INSERT`, after `MERGE`, or as the
 * `into_clause` of a `SELECT`. The word before `INTO` does not tell them apart, because `insert` and `merge` can name
 * a column. Removing the `INTO` of an `INSERT` or `MERGE` leaves SQL that PostgreSQL rejects.
 *
 * A clause ends with its table name. `OptTempTableName` puts the reserved word `TABLE` and unreserved option words
 * before the name. A name part is a word that is not reserved, a quoted identifier, or any word after a `.`. A
 * Unicode name `U&"..."` may carry `UESCAPE '...'`. Any other token ends the clause.
 *
 * @param reservedWords the lowercase keywords that cannot start a name, as `pg_get_keywords()` lists them.
 */
internal fun withoutEachIntoClause(sql: String, reservedWords: Set<String>): Sequence<String> = sequence {
  val cursor = SqlTokenCursor(sql, 0)
  while (true) {
    val span = cursor.advanceSignificant() ?: break
    if (span.word(sql) == "INTO") {
      val start = (span as SqlSpan.Word).from
      yield(sql.removeRange(start, intoClauseEnd(sql, SqlTokenCursor(sql, span.to), span.to, reservedWords)))
    }
  }
}

private fun intoClauseEnd(sql: String, cursor: SqlTokenCursor, intoEnd: Int, reservedWords: Set<String>): Int {
  var end = intoEnd
  var afterDot = false
  var afterUescape = false
  while (true) {
    val span = cursor.advanceSignificant() ?: return end
    val namePartEnd = when (span) {
      is SqlSpan.Opaque -> span.to.takeIf { sql[span.from] == '"' || (afterUescape && sql[span.from] == '\'') }
      is SqlSpan.Word -> span.to.takeIf {
        afterDot ||
          span.word(sql) == "TABLE" ||
          isUnreserved(span, sql, reservedWords)
      }
      is SqlSpan.Char -> (span.index + 1).takeIf { opensUnicodeIdentifier(sql, span.index, end) }
    }
    when {
      namePartEnd != null -> {
        end = namePartEnd
        afterDot = false
        afterUescape = span.word(sql) == "UESCAPE"
      }
      span is SqlSpan.Char && sql[span.index] == '.' && !afterDot -> afterDot = true
      else -> return end
    }
  }
}

/** Tells whether the `&` at [index] joins a `U` that ends at [wordEnd] to a quoted name, as in `U&"d\0061ta"`. */
private fun opensUnicodeIdentifier(sql: String, index: Int, wordEnd: Int): Boolean =
  sql[index] == '&' && wordEnd == index && sql[index - 1].uppercaseChar() == 'U' && sql.startsWith("\"", index + 1)

private fun isUnreserved(word: SqlSpan.Word, sql: String, reservedWords: Set<String>): Boolean =
  sql.substring(word.from, word.to).lowercase() !in reservedWords

private fun SqlSpan.word(sql: String): String? = (this as? SqlSpan.Word)?.let {
  sql.substring(it.from, it.to).uppercase()
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
