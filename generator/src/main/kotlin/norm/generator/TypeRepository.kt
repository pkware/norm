package norm.generator

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.AnnotationSpec
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
 * - Resolves SQL types to Kotlin types
 *
 * These responsibilities are intentionally coupled: generating data classes requires
 * knowing how to resolve column types, and both operations use the same catalog
 * and query metadata.
 *
 * @param packageName to use for generated types.
 * @param catalog Postgres catalog to use when resolving projection information.
 * @param frameworks Frameworks for which to generate code.
 */
internal class TypeRepository(
  private val packageName: String,
  private val catalog: Catalog,
  private val frameworks: Set<Framework> = emptySet(),
) {

  /**
   * Projections of SQL tables.
   */
  private val tableModels = mutableMapOf<Table, Pair<ReturnType, TypeSpec>>()

  /**
   * Projections needed to return results from queries.
   */
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
  // TODO Does the columnOffset result in a bug if the same table is sometimes standalone and sometimes embedded?
  fun getTypeProjectionForTable(table: Table, columnOffset: Int = 1): ReturnType = tableModels.computeIfAbsent(table) {
    val tableName = table.rel!!.name
      .snakeToCamelCase()
      .titleCase()
    val nameOfTypeBeingDefined = ClassName(packageName, tableName)
    val typeBeingDefined = TypeSpec.classBuilder(nameOfTypeBeingDefined)
      .addModifiers(KModifier.DATA)
      .addAnnotation(JvmRecord::class)
      .annotateForFrameworks(frameworks, table.rel.name)
    val mapperArguments = mutableListOf<CodeBlock>()
    val primaryConstructor = FunSpec.constructorBuilder()
    // Parameters required to invoke the mapper
    val mapperParameters = mutableListOf<ParameterSpec>()
    for ((index, column) in table.columns.withIndex()) {
      val columnType = resolveColumnType(column)
      val propertyName = determineKotlinPropertyName(column.name, frameworks)
      val parameter = ParameterSpec(propertyName, columnType)
      primaryConstructor.addParameter(parameter)
      mapperParameters.add(parameter)
      mapperArguments.add(resolveMappableType(column).resultSetAction(index + columnOffset))
      typeBeingDefined.addProperty(
        PropertySpec.builder(propertyName, columnType)
          .initializer(propertyName)
          .addColumnMappingAnnotationIfNeeded(propertyName, column.name, frameworks)
          .also { if (column.is_primary_key) it.addIdAnnotationForFrameworks(frameworks) }
          .build(),
      )
    }
    typeBeingDefined.addClassKdoc(
      table.comment,
      table.rel.name,
      table.columns.map { column ->
        val propertyName = determineKotlinPropertyName(column.name, frameworks)
        PropertySource(propertyName, column.comment, table.rel.name, column.name)
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
   */
  fun resolveColumnType(column: Column): TypeName =
    wrapInArrayIfNeeded(resolveMappableType(column).klass, column.is_array, column.not_null)

  /**
   * Resolves the [SqlMappable] for a column.
   */
  fun resolveMappableType(column: Column): SqlMappable {
    val typeName = column.type?.name
      ?: error("Column ${column.fullyQualifiedName} has no type")

    return tryResolveStandardType(typeName, column.not_null, column.is_array)
      ?: error("Postgres type $typeName for column ${column.fullyQualifiedName} is not mapped to a Kotlin type")
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

/**
 * Determines the Kotlin property name for a database column.
 *
 * For frameworks that require camelCase naming (Micronaut Data, Spring Data),
 * converts snake_case column names to camelCase. Otherwise, uses the original name.
 *
 * @param columnName The original database column name
 * @param frameworks The set of frameworks for which code is being generated
 * @return The property name to use in the generated Kotlin class
 */
private fun determineKotlinPropertyName(columnName: String, frameworks: Set<Framework>): String {
  val requiresCamelCase = frameworks.any {
    it == Framework.MICRONAUT_DATA_JDBC || it == Framework.SPRING_DATA_JDBC
  }
  return if (requiresCamelCase) {
    columnName.snakeToCamelCase()
  } else {
    columnName
  }
}

/**
 * Adds a column mapping annotation if the property name differs from the database column name.
 *
 * This is necessary when frameworks use camelCase property names but the database uses snake_case.
 * For example, when a database column `author_id` is converted to a property `authorId`, this function
 * adds the appropriate mapping annotation to preserve the database relationship.
 *
 * - Micronaut Data JDBC: `@field:MappedProperty("column_name")`
 * - Spring Data JDBC: `@Column("column_name")`
 *
 * This function handles column-to-property mapping. For primary key annotations, see
 * [addIdAnnotationForFrameworks].
 *
 * @param propertyName The Kotlin property name (possibly camelCase)
 * @param columnName The original database column name (possibly snake_case)
 * @param frameworks The set of frameworks for which code is being generated
 * @see addIdAnnotationForFrameworks
 */
private fun PropertySpec.Builder.addColumnMappingAnnotationIfNeeded(
  propertyName: String,
  columnName: String,
  frameworks: Set<Framework>,
): PropertySpec.Builder = apply {
  if (propertyName != columnName) {
    for (framework in frameworks) {
      when (framework) {
        Framework.MICRONAUT_DATA_JDBC -> addAnnotation(
          AnnotationSpec
            .builder(MICRONAUT_DATA_MAPPED_PROPERTY_ANNOTATION)
            .addMember(""""$columnName"""")
            .useSiteTarget(AnnotationSpec.UseSiteTarget.FIELD)
            .build(),
        )
        Framework.SPRING_DATA_JDBC -> addAnnotation(
          AnnotationSpec
            .builder(SPRING_DATA_COLUMN_ANNOTATION)
            .addMember(""""$columnName"""")
            .build(),
        )
        Framework.ALL_TABLES -> continue // No mapping annotations needed
      }
    }
  }
}

private fun TypeSpec.Builder.annotateForFrameworks(frameworks: Set<Framework>, tableName: String): TypeSpec.Builder =
  apply {
    for (framework in frameworks) {
      val annotation = when (framework) {
        Framework.MICRONAUT_DATA_JDBC -> MICRONAUT_DATA_TABLE_ANNOTATION
        Framework.SPRING_DATA_JDBC -> SPRING_DATA_TABLE_ANNOTATION
        Framework.ALL_TABLES -> continue // No specific annotations required
      }
      addAnnotation(
        AnnotationSpec.builder(annotation)
          .addMember(""""$tableName"""")
          .build(),
      )
    }
  }

/**
 * Adds framework-specific ID annotations to primary key properties.
 *
 * This function marks properties as entity identifiers using framework conventions:
 * - Micronaut Data JDBC: `@field:Id`
 * - Spring Data JDBC: `@Id`
 *
 * This function handles primary key identification. For column name mapping annotations, see
 * [addColumnMappingAnnotationIfNeeded].
 *
 * @param frameworks The set of frameworks for which code is being generated
 * @see addColumnMappingAnnotationIfNeeded
 */
private fun PropertySpec.Builder.addIdAnnotationForFrameworks(frameworks: Set<Framework>): PropertySpec.Builder =
  apply {
    for (framework in frameworks) {
      when (framework) {
        Framework.MICRONAUT_DATA_JDBC -> addAnnotation(
          AnnotationSpec
            .builder(MICRONAUT_DATA_ID_ANNOTATION)
            .useSiteTarget(AnnotationSpec.UseSiteTarget.FIELD)
            .build(),
        )
        Framework.SPRING_DATA_JDBC -> addAnnotation(SPRING_DATA_ID_ANNOTATION)
        Framework.ALL_TABLES -> continue // No specific annotations required
      }
    }
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

private val MICRONAUT_DATA_ID_ANNOTATION = ClassName("io.micronaut.data.annotation", "Id")
private val MICRONAUT_DATA_TABLE_ANNOTATION = ClassName("io.micronaut.data.annotation", "MappedEntity")
private val MICRONAUT_DATA_MAPPED_PROPERTY_ANNOTATION = ClassName("io.micronaut.data.annotation", "MappedProperty")
private val SPRING_DATA_ID_ANNOTATION = ClassName("org.springframework.data.annotation", "Id")
private val SPRING_DATA_TABLE_ANNOTATION = ClassName("org.springframework.data.relational.core.mapping", "Table")
private val SPRING_DATA_COLUMN_ANNOTATION = ClassName("org.springframework.data.relational.core.mapping", "Column")
