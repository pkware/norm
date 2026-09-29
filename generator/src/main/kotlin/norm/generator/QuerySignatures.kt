package norm.generator

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.INT_ARRAY
import com.squareup.kotlinpoet.ITERABLE
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.jvm.throws
import java.sql.SQLException

/**
 * Creates the builder for the query method of [statement], with the statement's parameters and result type.
 *
 * Every command except `:many` declares `SQLException`.
 */
internal fun sqlFunction(statement: SqlStatement): FunSpec.Builder {
  val function = FunSpec.builder(statement.name)

  if (statement.command != Command.MANY) {
    function.throws(SQLException::class)
  }

  val optionalIndices = statement.optionalParameterIndices.toSet()
  for (index in statement.parameters.indices) {
    val parameterName = statement.getParameterName(index)
    val columnType = statement.parameterTypes[index]
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
 * Extends [sqlFunction] with a type variable for the mapped row and a trailing [MAPPER_PARAMETER_NAME] lambda that
 * produces it.
 */
internal fun mapperFunction(statement: SqlStatement): FunSpec.Builder {
  val mapperReturnType = statement.resultRowShape.mapperReturnType
  val function = sqlFunction(statement)
    .addTypeVariable(mapperReturnType)
    .returns(statement.command.applyTo(mapperReturnType))

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
 * Adds one lambda parameter per entry of [SqlStatement.parameters] that extracts the parameter's value from an
 * [inputType]. Parameters in [SqlStatement.optionalParameterIndices] take a nullable lambda.
 */
private fun FunSpec.Builder.addParameterExtractors(statement: SqlStatement, inputType: TypeVariableName) {
  val optionalIndices = statement.optionalParameterIndices.toSet()
  for (index in statement.parameters.indices) {
    val lambda = LambdaTypeName.get(
      parameters = arrayOf(ParameterSpec.unnamed(inputType)),
      returnType = statement.parameterTypes[index],
    )
    val parameterType = if (index in optionalIndices) lambda.copy(nullable = true) else lambda
    addParameter(statement.getParameterName(index), parameterType)
  }
}

/**
 * `:exec` and `:execrows` batch methods take a `stream` of inputs and return one update count per element.
 *
 * Extractor lambdas read each parameter from an input.
 */
internal fun batchFunction(statement: SqlStatement): FunSpec.Builder = sqlFunction(statement).apply {
  parameters.clear()
  val t = TypeVariableName("Input", ANY)
  addTypeVariable(t)
  returns(INT_ARRAY)
  addParameter("stream", ITERABLE.parameterizedBy(t))

  addParameterExtractors(statement, t)

  addParameter("batchSize", INT)
}

/**
 * The batch method of a synthesized INSERT maps each generated-key row through [MAPPER_PARAMETER_NAME] and returns
 * the mapped rows as a `List`.
 */
internal fun batchWithReturnFunction(statement: SqlStatement): FunSpec.Builder {
  // sqlFunction supplies neither the mapper type variable nor the `List` return type, so this starts from a bare
  // builder.
  val inputType = TypeVariableName("Input", ANY)
  val resultRowShape = statement.resultRowShape
  val mapperReturnType = resultRowShape.mapperReturnType

  return FunSpec.builder(statement.name).apply {
    throws(SQLException::class)
    addTypeVariable(inputType)
    addTypeVariable(mapperReturnType)
    addParameter("stream", ITERABLE.parameterizedBy(inputType))

    addParameterExtractors(statement, inputType)

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
 * The runtime type `ColumnValue` wraps the parameter of an overridable-default INSERT column (see
 * [SqlStatement.optionalParameterIndices]).
 */
internal val COLUMN_VALUE_CLASS_NAME = ClassName(RUNTIME_PACKAGE, "ColumnValue")

/** `ColumnValue.Default` is the runtime singleton that selects the column's own `DEFAULT`. */
internal val COLUMN_VALUE_DEFAULT_CLASS_NAME = COLUMN_VALUE_CLASS_NAME.nestedClass("Default")

/** `ColumnValue.Set` carries an explicit value to bind. */
internal val COLUMN_VALUE_SET_CLASS_NAME = COLUMN_VALUE_CLASS_NAME.nestedClass("Set")

/** Names the parameter that receives the lambda mapping a result row to the type the method returns. */
internal const val MAPPER_PARAMETER_NAME = "mapper"
