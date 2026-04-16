@file:OptIn(KspExperimental::class)

import com.google.devtools.ksp.KspExperimental

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

  // Micronaut Data JDBC transaction + connection support (provides ConnectionOperations,
  // TransactionOperations, @Transactional AOP — without the ORM entity layer)
  implementation(libs.micronaut.data.tx.jdbc)

  // Micronaut connection pooling
  implementation(libs.micronaut.hikari)

  // KSP annotation processors - generate DI and @Transactional AOP proxy implementations at compile time
  ksp(libs.micronaut.inject.kotlin)
  ksp(libs.micronaut.data.processor)

  // Micronaut test framework
  testImplementation(libs.micronaut.junit)
  // Runtime dependencies
  testRuntimeOnly(libs.micronaut.runtime)

  // Testcontainers for real Postgres in Docker
  testImplementation(libs.bundles.testcontainers)
}

// Include Norm-generated code (with @Singleton DI annotations)
sourceSets {
  main {
    kotlin {
      srcDir(rootProject.file("test-scenarios-frameworks/comprehensive/micronaut"))
    }
  }
}

ksp {
  useKsp2 = false
}
