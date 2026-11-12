package norm.generator.libpgquery

import norm.generator.JsonValue

private const val NO_LOCATION = -1

private val INTO_NAME_PARTS = listOf("catalogname", "schemaname", "relname")

/**
 * The root of the parse tree that [LibPgQuery.parse] returns.
 *
 * This and the other views in this file read the [JsonValue.JsonObject] they wrap. A field that is missing yields
 * `null` or an empty list, so a view throws only where its member says so.
 *
 * Instances are not thread-safe.
 */
internal class ParseResult(val node: JsonValue.JsonObject) {

  /** Every statement of the parsed text, in source order. Each tree node has one [StatementNode]. */
  val statements: List<StatementNode> by lazy(LazyThreadSafetyMode.NONE) {
    node.objectArrayField("stmts")
      .mapNotNull { it.objectField("stmt") }
      .map(::StatementNode)
  }

  /** The first of [statements], which is `null` for text with no statement. */
  val firstStatement: StatementNode?
    get() = statements.firstOrNull()

  /** The first of [statements] that returns a result set, or `null` when none does. */
  val outputStatement: OutputStatement?
    get() = statements.firstNotNullOfOrNull { it.outputStatement }

  /**
   * Finds the select-list or `RETURNING` item that starts at [location], searching in document order.
   *
   * @return the first node there with a value, or `null` when there is none.
   */
  fun resultTargetAt(location: Int): ResultTarget? = findItemAt(node, location)?.let(::ResultTarget)

  /**
   * Copies this tree with every location field removed and the value of the item that starts at [location] set to
   * `null`. Copies from texts of different length are equal when only that value differs.
   */
  fun withoutItemValueAndLocations(location: Int): JsonValue = withoutItemValueAndLocations(node, location)
}

/**
 * An object with one field named for its kind, such as the `stmt` of a statement or the body of a CTE.
 *
 * Instances are not thread-safe. Equality is identity, and [ParseResult] and [commonTableExpressions] hand out one
 * instance per tree node.
 */
internal class StatementNode(val node: JsonValue.JsonObject) {
  private val typeName: String?
  private val body: JsonValue.JsonObject?

  init {
    val field = node.fields.entries.singleOrNull()
    typeName = field?.key
    body = field?.value as? JsonValue.JsonObject
  }

  /** `null` unless the statement is a `SELECT`, `VALUES`, `TABLE` or set operation. */
  val select: SelectStatement?
    get() = body?.takeIf { typeName == "SelectStmt" }?.let(::SelectStatement)

  val explain: ExplainStatement?
    get() = body?.takeIf { typeName == "ExplainStmt" }?.let(::ExplainStatement)

  val call: CallStatement?
    get() = body?.takeIf { typeName == "CallStmt" }?.let(::CallStatement)

  /**
   * Lists the CTEs in declaration order. Only this statement's own `WITH` clause counts, so a `WITH` inside a CTE body
   * or a subquery adds none. Empty when the statement has no `WITH` clause.
   *
   * @throws IllegalStateException if a CTE has no name or no body.
   */
  @get:Throws(IllegalStateException::class)
  val commonTableExpressions: List<CommonTableExpression> by lazy(LazyThreadSafetyMode.NONE) {
    body?.objectField("withClause")
      ?.objectArrayField("ctes")
      .orEmpty()
      .mapNotNull { it.objectField("CommonTableExpr") }
      .map { expression ->
        CommonTableExpression(
          name = checkNotNull(expression.stringField("ctename")) {
            "A CTE has no name"
          },
          query = StatementNode(
            checkNotNull(expression.objectField("ctequery")) {
              "A CTE has no body"
            },
          ),
        )
      }
  }

  /**
   * The `RETURNING` items of an `INSERT`, `UPDATE`, `DELETE` or `MERGE`, or `null` when the statement is another kind
   * or has no `RETURNING` list.
   */
  val returningTargets: List<ResultTarget>?
    get() {
      when (typeName) {
        "InsertStmt", "UpdateStmt", "DeleteStmt", "MergeStmt" -> Unit
        else -> return null
      }
      val items = body?.objectField("returningClause")?.objectArrayField("exprs").orEmpty()
      return if (items.isEmpty()) null else resultTargets(items)
    }

  /**
   * Reads the result set that this statement returns.
   *
   * A `SELECT`, `VALUES`, `TABLE` or set operation produces one, as does a data-modifying statement with a `RETURNING`
   * list. An `EXPLAIN` has no targets, since its result set is the `QUERY PLAN` column. Every other statement, and a
   * data-modifying one without `RETURNING`, yields `null`.
   */
  val outputStatement: OutputStatement? by lazy(LazyThreadSafetyMode.NONE) {
    val selectStatement = select
    when {
      selectStatement != null -> {
        val leaf = selectStatement.leftmostLeaf
        val targets = if (leaf != null && !leaf.hasValuesLists) leaf.targets else emptyList()
        OutputStatement(this, targets, selectStatement.isSetOperation)
      }
      explain != null -> OutputStatement(this, emptyList(), false)
      else -> returningTargets?.let { OutputStatement(this, it, false) }
    }
  }
}

/**
 * Pairs a statement with the items of the result set it returns.
 *
 * @property node the statement.
 * @property targets the items of its select list or `RETURNING` list, in source order. Empty for `VALUES` and
 *   `EXPLAIN`. `TABLE t` holds one star item that the parser adds, with location `-1`.
 * @property isSetOperation whether the statement is a `UNION`, `INTERSECT` or `EXCEPT`. [targets] then belong to the
 *   leftmost branch.
 */
internal class OutputStatement(val node: StatementNode, val targets: List<ResultTarget>, val isSetOperation: Boolean)

/**
 * A CTE of a `WITH` clause.
 *
 * @property name the name as PostgreSQL's parser stores it, equal to the name the server reports.
 * @property query the CTE body.
 */
internal data class CommonTableExpression(val name: String, val query: StatementNode)

/** A `SelectStmt`, which is a `SELECT`, `VALUES`, `TABLE` or a set operation over those. */
@JvmInline
internal value class SelectStatement(val node: JsonValue.JsonObject) {

  val isSetOperation: Boolean
    get() {
      val operation = node.stringField("op")
      return operation != null && operation != "SETOP_NONE"
    }

  /** Follows the left branch of set operations down to a node that is not one. `null` when a branch is missing. */
  val leftmostLeaf: SelectStatement?
    get() {
      var current = this
      while (current.isSetOperation) {
        current = current.node.objectField("larg")?.let(::SelectStatement) ?: return null
      }
      return current
    }

  /** Whether this node is a `VALUES` list, whose rows stand where a `SELECT` has a select list. */
  val hasValuesLists: Boolean
    get() = node.fields.containsKey("valuesLists")

  /** The select list of this node, empty for `VALUES` and for a set operation. */
  val targets: List<ResultTarget>
    get() = resultTargets(node.objectArrayField("targetList"))

  /** Names the table that `SELECT ... INTO` creates. `null` when this node has no `INTO` clause. */
  val intoRelation: IntoRelation?
    get() = node.objectField("intoClause")
      ?.objectField("rel")
      ?.let(::IntoRelation)
}

/** A `ResTarget`: one item of a select list or `RETURNING` list. */
@JvmInline
internal value class ResultTarget(val node: JsonValue.JsonObject) {

  /** The `String` index where the item starts, `-1` for an item the parser adds as in `TABLE t`, `null` if absent. */
  val location: Int?
    get() = node.intField("location")

  /**
   * The alias as PostgreSQL's parser stores it, or `null` when the item has none. An unquoted alias is folded to
   * lowercase. A quoted alias has its quotes removed and each doubled `""` collapsed to `"`.
   */
  val alias: String?
    get() = node.stringField("name")

  /** The expression node of the item, or `null` when the item has none. */
  val value: JsonValue.JsonObject?
    get() = node.objectField("val")

  /** Whether the item is `*`, `table.*` or a composite expansion such as `(f(x)).*`. */
  val isStar: Boolean
    get() {
      val expression = value ?: return false
      val columnFields = expression.objectField("ColumnRef")
        ?.objectArrayField("fields")
      if (columnFields != null) return columnFields.lastOrNull()?.objectField("A_Star") != null
      val indirection = expression.objectField("A_Indirection")
        ?.objectArrayField("indirection")
      return indirection?.lastOrNull()?.objectField("A_Star") != null
    }

  /**
   * Holds the names of a one- or two-part column reference, as PostgreSQL's parser stores them. `null` for any other
   * expression, including a star.
   */
  val columnReferenceNames: List<String>?
    get() {
      val fields = value?.objectField("ColumnRef")
        ?.objectArrayField("fields") ?: return null
      if (fields.size !in 1..2) return null
      return fields.map {
        it.objectField("String")?.stringField("sval") ?: return null
      }
    }

  /** The greatest location field anywhere in the item, or `-1` when there is none. */
  val largestLocation: Int
    get() = largestLocation(node)

  /** Whether the value is a lone literal constant that starts at [location]. */
  fun hasConstantValueAt(location: Int): Boolean {
    val expression = value ?: return false
    val constant = expression.objectField("A_Const")
    return expression.fields.size == 1 && constant?.intField("location") == location
  }
}

/** The `RangeVar` that names the table of a `SELECT ... INTO`. */
@JvmInline
internal value class IntoRelation(val node: JsonValue.JsonObject) {

  /** The `String` index where the table name starts, or `null` when the tree records none. */
  val location: Int?
    get() = node.intField("location")

  /** Counts the written parts among database, schema and table name, which is one to three. */
  val partCount: Int
    get() = INTO_NAME_PARTS.count { node.stringField(it) != null }
}

/** An `ExplainStmt`. */
@JvmInline
internal value class ExplainStatement(val node: JsonValue.JsonObject) {

  /** Each option counts once, including the legacy words `ANALYZE` and `VERBOSE`. */
  val optionCount: Int
    get() = node.objectArrayField("options").size
}

/** A `CallStmt`. */
@JvmInline
internal value class CallStatement(val node: JsonValue.JsonObject) {

  /** The name as `pg_proc` stores it, or `null` when the call writes a schema part. */
  val procedureName: String?
    get() = node.objectField("funccall")
      ?.objectArrayField("funcname")
      ?.singleOrNull()
      ?.objectField("String")
      ?.stringField("sval")
}

private fun resultTargets(items: List<JsonValue.JsonObject>): List<ResultTarget> =
  items.mapNotNull { it.objectField("ResTarget") }.map(::ResultTarget)

private fun isItemAt(value: JsonValue.JsonObject, location: Int): Boolean =
  value.intField("location") == location && value.fields.containsKey("val")

private fun findItemAt(value: JsonValue, location: Int): JsonValue.JsonObject? = when (value) {
  is JsonValue.JsonObject ->
    value.takeIf { isItemAt(it, location) } ?: value.fields.values.firstNotNullOfOrNull { findItemAt(it, location) }
  is JsonValue.JsonArray -> value.items.firstNotNullOfOrNull { findItemAt(it, location) }
  else -> null
}

private fun withoutItemValueAndLocations(value: JsonValue, location: Int): JsonValue = when (value) {
  is JsonValue.JsonObject -> {
    val isItem = isItemAt(value, location)
    JsonValue.JsonObject(
      value.fields.filterKeys { it !in locationFieldNames }.mapValues { (key, child) ->
        if (isItem && key == "val") {
          JsonValue.JsonScalar("null")
        } else {
          withoutItemValueAndLocations(child, location)
        }
      },
    )
  }
  is JsonValue.JsonArray -> JsonValue.JsonArray(value.items.map { withoutItemValueAndLocations(it, location) })
  else -> value
}

private fun largestLocation(value: JsonValue): Int = when (value) {
  is JsonValue.JsonObject -> maxOf(
    value.intField("location") ?: NO_LOCATION,
    value.fields.values.maxOfOrNull(::largestLocation) ?: NO_LOCATION,
  )
  is JsonValue.JsonArray -> value.items.maxOfOrNull(::largestLocation) ?: NO_LOCATION
  else -> NO_LOCATION
}
