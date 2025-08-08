package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.MemberName
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
      addKdoc("""
        Invokes [%N] with a batch size of %L.

        @return The number of rows updated.
        """.trimIndent(), batchFunction, BATCH_SIZE)
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
