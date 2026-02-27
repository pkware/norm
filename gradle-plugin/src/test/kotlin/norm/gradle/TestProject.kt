package norm.gradle

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * Sets up a Gradle test project configured to use the Norm plugin with a specific scenario.
 *
 * The project uses Gradle's composite build feature to substitute the published `runtime` artifact
 * with the local project, enabling tests to run against unpublished changes.
 */
class TestProject(private val projectDir: Path, private val scenarioDirectory: Path) {

  val buildFile: Path = projectDir.resolve("build.gradle.kts")

  val generatedCodeDirectory: Path
    get() = projectDir.resolve("build").resolve(NormPlugin.NORM_GENERATED_CODE)

  /**
   * Sets up both settings.gradle.kts and build.gradle.kts for a standard scenario test.
   *
   * Reads optional `norm.properties` from the scenario directory for per-scenario configuration.
   * Supported properties:
   * - `generateCrud=true` — enables CRUD method generation.
   * - `typeMapping.type.<postgresType>=<kotlinType>:<adapterType>` — type-level override.
   * - `typeMapping.column.<table>.<column>=<kotlinType>:<adapterType>` — column-level override.
   *
   * When type mappings are present, user adapter source files from the scenario's `src/` directory
   * are copied into the test project's `src/main/kotlin/`.
   *
   * @param frameworks Optional set of framework names to enable. If empty, no frameworks are configured.
   */
  fun setup(frameworks: Set<String> = emptySet()) {
    setupSettingsOnly()

    val scenarioProperties = loadScenarioProperties()

    val frameworkImport = if (frameworks.isNotEmpty()) {
      "import norm.generator.Framework\n\n"
    } else {
      ""
    }

    val frameworksConfigBlock = if (frameworks.isNotEmpty()) {
      "frameworks.addAll(${frameworks.joinToString(", ") { "Framework.$it" }})"
    } else {
      ""
    }

    val generateCrud = scenarioProperties.getProperty("generateCrud", "false").toBoolean()
    val generateCrudBlock = "generateCrud = $generateCrud"

    val typeMappingsBlock = buildTypeMappingsBlock(scenarioProperties)

    val buildFileContent = """
      ${frameworkImport}plugins {
        kotlin("jvm")
        id("com.pkware.norm")
      }

      norm {
        databases {
          create("Test") {
            packageName = "${scenarioProperties.getProperty("packageName", "example")}"
            schemas.addAll("${scenarioDirectory.resolve("schema.sql").normalize().toAbsolutePath()}")
            queries.addAll("${scenarioDirectory.resolve("queries.sql").normalize().toAbsolutePath()}")
            $frameworksConfigBlock
            $generateCrudBlock
            $typeMappingsBlock
          }
        }
      }

      kotlin {
        compilerOptions {
          allWarningsAsErrors = true
        }
      }
    """.trimIndent()
    buildFile.writeText(buildFileContent)

    // Copy user source files from scenario's src/ directory into the test project
    copyUserSources()
  }

  /**
   * Loads scenario-specific configuration from `norm.properties` if it exists.
   */
  private fun loadScenarioProperties(): Properties {
    val propsFile = scenarioDirectory.resolve("norm.properties")
    return Properties().apply {
      if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
      }
    }
  }

  /**
   * Builds the `typeMappings { }` DSL block from scenario properties, or returns an empty string.
   */
  private fun buildTypeMappingsBlock(properties: Properties): String {
    val mappingLines = mutableListOf<String>()
    for ((key, value) in properties) {
      val keyStr = key.toString()
      val valueStr = value.toString()
      if (!keyStr.startsWith("typeMapping.")) continue
      val parts = valueStr.split(":")
      require(parts.size == 2) { "Invalid type mapping value: $valueStr" }
      val (kotlinType, adapterType) = parts

      if (keyStr.startsWith("typeMapping.type.")) {
        val postgresType = keyStr.removePrefix("typeMapping.type.")
        mappingLines.add("""type("$postgresType") mapTo "$kotlinType" using "$adapterType"""")
      } else if (keyStr.startsWith("typeMapping.column.")) {
        val remainder = keyStr.removePrefix("typeMapping.column.")
        val dotIndex = remainder.indexOf('.')
        require(dotIndex > 0) { "Invalid column mapping key: $keyStr" }
        val table = remainder.substring(0, dotIndex)
        val column = remainder.substring(dotIndex + 1)
        mappingLines.add("""column("$table", "$column") mapTo "$kotlinType" using "$adapterType"""")
      }
    }
    if (mappingLines.isEmpty()) return ""
    return buildString {
      appendLine("typeMappings {")
      for (line in mappingLines.sorted()) {
        appendLine("              $line")
      }
      append("            }")
    }
  }

  /**
   * Copies user-provided source files from the scenario's `src/` directory into the test project.
   *
   * These are files like adapter implementations that must be present for the generated code to compile.
   */
  private fun copyUserSources() {
    val scenarioSrcDir = scenarioDirectory.resolve("src")
    if (!scenarioSrcDir.exists()) return

    val targetDir = projectDir.resolve("src/main/kotlin")
    Files.walk(scenarioSrcDir).use { stream ->
      stream.filter { it.isRegularFile() }.forEach { sourceFile ->
        val relativePath = scenarioSrcDir.relativize(sourceFile)
        val targetFile = targetDir.resolve(relativePath)
        targetFile.parent.toFile().mkdirs()
        Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  /**
   * Sets up only settings.gradle.kts, for tests that need custom build.gradle.kts content.
   */
  fun setupSettingsOnly() {
    val includePath = ROOT_NORM_PROJECT_PATH.toString().replace('\\', '/')
    val settingsContent = """
      rootProject.name = "norm-test-project"

      // Include the parent Norm build to substitute runtime dependency with local project
      includeBuild("$includePath")

      pluginManagement {
        repositories {
          mavenCentral()
          gradlePluginPortal()
        }
      }

      dependencyResolutionManagement {
        repositories {
          mavenCentral()
        }
      }
    """.trimIndent()
    projectDir.resolve("settings.gradle.kts").writeText(settingsContent)
  }

  fun gradle(vararg tasks: String): GradleRunner = GradleRunner.create()
    .withProjectDir(projectDir.toFile())
    .withArguments(*tasks)
    .withPluginClasspath()
    .forwardOutput()

  companion object {
    val ROOT_NORM_PROJECT_PATH: Path = Path.of("..").normalize().toAbsolutePath()
  }
}
