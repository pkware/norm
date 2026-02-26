package norm.generator

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import plugin.Catalog
import plugin.Column
import plugin.File
import plugin.Query

private val NORM_DRIVER = ClassName(RUNTIME_PACKAGE, "NormDriver")
private val CONNECTION_PROVIDER = ClassName(RUNTIME_PACKAGE, "ConnectionProvider")

private val JAKARTA_SINGLETON = ClassName("jakarta.inject", "Singleton")
private val MICRONAUT_REQUIRES = ClassName("io.micronaut.context.annotation", "Requires")
private val SPRING_COMPONENT = ClassName("org.springframework.stereotype", "Component")

/**
 * Generates Kotlin models and query files for use with the Norm runtime.
 *
 * @param catalog The Postgres schema catalog.
 * @param queries The queries for which to generate code.
 * @param packageName The package in which to generate code.
 * @param frameworks The framework for which to generate code.
 * @param frameworkSchemas Schemas for which to generate framework code.
 * @return The generated files. File names include the package hierarchy.
 */
public fun generateCode(
  catalog: Catalog,
  queries: List<Query>,
  packageName: String,
  frameworks: Set<Framework>,
  frameworkSchemas: Set<String>,
): List<File> {
  val generator = TypeRepository(packageName, catalog, frameworks)

  generateModelsForFrameworks(catalog, generator, frameworks, frameworkSchemas)

  val resolvedQueries = queries.map { SqlStatement(catalog, it, generator) }
  val queriesInterface = ClassName(packageName, "Queries")
  val interfaceCode = generateQueryInterface(resolvedQueries, "Queries")
  val classCode = generateQueryImplementation(resolvedQueries, queriesInterface, frameworks)
  val connectionProviders = generateConnectionProviders(packageName, frameworks)

  val typeSpecFiles = (sequenceOf(interfaceCode, classCode) + generator.requiredTypes).map {
    val fileSpec = FileSpec.builder(packageName, "${it.name}.kt")
      .addType(it)
      .build()
    val result = Buffer()
    result.outputStream().writer().use(fileSpec::writeTo)
    File(packageName.replace('.', '/') + "/" + fileSpec.name, result.readByteString())
  }
    .toList()
  return typeSpecFiles + connectionProviders
}

private fun generateModelsForFrameworks(
  catalog: Catalog,
  generator: TypeRepository,
  frameworks: Set<Framework>,
  frameworkSchemas: Set<String>,
) {
  if (frameworks.isEmpty()) return
  // If we're generating code for frameworks, generate projections for all tables of all schemas.
  // This way the framework(s) can use them in their native modeling (Spring Data repositories, Micronaut repositories, etc.)
  // TODO Do a regex-based filter and include the table name. That way stuff only gets generated for tables we care about.
  for (schema in catalog.schemas) {
    // If the filters are empty, the caller doesn't want any filtering.
    if (frameworkSchemas.isEmpty() || frameworkSchemas.contains(schema.name)) {
      for (table in schema.tables) {
        if (table.columns.any(Column::is_primary_key)) {
          generator.getTypeProjectionForTable(table)
        }
      }
    }
  }
}

private fun generateQueryImplementation(
  queries: List<SqlStatement>,
  interfaceType: ClassName,
  frameworks: Set<Framework>,
): TypeSpec {
  val classBuilder = TypeSpec.classBuilder("PostgresQueries")
    .addSuperinterface(interfaceType)
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("connectionProvider", CONNECTION_PROVIDER)
        .build(),
    )
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
      Framework.MICRONAUT_DATA_JDBC -> {
        classBuilder.addAnnotation(JAKARTA_SINGLETON)
        classBuilder.addAnnotation(
          AnnotationSpec.builder(MICRONAUT_REQUIRES)
            .addMember("missingBeans = [%T::class]", missingBeanType)
            .build(),
        )
      }
      Framework.SPRING_DATA_JDBC -> classBuilder.addAnnotation(SPRING_COMPONENT)
      Framework.ALL_TABLES -> continue
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
 * @return A list of [File]s to include in the generated output. Empty when no DI frameworks are configured.
 */
private fun generateConnectionProviders(packageName: String, frameworks: Set<Framework>): List<File> {
  val result = mutableListOf<File>()
  for (framework in frameworks) {
    when (framework) {
      Framework.MICRONAUT_DATA_JDBC -> result.add(loadTemplate(packageName, "MicronautConnectionProvider"))
      Framework.SPRING_DATA_JDBC -> result.add(loadTemplate(packageName, "SpringConnectionProvider"))
      Framework.ALL_TABLES -> continue
    }
  }
  return result
}

/**
 * Loads a `.kt.template` resource, substitutes the package name, and returns it as a [File].
 */
private fun loadTemplate(packageName: String, className: String): File {
  val resourcePath = "/norm/generator/$className.kt"
  val template = object {}.javaClass.getResourceAsStream(resourcePath)
    ?.bufferedReader()?.readText()
    ?: error("Template resource not found: $resourcePath")
  val contents = template.replace(PACKAGE_PLACEHOLDER, packageName)
  val path = packageName.replace('.', '/') + "/$className.kt"
  return File(path, contents.encodeUtf8())
}
