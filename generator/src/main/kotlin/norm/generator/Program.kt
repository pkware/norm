package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import plugin.GenerateRequest
import javax.sql.DataSource

internal class Program(private val generateRequest: GenerateRequest) {
  fun execute(): Result {
    // TODO inject the name through plugin settings
    val name = "Example"
    val `package` = "example"
    val queriesInterface = ClassName(`package`, "${name.capitalize()}Queries")
    val interfaceSpec = TypeSpec.interfaceBuilder(queriesInterface)
      .addSuperinterface(ClassName("norm", "Transaction"))
    val queriesImplementation = ClassName(`package`, "Jdbc${name}Queries")
    val implementationSpec = TypeSpec.classBuilder(queriesImplementation)
      .addSuperinterface(queriesInterface)
      .superclass(ClassName("norm", "JdbcQueries"))
      .addSuperclassConstructorParameter("dataSource")
      .primaryConstructor(
        FunSpec.constructorBuilder()
          .addParameter("dataSource", DataSource::class)
          .build(),
      )
    for (query in generateRequest.queries) {
      val function = FunSpec.builder(query.name)
        .build()
      interfaceSpec.addFunction(function)
    }

    val interfaceFile = FileSpec.builder(queriesInterface)
      .indent("  ")
      .addType(interfaceSpec.build())
      .build()
    val implementationFile = FileSpec.builder(queriesImplementation)
      .indent("  ")
      .addType(implementationSpec.build())
      .build()
    return Result(listOf(interfaceFile, implementationFile))
  }
}

internal data class Result(val files: List<FileSpec>)
