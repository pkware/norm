package norm.generator

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.INT_ARRAY
import com.squareup.kotlinpoet.ITERABLE
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.jvm.throws
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Produces a function builder with a signature reflecting a SQL statement.
 *
 * Populates the throws, parameters, and return type using [statement].
 */
internal fun sqlFunction(statement: SqlStatement): FunSpec.Builder {
  val function = FunSpec.builder(statement.name)

  if (statement.command != Command.MANY) {
    function.throws(SQLException::class)
  }

  val optionalIndices = statement.optionalParameterIndices.toSet()
  for ((index, parameter) in statement.parameters.withIndex()) {
    val parameterName = statement.getParameterName(index)
    val columnType = statement.resolveColumnType(parameter.column!!)
    val parameterType = if (index in optionalIndices) {
      COLUMN_VALUE_CLASS_NAME.parameterizedBy(columnType)
    } else {
      columnType
    }
    function.addParameter(parameterName, parameterType)
  }

  val returnType = statement.command.applyTo(statement.resultRowShape.kotlinType)
  function.returns(returnType)

  return function
}

/**
 * Produces a function builder with a signature reflecting a SQL statement, a generic return type, and a mapper to
 * produce the return type.
 *
 * Populates the throws, parameters, and return type using [statement].
 */
internal fun mapperFunction(statement: SqlStatement): FunSpec.Builder {
  val mapperReturnType = statement.resultRowShape.mapperReturnType
  val function = sqlFunction(statement)
    .addTypeVariable(mapperReturnType)
    .returns(statement.command.applyTo(mapperReturnType))

  // Add the mapper as the last parameter
  function.addParameter(
    ParameterSpec(
      MAPPER_PARAMETER_NAME,
      LambdaTypeName.get(
        parameters = statement.resultRowShape.creationParameters.toTypedArray(),
        returnType = mapperReturnType,
      ),
    ),
  )
  return function
}

/**
 * Adds implementation method(s) for the given SQL statement to the receiver `class` builder.
 */
internal fun TypeSpec.Builder.addSqlStatementImplementationMethod(statement: SqlStatement) {
  when (statement.command) {
    Command.ONE -> {
      addOneImplementation(statement)
      if (statement.canBeBatchedWithReturn) {
        addFunction(buildBatchWithReturn(statement))
      }
    }
    Command.MANY -> addManyImplementation(statement)
    Command.EXEC_ROWS -> addExecRowsImplementation(statement)
    Command.EXEC -> addExecImplementation(statement)
  }
}

/**
 * Generates the implementation for a `:one` query.
 *
 * These queries return exactly one result row, mapped via the provided mapper function.
 */
private fun TypeSpec.Builder.addOneImplementation(statement: SqlStatement) {
  val function = mapperFunction(statement).apply {
    addModifiers(KModifier.OVERRIDE)
    if (statement.optionalParameterIndices.isEmpty()) {
      addStatement("val sql = %S", statement.sql)
    } else {
      addDynamicInsertSqlDeclaration(statement, statement.sql) { parameterIndex ->
        CodeBlock.of("%N is %T", statement.getParameterName(parameterIndex), COLUMN_VALUE_SET_CLASS_NAME)
      }
    }
    buildOne(statement)
  }
  addFunction(function.build())
}

/**
 * Generates the implementation for a `:many` query.
 *
 * Uses a helper pattern to enable code sharing between `Many<T>` and `Query<T>` variants.
 *
 * This generates:
 * 1. A private helper function that takes a block parameter to decide which driver method to call
 * 2. A public `Many` variant that calls the helper with `driver::queryMany`
 * 3. If eligible, a public `Query` variant that calls the helper with `driver::dynamic`
 */
private fun TypeSpec.Builder.addManyImplementation(statement: SqlStatement) {
  val resultRowShape = statement.resultRowShape
  val mapperReturnType = resultRowShape.mapperReturnType
  val returnTypeVariable = TypeVariableName("Return")
  // 1. Private helper function
  val helperFunction = mapperFunction(statement)
    .addModifiers(KModifier.PRIVATE)
    .addTypeVariable(returnTypeVariable)
    .addParameter(
      "processor",
      MANY_PROCESSOR.parameterizedBy(mapperReturnType, returnTypeVariable),
    )
    .returns(returnTypeVariable)
    .addStatement("val sql = %S", statement.sql)
    .apply {
      beginControlFlow("val rowReader: %T.() -> %T = {", ResultSet::class, mapperReturnType)
      addCode("%L\n", mapperInvocation(resultRowShape.builder))
      endControlFlow()
      if (statement.parameterBindings.isNotEmpty()) {
        beginControlFlow(
          "val queryBinder: (%T.() -> %T)? = {",
          PreparedStatement::class,
          Unit::class,
        )
        for (block in bindStatements(statement)) addCode("%L\n", block)
        endControlFlow()
        addStatement("return processor.invoke(sql, rowReader, queryBinder)")
      } else {
        addStatement("return processor.invoke(sql, rowReader, null)")
      }
    }
    .build()
  addFunction(helperFunction)
  // 2. Public Many variant: override fun <T : Any> queryName(mapper: ...) -> Many<T>
  val manyFunction = mapperFunction(statement)
    .addModifiers(KModifier.OVERRIDE)
    .apply {
      val args = (
        statement.parameters.indices.map { CodeBlock.of("%N", statement.getParameterName(it)) } + listOf(
          CodeBlock.of("%N", MAPPER_PARAMETER_NAME),
          CodeBlock.of("driver::queryMany"),
        )
        ).joinToString(", ")
      addStatement("return %N($args)", statement.name)
    }
    .build()
  addFunction(manyFunction)
  // 3. If eligible, public Query variant: override fun <T : Any> queryNameDynamically(mapper: ...) -> Query<T>
  if (statement.canBeDynamic) {
    val dynamicFunction = mapperFunction(statement).build()
      .toBuilder("${statement.name}Dynamically")
      .addModifiers(KModifier.OVERRIDE)
      .returns(Command.NORM_QUERY.parameterizedBy(mapperReturnType))
      .addStatement(
        "return %N(%N) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }",
        statement.name,
        MAPPER_PARAMETER_NAME,
      )
      .build()
    addFunction(dynamicFunction)
  }
}

/**
 * Generates the implementation for an `:execrows` query.
 *
 * These queries execute DML and return the number of affected rows.
 * If the statement can be batched, also generates a batch variant.
 */
private fun TypeSpec.Builder.addExecRowsImplementation(statement: SqlStatement) {
  val function = mapperFunction(statement).apply {
    addModifiers(KModifier.OVERRIDE)
    typeVariables.clear()
    parameters.removeLast()
    addStatement("val sql = %S", statement.sql)
    buildExecRows(statement)
  }
  addFunction(function.build())

  if (statement.canBeBatched) {
    addFunction(buildBatch(statement))
  }
}

/**
 * Generates the implementation for an `:exec` query.
 *
 * These queries execute DML without returning a result.
 * If the statement can be batched, also generates a batch variant.
 */
private fun TypeSpec.Builder.addExecImplementation(statement: SqlStatement) {
  val function = sqlFunction(statement).apply {
    addModifiers(KModifier.OVERRIDE)
    addStatement("val sql = %S", statement.sql)
    buildExec(statement)
  }
  addFunction(function.build())

  if (statement.canBeBatched) {
    addFunction(buildBatch(statement))
  }
}

/**
 * Builds a function that returns exactly 1, non-null result.
 *
 * The query author is in the best position to determine if a query is capable of returning no or some results.
 * The caller in Java shouldn't have to think about that.
 * Accordingly, we make `:one` queries be exact and `:many` queries flexible on return number.
 */
private fun FunSpec.Builder.buildOne(statement: SqlStatement) {
  val resultRowShape = statement.resultRowShape
  beginControlFlow("val rowReader: %T.() -> %T = {", ResultSet::class, resultRowShape.mapperReturnType)
  addCode("%L\n", mapperInvocation(resultRowShape.builder))
  // Close the rowReader
  endControlFlow()

  if (statement.optionalParameterIndices.isNotEmpty()) {
    beginControlFlow("return driver.queryOne(sql, rowReader) {")
    for (block in requiredBindStatements(statement)) addCode("%L\n", block)
    addStatement("var nextParameterIndex = %L", statement.parameters.size - statement.optionalParameterIndices.size)
    for (parameterIndex in statement.optionalParameterIndices) {
      val parameterName = statement.getParameterName(parameterIndex)
      val indexReference = "${parameterName}Index"
      val binding = statement.parameterBindings.first { it.parameterIndex == parameterIndex }
      val typeInfo = statement.resolveMappableType(binding.column)
      beginControlFlow("if (%N is %T)", parameterName, COLUMN_VALUE_SET_CLASS_NAME)
      addStatement("nextParameterIndex += 1")
      addStatement("val %N = nextParameterIndex", indexReference)
      addStatement(
        "%L",
        typeInfo.statementAction(CodeBlock.of("%N", indexReference), CodeBlock.of("%N.value", parameterName)),
      )
      endControlFlow()
    }
    endControlFlow()
  } else if (statement.parameterBindings.isNotEmpty()) {
    beginControlFlow("return driver.queryOne(sql, rowReader) {")
    for (block in bindStatements(statement)) addCode("%L\n", block)
    endControlFlow()
  } else {
    addStatement("return driver.queryOne(sql, rowReader)")
  }
}

private fun FunSpec.Builder.buildExecRows(statement: SqlStatement) {
  if (statement.parameterBindings.isNotEmpty()) {
    beginControlFlow("return driver.executeRows(sql) {")
    for (block in bindStatements(statement)) addCode("%L\n", block)
    endControlFlow()
  } else {
    addStatement("return driver.executeRows(sql)")
  }
}

private fun FunSpec.Builder.buildExec(statement: SqlStatement) {
  if (statement.parameterBindings.isNotEmpty()) {
    beginControlFlow("driver.execute(sql) {")
    for (block in bindStatements(statement)) addCode("%L\n", block)
    addCode("execute()\n")
    endControlFlow()
  } else {
    addStatement("driver.execute(sql, %T::execute)", PreparedStatement::class)
  }
}

/**
 * Produces a function builder with a signature reflecting a SQL statement, a generic return type, a stream to take
 * multiple inputs, a batch size, and a mapper to produce the return type.
 *
 * Populates the throws, parameters, and return type using [statement].
 */
internal fun batchFunction(statement: SqlStatement): FunSpec.Builder = sqlFunction(statement).apply {
  parameters.clear()
  val t = TypeVariableName("Input", ANY)
  addTypeVariable(t)
  returns(INT_ARRAY)
  addParameter("stream", ITERABLE.parameterizedBy(t))

  val optionalIndices = statement.optionalParameterIndices.toSet()
  for ((index, parameter) in statement.parameters.withIndex()) {
    val lambda = LambdaTypeName.get(
      parameters = arrayOf(ParameterSpec.unnamed(t)),
      returnType = statement.resolveColumnType(parameter.column!!),
    )
    val parameterType = if (index in optionalIndices) lambda.copy(nullable = true) else lambda
    addParameter(statement.getParameterName(index), parameterType)
  }

  addParameter("batchSize", INT)
}

/**
 * Produces a function builder with a signature reflecting a SQL statement, a generic return type, a stream to take
 * multiple inputs, a batch size, a per-column extractor lambda for each insertable column, and a mapper to produce
 * the return type from the generated keys.
 *
 * Populates the throws, parameters, and return type using [statement].
 */
internal fun batchWithReturnFunction(statement: SqlStatement): FunSpec.Builder {
  // Unlike batchFunction (which can delegate to sqlFunction), this function cannot delegate to sqlFunction because
  // it introduces an additional type variable (the mapper return type T) and changes the return type to List<T>.
  // Starting from FunSpec.builder directly avoids fighting against the wrong signature.
  val inputType = TypeVariableName("Input", ANY)
  val resultRowShape = statement.resultRowShape
  val mapperReturnType = resultRowShape.mapperReturnType

  return FunSpec.builder(statement.name).apply {
    throws(SQLException::class)
    addTypeVariable(inputType)
    addTypeVariable(mapperReturnType)
    addParameter("stream", ITERABLE.parameterizedBy(inputType))

    val optionalIndices = statement.optionalParameterIndices.toSet()
    for ((index, parameter) in statement.parameters.withIndex()) {
      val lambda = LambdaTypeName.get(
        parameters = arrayOf(ParameterSpec.unnamed(inputType)),
        returnType = statement.resolveColumnType(parameter.column!!),
      )
      val parameterType = if (index in optionalIndices) lambda.copy(nullable = true) else lambda
      addParameter(statement.getParameterName(index), parameterType)
    }

    addParameter(
      ParameterSpec(
        MAPPER_PARAMETER_NAME,
        LambdaTypeName.get(
          parameters = resultRowShape.creationParameters.toTypedArray(),
          returnType = mapperReturnType,
        ),
      ),
    )

    addParameter("batchSize", INT)
    returns(LIST.parameterizedBy(mapperReturnType))
  }
}

/**
 * Builds a batch execution function for the given statement.
 *
 * Every `executeBatch()` result is captured into `results`, including intermediate flushes. Both `:exec` and
 * `:execrows` batch overloads return an `IntArray` with one entry per element of `stream`, so discarding an
 * intermediate flush drops entries from the returned array. When the element count is a nonzero exact multiple
 * of `batchSize` it leaves `results` empty altogether, because the trailing partial batch never runs.
 */
private fun buildBatch(statement: SqlStatement): FunSpec = batchFunction(statement).apply {
  addModifiers(KModifier.OVERRIDE)
  addStatement("val sql = %S", statement.sql)
  beginControlFlow("return driver.execute(sql) {")
  addCode(
    """
      |var totalCount = 0
      |var batchCount = 0
      |val results = mutableListOf<IntArray>()
      |
    """.trimMargin(),
  )
  beginControlFlow("for (entry in stream) {")
  for (block in bindStatements(statement) { CodeBlock.of("%L(entry)", it) }) addStatement("%L", block)
  addCode(
    """
      |addBatch()
      |batchCount++
      |if (batchCount == batchSize) {
      |  results.add(executeBatch())
      |  batchCount = 0
      |  // Performance optimization to reduce register updates per loop iteration
      |  totalCount += batchSize
      |}
      |
    """.trimMargin(),
  )
  endControlFlow()

  addCode(
    """
      |if (batchCount > 0) {
      |  results.add(executeBatch())
      |  totalCount += batchCount
      |}
      |%M(results, totalCount, batchSize)
      |
    """.trimMargin(),
    PROCESS_EXEC_RESULTS,
  )

  endControlFlow()
  returns(INT_ARRAY)
}.build()

/**
 * Builds a batch-with-return function for the given synthesized INSERT statement.
 *
 * Uses [NormDriver.executeBatchWithGeneratedKeys] to prepare the statement with column names so that
 * [java.sql.PreparedStatement.getGeneratedKeys] is available after each `executeBatch()`. After each
 * flush (when `batchCount == batchSize`) and after the final partial batch, drains generated keys via
 * [readGeneratedKeys] into an accumulating `List<T>`.
 */
private fun buildBatchWithReturn(statement: SqlStatement): FunSpec = batchWithReturnFunction(statement).apply {
  addModifiers(KModifier.OVERRIDE)

  if (statement.optionalParameterIndices.isEmpty()) {
    addStatement("val sql = %S", statement.batchSql)
  } else {
    addDynamicInsertSqlDeclaration(statement, statement.batchSql) { parameterIndex ->
      CodeBlock.of("%N != null", statement.getParameterName(parameterIndex))
    }
  }
  addStatement(
    "val columnNames = arrayOf(%L)",
    statement.returningColumnNames.joinToString(", ") { "\"$it\"" },
  )

  // Which optional columns are present, and their bind positions, are decided once per batch call
  // (per extractor argument), not per row -- unlike the single-row path, which re-checks per call.
  val optionalIndexNames = mutableMapOf<Int, String>()
  if (statement.optionalParameterIndices.isNotEmpty()) {
    addStatement("var nextParameterIndex = %L", statement.parameters.size - statement.optionalParameterIndices.size)
    for (parameterIndex in statement.optionalParameterIndices) {
      val parameterName = statement.getParameterName(parameterIndex)
      val indexName = "${parameterName}Index"
      optionalIndexNames[parameterIndex] = indexName
      addStatement(
        "val %N: %T = if (%N != null) { nextParameterIndex += 1; nextParameterIndex } else null",
        indexName,
        INT.copy(nullable = true),
        parameterName,
      )
    }
  }

  beginControlFlow("return driver.executeBatchWithGeneratedKeys(sql, columnNames) {")

  val resultRowShape = statement.resultRowShape
  beginControlFlow("val rowReader: %T.() -> %T = {", ResultSet::class, resultRowShape.mapperReturnType)
  addCode("%L\n", mapperInvocation(resultRowShape.builder))
  endControlFlow()

  addCode(
    """
      |val results = mutableListOf<%T>()
      |var batchCount = 0
      |
    """.trimMargin(),
    resultRowShape.mapperReturnType,
  )

  beginControlFlow("for (entry in stream) {")
  for (block in requiredBindStatements(statement) { CodeBlock.of("%L(entry)", it) }) addStatement("%L", block)
  for (parameterIndex in statement.optionalParameterIndices) {
    val parameterName = statement.getParameterName(parameterIndex)
    val indexName = optionalIndexNames.getValue(parameterIndex)
    val binding = statement.parameterBindings.first { it.parameterIndex == parameterIndex }
    val typeInfo = statement.resolveMappableType(binding.column)
    beginControlFlow("if (%N != null)", indexName)
    addStatement(
      "%L",
      typeInfo.statementAction(CodeBlock.of("%N", indexName), CodeBlock.of("%N!!(entry)", parameterName)),
    )
    endControlFlow()
  }
  addCode(
    """
      |addBatch()
      |batchCount++
      |if (batchCount == batchSize) {
      |  executeBatch()
      |  generatedKeys.use { %M(it, rowReader, results) }
      |  batchCount = 0
      |}
      |
    """.trimMargin(),
    READ_GENERATED_KEYS,
  )
  endControlFlow()

  addCode(
    """
      |if (batchCount > 0) {
      |  executeBatch()
      |  generatedKeys.use { %M(it, rowReader, results) }
      |}
      |results
      |
    """.trimMargin(),
    READ_GENERATED_KEYS,
  )

  endControlFlow()
}.build()

private val MANY_PROCESSOR = ClassName(RUNTIME_PACKAGE, "ManyProcessor")

private val PROCESS_EXEC_RESULTS = MemberName(RUNTIME_PACKAGE, "combineExecBatchResults")

private val READ_GENERATED_KEYS = MemberName(RUNTIME_PACKAGE, "readGeneratedKeys")

/**
 * Reference to [norm.ColumnValue], the runtime type wrapping an overridable-default INSERT column's
 * parameter (see [SqlStatement.optionalParameterIndices]).
 */
internal val COLUMN_VALUE_CLASS_NAME = ClassName(RUNTIME_PACKAGE, "ColumnValue")

/** Reference to [norm.ColumnValue.Default], the singleton meaning "use the column's own `DEFAULT`". */
internal val COLUMN_VALUE_DEFAULT_CLASS_NAME = COLUMN_VALUE_CLASS_NAME.nestedClass("Default")

/** Reference to [norm.ColumnValue.Set], the case carrying an explicit value to bind. */
internal val COLUMN_VALUE_SET_CLASS_NAME = COLUMN_VALUE_CLASS_NAME.nestedClass("Set")

/**
 * Name of the parameter representing a query mapper.
 *
 * The query mapper is a function that maps a result row to the Java type returned by the method.
 */
internal const val MAPPER_PARAMETER_NAME = "mapper"

/**
 * Produces a [CodeBlock] per JDBC bind position that sets the parameter on the [PreparedStatement].
 *
 * @param nameTransform Converts the parameter's name reference (built via `%N`, so a name needing
 *   backtick-escaping — e.g. `My Col` — is escaped exactly as [ParameterSpec]'s own declaration
 *   already is, unlike the plain string interpolation this replaced) into the code expression that
 *   provides the value. For single-item functions this is just the name reference itself; for batch
 *   functions it calls it as a function of `entry` (`%L(entry)` around the same escaped reference).
 */
private fun bindStatements(
  statement: SqlStatement,
  nameTransform: (CodeBlock) -> CodeBlock = { it },
): List<CodeBlock> = statement.parameterBindings.map { binding ->
  val typeInfo = statement.resolveMappableType(binding.column)
  val parameterNameReference = CodeBlock.of("%N", statement.getParameterName(binding.parameterIndex))
  val paramName = nameTransform(parameterNameReference)
  typeInfo.statementAction(CodeBlock.of("%L", binding.jdbcPosition), paramName)
}

/**
 * Like [bindStatements], but only for the columns NOT in [SqlStatement.optionalParameterIndices].
 *
 * Used for a CRUD-synthesized INSERT with at least one overridable-default column, whose optional
 * columns need their own dynamic-index bind code (built separately by each caller of this function)
 * rather than [bindStatements]' fixed-position one.
 */
private fun requiredBindStatements(
  statement: SqlStatement,
  nameTransform: (CodeBlock) -> CodeBlock = { it },
): List<CodeBlock> {
  val optionalIndices = statement.optionalParameterIndices.toSet()
  return statement.parameterBindings
    .filter { it.parameterIndex !in optionalIndices }
    .map { binding ->
      val typeInfo = statement.resolveMappableType(binding.column)
      val parameterNameReference = CodeBlock.of("%N", statement.getParameterName(binding.parameterIndex))
      val paramName = nameTransform(parameterNameReference)
      typeInfo.statementAction(CodeBlock.of("%L", binding.jdbcPosition), paramName)
    }
}

/**
 * Adds the `val sql = ...` declaration for a CRUD-synthesized INSERT with at least one
 * overridable-default column ([SqlStatement.optionalParameterIndices] non-empty).
 *
 * For each such column, first adds a `val xPlaceholder = if (<presenceCheck>) "?" else "DEFAULT"`
 * declaration, then splices those locals into the SQL text in place of that column's `?` --
 * producing a static column list whose VALUES entries alternate between a bound placeholder and the
 * literal `DEFAULT`, per [norm.generator.CrudQuerySynthesizer.synthesizeInsert]'s KDoc.
 *
 * @param templateSql [SqlStatement.sql] for the single-row path, [SqlStatement.batchSql] for the
 *   batch-with-return path -- flat text with exactly one `?` per entry in [SqlStatement.parameters],
 *   left to right, which is always true of [norm.generator.CrudQuerySynthesizer]'s own INSERT shape.
 * @param presenceCheck For an optional column's index into [SqlStatement.parameters], the runtime
 *   boolean expression that is `true` when the caller supplied a value for it: `%N is ColumnValue.Set`
 *   for the single-row path, `%N != null` (on the extractor parameter) for the batch path.
 */
private fun FunSpec.Builder.addDynamicInsertSqlDeclaration(
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
 * Builds `val sql = <segment> + <placeholder> + <segment> + ...` by splitting [templateSql] on its
 * literal `?` occurrences (one per [SqlStatement.parameters] entry, left to right — see
 * [addDynamicInsertSqlDeclaration]'s KDoc) and substituting each optional column's entry in
 * [placeholderExpressionsByIndex] for its `?`, while every other (required-column) `?` is copied
 * through as static text.
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

/**
 * Generates the invocation of a mapper function.
 *
 * See [MAPPER_PARAMETER_NAME] for the name of the mapper.
 */
private fun mapperInvocation(blocks: Iterable<CodeBlock>): CodeBlock = CodeBlock.Builder()
  .addStatement("%N(", MAPPER_PARAMETER_NAME)
  .indent()
  .apply {
    for (block in blocks) {
      addStatement("%L,", block)
    }
  }
  .unindent()
  .add(")")
  .build()
