package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import java.sql.Statement

/**
 * Adds a method for the given SQL statement to the receiver `interface` builder.
 */
internal fun TypeSpec.Builder.addSqlStatementInterfaceMethod(query: SqlStatement) {
  val simpleFunction = sqlFunction(query)
  simpleFunction.addStandardKdoc(query)
  simpleFunction.applyOptionalParameterDefaults(
    query,
    parameterOffset = 0,
    defaultValue = CodeBlock.of("%T", COLUMN_VALUE_DEFAULT_CLASS_NAME),
  )

  when (query.command) {
    Command.ONE, Command.MANY -> {
      // Generate the mapper function so consumers can control allocations and how columns are consumed.
      val mapperFunction = mapperFunction(query)
        .addModifiers(ABSTRACT)
      mapperFunction.addStandardKdoc(query)
      mapperFunction.applyOptionalParameterDefaults(
        query,
        parameterOffset = 0,
        defaultValue = CodeBlock.of("%T", COLUMN_VALUE_DEFAULT_CLASS_NAME),
      )
      addFunction(mapperFunction.build())

      // The simple function delegates to the mapper function with a constructor reference.
      simpleFunction.addCode(buildMapperDelegationBody(query))

      if (query.canBeBatchedWithReturn) {
        addBatchWithReturnOverloads(query)
      }
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
  for (index in query.parameters.indices) {
    body.add("%N, ", query.getParameterName(index))
  }
  if (query.resultRowShape.isComposedOfMultipleColumns) {
    body.add("%L)", (query.resultRowShape.kotlinType!! as ClassName).constructorReference())
  } else {
    body.add("%L)", INPUT_VALUE_REFERENCE)
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
 * Adds the two batch-with-return overloads for a CRUD-synthesized INSERT `:one` query: a full overload with explicit
 * `mapper` and `batchSize` parameters returning `List<T>`, and a convenience overload that delegates with a default
 * batch size and a constructor reference (or [COLUMN_VALUE] for single-column results).
 */
private fun TypeSpec.Builder.addBatchWithReturnOverloads(query: SqlStatement) {
  val batchFunction = batchWithReturnFunction(query)
    .apply { applyOptionalParameterDefaults(query, parameterOffset = 1, defaultValue = CodeBlock.of("null")) }
    .build()

  // Full overload: abstract, with all parameters (stream, extractors, mapper, batchSize) → List<T>
  addFunction(
    batchFunction.toBuilder()
      .apply { addBatchWithReturnKdoc(query) }
      .addModifiers(ABSTRACT)
      .build(),
  )

  // Convenience overload: default batchSize + constructor reference mapper, concrete return type
  val resultRowShape = query.resultRowShape
  val concreteReturnType = checkNotNull(resultRowShape.kotlinType) {
    "Expected a non-null kotlinType for batch-with-return query ${query.name}"
  }
  val convenienceFunction = batchFunction.toBuilder().apply {
    parameters.removeLast() // batchSize
    parameters.removeLast() // mapper
    // Remove the T type variable — the concrete type is inferred from the mapper reference below.
    typeVariables.removeLast()
    returns(LIST.parameterizedBy(concreteReturnType))
    addBatchWithReturnKdoc(query, "Uses a batch size of %L.\n\n", BATCH_SIZE)
    val mapperRef = if (resultRowShape.isComposedOfMultipleColumns) {
      (concreteReturnType as ClassName).constructorReference()
    } else {
      INPUT_VALUE_REFERENCE
    }
    addCode(
      CodeBlock.builder()
        .add("return %N(", batchFunction)
        .apply {
          for (parameter in parameters) {
            add("%N, ", parameter)
          }
        }
        .add("%L, %L)", mapperRef, BATCH_SIZE)
        .build(),
    )
  }
  addFunction(convenienceFunction.build())
}

/**
 * Adds the standard KDoc block for a batch-with-return function: user comments, SQL code block, optional extra text,
 * `@param` tags, and a `@return` tag describing the returned list.
 *
 * @param extraFormat Optional format string for additional text between the SQL block and the `@param` tags.
 * @param extraArgs Format arguments for [extraFormat].
 */
private fun FunSpec.Builder.addBatchWithReturnKdoc(
  query: SqlStatement,
  extraFormat: String? = null,
  vararg extraArgs: Any,
) {
  addStandardKdoc(query, extraFormat, *extraArgs)
  addKdoc("@return A list containing the generated values for each inserted row, in insertion order.\n")
}

/**
 * Adds dynamic query interface methods for the given SQL statement.
 *
 * Dynamic queries return [Query], allowing callers to append SQL fragments and bind parameters at
 * runtime.
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
    simpleFunctionBody.add("%L)", INPUT_VALUE_REFERENCE)
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
    // A "%L" argument keeps any "%" in query.comments from being parsed as a format specifier.
    addKdoc("%L\n\n", query.comments.joinToString("\n", transform = String::trim))
  }
  // TypeRepository.addClassKdoc declines its own "sql" fenced block for this identical query text via
  // the same containsUnescapableBlockCommentDelimiter guard, so the two KDoc blocks never disagree
  // about whether the query can be rendered faithfully.
  if (!containsUnescapableBlockCommentDelimiter(query.sql)) {
    // Sized to markdownFenceDelimiter(query.sql), one backtick longer than any run already in the
    // query, so a query containing its own line of 3+ backticks cannot be mistaken for this fence's
    // closing line.
    val fence = markdownFenceDelimiter(query.sql)
    addKdoc("%Lsql\n%L\n%L\n\n", fence, query.sql, fence)
  }
  if (extraFormat != null) {
    addKdoc(extraFormat, *extraArgs)
  }
  for ((index, parameter) in query.parameters.withIndex()) {
    val comment = parameter.column!!.comment
    if (comment.isNotEmpty()) {
      // Consecutive `@param` lines share one CommonMark paragraph (joined by "\n", never a blank
      // line), so a stray, unpaired backtick in one comment could pair with a backtick in a later
      // parameter's comment instead, corrupting every `@param` line in between.
      addKdoc("@param %L %L\n", query.getParameterName(index), escapeMarkdownBacktick(comment))
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
 *
 * Not to be confused with `norm.ColumnValue` ([COLUMN_VALUE_CLASS_NAME]) — that's the public runtime
 * type wrapping an overridable-default column's parameter; this is an internal mapper-reference helper.
 */
private val INPUT_VALUE_REFERENCE = MemberName(RUNTIME_PACKAGE, "inputValue").reference()

/**
 * Default batch size to use.
 *
 * The value is arbitrary; no requirement constrains it.
 */
private const val BATCH_SIZE = 100

/**
 * Sets [defaultValue] as the default of each parameter in [SqlStatement.optionalParameterIndices].
 *
 * Kotlin forbids default values on overriding parameters, so call this only on interface declarations.
 *
 * @param parameterOffset index in [FunSpec.Builder.parameters] of the entry for [SqlStatement.parameters] index `0`.
 * @param defaultValue the expression written after `=` in each optional parameter.
 */
private fun FunSpec.Builder.applyOptionalParameterDefaults(
  statement: SqlStatement,
  parameterOffset: Int,
  defaultValue: CodeBlock,
) {
  for (index in statement.optionalParameterIndices) {
    val position = parameterOffset + index
    parameters[position] = parameters[position].toBuilder().defaultValue(defaultValue).build()
  }
}
