package norm.generator

/**
 * An item of [ParsedSql.outputItems], parsed from a SQL SELECT or RETURNING list.
 *
 * [alias] and [columnName] are independent. `description AS description_upper` has both,
 * `UPPER(description) AS description_upper` has only an alias, and `description` has only a column name.
 *
 * @property expression The item's source text from its first token to the end of its expression, without a trailing
 *   `AS` or alias (e.g. `COUNT(*)`, `author.name`, `book.title`). Comments between its tokens are kept.
 * @property columnName The name of a simple column reference as PostgreSQL's parser stores it, or `null` for a computed
 *   expression. An unquoted name is folded to lowercase. A quoted name has its quotes removed and each doubled `""`
 *   collapsed to `"`. On PostgreSQL 18, `ResultSetMetaData.getColumnName` for `SELECT "He""llo" FROM (SELECT 1 AS
 *   "He""llo") s` reports `He"llo`, which this property equals.
 * @property tableName The table qualifier of a qualified reference (e.g. `author` in `author.name`, or `My Table` in
 *   `"My Table".name`), under the same convention as [columnName]. `null` for an unqualified reference or a computed
 *   expression.
 * @property isStar Whether the item is `*`, `table.*`, or a composite expansion such as `(f(x)).*`. A star expands to
 *   as many columns as the expanded relation or composite has. The text does not give that number.
 * @property normalizedExpression [expression] rebuilt from its non-comment tokens, for embedding in generated
 *   documentation. Tokens that touch in the source stay joined. Every other gap, whether whitespace or a comment,
 *   becomes one space, except after `(` and before `)`. The same holds between the quoted segments of one string
 *   literal, which PostgreSQL reads as a single token (see [joinStringContinuation]).
 * @property alias The alias as PostgreSQL's parser stores it in `ResTarget.name`, or `null` if the item has no alias.
 *   An unquoted alias is folded to lowercase. A quoted alias has its quotes removed and each doubled `""` collapsed to
 *   `"`. A Unicode-escaped alias, `U&"d!0061t" UESCAPE '!'`, has its escapes resolved.
 * @property isAliasExplicit Whether [alias] follows an unquoted `AS`. `false` for an implicit alias (`UPPER(a) y`) and
 *   when [alias] is `null`.
 */
internal data class OutputItem(
  val expression: String,
  val columnName: String?,
  val tableName: String?,
  val isStar: Boolean,
  val normalizedExpression: String,
  val alias: String?,
  val isAliasExplicit: Boolean,
)
