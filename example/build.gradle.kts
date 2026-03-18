@file:OptIn(KspExperimental::class)

import com.google.devtools.ksp.KspExperimental
import norm.generator.Framework

plugins {
  kotlin("jvm") version "2.3.20"
  alias(libs.plugins.idea.ext)
  alias(libs.plugins.ksp)
  alias(libs.plugins.norm)
}

dependencies {
  implementation(libs.postgresql)

  // Micronaut Core (required for DI and runtime)
  implementation(libs.micronaut.inject)

  // Micronaut Data JDBC transaction + connection support (provides ConnectionOperations,
  // TransactionOperations, @Transactional AOP — without the ORM entity layer)
  implementation(libs.micronaut.data.tx.jdbc)

  // Micronaut connection pooling
  implementation(libs.micronaut.hikari)

  // KSP annotation processors - generate DI and @Transactional AOP implementations at compile time
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
      frameworks = setOf(Framework.MICRONAUT_DATA)
      typeMappings {
        // Map the Postgres `author_status` enum to idiomatic SCREAMING_SNAKE_CASE Kotlin.
        // Without this mapping, Norm would auto-generate an enum with lowercase constants
        // matching the database values (active, inactive, suspended).
        type("author_status") mapTo "example.AuthorStatus" using "example.AuthorStatusAdapter"
      }
    }
  }
}

ksp {
  useKsp2 = false
}
