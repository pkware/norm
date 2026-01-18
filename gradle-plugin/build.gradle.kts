plugins {
  `kotlin-conventions`
  `kotlin-dsl`
  `java-gradle-plugin`
  `publish-conventions`
}

gradlePlugin {
  plugins {
    register("norm-plugin") {
      id = "com.pkware.norm"
      implementationClass = "norm.gradle.NormPlugin"
    }
  }
}

dependencies {
  implementation(projects.generator)
  implementation(libs.wire.json)
  implementation(libs.moshi)
  implementation(kotlin("gradle-plugin"))

  // Add Testcontainers for database-backed analysis
  implementation(libs.testcontainers.postgresql)

  testImplementation(gradleTestKit())
}

kotlin {
  explicitApi()
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
