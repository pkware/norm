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
import plugin.Parameter
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

  // Add all parameters with deduplicated names
  for ((index, parameter) in statement.parameters.asSequence().mapNotNull(Parameter::column).withIndex()) {
    val parameterName = statement.getParameterName(index)
    val parameterType = statement.resolveColumnType(parameter)
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
    addStatement("val sql = %S", statement.sql)
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
  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  // 1. Private helper function
  val helperFunction = FunSpec.builder(statement.name)
    .addModifiers(KModifier.PRIVATE)
    .addTypeVariable(mapperReturnType)
    .addTypeVariable(returnTypeVariable)
    .apply {
      // Add query parameters first
      for ((index, parameter) in queryParameters.withIndex()) {
        addParameter(ParameterSpec(statement.getParameterName(index), statement.resolveColumnType(parameter)))
      }
    }
    .addParameter(
      ParameterSpec(
        MAPPER_PARAMETER_NAME,
        LambdaTypeName.get(
          parameters = resultRowShape.creationParameters.toTypedArray<ParameterSpec>(),
          returnType = mapperReturnType,
        ),
      ),
    )
    .addParameter(
      "processor",
      ClassName("norm", "ManyProcessor")
        .parameterizedBy(mapperReturnType, returnTypeVariable),
    )
    .returns(returnTypeVariable)
    .addStatement("val sql = %S", statement.sql)
    .apply {
      beginControlFlow("val rowReader: %T.() -> %T = {", ResultSet::class, mapperReturnType)
      addCode("%L\n", mapperInvocation(resultRowShape.builder))
      endControlFlow()
      if (queryParameters.isNotEmpty()) {
        beginControlFlow(
          "val queryBinder: (%T.() -> %T)? = {",
          PreparedStatement::class,
          Unit::class,
        )
        for ((index, parameter) in queryParameters.withIndex()) {
          val typeInfo = statement.resolveMappableType(parameter)
          addCode("%L\n", typeInfo.statementAction(index + 1, CodeBlock.of(statement.getParameterName(index))))
        }
        endControlFlow()
        addStatement("return processor.invoke(sql, rowReader, queryBinder)")
      } else {
        addStatement("return processor.invoke(sql, rowReader, null)")
      }
    }
    .build()
  addFunction(helperFunction)
  // 2. Public Many variant: override fun <T : Any> queryName(mapper: ...) -> Many<T>
  val manyFunction = FunSpec.builder(statement.name)
    .addModifiers(KModifier.OVERRIDE)
    .addTypeVariable(mapperReturnType)
    .apply {
      for ((index, parameter) in queryParameters.withIndex()) {
        addParameter(ParameterSpec(statement.getParameterName(index), statement.resolveColumnType(parameter)))
      }
    }
    .addParameter(
      ParameterSpec(
        MAPPER_PARAMETER_NAME,
        LambdaTypeName.get(
          parameters = resultRowShape.creationParameters.toTypedArray<ParameterSpec>(),
          returnType = mapperReturnType,
        ),
      ),
    )
    .returns(statement.command.applyTo(mapperReturnType))
    .apply {
      val args = (
        queryParameters.indices.map { CodeBlock.of("%N", statement.getParameterName(it)) } + listOf(
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
    val dynamicName = "${statement.name}Dynamically"
    val dynamicFunction = FunSpec.builder(dynamicName)
      .addModifiers(KModifier.OVERRIDE)
      .addTypeVariable(mapperReturnType)
      .addParameter(
        ParameterSpec(
          MAPPER_PARAMETER_NAME,
          LambdaTypeName.get(
            parameters = resultRowShape.creationParameters.toTypedArray<ParameterSpec>(),
            returnType = mapperReturnType,
          ),
        ),
      )
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
    addFunction(buildBatch(statement, trackIntermediateResults = true))
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
    addFunction(buildBatch(statement, trackIntermediateResults = false))
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

  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  if (queryParameters.isNotEmpty()) {
    beginControlFlow("return driver.queryOne(sql, rowReader) {")
    for ((index, parameter) in queryParameters.withIndex()) {
      val typeInfo = statement.resolveMappableType(parameter)
      addCode("%L\n", typeInfo.statementAction(index + 1, CodeBlock.of(statement.getParameterName(index))))
    }
    endControlFlow()
  } else {
    addStatement("return driver.queryOne(sql, rowReader)")
  }
}

private fun FunSpec.Builder.buildExecRows(statement: SqlStatement) {
  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  if (queryParameters.isNotEmpty()) {
    beginControlFlow("return driver.executeRows(sql) {")
    for ((index, parameter) in queryParameters.withIndex()) {
      val typeInfo = statement.resolveMappableType(parameter)
      addCode("%L\n", typeInfo.statementAction(index + 1, CodeBlock.of(statement.getParameterName(index))))
    }
    endControlFlow()
  } else {
    addStatement("return driver.executeRows(sql)")
  }
}

private fun FunSpec.Builder.buildExec(statement: SqlStatement) {
  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  if (queryParameters.isNotEmpty()) {
    beginControlFlow("driver.execute(sql) {")
    for ((index, parameter) in queryParameters.withIndex()) {
      val typeInfo = statement.resolveMappableType(parameter)
      addCode("%L\n", typeInfo.statementAction(index + 1, CodeBlock.of(statement.getParameterName(index))))
    }
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

  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  for ((index, parameter) in queryParameters.withIndex()) {
    val lambda = LambdaTypeName.get(
      receiver = t,
      returnType = statement.resolveColumnType(parameter),
    )
    addParameter(statement.getParameterName(index), lambda)
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

    val queryParameters = statement.parameters.mapNotNull(Parameter::column)
    for ((index, parameter) in queryParameters.withIndex()) {
      val lambda = LambdaTypeName.get(
        receiver = inputType,
        returnType = statement.resolveColumnType(parameter),
      )
      addParameter(statement.getParameterName(index), lambda)
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
 * @param trackIntermediateResults When `true`, intermediate `executeBatch()` results are captured into `results`
 *   and `totalCount` is tracked per flush. Used by `:execrows` where the per-batch counts matter.
 *   When `false`, intermediate flushes call `executeBatch()` without capturing. Used by `:exec`.
 */
private fun buildBatch(statement: SqlStatement, trackIntermediateResults: Boolean): FunSpec =
  batchFunction(statement).apply {
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
    for ((index, parameter) in statement.parameters.asSequence().mapNotNull(Parameter::column).withIndex()) {
      val typeInfo = statement.resolveMappableType(parameter)
      addStatement(
        "%L",
        typeInfo.statementAction(index + 1, CodeBlock.of("entry.${statement.getParameterName(index)}()")),
      )
    }
    if (trackIntermediateResults) {
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
    } else {
      addCode(
        """
				|addBatch()
				|batchCount++
				|if (batchCount == batchSize) {
				|  executeBatch()
				|  batchCount = 0
				|}
				|
        """.trimMargin(),
      )
    }
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
  addStatement("val sql = %S", statement.batchSql)
  addStatement(
    "val columnNames = arrayOf(%L)",
    statement.returningColumnNames.joinToString(", ") { "\"$it\"" },
  )

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
  for ((index, parameter) in statement.parameters.asSequence().mapNotNull(Parameter::column).withIndex()) {
    val typeInfo = statement.resolveMappableType(parameter)
    addStatement(
      "%L",
      typeInfo.statementAction(index + 1, CodeBlock.of("entry.${statement.getParameterName(index)}()")),
    )
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

private val PROCESS_EXEC_RESULTS = MemberName(RUNTIME_PACKAGE, "combineExecBatchResults")

private val READ_GENERATED_KEYS = MemberName(RUNTIME_PACKAGE, "readGeneratedKeys")

/**
 * Name of the parameter representing a query mapper.
 *
 * The query mapper is a function that maps a result row to the Java type returned by the method.
 */
internal const val MAPPER_PARAMETER_NAME = "mapper"

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
