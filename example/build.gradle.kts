@file:OptIn(KspExperimental::class)

import com.google.devtools.ksp.KspExperimental
import norm.generator.Framework

plugins {
  kotlin("jvm") version "2.2.21"
  alias(libs.plugins.ksp)
  alias(libs.plugins.norm)
}

dependencies {
  implementation(libs.postgresql)

  // Micronaut Core (required for DI and runtime)
  implementation(libs.micronaut.inject)

  // Micronaut Data JDBC
  implementation(libs.micronaut.data.jdbc)
  implementation(libs.micronaut.hikari)

  // KSP annotation processors - generate implementations at compile time
  ksp(libs.micronaut.inject.kotlin)
  ksp(libs.micronaut.data.processor)

  // Runtime dependencies
  runtimeOnly(libs.micronaut.runtime)
}

repositories {
  mavenCentral()
}

norm {
  databases {
    register("example") {
      packageName = "example"
      schemas.addAll("src/main/sql/schema.sql")
      queries.addAll("src/main/sql/queries.sql")
      frameworks = setOf(Framework.MICRONAUT_DATA_JDBC)
    }
  }
}

ksp {
  useKsp2 = false
}
