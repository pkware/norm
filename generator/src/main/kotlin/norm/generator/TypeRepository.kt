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
import plugin.Catalog
import plugin.Column
import plugin.Domain
import plugin.Enum
import plugin.Schema
import plugin.Table

/**
 * Repository for types generated as part of query generation.
 *
 * Responsibilities:
 * - Generates Kotlin data classes for query results and table projections
 * - Resolves SQL types to Kotlin types
 *
 * These responsibilities are intentionally coupled: generating data classes requires
 * knowing how to resolve column types, and both operations use the same catalog
 * and query metadata.
 *
 * @param packageName to use for generated types.
 * @param catalog Postgres catalog to use when resolving projection information.
 */
internal class TypeRepository(private val packageName: String, private val catalog: Catalog) {

  /**
   * Projections of SQL tables.
   */
  private val tableModels = mutableMapOf<Table, Pair<ReturnType, TypeSpec>>()

  /**
   * Projections needed to return results from queries.
   */
  private val queryModels = mutableListOf<Pair<ReturnType, TypeSpec>>()

  /**
   * Index of all enum types in the catalog, keyed by Postgres type name.
   */
  private val enumsByName: Map<String, Enum> =
    catalog.schemas.flatMap(Schema::enums).associateBy(Enum::name)

  /**
   * Index of all domain types in the catalog, keyed by Postgres type name.
   */
  private val domainsByName: Map<String, Domain> =
    catalog.schemas.flatMap(Schema::domains).associateBy(Domain::name)

  /**
   * Enum types that are actually referenced by columns in resolved queries.
   *
   * Populated during [resolveMappableType] calls. Only referenced enums get
   * generated as Kotlin enum classes and adapters.
   */
  private val referencedEnums = mutableSetOf<Enum>()

  /**
   * Domain types that are actually referenced by columns in resolved queries.
   *
   * Populated during [resolveMappableType] calls. Only referenced domains get
   * generated as value classes and adapters.
   */
  private val referencedDomains = mutableSetOf<Domain>()

  /**
   * The set of Postgres enum types discovered as referenced by query columns.
   *
   * Available after all queries have been resolved via [resolveMappableType].
   */
  val discoveredEnums: Set<Enum>
    get() = referencedEnums

  /**
   * The set of Postgres domain types discovered as referenced by query columns.
   *
   * Available after all queries have been resolved via [resolveMappableType].
   */
  val discoveredDomains: Set<Domain>
    get() = referencedDomains

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
  // TODO Does the columnOffset result in a bug if the same table is sometimes standalone and sometimes embedded?
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
    typeBeingDefined.addClassKdoc(
      table.comment,
      table.rel.name,
      table.columns.map { column ->
        PropertySource(column.name, column.comment, table.rel.name, column.name)
      },
    )
    typeBeingDefined.primaryConstructor(primaryConstructor.build())
    val returnType = ReturnType(nameOfTypeBeingDefined, mapperArguments, mapperParameters)
    returnType to typeBeingDefined.build()
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
  fun buildTypeProjectionForQuery(queryName: String, queryResults: List<Column>, queryText: String = ""): ReturnType {
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
    val selectItems = parseSelectItems(queryText)
    typeBeingDefined.addClassKdoc(
      classComment = "",
      tableName = null,
      properties = queryResults.mapIndexed { columnIndex, column ->
        val selectItem = selectItems.getOrNull(columnIndex)
        // For computed expressions (no source table and not a simple column reference), include the SQL
        // expression so it can appear in KDoc. Simple column references (e.g. crosstab output columns)
        // are excluded because echoing the column name back adds no value.
        val isComputedExpression = column.table == null && selectItem != null && selectItem.columnName == null
        PropertySource(
          propertyName = column.name,
          comment = column.comment,
          sourceTable = column.table?.name,
          sourceColumn = column.original_name.ifEmpty { null },
          expression = if (isComputedExpression) selectItem.expression else "",
        )
      },
      sql = queryText,
    )
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
   *
   * Uses [SqlMappable.typeName] rather than [SqlMappable.klass] so that generated types
   * (like enum classes) can provide their [TypeName] without requiring a [KClass] at generator time.
   *
   * Array wrapping is handled by [tryResolveStandardType] which returns an [ArrayTypeDecorator]
   * whose [SqlMappable.typeName] is already the correct parameterized array type.
   */
  fun resolveColumnType(column: Column): TypeName =
    resolveMappableType(column).typeName.copy(nullable = !column.not_null)

  /**
   * Resolves the [SqlMappable] for a column.
   */
  fun resolveMappableType(column: Column): SqlMappable {
    val typeName = column.type?.name
      ?: error("Column ${column.fullyQualifiedName} has no type")

    return tryResolveStandardType(typeName, column.not_null, column.is_array)
      ?: tryResolveEnumType(typeName, column.not_null, column.is_array)
      ?: tryResolveDomainType(typeName, column.not_null, column.is_array)
      ?: error("Postgres type $typeName for column ${column.fullyQualifiedName} is not mapped to a Kotlin type")
  }

  /**
   * Returns an [EnumTypeSqlMappable] if [typeName] matches a known Postgres enum type, or `null`.
   *
   * Enum arrays are not yet supported and return `null` (falling through to the error in
   * [resolveMappableType]).
   */
  private fun tryResolveEnumType(typeName: String, notNull: Boolean, isArray: Boolean): SqlMappable? {
    if (isArray) return null // Enum arrays not yet supported
    val enumDefinition = enumsByName[typeName] ?: return null
    referencedEnums.add(enumDefinition)

    val enumClassName = ClassName(packageName, enumDefinition.name.snakeToCamelCase().titleCase())
    return EnumTypeSqlMappable(enumClassName, adapterPropertyName(enumDefinition), notNull)
  }

  /** Returns the [SqlMappable] for a standard Postgres type, or `null` if not recognized. */
  private fun tryResolveStandardType(typeName: String, notNull: Boolean, isArray: Boolean): SqlMappable? {
    val baseType = resolveBaseType(typeName, notNull) ?: return null

    if (!isArray) return baseType

    val arrayTypeName = ARRAY.parameterizedBy(baseType.typeName.copy(nullable = true))
      .copy(nullable = !notNull)
    return ArrayTypeDecorator(baseType, arrayTypeName)
  }

  /**
   * Returns a [DomainTypeSqlMappable] if [typeName] matches a known Postgres domain type, or `null`.
   *
   * Domain arrays are not yet supported and return `null` (falling through to the error in
   * [resolveMappableType]).
   */
  private fun tryResolveDomainType(typeName: String, notNull: Boolean, isArray: Boolean): SqlMappable? {
    if (isArray) return null // Domain arrays not yet supported
    val domain = domainsByName[typeName] ?: return null
    referencedDomains.add(domain)

    val domainClassName = ClassName(packageName, domain.name.snakeToCamelCase().titleCase())
    val baseTypeInfo = resolveDomainBaseTypeInfo(domain.base_type)
      ?: error("Domain ${domain.name} has unsupported base type: ${domain.base_type}")
    return DomainTypeSqlMappable(domainClassName, domainAdapterPropertyName(domain), notNull, baseTypeInfo)
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
}

/**
 * Maps a Postgres base type name to its [DomainBaseTypeInfo], or returns `null` if unsupported.
 *
 * This covers the types most commonly used as domain bases. The mapping must stay in sync with
 * [TypeRepository.resolveBaseType] — any type supported there as a domain base should have an entry here.
 */
internal fun resolveDomainBaseTypeInfo(baseTypeName: String): DomainBaseTypeInfo? = when (baseTypeName) {
  "text", "varchar", "bpchar" -> DomainBaseTypeInfo("getString", "setString", false, "VARCHAR")
  "int2" -> DomainBaseTypeInfo("getShort", "setShort", true, "SMALLINT")
  "int4" -> DomainBaseTypeInfo("getInt", "setInt", true, "INTEGER")
  "int8" -> DomainBaseTypeInfo("getLong", "setLong", true, "BIGINT")
  "float4" -> DomainBaseTypeInfo("getFloat", "setFloat", true, "REAL")
  "float8" -> DomainBaseTypeInfo("getDouble", "setDouble", true, "DOUBLE")
  "bool" -> DomainBaseTypeInfo("getBoolean", "setBoolean", true, "BOOLEAN")
  "numeric" -> DomainBaseTypeInfo("getBigDecimal", "setBigDecimal", false, "NUMERIC")
  else -> null
}

/**
 * Describes a property's source in the database.
 *
 * @property propertyName The Kotlin property name.
 * @property comment The Postgres column comment. Empty if none.
 * @property sourceTable The database table the column comes from. `null` for computed columns.
 * @property sourceColumn The original column name in the database. `null` for computed columns.
 * @property expression The SQL expression for computed columns (e.g. `COUNT(*)`). Empty if not computed.
 */
internal data class PropertySource(
  val propertyName: String,
  val comment: String,
  val sourceTable: String?,
  val sourceColumn: String?,
  val expression: String = "",
)

/**
 * Adds a class-level KDoc block with an optional description, table mapping, and `@property` tags.
 *
 * Produces a single consolidated KDoc block rather than separate per-property doc comments, which is the
 * idiomatic Kotlin style for data classes with constructor properties.
 *
 * For table projections, the table name is shown as "Maps to the `X` table.".
 * For query projections, the SQL is included and source columns are shown per-property as `table.column` references.
 *
 * @param classComment The table or class-level comment. May be empty.
 * @param tableName The database table this class fully maps to. `null` for ad-hoc query projections.
 * @param properties Source information for each property.
 * @param sql The SQL query text. Included in query projection KDoc as a fenced code block.
 */
internal fun TypeSpec.Builder.addClassKdoc(
  classComment: String,
  tableName: String?,
  properties: List<PropertySource>,
  sql: String = "",
) {
  val hasTableMapping = tableName != null
  val hasSql = sql.isNotEmpty()
  val documentedProperties = properties.filter { it.hasDocumentation(hasTableMapping) }
  if (classComment.isEmpty() && !hasTableMapping && !hasSql && documentedProperties.isEmpty()) return

  val kdoc = buildString {
    if (classComment.isNotEmpty()) {
      append(classComment)
    }
    if (hasTableMapping) {
      if (isNotEmpty()) append("\n\n")
      append("Maps to the `$tableName` table.")
    }
    if (hasSql) {
      if (isNotEmpty()) append("\n\n")
      append("```sql\n")
      append(sql.trim())
      append("\n```")
    }
    if (documentedProperties.isNotEmpty()) {
      if (isNotEmpty()) append("\n\n")
      for ((index, property) in documentedProperties.withIndex()) {
        append("@property ${property.propertyName} ")
        if (property.comment.isNotEmpty()) {
          append(property.comment)
        }
        if (!hasTableMapping) {
          val source = property.sourceReference()
          if (source != null) {
            if (property.comment.isNotEmpty()) append(" ")
            append("($source)")
          }
        }
        if (index < documentedProperties.lastIndex) append("\n")
      }
    }
  }
  addKdoc("%L", kdoc)
}

/**
 * Whether this property has any documentation to show in KDoc.
 */
private fun PropertySource.hasDocumentation(hasTableMapping: Boolean): Boolean =
  comment.isNotEmpty() || (!hasTableMapping && sourceReference() != null)

/**
 * Returns a source reference string for display in KDoc, or `null` if none is available.
 *
 * - For columns from a table: `` `table.column` ``
 * - For computed expressions: `` `COUNT(*)` ``
 */
private fun PropertySource.sourceReference(): String? = when {
  sourceTable != null -> "`$sourceTable.$sourceColumn`"
  expression.isNotEmpty() -> "`$expression`"
  else -> null
}
