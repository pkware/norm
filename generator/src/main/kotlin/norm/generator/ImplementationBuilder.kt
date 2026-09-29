package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.INT_ARRAY
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import java.sql.PreparedStatement
import java.sql.ResultSet

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
      val typeInfo = binding.mappable
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
    val typeInfo = binding.mappable
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
 * Produces a [CodeBlock] per JDBC bind position that sets the parameter on the [PreparedStatement].
 *
 * @param nameTransform turns the `%N` name reference into the value expression. Batch functions wrap it
 *   as `%L(entry)` to call the extractor lambda.
 */
private fun bindStatements(
  statement: SqlStatement,
  nameTransform: (CodeBlock) -> CodeBlock = { it },
): List<CodeBlock> = statement.parameterBindings.map { bindStatement(statement, it, nameTransform) }

/**
 * Produces the [bindStatements] output for parameters outside [SqlStatement.optionalParameterIndices].
 *
 * Callers bind the optional parameters themselves at dynamic positions.
 */
private fun requiredBindStatements(
  statement: SqlStatement,
  nameTransform: (CodeBlock) -> CodeBlock = { it },
): List<CodeBlock> {
  val optionalIndices = statement.optionalParameterIndices.toSet()
  return statement.parameterBindings
    .filter { it.parameterIndex !in optionalIndices }
    .map { bindStatement(statement, it, nameTransform) }
}

/**
 * Builds the code that binds [binding] to its JDBC position, reading the value through [nameTransform] applied to
 * the parameter's name reference.
 */
private fun bindStatement(
  statement: SqlStatement,
  binding: ParameterBinding,
  nameTransform: (CodeBlock) -> CodeBlock,
): CodeBlock {
  val parameterNameReference = CodeBlock.of("%N", statement.getParameterName(binding.parameterIndex))
  val value = nameTransform(parameterNameReference)
  return binding.mappable.statementAction(CodeBlock.of("%L", binding.jdbcPosition), value)
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
