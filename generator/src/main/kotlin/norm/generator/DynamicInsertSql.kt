package norm.generator

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec

/**
 * Adds the `val sql = ...` declaration for a synthesized INSERT that has optional columns
 * ([SqlStatement.optionalParameterIndices]).
 *
 * Each optional column gets a `val xPlaceholder = if (<presenceCheck>) "?" else "DEFAULT"` declaration. The emitted
 * `sql` expression concatenates these locals in place of the column's `?`. The column list stays the same on every
 * call.
 *
 * @param templateSql [SqlStatement.sql] on the single-row path or [SqlStatement.batchSql] on the batch-with-return
 *   path. Flat text with one `?` per entry of [SqlStatement.parameters], left to right, as [CrudQuerySynthesizer]
 *   emits.
 * @param presenceCheck given an optional column's parameter index, returns the runtime boolean expression that is
 *   `true` when the caller supplied a value: `%N is ColumnValue.Set` on the single-row path, `%N != null` on the
 *   batch path.
 */
internal fun FunSpec.Builder.addDynamicInsertSqlDeclaration(
  statement: SqlStatement,
  templateSql: String,
  presenceCheck: (parameterIndex: Int) -> CodeBlock,
) {
  val placeholderExpressionsByIndex = mutableMapOf<Int, CodeBlock>()
  for (parameterIndex in statement.optionalParameterIndices) {
    val placeholderName = "${statement.getParameterName(parameterIndex)}Placeholder"
    addStatement("val %N = if (%L) %S else %S", placeholderName, presenceCheck(parameterIndex), "?", "DEFAULT")
    placeholderExpressionsByIndex[parameterIndex] = CodeBlock.of("%N", placeholderName)
  }
  addCode(buildDynamicSqlExpression(statement, templateSql, placeholderExpressionsByIndex))
  addCode("\n")
}

/**
 * Builds a `val sql = <segment> + <placeholder> + <segment> + ...` expression from [templateSql].
 *
 * Splits [templateSql] on `?`, one per entry of [SqlStatement.parameters]. Each `?` with an entry in
 * [placeholderExpressionsByIndex] is replaced by that expression; the other `?` characters stay in the static text.
 */
private fun buildDynamicSqlExpression(
  statement: SqlStatement,
  templateSql: String,
  placeholderExpressionsByIndex: Map<Int, CodeBlock>,
): CodeBlock {
  val segments = templateSql.split("?")
  check(segments.size == statement.parameters.size + 1) {
    "Expected ${statement.parameters.size} '?' placeholders in synthesized INSERT SQL: $templateSql"
  }
  val parts = mutableListOf<CodeBlock>()
  var buffer = StringBuilder(segments[0])
  for (index in statement.parameters.indices) {
    val dynamicEntry = placeholderExpressionsByIndex[index]
    if (dynamicEntry == null) {
      buffer.append("?")
    } else {
      parts.add(CodeBlock.of("%S", buffer.toString()))
      parts.add(dynamicEntry)
      buffer = StringBuilder()
    }
    buffer.append(segments[index + 1])
  }
  parts.add(CodeBlock.of("%S", buffer.toString()))
  val expression = parts.reduce { acc, part -> CodeBlock.of("%L + %L", acc, part) }
  return CodeBlock.of("val sql = %L", expression)
}
