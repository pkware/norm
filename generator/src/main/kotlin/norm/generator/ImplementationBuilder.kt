package norm.generator

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.INT_ARRAY
import com.squareup.kotlinpoet.ITERABLE
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.jvm.throws
import plugin.Parameter
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock

/**
 * Produces a function builder with a signature reflecting a SQL statement.
 *
 * Populates the throws, kdoc, parameters, and return type using [statement].
 */
internal fun sqlFunction(statement: SqlStatement): FunSpec.Builder {
  val function = FunSpec.builder(statement.name)
    .throws(SQLException::class)
    .addKdoc(statement.comments.joinToString("\n", transform = String::trim))

  for (parameter in statement.parameters.asSequence().mapNotNull(Parameter::column).toSet()) {
    val parameterSpec = ParameterSpec.builder(parameter.name, parameter.typeName)
      // TODO Comments don't work
      .addKdoc(parameter.comment)
      .build()
    function.addParameter(parameterSpec)
  }

  val returnType = statement.command.applyTo(statement.resultRowShape.kotlinType)
  function.returns(returnType)

  return function
}

/**
 * Produces a function builder with a signature reflecting a SQL statement, a generic return type, and a mapper to
 * produce the return type.
 *
 * Populates the throws, kdoc, parameters, and return type using [statement].
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
 * Adds a method for the given SQL statement to the receiver `class` builder.
 */
internal fun TypeSpec.Builder.addSqlStatementImplementationMethod(statement: SqlStatement) {
  val classBuilder = this
  val function = mapperFunction(statement).apply {
    addModifiers(KModifier.OVERRIDE)
    addStatement("val sql = %S", statement.sql)
    when (statement.command) {
      Command.ONE -> buildOne(statement)
      Command.MANY -> buildMany(statement)
      Command.EXEC_ROWS -> {
        typeVariables.clear()
        parameters.removeLast()
        buildExecRows(statement)
      }
      Command.EXEC -> TODO(Command.EXEC.toString())
    }
  }
  classBuilder.addFunction(function.build())

  if (statement.canBeBatched) {
    classBuilder.addFunction(buildExecRowsBatch(statement))
  }
}

/**
 * Builds a function that returns exactly 1, non-null result.
 *
 * The query author is in the best position to determine if a query is capable of returning no or some results.
 * The caller in Java shouldn't have to think about that.
 * Accordingly, we make this query be exact and the [buildMany] query flexible on return number.
 */
private fun FunSpec.Builder.buildOne(statement: SqlStatement) {
  val resultRowShape = statement.resultRowShape
  beginControlFlow("val rowReader: %T.() -> %T = {", RESULT_SET, resultRowShape.mapperReturnType)
  addCode("%L\n", mapperInvocation(resultRowShape.builder))
  // Close the rowReader
  endControlFlow()

  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  if (queryParameters.isNotEmpty()) {
    beginControlFlow("return driver.queryOne(sql, rowReader) {")
    for ((index, parameter) in queryParameters.withIndex()) {
      val typeInfo = parameter.mappableType
      addCode("%L\n", typeInfo.statementAction(index + 1, CodeBlock.of(parameter.name)))
    }
    endControlFlow()
  } else {
    addStatement("return driver.queryOne(sql, rowReader)")
  }
}

/**
 * Builds a function that returns 0 to many results.
 *
 * The query author is in the best position to determine if a query is capable of returning no or some results.
 * The caller in Java shouldn't have to think about that.
 * Accordingly, we make this query return a `Many` and the [buildOne] query be exact.
 */
private fun FunSpec.Builder.buildMany(statement: SqlStatement) {
  val resultRowShape = statement.resultRowShape
  beginControlFlow("val rowReader: %T.() -> %T = {", RESULT_SET, resultRowShape.mapperReturnType)
  addCode("%L\n", mapperInvocation(resultRowShape.builder))
  // Close the rowReader
  endControlFlow()

  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  if (queryParameters.isNotEmpty()) {
    beginControlFlow("return driver.queryMany(sql, rowReader) {")
    for ((index, parameter) in queryParameters.withIndex()) {
      val typeInfo = parameter.mappableType
      addCode("%L\n", typeInfo.statementAction(index + 1, CodeBlock.of(parameter.name)))
    }
    endControlFlow()
  } else {
    addStatement("return driver.queryMany(sql, rowReader)")
  }
}

private fun FunSpec.Builder.buildExecRows(statement: SqlStatement) {
  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  if (queryParameters.isNotEmpty()) {
    beginControlFlow("return driver.executeRows(sql) {")
    for ((index, parameter) in queryParameters.withIndex()) {
      val typeInfo = parameter.mappableType
      addCode("%L\n", typeInfo.statementAction(index + 1, CodeBlock.of(parameter.name)))
    }
    endControlFlow()
  } else {
    addStatement("return driver.executeRows(sql)")
  }
}

/**
 * Produces a function builder with a signature reflecting a SQL statement, a generic return type, a stream to take
 * multiple inputs, a batch size, and a mapper to produce the return type.
 *
 * Populates the throws, kdoc, parameters, and return type using [statement].
 */
internal fun batchFunction(statement: SqlStatement): FunSpec.Builder = sqlFunction(statement).apply {
  parameters.clear()
  val t = TypeVariableName("Input", ANY)
  addTypeVariable(t)
  returns(INT_ARRAY)
  addParameter("stream", ITERABLE.parameterizedBy(t))

  val queryParameters = statement.parameters.mapNotNull(Parameter::column)
  for (parameter in queryParameters) {
    val lamda = LambdaTypeName.get(
      receiver = t,
      returnType = parameter.typeName,
    )
    addParameter(parameter.name, lamda)
  }

  addParameter("batchSize", INT)
}

private fun buildExecRowsBatch(statement: SqlStatement): FunSpec = batchFunction(statement).apply {
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
    ReentrantLock::class.asTypeName(),
  )
  beginControlFlow("for (entry in stream) {")
  for ((index, parameter) in statement.parameters.asSequence().mapNotNull(Parameter::column).withIndex()) {
    val typeInfo = parameter.mappableType
    addStatement("%L", typeInfo.statementAction(index + 1, CodeBlock.of("entry.${parameter.name}()")))
  }
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
  // Close the forEach
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

  // Close the query
  endControlFlow()

  returns(INT_ARRAY)
}.build()

private val RESULT_SET = ResultSet::class.asTypeName()

private val PROCESS_EXEC_RESULTS = MemberName(RUNTIME_PACKAGE, "combineExecBatchResults")

/**
 * Name of the parameter representing a query mapper.
 *
 * The query mapper is a function that maps a result row to the Java type returned by the method.
 */
private const val MAPPER_PARAMETER_NAME = "mapper"

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
