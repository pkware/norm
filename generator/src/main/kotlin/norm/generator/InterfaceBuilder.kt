package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import plugin.Parameter
import java.sql.Statement

/**
 * Adds a method for the given SQL statement to the receiver `interface` builder.
 */
internal fun TypeSpec.Builder.addSqlStatementInterfaceMethod(query: SqlStatement) {
  val simpleFunction = sqlFunction(query)
  simpleFunction.addStandardKdoc(query)

  when (query.command) {
    Command.ONE, Command.MANY -> {
      // Generate the mapper function so consumers can control allocations and how columns are consumed.
      val mapperFunction = mapperFunction(query)
        .addModifiers(ABSTRACT)
      mapperFunction.addStandardKdoc(query)
      addFunction(mapperFunction.build())

      // The simple function delegates to the mapper function with a constructor reference.
      simpleFunction.addCode(buildMapperDelegationBody(query))
    }
    Command.EXEC, Command.EXEC_ROWS -> {
      simpleFunction.addModifiers(ABSTRACT)
      if (query.command == Command.EXEC_ROWS) {
        simpleFunction.addKdoc("@return The number of rows updated.\n")
      }

      if (query.canBeBatched) {
        addBatchOverloads(query)
      }
    }
  }
  addFunction(simpleFunction.build())

  if (query.canBeDynamic) {
    addDynamicInterfaceMethods(query)
  }
}

/**
 * Builds the delegation body that a simple function uses to call its mapper overload.
 *
 * Produces code like: `return queryName(param1, param2, ::RowType)`
 */
private fun buildMapperDelegationBody(query: SqlStatement): CodeBlock {
  val body = CodeBlock.builder().add("return %N(", query.name)
  for ((index, _) in query.parameters.asSequence().mapNotNull(Parameter::column).withIndex()) {
    body.add("%N, ", query.getParameterName(index))
  }
  if (query.resultRowShape.isComposedOfMultipleColumns) {
    body.add("%L)", (query.resultRowShape.kotlinType!! as ClassName).constructorReference())
  } else {
    body.add("%L)", COLUMN_VALUE)
  }
  return body.build()
}

/**
 * Adds the two batch overloads for an `:exec` or `:execrows` query: a full overload with a `batchSize` parameter,
 * and a convenience overload that delegates with a default batch size.
 */
private fun TypeSpec.Builder.addBatchOverloads(query: SqlStatement) {
  val batchFunction = batchFunction(query).build()

  // Full overload with explicit batchSize parameter
  addFunction(
    batchFunction.toBuilder()
      .apply { addBatchKdoc(query) }
      .addModifiers(ABSTRACT)
      .build(),
  )

  // Convenience overload that delegates with a default batch size
  val convenienceFunction = batchFunction.toBuilder().apply {
    parameters.removeLast()
    addBatchKdoc(query, "Uses a batch size of %L.\n\n", BATCH_SIZE)
    addCode(
      CodeBlock.builder()
        .add("return %N(", batchFunction)
        .apply {
          for (parameter in parameters) {
            add("%N, ", parameter)
          }
        }
        .add("%L)", BATCH_SIZE)
        .build(),
    )
  }
  addFunction(convenienceFunction.build())
}

/**
 * Adds dynamic query interface methods for the given SQL statement.
 *
 * Dynamic queries return [Query] instead of [Many], allowing callers to append SQL fragments
 * and bind parameters at runtime.
 */
private fun TypeSpec.Builder.addDynamicInterfaceMethods(query: SqlStatement) {
  val dynamicName = "${query.name}Dynamically"
  val resultRowShape = query.resultRowShape
  val mapperReturnType = resultRowShape.mapperReturnType

  // Abstract mapper function: fun <T : Any> queryNameDynamically(mapper: ...) -> Query<T>
  val dynamicMapperFunction = FunSpec.builder(dynamicName)
    .addTypeVariable(mapperReturnType)
    .addParameter(
      ParameterSpec(
        MAPPER_PARAMETER_NAME,
        LambdaTypeName.get(
          parameters = resultRowShape.creationParameters.toTypedArray(),
          returnType = mapperReturnType,
        ),
      ),
    )
    .returns(Command.NORM_QUERY.parameterizedBy(mapperReturnType))
    .addModifiers(ABSTRACT)
    .build()
  addFunction(dynamicMapperFunction)

  // Simple function: fun queryNameDynamically(): Query<RowType> = queryNameDynamically(::RowType)
  val dynamicSimpleFunction = FunSpec.builder(dynamicName)
    .returns(Command.NORM_QUERY.parameterizedBy(resultRowShape.kotlinType!!))
  val simpleFunctionBody = CodeBlock.builder()
    .add("return %N(", dynamicName)
  if (resultRowShape.isComposedOfMultipleColumns) {
    simpleFunctionBody.add("%L)", (resultRowShape.kotlinType as ClassName).constructorReference())
  } else {
    simpleFunctionBody.add("%L)", COLUMN_VALUE)
  }
  dynamicSimpleFunction.addCode(simpleFunctionBody.build())
  addFunction(dynamicSimpleFunction.build())
}

/**
 * Adds the standard KDoc block for a query method: user comments, SQL code block, optional extra text,
 * and `@param` tags.
 *
 * @param extraFormat Optional format string for additional text between the SQL block and the `@param` tags.
 * @param extraArgs Format arguments for [extraFormat].
 */
private fun FunSpec.Builder.addStandardKdoc(query: SqlStatement, extraFormat: String? = null, vararg extraArgs: Any) {
  if (query.comments.isNotEmpty()) {
    addKdoc(query.comments.joinToString("\n", postfix = "\n\n", transform = String::trim))
  }
  addKdoc("```sql\n%L\n```\n\n", query.sql)
  if (extraFormat != null) {
    addKdoc(extraFormat, *extraArgs)
  }
  for ((index, parameter) in query.parameters.asSequence().mapNotNull(Parameter::column).withIndex()) {
    val comment = parameter.comment
    if (comment.isNotEmpty()) {
      addKdoc("@param %L %L\n", query.getParameterName(index), comment)
    }
  }
}

/**
 * Adds the standard KDoc block for a batch function: comments, SQL, optional extra text, `@param` tags,
 * and the batch `@return` block.
 *
 * @param extraFormat Optional format string for additional text between the SQL block and the `@param` tags.
 * @param extraArgs Format arguments for [extraFormat].
 */
private fun FunSpec.Builder.addBatchKdoc(query: SqlStatement, extraFormat: String? = null, vararg extraArgs: Any) {
  addStandardKdoc(query, extraFormat, *extraArgs)
  addKdoc(
    """
    @return An array containing the result of each batch. The array has the same number as elements as [stream]
            had. The number in each slot can have one of several meanings:
            1. A number greater than or equal to zero -- indicates that the
               command was processed successfully and is an update count giving the
               number of rows in the database that were affected by the command's execution
            2. A value of [%M] -- indicates that the command was processed successfully
               but that the number of rows affected is unknown
            3. A value of [%M] -- indicates that the command failed to execute
               successfully and occurs only if a driver continues to process commands after a command fails
    """.trimIndent(),
    MemberName(Statement::class.asClassName(), "SUCCESS_NO_INFO"),
    MemberName(Statement::class.asClassName(), "EXECUTE_FAILED"),
  )
}

/**
 * Reference to a runtime method that returns the input value.
 *
 * Using this is more readable than using an inline lamda at each call site, and lets the JIT inline sooner.
 */
private val COLUMN_VALUE = MemberName(RUNTIME_PACKAGE, "inputValue").reference()

/**
 * Default batch size to use.
 *
 * The value was chosen somewhat arbitrarily, and does not currently have any requirements.
 */
private const val BATCH_SIZE = 100
