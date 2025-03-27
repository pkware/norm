package com.pkware.norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.wire.WireJsonAdapterFactory
import okio.Buffer
import plugin.File
import plugin.GenerateRequest
import plugin.GenerateResponse

private val NORM_DRIVER = ClassName("com.pkware.norm.runtime", "NormDriver")

/**
 * Invoked by sqlc during code execution.
 */
@OptIn(ExperimentalStdlibApi::class)
fun main(unused: Array<String>) {
  val moshi = Moshi.Builder()
    .add(WireJsonAdapterFactory())
    .build()
  val requestJsonAdapter = moshi.adapter<GenerateRequest>()
  // FIXME Remove the path
// 	val jsonExploreFile = Path("/Users/marius.volkhart/Documents/personal/postgres-kotlin-generator/demo/out.json")
  val request = GenerateRequest.ADAPTER.decode(System.`in`)
// 	Files.writeString(jsonExploreFile, requestJsonAdapter.toJson(request))

// 	val request = requestJsonAdapter.fromJson(Files.readString(jsonExploreFile))!!
  val pluginOptions = request.getPluginOptions(moshi)
  val catalog = request.catalog!!

  val generator = TypeRepository(pluginOptions.packageName, catalog)

  val resolvedQueries = request.queries.asSequence().map { SqlStatement(catalog, it, generator) }
  val interfaceCode = generateQueryInterface(resolvedQueries, "Queries")
  val classCode = generateQueryImplementation(resolvedQueries, ClassName(pluginOptions.packageName, "Queries"))

  val files = (sequenceOf(interfaceCode, classCode) + generator.requiredTypes).map {
    val fileSpec = FileSpec.builder(pluginOptions.packageName, "${it.name}.kt")
      .addType(it)
      .build()
    val result = Buffer()
    result.outputStream().writer().use(fileSpec::writeTo)
    File(pluginOptions.packageName + "/" + fileSpec.name, result.readByteString())
  }
    .toList()
  val response = GenerateResponse(files)
  GenerateResponse.ADAPTER.encode(System.out, response)
}

private fun generateQueryImplementation(queries: Sequence<SqlStatement>, interfaceType: TypeName): TypeSpec {
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

private fun generateQueryInterface(queries: Sequence<SqlStatement>, interfaceName: String): TypeSpec {
  val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)
    .addSuperinterface(ClassName("com.pkware.norm.runtime", "Transacter"))

  queries.forEach(interfaceBuilder::addSqlStatementInterfaceMethod)

  return interfaceBuilder.build()
}
