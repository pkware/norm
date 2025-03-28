package com.pkware.norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import okio.Buffer
import plugin.Catalog
import plugin.File
import plugin.Query

private val NORM_DRIVER = ClassName("com.pkware.norm.runtime", "NormDriver")

/**
 * Generates Kotlin models and query files for use with the NORM runtime.
 *
 * @param catalog The Postgres schema catalog.
 * @param queries The queries for which to generate code.
 * @param packageName The package in which to generate code.
 * @return The generated files. File names include the package hierarchy.
 */
public fun generateCode(catalog: Catalog, queries: List<Query>, packageName: String): List<File> {
  val generator = TypeRepository(packageName, catalog)

  val resolvedQueries = queries.map { SqlStatement(catalog, it, generator) }
  val interfaceCode = generateQueryInterface(resolvedQueries, "Queries")
  val classCode = generateQueryImplementation(resolvedQueries, ClassName(packageName, "Queries"))

  val files = (sequenceOf(interfaceCode, classCode) + generator.requiredTypes).map {
    val fileSpec = FileSpec.builder(packageName, "${it.name}.kt")
      .addType(it)
      .build()
    val result = Buffer()
    result.outputStream().writer().use(fileSpec::writeTo)
    File(packageName + "/" + fileSpec.name, result.readByteString())
  }
    .toList()
  return files
}

private fun generateQueryImplementation(queries: List<SqlStatement>, interfaceType: TypeName): TypeSpec {
  val classBuilder = TypeSpec.classBuilder("PostgresQueries")
    .addSuperinterface(interfaceType)
    .superclass(ClassName("com.pkware.norm.runtime", "RealTransacter"))
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
    .addSuperinterface(ClassName("com.pkware.norm.runtime", "Transacter"))

  queries.forEach(interfaceBuilder::addSqlStatementInterfaceMethod)

  return interfaceBuilder.build()
}
