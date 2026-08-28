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
 */
internal class TypeRepository(
  private val packageName: String,
  private val catalog: Catalog,
  private val typeMappings: List<TypeMapping> = emptyList(),
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
        // For computed expressions (no source table and not a simple column reference), include the SQL
        // expression so it can appear in KDoc. Simple column references (e.g. crosstab output columns)
        // are excluded because echoing the column name back adds no value.
        val isComputedExpression =
          column.table == null && selectItem != null && selectItem.columnName == null && !hasSetOperation
        // A plain reference into a CTE's output (column.table == null, but the outer item IS a
        // simple column reference) can still be expression-derived one level down, inside the CTE
        // body -- column.provenanceExpression was already resolved from the query's own parsed node
        // tree, cross-validated against this same queryText, during analysis (see
        // ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody and
        // resolveNodeTreeProvenanceExpression for the shape it resolves and every case it
        // deliberately punts on rather than guessing).
        val cteExpression = if (!isComputedExpression && column.table == null) column.provenanceExpression else null
        PropertySource(
          propertyName = column.name,
          comment = column.comment,
          sourceTable = column.table?.name,
          sourceColumn = column.originalName.ifEmpty { null },
          expression = if (isComputedExpression) selectItem.expression else cteExpression ?: "",
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
        append("@property ${property.propertyName.formatAsKdocPropertyReference()} ")
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
 */
private fun String.formatAsKdocPropertyReference(): String =
  if (PLAIN_KOTLIN_IDENTIFIER.matches(this)) this else "`$this`"

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
