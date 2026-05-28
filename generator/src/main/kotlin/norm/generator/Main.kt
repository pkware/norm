package norm.generator

import com.squareup.kotlinpoet.AnnotationSpec
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

/**
 * Generates Kotlin models and query files for use with the Norm runtime.
 *
 * @param catalog The Postgres schema catalog.
 * @param queries The queries for which to generate code.
 * @param packageName The package in which to generate code.
 * @param frameworks The frameworks for which to generate DI annotations and connection providers.
 * @param typeMappings User-configured type/column overrides. Type-level overrides suppress
 *   auto-generation of the matching enum or domain.
 * @return The generated files. File names include the package hierarchy.
 */
public fun generateCode(
  catalog: Catalog,
  queries: List<Query>,
  packageName: String,
  frameworks: Set<Framework>,
  typeMappings: List<TypeMapping> = emptyList(),
): List<GeneratedFile> {
  val generator = TypeRepository(packageName, catalog, typeMappings)

  val resolvedQueries = queries.map { SqlStatement(catalog, it, generator) }
  val queriesInterface = ClassName(packageName, "Queries")
  val interfaceCode = generateQueryInterface(resolvedQueries, "Queries")

  // Type-level overrides suppress auto-generation for the overridden type.
  val typeOverridePostgresTypes = typeMappings.filter { it.isTypeLevel }.map { it.postgresType }.toSet()

  // Build enum + adapter TypeSpecs for all enums discovered during query resolution.
  // discoveredEnums is populated as a side effect of resolving column types above.
  val enumTypeSpecs = generator.discoveredEnums
    .filter { it.name !in typeOverridePostgresTypes }
    .sortedBy { it.name }
    .flatMap { enumDefinition ->
      listOf(
        buildEnumTypeSpec(enumDefinition, packageName),
        buildAdapterTypeSpec(enumDefinition, packageName, frameworks),
      )
    }

  // Build value class + adapter TypeSpecs for all domains discovered during query resolution.
  val domainTypeSpecs = generator.discoveredDomains
    .filter { it.name !in typeOverridePostgresTypes }
    .sortedBy { it.name }
    .flatMap { domain ->
      listOf(
        buildDomainValueClassTypeSpec(domain, packageName),
        buildDomainAdapterTypeSpec(domain, packageName, frameworks),
      )
    }

  val classCode =
    generateQueryImplementation(
      resolvedQueries,
      queriesInterface,
      frameworks,
      generator.discoveredEnums,
      generator.discoveredDomains,
      packageName,
      typeMappings,
      typeOverridePostgresTypes,
      catalog,
    )
  val connectionProviders = generateConnectionProviders(packageName, frameworks)

  val typeSpecFiles = (
    sequenceOf(
      interfaceCode,
      classCode,
    ) + generator.requiredTypes + enumTypeSpecs + domainTypeSpecs
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

private fun generateQueryImplementation(
  queries: List<SqlStatement>,
  interfaceType: ClassName,
  frameworks: Set<Framework>,
  discoveredEnums: Set<Enum>,
  discoveredDomains: Set<Domain>,
  packageName: String,
  typeMappings: List<TypeMapping>,
  typeOverridePostgresTypes: Set<String>,
  catalog: Catalog,
): TypeSpec {
  val constructorBuilder = FunSpec.constructorBuilder()
    .addParameter("connectionProvider", CONNECTION_PROVIDER)

  val classBuilder = TypeSpec.classBuilder("PostgresQueries")
    .addSuperinterface(interfaceType)

  if (frameworks.isEmpty()) {
    // Frameworks provide their own transaction mechanism.
    // Only generate support for Norm's transaction API if not using a framework.
    classBuilder.superclass(REAL_TRANSACTABLE)
    classBuilder.addSuperclassConstructorParameter("connectionProvider")
  }

  // Adapter parameters come in two groups:
  // 1. User-configured adapters (no default) — must come first in the constructor
  // 2. Auto-generated adapters (with default) — come after
  data class AdapterParam(val propertyName: String, val adapterType: TypeName, val defaultClass: ClassName?)

  // User-configured adapter params (no default value → must come first)
  val userAdapterParams = typeMappings.map { mapping ->
    val applicationTypeName = parseTypeName(mapping.kotlinType)
    val databaseTypeName = resolveWireTypeName(mapping, catalog)
    AdapterParam(
      userAdapterPropertyName(mapping),
      COLUMN_ADAPTER.parameterizedBy(applicationTypeName, databaseTypeName),
      null,
    )
  }.distinctBy { it.propertyName }.sortedBy { it.propertyName }

  // Auto-generated adapter params (with default → come after)
  val autoAdapterParams = buildList {
    for (enumDefinition in discoveredEnums) {
      if (enumDefinition.name in typeOverridePostgresTypes) continue
      val enumClassName = ClassName(packageName, enumDefinition.name.snakeToCamelCase().titleCase())
      add(
        AdapterParam(
          adapterPropertyName(enumDefinition),
          COLUMN_ADAPTER.parameterizedBy(enumClassName, String::class.asTypeName()),
          adapterClassName(enumDefinition, packageName),
        ),
      )
    }
    for (domain in discoveredDomains) {
      if (domain.name in typeOverridePostgresTypes) continue
      val valueClassName = domainValueClassName(domain, packageName)
      val baseKotlinType = domainKotlinBaseType(domain.baseType)
      add(
        AdapterParam(
          domainAdapterPropertyName(domain),
          COLUMN_ADAPTER.parameterizedBy(valueClassName, baseKotlinType),
          domainAdapterClassName(domain, packageName),
        ),
      )
    }
  }.sortedBy { it.propertyName }

  for (param in userAdapterParams + autoAdapterParams) {
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

/**
 * Adds framework-specific dependency injection annotations to generated classes.
 *
 * - Micronaut: `@Singleton` + `@Requires(missingBeans = [beanType])` — auto-registers the class as a bean,
 *   but steps aside if the user provides their own implementation.
 * - Spring: `@Component` — auto-registers via component scanning.
 *
 * @param classBuilder The class to annotate.
 * @param frameworks The frameworks for which to generate annotations.
 * @param missingBeanType The type to check in `@Requires(missingBeans)`. When this type is already
 *   present in the DI container, the generated bean is skipped.
 */
private fun addDependencyInjectionAnnotations(
  classBuilder: TypeSpec.Builder,
  frameworks: Set<Framework>,
  missingBeanType: TypeName,
) {
  for (framework in frameworks) {
    when (framework) {
      Framework.MICRONAUT_DATA -> {
        classBuilder.addAnnotation(JAKARTA_SINGLETON)
        classBuilder.addAnnotation(
          AnnotationSpec.builder(MICRONAUT_REQUIRES)
            .addMember("missingBeans = [%T::class]", missingBeanType)
            .build(),
        )
        classBuilder.addAnnotation(
          AnnotationSpec.builder(MICRONAUT_REQUIRES)
            .addMember("beans = [%T::class]", JAVAX_DATASOURCE)
            .build(),
        )
      }
      Framework.SPRING_DATA -> classBuilder.addAnnotation(SPRING_COMPONENT)
    }
  }
}

private fun generateQueryInterface(queries: List<SqlStatement>, interfaceName: String): TypeSpec {
  val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)

  queries.forEach(interfaceBuilder::addSqlStatementInterfaceMethod)

  return interfaceBuilder.build()
}

private const val PACKAGE_PLACEHOLDER = "packages.placeholder"

/**
 * Generates framework-specific [ConnectionProvider] implementations from template resources.
 *
 * When a DI framework is configured, users need a [ConnectionProvider] that bridges the framework's
 * connection management to Norm. Rather than requiring users to write this boilerplate, we generate it.
 *
 * These implementations are static (no per-schema variation), so they're shipped as plain `.kt` template
 * files with a package placeholder, rather than using KotlinPoet.
 *
 * - Micronaut: Uses `ConnectionOperations<Connection>` to participate in `@Transactional` scopes.
 * - Spring: Uses `DataSourceUtils` to participate in `@Transactional` scopes.
 *
 * @return A list of [GeneratedFile]s to include in the generated output. Empty when no DI frameworks are configured.
 */
private fun generateConnectionProviders(packageName: String, frameworks: Set<Framework>): List<GeneratedFile> =
  frameworks.map { framework ->
    when (framework) {
      Framework.MICRONAUT_DATA -> loadTemplate(packageName, "MicronautConnectionProvider")
      Framework.SPRING_DATA -> loadTemplate(packageName, "SpringConnectionProvider")
    }
  }

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
 * - Type-level: `${postgresType}Adapter` (e.g., `"jsonb"` → `"jsonbAdapter"`, `"mood"` → `"moodAdapter"`)
 * - Column-level: `${table}${Column}Adapter` (e.g., `users.metadata` → `"usersMetadataAdapter"`)
 */
internal fun userAdapterPropertyName(mapping: TypeMapping): String = if (mapping.isColumnLevel) {
  "${mapping.table!!.snakeToCamelCase()}${mapping.column!!.snakeToCamelCase().titleCase()}Adapter"
} else {
  "${mapping.postgresType.snakeToCamelCase()}Adapter"
}

/**
 * Resolves the Kotlin [TypeName] for the database (wire) side of a user-configured adapter.
 *
 * For type-level overrides, this maps the Postgres type directly.
 * For column-level overrides, this looks up the column's actual type from the catalog first.
 */
private fun resolveWireTypeName(mapping: TypeMapping, catalog: Catalog): TypeName {
  val postgresType = if (mapping.isColumnLevel) {
    resolveColumnPostgresType(catalog, mapping.table!!, mapping.column!!)
  } else {
    mapping.postgresType
  }
  return resolveWireKotlinType(postgresType, catalog)
}

/**
 * Looks up a column's Postgres type name from the catalog.
 */
private fun resolveColumnPostgresType(catalog: Catalog, table: String, column: String): String =
  catalog.schemas.flatMap { it.tables }
    .firstOrNull { it.rel.name == table }
    ?.columns?.firstOrNull { it.name == column }
    ?.type?.name
    ?: error("Column '$table.$column' not found in catalog")

/**
 * Maps a Postgres type name to the Kotlin type that JDBC delivers it as (the wire type).
 *
 * - Enums → `String` (JDBC delivers enum values as strings)
 * - Domains → chains to the domain's base type
 * - Standard types → uses [wireKotlinType]
 */
private fun resolveWireKotlinType(postgresType: String, catalog: Catalog): TypeName {
  // Check if it's an enum
  val isEnum = catalog.schemas.flatMap { it.enums }.any { it.name == postgresType }
  if (isEnum) return String::class.asTypeName()

  // Check if it's a domain — chain to base type
  val domain = catalog.schemas.flatMap { it.domains }.firstOrNull { it.name == postgresType }
  if (domain != null) return resolveWireKotlinType(domain.baseType, catalog)

  // Standard type
  return wireKotlinType(postgresType)
}

/**
 * Maps a Postgres base type name to the Kotlin type that JDBC delivers it as.
 *
 * Delegates to [resolveJdbcTypeInfo] as the single source of truth for type mappings.
 * This is a superset of [domainKotlinBaseType] — it also covers types like `jsonb`
 * which are valid adapter wire types but not valid domain bases.
 */
private fun wireKotlinType(postgresType: String): TypeName = resolveJdbcTypeInfo(postgresType)?.kotlinType
  ?: error(
    "Postgres type '$postgresType' cannot be used as the wire type for a custom adapter. " +
      "Supported wire types: text, varchar, bpchar, jsonb, int2, int4, int8, float4, float8, bool, numeric.",
  )
