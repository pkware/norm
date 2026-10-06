package norm.generator

/**
 * Describes what PostgreSQL implies about whether a `$n` parameter may be `null`.
 */
internal sealed interface ParameterNullability {

  /**
   * The parameter is written to a column, so the column's nullability decides.
   *
   * @property column the column that the parameter is written to.
   */
  data class Inherit(val column: ColumnReference) : ParameterNullability

  /** PostgreSQL accepts `null` here and gives it a meaning. */
  data object Nullable : ParameterNullability

  /** PostgreSQL rejects `null` here, or `null` can never satisfy the condition the parameter is in. */
  data object NonNull : ParameterNullability
}

/**
 * Names a relation by its schema and its name.
 *
 * @property schema the `pg_namespace.nspname` of the schema that holds the relation.
 * @property name the `pg_class.relname` of the relation.
 */
internal data class RelationName(val schema: String, val name: String)

/**
 * Names a column and the relation that holds it.
 *
 * @property schema the schema of [table].
 * @property table the relation that holds [column].
 * @property column the column name.
 */
internal data class ColumnReference(val schema: String, val table: String, val column: String)

/**
 * Describes what is inferred about a query parameter.
 *
 * @property name the name for the generated parameter, `null` when nothing names it. It is the column the parameter
 *   is compared with, or else the column of its assignment. A function argument name replaces either.
 * @property nullability what PostgreSQL implies about `null`, `null` when it implies nothing.
 * @property identity the column whose type, overrides and comment the parameter takes, `null` when it takes none.
 *   It is the assignment target for the column's own value. For an operand, it is the compared column when the
 *   operand has the type the operator receives for it.
 */
internal data class InferredParameter(
  val name: String?,
  val nullability: ParameterNullability?,
  val identity: ColumnReference? = null,
)

/**
 * Decides whether each `$n` parameter of a statement may be `null`, from the statement's parsed query tree.
 *
 * The walk starts at the root and visits every node. A node kind walks the fields it lists in its own context and
 * every other field with no opinion.
 *
 * The walk carries two independent parts of context. The null mode records what PostgreSQL does with the value of
 * the current node.
 * - `VALUE` means the value is written to a column, so the column's nullability decides.
 * - `ELEMENT` means the value is stored in an array element or composite field, which accepts `null` even in a
 *   `NOT NULL` column.
 * - `CONDITION` means the value decides a condition, where `null` counts as not true, or is a subscript, where
 *   `null` is an error.
 * - `FREE` means PostgreSQL gives `null` a meaning, as for the operand of `IS NULL` or a `coalesce` fallback.
 * - `UNKNOWN` means no opinion. A parameter in such a position has no nullability, but a comparison still names it.
 *
 * A rule that changes the null mode leaves `UNKNOWN` as it is. Only a query, a `FROM` or `JOIN` qualifier, a `NULL`
 * or boolean test, `IS DISTINCT FROM`, `coalesce`, and `NULLIF` set a mode of their own wherever they appear.
 *
 * The assignment target is the column that a `SET` item or an `INSERT` value writes to. A change of null mode leaves
 * the target alone. A parameter under an assignment is named after the target column.
 *
 * A `boolean` operator with two operands names a parameter after the column on its other side. The parameter must
 * reach the operator through coercions or choices between values. The pattern of `like_escape` counts as such a
 * path. Choices between values are `coalesce` and `CASE` results. All of this holds in every clause and null mode.
 *
 * The column resolves against the range table of the query that holds the `VAR`. A positive `varlevelsup` selects an
 * enclosing query. The name is the `eref` column name of the entry.
 *
 * A join column resolves through `joinaliasvars` to the base relation it comes from. A group column resolves through
 * `groupexprs` to the entry its `VAR` references and takes its name from there. A join column that comes from an
 * expression has no identity, as for the merged column of a `FULL JOIN`. A group column that comes from an
 * expression has no name.
 *
 * A parameter whose own type is a `NOT NULL` domain, or a domain over one, rejects `null` in every mode but
 * `UNKNOWN`. Operators and functions keep the null mode whatever their strictness, so a function that raises on
 * `null` is classified as if it accepted it. A `FREE` position applies in every statement, so a `coalesce` in a
 * `SELECT` list accepts `null`.
 *
 * The null mode is direct while the node is still the datum written to the column or element. Only coercions and
 * casts keep it direct. Constructor elements are elements only in a direct mode, because in `1 = ANY(ARRAY[?, 2])`
 * the array is an operand and nothing is stored.
 *
 * A parameter takes the identity of its target column, which selects the generated type, when it is the column's
 * own value and its type is the column's type or one in that type's domain chain. The own value survives coercions
 * and choices between values such as `coalesce`, but not the increment in `a = a + 1 + ?`. A parameter compared
 * with a column of a base relation can take that column's identity. Its type must be the type the operator receives
 * for the column.
 *
 * @property isNotNullDomain whether the `pg_type` OID names a domain declared `NOT NULL`, or a domain over one.
 * @property columnTypeOid the type of the attribute with the given number, `null` when unknown.
 * @property typeChain the type and every type in its `typbasetype` chain.
 * @property attributeName the name of the attribute with the given number, `null` when unknown.
 * @property relation the schema and name of the relation with the given `pg_class` OID, `null` when unknown.
 * @property isLikeEscape whether the `pg_proc` OID names `pg_catalog.like_escape`, the function PostgreSQL calls on
 *   the pattern of `LIKE ... ESCAPE`.
 */
internal class ParameterNullabilityClassifier(
  private val isNotNullDomain: (typeOid: Int) -> Boolean,
  private val columnTypeOid: (relid: Int, attno: Int) -> Int?,
  private val typeChain: (typeOid: Int) -> Set<Int>,
  private val attributeName: (relid: Int, attno: Int) -> String?,
  private val relation: (relid: Int) -> RelationName?,
  private val isLikeEscape: (functionOid: Int) -> Boolean,
) {

  private enum class Kind { VALUE, ELEMENT, CONDITION, FREE, UNKNOWN }

  private data class Target(val column: String, val relation: RelationName?, val typeOid: Int?) {
    val reference: ColumnReference? get() = relation?.let { ColumnReference(it.schema, it.name, column) }
  }

  private data class Compared(val name: String, val column: ColumnReference?, val receivedType: Int?)

  /**
   * Carries the state of the walk at one node.
   *
   * @property kind the null mode.
   * @property target the assignment target the node sits under, `null` outside an assignment.
   * @property direct whether the node is still the datum written to the column or element.
   * @property ownValue whether the node is still the column's own value, which an operator or a constructor
   *   destroys and a choice between values keeps.
   * @property source the column that a parameter operand is compared with, kept as long as the node is that operand
   *   or a coercion or choice between values around the parameter.
   */
  private data class Context(
    val kind: Kind,
    val target: Target? = null,
    val direct: Boolean = false,
    val ownValue: Boolean = false,
    val source: Compared? = null,
  ) {
    fun mode(newKind: Kind) = if (kind == Kind.UNKNOWN) this else copy(kind = newKind, direct = false)

    fun free() = copy(kind = Kind.FREE, direct = false)

    fun opaque() = copy(direct = false, ownValue = false, source = null)

    fun choice() = copy(direct = false)

    fun element() = if (kind == Kind.UNKNOWN) {
      this
    } else {
      copy(
        kind = Kind.ELEMENT,
        target = target?.copy(relation = null),
        direct = true,
        ownValue = false,
        source = null,
      )
    }

    fun constructorElements(): Context =
      if (direct && (kind == Kind.VALUE || kind == Kind.ELEMENT)) element() else opaque()

    companion object {
      val NONE = Context(Kind.UNKNOWN)

      val CONDITION = Context(Kind.CONDITION)
    }
  }

  /**
   * Classifies the parameters of a statement.
   *
   * @param nodeTree the `pg_proc.prosqlbody` text of a function whose body is the statement, with each `$n`
   *   standing for the statement's `n`th `?` placeholder.
   * @return the parameters PostgreSQL implies something about or that a comparison names, keyed by 1-based parameter
   *   number; empty when [nodeTree] cannot be read.
   */
  fun classify(nodeTree: String): Map<Int, InferredParameter> {
    val root = try {
      parsePgNodeValue(nodeTree)
    } catch (_: IllegalArgumentException) {
      return emptyMap()
    }
    val inferred = mutableMapOf<Int, InferredParameter>()
    Walk(inferred).value(root, Context.NONE)
    return inferred
  }

  private inner class Walk(private val inferred: MutableMap<Int, InferredParameter>) {

    /** Holds the range table of each query being walked, the innermost last. */
    private val rangeTables = ArrayDeque<List<PgNodeValue.Node>>()

    /** Tracks the fields a rule walked, so the walk of the remaining fields skips them. */
    private inner class Fields(private val node: PgNodeValue.Node) {
      private val walked = mutableSetOf<String>()

      fun node(field: String): PgNodeValue.Node? {
        walked += field
        return node.node(field)
      }

      fun nodes(field: String): List<PgNodeValue.Node> {
        walked += field
        return node.nodes(field)
      }

      fun walkNode(field: String, context: Context) {
        node(field)?.let { walk(it, context) }
      }

      fun walkNodes(field: String, context: Context) {
        for (child in nodes(field)) walk(child, context)
      }

      fun walkRemaining() {
        for ((field, values) in node.fields) {
          if (field in walked) continue
          for (child in values) value(child, Context.NONE)
        }
      }
    }

    fun value(value: PgNodeValue, context: Context) {
      when (value) {
        is PgNodeValue.Atom -> Unit
        is PgNodeValue.Sequence -> for (item in value.items) value(item, context)
        is PgNodeValue.Node -> walk(value, context)
      }
    }

    private fun walk(node: PgNodeValue.Node, context: Context) {
      if (node.name != "QUERY") return walkNode(node, context)
      rangeTables.addLast(node.nodes("rtable"))
      try {
        walkNode(node, context)
      } finally {
        rangeTables.removeLast()
      }
    }

    private fun walkNode(node: PgNodeValue.Node, context: Context) {
      val fields = Fields(node)
      when (node.name) {
        "PARAM" -> classifyParameter(node, context)

        in COERCIONS -> fields.walkNode("arg", context)

        "FUNCEXPR" -> walkFunction(fields, node, context)

        "OPEXPR" -> walkOperands(fields, node, context)

        "SCALARARRAYOPEXPR" -> fields.walkNodes("args", context.opaque())

        "COERCETODOMAIN" -> {
          val isNotNull = node.int("resulttype")?.let(isNotNullDomain) == true
          fields.walkNode("arg", if (isNotNull) context.mode(Kind.CONDITION) else context)
        }

        "NULLTEST", "BOOLEANTEST" -> fields.walkNode("arg", context.free().opaque())

        "DISTINCTEXPR" -> fields.walkNodes("args", context.free().opaque())

        "COALESCEEXPR" -> {
          val arguments = fields.nodes("args")
          for ((index, argument) in arguments.withIndex()) {
            walk(argument, if (index < arguments.lastIndex) context.free() else context.choice())
          }
        }

        "NULLIFEXPR" -> {
          val arguments = fields.nodes("args")
          arguments.getOrNull(0)?.let { walk(it, context.choice()) }
          arguments.getOrNull(1)?.let { walk(it, context.free().opaque()) }
        }

        "MINMAXEXPR" -> fields.walkNodes("args", context.choice())

        "BOOLEXPR" -> fields.walkNodes("args", context.opaque())

        "CASEEXPR" -> walkCase(fields, context)

        "SUBSCRIPTINGREF" -> walkSubscript(fields, context)

        "FIELDSTORE" -> {
          fields.walkNodes("newvals", context.element())
          fields.walkNode("arg", context.opaque())
        }

        "ARRAYEXPR" -> fields.walkNodes("elements", context.constructorElements())

        "ROWEXPR" -> fields.walkNodes("args", context.constructorElements())

        "SUBLINK" -> fields.walkNode("testexpr", context.opaque())

        "QUERY" -> walkQuery(fields, node)

        "FROMEXPR", "JOINEXPR" -> fields.walkNode("quals", Context.CONDITION)
      }
      fields.walkRemaining()
    }

    private fun walkQuery(fields: Fields, query: PgNodeValue.Node) {
      when (query.int("commandType")) {
        COMMAND_TYPE_UPDATE -> assignAll(fields.nodes("targetList"), query, isInsert = false)
        COMMAND_TYPE_INSERT -> assignAll(fields.nodes("targetList"), query, isInsert = true)
      }
      for (action in fields.nodes("mergeActionList")) {
        val actionFields = Fields(action)
        if (action.int("commandType") == COMMAND_TYPE_UPDATE) {
          assignAll(actionFields.nodes("targetList"), query, isInsert = false)
        }
        actionFields.walkNode("qual", Context.CONDITION)
        actionFields.walkRemaining()
      }
      fields.walkNode("mergeJoinCondition", Context.CONDITION)
      fields.walkNode("havingQual", Context.CONDITION)
      fields.node("onConflict")?.let { onConflict ->
        val onConflictFields = Fields(onConflict)
        assignAll(onConflictFields.nodes("onConflictSet"), query, isInsert = false)
        onConflictFields.walkNode("onConflictWhere", Context.CONDITION)
        onConflictFields.walkRemaining()
      }
    }

    /**
     * Walks the value of each entry of a target list as the value of the entry's column in the result relation of
     * [query]. A multi-row `INSERT ... VALUES` walks every row.
     */
    private fun assignAll(entries: List<PgNodeValue.Node>, query: PgNodeValue.Node, isInsert: Boolean) {
      val relid = query.nodes("rtable").getOrNull((query.int("resultRelation") ?: 0) - 1)?.int("relid")
      val targetRelation = relid?.let(relation)
      for (entry in entries) {
        val column = entry.atom("resname")
        val expression = entry.node("expr") ?: continue
        if (entry.boolean("resjunk") == true || column == null) {
          walk(expression, Context.NONE)
          continue
        }
        val typeOid = relid?.let { relationId -> entry.int("resno")?.let { columnTypeOid(relationId, it) } }
        val context = Context(Kind.VALUE, Target(column, targetRelation, typeOid), direct = true, ownValue = true)
        val values = if (isInsert) insertedValues(expression, query) else listOf(expression)
        for (value in values) walk(value, context)
      }
    }

    /**
     * Returns the expressions that [expression], a target entry of an `INSERT`, writes to its column. The entry of a
     * multi-row `VALUES` refers to the `VALUES` range table entry. The `k`th item of each row feeds the entry whose
     * `VAR` has `varattno` `k`. Any other entry writes itself.
     */
    private fun insertedValues(expression: PgNodeValue.Node, query: PgNodeValue.Node): List<PgNodeValue.Node> {
      val variable = firstVariable(expression) ?: return listOf(expression)
      val entry = query.nodes("rtable").getOrNull((variable.int("varno") ?: 0) - 1)
      if (entry == null || entry.int("rtekind") != RTE_VALUES) return listOf(expression)
      return entry.nodeLists("values_lists").map { row -> replaceVariables(expression, row) as PgNodeValue.Node }
    }

    private fun firstVariable(value: PgNodeValue): PgNodeValue.Node? = when (value) {
      is PgNodeValue.Atom -> null
      is PgNodeValue.Sequence -> value.items.firstNotNullOfOrNull(::firstVariable)
      is PgNodeValue.Node ->
        if (value.name == "VAR") value else value.fields.values.flatten().firstNotNullOfOrNull(::firstVariable)
    }

    private fun replaceVariables(value: PgNodeValue, row: List<PgNodeValue.Node?>): PgNodeValue = when (value) {
      is PgNodeValue.Atom -> value
      is PgNodeValue.Sequence -> PgNodeValue.Sequence(value.items.map { replaceVariables(it, row) })
      is PgNodeValue.Node ->
        if (value.name == "VAR") {
          row.getOrNull((value.int("varattno") ?: 0) - 1) ?: value
        } else {
          PgNodeValue.Node(value.name, value.fields.mapValues { (_, items) -> items.map { replaceVariables(it, row) } })
        }
    }

    /** Walks the arguments of a function. A cast passes the context on. So does the pattern of `like_escape`. */
    private fun walkFunction(fields: Fields, node: PgNodeValue.Node, context: Context) {
      if (node.isCast()) return fields.walkNodes("args", context)
      val passesFirstArgument = node.int("funcid")?.let(isLikeEscape) == true
      for ((index, argument) in fields.nodes("args").withIndex()) {
        walk(argument, if (passesFirstArgument && index == 0) context else context.opaque())
      }
    }

    private fun walkCase(fields: Fields, context: Context) {
      fields.walkNode("arg", context.mode(Kind.CONDITION).opaque())
      for (branch in fields.nodes("args")) {
        val branchFields = Fields(branch)
        branchFields.walkNode("expr", context.mode(Kind.CONDITION).opaque())
        branchFields.walkNode("result", context.choice())
        branchFields.walkRemaining()
      }
      fields.walkNode("defresult", context.choice())
    }

    private fun walkSubscript(fields: Fields, context: Context) {
      val assigned = fields.node("refassgnexpr")
      val indexContext = if (assigned == null) context.opaque() else context.mode(Kind.CONDITION).opaque()
      fields.walkNodes("refupperindexpr", indexContext)
      fields.walkNodes("reflowerindexpr", indexContext)
      assigned?.let { walk(it, context.element()) }
      fields.walkNode("refexpr", context.opaque())
    }

    /** Walks the operands of an operator. Each operand of a two-operand `boolean` operator is compared with the other. */
    private fun walkOperands(fields: Fields, node: PgNodeValue.Node, context: Context) {
      val operands = fields.nodes("args")
      val compares = node.int("opresulttype") == BOOLEAN_TYPE && operands.size == 2
      for ((index, operand) in operands.withIndex()) {
        val source = if (compares) comparedColumn(operands[1 - index]) else null
        walk(operand, context.opaque().copy(source = source))
      }
    }

    /**
     * Returns the column that [node] is when only binary-compatible `RELABELTYPE` nodes wrap it, or `null` when it is
     * anything else. The type the operator receives is the one of the outermost `RELABELTYPE`.
     */
    private fun comparedColumn(node: PgNodeValue.Node): Compared? {
      var variable = node
      var receivedType: Int? = null
      while (variable.name == RELABEL) {
        if (receivedType == null) receivedType = variable.int("resulttype")
        variable = variable.node("arg") ?: return null
      }
      if (variable.name != "VAR") return null
      var attno = variable.int("varattno")?.takeIf { it != 0 } ?: return null
      val rangeTable = rangeTables.getOrNull(rangeTables.lastIndex - (variable.int("varlevelsup") ?: 0)) ?: return null
      var entry = rangeTable.getOrNull((variable.int("varno") ?: 0) - 1) ?: return null
      while (entry.int("rtekind") == RTE_GROUP) {
        val grouped = plainVariable(entry.nodes("groupexprs").getOrNull(attno - 1)) ?: return null
        attno = grouped.int("varattno")?.takeIf { it != 0 } ?: return null
        entry = rangeTable.getOrNull((grouped.int("varno") ?: 0) - 1) ?: return null
      }
      val column = baseColumn(rangeTable, entry, attno)
      val name = entry.node("eref")?.strings("colnames")?.getOrNull(attno - 1)?.takeIf { attno > 0 && it != EMPTY }
        ?: column?.column
        ?: return null
      return Compared(name, column, receivedType ?: variable.int("vartype"))
    }

    /**
     * Returns attribute [attno] of [entry] of [rangeTable] as a column of a base relation, or `null` when it is
     * anything else. A join column resolves through `joinaliasvars`. Resolution continues while the result is a plain
     * `VAR` of the same query, possibly under `RELABELTYPE`.
     */
    private fun baseColumn(rangeTable: List<PgNodeValue.Node>, entry: PgNodeValue.Node, attno: Int): ColumnReference? {
      var current = entry
      var currentAttno = attno
      while (true) {
        val expressions = when (current.int("rtekind")) {
          RTE_RELATION -> return relationColumn(current, currentAttno)
          RTE_JOIN -> current.nodes("joinaliasvars")
          else -> return null
        }
        val variable = plainVariable(expressions.getOrNull(currentAttno - 1)) ?: return null
        currentAttno = variable.int("varattno")?.takeIf { it != 0 } ?: return null
        current = rangeTable.getOrNull((variable.int("varno") ?: 0) - 1) ?: return null
      }
    }

    /** Returns [expression] when it is a `VAR` of the same query, possibly under `RELABELTYPE`, else `null`. */
    private fun plainVariable(expression: PgNodeValue.Node?): PgNodeValue.Node? {
      var variable = expression
      while (variable?.name == RELABEL) variable = variable.node("arg")
      return variable?.takeIf { it.name == "VAR" && (it.int("varlevelsup") ?: 0) == 0 }
    }

    private fun relationColumn(entry: PgNodeValue.Node, attno: Int): ColumnReference? {
      val relid = entry.int("relid") ?: return null
      val relationName = relation(relid) ?: return null
      return ColumnReference(relationName.schema, relationName.name, attributeName(relid, attno) ?: return null)
    }

    private fun classifyParameter(node: PgNodeValue.Node, context: Context) {
      if (node.int("paramkind") != PARAM_KIND_EXTERNAL) return
      val id = node.int("paramid") ?: return
      val parameterType = node.int("paramtype")
      val target = context.target
      val compared = context.source
      val nullability = when {
        context.kind == Kind.UNKNOWN -> null
        parameterType?.let(isNotNullDomain) == true -> ParameterNullability.NonNull
        context.kind == Kind.VALUE -> target?.reference?.let(ParameterNullability::Inherit)
        context.kind == Kind.ELEMENT || context.kind == Kind.FREE -> ParameterNullability.Nullable
        else -> ParameterNullability.NonNull
      }
      val hasColumnType = context.ownValue &&
        target?.relation != null &&
        target.typeOid != null &&
        parameterType != null &&
        parameterType in typeChain(target.typeOid)
      val hasComparedType = compared != null && parameterType != null && parameterType == compared.receivedType
      val identity = when {
        hasComparedType -> compared.column
        hasColumnType -> target.reference
        else -> null
      }
      val name = compared?.name ?: target?.column
      if (name == null && nullability == null) return
      val existing = inferred[id]
      if (existing == null || nullability.restrictiveness() > existing.nullability.restrictiveness()) {
        inferred[id] = InferredParameter(name, nullability, identity)
      }
    }
  }

  private fun PgNodeValue.Node.isCast() = name == "FUNCEXPR" && int("funcformat") in CAST_FUNCTION_FORMATS

  private fun ParameterNullability?.restrictiveness(): Int = when (this) {
    ParameterNullability.NonNull -> 2
    is ParameterNullability.Inherit -> 1
    ParameterNullability.Nullable -> 0
    null -> -1
  }

  private companion object {
    const val COMMAND_TYPE_UPDATE = 2
    const val COMMAND_TYPE_INSERT = 3
    const val PARAM_KIND_EXTERNAL = 0
    const val RTE_RELATION = 0
    const val RTE_JOIN = 2
    const val RTE_GROUP = 9
    const val RTE_VALUES = 5
    const val BOOLEAN_TYPE = 16
    const val RELABEL = "RELABELTYPE"

    /** Stands for an empty string in `pg_node_tree` text. */
    const val EMPTY = "<>"

    /** Lists the node kinds that pass their `arg` through unchanged. */
    val COERCIONS = setOf(RELABEL, "COERCEVIAIO", "COLLATEEXPR", "ARRAYCOERCEEXPR")

    /** Holds the `CoercionForm` values `COERCE_EXPLICIT_CAST` and `COERCE_IMPLICIT_CAST`. */
    val CAST_FUNCTION_FORMATS = 1..2
  }
}
