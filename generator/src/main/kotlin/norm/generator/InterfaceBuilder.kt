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
  val interfaceBuilder = this
	/*
	 * For each query, we potentially generate multiple functions: a simple function that maps rows into value objects,
	 * and a mapper function that takes a lambda allowing the row to be mapped as-desired. These have similar signatures.
	 */
  val simpleFunction = sqlFunction(query)
  if (query.comments.isNotEmpty()) {
    simpleFunction.addKdoc(query.comments.joinToString("\n", postfix = "\n\n", transform = String::trim))
  }

  // Not every query needs a mapper function, but it's easier to build it up in code here and end up not attaching it to
  // the TypeSpec than it is to build it up conditionally.
  val mapperFunction = mapperFunction(query)
    .addModifiers(ABSTRACT)
  if (query.comments.isNotEmpty()) {
    mapperFunction.addKdoc(query.comments.joinToString("\n", postfix = "\n\n", transform = String::trim))
  }

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
  when (query.command) {
    Command.ONE, Command.MANY -> {
      // We're going to generate the mapper function so consumers have the power to control allocations and how the
      // columns are consumed.
      interfaceBuilder.addFunction(mapperFunction.build())
      simpleFunction.addCode(simpleFunctionBody.build())
    }
    Command.EXEC, Command.EXEC_ROWS -> {
      val kdoc = if (query.command == Command.EXEC_ROWS) {
        """
        Norm: Executes a SQL statement and returns the number of rows updated.

        @return The number of rows updated.
        """.trimIndent()
      } else {
        """
        Norm: Executes a SQL statement.
        """.trimIndent()
      }
      simpleFunction
        .addModifiers(ABSTRACT)
        .addKdoc(kdoc)

      if (query.canBeBatched) {
        val batchFunction = batchFunction(query)
          .build()

        // Full parameter function to allow customization of batch size
        interfaceBuilder.addFunction(
          batchFunction.toBuilder()
            .addKdoc(kdoc)
            .addKdoc(
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
            .addModifiers(ABSTRACT)
            .build(),
        )

        val batchSizedFunction = batchFunction.toBuilder().apply {
          parameters.removeLast()
          addKdoc(
            "Norm: Invokes [%N] with a batch size of %L.",
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
    }
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
