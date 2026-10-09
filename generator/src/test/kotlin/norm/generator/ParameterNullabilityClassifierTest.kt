package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ParameterNullabilityClassifierTest {

  private val notNullDomain = 700
  private val domainOverInteger = 800
  private val relationNames = mapOf(
    16384 to RelationName("public", "t"),
    16385 to RelationName("other", "u"),
    16386 to RelationName("public", "d"),
  )
  private val likeEscapeFunction = 1637
  private val columnTypes = mapOf(16384 to 23, 16385 to 23, 16386 to domainOverInteger)

  private val classifier = ParameterNullabilityClassifier(
    isNotNullDomain = { it == notNullDomain },
    columnTypeOid = { relid, attno -> if (attno == 1 || attno == 2) columnTypes[relid] else null },
    typeChain = { type -> if (type == domainOverInteger) setOf(type, 23) else setOf(type) },
    attributeName = { relid, attno ->
      when (attno) {
        2 -> "k"
        1 -> "a"
        -1 -> "ctid"
        else -> null
      }
    },
    relation = relationNames::get,
    isLikeEscape = { it == likeEscapeFunction },
  )

  private val constantNode = "{CONST :consttype 23 :constisnull false}"
  private val targetColumn = ColumnReference("public", "t", "a")
  private val inherit = InferredParameter("a", ParameterNullability.Inherit(targetColumn), targetColumn)
  private val inheritMismatch = inherit.copy(identity = null)
  private val nullableInColumn = InferredParameter("a", ParameterNullability.Nullable, targetColumn)
  private val nullableMismatch = nullableInColumn.copy(identity = null)
  private val elementNullable = InferredParameter("a", ParameterNullability.Nullable)
  private val nonNull = InferredParameter(null, ParameterNullability.NonNull)
  private val nonNullOfColumn = InferredParameter("a", ParameterNullability.NonNull, targetColumn)
  private val nonNullOpaque = nonNullOfColumn.copy(identity = null)
  private val nonNullElement = InferredParameter("a", ParameterNullability.NonNull)
  private val nullableInCondition = InferredParameter(null, ParameterNullability.Nullable)

  private fun parameter(id: Int, kind: Int = 0, type: Int = 23) =
    "{PARAM :paramkind $kind :paramid $id :paramtype $type :location -1}"

  private fun entry(expression: String, column: String = "a", junk: Boolean = false) =
    "{TARGETENTRY :expr $expression :resno 1 :resname $column :resjunk $junk}"

  private fun relation(relid: Int) =
    "{RANGETBLENTRY :rtekind 0 :relid $relid :eref {ALIAS :aliasname r :colnames (\"a\" \"k\")}}"

  private fun fromExpression(quals: String = "<>") = "{FROMEXPR :fromlist <> :quals $quals}"

  private fun update(
    expression: String,
    column: String = "a",
    relid: Int = 16384,
    quals: String = "<>",
    extra: String = "",
  ) = "{QUERY :commandType 2 :resultRelation 1 :cteList <> :rtable (${relation(relid)}) " +
    ":jointree ${fromExpression(quals)} :targetList (${entry(expression, column)}) $extra :returningList <>}"

  private fun select(
    quals: String = "<>",
    targetList: String = "<>",
    fromList: String = "<>",
    having: String = "<>",
    rangeTable: String = "",
  ) = "{QUERY :commandType 1 :resultRelation 0 :cteList <> :rtable ($rangeTable) :jointree {FROMEXPR " +
    ":fromlist $fromList :quals $quals} :havingQual $having :targetList $targetList}"

  private fun classify(vararg queries: String): Map<Int, InferredParameter> =
    classifier.classify("((${queries.joinToString(" ")}))")

  private fun operation(vararg arguments: String) = "{OPEXPR :opfuncid 65 :args (${arguments.joinToString(" ")})}"

  private fun call(vararg arguments: String, format: Int = 0) =
    "{FUNCEXPR :funcid 100 :funcformat $format :args (${arguments.joinToString(" ")})}"

  @Nested
  inner class Roots {
    @Test
    fun `a junk target entry has no column and is skipped`() {
      val query = "{QUERY :commandType 2 :resultRelation 1 :cteList <> :rtable (${relation(16384)}) " +
        ":jointree ${fromExpression()} :targetList (${entry(parameter(1), junk = true)}) :returningList <>}"

      assertThat(classify(query)).isEmpty()
    }

    @Test
    fun `a MERGE update action writes to its column and an insert action has no opinion`() {
      val merge = "{QUERY :commandType 5 :resultRelation 1 :cteList <> :rtable (${relation(16384)}) " +
        ":jointree ${fromExpression()} :mergeJoinCondition <> :mergeActionList (" +
        "{MERGEACTION :matchKind 0 :commandType 2 :qual <> :targetList (${entry(parameter(1))})} " +
        "{MERGEACTION :matchKind 1 :commandType 3 :qual <> :targetList (${entry(parameter(2))})}) :targetList <>}"

      assertThat(classify(merge)).isEqualTo(mapOf(1 to inherit))
    }

    @Test
    fun `a MERGE action qualifier and the MERGE join condition are conditions`() {
      val merge = "{QUERY :commandType 5 :resultRelation 1 :cteList <> :rtable (${relation(16384)}) " +
        ":jointree ${fromExpression()} :mergeJoinCondition ${operation(parameter(1))} :mergeActionList (" +
        "{MERGEACTION :matchKind 0 :commandType 4 :qual ${operation(parameter(2))} :targetList <>}) :targetList <>}"

      assertThat(classify(merge)).isEqualTo(mapOf(1 to nonNull, 2 to nonNull))
    }

    @Test
    fun `an INSERT value and an ON CONFLICT set entry write to their columns, and ON CONFLICT WHERE is a condition`() {
      val insert = "{QUERY :commandType 3 :resultRelation 1 :cteList <> :rtable (${relation(16384)}) " +
        ":jointree ${fromExpression()} :targetList (${entry(parameter(1), "id")}) :onConflict {ONCONFLICTEXPR " +
        ":action 2 :onConflictSet (${entry(parameter(2))}) :onConflictWhere ${operation(parameter(3))}} " +
        ":returningList (${entry(parameter(4))})}"

      val insertedColumn = ColumnReference("public", "t", "id")
      assertThat(classify(insert)).isEqualTo(
        mapOf(
          1 to InferredParameter("id", ParameterNullability.Inherit(insertedColumn), insertedColumn),
          2 to inherit,
          3 to nonNull,
        ),
      )
    }

    @Test
    fun `FROM and JOIN qualifiers and HAVING are conditions`() {
      val join = "{JOINEXPR :jointype 0 :larg {RANGETBLREF :rtindex 1} :rarg {RANGETBLREF :rtindex 2} " +
        ":quals ${operation(parameter(2))}}"
      val query = select(quals = operation(parameter(1)), fromList = "($join)", having = operation(parameter(3)))

      assertThat(classify(query))
        .isEqualTo(mapOf(1 to nonNull, 2 to nonNull, 3 to nonNull))
    }

    @Test
    fun `SELECT and RETURNING lists have no opinion`() {
      val query = update(constantNode, extra = ":unused (${entry(parameter(2))})")
        .replace(":returningList <>", ":returningList (${entry(parameter(1))})")

      assertThat(classify(query)).isEmpty()
      assertThat(classify(select(targetList = "(${entry(parameter(1))})"))).isEmpty()
    }

    @Test
    fun `an unresolvable relation leaves the column named and gives no nullability`() {
      assertThat(classify(update(parameter(1), relid = 99999))).isEqualTo(mapOf(1 to InferredParameter("a", null)))
    }
  }

  @Nested
  inner class PassThroughNodes {
    @Test
    fun `coercions and collations keep the context and the column type`() {
      val wrapped = "{RELABELTYPE :arg {COERCEVIAIO :arg {COLLATEEXPR :arg {ARRAYCOERCEEXPR :arg ${parameter(1)}}}}}"

      assertThat(classify(update(wrapped))).isEqualTo(mapOf(1 to inherit))
    }

    @Test
    fun `a function that is a cast keeps the column type`() {
      assertThat(classify(update(call(parameter(1), format = 1)))).isEqualTo(mapOf(1 to inherit))
      assertThat(classify(update(call(parameter(1), format = 2)))).isEqualTo(mapOf(1 to inherit))
    }

    @Test
    fun `array operator arguments keep the context`() {
      val expression = "{SCALARARRAYOPEXPR :opfuncid 65 :useOr true :args (${parameter(1)} ${parameter(2)})}"

      assertThat(classify(update(expression))).isEqualTo(mapOf(1 to inheritMismatch, 2 to inheritMismatch))
    }

    @Test
    fun `GREATEST and BOOLEXPR keep the context`() {
      val minMax = "{MINMAXEXPR :args (${parameter(1)} ${parameter(2)})}"
      val boolean = "{BOOLEXPR :boolop or :args (${parameter(1)} {BOOLEXPR :boolop not :args (${parameter(2)})})}"

      assertThat(classify(update(minMax))).isEqualTo(mapOf(1 to inherit, 2 to inherit))
      assertThat(classify(select(quals = boolean))).isEqualTo(mapOf(1 to nonNull, 2 to nonNull))
    }
  }

  @Nested
  inner class FreeNodes {
    @Test
    fun `NULL tests, boolean tests and DISTINCT operands accept null`() {
      val nullTest = "{NULLTEST :arg ${parameter(1)} :nulltesttype 0}"
      val booleanTest = "{BOOLEANTEST :arg ${parameter(2)} :booltesttype 0}"
      val distinct = "{DISTINCTEXPR :args (${parameter(3)} ${parameter(4)})}"

      assertThat(classify(update(nullTest))).isEqualTo(mapOf(1 to nullableMismatch))
      assertThat(classify(update(booleanTest))).isEqualTo(mapOf(2 to nullableMismatch))
      assertThat(classify(update(distinct))).isEqualTo(mapOf(3 to nullableMismatch, 4 to nullableMismatch))
    }

    @Test
    fun `a NULL test in a condition is nullable without a column`() {
      val quals = "{BOOLEXPR :boolop or :args (${operation(parameter(1))} {NULLTEST :arg ${parameter(2)}})}"

      assertThat(classify(select(quals = quals)))
        .isEqualTo(mapOf(1 to nonNull, 2 to nullableInCondition))
    }

    @Test
    fun `every coalesce argument but the last accepts null and the last keeps the context`() {
      val coalesce = "{COALESCEEXPR :args (${parameter(1)} ${parameter(2)} ${parameter(3)})}"

      assertThat(classify(update(coalesce)))
        .isEqualTo(mapOf(1 to nullableInColumn, 2 to nullableInColumn, 3 to inherit))
    }

    @Test
    fun `NULLIF keeps the context for its first argument and accepts null in its second`() {
      val nullIf = "{NULLIFEXPR :opfuncid 65 :args (${parameter(1)} ${parameter(2)})}"

      assertThat(classify(update(nullIf))).isEqualTo(mapOf(1 to inherit, 2 to nullableMismatch))
    }
  }

  @Nested
  inner class Case {
    @Test
    fun `the CASE operand and WHEN conditions are conditions and the results keep the context`() {
      val caseExpression =
        "{CASEEXPR :arg ${parameter(1)} :args ({CASEWHEN :expr ${parameter(2)} :result ${parameter(3)}}) " +
          ":defresult ${parameter(4)}}"

      assertThat(classify(update(caseExpression))).isEqualTo(
        mapOf(1 to nonNullOpaque, 2 to nonNullOpaque, 3 to inherit, 4 to inherit),
      )
    }
  }

  @Nested
  inner class ArraysAndFields {
    private fun subscriptAssignment(upper: String, assigned: String, source: String = "{VAR :varno 1 :varattno 2}") =
      "{SUBSCRIPTINGREF :refupperindexpr ($upper) :reflowerindexpr <> :refexpr $source :refassgnexpr $assigned}"

    @Test
    fun `an assigned subscript index rejects null and the assigned value is an element`() {
      val assignment = subscriptAssignment(upper = parameter(1), assigned = parameter(2))

      assertThat(
        classify(update(assignment)),
      ).isEqualTo(mapOf(1 to nonNullOpaque, 2 to elementNullable))
    }

    @Test
    fun `a subscript read keeps the context for every child`() {
      val read = "{SUBSCRIPTINGREF :refupperindexpr (${parameter(1)}) :reflowerindexpr (${parameter(2)}) " +
        ":refexpr ${parameter(3)} :refassgnexpr <>}"

      assertThat(classify(update(read)))
        .isEqualTo(mapOf(1 to inheritMismatch, 2 to inheritMismatch, 3 to inheritMismatch))
    }

    @Test
    fun `a composite field store makes each new value an element`() {
      val store = "{FIELDSTORE :arg {VAR :varno 1 :varattno 2} :newvals (${parameter(1)} ${parameter(2)})}"

      assertThat(classify(update(store))).isEqualTo(
        mapOf(1 to elementNullable, 2 to elementNullable),
      )
    }

    @Test
    fun `an array or row constructor makes its elements elements in a value and in an element`() {
      val array = "{ARRAYEXPR :elements (${parameter(1)} $constantNode)}"
      val row = "{ROWEXPR :args (${parameter(2)} $constantNode)}"
      val nested = "{ARRAYEXPR :elements ({ARRAYEXPR :elements (${parameter(3)})})}"

      val elementOfValue = elementNullable
      assertThat(classify(update(array))).isEqualTo(mapOf(1 to elementOfValue))
      assertThat(classify(update(row))).isEqualTo(mapOf(2 to elementOfValue))
      assertThat(classify(update(nested))).isEqualTo(mapOf(3 to elementOfValue))
    }

    @Test
    fun `an array constructor in a condition keeps the condition for its elements`() {
      val quals = operation(parameter(1), "{ARRAYEXPR :elements (${parameter(2)} ${parameter(3)})}")

      assertThat(classify(select(quals = quals))).isEqualTo(mapOf(1 to nonNull, 2 to nonNull, 3 to nonNull))
    }

    @Test
    fun `an array constructor in no condition or value gives its elements no opinion`() {
      val array = "{ARRAYEXPR :elements (${parameter(1)} $constantNode)}"

      assertThat(classify(select(targetList = "(${entry(array)})"))).isEmpty()
    }
  }

  @Nested
  inner class DirectContext {
    private val array = "{ARRAYEXPR :elements (${parameter(1)} $constantNode)}"

    @Test
    fun `a constructor under an operator keeps the value context for its elements`() {
      val comparison = "{SCALARARRAYOPEXPR :opfuncid 65 :useOr true :args ($constantNode $array)}"

      assertThat(classify(update(comparison))).isEqualTo(mapOf(1 to inheritMismatch))
    }

    @Test
    fun `a constructor under a subscript read keeps the value context for its elements`() {
      val read = "{SUBSCRIPTINGREF :refupperindexpr ($constantNode) :reflowerindexpr <> :refexpr $array " +
        ":refassgnexpr <>}"

      assertThat(classify(update(read))).isEqualTo(mapOf(1 to inheritMismatch))
    }

    @Test
    fun `a constructor under a coalesce keeps the value context for its elements`() {
      val coalesce = "{COALESCEEXPR :args ($constantNode $array)}"

      assertThat(classify(update(coalesce))).isEqualTo(mapOf(1 to inheritMismatch))
    }

    @Test
    fun `a constructor under casts and a NOT NULL-free domain still makes its elements elements`() {
      val wrapped = "{RELABELTYPE :arg {COERCETODOMAIN :arg {FUNCEXPR :funcid 100 :funcformat 2 :args ($array)} " +
        ":resulttype 701}}"

      assertThat(classify(update(wrapped))).isEqualTo(mapOf(1 to elementNullable))
    }

    @Test
    fun `a constructor that is a field or subscript assignment is a direct element`() {
      val store = "{FIELDSTORE :arg {VAR :varno 1 :varattno 2} :newvals ($array)}"

      assertThat(classify(update(store))).isEqualTo(mapOf(1 to elementNullable))
    }

    @Test
    fun `a constructor in a NOT NULL domain value is no longer direct`() {
      val coerced = "{COERCETODOMAIN :arg $array :resulttype $notNullDomain}"

      assertThat(classify(update(coerced))).isEqualTo(mapOf(1 to nonNullOpaque))
    }
  }

  @Nested
  inner class ColumnIdentity {
    private val domainColumn = ColumnReference("public", "d", "d").let {
      InferredParameter("d", ParameterNullability.Inherit(it), it)
    }

    @Test
    fun `a parameter of another type than the column carries no column identity`() {
      val result = classify(update(operation(parameter(1, type = 701))))

      assertThat(result).isEqualTo(mapOf(1 to inheritMismatch))
    }

    @Test
    fun `a parameter whose type is in the column type's domain chain carries the column identity`() {
      assertThat(classify(update(parameter(1, type = 23), column = "d", relid = 16386)))
        .isEqualTo(mapOf(1 to domainColumn))
    }

    @Test
    fun `a parameter of the column's own domain type carries the column identity`() {
      assertThat(classify(update(parameter(1, type = domainOverInteger), column = "d", relid = 16386)))
        .isEqualTo(mapOf(1 to domainColumn))
    }

    @Test
    fun `a parameter reached through coercions alone carries the column identity`() {
      val cast = "{FUNCEXPR :funcid 100 :funcformat 2 :args ({RELABELTYPE :arg ${parameter(1)}})}"

      assertThat(classify(update(cast))).isEqualTo(mapOf(1 to inherit))
    }

    @Test
    fun `a nullable parameter of another type carries no column identity either`() {
      val nullTest = "{NULLTEST :arg ${parameter(1, type = 16)} :nulltesttype 0}"

      assertThat(classify(update(nullTest))).isEqualTo(mapOf(1 to nullableMismatch))
    }

    @Test
    fun `a NOT NULL domain keeps the column identity of the value it wraps`() {
      val coerced = "{COERCETODOMAIN :arg {COALESCEEXPR :args (${parameter(
        1,
      )} ${parameter(2)})} :resulttype $notNullDomain}"

      assertThat(classify(update(coerced))).isEqualTo(mapOf(1 to nullableInColumn, 2 to nonNullOfColumn))
    }

    @Test
    fun `a non-null parameter under an assignment still names its column and table`() {
      val increment = operation(parameter(1), constantNode)
      val coerced = "{COERCETODOMAIN :arg $increment :resulttype $notNullDomain}"

      assertThat(classify(update(coerced))).isEqualTo(mapOf(1 to nonNullOpaque))
      assertThat(classify(update(parameter(1, type = notNullDomain)))).isEqualTo(mapOf(1 to nonNullOpaque))
    }

    @Test
    fun `a CASE condition keeps the column name and drops the column identity`() {
      val caseExpression = "{CASEEXPR :args ({CASEWHEN :expr ${parameter(1)} :result ${parameter(2)}})}"

      assertThat(classify(update(caseExpression))).isEqualTo(mapOf(1 to nonNullOpaque, 2 to inherit))
    }

    @Test
    fun `a parameter with an unknown column type carries no column identity`() {
      assertThat(classify(update(parameter(1), relid = 99999))).isEqualTo(mapOf(1 to InferredParameter("a", null)))
    }
  }

  @Nested
  inner class SchemaOfTheTarget {
    @Test
    fun `the target carries the schema of its relation`() {
      val result = classify(update(parameter(1), relid = 16385))

      val column = ColumnReference("other", "u", "a")
      assertThat(result).isEqualTo(
        mapOf(1 to InferredParameter("a", ParameterNullability.Inherit(column), column)),
      )
    }

    @Test
    fun `an array element carries neither table nor schema`() {
      val result = classify(update("{ARRAYEXPR :elements (${parameter(1)})}", relid = 16385))

      assertThat(result).isEqualTo(mapOf(1 to InferredParameter("a", ParameterNullability.Nullable)))
    }
  }

  @Nested
  inner class ComparedColumn {
    private val flag = inheritMismatch

    private fun variable(varno: Int, attno: Int, levelsUp: Int = 0, type: Int = 23) =
      "{VAR :varno $varno :varattno $attno :varlevelsup $levelsUp :vartype $type}"

    private fun comparison(left: String, right: String, resultType: Int = 16) =
      "{OPEXPR :opfuncid 65 :opresulttype $resultType :args ($left $right)}"

    private fun updateFrom(expression: String) = update(expression).replace(
      ":rtable (${relation(16384)})",
      ":rtable (${relation(16384)} ${relation(16385)})",
    )

    @Test
    fun `the compared column can be on either side and in another relation`() {
      val result = classify(updateFrom(comparison(parameter(1), variable(2, 2))))

      val compared = flag.copy(name = "k", identity = ColumnReference("other", "u", "k"))
      assertThat(result).isEqualTo(mapOf(1 to compared))
    }

    @Test
    fun `a compared column of another type gives the name but no identity`() {
      val result = classify(update(comparison(variable(1, 1), parameter(1, type = 20))))

      assertThat(result).isEqualTo(mapOf(1 to flag))
    }

    @Test
    fun `a compared column of another type names the parameter after the compared column`() {
      val result = classify(update(comparison(variable(1, 2), parameter(1, type = 20))))

      assertThat(result).isEqualTo(mapOf(1 to flag.copy(name = "k")))
    }

    @Test
    fun `a coercion around the column or the parameter is looked through`() {
      val column = "{RELABELTYPE :arg ${variable(1, 1)} :resulttype 23}"
      val operand = "{COERCETODOMAIN :arg ${parameter(1)} :resulttype 701}"

      assertThat(classify(update(comparison(column, operand))))
        .isEqualTo(mapOf(1 to flag.copy(identity = targetColumn)))
    }

    @Test
    fun `a binary-compatible relabel gives the identity when the parameter has the relabeled type`() {
      val varchar = 1043
      val text = 25
      val column = "{RELABELTYPE :arg ${variable(1, 1, type = varchar)} :resulttype $text}"

      assertThat(classify(update(comparison(column, parameter(1, type = text)))))
        .isEqualTo(mapOf(1 to flag.copy(identity = targetColumn)))
      assertThat(classify(update(comparison(column, parameter(1, type = varchar)))))
        .isEqualTo(mapOf(1 to flag))
    }

    @Test
    fun `the outermost relabel decides the type the operator receives`() {
      val inner = "{RELABELTYPE :arg ${variable(1, 1, type = 1043)} :resulttype 25}"
      val column = "{RELABELTYPE :arg $inner :resulttype 19}"

      assertThat(classify(update(comparison(column, parameter(1, type = 19)))))
        .isEqualTo(mapOf(1 to flag.copy(identity = targetColumn)))
      assertThat(classify(update(comparison(column, parameter(1, type = 25)))))
        .isEqualTo(mapOf(1 to flag))
    }

    @Test
    fun `a cast function or an I O conversion around the column gives no compared column`() {
      val cast = call(variable(1, 1), format = 1)
      val conversion = "{COERCEVIAIO :arg ${variable(1, 1)} :resulttype 25}"

      assertThat(classify(update(comparison(cast, parameter(1))))).isEqualTo(mapOf(1 to flag))
      assertThat(classify(update(comparison(conversion, parameter(1, type = 25))))).isEqualTo(mapOf(1 to flag))
    }

    @Test
    fun `an operand of an operator that does not return boolean has no compared column`() {
      assertThat(classify(update(comparison(variable(1, 1), parameter(1), resultType = 23))))
        .isEqualTo(mapOf(1 to flag))
    }

    @Test
    fun `an outer reference or a column that is not a plain Var gives no compared column`() {
      assertThat(classify(update(comparison(variable(1, 1, levelsUp = 1), parameter(1))))).isEqualTo(mapOf(1 to flag))
      assertThat(classify(update(comparison(constantNode, parameter(1))))).isEqualTo(mapOf(1 to flag))
    }

    @Test
    fun `a comparison outside an assignment names the parameter after the compared column`() {
      val query = select(quals = comparison(variable(1, 1), parameter(1)), rangeTable = relation(16384))

      assertThat(classify(query)).isEqualTo(mapOf(1 to nonNullOfColumn))
    }
  }

  @Nested
  inner class ComparisonsInEveryPosition {
    private fun variable(varno: Int, attno: Int, levelsUp: Int = 0, type: Int = 23) =
      "{VAR :varno $varno :varattno $attno :varlevelsup $levelsUp :vartype $type}"

    private fun comparison(left: String, right: String) = "{OPEXPR :opfuncid 65 :opresulttype 16 :args ($left $right)}"

    private fun subquery(columns: String) =
      "{RANGETBLENTRY :rtekind 1 :subquery ${select()} :eref {ALIAS :aliasname s :colnames ($columns)}}"

    private fun nameOf(query: String, id: Int = 1) = classify(query)[id]?.name

    @Test
    fun `a SELECT WHERE comparison names the parameter after the column and takes its identity`() {
      val query = select(quals = comparison(variable(1, 2), parameter(1)), rangeTable = relation(16385))

      assertThat(classify(query).getValue(1).name).isEqualTo("k")
      assertThat(classify(query).getValue(1).identity).isEqualTo(ColumnReference("other", "u", "k"))
    }

    @Test
    fun `a column of a subquery entry names the parameter and gives no identity`() {
      val query = select(quals = comparison(variable(1, 2), parameter(1)), rangeTable = subquery("\"x\" \"y\""))

      assertThat(classify(query).getValue(1).name).isEqualTo("y")
      assertThat(classify(query).getValue(1).identity).isNull()
    }

    @Test
    fun `a SELECT list comparison names the parameter and gives it no nullability`() {
      val query = select(
        targetList = "(${entry(comparison(variable(1, 1), parameter(1)))})",
        rangeTable = relation(16384),
      )

      assertThat(classify(query).getValue(1).name).isEqualTo("a")
    }

    @Test
    fun `a variable one level up resolves against the enclosing query and its own level against the subquery`() {
      val subselect = select(
        quals = "{BOOLEXPR :boolop and :args (${comparison(variable(1, 2, levelsUp = 1), parameter(1))} " +
          "${comparison(variable(1, 1), parameter(2))})}",
        rangeTable = relation(16385),
      )
      val sublink = "{SUBLINK :subLinkType 0 :subselect $subselect}"
      val query = select(targetList = "(${entry(sublink)})", rangeTable = relation(16384))

      assertThat(classify(query).getValue(1).identity).isEqualTo(ColumnReference("public", "t", "k"))
      assertThat(classify(query).getValue(2).identity).isEqualTo(ColumnReference("other", "u", "a"))
    }

    @Test
    fun `a variable two levels up resolves against the outermost query`() {
      val innermost =
        select(quals = comparison(variable(1, 2, levelsUp = 2), parameter(1)), rangeTable = relation(16385))
      val middle = select(
        targetList = "(${entry("{SUBLINK :subLinkType 0 :subselect $innermost}")})",
        rangeTable = relation(16385),
      )
      val query = select(
        targetList = "(${entry("{SUBLINK :subLinkType 0 :subselect $middle}")})",
        rangeTable = relation(16384),
      )

      assertThat(classify(query).getValue(1).identity).isEqualTo(ColumnReference("public", "t", "k"))
    }

    @Test
    fun `an outer reference with no enclosing query names nothing`() {
      val query = select(quals = comparison(variable(1, 1, levelsUp = 1), parameter(1)), rangeTable = relation(16384))

      assertThat(nameOf(query)).isNull()
    }

    @Test
    fun `value choices pass the compared column to the parameter`() {
      val coalesce = "{COALESCEEXPR :args ($constantNode ${parameter(1)})}"
      val firstCoalesce = "{COALESCEEXPR :args (${parameter(2)} $constantNode)}"
      val case = "{CASEEXPR :args ({CASEWHEN :expr $constantNode :result ${parameter(3)}})}"
      val nullIf = "{NULLIFEXPR :opfuncid 65 :args (${parameter(4)} $constantNode)}"
      val minMax = "{MINMAXEXPR :args (${parameter(5)} $constantNode)}"
      val cast = "{COERCETODOMAIN :arg {RELABELTYPE :arg ${parameter(6)}} :resulttype 701}"
      val operands = listOf(coalesce, firstCoalesce, case, nullIf, minMax, cast)

      for ((index, operand) in operands.withIndex()) {
        val query = select(quals = comparison(variable(1, 1), operand), rangeTable = relation(16384))
        assertThat(nameOf(query, index + 1)).isEqualTo("a")
      }
    }

    @Test
    fun `a non-cast function, an operator, a constructor or the second NULLIF argument end the naming`() {
      val operands = listOf(
        call(parameter(1)),
        operation(parameter(2), constantNode),
        "{ROWEXPR :args (${parameter(3)})}",
        "{ARRAYEXPR :elements (${parameter(4)})}",
        "{NULLIFEXPR :opfuncid 65 :args ($constantNode ${parameter(5)})}",
        "{BOOLEXPR :boolop and :args (${parameter(6)})}",
      )

      for ((index, operand) in operands.withIndex()) {
        val query = select(quals = comparison(variable(1, 1), operand), rangeTable = relation(16384))
        assertThat(nameOf(query, index + 1)).isNull()
      }
    }

    @Test
    fun `a compared operand that is not a column under an assignment keeps the assignment target name`() {
      val query = update(comparison(variable(1, 0), parameter(1)), column = "k")

      assertThat(classify(query).getValue(1).name).isEqualTo("k")
    }
  }

  @Nested
  inner class SystemColumnsAndLikeEscape {
    private fun variable(varno: Int, attno: Int) = "{VAR :varno $varno :varattno $attno :varlevelsup 0 :vartype 23}"

    private fun comparison(left: String, right: String) = "{OPEXPR :opfuncid 65 :opresulttype 16 :args ($left $right)}"

    private fun likeEscape(function: Int) =
      "{FUNCEXPR :funcid $function :funcformat 0 :args (${parameter(1)} ${parameter(2)})}"

    @Test
    fun `a system column names the parameter after its attribute`() {
      val query = select(quals = comparison(variable(1, -1), parameter(1)), rangeTable = relation(16384))

      assertThat(classify(query).getValue(1).name).isEqualTo("ctid")
      assertThat(classify(query).getValue(1).identity).isEqualTo(ColumnReference("public", "t", "ctid"))
    }

    @Test
    fun `the first argument of like_escape keeps the compared column and the other arguments do not`() {
      val query = select(
        quals = comparison(variable(1, 1), likeEscape(likeEscapeFunction)),
        rangeTable = relation(16384),
      )

      assertThat(classify(query)).isEqualTo(mapOf(1 to nonNullOfColumn, 2 to nonNull))
    }

    @Test
    fun `a function other than like_escape ends the naming`() {
      val query = select(quals = comparison(variable(1, 1), likeEscape(100)), rangeTable = relation(16384))

      assertThat(classify(query)).isEqualTo(mapOf(1 to nonNull, 2 to nonNull))
    }
  }

  @Nested
  inner class JoinAndGroupEntries {
    private fun variable(varno: Int, attno: Int) = "{VAR :varno $varno :varattno $attno :varlevelsup 0 :vartype 23}"

    private fun comparison(left: String, right: String) = "{OPEXPR :opfuncid 65 :opresulttype 16 :args ($left $right)}"

    private fun entry(kind: Int, field: String, expressions: String, columns: String) =
      "{RANGETBLENTRY :rtekind $kind :$field ($expressions) :eref {ALIAS :aliasname j :colnames ($columns)}}"

    private fun join(aliasVars: String, columns: String = "\"k\"") = entry(2, "joinaliasvars", aliasVars, columns)

    private fun group(expressions: String, columns: String = "\"k\"") = entry(9, "groupexprs", expressions, columns)

    private fun classifyBy(entryText: String, operand: String = variable(2, 1)) = classify(
      select(quals = comparison(operand, parameter(1)), rangeTable = relation(16384) + " " + entryText),
    ).getValue(1)

    @Test
    fun `a join column resolves through its alias variable to the base relation`() {
      val result = classifyBy(join(variable(1, 2)))

      assertThat(result.name).isEqualTo("k")
      assertThat(result.identity).isEqualTo(ColumnReference("public", "t", "k"))
    }

    @Test
    fun `a relabeled alias variable resolves like a plain one`() {
      val result = classifyBy(join("{RELABELTYPE :arg ${variable(1, 2)} :resulttype 23}"))

      assertThat(result.identity).isEqualTo(ColumnReference("public", "t", "k"))
    }

    @Test
    fun `a join over a join resolves until it reaches a base relation`() {
      val result = classifyBy(join(variable(1, 2)) + " " + join(variable(2, 1), "\"k2\""), variable(3, 1))

      assertThat(result.name).isEqualTo("k2")
      assertThat(result.identity).isEqualTo(ColumnReference("public", "t", "k"))
    }

    @Test
    fun `a merged join column whose alias variable is a coalesce keeps its name and has no identity`() {
      val result = classifyBy(join("{COALESCEEXPR :args (${variable(1, 2)} ${variable(1, 2)})}"))

      assertThat(result.name).isEqualTo("k")
      assertThat(result.identity).isNull()
    }

    @Test
    fun `a group column resolves through its grouping expression to the base relation`() {
      val result = classifyBy(group(variable(1, 1), "\"a\""))

      assertThat(result.name).isEqualTo("a")
      assertThat(result.identity).isEqualTo(ColumnReference("public", "t", "a"))
    }

    @Test
    fun `a group column over a subquery column is named after that column and has no identity`() {
      val subquery = "{RANGETBLENTRY :rtekind 1 :subquery ${select()} :eref {ALIAS :aliasname x :colnames (\"sa\")}}"
      val query = select(
        quals = comparison(variable(2, 1), parameter(1)),
        rangeTable = subquery + " " + group(variable(1, 1), "\"sa\""),
      )

      assertThat(classify(query).getValue(1).name).isEqualTo("sa")
      assertThat(classify(query).getValue(1).identity).isNull()
    }

    @Test
    fun `a group column over a merged join column is named after it and has no identity`() {
      val merged = join("{COALESCEEXPR :args (${variable(1, 2)} ${variable(1, 2)})}")
      val query = select(
        quals = comparison(variable(3, 1), parameter(1)),
        rangeTable = relation(16384) + " " + merged + " " + group(variable(2, 1), "\"k\""),
      )

      assertThat(classify(query).getValue(1).name).isEqualTo("k")
      assertThat(classify(query).getValue(1).identity).isNull()
    }

    @Test
    fun `a group column over a relabeled variable resolves like a plain one`() {
      val result = classifyBy(group("{RELABELTYPE :arg ${variable(1, 1)} :resulttype 23}", "\"a\""))

      assertThat(result.identity).isEqualTo(ColumnReference("public", "t", "a"))
    }

    @Test
    fun `a group column of an expression leaves the parameter unnamed`() {
      val result = classifyBy(group("{OPEXPR :opfuncid 65 :opresulttype 23 :args (${variable(1, 1)})}", "\"?column?\""))

      assertThat(result.name).isNull()
      assertThat(result.identity).isNull()
    }
  }

  @Nested
  inner class InsertRows {
    private fun insertValues(rows: String, entries: String) =
      "{QUERY :commandType 3 :resultRelation 1 :cteList <> :rtable (${relation(16384)} " +
        "{RANGETBLENTRY :rtekind 5 :values_lists ($rows)}) :jointree {FROMEXPR :fromlist <> :quals <>} " +
        ":targetList ($entries) :returningList <>}"

    private fun column(attno: Int, name: String) =
      "{TARGETENTRY :expr {VAR :varno 2 :varattno $attno :varlevelsup 0 :vartype 23} :resno $attno " +
        ":resname $name :resjunk false}"

    @Test
    fun `every row of a multi-row VALUES feeds the target column of its position`() {
      val rows = "(${parameter(1)} ${parameter(2)}) (${parameter(3)} ${parameter(4)})"
      val result = classify(insertValues(rows, column(1, "a") + " " + column(2, "k")))

      assertThat(result.mapValues { it.value.name }).isEqualTo(mapOf(1 to "a", 2 to "k", 3 to "a", 4 to "k"))
      assertThat(result.getValue(3)).isEqualTo(inherit)
    }

    @Test
    fun `a single-row INSERT writes its target entry expressions as column values`() {
      val query = "{QUERY :commandType 3 :resultRelation 1 :cteList <> :rtable (${relation(16384)}) " +
        ":jointree {FROMEXPR :fromlist <> :quals <>} :targetList (${entry(parameter(1))}) :returningList <>}"

      assertThat(classify(query)).isEqualTo(mapOf(1 to inherit))
    }
  }

  @Nested
  inner class FreePositionsEverywhere {
    private fun nullTest(id: Int) = "{NULLTEST :arg ${parameter(id)} :nulltesttype 0}"

    @Test
    fun `a SELECT list coalesce and NULL test accept null`() {
      val coalesce = "{COALESCEEXPR :args (${parameter(1)} $constantNode)}"
      val query = select(targetList = "(${entry(coalesce)} ${entry(nullTest(2))})")

      assertThat(classify(query)).isEqualTo(mapOf(1 to nullableInCondition, 2 to nullableInCondition))
    }

    @Test
    fun `a coalesce in an ORDER BY junk entry or a RETURNING list accepts null`() {
      val coalesce = "{COALESCEEXPR :args (${parameter(1)} $constantNode)}"
      val query = update(constantNode).replace(":returningList <>", ":returningList (${entry(coalesce)})")

      assertThat(classify(query)).isEqualTo(mapOf(1 to nullableInCondition))
    }

    @Test
    fun `a free position under a node the walk does not list accepts null`() {
      val aggregate = "{AGGREF :args ({COALESCEEXPR :args (${parameter(1)} $constantNode)})}"

      assertThat(classify(select(targetList = "(${entry(aggregate)})"))).isEqualTo(mapOf(1 to nullableInCondition))
    }

    @Test
    fun `a free position through an operator accepts null`() {
      val coalesce = "{COALESCEEXPR :args (${operation(parameter(1))} $constantNode)}"

      assertThat(classify(select(targetList = "(${entry(coalesce)})"))).isEqualTo(mapOf(1 to nullableInCondition))
    }
  }

  @Nested
  inner class Sublinks {
    @Test
    fun `a sublink operand keeps the context and the subselect has its own roots`() {
      val subselect = select(quals = operation(parameter(2)), targetList = "(${entry(parameter(3))})")
      val sublink = "{SUBLINK :subLinkType 2 :testexpr {OPEXPR :args (${parameter(1)})} :subselect $subselect}"

      assertThat(classify(update(sublink))).isEqualTo(mapOf(1 to inheritMismatch, 2 to nonNull))
    }

    @Test
    fun `a subselect in a SELECT list still has its own conditions`() {
      val subselect = select(quals = operation(parameter(2)))
      val sublink = "{SUBLINK :subLinkType 4 :subselect $subselect}"

      assertThat(classify(select(targetList = "(${entry(sublink)})"))).isEqualTo(mapOf(2 to nonNull))
    }
  }

  @Nested
  inner class Domains {
    @Test
    fun `a NOT NULL domain around a parameter makes the parameter non-null`() {
      val coerced = "{COERCETODOMAIN :arg ${parameter(1)} :resulttype $notNullDomain}"

      assertThat(classify(update(coerced))).isEqualTo(mapOf(1 to nonNullOfColumn))
    }

    @Test
    fun `a NOT NULL domain makes a coalesce argument that is not last nullable and the last non-null`() {
      val coerced = "{COERCETODOMAIN :arg {COALESCEEXPR :args (${parameter(1)} ${parameter(2)})} " +
        ":resulttype $notNullDomain}"

      assertThat(classify(update(coerced))).isEqualTo(mapOf(1 to nullableInColumn, 2 to nonNullOfColumn))
    }

    @Test
    fun `a domain that is not NOT NULL keeps the context`() {
      val coerced = "{COERCETODOMAIN :arg ${parameter(1)} :resulttype 701}"

      assertThat(classify(update(coerced))).isEqualTo(mapOf(1 to inherit))
    }

    @Test
    fun `a parameter whose own type is a NOT NULL domain is non-null in every context`() {
      val domainParameter = parameter(1, type = notNullDomain)
      val store = "{FIELDSTORE :arg {VAR :varno 1 :varattno 2} :newvals (${parameter(2, type = notNullDomain)})}"

      assertThat(classify(update(domainParameter))).isEqualTo(mapOf(1 to nonNullOpaque))
      assertThat(classify(update(store))).isEqualTo(mapOf(2 to nonNullElement))
    }
  }

  @Nested
  inner class NoOpinionContext {
    private val unlisted = "{XMLEXPR :args (%s)}"

    @Test
    fun `a free position under an unlisted node in an assignment accepts null without the column`() {
      val nullTest = "{NULLTEST :arg ${parameter(1)} :nulltesttype 0}"

      assertThat(classify(update(unlisted.format(nullTest)))).isEqualTo(mapOf(1 to nullableInCondition))
    }

    @Test
    fun `a condition under an unlisted node in an assignment is not an assignment`() {
      val subselect = select(quals = operation(parameter(1)))
      val sublink = "{SUBLINK :subLinkType 4 :subselect $subselect}"

      assertThat(classify(update(unlisted.format(sublink)))).isEqualTo(mapOf(1 to nonNull))
    }

    @Test
    fun `a NOT NULL domain cast in a SELECT list has no opinion`() {
      val coerced = "{COERCETODOMAIN :arg ${parameter(1)} :resulttype $notNullDomain}"

      assertThat(classify(select(targetList = "(${entry(coerced)})"))).isEmpty()
      assertThat(classify(select(targetList = "(${entry(parameter(1, type = notNullDomain))})"))).isEmpty()
    }

    @Test
    fun `a NOT NULL domain cast under an unlisted node in an assignment has no opinion`() {
      val coerced = "{COERCETODOMAIN :arg ${parameter(1)} :resulttype $notNullDomain}"

      assertThat(classify(update(unlisted.format(coerced)))).isEmpty()
    }
  }

  @Nested
  inner class NestedQueries {
    private val subselect = select(quals = operation(parameter(1)))

    @Test
    fun `a common table expression query has its own conditions`() {
      val withCte = update(constantNode).replace(
        ":cteList <>",
        ":cteList ({COMMONTABLEEXPR :ctename x :ctequery $subselect})",
      )

      assertThat(classify(withCte)).isEqualTo(mapOf(1 to nonNull))
    }

    @Test
    fun `a range table subquery has its own conditions`() {
      val withSubquery = update(constantNode).replace(
        ":rtable (${relation(16384)})",
        ":rtable (${relation(16384)} {RANGETBLENTRY :rtekind 1 :subquery $subselect})",
      )

      assertThat(classify(withSubquery)).isEqualTo(mapOf(1 to nonNull))
    }
  }

  @Nested
  inner class Parameters {
    @Test
    fun `a parameter that is not external has no opinion`() {
      assertThat(classify(update(parameter(65537, kind = 3)))).isEmpty()
    }

    @Test
    fun `a node kind the rule does not list gives its children no opinion`() {
      val unlisted = "{AGGREF :args (${parameter(1)})}"

      assertThat(classify(update(unlisted))).isEmpty()
    }
  }
}
