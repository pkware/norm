package norm.generator

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import plugin.Catalog
import plugin.Column
import plugin.Table
import kotlin.reflect.KClass

/**
 * Repository for types generated as part of query generation.
 *
 * Responsibilities:
 * - Generates Kotlin data classes for query results and table projections
 * - Resolves SQL types (including Postgres domains) to Kotlin types
 *
 * These responsibilities are intentionally coupled: generating data classes requires
 * knowing how to resolve column types, and both operations use the same catalog
 * and query metadata.
 *
 * @param packageName to use for generated types.
 * @param catalog Postgres catalog to use when resolving projection information.
 * @param queries All queries to be generated. Used to build domain type resolution mapping.
 */
internal class TypeRepository(
  private val packageName: String,
  private val catalog: Catalog,
  queries: List<plugin.Query> = emptyList(),
) {
  private val tableModels = mutableMapOf<Table, Pair<ReturnType, TypeSpec>>()
  private val queryModels = mutableListOf<Pair<ReturnType, TypeSpec>>()

  /**
   * Maps domain types to their underlying base types by analyzing query parameters.
   *
   * sqlc resolves domain types to base types for parameters in WHERE clauses,
   * so we can build a mapping by comparing table column types with parameter types.
   *
   * Key format: "domain_type_name" -> base_type_identifier
   * Example: "email" -> Identifier("text")
   */
  private val domainTypeMapping: Map<String, plugin.Identifier> = buildDomainTypeMapping(queries)

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
      .addAnnotation(JvmRecord::class)
    val mapperArguments = mutableListOf<CodeBlock>()
    val primaryConstructor = FunSpec.constructorBuilder()
    // Parameters required to invoke the mapper
    val mapperParameters = mutableListOf<ParameterSpec>()
    for ((index, column) in table.columns.withIndex()) {
      val columnType = resolveColumnType(column)
      val parameter = ParameterSpec(column.name, columnType)
      primaryConstructor.addParameter(parameter)
      mapperParameters.add(parameter)
      mapperArguments.add(resolveMappableType(column).resultSetAction(index + columnOffset))
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
  fun buildTypeProjectionForQuery(queryName: String, queryResults: List<Column>): ReturnType {
    val nameOfTypeBeingDefined = ClassName(packageName, queryName.titleCase())
    val typeBeingDefined = TypeSpec.classBuilder(nameOfTypeBeingDefined)
      .addModifiers(KModifier.DATA)
      .addAnnotation(JvmRecord::class)
    val mapperArguments = mutableListOf<CodeBlock>()
    val primaryConstructor = FunSpec.constructorBuilder()

    // null indicates a secondary constructor won't be needed.
    val secondaryConstructor = if (queryResults.any { it.embed_table != null }) FunSpec.constructorBuilder() else null
    val secondaryToPrimaryConstructorInputs = mutableListOf<CodeBlock>()

    // Parameters required to invoke the mapper
    val mapperParameters = mutableListOf<ParameterSpec>()
    var index = 1
    for (column in queryResults) {
      val columnType = if (column.embed_table != null) {
        // sqlc.embed() column. Ensure the embedded type is registered, then build inline constructor.
        val table = catalog.resolveTable(column.embed_table)

        // Register the embedded type itself (with default offset) so it gets generated
        getTypeProjectionForTable(table, columnOffset = 1)

        val embeddedTypeClassName = ClassName(
          packageName,
          table.rel!!.name.snakeToCamelCase().titleCase(),
        )

        val embeddedTypeConstructorInvocation = CodeBlock.builder()
          .addStatement("%T(", embeddedTypeClassName)
          .indent()

        for (embeddedColumn in table.columns) {
          val embeddedColumnType = resolveColumnType(embeddedColumn)

          // Prefix parameter with embed column name to avoid duplicates across multiple embeds
          val paramName = "${column.name}_${embeddedColumn.name}"
          val parameter = ParameterSpec(paramName, embeddedColumnType)

          secondaryConstructor!!.addParameter(parameter)
          mapperParameters.add(parameter)
          mapperArguments.add(resolveMappableType(embeddedColumn).resultSetAction(index))
          index++

          embeddedTypeConstructorInvocation.addStatement("%N,", parameter)
        }

        embeddedTypeConstructorInvocation
          .unindent()
          .add(")")
        secondaryToPrimaryConstructorInputs.add(embeddedTypeConstructorInvocation.build())

        embeddedTypeClassName
      } else {
        // We have a regular column
        val columnType = resolveColumnType(column)
        val parameter = ParameterSpec(column.name, columnType)
        secondaryConstructor?.addParameter(parameter)

        // Add parameter to secondary-to-primary constructor call inputs
        secondaryToPrimaryConstructorInputs.add(CodeBlock.of("%N", parameter))

        mapperParameters.add(parameter)
        mapperArguments.add(resolveMappableType(column).resultSetAction(index))
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

  /**
   * Resolves the Kotlin [TypeName] for a column, with support for domain type resolution.
   *
   * Postgres domains (e.g., `CREATE DOMAIN email AS text`) are resolved to their base types
   * by analyzing query parameters. This method handles both standard types and domains.
   */
  fun resolveColumnType(column: Column): TypeName =
    wrapInArrayIfNeeded(resolveMappableType(column).klass, column.is_array, column.not_null)

  /**
   * Resolves the [SqlMappable] for a column, handling both standard types and Postgres domains.
   */
  fun resolveMappableType(column: Column): SqlMappable {
    val typeName = column.type?.name
      ?: error("Column ${column.fullyQualifiedName} has no type")

    // Try standard type first
    tryResolveStandardType(typeName, column.not_null, column.is_array)?.let { return it }

    // Check if it's a domain type and resolve recursively
    domainTypeMapping[typeName]?.let { baseType ->
      return resolveMappableType(column.copy(type = baseType))
    }

    error("Postgres type $typeName for column ${column.fullyQualifiedName} is not mapped to a Kotlin type")
  }

  /**
   * Builds domain-to-base-type mapping by comparing catalog column types with parameter types.
   *
   * sqlc resolves domains for parameters but not for table schemas, so when they differ,
   * the parameter type is the resolved base type.
   */
  private fun buildDomainTypeMapping(queries: List<plugin.Query>): Map<String, plugin.Identifier> {
    val tableColumnTypes = buildTableColumnTypeIndex()
    val mapping = mutableMapOf<String, plugin.Identifier>()

    for (query in queries) {
      @Suppress("LoopWithTooManyJumpStatements")
      for (parameter in query.params) {
        val column = parameter.column ?: continue
        val tableName = column.table?.name ?: continue
        val parameterType = column.type ?: continue
        val key = "$tableName.${column.name}"

        val tableColumnType = tableColumnTypes[key] ?: continue
        if (tableColumnType != parameterType.name) {
          mapping[tableColumnType] = parameterType
        }
      }
    }

    return mapping
  }

  /** Builds an index of "tableName.columnName" -> typeName from the catalog. */
  private fun buildTableColumnTypeIndex(): Map<String, String> = buildMap {
    for (schema in catalog.schemas) {
      for (table in schema.tables) {
        val tableName = table.rel?.name ?: continue
        for (column in table.columns) {
          val typeName = column.type?.name ?: continue
          put("$tableName.${column.name}", typeName)
        }
      }
    }
  }

  /** Returns the [SqlMappable] for a standard Postgres type, or `null` if not recognized. */
  private fun tryResolveStandardType(typeName: String, notNull: Boolean, isArray: Boolean): SqlMappable? {
    val baseType = resolveBaseType(typeName, notNull) ?: return null

    if (!isArray) return baseType

    val arrayTypeName = ARRAY.parameterizedBy(baseType.klass.asTypeName().copy(nullable = true))
      .copy(nullable = !notNull)
    return ArrayTypeDecorator(baseType, arrayTypeName)
  }

  /** Maps a Postgres type name to its base [SqlMappable], or `null` if not recognized. */
  private fun resolveBaseType(typeName: String, notNull: Boolean): SqlMappable? = when (typeName) {
    "smallserial", "serial2", "pg_catalog.serial2" -> JdbcTypes.SHORT.decorateForNullable(notNull)
    "serial", "serial4", "pg_catalog.serial4" -> JdbcTypes.INT.decorateForNullable(notNull)
    "bigserial", "serial8", "pg_catalog.serial8" -> JdbcTypes.LONG.decorateForNullable(notNull)

    "smallint", "int2", "pg_catalog.int2" -> JdbcTypes.SHORT.decorateForNullable(notNull)
    "integer", "int", "int4", "pg_catalog.int4" -> JdbcTypes.INT.decorateForNullable(notNull)
    "bigint", "int8", "pg_catalog.int8" -> JdbcTypes.LONG.decorateForNullable(notNull)

    "real", "float4", "pg_catalog.float4" -> JdbcTypes.FLOAT.decorateForNullable(notNull)
    "float", "double precision", "float8", "pg_catalog.float8" -> JdbcTypes.DOUBLE.decorateForNullable(notNull)
    "numeric", "pg_catalog.numeric" -> JdbcTypes.BIG_DECIMAL

    "bool", "boolean", "pg_catalog.bool" -> JdbcTypes.BOOLEAN.decorateForNullable(notNull)

    "jsonb" -> JdbcTypes.STRING

    "oid", "pg_catalog.oid" -> JdbcTypes.BLOB
    "bytea", "pg_catalog.bytea" -> PostgresSupportedTypes.BYTE_ARRAY

    "date", "pg_catalog.date" -> PostgresSupportedTypes.LOCAL_DATE
    "time", "pg_catalog.time" -> PostgresSupportedTypes.LOCAL_TIME
    "timetz", "pg_catalog.timetz" -> PostgresSupportedTypes.OFFSET_TIME
    "timestamp", "pg_catalog.timestamp" -> PostgresSupportedTypes.LOCAL_DATE_TIME
    "timestamptz", "pg_catalog.timestamptz" -> PostgresSupportedTypes.OFFSET_DATE_TIME

    "text", "varchar", "pg_catalog.varchar", "bpchar", "pg_catalog.bpchar", "string" -> JdbcTypes.STRING

    "uuid", "pg_catalog.uuid" -> PostgresSupportedTypes.UUID

    else -> null
  }

  private fun wrapInArrayIfNeeded(type: KClass<*>, isArray: Boolean, notNull: Boolean): TypeName = if (isArray) {
    ARRAY.parameterizedBy(type.asTypeName().copy(nullable = true))
  } else {
    type.asTypeName()
  }.copy(nullable = !notNull)
}
