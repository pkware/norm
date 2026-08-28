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
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import java.math.BigDecimal
import java.sql.Blob
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.util.UUID

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
 *   (`order`, `user`) is quoted rather than emitted as text PostgreSQL rejects with a syntax error
 *   (#238 10.1). Defaults to `emptySet()` for callers (mostly tests building an in-memory [Catalog]
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

  /** Column-level overrides, keyed by (table, column) pair. */
  private val columnLevelOverrides: Map<Pair<String, String>, TypeMapping> =
    typeMappings.filter { it.isColumnLevel }.associateBy { it.table!! to it.column!! }

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
    // A top-level set operation (UNION/INTERSECT/EXCEPT) means parseSelectItems only ever parsed
    // ONE branch's own items -- a computed expression documented from that branch alone would
    // present one branch as the whole answer, a WRONG emission by this pipeline's own "correct or
    // silent" standard (#238). See hasTopLevelSetOperation's own KDoc for why a bare column
    // reference is unaffected and stays documented exactly as before.
    val hasSetOperation = hasTopLevelSetOperation(queryText)
    typeBeingDefined.addClassKdoc(
      classComment = "",
      tableName = null,
      properties = queryResults.mapIndexed { columnIndex, column ->
        val selectItem = selectItems.getOrNull(columnIndex)
        // A star item (`*`, `c.*`) is never itself a provenance expression -- parseOutputItemsWithAlias
        // returns a LONE star item as-is (see its own KDoc), rather than the empty-list fail-safe it
        // uses for a star followed by more items, so selectItem.columnName == null for it exactly as
        // for a genuine computed expression. Without this guard, isComputedExpression fired on the
        // star's own literal text ("*"/"c.*"), documenting the wildcard itself as the "expression"
        // that produced the column -- even when the column is really a plain pass-through of a CTE's
        // own computed expression, whose correct text is already sitting in provenanceExpression (see
        // below) and gets shadowed by this branch's false claim (#238 9.2).
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
        // A plain reference into a CTE's output (column.table == null, but the outer item IS a
        // simple column reference, OR is itself a star selectItem never doc'd as an expression above)
        // can still be expression-derived one level down, inside the CTE body --
        // column.provenanceExpression was already resolved from the query's own parsed node tree,
        // cross-validated against this same queryText, during analysis (see
        // ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody and
        // resolveNodeTreeProvenanceExpression for the shape it resolves and every case it
        // deliberately punts on rather than guessing). `null` here (no resolved expression) means
        // this property gets no source-reference line at all -- never the star text itself.
        val cteExpression = if (!isComputedExpression && column.table == null) column.provenanceExpression else null
        PropertySource(
          propertyName = column.name,
          comment = column.comment,
          sourceTable = column.table?.name,
          sourceColumn = column.originalName.ifEmpty { null },
          // #238 8.1: this top-level path re-lexes selectItem.expression straight from queryText,
          // the SAME raw text the CTE path's own resolveNodeTreeProvenanceExpression extracts a
          // comment/cosmetic-whitespace-free expression from before returning it (see that
          // function's own KDoc) -- so this path must apply the SAME two normalizations to avoid
          // embedding comment text (or the cosmetic whitespace stripComments' own substitution can
          // leave behind) verbatim in generated KDoc, which a developer could paste back into
          // PostgreSQL only to have the comment swallow part of the expression.
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
    val mapping = columnLevelOverrides[tableName to columnName] ?: return null
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

/**
 * Every canonical Postgres base type name [TypeRepository.resolveBaseType] accepts, keyed by name
 * (after stripping a `pg_catalog.` qualification — see [TypeRepository.resolveBaseType]) and
 * mapped to the [SqlMappable] used for a plain (non-domain) column of that type.
 *
 * This is the single source of truth for "every type name resolveBaseType accepts": both
 * [TypeRepository.resolveBaseType] itself and [ColumnTypeMappingTest]'s domain-base-type-parity
 * sweep read from these exact keys, so a type added here without a matching [resolveJdbcTypeInfo]
 * entry fails that sweep immediately — see [resolveJdbcTypeInfo]'s KDoc for the invariant this
 * enforces between the two maps.
 *
 * Includes the `serial`/`smallserial`/`bigserial` pseudo-types even though Postgres rejects
 * `CREATE DOMAIN ... AS serial` outright (`type "serial" does not exist` — verified against a live
 * server; a domain's base is always a REAL registered `pg_type`, so `domain.baseType` can never
 * actually be one of these), and even though `resolveJdbcTypeInfo`'s only other callers
 * ([TypeRepository.buildUserConfiguredMappable]'s user-configured type mappings) also only ever
 * see the real, JDBC-reported type name, never a serial alias: keeping them out would make this
 * map a proper subset of `resolveBaseType`'s branches, silently reintroducing exactly the kind of
 * incomplete "should be identical" list this map exists to prevent.
 */
internal val BASE_TYPE_RESOLVERS: Map<String, (notNull: Boolean) -> SqlMappable> = mapOf(
  "smallserial" to { notNull: Boolean -> JdbcTypes.SHORT.decorateForNullable(notNull) },
  "serial2" to { notNull: Boolean -> JdbcTypes.SHORT.decorateForNullable(notNull) },
  "serial" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "serial4" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "bigserial" to { notNull: Boolean -> JdbcTypes.LONG.decorateForNullable(notNull) },
  "serial8" to { notNull: Boolean -> JdbcTypes.LONG.decorateForNullable(notNull) },

  "smallint" to { notNull: Boolean -> JdbcTypes.SHORT.decorateForNullable(notNull) },
  "int2" to { notNull: Boolean -> JdbcTypes.SHORT.decorateForNullable(notNull) },
  "integer" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "int" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "int4" to { notNull: Boolean -> JdbcTypes.INT.decorateForNullable(notNull) },
  "bigint" to { notNull: Boolean -> JdbcTypes.LONG.decorateForNullable(notNull) },
  "int8" to { notNull: Boolean -> JdbcTypes.LONG.decorateForNullable(notNull) },

  "real" to { notNull: Boolean -> JdbcTypes.FLOAT.decorateForNullable(notNull) },
  "float4" to { notNull: Boolean -> JdbcTypes.FLOAT.decorateForNullable(notNull) },
  "float" to { notNull: Boolean -> JdbcTypes.DOUBLE.decorateForNullable(notNull) },
  "double precision" to { notNull: Boolean -> JdbcTypes.DOUBLE.decorateForNullable(notNull) },
  "float8" to { notNull: Boolean -> JdbcTypes.DOUBLE.decorateForNullable(notNull) },
  "numeric" to { _: Boolean -> JdbcTypes.BIG_DECIMAL },

  "bool" to { notNull: Boolean -> JdbcTypes.BOOLEAN.decorateForNullable(notNull) },
  "boolean" to { notNull: Boolean -> JdbcTypes.BOOLEAN.decorateForNullable(notNull) },

  // Not JdbcTypes.STRING: pgjdbc rejects setString() for json and jsonb columns.
  "json" to { notNull: Boolean -> JsonSqlMappable(notNull) },
  "jsonb" to { notNull: Boolean -> JsonSqlMappable(notNull) },

  // Scalar oid maps to Blob: pgjdbc's setBlob() creates a Postgres large object and stores its
  // oid, the standard large-object convention. oid[] does not share this mapping (see
  // tryResolveStandardType) because an array of large-object handles has no coherent JDBC
  // semantics, and real-world oid[] columns hold plain catalog identifiers, not large objects.
  "oid" to { _: Boolean -> JdbcTypes.BLOB },
  "bytea" to { _: Boolean -> PostgresSupportedTypes.BYTE_ARRAY },

  "date" to { _: Boolean -> PostgresSupportedTypes.LOCAL_DATE },
  "time" to { _: Boolean -> PostgresSupportedTypes.LOCAL_TIME },
  "timetz" to { _: Boolean -> PostgresSupportedTypes.OFFSET_TIME },
  "timestamp" to { _: Boolean -> PostgresSupportedTypes.LOCAL_DATE_TIME },
  "timestamptz" to { notNull: Boolean -> InstantSqlMappable(notNull) },

  "text" to { _: Boolean -> JdbcTypes.STRING },
  "varchar" to { _: Boolean -> JdbcTypes.STRING },
  "bpchar" to { _: Boolean -> JdbcTypes.STRING },
  "string" to { _: Boolean -> JdbcTypes.STRING },

  "uuid" to { _: Boolean -> PostgresSupportedTypes.UUID },
)

/**
 * Canonicalizes a Postgres type name for use as the element type of
 * [java.sql.Connection.createArrayOf].
 *
 * The driver appends `[]` to this name and looks the result up in `pg_type`, so it must be a
 * canonical `pg_type` name. [TypeRepository.resolveBaseType] additionally accepts SQL spellings
 * (`integer`, `boolean`, `double precision`) and `pg_catalog.`-qualified names; without folding
 * those here, `postgresArrayElementTypeName("integer")` would return `"integer"` verbatim and
 * `createArrayOf` would fail with `Unable to find server array type for provided name {0}`, since
 * `pg_type` has no row named `integer` — only `int4`.
 *
 * Every branch below was verified against a live PostgreSQL 17 server via
 * `SELECT typname FROM pg_type WHERE oid = to_regtype(?)`: every alias here resolves to the
 * canonical name on its right-hand side, and every `pg_catalog.`-qualified spelling of an
 * ALREADY-canonical name (e.g. `pg_catalog.uuid`, `pg_catalog.timestamptz`) resolves to itself —
 * confirming the universal `removePrefix` below is sufficient for those without a dedicated
 * branch. `pg_catalog.boolean` and `pg_catalog.integer` do NOT resolve on a live server (`boolean`
 * and `integer` are SQL-standard keyword aliases recognized only unqualified, not as schema-
 * qualified `pg_catalog` names) — but that combination can never actually reach this function:
 * JDBC's `TYPE_NAME`/`getColumnTypeName` always report the canonical, unqualified name.
 *
 * `serial` and its variants need no entry: Postgres has no serial array type, so a serial column
 * can never reach the array path.
 */
internal fun postgresArrayElementTypeName(typeName: String): String =
  when (val canonical = typeName.removePrefix("pg_catalog.")) {
    "smallint" -> "int2"
    "integer", "int" -> "int4"
    "bigint" -> "int8"
    "real" -> "float4"
    "double precision", "float" -> "float8"
    "boolean" -> "bool"
    "string" -> "text"
    else -> canonical
  }

/**
 * Maps a Postgres base type name to its [JdbcTypeInfo], or returns `null` if unsupported.
 *
 * Every key in [BASE_TYPE_RESOLVERS] has an entry here — [ColumnTypeMappingTest]'s domain-base-
 * type-parity sweep asserts this directly, rather than relying on the two lists being hand-kept in
 * sync, so a domain over any base type [TypeRepository.resolveBaseType] itself supports (e.g.
 * `CREATE DOMAIN d AS timestamptz`) always resolves here too: [TypeRepository]'s domain resolution
 * chains through this function (see [TypeRepository.tryResolveDomainType] and
 * [domainKotlinBaseType][norm.generator.domainKotlinBaseType]), and its `error()` calls are
 * reachable only for a base type [TypeRepository.resolveBaseType] itself does not support either
 * (e.g. `xml`, `interval`, `money` — Postgres allows a domain over any of these, but Norm has never
 * mapped them to a Kotlin type as a plain column type, so the same limitation applies to a domain
 * built on one). That failure is intentional: a clear, immediate `error()` naming the unsupported
 * type is preferable to silently guessing a mapping for a type Norm has no tested behavior for.
 *
 * Every getter/setter/Kotlin-type combination below matches [BASE_TYPE_RESOLVERS]'s NON-domain
 * mapping for the same key exactly — see [JdbcTypeInfo.getterClassHint] and
 * [JdbcTypeInfo.convertOffsetDateTimeToInstant] for the cases (`java.time` types, `uuid`, and
 * `timestamptz` specifically) where matching the non-domain path requires more than a plain
 * `getX`/`setX` method pair, each verified against pgjdbc 42.7.13's source rather than assumed.
 */
internal fun resolveJdbcTypeInfo(baseTypeName: String): JdbcTypeInfo? = when (baseTypeName) {
  "smallserial", "serial2", "smallint", "int2" ->
    JdbcTypeInfo("getShort", "setShort", true, "SMALLINT", kotlinType = Short::class.asTypeName())
  "serial", "serial4", "integer", "int", "int4" ->
    JdbcTypeInfo("getInt", "setInt", true, "INTEGER", kotlinType = Int::class.asTypeName())
  "bigserial", "serial8", "bigint", "int8" ->
    JdbcTypeInfo("getLong", "setLong", true, "BIGINT", kotlinType = Long::class.asTypeName())
  "real", "float4" ->
    JdbcTypeInfo("getFloat", "setFloat", true, "REAL", kotlinType = Float::class.asTypeName())
  "float", "double precision", "float8" ->
    JdbcTypeInfo("getDouble", "setDouble", true, "DOUBLE", kotlinType = Double::class.asTypeName())
  "bool", "boolean" ->
    JdbcTypeInfo("getBoolean", "setBoolean", true, "BOOLEAN", kotlinType = Boolean::class.asTypeName())
  "numeric" ->
    JdbcTypeInfo("getBigDecimal", "setBigDecimal", false, "NUMERIC", kotlinType = BigDecimal::class.asTypeName())
  // json and jsonb require setObject(..., Types.OTHER): Postgres JDBC rejects setString() for both
  // in prepared statements, just as it does for enum columns. Keep in sync with JsonSqlMappable,
  // which defines the same binding for plain (adapterless) json and jsonb columns.
  "json", "jsonb" ->
    JdbcTypeInfo(
      "getString",
      "setObject",
      false,
      "OTHER",
      useSqlTypeHint = true,
      kotlinType = String::class.asTypeName(),
    )
  "text", "varchar", "bpchar", "string" ->
    JdbcTypeInfo("getString", "setString", false, "VARCHAR", kotlinType = String::class.asTypeName())
  // Matches JdbcTypes.BLOB, the non-domain scalar mapping for oid (see BASE_TYPE_RESOLVERS):
  // pgjdbc's getBlob()/setBlob() are plain named methods, needing no class-hint or Types constant.
  "oid" ->
    JdbcTypeInfo("getBlob", "setBlob", false, "BLOB", kotlinType = Blob::class.asTypeName())
  // Matches PostgresSupportedTypes.BYTE_ARRAY: java.sql.ResultSet.getBytes/PreparedStatement.setBytes
  // are plain named methods for bytea, needing no class-hint.
  "bytea" ->
    JdbcTypeInfo("getBytes", "setBytes", false, "BINARY", kotlinType = ByteArray::class.asTypeName())
  // Matches PostgresSupportedTypes.LOCAL_DATE/LOCAL_TIME/OFFSET_TIME/LOCAL_DATE_TIME: pgjdbc's
  // plain getObject(int) returns java.sql.Date/Time/Timestamp for these columns, NOT the java.time
  // type, so the read needs the class-qualified getObject(int, Class) overload (getterClassHint).
  // The write side needs no such qualification: PgPreparedStatement.setObject(int, Object) already
  // dispatches on the runtime type of a LocalDate/LocalTime/OffsetTime/LocalDateTime/OffsetDateTime
  // argument directly (verified against pgjdbc 42.7.13's source).
  "date" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "DATE",
      kotlinType = LocalDate::class.asTypeName(),
      getterClassHint = LocalDate::class.asClassName(),
    )
  "time" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "TIME",
      kotlinType = LocalTime::class.asTypeName(),
      getterClassHint = LocalTime::class.asClassName(),
    )
  "timetz" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "TIME_WITH_TIMEZONE",
      kotlinType = OffsetTime::class.asTypeName(),
      getterClassHint = OffsetTime::class.asClassName(),
    )
  "timestamp" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "TIMESTAMP",
      kotlinType = LocalDateTime::class.asTypeName(),
      getterClassHint = LocalDateTime::class.asClassName(),
    )
  // Matches InstantSqlMappable: the wire representation is OffsetDateTime (read via the
  // class-qualified getObject, written via plain setObject — both verified against pgjdbc's
  // source the same way as the other java.time entries above), but the Kotlin representation the
  // non-domain scalar path uses is Instant, via a `.toInstant()`/`OffsetDateTime.ofInstant(...)`
  // conversion — see JdbcTypeInfo.convertOffsetDateTimeToInstant's KDoc.
  "timestamptz" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "TIMESTAMP_WITH_TIMEZONE",
      kotlinType = Instant::class.asTypeName(),
      getterClassHint = OffsetDateTime::class.asClassName(),
      convertOffsetDateTimeToInstant = true,
    )
  // Matches PostgresSupportedTypes.UUID: java.sql.ResultSet.getObject(int) is declared to return
  // Object, so a bare getObject(index) call is statically Any in Kotlin regardless of what pgjdbc
  // returns at runtime — PgResultSet's internalGetObject does special-case the Postgres "uuid"
  // type by name and hands back a java.util.UUID instance (verified against pgjdbc 42.7.13's
  // source), but that's a runtime fact, not a static type, and ColumnAdapter<Application,
  // UUID>.decode requires a statically-typed UUID argument. The class-qualified
  // getObject(int, Class) overload (getterClassHint) fixes the static type; pgjdbc's
  // PgResultSet#getObject(int, Class<T>) explicitly special-cases `type == UUID.class` by
  // delegating to the same runtime read and casting, so this is safe.
  "uuid" ->
    JdbcTypeInfo(
      "getObject",
      "setObject",
      false,
      "OTHER",
      kotlinType = UUID::class.asTypeName(),
      getterClassHint = UUID::class.asClassName(),
    )
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
 * @param reservedWords The connected PostgreSQL server's reserved keywords, forwarded to
 *   [quoteSqlIdentifierIfNeeded] for each property's `` `table.column` `` source reference (#238
 *   10.1). Empty for a table projection, which never renders a source reference at all.
 */
internal fun TypeSpec.Builder.addClassKdoc(
  classComment: String,
  tableName: String?,
  properties: List<PropertySource>,
  sql: String = "",
  reservedWords: Set<String> = emptySet(),
) {
  val hasTableMapping = tableName != null
  // #238 9.1: KotlinPoet's own KDoc emission unconditionally rewrites "/*"/"*/" to "/&#42;"/"&#42;/"
  // inside EVERY KDoc block (CodeWriter.emit, kdoc branch) so a literal block comment can never
  // prematurely close the surrounding "/** ... */" comment -- necessary for the KDoc to compile at
  // all, but CommonMark never decodes an HTML entity reference INSIDE a fenced code block (the exact
  // construct the sql block below renders as), so once that rewrite happens there is no longer any
  // way to render sql back to its own byte-exact text. Declining the whole fenced block for a query
  // containing either sequence is the "correct or silent" choice -- see
  // containsUnescapableBlockCommentDelimiter's own KDoc, and markdownInlineCodeSpan for the identical
  // hazard on a single property's source-reference span.
  val canRenderSqlVerbatim = sql.isNotEmpty() && !containsUnescapableBlockCommentDelimiter(sql)
  // #238 10.2: a property whose OWN name cannot be rendered as a `@property` name token at all
  // (formatAsKdocPropertyReference returns `null` -- see its own KDoc) has no line to emit in the
  // first place, not merely a corrupted one -- so it is dropped here, before the property/name pair
  // is ever built, rather than emitted with a name that reads back as a DIFFERENT (mangled)
  // property than the one actually declared.
  val documentedProperties = properties.mapNotNull { property ->
    if (!property.hasDocumentation(hasTableMapping, reservedWords)) return@mapNotNull null
    val formattedName = property.propertyName.formatAsKdocPropertyReference() ?: return@mapNotNull null
    formattedName to property
  }
  if (classComment.isEmpty() && !hasTableMapping && !canRenderSqlVerbatim && documentedProperties.isEmpty()) return

  val kdoc = buildString {
    if (classComment.isNotEmpty()) {
      append(classComment)
    }
    if (hasTableMapping) {
      if (isNotEmpty()) append("\n\n")
      append("Maps to the `$tableName` table.")
    }
    if (canRenderSqlVerbatim) {
      if (isNotEmpty()) append("\n\n")
      // #238: a fixed 3-backtick fence breaks if sql ITSELF contains a run of 3+ backticks (e.g.
      // inside a string literal) -- a line matching or exceeding the fence's own length terminates
      // a CommonMark fenced code block early, same class of defect as sourceReference()'s inline
      // span below. A fence one backtick longer than any run already in sql can never be mistaken
      // for a closing fence.
      val fence = markdownFenceDelimiter(sql)
      append(fence).append("sql\n")
      append(sql.trim())
      append("\n").append(fence)
    }
    if (documentedProperties.isNotEmpty()) {
      if (isNotEmpty()) append("\n\n")
      for ((index, formattedNameAndProperty) in documentedProperties.withIndex()) {
        val (formattedName, property) = formattedNameAndProperty
        append("@property $formattedName ")
        if (property.comment.isNotEmpty()) {
          // #238 10.3: property.comment is arbitrary PostgreSQL comment prose, appended into the
          // SAME CommonMark paragraph as every OTHER property's own source-reference code span (no
          // blank line separates consecutive `@property` lines) -- so an unescaped, unpaired
          // backtick in ONE comment is free to pair with a backtick belonging to a LATER property's
          // span instead of its own, corrupting every span in between. Escaping it here (never
          // altering the comment TEXT PostgreSQL reports, only how CommonMark delimits it) keeps it
          // from ever being read as a code-span delimiter at all.
          append(escapeMarkdownBacktick(property.comment))
        }
        if (!hasTableMapping) {
          val source = property.sourceReference(reservedWords)
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
private fun PropertySource.hasDocumentation(hasTableMapping: Boolean, reservedWords: Set<String>): Boolean =
  comment.isNotEmpty() || (!hasTableMapping && sourceReference(reservedWords) != null)

/**
 * A regular Kotlin identifier: matched WITHOUT surrounding backticks in KDoc's `@property` tag.
 */
private val PLAIN_KOTLIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Formats a Kotlin property name for use as the name token in a KDoc `@property` tag.
 *
 * KDoc's `@property` tag takes exactly one name token before the description text begins, so a
 * property name containing a space or other non-identifier character (e.g. a quoted SQL column
 * name like `"My Col"`, generated as the Kotlin property `` `My Col` ``) must be wrapped in
 * backticks here too -- otherwise `@property My Col Some comment.` reads as a property literally
 * named `My`, with `Col Some comment.` swallowed into the description.
 *
 * Uses [wrapInBacktickDelimiter]'s same longest-run rule [markdownInlineCodeSpan] uses, rather than
 * a fixed single-backtick wrap (#238 9.5): a property name containing its OWN literal backtick
 * (only reachable via a source column name containing one, e.g. `` a`b ``) would otherwise close
 * the `@property` tag's span early, corrupting everything after it on the line -- the exact defect
 * class #238 8.3 already fixed for [sourceReference]'s own span.
 *
 * Returns `null` — decline, emit no `@property` line for this property at all — when [this] contains
 * a literal block-comment open or close delimiter ([containsUnescapableBlockCommentDelimiter]):
 * widening the backtick delimiter fixes the SPAN, but KotlinPoet's own KDoc emission still rewrites
 * the block-comment delimiter itself to an HTML entity inside it, same as [markdownInlineCodeSpan]
 * already declines for a source-reference span (#238 9.1) — so a name containing that delimiter
 * would otherwise render with the entity substituted in, a tag naming a DIFFERENT property than the
 * one actually declared (#238 10.2).
 *
 * This fixes only the KDoc SPAN; it does not and cannot fix the Kotlin PROPERTY declaration itself
 * (`` public val `a\`b`: ... ``), which is not valid Kotlin -- a backtick-quoted Kotlin identifier
 * cannot contain a backtick character, and there is no escape sequence for one inside a backtick
 * identifier at all. That is a distinct, pre-existing defect in how a column's raw database
 * identifier becomes a Kotlin property name (`JdbcAnalyzer.loadColumns`'s `columnName`,
 * `JdbcAnalyzer.buildResultColumns`'s `columnLabel`) shared by the rest of the naming pipeline's
 * unfixed identifiers (a block-comment delimiter, `.`, a literal newline) — sanitizing only the
 * [Column.name] used for Kotlin generation once caused a worse regression when the SAME field also
 * carries the identifier back into generated SQL and catalog lookups (`Column.name`/
 * `Catalog.findColumn`), so making the naming pipeline total is left for separate resolution, not
 * attempted here.
 */
private fun String.formatAsKdocPropertyReference(): String? = when {
  PLAIN_KOTLIN_IDENTIFIER.matches(this) -> this
  containsUnescapableBlockCommentDelimiter(this) -> null
  else -> wrapInBacktickDelimiter(this)
}

/**
 * A PostgreSQL identifier that never needs double-quoting when written back into SQL: starts with
 * a lowercase letter or underscore, followed by any number of lowercase letters, digits,
 * underscores, or dollar signs (Postgres's own `SAFE_IDENTIFIER` rule, matching what an
 * ALREADY-live-connected caller does in [JdbcAnalyzer.buildIdentifierQuoter] — this copy exists
 * because [TypeRepository] has no connection of its own to query, per this file's own doc comment
 * on why `TypeRepository` re-lexes rather than re-querying). Matching this pattern is necessary but
 * NOT sufficient — [quoteSqlIdentifierIfNeeded] additionally rejects a RESERVED word, which this
 * pattern alone cannot rule out (`order` and `user` both match it) (#238 10.1).
 */
private val SQL_UNQUOTED_IDENTIFIER = Regex("[a-z_][a-z0-9_\$]*")

/**
 * Double-quotes [identifier] exactly as PostgreSQL itself requires it to be written back into SQL
 * — doubling any embedded `"` per PostgreSQL's own quoted-identifier escape rule — unless BOTH
 * [SQL_UNQUOTED_IDENTIFIER] accepts it bare AND it is not one of [reservedWords].
 *
 * Without the [SQL_UNQUOTED_IDENTIFIER] half, a mixed-case or space-containing original column name
 * (`"Foo"`, `"My Col"`) was rendered into a `` `table.column` `` source reference as bare
 * `table.Foo`/`table.My Col` — text that reads back as PostgreSQL folding `Foo` to `foo` (a column
 * `Foo` never has), or as two unrelated tokens instead of one qualified reference — verified live:
 * `SELECT tq.Foo FROM tq` fails with `column tq.foo does not exist` (#238 9.3).
 *
 * Without the [reservedWords] half, a relation or column named after a PostgreSQL reserved word
 * (`order`, `user`) — which [SQL_UNQUOTED_IDENTIFIER] alone cannot distinguish from any other
 * all-lowercase identifier — was rendered bare too: `` `order.id` `` reads back as `SELECT order.id
 * FROM "order"`, which PostgreSQL rejects with `syntax error at or near "."`, since an unquoted
 * `order` is parsed as the reserved keyword, not a table reference (#238 10.1). [reservedWords] is
 * always the CONNECTED server's own live keyword set ([JdbcAnalyzer.fetchReservedWords]) — never a
 * hardcoded snapshot, which drifts as PostgreSQL's own reserved-word list changes across versions.
 */
private fun quoteSqlIdentifierIfNeeded(identifier: String, reservedWords: Set<String>): String =
  if (SQL_UNQUOTED_IDENTIFIER.matches(identifier) && identifier !in reservedWords) {
    identifier
  } else {
    "\"${identifier.replace("\"", "\"\"")}\""
  }

/**
 * Returns a source reference string for display in KDoc, or `null` if none is available (either
 * there is nothing to reference, or [markdownInlineCodeSpan] could not render it faithfully — see
 * that function's own KDoc for when that happens).
 *
 * - For columns from a table: `` `table."Column"` `` — each identifier individually quoted via
 *   [quoteSqlIdentifierIfNeeded] exactly as PostgreSQL itself would require it written back into
 *   SQL, so this can always be pasted into a query verbatim (#238 9.3, 10.1).
 * - For computed expressions: `` `COUNT(*)` ``
 *
 * @param reservedWords The connected server's reserved keywords, forwarded to
 *   [quoteSqlIdentifierIfNeeded] (#238 10.1).
 */
private fun PropertySource.sourceReference(reservedWords: Set<String>): String? = when {
  sourceTable != null -> {
    val qualifiedColumn = sourceColumn?.let { quoteSqlIdentifierIfNeeded(it, reservedWords) }.orEmpty()
    markdownInlineCodeSpan("${quoteSqlIdentifierIfNeeded(sourceTable, reservedWords)}.$qualifiedColumn")
  }
  expression.isNotEmpty() -> markdownInlineCodeSpan(expression)
  else -> null
}

/**
 * The longest run of consecutive backtick characters anywhere in [text], or `0` if [text] contains
 * none. Used by [markdownInlineCodeSpan] and [markdownFenceDelimiter] to pick a delimiter that can
 * never be mistaken for a same-length run already inside [text].
 */
private fun longestBacktickRun(text: String): Int {
  var longest = 0
  var current = 0
  for (character in text) {
    if (character == '`') {
      current++
      if (current > longest) longest = current
    } else {
      current = 0
    }
  }
  return longest
}

/**
 * Wraps [text] in a Markdown inline code span that renders back to EXACTLY [text] — never [text]
 * altered to fit the rendering (that is the same class of defect as the truncation #238 8.1
 * reverted) — or `null` if no inline code span can carry [text] faithfully at all.
 *
 * Two hazards, both #238 emission-site defects rather than resolution defects (the text handed in
 * here is already the developer's own complete, correct SQL):
 * - A run of backticks INSIDE [text] as long as the span's own delimiter would be read as the
 *   CLOSING delimiter, ending the span early (#238 8.3, `s || '\`'` truncating the rendered span).
 *   Fixed by using a delimiter one backtick longer than [text]'s own longest run
 *   ([longestBacktickRun]) — CommonMark's own rule for an unambiguous code span delimiter — with a
 *   single padding space on each side when [text] itself starts or ends with a backtick, so that
 *   character never sits directly against the delimiter.
 * - A raw newline INSIDE [text] (#238 8.2, `s || 'a\nb'` — a string literal containing a genuine
 *   newline). CommonMark folds any line ending inside an inline code span to a single space when
 *   rendering, which would silently turn that into `s || 'a b'`, a DIFFERENT value (`'a\nb' =
 *   'a b'` is `false`). No inline-span delimiter choice can fix this — the corruption happens
 *   during RENDERING, not parsing — so per the "correct or silent" invariant this declines rather
 *   than emit a value that reads back as something else. (A multi-line expression could in
 *   principle be rendered as a fenced block instead of an inline span, but a `@property` tag's
 *   description begins on the SAME line as its name token, and KDoc has no established convention
 *   for interleaving one there — declining is the only choice provable correct today.)
 * - A literal block-comment open or close delimiter INSIDE [text] (#238 9.1) — see
 *   [containsUnescapableBlockCommentDelimiter]'s own KDoc for why KotlinPoet's own KDoc emission
 *   makes this a third, un-fixable-by-delimiter-choice hazard exactly like the newline case above.
 */
internal fun markdownInlineCodeSpan(text: String): String? {
  if (text.contains('\n') || text.contains('\r')) return null
  if (containsUnescapableBlockCommentDelimiter(text)) return null
  return wrapInBacktickDelimiter(text)
}

/**
 * Whether [text] contains `/*` or `*/` — either would be silently rewritten by KotlinPoet's own
 * KDoc emission (`CodeWriter.emit`, unconditionally, whenever writing inside a `/** ... */` block)
 * to `/&#42;`/`&#42;/`, so that a literal block comment inside generated KDoc text can never
 * prematurely close the surrounding KDoc comment — necessary for the generated `.kt` file to
 * compile at all. That rewrite is invisible to [CodeBlock.toString] (it happens only when the
 * containing [TypeSpec] is actually rendered, e.g. via `TypeSpec.toString()`/`FileSpec.writeTo`),
 * but it is NOT invisible to a developer reading the generated FILE: CommonMark never decodes an
 * HTML entity reference like `&#42;` back to `*` INSIDE an inline code span or a fenced code
 * block — only in ordinary prose text — so once KotlinPoet's rewrite happens, the exact two
 * constructs every source reference ([markdownInlineCodeSpan]) and the `sql` fenced block
 * ([TypeSpec.Builder.addClassKdoc]) render as, there is no delimiter choice left that can carry
 * [text] back to its own original value. Declining rather than emitting the rewritten text is this
 * file's "correct or silent" invariant applied to an emission-time hazard neither of those two
 * callers could see on its own (#238 9.1).
 */
internal fun containsUnescapableBlockCommentDelimiter(text: String): Boolean =
  text.contains("/*") || text.contains("*/")

/**
 * Backslash-escapes every literal backtick in [text] so it can never be read as a CommonMark inline-
 * code-span delimiter, without altering the character [text] itself renders as: CommonMark treats a
 * backslash immediately before an ASCII punctuation character as a literal escape everywhere EXCEPT
 * inside an already-open code span, code block, autolink, or raw HTML — none of which apply to
 * [text] here, since it is plain prose (a PostgreSQL column comment), never itself wrapped in a code
 * span.
 *
 * [TypeSpec.Builder.addClassKdoc] appends every property's own [PropertySource.comment] into ONE
 * continuous CommonMark paragraph shared by every `@property` line (consecutive lines with no blank
 * line between them never start a new paragraph), so an odd, unescaped backtick in ONE property's
 * comment is free to pair with a backtick belonging to a DIFFERENT property's own source-reference
 * span later in that same paragraph — silently turning every span in between from a real inline code
 * span into plain, undelimited text (#238 10.3). Escaping here removes the character from
 * delimiter-matching ENTIRELY, rather than attempting to widen or relocate a delimiter the way
 * [wrapInBacktickDelimiter] does for a span [text] itself is wrapped in — there is no single
 * "delimiter" to widen for backtick characters scattered through plain prose.
 *
 * Escapes [text]'s own literal backslashes FIRST, before escaping backticks (#238 11.2): escaping
 * only the backtick (`` text.replace("`", "\\`") ``) is defeated when [text] already contains a
 * backslash immediately before a backtick (e.g. a PostgreSQL comment `` 'weird \`' ``) — the naive
 * replacement produces `` \\` ``, which CommonMark reads as an ESCAPED backslash (a literal `\`)
 * followed by an UNESCAPED backtick, free to open a code span that pairs forward with a LATER
 * property's own source-reference span instead of closing here, corrupting every span in between —
 * the exact defect class this function exists to prevent. Escaping the backslash first means any
 * backslash the SECOND replacement introduces was never itself subject to the first.
 */
internal fun escapeMarkdownBacktick(text: String): String = text.replace("\\", "\\\\").replace("`", "\\`")

/**
 * Wraps [text] in a backtick-delimited span using a delimiter one backtick longer than [text]'s own
 * longest internal run ([longestBacktickRun]) — CommonMark's own rule for an unambiguous code span
 * delimiter — with a single padding space on each side when [text] itself starts or ends with a
 * backtick, so that character never sits directly against the delimiter. Shared by
 * [markdownInlineCodeSpan] (a `@property` tag's source-reference span) and
 * [formatAsKdocPropertyReference] (that same tag's NAME token) — both are backtick-delimited spans
 * subject to the identical #238 8.3 hazard, so both need the identical fix.
 */
private fun wrapInBacktickDelimiter(text: String): String {
  val delimiter = "`".repeat(longestBacktickRun(text) + 1)
  val needsPadding = text.startsWith("`") || text.endsWith("`")
  return if (needsPadding) "$delimiter $text $delimiter" else "$delimiter$text$delimiter"
}

/**
 * A backtick-fence delimiter (` ``` `, or longer) that can be used to open a Markdown fenced code
 * block containing [text] without [text] itself ever being able to supply a same-length or longer
 * run of backticks that CommonMark would read as the block's own closing fence (#238) — one
 * backtick longer than [text]'s own longest run ([longestBacktickRun]), and never shorter than the
 * conventional 3.
 */
internal fun markdownFenceDelimiter(text: String): String = "`".repeat(maxOf(3, longestBacktickRun(text) + 1))

/**
 * Collapses the cosmetic whitespace [stripComments]' own single-space substitution can leave
 * behind in an expression about to be embedded VERBATIM in generated KDoc — a comment sitting
 * directly after an opening parenthesis or before a closing one (`UPPER(/* x */a)` strips to
 * `UPPER( a)`) reads oddly there, even though inserting that space is exactly right for
 * [stripComments]' OWN purpose of never fusing two tokens a comment used to separate.
 *
 * Applied ONLY at the point [resolveNodeTreeProvenanceExpression] returns an expression for KDoc,
 * never inside [stripComments] itself — see that function's own KDoc for why it inserts the space
 * in the first place, a purpose this whitespace-removal step would otherwise defeat for any OTHER
 * caller relying on the fusion-prevention guarantee.
 *
 * Collapses any run of whitespace OUTSIDE a single-quoted literal, a dollar-quoted string, a
 * quoted identifier, or a comment to a single space, then removes a single such space immediately
 * after `(` or immediately before `)` — whitespace directly adjacent to a parenthesis is never
 * semantically significant in SQL, so removing it here can never change what the expression means.
 *
 * Every one of those four spans is walked over VERBATIM, byte for byte, via [skipLexicalToken] —
 * the SAME primitive [stripComments] itself uses to decide what it may touch — rather than a
 * second, independently-written scanner: a plain `Regex("\\s+")` collapse, or a blind
 * `.replace("( ", "(")`, cannot tell a cosmetic space from one that is part of the developer's own
 * SQL, and would rewrite `UPPER("My  Col")` (a quoted identifier with two literal spaces) to
 * `UPPER("My Col")` — a column name PostgreSQL then rejects outright (#238 P1) — or turn
 * `name || '( x )'` into `name || '(x)'`, silently changing what the literal STRING itself
 * contains, never merely how it is padded.
 */
internal fun collapseCosmeticWhitespace(text: String): String {
  val trimmed = text.trim()
  val builder = StringBuilder(trimmed.length)
  var i = 0
  while (i < trimmed.length) {
    val afterToken = skipLexicalToken(trimmed, i)
    if (afterToken != i) {
      builder.append(trimmed, i, afterToken)
      i = afterToken
      continue
    }
    val character = trimmed[i]
    if (!character.isWhitespace()) {
      builder.append(character)
      i++
      continue
    }
    var afterWhitespace = i
    while (afterWhitespace < trimmed.length && trimmed[afterWhitespace].isWhitespace()) afterWhitespace++
    val precededByOpenParenthesis = builder.isNotEmpty() && builder.last() == '('
    val followedByCloseParenthesis = afterWhitespace < trimmed.length && trimmed[afterWhitespace] == ')'
    if (!precededByOpenParenthesis && !followedByCloseParenthesis) builder.append(' ')
    i = afterWhitespace
  }
  return builder.toString()
}
