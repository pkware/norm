package norm.generator

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Parses `{NODE_TYPE ...}` expression blocks into [PgNodeExpression] nodes. Never throws.
 */
internal class PgNodeExpressionParser(private val scanner: PgNodeTreeScanner) {

  private val logger = Logger.getLogger(PgNodeExpressionParser::class.java.name)

  internal fun parseExpression(text: String): PgNodeExpression = try {
    val nodeType = nodeTypePattern.find(text)?.groupValues?.get(1)
      ?: return PgNodeExpression.Unknown("PARSE_ERROR").also {
        logger.log(Level.FINE, "Could not determine node type from: $text")
      }
    when (nodeType) {
      "VAR" -> parseVar(text)
      "CONST" -> parseConst(text)
      "FUNCEXPR" -> parseFuncExpr(text)
      "OPEXPR" -> parseOpExpr(text)
      "SCALARARRAYOPEXPR" -> parseScalarArrayOpExpr(text)
      "AGGREF" -> parseAggref(text)
      "WINDOWFUNC" -> parseWindowFunc(text)
      "COALESCEEXPR" -> parseCoalesceExpr(text)
      "NULLIFEXPR" -> parseNullIfExpr(text)
      "MINMAXEXPR" -> parseMinMaxExpr(text)
      "SUBLINK" -> parseSubLink(text)
      "CASEEXPR" -> parseCaseExpr(text)
      "BOOLEXPR" -> parseBoolExpr(text)
      "RELABELTYPE" -> parseRelabelType(text)
      "COERCEVIAIO" -> parseCoerceViaIo(text)
      "ARRAYCOERCEEXPR" -> parseArrayCoerceExpr(text)
      "COLLATEEXPR" -> parseCollateExpr(text)
      "COERCETODOMAIN" -> parseCoerceToDomain(text)
      "SQLVALUEFUNCTION" -> parseSqlValueFunction(text)
      "NULLTEST" -> parseNullTest(text)
      "BOOLEANTEST" -> parseBooleanTest(text)
      "NEXTVALEXPR" -> parseNextValExpr(text)
      "GROUPINGFUNC" -> parseGroupingFunc(text)
      "DISTINCTEXPR" -> parseDistinctExpr(text)
      "ARRAYEXPR" -> parseArrayExpr(text)
      "ROWEXPR" -> parseRowExpr(text)
      "FIELDSELECT" -> parseFieldSelect(text)
      "JSONISPREDICATE" -> parseJsonIsPredicate(text)
      "JSONCONSTRUCTOREXPR" -> parseJsonConstructorExpr(text)
      "JSONVALUEEXPR" -> parseJsonValueExpr(text)
      "JSONEXPR" -> parseJsonExpr(text)
      "XMLEXPR" -> parseXmlExpr(text)
      else -> PgNodeExpression.Unknown(nodeType)
    }
  } catch (cause: RuntimeException) {
    logger.log(Level.FINE, "Failed to parse node tree expression: ${cause.message}\nInput: $text")
    PgNodeExpression.Unknown("PARSE_ERROR")
  }

  private fun parseVar(text: String): PgNodeExpression.Var {
    val varno = scanner.extractIntField(text, ":varno")
      ?: error("Missing :varno in VAR node")
    val varattno = scanner.extractIntField(text, ":varattno")
      ?: error("Missing :varattno in VAR node")
    val nullingRelations = scanner.extractBitmapset(text, ":varnullingrels")
    val levelsUp = scanner.extractIntField(text, ":varlevelsup") ?: 0
    // :varreturningtype only appears on PostgreSQL 18+ (RETURNING WITH (OLD AS o, NEW AS n)) and is
    // absent on 16/17 — see PgNodeExpression.Var.returningType's KDoc for why 0 (never 1, "OLD") is
    // the correct default for a missing field.
    val returningType = scanner.extractIntField(text, ":varreturningtype") ?: 0
    return PgNodeExpression.Var(
      varno = varno,
      varattno = varattno,
      nullingRelations = nullingRelations,
      levelsUp = levelsUp,
      returningType = returningType,
    )
  }

  private fun parseConst(text: String): PgNodeExpression.Const {
    // Default to `true` (nullable) when `:constisnull` cannot be read, never `false`: a future
    // Postgres version renaming or reordering the field must degrade toward nullable, not toward a
    // confidently wrong NOT NULL.
    val isNull = scanner.extractBoolField(text, ":constisnull") ?: true
    return PgNodeExpression.Const(isNull = isNull)
  }

  /**
   * Parses a `{FUNCEXPR ...}` block.
   *
   * `:funcvariadic` always precedes `:args` in `FUNCEXPR`'s field order on PostgreSQL 16, 17, and
   * 18, and is always emitted explicitly, including `:funcvariadic false`. `extractBoolField`
   * matches the first occurrence of `:funcvariadic` anywhere in [text], including nested blocks, so
   * this relies on the outer node's own field appearing before any nested `FUNCEXPR`'s field — both
   * nesting directions are covered by `PgNodeTreeParserTest`. If a future
   * PostgreSQL version ever reordered the fields, this extraction would silently read the wrong
   * node's flag instead, and the fallback below cannot catch that: it only fires when
   * `:funcvariadic` is absent from [text] entirely. For that absent-field case, the default is
   * `true` (variadic), not `false`: variadic calls are treated more conservatively by
   * [NodeTreeNullabilityAnalyzer.isNonNull]'s `FuncExpr` branch, so this default fails toward
   * nullable, never toward a wrong NOT NULL.
   */
  private fun parseFuncExpr(text: String): PgNodeExpression.FuncExpr {
    val functionOid = scanner.extractIntField(text, ":funcid") ?: error("Missing :funcid in FUNCEXPR node")
    val isVariadic = scanner.extractBoolField(text, ":funcvariadic") ?: true
    return PgNodeExpression.FuncExpr(
      functionOid = functionOid,
      arguments = parseArgList(text, ":args"),
      isVariadic = isVariadic,
    )
  }

  private fun parseOpExpr(text: String): PgNodeExpression.OpExpr {
    val operatorFunctionOid = scanner.extractIntField(text, ":opfuncid") ?: error("Missing :opfuncid in OPEXPR node")
    return PgNodeExpression.OpExpr(operatorFunctionOid = operatorFunctionOid, arguments = parseArgList(text, ":args"))
  }

  private fun parseScalarArrayOpExpr(text: String): PgNodeExpression.ScalarArrayOpExpr {
    val operatorFunctionOid = scanner.extractIntField(text, ":opfuncid")
      ?: error("Missing :opfuncid in SCALARARRAYOPEXPR node")
    // Unknown defaults to the more restrictive ALL handling.
    val useOr = scanner.extractBoolField(text, ":useOr") ?: false
    return PgNodeExpression.ScalarArrayOpExpr(
      operatorFunctionOid = operatorFunctionOid,
      arguments = parseArgList(text, ":args"),
      useOr = useOr,
    )
  }

  private fun parseAggref(text: String): PgNodeExpression.Aggref {
    val aggregateFunctionOid = scanner.extractIntField(text, ":aggfnoid") ?: error("Missing :aggfnoid in AGGREF node")
    return PgNodeExpression.Aggref(
      aggregateFunctionOid = aggregateFunctionOid,
      arguments = extractTargetEntryExpressions(text, ":args"),
    )
  }

  private fun parseWindowFunc(text: String): PgNodeExpression.WindowFunc {
    val windowFunctionOid = scanner.extractIntField(text, ":winfnoid") ?: error("Missing :winfnoid in WINDOWFUNC node")
    return PgNodeExpression.WindowFunc(windowFunctionOid = windowFunctionOid, arguments = parseArgList(text, ":args"))
  }

  private fun parseCoalesceExpr(text: String): PgNodeExpression.CoalesceExpr =
    PgNodeExpression.CoalesceExpr(arguments = parseArgList(text, ":args"))

  private fun parseNullIfExpr(text: String): PgNodeExpression.NullIfExpr =
    PgNodeExpression.NullIfExpr(arguments = parseArgList(text, ":args"))

  private fun parseMinMaxExpr(text: String): PgNodeExpression.MinMaxExpr =
    PgNodeExpression.MinMaxExpr(arguments = parseArgList(text, ":args"))

  private fun parseSubLink(text: String): PgNodeExpression.SubLink {
    val subLinkType = scanner.extractIntField(text, ":subLinkType") ?: error("Missing :subLinkType in SUBLINK node")
    // :testexpr is extracted unconditionally, not just for ANY/ALL: outerOperand also feeds
    // GroupingSetNullExtension.safetyWalkChildren, NodeTreeNullabilityAnalyzer.containsVarOutsideRelation, and
    // GroupRteSubstitution's Var walk. Every other sublink type either emits no :testexpr or emits a
    // ROWCOMPAREEXPR with no readable :args, so a future SubLinkType carrying a real testexpr becomes
    // visible automatically instead of being hidden by a subLinkType gate. isNonNull's ANY/ALL proof
    // stays gated below, so this alone cannot make any sublink provably non-null.
    val testExprBlock = scanner.blockAtDepthOne(text, ":testexpr")
    val outerOperand = testExprBlock?.let { testExpr ->
      scanner.listAtDepthOne(testExpr, ":args")
        ?.let { scanner.splitBraceBlocks(it).firstOrNull()?.let(::parseExpression) }
    }
    val testExpressionOperatorOid = (testExprBlock?.let(::parseExpression) as? PgNodeExpression.OpExpr)
      ?.operatorFunctionOid
    // :subselect holds the sublink's subquery body ({QUERY ...}), mirroring parseRangeTableEntries's
    // own :subquery extraction and parseCteList's extraction of the same node shape.
    // :testexpr precedes :subselect and can hold a nested sublink with its own :subselect, so this
    // must read at depth one.
    val subselectBlock = scanner.blockAtDepthOne(text, ":subselect")
    return PgNodeExpression.SubLink(
      subLinkType = subLinkType,
      outerOperand = outerOperand,
      subselectBlock = subselectBlock,
      testExpressionOperatorOid = testExpressionOperatorOid,
    )
  }

  private fun parseCaseExpr(text: String): PgNodeExpression.CaseExpr {
    // The `CASE testexpr WHEN ...` test expression (`:arg`) holds a real Var for the shorthand
    // form, e.g. `CASE a WHEN 'x' THEN 1 ELSE 2 END` — see PgNodeExpression.CaseExpr's KDoc.
    val testExpression = scanner.blockAtDepthOne(text, ":arg")?.let { parseExpression(it) }
    val whenBlocks = scanner.listAtDepthOne(text, ":args")?.let { scanner.splitBraceBlocks(it) }
      ?.filter { it.startsWith("{CASEWHEN") }
      ?: emptyList()
    val resultExpressions = whenBlocks.mapNotNull { block -> scanner.blockAtDepthOne(block, ":result") }
      .map { parseExpression(it) }
    // Each WHEN's own condition (`:expr`) holds the real Var for the explicit form, e.g.
    // `CASE WHEN a = 'x' THEN 1 ELSE 2 END` — see PgNodeExpression.CaseExpr's KDoc.
    val whenConditions = whenBlocks.mapNotNull { block -> scanner.blockAtDepthOne(block, ":expr") }
      .map { parseExpression(it) }
    val defaultResult = scanner.blockAtDepthOne(text, ":defresult")?.let { parseExpression(it) }
    return PgNodeExpression.CaseExpr(
      resultExpressions = resultExpressions,
      defaultResult = defaultResult,
      testExpression = testExpression,
      whenConditions = whenConditions,
    )
  }

  private fun parseBoolExpr(text: String): PgNodeExpression.BoolExpr = PgNodeExpression.BoolExpr(
    arguments = parseArgList(text, ":args"),
    boolOperator = scanner.extractStringField(text, ":boolop") ?: "",
  )

  private fun parseRelabelType(text: String): PgNodeExpression.RelabelType {
    val argument = scanner.blockAtDepthOne(text, ":arg") ?: error("Missing :arg in RELABELTYPE node")
    return PgNodeExpression.RelabelType(argument = parseExpression(argument))
  }

  private fun parseCoerceViaIo(text: String): PgNodeExpression.CoerceViaIo {
    val argument = scanner.blockAtDepthOne(text, ":arg") ?: error("Missing :arg in COERCEVIAIO node")
    return PgNodeExpression.CoerceViaIo(argument = parseExpression(argument))
  }

  private fun parseArrayCoerceExpr(text: String): PgNodeExpression.ArrayCoerceExpr {
    val argument = scanner.blockAtDepthOne(text, ":arg") ?: error("Missing :arg in ARRAYCOERCEEXPR node")
    return PgNodeExpression.ArrayCoerceExpr(argument = parseExpression(argument))
  }

  private fun parseCollateExpr(text: String): PgNodeExpression.CollateExpr {
    val argument = scanner.blockAtDepthOne(text, ":arg") ?: error("Missing :arg in COLLATEEXPR node")
    return PgNodeExpression.CollateExpr(argument = parseExpression(argument))
  }

  private fun parseCoerceToDomain(text: String): PgNodeExpression.CoerceToDomain {
    val argument = scanner.blockAtDepthOne(text, ":arg") ?: error("Missing :arg in COERCETODOMAIN node")
    return PgNodeExpression.CoerceToDomain(argument = parseExpression(argument))
  }

  private fun parseSqlValueFunction(text: String): PgNodeExpression.SqlValueFunction {
    val operation = scanner.extractIntField(text, ":op") ?: error("Missing :op in SQLVALUEFUNCTION node")
    return PgNodeExpression.SqlValueFunction(operation = operation)
  }

  private fun parseNullTest(text: String): PgNodeExpression.NullTest {
    val argument = scanner.blockAtDepthOne(text, ":arg") ?: error("Missing :arg in NULLTEST node")
    // Unknown defaults to the form that proves nothing.
    val nullTestType = scanner.extractIntField(text, ":nulltesttype") ?: PgNodeExpression.NULL_TEST_IS_NULL
    return PgNodeExpression.NullTest(argument = parseExpression(argument), nullTestType = nullTestType)
  }

  private fun parseBooleanTest(text: String): PgNodeExpression.BooleanTest {
    val argument = scanner.blockAtDepthOne(text, ":arg") ?: error("Missing :arg in BOOLEANTEST node")
    return PgNodeExpression.BooleanTest(argument = parseExpression(argument))
  }

  private fun parseNextValExpr(text: String): PgNodeExpression.NextValExpr {
    val sequenceOid = scanner.extractIntField(text, ":seqid") ?: error("Missing :seqid in NEXTVALEXPR node")
    return PgNodeExpression.NextValExpr(sequenceOid = sequenceOid)
  }

  private fun parseGroupingFunc(text: String): PgNodeExpression.GroupingFunc =
    PgNodeExpression.GroupingFunc(arguments = parseArgList(text, ":args"))

  private fun parseDistinctExpr(text: String): PgNodeExpression.DistinctExpr =
    PgNodeExpression.DistinctExpr(arguments = parseArgList(text, ":args"))

  private fun parseArrayExpr(text: String): PgNodeExpression.ArrayExpr =
    PgNodeExpression.ArrayExpr(elements = parseArgList(text, ":elements"))

  private fun parseRowExpr(text: String): PgNodeExpression.RowExpr =
    PgNodeExpression.RowExpr(arguments = parseArgList(text, ":args"))

  private fun parseFieldSelect(text: String): PgNodeExpression.FieldSelect {
    val argument = scanner.blockAtDepthOne(text, ":arg") ?: error("Missing :arg in FIELDSELECT node")
    val fieldNumber = scanner.extractIntField(text, ":fieldnum") ?: error("Missing :fieldnum in FIELDSELECT node")
    return PgNodeExpression.FieldSelect(argument = parseExpression(argument), fieldNumber = fieldNumber)
  }

  private fun parseJsonIsPredicate(text: String): PgNodeExpression.JsonIsPredicate {
    val argument = scanner.blockAtDepthOne(text, ":expr") ?: error("Missing :expr in JSONISPREDICATE node")
    return PgNodeExpression.JsonIsPredicate(argument = parseExpression(argument))
  }

  private fun parseJsonConstructorExpr(text: String): PgNodeExpression.JsonConstructorExpr {
    val type = scanner.extractIntField(text, ":type") ?: error("Missing :type in JSONCONSTRUCTOREXPR node")
    val function = scanner.blockAtDepthOne(text, ":func")?.let(::parseExpression)
    return PgNodeExpression.JsonConstructorExpr(
      type = type,
      arguments = parseArgList(text, ":args"),
      function = function,
    )
  }

  /**
   * Parses a `{JSONVALUEEXPR ...}` block — Postgres's wrapper around a JSON-constructor argument
   * needing `FORMAT`-aware coercion — by transparently unwrapping to its `:formatted_expr` child
   * rather than modeling it as its own [PgNodeExpression] variant.
   *
   * Without this case a `{JSONVALUEEXPR ...}` parses to [PgNodeExpression.Unknown], which
   * [NodeTreeNullabilityAnalyzer.isNonNull] always treats as nullable, so
   * `JSON(a_not_null_column)`/`JSON_SERIALIZE(a_not_null_column)` would be reported nullable
   * regardless of the source column's constraint. `JSON()`'s single argument always wraps;
   * `JSON_SERIALIZE`'s wraps only when its argument is not already `jsonb`; `JSON_SCALAR`'s never does.
   *
   * `:formatted_expr`, not `:raw_expr`, is the value this node contributes to the enclosing
   * expression; Postgres coerces `:raw_expr` through it, typically via a [PgNodeExpression.CoerceViaIo]
   * whose existing strict-propagation rule carries nullability through unchanged.
   *
   * @return [PgNodeExpression.Unknown]`("PARSE_ERROR")` if `:formatted_expr` is absent or malformed —
   *   this class never throws (see the class-level KDoc), and this degrades toward nullable.
   */
  private fun parseJsonValueExpr(text: String): PgNodeExpression {
    val formattedExpression = scanner.blockAtDepthOne(text, ":formatted_expr")
      ?: return PgNodeExpression.Unknown("PARSE_ERROR")
    return parseExpression(formattedExpression)
  }

  private fun parseJsonExpr(text: String): PgNodeExpression.JsonExpr {
    val op = scanner.extractIntField(text, ":op") ?: error("Missing :op in JSONEXPR node")
    val argument = scanner.blockAtDepthOne(text, ":formatted_expr")
      ?: error("Missing :formatted_expr in JSONEXPR node")
    val onEmptyBlock = scanner.blockAtDepthOne(text, ":on_empty")
    val onEmpty = if (onEmptyBlock != null) {
      scanner.extractIntField(onEmptyBlock, ":btype") ?: PgNodeExpression.JSON_BEHAVIOR_NULL
    } else {
      PgNodeExpression.JSON_BEHAVIOR_NULL
    }
    val onEmptyDefault = onEmptyBlock?.let { scanner.blockAtDepthOne(it, ":expr")?.let(::parseExpression) }
    val onErrorBlock = scanner.blockAtDepthOne(text, ":on_error")
    val onError = if (onErrorBlock != null) {
      scanner.extractIntField(onErrorBlock, ":btype") ?: PgNodeExpression.JSON_BEHAVIOR_NULL
    } else {
      PgNodeExpression.JSON_BEHAVIOR_NULL
    }
    val onErrorDefault = onErrorBlock?.let { scanner.blockAtDepthOne(it, ":expr")?.let(::parseExpression) }
    return PgNodeExpression.JsonExpr(
      op = op,
      argument = parseExpression(argument),
      onEmpty = onEmpty,
      onEmptyDefault = onEmptyDefault,
      onError = onError,
      onErrorDefault = onErrorDefault,
    )
  }

  private fun parseXmlExpr(text: String): PgNodeExpression.XmlExpr {
    val op = scanner.extractIntField(text, ":op") ?: error("Missing :op in XMLEXPR node")
    val namedArguments = parseArgList(text, ":named_args")
    val arguments = parseArgList(text, ":args")
    return PgNodeExpression.XmlExpr(op = op, arguments = namedArguments + arguments)
  }

  /**
   * Parses a `(...)` argument list from [fieldName] into a list of [PgNodeExpression]s.
   *
   * Handles `<>` (empty/absent) by returning an empty list.
   */
  internal fun parseArgList(text: String, fieldName: String): List<PgNodeExpression> {
    val content = scanner.listAtDepthOne(text, fieldName) ?: return emptyList()
    return scanner.splitBraceBlocks(content).map { parseExpression(it) }
  }

  /**
   * Extracts expression nodes from TARGETENTRY-wrapped items in a named `(...)` list.
   *
   * AGGREF stores its arguments as `{TARGETENTRY :expr {actual_expr} ...}` wrappers. This method
   * splits the list, identifies TARGETENTRY blocks, and extracts the inner `:expr` expression from
   * each one. Non-TARGETENTRY blocks are parsed directly.
   */
  private fun extractTargetEntryExpressions(text: String, fieldName: String): List<PgNodeExpression> {
    val content = scanner.listAtDepthOne(text, fieldName) ?: return emptyList()
    return scanner.splitBraceBlocks(content).map { block ->
      if (block.startsWith("{TARGETENTRY")) {
        val expressionText = scanner.blockAtDepthOne(block, ":expr")
        if (expressionText != null) parseExpression(expressionText) else PgNodeExpression.Unknown("TARGETENTRY")
      } else {
        parseExpression(block)
      }
    }
  }

  private companion object {
    val nodeTypePattern = Regex("""^\{(\w+)""")
  }
}
