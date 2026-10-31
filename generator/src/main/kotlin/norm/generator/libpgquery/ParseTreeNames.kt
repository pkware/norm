package norm.generator.libpgquery

/**
 * Node and field names in the JSON parse tree from [LibPgQuery.parse].
 *
 * Each value is spelled as in the PostgreSQL parse node struct it comes from (`SelectStmt`, `ResTarget`,
 * `ColumnRef` in `parsenodes.h` and `primnodes.h`), as libpg_query's `pg_query.proto` repeats them.
 */
internal object ParseTreeNames {
  /** `ParseResult.stmts`: the statements of the parsed text. */
  const val STATEMENTS = "stmts"

  /** `RawStmt.stmt`: the node of one statement, which holds a single field named after its node type. */
  const val STATEMENT = "stmt"

  /** `ExplainStmt.options`: the `DefElem` nodes of the legacy words `ANALYZE` and `VERBOSE` or of the option list. */
  const val EXPLAIN_OPTIONS = "options"

  /** `SelectStmt`: a `SELECT`, `VALUES` or `TABLE` statement, or a set operation over those. */
  const val SELECT_STATEMENT = "SelectStmt"

  /** `InsertStmt`. */
  const val INSERT_STATEMENT = "InsertStmt"

  /** `UpdateStmt`. */
  const val UPDATE_STATEMENT = "UpdateStmt"

  /** `DeleteStmt`. */
  const val DELETE_STATEMENT = "DeleteStmt"

  /** `MergeStmt`. */
  const val MERGE_STATEMENT = "MergeStmt"

  /** `ExplainStmt`. */
  const val EXPLAIN_STATEMENT = "ExplainStmt"

  /** `WithClause` field of a statement that can start with `WITH`. */
  const val WITH_CLAUSE = "withClause"

  /** `WithClause.ctes`: the `CommonTableExpr` nodes of a `WITH` clause, in source order. */
  const val COMMON_TABLE_EXPRESSIONS = "ctes"

  /** `CommonTableExpr.ctename`: the CTE name in the form [STRING_VALUE] holds, cut to the server's identifier limit. */
  const val COMMON_TABLE_EXPRESSION_NAME = "ctename"

  /** `CommonTableExpr.aliascolnames`: the column list written after the CTE name, absent when there is none. */
  const val COMMON_TABLE_EXPRESSION_COLUMN_NAMES = "aliascolnames"

  /** `CommonTableExpr`: one CTE of a `WITH` clause. Its [LOCATION] is where the CTE name starts. */
  const val COMMON_TABLE_EXPRESSION = "CommonTableExpr"

  /** `SelectStmt.targetList`: the select list. */
  const val TARGET_LIST = "targetList"

  /** `SelectStmt.valuesLists`: the rows of a `VALUES` list. */
  const val VALUES_LISTS = "valuesLists"

  /** `SelectStmt.op`: the set operation, `SETOP_NONE` for a plain `SELECT`. */
  const val SET_OPERATION = "op"

  /** `SetOperation.SETOP_NONE`: the value of [SET_OPERATION] on a statement that is not a set operation. */
  const val SET_OPERATION_NONE = "SETOP_NONE"

  /** `SelectStmt.larg`: the left branch of a set operation. */
  const val LEFT_BRANCH = "larg"

  /** `ReturningClause` field of a data-modifying statement. */
  const val RETURNING_CLAUSE = "returningClause"

  /** `ReturningClause.exprs`: the items of a `RETURNING` list. */
  const val RETURNING_ITEMS = "exprs"

  /** `ResTarget`: one select-list or `RETURNING` item. */
  const val RESULT_TARGET = "ResTarget"

  /** `ResTarget.name`: the item's alias, set for both `AS name` and a bare trailing name. */
  const val ALIAS = "name"

  /** `ResTarget.val`: the item's expression. */
  const val VALUE = "val"

  /** `ResTarget.location`, and the location field of every other node: the `String` index where the node starts. */
  const val LOCATION = "location"

  /** `A_Const`: a literal constant. */
  const val CONSTANT = "A_Const"

  /** `ColumnRef`: a column reference, qualified or not. */
  const val COLUMN_REFERENCE = "ColumnRef"

  /** `ColumnRef.fields`: the dot-separated parts of a column reference. */
  const val FIELDS = "fields"

  /** `String`: an identifier part, which holds [STRING_VALUE]. */
  const val STRING = "String"

  /** `String.sval`: the identifier with quotes removed, escapes resolved and unquoted letters folded. */
  const val STRING_VALUE = "sval"

  /** `A_Star`: the `*` in `*` or `table.*`. */
  const val STAR = "A_Star"

  /** `A_Indirection`: an expression followed by field selections or subscripts, such as `(f(x)).*`. */
  const val INDIRECTION_NODE = "A_Indirection"

  /** `A_Indirection.indirection`: the selections and subscripts applied to the expression. */
  const val INDIRECTION = "indirection"
}
