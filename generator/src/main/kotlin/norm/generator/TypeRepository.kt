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

/**
 * [JdbcTypeInfo] for Postgres enum types.
 *
 * Enum types require `setObject(index, value, Types.OTHER)` rather than `setString(index, value)`.
 * The Postgres JDBC driver rejects `VARCHAR` bindings for enum columns in prepared statements;
 * `Types.OTHER` bypasses driver-side type enforcement and lets Postgres coerce the string.
 */
private val ENUM_JDBC_TYPE_INFO =
  JdbcTypeInfo("getString", "setObject", false, "OTHER", useSqlTypeHint = true, kotlinType = String::class.asTypeName())

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
 * @param typeMappings User-configured type/column overrides. Type-level overrides take precedence
 *   over auto-generated enums/domains; column-level overrides take precedence over everything.
 * @param reservedWords The connected PostgreSQL server's reserved keywords, from
 *   [JdbcAnalyzer.fetchReservedWords] — consulted by [quoteSqlIdentifierIfNeeded] when rendering a
 *   `` `table.column` `` source reference, so a relation or column named after a reserved word
 *   (`order`, `user`) is quoted rather than emitted as text PostgreSQL rejects with a syntax error.
 *   Defaults to `emptySet()` for callers (mostly tests building an in-memory [Catalog]
 *   with no live connection) whose fixtures never name anything after a reserved word;
 *   [generateCode] — the real production entry point — always supplies
 *   [JdbcAnalyzer.fetchReservedWords]'s live result explicitly instead of relying on this default.
 */
internal class TypeRepository(
  private val packageName: String,
  private val catalog: Catalog,
  private val typeMappings: List<TypeMapping> = emptyList(),
  private val reservedWords: Set<String> = emptySet(),
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
   * Index of all enum types in the catalog, keyed by Postgres type name.
   */
  private val enumsByName: Map<String, Enum> =
    catalog.schemas.flatMap(Schema::enums).associateBy(Enum::name)

  /**
   * Index of all domain types in the catalog, keyed by Postgres type name.
   */
  private val domainsByName: Map<String, Domain> =
    catalog.schemas.flatMap(Schema::domains).associateBy(Domain::name)

  /** Type-level overrides, keyed by Postgres type name. */
  private val typeLevelOverrides: Map<String, TypeMapping> =
    typeMappings.filter { it.isTypeLevel }.associateBy { it.postgresType }

  /**
   * Column-level overrides, keyed by (table, column) pair.
   *
   * Both are truncated because a user configures an override with the full name they wrote in DDL,
   * while the catalog names these keys are matched against came back from the server already
   * truncated. Left untruncated, an override on an over-length name would never match and would be
   * silently dropped.
   */
  private val columnLevelOverrides: Map<Pair<String, String>, TypeMapping> =
    typeMappings.filter { it.isColumnLevel }
      .associateBy { truncateIdentifier(it.table!!) to truncateIdentifier(it.column!!) }

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
    val tableName = table.rel.name
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
    val secondaryConstructor = if (queryResults.any { it.embedTable != null }) FunSpec.constructorBuilder() else null
    val secondaryToPrimaryConstructorInputs = mutableListOf<CodeBlock>()

    // Parameters required to invoke the mapper
    val mapperParameters = mutableListOf<ParameterSpec>()
    var index = 1
    for (column in queryResults) {
      val columnType = if (column.embedTable != null) {
        // sqlc.embed() column. Ensure the embedded type is registered, then build inline constructor.
        val table = catalog.resolveTable(column.embedTable)

        // Register the embedded type itself (with default offset) so it gets generated
        getTypeProjectionForTable(table, columnOffset = 1)

        val embeddedTypeClassName = ClassName(
          packageName,
          table.rel.name.snakeToCamelCase().titleCase(),
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
    // A non-empty parseSelectItems() result whose size disagrees with the real column count
    // (queryResults.size, ultimately from ResultSetMetaData.getColumnCount()) means at least one
    // select item didn't map 1:1 onto a result column (e.g. an unrecognized star item expanding to
    // several columns) -- see parseSelectItems' KDoc for why it has no independent cross-check of
    // its own. Treating the mismatch as if parsing had failed outright (the documented empty-list
    // fail-safe) avoids a wrong, shifted mapping of names/comments/expressions onto columns they
    // don't belong to.
    val rawSelectItems = parseSelectItems(queryText)
    val selectItems = if (rawSelectItems.isNotEmpty() && rawSelectItems.size != queryResults.size) {
      emptyList()
    } else {
      rawSelectItems
    }
    // A top-level set operation (UNION/INTERSECT/EXCEPT) means parseSelectItems only parsed ONE
    // branch's own items, so a computed expression documented from that branch alone would present
    // it as the whole answer. A bare column reference is unaffected -- see hasTopLevelSetOperation.
    val hasSetOperation = hasTopLevelSetOperation(queryText)
    typeBeingDefined.addClassKdoc(
      classComment = "",
      tableName = null,
      properties = queryResults.mapIndexed { columnIndex, column ->
        val selectItem = selectItems.getOrNull(columnIndex)
        // A star item (`*`, `c.*`) is never itself a provenance expression -- parseOutputItemsWithAlias
        // returns a lone star item with columnName == null, same as a genuine computed expression.
        // Without this guard, isComputedExpression would document the wildcard's own literal text as
        // the "expression", shadowing a CTE pass-through's real expression in provenanceExpression.
        val isStarSelectItem = selectItem != null && isStarItem(selectItem.expression)
        // For computed expressions (no source table and not a simple column reference), include the SQL
        // expression so it can appear in KDoc. Simple column references (e.g. crosstab output columns)
        // are excluded because echoing the column name back adds no value.
        val isComputedExpression =
          column.table == null &&
            selectItem != null &&
            selectItem.columnName == null &&
            !hasSetOperation &&
            !isStarSelectItem
        // A plain reference into a CTE's output (a simple column reference or a star item) can still
        // be expression-derived one level down, inside the CTE body. column.provenanceExpression was
        // already resolved and cross-validated against queryText during analysis -- see
        // resolveNodeTreeProvenanceExpression. `null` means no resolved expression, so this property
        // gets no source-reference line at all -- never the star text itself.
        val cteExpression = if (!isComputedExpression && column.table == null) column.provenanceExpression else null
        PropertySource(
          propertyName = column.name,
          comment = column.comment,
          sourceTable = column.table?.name,
          sourceColumn = column.originalName.ifEmpty { null },
          // This top-level path re-lexes selectItem.expression straight from queryText, so it must
          // apply the same comment-stripping and whitespace-collapsing normalizations
          // resolveNodeTreeProvenanceExpression applies on the CTE path, to avoid embedding comment
          // text or leftover whitespace verbatim in generated KDoc.
          expression = if (isComputedExpression) {
            collapseCosmeticWhitespace(stripComments(selectItem.expression))
          } else {
            cteExpression ?: ""
          },
        )
      },
      sql = queryText,
      reservedWords = reservedWords,
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
    resolveMappableType(column).typeName.copy(nullable = !column.notNull)

  /**
   * Resolves the [SqlMappable] for a column.
   *
   * Resolution precedence: column override → type override → standard → enum → domain → error.
   */
  fun resolveMappableType(column: Column): SqlMappable {
    val typeName = column.type.name

    return tryResolveColumnOverride(column)
      ?: tryResolveTypeOverride(typeName, column.notNull, column.isArray)
      ?: tryResolveStandardType(typeName, column.notNull, column.isArray)
      ?: tryResolveEnumType(typeName, column.notNull, column.isArray)
      ?: tryResolveDomainType(typeName, column.notNull, column.isArray)
      ?: error("Postgres type $typeName for column ${column.fullyQualifiedName} is not mapped to a Kotlin type")
  }

  /**
   * Returns an adapted [SqlMappable] if the column has a column-level user override, or `null`.
   */
  private fun tryResolveColumnOverride(column: Column): SqlMappable? {
    val tableName = column.table?.name ?: return null
    val columnName = column.originalName.ifEmpty { column.name }
    // Truncated on both sides so the lookup stays symmetric with columnLevelOverrides' own keys,
    // whatever the caller built this Column from.
    val mapping = columnLevelOverrides[truncateIdentifier(tableName) to truncateIdentifier(columnName)]
      ?: return null
    return buildUserConfiguredMappable(mapping, column.type.name, column.notNull, column.isArray)
  }

  /**
   * Returns an adapted [SqlMappable] if [typeName] has a type-level user override, or `null`.
   */
  private fun tryResolveTypeOverride(typeName: String, notNull: Boolean, isArray: Boolean): SqlMappable? {
    val mapping = typeLevelOverrides[typeName] ?: return null
    return buildUserConfiguredMappable(mapping, typeName, notNull, isArray)
  }

  /**
   * Builds an adapted [SqlMappable] for a user-configured type mapping.
   *
   * Resolves the JDBC wire type for the Postgres type, then creates either an
   * [AdaptedTypeSqlMappable] (scalar) or [AdaptedArrayTypeSqlMappable] (array) with
   * the user's application type and adapter property name.
   */
  private fun buildUserConfiguredMappable(
    mapping: TypeMapping,
    postgresType: String,
    notNull: Boolean,
    isArray: Boolean,
  ): SqlMappable {
    val applicationTypeName = parseTypeName(mapping.kotlinType)
    val adapterPropertyName = userAdapterPropertyName(mapping)
    val jdbcTypeInfo = resolveJdbcTypeInfoForType(postgresType)
      ?: error(
        "Postgres type '$postgresType' cannot be used with a custom adapter — " +
          "no JDBC type mapping is available.",
      )

    if (isArray) {
      return AdaptedArrayTypeSqlMappable(
        applicationTypeName = applicationTypeName,
        adapterPropertyName = adapterPropertyName,
        columnNotNull = notNull,
        postgresTypeName = postgresType,
      )
    }
    return AdaptedTypeSqlMappable(applicationTypeName, adapterPropertyName, notNull, jdbcTypeInfo)
  }

  /**
   * Resolves [JdbcTypeInfo] for any Postgres type, chaining through enums and domains as needed.
   *
   * - Enum types → String (VARCHAR)
   * - Domain types → chains to the domain's base type
   * - Standard types → uses [resolveJdbcTypeInfo]
   */
  private fun resolveJdbcTypeInfoForType(postgresType: String): JdbcTypeInfo? {
    if (postgresType in enumsByName) return ENUM_JDBC_TYPE_INFO
    val domain = domainsByName[postgresType]
    if (domain != null) return resolveJdbcTypeInfoForType(domain.baseType)
    return resolveJdbcTypeInfo(postgresType)
  }

  /**
   * Returns an adapted [SqlMappable] if [typeName] matches a known Postgres enum type, or `null`.
   *
   * For scalar columns, returns [AdaptedTypeSqlMappable]. For array columns (e.g., `mood[]`),
   * returns [AdaptedArrayTypeSqlMappable] which generates per-element adapter decode/encode calls.
   */
  private fun tryResolveEnumType(typeName: String, notNull: Boolean, isArray: Boolean): SqlMappable? {
    val enumDefinition = enumsByName[typeName] ?: return null
    referencedEnums.add(enumDefinition)

    val enumClassName = ClassName(packageName, enumDefinition.name.snakeToCamelCase().titleCase())
    val propertyName = adapterPropertyName(enumDefinition)

    if (isArray) {
      return AdaptedArrayTypeSqlMappable(
        applicationTypeName = enumClassName,
        adapterPropertyName = propertyName,
        columnNotNull = notNull,
        postgresTypeName = typeName,
      )
    }
    return AdaptedTypeSqlMappable(enumClassName, propertyName, notNull, ENUM_JDBC_TYPE_INFO)
  }

  /** Returns the [SqlMappable] for a standard Postgres type, or `null` if not recognized. */
  private fun tryResolveStandardType(typeName: String, notNull: Boolean, isArray: Boolean): SqlMappable? {
    if (!isArray) return resolveBaseType(typeName, notNull)

    // oid[] diverges from scalar oid: resolveBaseType maps scalar oid to Blob (pgjdbc's setBlob
    // creates a large object and stores its oid), but an array of large-object handles has no
    // coherent JDBC semantics, and real-world oid[] columns hold plain catalog identifiers. oid is
    // an unsigned 32-bit integer, so Long (not Int) is required to hold values above Int.MAX_VALUE.
    //
    // Long is wider than oid's valid range of 0..4294967295, and the driver does not reject values
    // outside it symmetrically: a negative Long silently wraps to its unsigned 32-bit equivalent on
    // write (`-1L` is stored and read back as `4294967295`), with no error, while a value above
    // 4294967295 is rejected by Postgres with "value out of range". Callers must keep bound values
    // within `0..4294967295` themselves; this mapping does not validate that range.
    if (typeName == "oid" || typeName == "pg_catalog.oid") {
      val elementType = JdbcTypes.LONG.decorateForNullable(notNull = false)
      val arrayTypeName = ARRAY.parameterizedBy(elementType.typeName.copy(nullable = true))
        .copy(nullable = !notNull)
      return ArrayTypeDecorator(elementType, arrayTypeName, postgresArrayElementTypeName(typeName))
    }

    // Postgres array elements are always nullable regardless of the column's NOT NULL constraint,
    // so the element read must be the nullable form: getInt would turn a NULL element into 0, and
    // InstantSqlMappable's non-null read would throw NullPointerException on one.
    val elementType = resolveBaseType(typeName, notNull = false) ?: return null

    val arrayTypeName = ARRAY.parameterizedBy(elementType.typeName.copy(nullable = true))
      .copy(nullable = !notNull)
    return ArrayTypeDecorator(elementType, arrayTypeName, postgresArrayElementTypeName(typeName))
  }

  /**
   * Returns an adapted [SqlMappable] if [typeName] matches a known Postgres domain type, or `null`.
   *
   * For scalar columns, returns [AdaptedTypeSqlMappable]. For array columns (e.g., `email[]`),
   * returns [AdaptedArrayTypeSqlMappable] which generates per-element adapter decode/encode calls.
   *
   * [resolveJdbcTypeInfo] covers every key in [BASE_TYPE_RESOLVERS] (enforced by
   * [ColumnTypeMappingTest]'s domain-base-type-parity sweep), so `error` below is unreachable for a
   * domain over a common base type like `timestamptz` or `uuid` — see [domainKotlinBaseType]'s
   * KDoc for the (intentional) case where it remains reachable.
   */
  private fun tryResolveDomainType(typeName: String, notNull: Boolean, isArray: Boolean): SqlMappable? {
    val domain = domainsByName[typeName] ?: return null
    referencedDomains.add(domain)

    val domainClassName = ClassName(packageName, domain.name.snakeToCamelCase().titleCase())
    val propertyName = domainAdapterPropertyName(domain)
    val jdbcTypeInfo = resolveJdbcTypeInfo(domain.baseType)
      ?: error("Domain ${domain.name} has unsupported base type: ${domain.baseType}")

    if (isArray) {
      return AdaptedArrayTypeSqlMappable(
        applicationTypeName = domainClassName,
        adapterPropertyName = propertyName,
        columnNotNull = notNull,
        postgresTypeName = typeName,
      )
    }
    return AdaptedTypeSqlMappable(domainClassName, propertyName, notNull, jdbcTypeInfo)
  }

  /**
   * Maps a Postgres type name to its base [SqlMappable], or `null` if not recognized.
   *
   * [typeName] may carry a `pg_catalog.` qualification (e.g. `pg_catalog.int4`); it is stripped
   * once here rather than duplicated per literal in [BASE_TYPE_RESOLVERS], so every entry in that
   * map accepts both the qualified and unqualified spelling without needing its own branch for
   * each.
   */
  private fun resolveBaseType(typeName: String, notNull: Boolean): SqlMappable? =
    BASE_TYPE_RESOLVERS[typeName.removePrefix("pg_catalog.")]?.invoke(notNull)
}
