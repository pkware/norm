package norm.generator

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/**
 * Describes a property's source in the database.
 *
 * @property propertyName The Kotlin property name.
 * @property comment The Postgres column comment. Empty if none.
 * @property sourceTable The database table the column comes from. `null` for computed columns.
 * @property sourceColumn The original column name in the database. `null` for computed columns.
 * @property expression The SQL expression for computed columns (e.g. `COUNT(*)`). Empty if not computed.
 */
internal data class PropertySource(
  val propertyName: String,
  val comment: String,
  val sourceTable: String?,
  val sourceColumn: String?,
  val expression: String = "",
)

/**
 * Adds a class-level KDoc block with an optional description, table mapping, and `@property` tags.
 *
 * Produces a single consolidated KDoc block rather than separate per-property doc comments, which is the
 * idiomatic Kotlin style for data classes with constructor properties.
 *
 * For table projections, the table name is shown as "Maps to the `X` table.".
 * For query projections, the SQL is included and source columns are shown per-property as `table.column` references.
 *
 * @param classComment The table or class-level comment. May be empty.
 * @param tableName The database table this class fully maps to. `null` for ad-hoc query projections.
 * @param properties Source information for each property.
 * @param sql The SQL query text. Included in query projection KDoc as a fenced code block.
 * @param reservedWords The connected PostgreSQL server's reserved keywords, forwarded to
 *   [quoteSqlIdentifierIfNeeded] for each property's `` `table.column` `` source reference. Empty
 *   for a table projection, which never renders a source reference at all.
 */
internal fun TypeSpec.Builder.addClassKdoc(
  classComment: String,
  tableName: String?,
  properties: List<PropertySource>,
  sql: String = "",
  reservedWords: Set<String> = emptySet(),
) {
  val hasTableMapping = tableName != null
  // KotlinPoet's KDoc emission rewrites "/*"/"*/" to "/&#42;"/"&#42;/" inside every KDoc block so a
  // literal block comment can't prematurely close the surrounding "/** ... */" comment, but CommonMark
  // never decodes that HTML entity back inside a fenced code block -- see
  // containsUnescapableBlockCommentDelimiter. Declining the whole fenced block is the only correct
  // choice for a query containing either sequence.
  val canRenderSqlVerbatim = sql.isNotEmpty() && !containsUnescapableBlockCommentDelimiter(sql)
  // A property whose name can't be rendered as a `@property` name token at all
  // (formatAsKdocPropertyReference returns `null`) is dropped here rather than emitted with a
  // mangled name that reads back as a different property than the one actually declared.
  val documentedProperties = properties.mapNotNull { property ->
    if (!property.hasDocumentation(hasTableMapping, reservedWords)) return@mapNotNull null
    val formattedName = property.propertyName.formatAsKdocPropertyReference() ?: return@mapNotNull null
    formattedName to property
  }
  if (classComment.isEmpty() && !hasTableMapping && !canRenderSqlVerbatim && documentedProperties.isEmpty()) return

  val kdoc = buildString {
    if (classComment.isNotEmpty()) {
      append(classComment)
    }
    if (hasTableMapping) {
      if (isNotEmpty()) append("\n\n")
      append("Maps to the `$tableName` table.")
    }
    if (canRenderSqlVerbatim) {
      if (isNotEmpty()) append("\n\n")
      // A fixed 3-backtick fence breaks if sql itself contains a run of 3+ backticks -- a line
      // matching or exceeding the fence's own length terminates the fenced block early. A fence one
      // backtick longer than any run already in sql can never be mistaken for a closing fence.
      val fence = markdownFenceDelimiter(sql)
      append(fence).append("sql\n")
      append(sql.trim())
      append("\n").append(fence)
    }
    if (documentedProperties.isNotEmpty()) {
      if (isNotEmpty()) append("\n\n")
      for ((index, formattedNameAndProperty) in documentedProperties.withIndex()) {
        val (formattedName, property) = formattedNameAndProperty
        append("@property $formattedName ")
        if (property.comment.isNotEmpty()) {
          // Every `@property` line shares one CommonMark paragraph (no blank line between them), so
          // an unescaped backtick in one comment could pair with a later property's own
          // source-reference span instead of closing here. Escaping it keeps it from ever being read
          // as a code-span delimiter.
          append(escapeMarkdownBacktick(property.comment))
        }
        if (!hasTableMapping) {
          val source = property.sourceReference(reservedWords)
          if (source != null) {
            if (property.comment.isNotEmpty()) append(" ")
            append("($source)")
          }
        }
        if (index < documentedProperties.lastIndex) append("\n")
      }
    }
  }
  addKdoc("%L", kdoc)
}

/**
 * Whether this property has any documentation to show in KDoc.
 */
private fun PropertySource.hasDocumentation(hasTableMapping: Boolean, reservedWords: Set<String>): Boolean =
  comment.isNotEmpty() || (!hasTableMapping && sourceReference(reservedWords) != null)

/**
 * Whether KotlinPoet would backtick-quote the property declaration it renders for [name].
 *
 * KotlinPoet escapes a declaration name for four independent reasons -- not a legal Java identifier,
 * one of its own reserved `KEYWORDS`, contains `$`, or is all underscores -- and both the rule and
 * the keyword set are `internal` to it, so a copy here would drift. Rendering a throwaway
 * [PropertySpec] asks KotlinPoet directly instead.
 *
 * Tests for a backtick anywhere in the rendered text rather than for `` `$name` `` specifically:
 * KotlinPoet's line wrapper substitutes a space for the characters it reserves as wrapping markers
 * (U+00B7 and U+2662), so such a name is escaped in the output without appearing there verbatim.
 * Callers must rule out a name containing its own backtick first -- KotlinPoet treats one as already
 * escaped and skips all four checks.
 */
private fun needsKotlinPoetDeclarationBackticks(name: String): Boolean =
  PropertySpec.builder(name, ANY).build().toString().contains('`')

/**
 * Formats a Kotlin property name for use as the name token in a KDoc `@property` tag.
 *
 * KDoc's `@property` tag takes exactly one name token before the description text begins, so a
 * property name containing a space or other non-identifier character (e.g. the Kotlin property
 * `` `My Col` `` generated for a quoted SQL column `"My Col"`) must be wrapped in backticks here too
 * -- otherwise `@property My Col Some comment.` reads as a property literally named `My`. The name is
 * left bare only when the declaration KotlinPoet renders for it is bare too, so the two never
 * disagree.
 *
 * Uses [wrapInBacktickDelimiter]'s longest-run rule rather than a fixed single-backtick wrap, since a
 * name containing its own literal backtick (e.g. `` a`b ``) would otherwise close the `@property`
 * tag's span early, corrupting the rest of the line.
 *
 * Returns `null` — decline, emit no `@property` line at all — when [this] contains a literal
 * block-comment open or close delimiter ([containsUnescapableBlockCommentDelimiter]): widening the
 * backtick delimiter fixes the span, but KotlinPoet's KDoc emission still rewrites the delimiter
 * itself to an HTML entity, which would render a tag naming a different property than the one
 * actually declared.
 *
 * This fixes only the KDoc span; it does not and cannot fix the Kotlin property declaration itself
 * (`` public val `a\`b`: ... ``), which is not valid Kotlin — a backtick-quoted identifier cannot
 * contain a backtick, and there is no escape for one. That is a separate, pre-existing defect in how
 * a column's raw database identifier becomes a Kotlin property name, left unfixed here because the
 * same field also carries the identifier back into generated SQL and catalog lookups.
 */
private fun String.formatAsKdocPropertyReference(): String? = when {
  !contains('`') && !needsKotlinPoetDeclarationBackticks(this) -> this
  containsUnescapableBlockCommentDelimiter(this) -> null
  else -> wrapInBacktickDelimiter(this)
}

/**
 * Returns a source reference string for display in KDoc, or `null` if none is available (either
 * there is nothing to reference, or [markdownInlineCodeSpan] could not render it faithfully — see
 * that function's own KDoc for when that happens).
 *
 * - For columns from a table: `` `table."Column"` `` — each identifier individually quoted via
 *   [quoteSqlIdentifierIfNeeded] exactly as PostgreSQL requires it written back into SQL, so this
 *   can always be pasted into a query verbatim.
 * - For computed expressions: `` `COUNT(*)` ``
 *
 * @param reservedWords The connected server's reserved keywords, forwarded to
 *   [quoteSqlIdentifierIfNeeded].
 */
private fun PropertySource.sourceReference(reservedWords: Set<String>): String? = when {
  sourceTable != null -> {
    val qualifiedColumn = sourceColumn?.let { quoteSqlIdentifierIfNeeded(it, reservedWords) }.orEmpty()
    markdownInlineCodeSpan("${quoteSqlIdentifierIfNeeded(sourceTable, reservedWords)}.$qualifiedColumn")
  }
  expression.isNotEmpty() -> markdownInlineCodeSpan(expression)
  else -> null
}

/**
 * The longest run of consecutive backtick characters anywhere in [text], or `0` if [text] contains
 * none. Used by [markdownInlineCodeSpan] and [markdownFenceDelimiter] to pick a delimiter that can
 * never be mistaken for a same-length run already inside [text].
 */
private fun longestBacktickRun(text: String): Int {
  var longest = 0
  var current = 0
  for (character in text) {
    if (character == '`') {
      current++
      if (current > longest) longest = current
    } else {
      current = 0
    }
  }
  return longest
}

/**
 * Wraps [text] in a Markdown inline code span that renders back to exactly [text], or `null` if no
 * inline code span can carry it faithfully.
 *
 * Two hazards:
 * - A run of backticks inside [text] as long as the span's own delimiter would be read as the
 *   closing delimiter, ending the span early. Fixed by using a delimiter one backtick longer than
 *   [text]'s own longest run ([longestBacktickRun]), with a padding space on each side when [text]
 *   itself starts or ends with a backtick.
 * - A raw newline inside [text] (e.g. a string literal containing one). CommonMark folds a line
 *   ending inside an inline code span to a single space when rendering, silently changing the value.
 *   No delimiter choice can fix this — the corruption happens during rendering — so this declines.
 * - A literal block-comment open or close delimiter inside [text] — see
 *   [containsUnescapableBlockCommentDelimiter] for why that is a third, un-fixable-by-delimiter
 *   hazard.
 */
internal fun markdownInlineCodeSpan(text: String): String? {
  if (text.contains('\n') || text.contains('\r')) return null
  if (containsUnescapableBlockCommentDelimiter(text)) return null
  return wrapInBacktickDelimiter(text)
}

/**
 * Whether [text] contains `/*` or `*/`. KotlinPoet's KDoc emission unconditionally rewrites either to
 * `/&#42;`/`&#42;/` so a literal block comment can never prematurely close the surrounding KDoc
 * comment, but CommonMark never decodes that HTML entity back inside an inline code span or fenced
 * code block — the two constructs [markdownInlineCodeSpan] and [TypeSpec.Builder.addClassKdoc]'s
 * `sql` block render as. Once that rewrite happens there is no delimiter choice that can carry [text]
 * back to its own value, so both callers decline instead.
 */
internal fun containsUnescapableBlockCommentDelimiter(text: String): Boolean =
  text.contains("/*") || text.contains("*/")

/**
 * Backslash-escapes every literal backtick in [text] so it can never be read as a CommonMark
 * inline-code-span delimiter, without altering the character [text] renders as.
 *
 * [TypeSpec.Builder.addClassKdoc] appends every property's [PropertySource.comment] into one
 * continuous CommonMark paragraph shared by every `@property` line, so an unescaped backtick in one
 * comment could pair with a backtick belonging to a later property's own source-reference span,
 * corrupting every span in between. Escaping here removes the character from delimiter-matching
 * entirely.
 *
 * Escapes [text]'s own literal backslashes first, before escaping backticks: escaping only the
 * backtick is defeated when [text] already has a backslash immediately before one (e.g.
 * `` 'weird \`' ``) — the naive replacement produces `` \\` ``, which CommonMark reads as an escaped
 * backslash followed by an unescaped, still-open backtick.
 */
internal fun escapeMarkdownBacktick(text: String): String = text.replace("\\", "\\\\").replace("`", "\\`")

/**
 * Wraps [text] in a backtick-delimited span using a delimiter one backtick longer than [text]'s own
 * longest internal run ([longestBacktickRun]), with a padding space on each side when [text] starts
 * or ends with a backtick. Shared by [markdownInlineCodeSpan] and [formatAsKdocPropertyReference],
 * whose spans are both subject to the same backtick-collision hazard.
 */
private fun wrapInBacktickDelimiter(text: String): String {
  val delimiter = "`".repeat(longestBacktickRun(text) + 1)
  val needsPadding = text.startsWith("`") || text.endsWith("`")
  return if (needsPadding) "$delimiter $text $delimiter" else "$delimiter$text$delimiter"
}

/**
 * A backtick-fence delimiter (` ``` `, or longer) that can open a Markdown fenced code block
 * containing [text] without [text] itself supplying a same-length or longer backtick run that
 * CommonMark would read as the block's own closing fence — one backtick longer than [text]'s own
 * longest run ([longestBacktickRun]), never shorter than the conventional 3.
 */
internal fun markdownFenceDelimiter(text: String): String = "`".repeat(maxOf(3, longestBacktickRun(text) + 1))
