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
import plugin.Parameter

/**
 * Adds a method for the given SQL statement to the receiver `interface` builder.
 */
internal fun TypeSpec.Builder.addSqlStatementInterfaceMethod(query: SqlStatement) {
  val interfaceBuilder = this
	/*
	 * For each query, we potentially generate multiple functions: a simple function that maps rows into value objects,
	 * and a mapper function that takes a lambda allowing the row to be mapped as-desired. These have similar signatures,
	 * so we have the baseFunction that encapsulates that.
	 */
  val simpleFunction = sqlFunction(query)

  // Not every query needs a mapper function, but it's easier to build it up in code here and end up not attaching it to
  // the TypeSpec than it is to build it up conditionally.
  val mapperFunction = mapperFunction(query)
    .addModifiers(ABSTRACT)
  val simpleFunctionBody = CodeBlock.builder()
    .add("return %N(", query.name)

  for (parameter in query.parameters.asSequence().mapNotNull(Parameter::column).toSet()) {
    simpleFunctionBody.add("%N, ", parameter.name)
  }

  if (query.resultRowShape.isComposedOfMultipleColumns) {
    simpleFunctionBody.add("%L)", (query.resultRowShape.kotlinType!! as ClassName).constructorReference())
  } else {
    simpleFunctionBody.add("%L)", COLUMN_VALUE)
  }
  if (query.command != Command.EXEC_ROWS) {
    // We're going to generate the mapper function so consumers have the power to control allocations and how the
    // columns are consumed.
    interfaceBuilder.addFunction(mapperFunction.build())
    simpleFunction.addCode(simpleFunctionBody.build())
  } else {
    val kdoc = """
        Executes a SQL statement and returns the number of rows updated.

        @return The number of rows updated.
    """.trimIndent()
    simpleFunction
      .addModifiers(ABSTRACT)
      .addKdoc(kdoc)
    val batchFunction = batchFunction(query)
      .build()

    // Full parameter function to allow customization of batch size
    interfaceBuilder.addFunction(
      batchFunction.toBuilder()
        .addKdoc(kdoc)
        .addModifiers(ABSTRACT)
        .build(),
    )

    val batchSizedFunction = batchFunction.toBuilder().apply {
      parameters.removeLast()
      addKdoc(
        """
        Invokes [%N] with a batch size of %L.

        @return The number of rows updated.
        """.trimIndent(),
        batchFunction,
        BATCH_SIZE,
      )
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
    interfaceBuilder.addFunction(batchSizedFunction.build())
  }
  interfaceBuilder.addFunction(simpleFunction.build())

  // Generate dynamic query variants for eligible queries
  if (query.canBeDynamic) {
    interfaceBuilder.addDynamicInterfaceMethods(query)
  }
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

private const val MAPPER_PARAMETER_NAME = "mapper"

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
