plugins {
  `kotlin-conventions`
  `kotlin-dsl`
  `java-gradle-plugin`
  `publish-conventions`
  alias(libs.plugins.shadow)
}

gradlePlugin {
  plugins {
    register("norm-plugin") {
      id = "com.pkware.norm"
      implementationClass = "norm.gradle.NormPlugin"
    }
  }
}

// Configuration for dependencies that Shadow will bundle into the plugin JAR.
// Extends compileClasspath and runtimeClasspath so the code compiles and TestKit tests work,
// but does NOT extend runtimeElements so these dependencies stay out of the published POM.
val shaded: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}
configurations.compileClasspath { extendsFrom(shaded) }
configurations.runtimeClasspath { extendsFrom(shaded) }
configurations.testCompileClasspath { extendsFrom(shaded) }
configurations.testRuntimeClasspath { extendsFrom(shaded) }

dependencies {
  shaded(projects.generator)
  // Testcontainers and docker-java are shaded to isolate them from Jackson versions that other
  // Gradle plugins (e.g., Micronaut) place on the buildscript classpath. Testcontainers' shaded
  // Jackson ObjectMapper fails to see @JsonValue annotations on docker-java model classes when a
  // non-shaded jackson-databind is also present, producing malformed JSON that Docker rejects.
  shaded(libs.testcontainers.postgresql)

  implementation(kotlin("gradle-plugin"))
  implementation(libs.postgresql)

  testImplementation(gradleTestKit())
}

kotlin {
  explicitApi()
}

// Generate a BuildConfig with the project version so the plugin can add the correct runtime
// dependency version at apply-time, rather than relying on a hardcoded constant.
val generateBuildConfig by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/buildconfig")
  val projectVersion = project.version.toString()
  inputs.property("version", projectVersion)
  outputs.dir(outputDir)
  doLast {
    val dir = outputDir.get().asFile.resolve("norm/gradle")
    dir.mkdirs()
    dir.resolve("BuildConfig.kt").writeText(
      """
      |package norm.gradle
      |
      |internal const val BUILD_VERSION: String = "$projectVersion"
      |
      """.trimMargin(),
    )
  }
}
sourceSets.main { kotlin.srcDir(generateBuildConfig) }

// Shadow relocates KotlinPoet and Testcontainers to prevent classloader conflicts in Gradle's
// isolated plugin classloader hierarchy.
tasks.shadowJar {
  configurations = listOf(shaded)
  relocate("com.squareup.kotlinpoet", "com.pkware.norm.shaded.com.squareup.kotlinpoet")
  // Testcontainers, docker-java, and Jackson annotations must be relocated together so that
  // Jackson's annotation introspector and the docker-java model classes share the same relocated
  // annotation package. Without this, a non-shaded jackson-databind from another Gradle plugin
  // (e.g., Micronaut) on the buildscript classpath interferes with Testcontainers' shaded
  // Jackson, causing @JsonValue annotations on docker-java model types to be ignored.
  relocate("org.testcontainers", "com.pkware.norm.shaded.org.testcontainers")
  relocate("com.github.dockerjava", "com.pkware.norm.shaded.com.github.dockerjava")
  relocate("com.fasterxml.jackson", "com.pkware.norm.shaded.com.fasterxml.jackson")
  relocate("org.apache.commons", "com.pkware.norm.shaded.org.apache.commons")
  relocate("org.rnorth", "com.pkware.norm.shaded.org.rnorth")
  // Rename META-INF/services files based on relocation rules so that ServiceLoader using the
  // relocated interface class (e.g., DockerClientProviderStrategy) can find its implementations.
  // Without this, the services file stays at its original name and ServiceLoader gets zero results.
  mergeServiceFiles()
  archiveClassifier = ""
}

tasks.jar {
  archiveClassifier = "plain"
}

// Publish the shadow JAR instead of the plain JAR. java-gradle-plugin's auto-publication uses
// components["java"], which reads artifacts from these outgoing configurations.
configurations.apiElements {
  outgoing.artifacts.clear()
  outgoing.artifact(tasks.shadowJar)
}
configurations.runtimeElements {
  outgoing.artifacts.clear()
  outgoing.artifact(tasks.shadowJar)
}

// Exclude golden file generation from regular test runs
tasks.test {
  useJUnitPlatform {
    excludeTags("generateGoldenFiles")
  }
}

tasks.register<Test>("generateGoldenFiles") {
  description = "Generates golden files for test scenarios"
  group = "verification"

  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath

  useJUnitPlatform {
    includeTags("generateGoldenFiles")
  }

  // Pass through scenario filter if provided
  systemProperty("scenario", project.findProperty("scenario") ?: "")
}
