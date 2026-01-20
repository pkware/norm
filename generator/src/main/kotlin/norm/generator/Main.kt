package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import okio.Buffer
import plugin.Catalog
import plugin.Column
import plugin.File
import plugin.Query

private val NORM_DRIVER = ClassName(RUNTIME_PACKAGE, "NormDriver")

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
  val generator = TypeRepository(packageName, catalog, queries, frameworks)

  generateModelsForFrameworks(catalog, generator, frameworks, frameworkSchemas)

  val resolvedQueries = queries.map { SqlStatement(catalog, it, generator) }
  val interfaceCode = generateQueryInterface(resolvedQueries, "Queries")
  val classCode = generateQueryImplementation(resolvedQueries, ClassName(packageName, "Queries"))

  val files = (sequenceOf(interfaceCode, classCode) + generator.requiredTypes).map {
    val fileSpec = FileSpec.builder(packageName, "${it.name}.kt")
      .addType(it)
      .build()
    val result = Buffer()
    result.outputStream().writer().use(fileSpec::writeTo)
    File(packageName.replace('.', '/') + "/" + fileSpec.name, result.readByteString())
  }
    .toList()
  return files
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
        if (table.columns.any(Column::isPrimaryKey)) {
          generator.getTypeProjectionForTable(table)
        }

        // TODO Generate a Repository interface for each table
      }
    }
  }
}

private fun generateQueryImplementation(queries: List<SqlStatement>, interfaceType: TypeName): TypeSpec {
  val classBuilder = TypeSpec.classBuilder("PostgresQueries")
    .addSuperinterface(interfaceType)
    .superclass(ClassName(RUNTIME_PACKAGE, "RealTransacter"))
    .addSuperclassConstructorParameter("driver")
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("driver", NORM_DRIVER)
        .build(),
    )

  queries.forEach(classBuilder::addSqlStatementImplementationMethod)

  return classBuilder.build()
}

private fun generateQueryInterface(queries: List<SqlStatement>, interfaceName: String): TypeSpec {
  val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)
    .addSuperinterface(ClassName(RUNTIME_PACKAGE, "Transacter"))

  queries.forEach(interfaceBuilder::addSqlStatementInterfaceMethod)

  return interfaceBuilder.build()
}
