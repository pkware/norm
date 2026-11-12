package norm.generator

import norm.generator.libpgquery.ParseTreeNames

/**
 * A CTE definition from a `WITH` clause.
 *
 * @property name The CTE name as PostgreSQL's parser stores it, equal to the name the server reports.
 * @property ctequery The parse tree node of the CTE body. It has the shape [outputStatementOf] and [cteDefinitions]
 *   read: an object with one field named for the statement kind, such as `SelectStmt`.
 */
internal data class CteDefinition(val name: String, val ctequery: JsonValue.JsonObject)

/**
 * Reads the `WITH` clause of [statementNode], so a `WITH` inside a CTE body or a subquery is not included.
 *
 * @param statementNode an object with one field named for the statement kind, as [firstStatement] returns it or as a
 *   [CteDefinition.ctequery] holds it.
 * @return The CTEs in declaration order. Empty when the statement has no `WITH` clause.
 * @throws IllegalStateException if a CTE has no name or no body.
 */
@Throws(IllegalStateException::class)
internal fun cteDefinitions(statementNode: JsonValue.JsonObject): List<CteDefinition> {
  val statement = statementNode.fields.values.singleOrNull() as? JsonValue.JsonObject
  return statement?.objectField(ParseTreeNames.WITH_CLAUSE)
    ?.objectArrayField(ParseTreeNames.COMMON_TABLE_EXPRESSIONS)
    .orEmpty()
    .mapNotNull { it.objectField(ParseTreeNames.COMMON_TABLE_EXPRESSION) }
    .map { expression ->
      CteDefinition(
        name = checkNotNull(expression.stringField(ParseTreeNames.COMMON_TABLE_EXPRESSION_NAME)) {
          "A CTE has no name"
        },
        ctequery = checkNotNull(expression.objectField(ParseTreeNames.COMMON_TABLE_EXPRESSION_QUERY)) {
          "A CTE has no body"
        },
      )
    }
}
