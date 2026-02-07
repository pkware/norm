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
  shaded(libs.wire.json)
  shaded(libs.moshi)

  implementation(kotlin("gradle-plugin"))

  // Add Testcontainers for database-backed analysis
  implementation(libs.testcontainers.postgresql)

  testImplementation(gradleTestKit())
}

kotlin {
  explicitApi()
}

// Shadow relocates Wire, Moshi, Okio, and KotlinPoet to prevent classloader conflicts when the
// consuming project also has Wire on its build classpath. Wire's ProtoAdapter resolves adapters
// via Class.forName() using the calling class's classloader, which fails in Gradle's isolated
// plugin classloader hierarchy without relocation.
tasks.shadowJar {
  configurations = listOf(shaded)
  relocate("com.squareup.wire", "com.pkware.norm.shaded.com.squareup.wire")
  relocate("com.squareup.moshi", "com.pkware.norm.shaded.com.squareup.moshi")
  relocate("com.squareup.kotlinpoet", "com.pkware.norm.shaded.com.squareup.kotlinpoet")
  relocate("okio", "com.pkware.norm.shaded.okio")
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
