package norm.generator.baseline

import norm.generator.OutputItemWithAlias
import norm.generator.QueryFileParser
import norm.generator.alignSelectItems
import norm.generator.explainedStatementStart
import norm.generator.hasTopLevelSetOperation
import norm.generator.parseCteClause
import norm.generator.parseOutputItemsWithAlias
import norm.generator.parseSelectItems
import norm.generator.placeholderPositions
import norm.generator.replaceParameterPlaceholders
import norm.generator.splitStatements

/**
 * Renders what each hand-written scanner function returns, or throws, for every [CorpusEntry].
 *
 * A thrown exception is recorded as `ClassName: message`. Each recorded value is one line, with control characters
 * escaped, so a change of any function's output for any entry shows up as a changed line.
 */
internal object ScannerBaseline {
  private const val HEX_RADIX = 16
  private const val UNICODE_ESCAPE_DIGITS = 4

  fun render(entries: List<CorpusEntry>): String = buildString {
    for (entry in entries) {
      appendLine("=== ${entry.key}")
      appendLine("text: ${escape(entry.text)}")
      renderEntry(entry.text)
    }
  }

  private fun StringBuilder.renderEntry(text: String) {
    record("parseSelectItems") { parseSelectItems(text) }
    record("parseOutputItemsWithAlias") { parseOutputItemsWithAlias(text) }
    val columnCount = runCatching { parseSelectItems(text).size }
    record("alignSelectItems(columnCount = parseSelectItems.size)") {
      alignSelectItems(text, columnCount.getOrThrow())
    }
    record("alignSelectItems(columnCount = parseSelectItems.size + 1)") {
      alignSelectItems(text, columnCount.getOrThrow() + 1)
    }
    record("hasTopLevelSetOperation") { hasTopLevelSetOperation(text) }
    record("placeholderPositions") { placeholderPositions(text).contentToString() }
    record("replaceParameterPlaceholders") { replaceParameterPlaceholders(text) { "\$${it + 1}" } }
    record("QueryFileParser.parse") { QueryFileParser.parse(text, "") }
    record("splitStatements") { splitStatements(text) }
    record("explainedStatementStart") { explainedStatementStart(text) }
    record("parseCteClause") { parseCteClause(text) }
    val items = runCatching { parseOutputItemsWithAlias(text) }
    items.fold(
      onSuccess = { renderItems(it) },
      onFailure = { appendLine("items: ${escape(describe(it))}") },
    )
  }

  private fun StringBuilder.renderItems(items: List<OutputItemWithAlias>) {
    for ((index, item) in items.withIndex()) {
      record("isStar(item[$index])") { item.selectItem.isStar }
      record("alias(item[$index])") { item.alias }
      record("isAliasExplicit(item[$index])") { item.isAliasExplicit }
    }
  }

  private fun StringBuilder.record(name: String, compute: () -> Any?) {
    val outcome = try {
      compute().toString()
    } catch (exception: Exception) {
      describe(exception)
    }
    appendLine("$name: ${escape(outcome)}")
  }

  private fun describe(throwable: Throwable): String = "${throwable::class.java.name}: ${throwable.message}"

  /** Escapes backslashes, control characters and unpaired surrogates so a value fits on one line of UTF-8 text. */
  fun escape(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
      val character = value[index]
      when {
        character == '\\' -> append("\\\\")
        character == '\n' -> append("\\n")
        character == '\r' -> append("\\r")
        character == '\t' -> append("\\t")
        character.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() == true -> {
          append(character).append(value[index + 1])
          index++
        }
        character < ' ' || character == '\u007f' || character.isSurrogate() ->
          append("\\u").append(character.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
        else -> append(character)
      }
      index++
    }
  }
}
