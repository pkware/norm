@file:OptIn(KspExperimental::class)

import com.google.devtools.ksp.KspExperimental
import io.gitlab.arturbosch.detekt.Detekt

plugins {
  `kotlin-conventions`
  alias(libs.plugins.ksp)
}

dependencies {
  // Norm runtime (required by generated code)
  implementation(projects.runtime)

  // PostgreSQL JDBC driver
  implementation(libs.postgresql)

  // Micronaut Core (required for DI and runtime)
  implementation(libs.micronaut.inject)

  // Micronaut Data JDBC
  implementation(libs.micronaut.data.jdbc)
  implementation(libs.micronaut.hikari)

  // KSP annotation processors - generate implementations at compile time
  ksp(libs.micronaut.inject.kotlin)
  ksp(libs.micronaut.data.processor)

  // Micronaut test framework
  testImplementation(libs.micronaut.junit)
  // Runtime dependencies
  testRuntimeOnly(libs.micronaut.runtime)

  // Testcontainers for real Postgres in Docker
  testImplementation(libs.bundles.testcontainers)
}

// Include Norm-generated entities (with @MappedEntity annotations)
sourceSets {
  main {
    kotlin {
      srcDir(rootProject.file("test-scenarios-frameworks/all_tables_behavior/micronaut"))
    }
  }
}

ksp {
  useKsp2 = false
}

// Disable detekt for E2E test module - detekt 1.23.8 has compatibility issues with Kotlin 2.2
// See: https://detekt.dev/docs/introduction/compatibility/
tasks.withType<Detekt>().configureEach {
  enabled = false
}
