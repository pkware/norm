package com.pkware.norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import plugin.Catalog
import plugin.Column
import plugin.Table

/**
 * Repository for types generated as part of query generation.
 *
 * @param packageName to use for generated types.
 * @param catalog Postgres catalog to use when resolving projection information.
 */
class TypeRepository(
  private val packageName: String,
  private val catalog: Catalog,
) {
  private val tableModels = mutableMapOf<Table, Pair<ReturnType, TypeSpec>>()
  private val queryModels = mutableListOf<Pair<ReturnType, TypeSpec>>()

  /**
   * Types generated during query generation that are needed for complete compilation of query code.
   */
  val requiredTypes: Sequence<TypeSpec>
    get() = sequence {
      yieldAll(queryModels.asSequence().map { it.second })
      yieldAll(tableModels.values.asSequence().map { it.second })
    }

  /**
   * Builds a type projection for a Kotlin representation of the columns in this table.
   *
   * This is similar to what ORM entity mappings are, in that the model will have a property for each column in the
   * table.
   *
   * A [TypeSpec] will be registered for the created Kotlin class.
   *
   * This function can be used to load types that are
   * [embedded](https://docs.sqlc.dev/en/latest/reference/macros.html#sqlc-embed). Embedded types may be at an index
   * other than `1`, so an offset can be provided to adjust how column accessors are generated.
   *
   * @param table for which to generate the model.
   * @param columnOffset Column index offset to use when generating column accessors.
   */
  fun getTypeProjectionForTable(table: Table, columnOffset: Int = 1): ReturnType = tableModels.computeIfAbsent(table) {
    val tableName = table.rel!!.name
      .snakeToCamelCase()
      .titleCase()
    val nameOfTypeBeingDefined = ClassName(packageName, tableName)
    val typeBeingDefined = TypeSpec.classBuilder(nameOfTypeBeingDefined)
      .addModifiers(KModifier.DATA)
    val mapperArguments = mutableListOf<CodeBlock>()
    val primaryConstructor = FunSpec.constructorBuilder()
    // Parameters required to invoke the mapper
    val mapperParameters = mutableListOf<ParameterSpec>()
    for ((index, column) in table.columns.withIndex()) {
      val columnType = column.typeName
      val parameter = ParameterSpec(column.name, columnType)
      primaryConstructor.addParameter(parameter)
      mapperParameters.add(parameter)
      mapperArguments.add(column.mappableType.resultSetAction(index + columnOffset))
      typeBeingDefined.addProperty(
        PropertySpec.builder(column.name, columnType)
          .initializer(column.name)
          .build(),
      )
    }
    val returnType = ReturnType(nameOfTypeBeingDefined, mapperArguments, mapperParameters)
    returnType to typeBeingDefined.primaryConstructor(primaryConstructor.build()).build()
  }.first

  /**
   * Creates a type projection for a Kotlin representation of the columns returning from this query.
   *
   * The Kotlin class will have a property for each column returned by the query.
   *
   * A [TypeSpec] will be registered for the created Kotlin class.
   *
   * See [getTypeProjectionForTable] for building [TypeSpec]s based on Table layouts. This function specializes in
   * creating [TypeSpec]s for ad-hoc projections.
   *
   * @param queryName Name of the query. Used to generate the Kotlin model name.
   * @param queryResults Columns that are returned by the query.
   */
  // TODO Tests:
  //  - regular, embed, regular
  //  - regular, regular
  //  - embed, embed
  fun buildTypeProjectionForQuery(queryName: String, queryResults: List<Column>): ReturnType {
    val nameOfTypeBeingDefined = ClassName(packageName, queryName.titleCase())
    val typeBeingDefined = TypeSpec.classBuilder(nameOfTypeBeingDefined)
      .addModifiers(KModifier.DATA)
    val mapperArguments = mutableListOf<CodeBlock>()
    val primaryConstructor = FunSpec.constructorBuilder()

    // null indicates a secondary constructor won't be needed.
    val secondaryConstructor = if (queryResults.any { it.embed_table != null }) FunSpec.constructorBuilder() else null
    val secondaryToPrimaryConstructorInputs = mutableListOf<CodeBlock>()

    // Parameters required to invoke the mapper
    val mapperParameters = mutableListOf<ParameterSpec>()
    // TODO There are all kinds of bugs here with the index and the offset I think
    var index = 1
    for (column in queryResults) {
      val columnType = if (column.embed_table != null) {
        // sqlc.embed() column. Return the table model of the target table
        val table = catalog.resolveTable(column.embed_table)
        val embeddedType = getTypeProjectionForTable(table, index)
        index += embeddedType.builder.size

        val embeddedTypeConstructorInvocation = CodeBlock.builder()
          .addStatement("%T(", embeddedType.kotlinType)
          .indent()
        for (parameter in embeddedType.creationParameters) {
          secondaryConstructor!!.addParameter(parameter)
          embeddedTypeConstructorInvocation.addStatement("%N,", parameter)
        }
        embeddedTypeConstructorInvocation
          .unindent()
          .add(")")
        secondaryToPrimaryConstructorInputs.add(embeddedTypeConstructorInvocation.build())

        // When generating the mapper, we just flatten all the columns. Proper creation of the embedded models is
        // delegated to the secondary constructor.
        mapperParameters.addAll(embeddedType.creationParameters)
        mapperArguments.addAll(embeddedType.builder)
        embeddedType.kotlinType!!
      } else {
        // We have a regular column
        val columnType = column.typeName
        val parameter = ParameterSpec(column.name, columnType)
        secondaryConstructor?.addParameter(parameter)
        mapperParameters.add(parameter)
        mapperArguments.add(column.mappableType.resultSetAction(index))
        index++
        columnType
      }

      typeBeingDefined.addProperty(
        PropertySpec.builder(column.name, columnType)
          .initializer(column.name)
          .build(),
      )
      primaryConstructor.addParameter(column.name, columnType)
    }
    typeBeingDefined.primaryConstructor(primaryConstructor.build())
    if (secondaryConstructor != null) {
      secondaryConstructor.callThisConstructor(secondaryToPrimaryConstructorInputs)
      typeBeingDefined.addFunction(secondaryConstructor.build())
    }

    val returnType = ReturnType(nameOfTypeBeingDefined, mapperArguments, mapperParameters)
    queryModels.add(returnType to typeBeingDefined.build())
    return returnType
  }
}
