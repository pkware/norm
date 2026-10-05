package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName

private val NORM_DRIVER = ClassName(RUNTIME_PACKAGE, "NormDriver")
private val CONNECTION_PROVIDER = ClassName(RUNTIME_PACKAGE, "ConnectionProvider")
private val REAL_TRANSACTABLE = ClassName(RUNTIME_PACKAGE, "RealTransactable")
private val TRANSACTABLE = ClassName(RUNTIME_PACKAGE, "Transactable")

/**
 * Generates Kotlin models and query files for use with the Norm runtime.
 *
 * @param catalog The Postgres schema catalog.
 * @param queries The queries for which to generate code.
 * @param packageName The package in which to generate code.
 * @param frameworks The frameworks for which to generate DI annotations and connection providers.
 * @param reservedWords The connected PostgreSQL server's reserved keywords, from
 *   [JdbcAnalyzer.fetchReservedWords] — used when rendering a `` `table.column` `` KDoc source
 *   reference for a relation or column named after a reserved word (`order`, `user`), which
 *   PostgreSQL rejects with a syntax error unless quoted. Has no default because the set must come
 *   from the server this run targets.
 * @param typeMappings User-configured type/column overrides. Type-level overrides suppress
 *   auto-generation of the matching enum or domain. Duplicate entries are ignored.
 * @return The generated files. File names include the package hierarchy.
 * @throws IllegalStateException if two unequal [typeMappings] target the same Postgres type or column.
 *   Also thrown if an adapter property name is shared by two [typeMappings] or by a mapping and the
 *   auto-generated adapter of a referenced enum or domain.
 */
@Throws(IllegalStateException::class)
public fun generateCode(
  catalog: Catalog,
  queries: List<Query>,
  packageName: String,
  frameworks: Set<Framework>,
  reservedWords: Set<String>,
  typeMappings: List<TypeMapping> = emptyList(),
): List<GeneratedFile> {
  val typeRepository = TypeRepository(packageName, catalog, typeMappings, reservedWords)

  val resolvedQueries = queries.map { SqlStatement(catalog, it, typeRepository) }
  val queriesInterface = ClassName(packageName, "Queries")
  val interfaceCode = generateQueryInterface(resolvedQueries, "Queries", frameworks)

  // Type-level overrides suppress auto-generation for the overridden type.
  val typeOverridePostgresTypes = typeMappings.filterIsInstance<TypeMapping.ByType>().map { it.postgresType }.toSet()

  // Build enum + adapter TypeSpecs for all enums discovered during query resolution.
  val enumTypeSpecs = typeRepository.discoveredEnums
    .filter { it.name !in typeOverridePostgresTypes }
    .sortedBy { it.name }
    .flatMap { enumDefinition ->
      listOf(
        buildEnumTypeSpec(enumDefinition, packageName),
        buildAdapterTypeSpec(enumDefinition, packageName, frameworks),
      )
    }

  // Build value class + adapter TypeSpecs for all domains discovered during query resolution.
  val domainTypeSpecs = typeRepository.discoveredDomains
    .filter { it.name !in typeOverridePostgresTypes }
    .sortedBy { it.name }
    .flatMap { domain ->
      listOf(
        buildDomainValueClassTypeSpec(domain, packageName),
        buildDomainAdapterTypeSpec(domain, packageName, frameworks),
      )
    }

  val adapterParameters =
    adapterParameters(typeRepository, typeMappings, catalog, packageName, typeOverridePostgresTypes)

  val classCode = generateQueryImplementation(resolvedQueries, queriesInterface, frameworks, adapterParameters)
  val connectionProviders = generateConnectionProviders(packageName, frameworks)

  val typeSpecFiles = (
    sequenceOf(
      interfaceCode,
      classCode,
    ) + typeRepository.requiredTypes + enumTypeSpecs + domainTypeSpecs
    ).map {
    val fileSpec = FileSpec.builder(packageName, "${it.name}.kt")
      .addType(it)
      .build()
    val contents = buildString { fileSpec.writeTo(this) }
    GeneratedFile(packageName.replace('.', '/') + "/" + fileSpec.name, contents)
  }
    .toList()
  return typeSpecFiles + connectionProviders
}

/**
 * A single adapter constructor parameter for the generated `PostgresQueries` implementation.
 *
 * @property propertyName The constructor parameter (and private property) name.
 * @property adapterType The `ColumnAdapter<Application, Database>` type of the parameter.
 * @property defaultClass The adapter class to instantiate as the parameter's default value
 *   (`= DefaultClass()`), or `null` for user-configured adapters, which have no default and must be
 *   supplied explicitly.
 */
private data class AdapterParameter(val propertyName: String, val adapterType: TypeName, val defaultClass: ClassName?)

/**
 * Computes the adapter constructor parameters for the generated `PostgresQueries` implementation.
 *
 * Adapter parameters come in two groups:
 * 1. User-configured adapters (no default) — must come first in the constructor.
 * 2. Auto-generated adapters, for enums and domains discovered while resolving column types (with a
 *    default) — come after.
 *
 * @param typeOverridePostgresTypes Postgres type names with a user-configured type-level override,
 *   already computed by the caller so it's derived from [typeMappings] exactly once.
 * @throws IllegalStateException if a user-configured adapter has the same property name as the
 *   auto-generated adapter of a discovered enum or domain.
 */
private fun adapterParameters(
  typeRepository: TypeRepository,
  typeMappings: List<TypeMapping>,
  catalog: Catalog,
  packageName: String,
  typeOverridePostgresTypes: Set<String>,
): List<AdapterParameter> {
  // User-configured adapter params (no default value → must come first)
  val userMappings = typeMappings.distinct()
  val userAdapterParams = userMappings.map { mapping ->
    val applicationTypeName = parseTypeName(mapping.kotlinType)
    val databaseTypeName = resolveWireTypeName(mapping, typeRepository, catalog)
    AdapterParameter(
      userAdapterPropertyName(mapping),
      COLUMN_ADAPTER.parameterizedBy(applicationTypeName, databaseTypeName),
      null,
    )
  }.sortedBy { it.propertyName }
  val userMappingsByPropertyName = userMappings.associateBy(::userAdapterPropertyName)

  fun checkNoUserCollision(propertyName: String, kind: String, postgresTypeName: String) {
    val userMapping = userMappingsByPropertyName[propertyName] ?: return
    error(
      "Mapping ${userMapping.describe()} generates the adapter parameter `$propertyName`, " +
        "which the generated adapter for $kind \"$postgresTypeName\" also uses.",
    )
  }

  // Auto-generated adapter params (with default → come after)
  val autoAdapterParams = buildList {
    for (enumDefinition in typeRepository.discoveredEnums) {
      if (enumDefinition.name in typeOverridePostgresTypes) continue
      checkNoUserCollision(adapterPropertyName(enumDefinition), "enum", enumDefinition.name)
      val enumClassName = enumClassName(enumDefinition, packageName)
      add(
        AdapterParameter(
          adapterPropertyName(enumDefinition),
          COLUMN_ADAPTER.parameterizedBy(enumClassName, String::class.asTypeName()),
          adapterClassName(enumDefinition, packageName),
        ),
      )
    }
    for (domain in typeRepository.discoveredDomains) {
      if (domain.name in typeOverridePostgresTypes) continue
      checkNoUserCollision(domainAdapterPropertyName(domain), "domain", domain.name)
      val valueClassName = domainValueClassName(domain, packageName)
      val wireKotlinType = domainKotlinWireType(domain.baseType)
      add(
        AdapterParameter(
          domainAdapterPropertyName(domain),
          COLUMN_ADAPTER.parameterizedBy(valueClassName, wireKotlinType),
          domainAdapterClassName(domain, packageName),
        ),
      )
    }
  }.sortedBy { it.propertyName }

  return userAdapterParams + autoAdapterParams
}

private fun generateQueryImplementation(
  queries: List<SqlStatement>,
  interfaceType: ClassName,
  frameworks: Set<Framework>,
  adapterParameters: List<AdapterParameter>,
): TypeSpec {
  val constructorBuilder = FunSpec.constructorBuilder()
    .addParameter("connectionProvider", CONNECTION_PROVIDER)

  val classBuilder = TypeSpec.classBuilder("PostgresQueries")
    .addSuperinterface(interfaceType)

  if (usesNormManagedTransactions(frameworks)) {
    // Norm-managed transactions: the concrete PostgresQueries extends RealTransactable so callers can
    // run transaction { } directly. Data-integration frameworks instead delegate to @Transactional.
    classBuilder.superclass(REAL_TRANSACTABLE)
    classBuilder.addSuperclassConstructorParameter("connectionProvider")
  }

  for (param in adapterParameters) {
    val paramBuilder = ParameterSpec.builder(param.propertyName, param.adapterType)
    if (param.defaultClass != null) {
      paramBuilder.defaultValue("%T()", param.defaultClass)
    }
    constructorBuilder.addParameter(paramBuilder.build())
    classBuilder.addProperty(
      PropertySpec.builder(param.propertyName, param.adapterType, KModifier.PRIVATE)
        .initializer(param.propertyName)
        .build(),
    )
  }

  classBuilder.primaryConstructor(constructorBuilder.build())
    .addProperty(
      PropertySpec.builder("driver", NORM_DRIVER, KModifier.PRIVATE)
        .initializer("%T(connectionProvider)", NORM_DRIVER)
        .build(),
    )

  addDependencyInjectionAnnotations(classBuilder, frameworks, interfaceType)

  queries.forEach(classBuilder::addSqlStatementImplementationMethod)

  return classBuilder.build()
}

private fun addDependencyInjectionAnnotations(
  classBuilder: TypeSpec.Builder,
  frameworks: Set<Framework>,
  missingBeanType: TypeName,
) {
  frameworks.flatMap { it.queriesAnnotations(missingBeanType) }.forEach { classBuilder.addAnnotation(it) }
}

/**
 * Whether transactions are managed by Norm's own `norm.Transactable` API (backed by a
 * `norm.TransactionalConnectionProvider`), without a framework's `@Transactional`.
 */
private fun usesNormManagedTransactions(frameworks: Set<Framework>): Boolean =
  frameworks.none { it.delegatesTransactions }

private fun generateQueryInterface(
  queries: List<SqlStatement>,
  interfaceName: String,
  frameworks: Set<Framework>,
): TypeSpec {
  val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)

  if (usesNormManagedTransactions(frameworks)) {
    // Norm-managed transactions: the concrete PostgresQueries extends RealTransactable, so the interface
    // can expose Norm-managed transactions directly. Data-integration frameworks route transactions
    // through @Transactional and the concrete class has no transaction methods, so declaring them here
    // would break the implements relationship.
    interfaceBuilder.addSuperinterface(TRANSACTABLE)
  }

  queries.forEach(interfaceBuilder::addSqlStatementInterfaceMethod)

  return interfaceBuilder.build()
}

private const val PACKAGE_PLACEHOLDER = "packages.placeholder"

/**
 * Loads one [ConnectionProvider] implementation per framework from its template resource.
 *
 * The implementations have no per-schema variation, so they ship as plain `.kt` templates with a package
 * placeholder and bypass KotlinPoet.
 *
 * @return the [GeneratedFile]s to include in the generated output. Empty when no frameworks are configured.
 */
private fun generateConnectionProviders(packageName: String, frameworks: Set<Framework>): List<GeneratedFile> =
  frameworks.map { loadTemplate(packageName, it.connectionProviderTemplate) }

/**
 * Loads a `.kt.template` resource, substitutes the package name, and returns it as a [GeneratedFile].
 */
private fun loadTemplate(packageName: String, className: String): GeneratedFile {
  val resourcePath = "/norm/generator/$className.kt"
  val template = object {}.javaClass.getResourceAsStream(resourcePath)
    ?.bufferedReader()?.readText()
    ?: error("Template resource not found: $resourcePath")
  val contents = template.replace(PACKAGE_PLACEHOLDER, packageName)
  val path = packageName.replace('.', '/') + "/$className.kt"
  return GeneratedFile(path, contents)
}

/**
 * Returns the adapter property name for a user-configured [TypeMapping].
 *
 * - [TypeMapping.ByType]: `${postgresType}Adapter` (e.g., `"jsonb"` → `"jsonbAdapter"`, `"mood"` → `"moodAdapter"`)
 * - [TypeMapping.ByColumn]: `${table}${Column}Adapter` (e.g., `users.metadata` → `"usersMetadataAdapter"`)
 */
internal fun userAdapterPropertyName(mapping: TypeMapping): String = when (mapping) {
  is TypeMapping.ByType -> "${mapping.postgresType.snakeToCamelCase()}Adapter"
  is TypeMapping.ByColumn ->
    "${mapping.table.snakeToCamelCase()}${mapping.column.snakeToCamelCase().titleCase()}Adapter"
}

/**
 * Resolves the Kotlin [TypeName] for the database (wire) side of a user-configured adapter.
 *
 * A [TypeMapping.ByType] mapping names the Postgres type directly. A [TypeMapping.ByColumn] mapping takes the
 * column's type from the catalog.
 */
private fun resolveWireTypeName(mapping: TypeMapping, typeRepository: TypeRepository, catalog: Catalog): TypeName {
  val postgresType = when (mapping) {
    is TypeMapping.ByType -> mapping.postgresType
    is TypeMapping.ByColumn -> resolveColumnPostgresType(catalog, mapping.table, mapping.column)
  }
  return typeRepository.resolveAdapterWireCodec(postgresType).kotlinType
}

/**
 * Looks up a column's Postgres type name from the catalog.
 *
 * [table] and [column] come from a user-configured [TypeMapping], spelled the way the user wrote
 * them in DDL, while the catalog's names came back from the server already truncated. Both are
 * truncated here so an override on an over-length name resolves in this lookup.
 */
private fun resolveColumnPostgresType(catalog: Catalog, table: String, column: String): String {
  val truncatedTable = truncateIdentifier(table)
  val truncatedColumn = truncateIdentifier(column)
  return catalog.findColumn(truncatedTable, truncatedColumn)?.type?.name
    ?: error("Column '$truncatedTable.$truncatedColumn' not found in catalog")
}
