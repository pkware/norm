package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

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
 *   Also thrown if two adapters share a property name, whether user-configured or generated for a
 *   referenced enum or domain.
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

  val adapterTypeSpecs = typeRepository.referencedAdapterTypes
    .sortedBy { it.postgresName }
    .flatMap { it.typeSpecs(frameworks) }

  val adapterParameters = typeRepository.adapterParameters()

  val classCode = generateQueryImplementation(resolvedQueries, queriesInterface, frameworks, adapterParameters)
  val connectionProviders = generateConnectionProviders(packageName, frameworks)

  val typeSpecFiles = (
    sequenceOf(
      interfaceCode,
      classCode,
    ) + typeRepository.requiredTypes + adapterTypeSpecs
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
